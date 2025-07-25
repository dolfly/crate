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

package io.crate.blob.v2;

import static io.crate.blob.v2.BlobIndex.isBlobIndex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.cluster.routing.ShardIterator;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.PathUtils;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsException;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.shard.IndexEventListener;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.IndexShardState;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.ShardNotFoundException;
import org.elasticsearch.indices.cluster.IndicesClusterStateService;
import org.jetbrains.annotations.Nullable;

/**
 * Manages the creation and deletion of BlobIndex and BlobShard instances.
 */
public class BlobIndicesService implements IndexEventListener {

    private static final Logger LOGGER = LogManager.getLogger(BlobIndicesService.class);

    public static final Setting<Boolean> SETTING_INDEX_BLOBS_ENABLED = Setting.boolSetting(
        "index.blobs.enabled", false, Setting.Property.IndexScope);
    public static final Setting<String> SETTING_INDEX_BLOBS_PATH = Setting.simpleString(
        "index.blobs.path", Setting.Property.IndexScope);
    public static final Setting<String> SETTING_BLOBS_PATH = Setting.simpleString(
        "blobs.path", Setting.Property.NodeScope);

    private final ClusterService clusterService;

    final Map<String, BlobIndex> indices = new ConcurrentHashMap<>();

    @Nullable
    private final Path globalBlobPath;

    public BlobIndicesService(Settings settings, ClusterService clusterService) {
        this.clusterService = clusterService;
        globalBlobPath = getGlobalBlobPath(settings);
    }

    @Nullable
    public static Path getGlobalBlobPath(Settings settings) {
        String customGlobalBlobPathSetting = SETTING_BLOBS_PATH.get(settings);
        if (Strings.isNullOrEmpty(customGlobalBlobPathSetting)) {
            return null;
        }
        Path globalBlobPath = PathUtils.get(customGlobalBlobPathSetting);
        ensureExistsAndWritable(globalBlobPath);
        return globalBlobPath;
    }


    @Override
    public void afterIndexCreated(IndexService indexService) {
        String indexName = indexService.index().getName();
        String indexUUID = indexService.index().getUUID();
        if (isBlobIndex(indexName)) {
            BlobIndex oldBlobIndex = indices.put(indexUUID, new BlobIndex(LOGGER, globalBlobPath));
            assert oldBlobIndex == null : "There must not be an index present if a new index is created";
        }
    }

    @Override
    public void afterIndexRemoved(Index index,
                                  IndexSettings indexSettings,
                                  IndicesClusterStateService.AllocatedIndices.IndexRemovalReason reason) {
        String indexName = index.getName();
        String indexUUID = index.getUUID();
        if (isBlobIndex(indexName)) {
            BlobIndex blobIndex = indices.remove(indexUUID);
            assert blobIndex != null : "BlobIndex not found on afterIndexDeleted";
        }
    }

    @Override
    public void afterIndexShardCreated(IndexShard indexShard) {
        String indexName = indexShard.shardId().getIndexName();
        String indexUUID = indexShard.shardId().getIndexUUID();
        if (isBlobIndex(indexName)) {
            BlobIndex blobIndex = indices.get(indexUUID);
            assert blobIndex != null : "blobIndex must exists if a shard is created in it";
            blobIndex.createShard(indexShard);
        }
    }

    @Override
    public void indexShardStateChanged(IndexShard indexShard,
                                       @Nullable IndexShardState previousState,
                                       IndexShardState currentState,
                                       @Nullable String reason) {
        if (currentState == IndexShardState.POST_RECOVERY) {
            String indexName = indexShard.shardId().getIndexName();
            String indexUUID = indexShard.shardId().getIndexUUID();
            if (isBlobIndex(indexName)) {
                BlobIndex blobIndex = indices.get(indexUUID);
                blobIndex.initializeShard(indexShard);
            }
        }
    }

    @Override
    public void afterIndexShardDeleted(ShardId shardId, Settings indexSettings) {
        String index = shardId.getIndexName();
        String indexUUID = shardId.getIndexUUID();
        if (isBlobIndex(index)) {
            BlobIndex blobIndex = indices.get(indexUUID);
            if (blobIndex != null) {
                blobIndex.removeShard(shardId);
            }
        }
    }

    @Nullable
    public BlobShard blobShard(ShardId shardId) {
        BlobIndex blobIndex = indices.get(shardId.getIndexUUID());
        if (blobIndex == null) {
            return null;
        }
        return blobIndex.getShard(shardId.id());
    }

    public BlobShard blobShardSafe(ShardId shardId) {
        String index = shardId.getIndexName();
        if (isBlobIndex(index)) {
            BlobShard blobShard = blobShard(shardId);
            if (blobShard == null) {
                throw new ShardNotFoundException(shardId);
            }
            return blobShard;
        }
        throw new BlobsDisabledException(shardId.getIndex());
    }

    public BlobShard localBlobShard(String index, String digest) {
        return blobShardSafe(localShardId(index, digest));
    }

    private ShardId localShardId(String index, String digest) {
        ShardIterator si = clusterService.operationRouting().getShards(
            clusterService.state(), index, null, digest, "_only_local");
        return si.shardId();
    }

    static boolean ensureExistsAndWritable(Path blobsPath) {
        if (Files.exists(blobsPath)) {
            if (!Files.isDirectory(blobsPath)) {
                throw new SettingsException(
                    String.format(Locale.ENGLISH, "blobs path '%s' is a file, must be a directory", blobsPath));
            }
            if (!Files.isWritable(blobsPath)) {
                throw new SettingsException(
                    String.format(Locale.ENGLISH, "blobs path '%s' is not writable", blobsPath));
            }
        } else {
            try {
                Files.createDirectories(blobsPath);
            } catch (IOException e) {
                throw new SettingsException(
                    String.format(Locale.ENGLISH, "blobs path '%s' could not be created", blobsPath));
            }
        }
        return true;
    }
}
