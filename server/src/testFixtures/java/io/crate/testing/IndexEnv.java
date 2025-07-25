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

package io.crate.testing;


import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.QueryCache;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.env.ShardLock;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexModule;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.IndexService.IndexCreationContext;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AnalysisRegistry;
import org.elasticsearch.index.cache.query.DisabledQueryCache;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndicesQueryCache;
import org.elasticsearch.indices.analysis.AnalysisModule;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.test.IndexSettingsModule;
import org.elasticsearch.threadpool.ThreadPool;

import io.crate.expression.reference.doc.lucene.LuceneReferenceResolver;
import io.crate.lucene.CrateLuceneTestCase;
import io.crate.metadata.NodeContext;
import io.crate.metadata.doc.DocTableInfo;

public final class IndexEnv implements AutoCloseable {

    private final LuceneReferenceResolver luceneReferenceResolver;
    private final NodeEnvironment nodeEnvironment;
    private final QueryCache queryCache;
    private final IndexService indexService;
    private final IndexWriter writer;

    public IndexEnv(NodeContext nodeContext,
                    ThreadPool threadPool,
                    DocTableInfo table,
                    ClusterState clusterState,
                    Version indexVersion) throws IOException {
        Index index = clusterState.metadata().getIndex(table.ident(), List.of(), true, IndexMetadata::getIndex);
        assert index != null : "ClusterState must contain the index: " + table.ident().fqn();

        Path tempDir = CrateLuceneTestCase.createTempDir();
        Settings nodeSettings = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, indexVersion)
            .put("path.home", tempDir.toAbsolutePath())
            .build();
        Environment env = new Environment(nodeSettings, tempDir.resolve("config"));
        IndexSettings idxSettings = IndexSettingsModule.newIndexSettings(index, nodeSettings);
        AnalysisRegistry analysisRegistry = new AnalysisModule(env, Collections.emptyList()).getAnalysisRegistry();

        queryCache = DisabledQueryCache.instance();
        IndexModule indexModule = new IndexModule(idxSettings, analysisRegistry, List.of(), Collections.emptyMap());
        nodeEnvironment = new NodeEnvironment(Settings.EMPTY, env);
        luceneReferenceResolver = new LuceneReferenceResolver(
            List.of(),
            table.partitionedByColumns(),
            table.primaryKey(),
            indexVersion,
            table.isParentReferenceIgnored()
        );
        indexService = indexModule.newIndexService(
            nodeContext,
            IndexCreationContext.CREATE_INDEX,
            nodeEnvironment,
            new IndexService.ShardStoreDeleter() {
                @Override
                public void deleteShardStore(String reason, ShardLock lock, IndexSettings indexSettings) throws IOException {

                }

                @Override
                public void addPendingDelete(ShardId shardId, IndexSettings indexSettings) {

                }
            },
            new NoneCircuitBreakerService(),
            BigArrays.NON_RECYCLING_INSTANCE,
            threadPool,
            IndicesQueryCache.createCache(Settings.EMPTY),
            () -> table
        );
        IndexWriterConfig conf = new IndexWriterConfig(new StandardAnalyzer());
        writer = new IndexWriter(new ByteBuffersDirectory(), conf);
    }

    @Override
    public void close() throws Exception {
        indexService.close("stopping", true);
        writer.close();
        nodeEnvironment.close();
    }

    public QueryCache queryCache() {
        return queryCache;
    }

    public IndexWriter writer() {
        return writer;
    }

    public LuceneReferenceResolver luceneReferenceResolver() {
        return luceneReferenceResolver;
    }

    public IndexService indexService() {
        return indexService;
    }
}
