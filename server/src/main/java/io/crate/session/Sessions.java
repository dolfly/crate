/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.session;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Provider;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.transport.NodeDisconnectedException;
import org.jetbrains.annotations.Nullable;

import io.crate.analyze.Analyzer;
import io.crate.common.unit.TimeValue;
import io.crate.execution.engine.collect.stats.JobsLogs;
import io.crate.execution.jobs.transport.CancelRequest;
import io.crate.execution.jobs.transport.TransportCancelAction;
import io.crate.metadata.NodeContext;
import io.crate.metadata.settings.CoordinatorSessionSettings;
import io.crate.metadata.settings.session.SessionSetting;
import io.crate.metadata.settings.session.SessionSettingRegistry;
import io.crate.planner.DependencyCarrier;
import io.crate.planner.Planner;
import io.crate.planner.optimizer.LoadedRules;
import io.crate.protocols.postgres.ConnectionProperties;
import io.crate.protocols.postgres.KeyData;
import io.crate.role.Role;


@Singleton
public class Sessions {

    public static final String MEMORY_LIMIT_KEY = "memory.operation_limit";
    public static final String NODE_READ_ONLY_SETTING_KEY = "node.sql.read_only";
    public static final String STATEMENT_TIMEOUT_KEY = "statement_timeout";

    public static final Setting<Boolean> NODE_READ_ONLY_SETTING = Setting.boolSetting(
        NODE_READ_ONLY_SETTING_KEY,
        false,
        Setting.Property.NodeScope);

    public static final Setting<TimeValue> STATEMENT_TIMEOUT = Setting.timeSetting(
        STATEMENT_TIMEOUT_KEY,
        TimeValue.timeValueMillis(0),
        Setting.Property.Dynamic,
        Setting.Property.NodeScope,
        Setting.Property.Exposed
    );

    public static final Setting<Integer> STATEMENT_MAX_LENGTH = Setting.intSetting(
        "statement_max_length",
        262144,
        Setting.Property.NodeScope,
        Setting.Property.Exposed
    );

    /**
     * How often to retry on errors which might be temporary like shard/index-not-found/connection errors.
     * Currently not exposed (in sys.cluster settings) and not documented as it is for testing.
     **/
    public static final Setting<Integer> TEMP_ERROR_RETRY_COUNT = Setting.intSetting(
        "node.sql.num_temp_error_retries",
        3,
        Setting.Property.NodeScope
    );

    public static final Setting<Integer> MEMORY_LIMIT = Setting.intSetting(
        MEMORY_LIMIT_KEY, 0, Property.Dynamic, Property.NodeScope, Property.Exposed);


    private static final Logger LOGGER = LogManager.getLogger(Sessions.class);

    private final NodeContext nodeCtx;
    private final Analyzer analyzer;
    private final Planner planner;
    private final Provider<DependencyCarrier> executorProvider;
    private final JobsLogs jobsLogs;
    private final ClusterService clusterService;
    private final SessionSettingRegistry sessionSettingRegistry;
    private final boolean isReadOnly;
    private final AtomicInteger nextSessionId = new AtomicInteger();
    private final ConcurrentMap<Integer, Session> sessions = new ConcurrentHashMap<>();
    private final int tempErrorRetryCount;
    private final int statementMaxLength;

    private volatile boolean disabled;
    private volatile TimeValue defaultStatementTimeout;
    private volatile int memoryLimit;


    public Sessions(NodeContext nodeCtx,
                    Analyzer analyzer,
                    Planner planner,
                    Provider<DependencyCarrier> executorProvider,
                    JobsLogs jobsLogs,
                    Settings settings,
                    ClusterService clusterService,
                    SessionSettingRegistry sessionSettingRegistry) {
        this.nodeCtx = nodeCtx;
        this.analyzer = analyzer;
        this.planner = planner;
        this.executorProvider = executorProvider;
        this.jobsLogs = jobsLogs;
        this.clusterService = clusterService;
        this.isReadOnly = NODE_READ_ONLY_SETTING.get(settings);
        this.defaultStatementTimeout = STATEMENT_TIMEOUT.get(settings);
        this.memoryLimit = MEMORY_LIMIT.get(settings);
        this.tempErrorRetryCount = TEMP_ERROR_RETRY_COUNT.get(settings);
        this.statementMaxLength = STATEMENT_MAX_LENGTH.get(settings);
        ClusterSettings clusterSettings = clusterService.getClusterSettings();
        clusterSettings.addSettingsUpdateConsumer(STATEMENT_TIMEOUT, statementTimeout -> {
            this.defaultStatementTimeout = statementTimeout;
        });
        clusterSettings.addSettingsUpdateConsumer(MEMORY_LIMIT, newLimit -> {
            this.memoryLimit = newLimit;
        });
        this.sessionSettingRegistry = sessionSettingRegistry;
    }

