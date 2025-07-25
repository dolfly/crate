/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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

package io.crate.replication.logical.action;

import java.util.Locale;

import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateTaskExecutor;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.RelationMetadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import io.crate.exceptions.RelationUnknown;
import io.crate.execution.ddl.AbstractDDLTransportAction;
import io.crate.metadata.cluster.DDLClusterStateTaskExecutor;
import io.crate.replication.logical.exceptions.PublicationAlreadyExistsException;
import io.crate.replication.logical.metadata.Publication;
import io.crate.replication.logical.metadata.PublicationsMetadata;
import io.crate.role.Roles;

public class TransportCreatePublication extends AbstractDDLTransportAction<CreatePublicationRequest, AcknowledgedResponse> {

    public static final Action ACTION = new Action();
    private final Roles roles;

    public static class Action extends ActionType<AcknowledgedResponse> {
        private static final String NAME = "internal:crate:replication/logical/publication/create";

        private Action() {
            super(NAME);
        }
    }

    @Inject
    public TransportCreatePublication(TransportService transportService,
                                      ClusterService clusterService,
                                      ThreadPool threadPool,
                                      Roles roles) {
        super(ACTION.name(),
              transportService,
              clusterService,
              threadPool,
              CreatePublicationRequest::new,
              AcknowledgedResponse::new,
              AcknowledgedResponse::new,
              "create-publication");
        this.roles = roles;
    }

    @Override
    public ClusterStateTaskExecutor<CreatePublicationRequest> clusterStateTaskExecutor(CreatePublicationRequest request) {
        return new DDLClusterStateTaskExecutor<>() {
            @Override
            protected ClusterState execute(ClusterState currentState,
                                           CreatePublicationRequest request) throws Exception {
                Metadata currentMetadata = currentState.metadata();
                Metadata.Builder mdBuilder = Metadata.builder(currentMetadata);

                var oldMetadata = (PublicationsMetadata) mdBuilder.getCustom(PublicationsMetadata.TYPE);
                if (oldMetadata != null && oldMetadata.publications().containsKey(request.name())) {
                    throw new PublicationAlreadyExistsException(request.name());
                }

                // Ensure publication owner exists
                if (roles.findUser(request.owner()) == null) {
                    throw new IllegalStateException(
                        String.format(
                            Locale.ENGLISH, "Publication '%s' cannot be created as the user '%s' owning the publication has been dropped.",
                            request.name(),
                            request.owner()
                        )
                    );
                }

                // Ensure tables exists
                for (var relation : request.tables()) {
                    RelationMetadata.Table table = currentMetadata.getRelation(relation);
                    if (table == null) {
                        throw new RelationUnknown(relation);
                    }
                }

                // create a new instance of the metadata, to guarantee the cluster changed action.
                var newMetadata = PublicationsMetadata.newInstance(oldMetadata);
                newMetadata.publications().put(
                    request.name(),
                    new Publication(request.owner(), request.isForAllTables(), request.tables())
                );
                assert !newMetadata.equals(oldMetadata) : "must not be equal to guarantee the cluster change action";
                mdBuilder.putCustom(PublicationsMetadata.TYPE, newMetadata);

                return ClusterState.builder(currentState).metadata(mdBuilder).build();
            }
        };
    }

    @Override
    protected ClusterBlockException checkBlock(CreatePublicationRequest request,
                                               ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
    }
}