    private Session newSession(@Nullable ConnectionProperties connectionProperties,
                               CoordinatorSessionSettings sessionSettings) {
        if (disabled) {
            throw new NodeDisconnectedException(clusterService.localNode(), "sql");
        }
        int sessionId = nextSessionId.incrementAndGet();
        Session session = new Session(
            sessionId,
            connectionProperties,
            analyzer,
            planner,
            jobsLogs,
            isReadOnly,
            executorProvider.get(),
            sessionSettings,
            () -> sessions.remove(sessionId),
            tempErrorRetryCount,
            statementMaxLength
        );
        sessions.put(sessionId, session);
        return session;
    }

    public Session newSession(ConnectionProperties connectionProperties,
                              @Nullable String defaultSchema,
                              Role authenticatedUser) {
        CoordinatorSessionSettings sessionSettings;
        if (defaultSchema == null) {
            sessionSettings = new CoordinatorSessionSettings(
                authenticatedUser,
                authenticatedUser,
                LoadedRules.INSTANCE.disabledRules()
            );
        } else {
            sessionSettings = new CoordinatorSessionSettings(
                authenticatedUser,
                authenticatedUser,
                LoadedRules.INSTANCE.disabledRules(),
                defaultSchema
            );
        }
        sessionSettings.statementTimeout(defaultStatementTimeout);
        sessionSettings.memoryLimit(memoryLimit);

        for (Map.Entry<String, Object> entry : authenticatedUser.sessionSettings().entrySet()) {
            SessionSetting<?> setting = sessionSettingRegistry.settings().get(entry.getKey());
            assert setting != null : "setting would be null only if it's been removed from the registry";
            setting.apply(sessionSettings, entry.getValue());
        }

        return newSession(connectionProperties, sessionSettings);
    }

    public Session newSystemSession() {
        return newSession(null, CoordinatorSessionSettings.systemDefaults());
    }

    /**
     * Disable processing of new sql statements.
     * {@link io.crate.cluster.gracefulstop.DecommissioningService} must call this while before starting to decommission.
     */
    public void disable() {
        disabled = true;
    }

    /**
     * (Re-)Enable processing of new sql statements
     * {@link io.crate.cluster.gracefulstop.DecommissioningService} must call this when decommissioning is aborted.
     */
    public void enable() {
        disabled = false;
    }

    public boolean isEnabled() {
        return !disabled;
    }

    /**
     * @return true if a session matches the keyData, false otherwise.
     */
    public boolean cancelLocally(KeyData keyData) {
        Session session = sessions.get(keyData.pid());
        if (session != null && session.secret() == keyData.secretKey()) {
            session.cancelCurrentJob();
            return true;
        } else {
            return false;
        }
    }

    public void cancel(KeyData keyData) {
        boolean cancelled = cancelLocally(keyData);
        if (!cancelled) {
            var client = executorProvider.get().client();
            CancelRequest request = new CancelRequest(keyData);
            client.execute(TransportCancelAction.ACTION, request).whenComplete((_, err) -> {
                if (err != null) {
                    LOGGER.error("Error during cancel broadcast", err);
                }
            });
        }
    }

    public Iterable<Session> getActive() {
        return sessions.values();
    }

    public Iterable<Cursor> getCursors(Role user) {
        return () -> sessions.values().stream()
            .filter(session ->
                nodeCtx.roles().hasALPrivileges(user)
                || session.sessionSettings().sessionUser().equals(user))
            .flatMap(session -> StreamSupport.stream(session.cursors.spliterator(), false))
            .iterator();
    }
}
