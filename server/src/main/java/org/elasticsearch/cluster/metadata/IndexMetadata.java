/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster.metadata;

import static org.elasticsearch.cluster.node.DiscoveryNodeFilters.IP_VALIDATOR;
import static org.elasticsearch.cluster.node.DiscoveryNodeFilters.OpType.AND;
import static org.elasticsearch.cluster.node.DiscoveryNodeFilters.OpType.OR;
import static org.elasticsearch.common.settings.Settings.readSettingsFromStream;
import static org.elasticsearch.common.settings.Settings.writeSettingsToStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.elasticsearch.Assertions;
import org.elasticsearch.Version;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.cluster.Diff;
import org.elasticsearch.cluster.Diffable;
import org.elasticsearch.cluster.Diffs;
import org.elasticsearch.cluster.block.ClusterBlock;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.node.DiscoveryNodeFilters;
import org.elasticsearch.cluster.routing.allocation.IndexMetadataUpdater;
import org.elasticsearch.common.collect.ImmutableOpenIntMap;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.gateway.MetadataStateFormat;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.rest.RestStatus;
import org.jetbrains.annotations.Nullable;

import com.carrotsearch.hppc.LongArrayList;
import com.carrotsearch.hppc.cursors.IntObjectCursor;
import com.carrotsearch.hppc.cursors.ObjectCursor;

import io.crate.Constants;
import io.crate.common.collections.MapBuilder;
import io.crate.metadata.IndexName;
import io.crate.metadata.IndexParts;
import io.crate.metadata.PartitionName;
import io.crate.server.xcontent.XContentHelper;
import io.crate.types.DataTypes;

public class IndexMetadata implements Diffable<IndexMetadata> {

    public static final ClusterBlock INDEX_CLOSED_BLOCK = new ClusterBlock(4, "index closed", false, false, false, RestStatus.FORBIDDEN, ClusterBlockLevel.READ_WRITE);
    public static final ClusterBlock INDEX_READ_ONLY_BLOCK = new ClusterBlock(5, "index read-only (api)", false, false, false, RestStatus.FORBIDDEN, EnumSet.of(ClusterBlockLevel.WRITE, ClusterBlockLevel.METADATA_WRITE));
    public static final ClusterBlock INDEX_READ_BLOCK = new ClusterBlock(7, "index read (api)", false, false, false, RestStatus.FORBIDDEN, EnumSet.of(ClusterBlockLevel.READ));
    public static final ClusterBlock INDEX_WRITE_BLOCK = new ClusterBlock(8, "index write (api)", false, false, false, RestStatus.FORBIDDEN, EnumSet.of(ClusterBlockLevel.WRITE));
    public static final ClusterBlock INDEX_METADATA_BLOCK = new ClusterBlock(9, "index metadata (api)", false, false, false, RestStatus.FORBIDDEN, EnumSet.of(ClusterBlockLevel.METADATA_WRITE, ClusterBlockLevel.METADATA_READ));
    public static final ClusterBlock INDEX_READ_ONLY_ALLOW_DELETE_BLOCK = new ClusterBlock(12, "index read-only / allow delete (api)", false, false, true, RestStatus.FORBIDDEN, EnumSet.of(ClusterBlockLevel.METADATA_WRITE, ClusterBlockLevel.WRITE));

    public enum State {
        OPEN((byte) 0),
        CLOSE((byte) 1);

        private final byte id;

        State(byte id) {
            this.id = id;
        }

        public byte id() {
            return this.id;
        }

        public static State fromId(byte id) {
            if (id == 0) {
                return OPEN;
            } else if (id == 1) {
                return CLOSE;
            }
            throw new IllegalStateException("No state match for id [" + id + "]");
        }

        public static State fromString(String state) {
            if ("open".equals(state)) {
                return OPEN;
            } else if ("close".equals(state)) {
                return CLOSE;
            }
            throw new IllegalStateException("No state match for [" + state + "]");
        }
    }

    static Setting<Integer> buildNumberOfShardsSetting() {
        /* This is a safety limit that should only be exceeded in very rare and special cases. The assumption is that
         * 99% of the users have less than 1024 shards per index. We also make it a hard check that requires restart of nodes
         * if a cluster should allow to create more than 1024 shards per index. NOTE: this does not limit the number of shards per cluster.
         * this also prevents creating stuff like a new index with millions of shards by accident which essentially kills the entire cluster
         * with OOM on the spot.*/
        final int maxNumShards = Integer.parseInt(System.getProperty("es.index.max_number_of_shards", "1024"));
        if (maxNumShards < 1) {
            throw new IllegalArgumentException("es.index.max_number_of_shards must be > 0");
        }
        return Setting.intSetting(SETTING_NUMBER_OF_SHARDS, Math.min(5, maxNumShards), 1, maxNumShards,
            Property.IndexScope, Property.Final);
    }

    public static final String INDEX_SETTING_PREFIX = "index.";
    public static final String SETTING_NUMBER_OF_SHARDS = "index.number_of_shards";
    public static final Setting<Integer> INDEX_NUMBER_OF_SHARDS_SETTING = buildNumberOfShardsSetting();
    public static final String SETTING_NUMBER_OF_REPLICAS = "index.number_of_replicas";
    public static final Setting<Integer> INDEX_NUMBER_OF_REPLICAS_SETTING =
        Setting.intSetting(SETTING_NUMBER_OF_REPLICAS, 0, 0, Property.Dynamic, Property.IndexScope, Property.ReplicatedIndexScope);

    public static final String SETTING_ROUTING_PARTITION_SIZE = "index.routing_partition_size";
    public static final Setting<Integer> INDEX_ROUTING_PARTITION_SIZE_SETTING =
            Setting.intSetting(SETTING_ROUTING_PARTITION_SIZE, 1, 1, Property.IndexScope);

    public static final Setting<Integer> INDEX_NUMBER_OF_ROUTING_SHARDS_SETTING =
        Setting.intSetting(
            "index.number_of_routing_shards",
            INDEX_NUMBER_OF_SHARDS_SETTING,
            1,
            new Setting.Validator<>() {
                @Override
                public void validate(final Integer value) {
                }

                @Override
                public void validate(Integer numRoutingShards, Map<Setting<?>, Object> settings) {
                    int numShards = (int) settings.get(INDEX_NUMBER_OF_SHARDS_SETTING);
                    if (numRoutingShards < numShards) {
                        throw new IllegalArgumentException("index.number_of_routing_shards [" + numRoutingShards
                                                           + "] must be >= index.number_of_shards [" + numShards + "]");
                    }
                    getRoutingFactor(numShards, numRoutingShards);
                }

                @Override
                public Iterator<Setting<?>> settings() {
                    final List<Setting<?>> settings = Collections.singletonList(INDEX_NUMBER_OF_SHARDS_SETTING);
                    return settings.iterator();
                }
            },
            Property.IndexScope, Property.Final);

    public static final String SETTING_READ_ONLY = "index.blocks.read_only";
    public static final Setting<Boolean> INDEX_READ_ONLY_SETTING =
        Setting.boolSetting(SETTING_READ_ONLY, false, Property.Dynamic, Property.IndexScope);

    public static final String SETTING_BLOCKS_READ = "index.blocks.read";
    public static final Setting<Boolean> INDEX_BLOCKS_READ_SETTING =
        Setting.boolSetting(SETTING_BLOCKS_READ, false, Property.Dynamic, Property.IndexScope);

    public static final String SETTING_BLOCKS_WRITE = "index.blocks.write";
    public static final Setting<Boolean> INDEX_BLOCKS_WRITE_SETTING =
        Setting.boolSetting(SETTING_BLOCKS_WRITE, false, Property.Dynamic, Property.IndexScope);

    public static final String SETTING_BLOCKS_METADATA = "index.blocks.metadata";
    public static final Setting<Boolean> INDEX_BLOCKS_METADATA_SETTING =
        Setting.boolSetting(SETTING_BLOCKS_METADATA, false, Property.Dynamic, Property.IndexScope);

    public static final String SETTING_READ_ONLY_ALLOW_DELETE = "index.blocks.read_only_allow_delete";
    public static final Setting<Boolean> INDEX_BLOCKS_READ_ONLY_ALLOW_DELETE_SETTING =
        Setting.boolSetting(SETTING_READ_ONLY_ALLOW_DELETE, false, Property.Dynamic, Property.IndexScope, Property.ReplicatedIndexScope);

    public static final String SETTING_VERSION_CREATED = "index.version.created";

    public static final Setting<Version> SETTING_INDEX_VERSION_CREATED =
            Setting.versionSetting(SETTING_VERSION_CREATED, Version.V_EMPTY, Property.IndexScope, Property.PrivateIndex);

    public static final String SETTING_VERSION_UPGRADED = "index.version.upgraded";
    public static final String SETTING_CREATION_DATE = "index.creation_date";
    public static final String SETTING_INDEX_UUID = "index.uuid";
    public static final String SETTING_HISTORY_UUID = "index.history.uuid";
    public static final String SETTING_DATA_PATH = "index.data_path";
    public static final Setting<String> INDEX_DATA_PATH_SETTING =
        new Setting<>(SETTING_DATA_PATH, "", Function.identity(), DataTypes.STRING, Property.IndexScope);
    public static final String INDEX_UUID_NA_VALUE = "_na_";

    public static final String SETTING_INDEX_NAME = "index.name";
    public static final String INDEX_NAME_NA_VALUE = "_na_";

    public static final String INDEX_ROUTING_REQUIRE_GROUP_PREFIX = "index.routing.allocation.require";
    public static final String INDEX_ROUTING_INCLUDE_GROUP_PREFIX = "index.routing.allocation.include";
    public static final String INDEX_ROUTING_EXCLUDE_GROUP_PREFIX = "index.routing.allocation.exclude";
    public static final Setting.AffixSetting<String> INDEX_ROUTING_REQUIRE_GROUP_SETTING =
        Setting.prefixKeySetting(INDEX_ROUTING_REQUIRE_GROUP_PREFIX + ".", key ->
            Setting.simpleString(key, value -> IP_VALIDATOR.accept(key, value), Property.Dynamic, Property.IndexScope, Property.ReplicatedIndexScope));
    public static final Setting.AffixSetting<String> INDEX_ROUTING_INCLUDE_GROUP_SETTING =
        Setting.prefixKeySetting(INDEX_ROUTING_INCLUDE_GROUP_PREFIX + ".", key ->
            Setting.simpleString(key, value -> IP_VALIDATOR.accept(key, value), Property.Dynamic, Property.IndexScope, Property.ReplicatedIndexScope));
    public static final Setting.AffixSetting<String> INDEX_ROUTING_EXCLUDE_GROUP_SETTING =
        Setting.prefixKeySetting(INDEX_ROUTING_EXCLUDE_GROUP_PREFIX + ".", key ->
            Setting.simpleString(key, value -> IP_VALIDATOR.accept(key, value), Property.Dynamic, Property.IndexScope,Property.ReplicatedIndexScope));

    // this is only setable internally not a registered setting!!
    public static final Setting.AffixSetting<String> INDEX_ROUTING_INITIAL_RECOVERY_GROUP_SETTING =
        Setting.prefixKeySetting("index.routing.allocation.initial_recovery.", key -> Setting.simpleString(key));

    /**
     * The number of active shard copies to check for before proceeding with a write operation.
     */
    public static final Setting<ActiveShardCount> SETTING_WAIT_FOR_ACTIVE_SHARDS =
        new Setting<>("index.write.wait_for_active_shards",
                      "1",
                      ActiveShardCount::parseString,
                      DataTypes.STRING,
                      Setting.Property.Dynamic,
                      Setting.Property.IndexScope,
                      Property.ReplicatedIndexScope);

    /**
     * an internal index format description, allowing us to find out if this index is upgraded or needs upgrading
     */
    private static final String INDEX_FORMAT = "index.format";
    public static final Setting<Integer> INDEX_FORMAT_SETTING =
            Setting.intSetting(INDEX_FORMAT, 0, Setting.Property.IndexScope, Setting.Property.Final);


    public static final Setting<Boolean> VERIFIED_BEFORE_CLOSE_SETTING =
        Setting.boolSetting("index.verified_before_close", false, Setting.Property.IndexScope, Setting.Property.PrivateIndex);

    public static final String KEY_IN_SYNC_ALLOCATIONS = "in_sync_allocations";
    static final String KEY_VERSION = "version";
    static final String KEY_MAPPING_VERSION = "mapping_version";
    static final String KEY_SETTINGS_VERSION = "settings_version";
    static final String KEY_ROUTING_NUM_SHARDS = "routing_num_shards";
    static final String KEY_SETTINGS = "settings";
    static final String KEY_STATE = "state";
    static final String KEY_MAPPINGS = "mappings";
    static final String KEY_ALIASES = "aliases";
    public static final String KEY_PRIMARY_TERMS = "primary_terms";

    public static final String INDEX_STATE_FILE_PREFIX = "state-";
    private final int routingNumShards;
    private final int routingFactor;
    private final int routingPartitionSize;

    private final int numberOfShards;
    private final int numberOfReplicas;

    private final Index index;
    private final long version;

    private final long mappingVersion;

    private final long settingsVersion;

    private final long[] primaryTerms;

    private final State state;

    /**
     * @deprecated indices and table association is created via {@link
     *             RelationMetadata.Table#indexUUIDs()} or {@link
     *             RelationMetadata.BlobTable#indexUUID()}
     **/
    @Deprecated
    private final ImmutableOpenMap<String, AliasMetadata> aliases;


    private final List<String> partitionValues;

    private final Settings settings;

    /**
     * @deprecated mapping is stored on table level via {@link RelationMetadata.Table}
     **/
    @Deprecated
    private final MappingMetadata mapping;

    private final ImmutableOpenIntMap<Set<String>> inSyncAllocationIds;

    private final transient int totalNumberOfShards;

    private final DiscoveryNodeFilters requireFilters;
    private final DiscoveryNodeFilters includeFilters;
    private final DiscoveryNodeFilters excludeFilters;
    private final DiscoveryNodeFilters initialRecoveryFilters;

    private final Version indexCreatedVersion;
    private final Version indexUpgradedVersion;

    private final ActiveShardCount waitForActiveShards;

    private IndexMetadata(Index index,
                          long version,
                          long mappingVersion,
                          long settingsVersion,
                          long[] primaryTerms,
                          State state,
                          int numberOfShards,
                          int numberOfReplicas,
                          Settings settings,
                          MappingMetadata mapping,
                          ImmutableOpenMap<String, AliasMetadata> aliases,
                          List<String> partitionValues,
                          ImmutableOpenIntMap<Set<String>> inSyncAllocationIds,
                          DiscoveryNodeFilters requireFilters,
                          DiscoveryNodeFilters initialRecoveryFilters,
                          DiscoveryNodeFilters includeFilters,
                          DiscoveryNodeFilters excludeFilters,
                          Version indexCreatedVersion,
                          Version indexUpgradedVersion,
                          int routingNumShards,
                          int routingPartitionSize,
                          ActiveShardCount waitForActiveShards) {

        this.index = index;
        this.version = version;
        assert mappingVersion >= 0 : mappingVersion;
        this.mappingVersion = mappingVersion;
        assert settingsVersion >= 0 : settingsVersion;
        this.settingsVersion = settingsVersion;
        this.primaryTerms = primaryTerms;
        assert primaryTerms.length == numberOfShards;
        this.state = state;
        this.numberOfShards = numberOfShards;
        this.numberOfReplicas = numberOfReplicas;
        this.totalNumberOfShards = numberOfShards * (numberOfReplicas + 1);
        this.settings = settings;
        this.mapping = mapping;
        this.aliases = aliases;
        this.partitionValues = partitionValues;
        this.inSyncAllocationIds = inSyncAllocationIds;
        this.requireFilters = requireFilters;
        this.includeFilters = includeFilters;
        this.excludeFilters = excludeFilters;
        this.initialRecoveryFilters = initialRecoveryFilters;
        this.indexCreatedVersion = indexCreatedVersion;
        this.indexUpgradedVersion = indexUpgradedVersion;
        this.routingNumShards = routingNumShards;
        this.routingFactor = routingNumShards / numberOfShards;
        this.routingPartitionSize = routingPartitionSize;
        this.waitForActiveShards = waitForActiveShards;
        assert numberOfShards * routingFactor == routingNumShards : routingNumShards + " must be a multiple of " + numberOfShards;
    }

    public Index getIndex() {
        return index;
    }

    public String getIndexUUID() {
        return index.getUUID();
    }

    public long getVersion() {
        return this.version;
    }

    public long getMappingVersion() {
        return mappingVersion;
    }

    public long getSettingsVersion() {
        return settingsVersion;
    }

    /**
     * The term of the current selected primary. This is a non-negative number incremented when
     * a primary shard is assigned after a full cluster restart or a replica shard is promoted to a primary.
     *
     * Note: since we increment the term every time a shard is assigned, the term for any operational shard (i.e., a shard
     * that can be indexed into) is larger than 0. See {@link IndexMetadataUpdater#applyChanges}.
     **/
    public long primaryTerm(int shardId) {
        return this.primaryTerms[shardId];
    }

    /**
     * Return the {@link Version} on which this index has been created. This
     * information is typically useful for backward compatibility.
     */
    public Version getCreationVersion() {
        return indexCreatedVersion;
    }

    /**
     * Return the {@link Version} on which this index has been upgraded. This
     * information is typically useful for backward compatibility.
     */
    public Version getUpgradedVersion() {
        return indexUpgradedVersion;
    }

    public long getCreationDate() {
        return settings.getAsLong(SETTING_CREATION_DATE, -1L);
    }

    public State getState() {
        return this.state;
    }

    public int getNumberOfShards() {
        return numberOfShards;
    }

    public int getNumberOfReplicas() {
        return numberOfReplicas;
    }

    public int getRoutingPartitionSize() {
        return routingPartitionSize;
    }

    public boolean isRoutingPartitionedIndex() {
        return routingPartitionSize != 1;
    }

    public int getTotalNumberOfShards() {
        return totalNumberOfShards;
    }

    /**
     * Returns the configured {@link #SETTING_WAIT_FOR_ACTIVE_SHARDS}, which defaults
     * to an active shard count of 1 if not specified.
     */
    public ActiveShardCount getWaitForActiveShards() {
        return waitForActiveShards;
    }

    public Settings getSettings() {
        return settings;
    }

    public ImmutableOpenMap<String, AliasMetadata> getAliases() {
        return this.aliases;
    }

    public List<String> partitionValues() {
        return partitionValues;
    }

    /**
     * Return the concrete mapping for this index or {@code null} if this index has no mappings at all.
     */
    @Nullable
    public MappingMetadata mapping() {
        return mapping;
    }

    public static final String INDEX_RESIZE_SOURCE_UUID_KEY = "index.resize.source.uuid";
    public static final String INDEX_RESIZE_SOURCE_NAME_KEY = "index.resize.source.name";
    public static final Setting<String> INDEX_RESIZE_SOURCE_UUID = Setting.simpleString(INDEX_RESIZE_SOURCE_UUID_KEY);
    public static final Setting<String> INDEX_RESIZE_SOURCE_NAME = Setting.simpleString(INDEX_RESIZE_SOURCE_NAME_KEY);

    public Index getResizeSourceIndex() {
        return INDEX_RESIZE_SOURCE_UUID.exists(settings)
            ? new Index(INDEX_RESIZE_SOURCE_NAME.get(settings), INDEX_RESIZE_SOURCE_UUID.get(settings)) : null;
    }

    public ImmutableOpenIntMap<Set<String>> getInSyncAllocationIds() {
        return inSyncAllocationIds;
    }

    public Set<String> inSyncAllocationIds(int shardId) {
        assert shardId >= 0 && shardId < numberOfShards;
        return inSyncAllocationIds.get(shardId);
    }

    @Nullable
    public DiscoveryNodeFilters requireFilters() {
        return requireFilters;
    }

    @Nullable
    public DiscoveryNodeFilters getInitialRecoveryFilters() {
        return initialRecoveryFilters;
    }

    @Nullable
    public DiscoveryNodeFilters includeFilters() {
        return includeFilters;
    }

    @Nullable
    public DiscoveryNodeFilters excludeFilters() {
        return excludeFilters;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof IndexMetadata other
            && version == other.version
            && aliases.equals(other.aliases)
            && index.equals(other.index)
            && Objects.equals(mapping, other.mapping)
            && settings.equals(other.settings)
            && state == other.state
            && routingNumShards == other.routingNumShards
            && routingFactor == other.routingFactor
            && Arrays.equals(primaryTerms, other.primaryTerms)
            && inSyncAllocationIds.equals(other.inSyncAllocationIds)
            && partitionValues.equals(other.partitionValues);
    }

    @Override
    public int hashCode() {
        int result = index.hashCode();
        result = 31 * result + Long.hashCode(version);
        result = 31 * result + state.hashCode();
        result = 31 * result + aliases.hashCode();
        result = 31 * result + settings.hashCode();
        result = 31 * result + Objects.hashCode(mapping);
        result = 31 * result + Long.hashCode(routingFactor);
        result = 31 * result + Long.hashCode(routingNumShards);
        result = 31 * result + Arrays.hashCode(primaryTerms);
        result = 31 * result + inSyncAllocationIds.hashCode();
        result = 31 * result + partitionValues.hashCode();
        return result;
    }


    @Override
    public Diff<IndexMetadata> diff(IndexMetadata previousState) {
        return new IndexMetadataDiff(previousState, this);
    }

    public static Diff<IndexMetadata> readDiffFrom(StreamInput in) throws IOException {
        return new IndexMetadataDiff(in);
    }

    public static IndexMetadata fromXContent(XContentParser parser) throws IOException {
        return Builder.fromXContent(parser);
    }

    private static class IndexMetadataDiff implements Diff<IndexMetadata> {

        private final String index;
        private final int routingNumShards;
        private final long version;
        private final long mappingVersion;
        private final long settingsVersion;
        private final long[] primaryTerms;
        private final State state;
        private final Settings settings;
        private final List<String> partitionValues;
        private final Diff<ImmutableOpenMap<String, MappingMetadata>> mappings;
        private final Diff<ImmutableOpenMap<String, AliasMetadata>> aliases;
        private final Diff<ImmutableOpenIntMap<Set<String>>> inSyncAllocationIds;

        IndexMetadataDiff(IndexMetadata before, IndexMetadata after) {
            index = after.index.getName();
            version = after.version;
            mappingVersion = after.mappingVersion;
            settingsVersion = after.settingsVersion;
            routingNumShards = after.routingNumShards;
            state = after.state;
            settings = after.settings;
            primaryTerms = after.primaryTerms;
            partitionValues = after.partitionValues;
            ImmutableOpenMap.Builder<String, MappingMetadata> beforeMappings = ImmutableOpenMap.builder();
            if (before.mapping != null) {
                beforeMappings.put(Constants.DEFAULT_MAPPING_TYPE, before.mapping);
            }
            ImmutableOpenMap.Builder<String, MappingMetadata> afterMappings = ImmutableOpenMap.builder();
            if (after.mapping != null) {
                afterMappings.put(Constants.DEFAULT_MAPPING_TYPE, after.mapping);
            }
            mappings = Diffs.diff(beforeMappings.build(), afterMappings.build(), Diffs.stringKeySerializer());
            aliases = Diffs.diff(before.aliases, after.aliases, Diffs.stringKeySerializer());
            inSyncAllocationIds = Diffs.diff(before.inSyncAllocationIds, after.inSyncAllocationIds,
                Diffs.intKeySerializer(), Diffs.StringSetValueSerializer.getInstance());
        }

        private static final Diffs.DiffableValueReader<String, AliasMetadata> ALIAS_METADATA_DIFF_VALUE_READER =
                new Diffs.DiffableValueReader<>(AliasMetadata::new, AliasMetadata::readDiffFrom);
        private static final Diffs.DiffableValueReader<String, MappingMetadata> MAPPING_DIFF_VALUE_READER =
                new Diffs.DiffableValueReader<>(MappingMetadata::new, MappingMetadata::readDiffFrom);
        private static final Diffs.DiffableValueReader<String, DiffableStringMap> CUSTOM_DIFF_VALUE_READER =
                new Diffs.DiffableValueReader<>(DiffableStringMap::readFrom, DiffableStringMap::readDiffFrom);

        IndexMetadataDiff(StreamInput in) throws IOException {
            index = in.readString();
            routingNumShards = in.readInt();
            version = in.readLong();
            mappingVersion = in.readVLong();
            settingsVersion = in.readVLong();
            state = State.fromId(in.readByte());
            settings = Settings.readSettingsFromStream(in);
            primaryTerms = in.readVLongArray();
            mappings = Diffs.readMapDiff(in, Diffs.stringKeySerializer(), MAPPING_DIFF_VALUE_READER);
            aliases = Diffs.readMapDiff(in, Diffs.stringKeySerializer(), ALIAS_METADATA_DIFF_VALUE_READER);
            if (in.getVersion().before(Version.V_5_8_0)) {
                Diffs.readMapDiff(in, Diffs.stringKeySerializer(), CUSTOM_DIFF_VALUE_READER);
            }
            inSyncAllocationIds = Diffs.readIntMapDiff(in, Diffs.intKeySerializer(),
                Diffs.StringSetValueSerializer.getInstance());
            if (in.getVersion().onOrAfter(Version.V_6_0_0)) {
                int numValues = in.readVInt();
                this.partitionValues = new ArrayList<>(numValues);
                for (int i = 0; i < numValues; i++) {
                    partitionValues.add(in.readOptionalString());
                }
            } else {
                IndexParts indexParts = IndexName.decode(index);
                this.partitionValues = indexParts.isPartitioned()
                    ? PartitionName.decodeIdent(indexParts.partitionIdent())
                    : List.of();
            }
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeString(index);
            out.writeInt(routingNumShards);
            out.writeLong(version);
            out.writeVLong(mappingVersion);
            out.writeVLong(settingsVersion);
            out.writeByte(state.id);
            Settings.writeSettingsToStream(out, settings);
            out.writeVLongArray(primaryTerms);
            mappings.writeTo(out);
            aliases.writeTo(out);
            if (out.getVersion().before(Version.V_5_8_0)) {
                Diff<ImmutableOpenMap<String, DiffableStringMap>> customData = Diffs.diff(
                    ImmutableOpenMap.<String, DiffableStringMap>of(),
                    ImmutableOpenMap.<String, DiffableStringMap>of(),
                    Diffs.stringKeySerializer()
                );
                customData.writeTo(out);
            }
            inSyncAllocationIds.writeTo(out);
            if (out.getVersion().onOrAfter(Version.V_6_0_0)) {
                out.writeVInt(partitionValues.size());
                for (String value : partitionValues) {
                    out.writeOptionalString(value);
                }
            }
        }

        @Override
        public IndexMetadata apply(IndexMetadata part) {
            String indexUUID = settings.get(SETTING_INDEX_UUID, INDEX_UUID_NA_VALUE);
            Builder builder = builder(indexUUID);
            builder.indexName(index);
            builder.version(version);
            builder.mappingVersion(mappingVersion);
            builder.settingsVersion(settingsVersion);
            builder.setRoutingNumShards(routingNumShards);
            builder.state(state);
            builder.settings(settings);
            builder.primaryTerms(primaryTerms);
            builder.partitionValues(partitionValues);
            ImmutableOpenMap.Builder<String, MappingMetadata> mappingBuilder = ImmutableOpenMap.<String, MappingMetadata>builder();
            if (part.mapping != null) {
                mappingBuilder.put(Constants.DEFAULT_MAPPING_TYPE, part.mapping);
                MappingMetadata appliedMapping = mappings.apply(mappingBuilder.build()).get(Constants.DEFAULT_MAPPING_TYPE);
                if (appliedMapping != null) {
                    builder.mapping = appliedMapping;
                }
            } else {
                builder.mapping = part.mapping;
            }
            builder.aliases.putAll(aliases.apply(part.aliases));
            builder.inSyncAllocationIds.putAll(inSyncAllocationIds.apply(part.inSyncAllocationIds));
            return builder.build();
        }
    }

    public static IndexMetadata readFrom(StreamInput in) throws IOException {
        Builder builder = new Builder();
        builder.indexName(in.readString());
        builder.version(in.readLong());
        builder.mappingVersion(in.readVLong());
        builder.settingsVersion(in.readVLong());
        builder.setRoutingNumShards(in.readInt());
        builder.state(State.fromId(in.readByte()));
        builder.settings(readSettingsFromStream(in));
        builder.primaryTerms(in.readVLongArray());
        int mappingsSize = in.readVInt();
        for (int i = 0; i < mappingsSize; i++) {
            MappingMetadata mappingMd = new MappingMetadata(in);
            builder.putMapping(mappingMd);
        }
        int aliasesSize = in.readVInt();
        for (int i = 0; i < aliasesSize; i++) {
            AliasMetadata aliasMd = new AliasMetadata(in);
            builder.putAlias(aliasMd);
        }
        if (in.getVersion().before(Version.V_5_8_0)) {
            int customSize = in.readVInt();
            for (int i = 0; i < customSize; i++) {
                in.readString(); // key
                DiffableStringMap.readFrom(in); // custom
            }
        }
        int inSyncAllocationIdsSize = in.readVInt();
        for (int i = 0; i < inSyncAllocationIdsSize; i++) {
            int key = in.readVInt();
            Set<String> allocationIds = Diffs.StringSetValueSerializer.getInstance().read(in, key);
            builder.putInSyncAllocationIds(key, allocationIds);
        }
        if (in.getVersion().onOrAfter(Version.V_6_0_0)) {
            int numValues = in.readVInt();
            ArrayList<String> partitionValues = new ArrayList<>(numValues);
            for (int i = 0; i < numValues; i++) {
                partitionValues.add(in.readOptionalString());
            }
            builder.partitionValues(partitionValues);
        }

        String indexUUID = builder.settings.get(SETTING_INDEX_UUID);
        builder.indexUUID(indexUUID);

        return builder.build();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(index.getName()); // uuid will come as part of settings
        out.writeLong(version);
        out.writeVLong(mappingVersion);
        out.writeVLong(settingsVersion);
        out.writeInt(routingNumShards);
        out.writeByte(state.id());
        writeSettingsToStream(out, settings);
        out.writeVLongArray(primaryTerms);
        if (mapping == null) {
            out.writeVInt(0);
        } else {
            out.writeVInt(1);
            mapping.writeTo(out);
        }
        out.writeVInt(aliases.size());
        for (ObjectCursor<AliasMetadata> cursor : aliases.values()) {
            cursor.value.writeTo(out);
        }
        // customData
        if (out.getVersion().before(Version.V_5_8_0)) {
            out.writeVInt(0);
        }
        out.writeVInt(inSyncAllocationIds.size());
        for (IntObjectCursor<Set<String>> cursor : inSyncAllocationIds) {
            out.writeVInt(cursor.key);
            Diffs.StringSetValueSerializer.getInstance().write(cursor.value, out);
        }
        if (out.getVersion().onOrAfter(Version.V_6_0_0)) {
            out.writeVInt(partitionValues.size());
            for (String value : partitionValues) {
                out.writeOptionalString(value);
            }
        }
    }

    public static Builder builder(String indexUUID) {
        return new Builder(indexUUID);
    }

    public static Builder builder(IndexMetadata indexMetadata) {
        return new Builder(indexMetadata);
    }

    public static class Builder {

        private String indexUUID;
        private String indexName;
        private State state = State.OPEN;
        private long version = 1;
        private long mappingVersion = 1;
        private long settingsVersion = 1;
        private long[] primaryTerms = null;
        private Settings settings = Settings.EMPTY;
        private MappingMetadata mapping;
        private List<String> partitionValues;
        private final ImmutableOpenMap.Builder<String, AliasMetadata> aliases;
        private final ImmutableOpenIntMap.Builder<Set<String>> inSyncAllocationIds;
        private Integer routingNumShards;

        public Builder() {
            this(INDEX_UUID_NA_VALUE);
        }

        public Builder(String indexUUID) {
            this.indexUUID = indexUUID;
            this.aliases = ImmutableOpenMap.builder();
            this.inSyncAllocationIds = ImmutableOpenIntMap.builder();
            this.partitionValues = List.of();
        }

        public Builder(IndexMetadata indexMetadata) {
            this.indexUUID = indexMetadata.getIndex().getUUID();
            this.indexName = indexMetadata.getIndex().getName();
            this.state = indexMetadata.state;
            this.version = indexMetadata.version;
            this.mappingVersion = indexMetadata.mappingVersion;
            this.settingsVersion = indexMetadata.settingsVersion;
            this.settings = indexMetadata.getSettings();
            this.primaryTerms = indexMetadata.primaryTerms.clone();
            this.mapping = indexMetadata.mapping;
            this.aliases = ImmutableOpenMap.builder(indexMetadata.aliases);
            this.partitionValues = indexMetadata.partitionValues;
            this.routingNumShards = indexMetadata.routingNumShards;
            this.inSyncAllocationIds = ImmutableOpenIntMap.builder(indexMetadata.inSyncAllocationIds);
        }

        public Builder indexName(String indexName) {
            this.indexName = indexName;
            return this;
        }

        public String indexUUID() {
            return indexUUID;
        }

        public Builder indexUUID(String indexUUID) {
            this.indexUUID = indexUUID;
            return this;
        }

        public Builder numberOfShards(int numberOfShards) {
            settings = Settings.builder().put(settings).put(SETTING_NUMBER_OF_SHARDS, numberOfShards).build();
            return this;
        }

        /**
         * Sets the number of shards that should be used for routing. This should only be used if the number of shards in
         * an index has changed ie if the index is shrunk.
         */
        public Builder setRoutingNumShards(int routingNumShards) {
            this.routingNumShards = routingNumShards;
            return this;
        }

        /**
         * Returns number of shards that should be used for routing. By default this method will return the number of shards
         * for this index.
         *
         * @see #setRoutingNumShards(int)
         * @see #numberOfShards()
         */
        public int getRoutingNumShards() {
            return routingNumShards == null ? numberOfShards() : routingNumShards;
        }

        /**
         * Returns the number of shards.
         *
         * @return the provided value or -1 if it has not been set.
         */
        public int numberOfShards() {
            return settings.getAsInt(SETTING_NUMBER_OF_SHARDS, -1);
        }

        public Builder numberOfReplicas(int numberOfReplicas) {
            settings = Settings.builder().put(settings).put(SETTING_NUMBER_OF_REPLICAS, numberOfReplicas).build();
            return this;
        }

        /**
         * Returns the number of replicas.
         *
         * @return the provided value or -1 if it has not been set.
         */
        public int numberOfReplicas() {
            return settings.getAsInt(SETTING_NUMBER_OF_REPLICAS, -1);
        }

        public Builder routingPartitionSize(int routingPartitionSize) {
            settings = Settings.builder().put(settings).put(SETTING_ROUTING_PARTITION_SIZE, routingPartitionSize).build();
            return this;
        }

        /**
         * Returns the routing partition size.
         *
         * @return the provided value or -1 if it has not been set.
         */
        public int routingPartitionSize() {
            return settings.getAsInt(SETTING_ROUTING_PARTITION_SIZE, -1);
        }

        public Builder creationDate(long creationDate) {
            settings = Settings.builder().put(settings).put(SETTING_CREATION_DATE, creationDate).build();
            return this;
        }

        public Builder settings(Settings.Builder settings) {
            return settings(settings.build());
        }

        public Builder settings(Settings settings) {
            this.settings = settings;
            return this;
        }

        public MappingMetadata mapping() {
            return mapping;
        }

        public Builder partitionValues(List<String> partitionValues) {
            this.partitionValues = partitionValues;
            return this;
        }

        /**
         * @deprecated mapping is on table level {@link RelationMetadata.Table}
         **/
        @Deprecated
        public Builder putMapping(String source) {
            putMapping(new MappingMetadata(XContentHelper.convertToMap(JsonXContent.JSON_XCONTENT, source, true)));
            return this;
        }

        /**
         * @deprecated mapping is on table level {@link RelationMetadata.Table}
         **/
        @Deprecated
        public Builder putMapping(MappingMetadata mappingMd) {
            if (mappingMd != null) {
                mapping = mappingMd;
            }
            return this;
        }

        public Builder state(State state) {
            this.state = state;
            return this;
        }

        public Builder putAlias(AliasMetadata aliasMetadata) {
            aliases.put(aliasMetadata.alias(), aliasMetadata);
            return this;
        }

        public Builder putAlias(AliasMetadata.Builder aliasMetadata) {
            aliases.put(aliasMetadata.alias(), aliasMetadata.build());
            return this;
        }

        public Builder removeAlias(String alias) {
            aliases.remove(alias);
            return this;
        }

        public Builder removeAllAliases() {
            aliases.clear();
            return this;
        }

        public Builder putInSyncAllocationIds(int shardId, Set<String> allocationIds) {
            inSyncAllocationIds.put(shardId, new HashSet<>(allocationIds));
            return this;
        }

        public long version() {
            return this.version;
        }

        public Builder version(long version) {
            this.version = version;
            return this;
        }

        public long mappingVersion() {
            return mappingVersion;
        }

        public long settingsVersion() {
            return settingsVersion;
        }

        public Builder mappingVersion(final long mappingVersion) {
            this.mappingVersion = mappingVersion;
            return this;
        }

        public Builder settingsVersion(final long settingsVersion) {
            this.settingsVersion = settingsVersion;
            return this;
        }

        public Builder incrementSettingsVersion() {
            this.settingsVersion = this.settingsVersion + 1;
            return this;
        }

        /**
         * returns the primary term for the given shard.
         * See {@link IndexMetadata#primaryTerm(int)} for more information.
         */
        public long primaryTerm(int shardId) {
            if (primaryTerms == null) {
                initializePrimaryTerms();
            }
            return this.primaryTerms[shardId];
        }

        /**
         * sets the primary term for the given shard.
         * See {@link IndexMetadata#primaryTerm(int)} for more information.
         */
        public Builder primaryTerm(int shardId, long primaryTerm) {
            if (primaryTerms == null) {
                initializePrimaryTerms();
            }
            this.primaryTerms[shardId] = primaryTerm;
            return this;
        }

        private void primaryTerms(long[] primaryTerms) {
            this.primaryTerms = primaryTerms.clone();
        }

        private void initializePrimaryTerms() {
            assert primaryTerms == null;
            if (numberOfShards() < 0) {
                throw new IllegalStateException("you must set the number of shards before setting/reading primary terms");
            }
            primaryTerms = new long[numberOfShards()];
        }


        public IndexMetadata build() {
            if (indexUUID == null || indexUUID.isEmpty()) {
                throw new IllegalArgumentException("index uuid must not be null or empty");
            }

            final ImmutableOpenMap.Builder<String, AliasMetadata> tmpAliases = aliases;
            indexName = indexName == null ? indexUUID : indexName;
            final Settings tmpSettings = Settings.builder()
                .put(settings)
                .put(SETTING_INDEX_NAME, indexName)
                .put(SETTING_INDEX_UUID, indexUUID)
                .build();

            Integer maybeNumberOfShards = settings.getAsInt(SETTING_NUMBER_OF_SHARDS, null);
            if (maybeNumberOfShards == null) {
                throw new IllegalArgumentException("must specify numberOfShards for index [" + indexUUID + "]");
            }
            int numberOfShards = maybeNumberOfShards;
            if (numberOfShards <= 0) {
                throw new IllegalArgumentException("must specify positive number of shards for index [" + indexUUID + "]");
            }

            Integer maybeNumberOfReplicas = settings.getAsInt(SETTING_NUMBER_OF_REPLICAS, null);
            if (maybeNumberOfReplicas == null) {
                throw new IllegalArgumentException("must specify numberOfReplicas for index [" + indexUUID + "]");
            }
            int numberOfReplicas = maybeNumberOfReplicas;
            if (numberOfReplicas < 0) {
                throw new IllegalArgumentException("must specify non-negative number of shards for index [" + indexUUID + "]");
            }

            int routingPartitionSize = INDEX_ROUTING_PARTITION_SIZE_SETTING.get(settings);
            if (routingPartitionSize != 1 && routingPartitionSize >= getRoutingNumShards()) {
                throw new IllegalArgumentException("routing partition size [" + routingPartitionSize + "] should be a positive number"
                        + " less than the number of shards [" + getRoutingNumShards() + "] for [" + indexUUID + "]");
            }
            // fill missing slots in inSyncAllocationIds with empty set if needed and make all entries immutable
            ImmutableOpenIntMap.Builder<Set<String>> filledInSyncAllocationIds = ImmutableOpenIntMap.builder();
            for (int i = 0; i < numberOfShards; i++) {
                if (inSyncAllocationIds.containsKey(i)) {
                    filledInSyncAllocationIds.put(i, Collections.unmodifiableSet(new HashSet<>(inSyncAllocationIds.get(i))));
                } else {
                    filledInSyncAllocationIds.put(i, Collections.emptySet());
                }
            }
            final Map<String, String> requireMap = INDEX_ROUTING_REQUIRE_GROUP_SETTING.getAsMap(settings);
            final DiscoveryNodeFilters requireFilters;
            if (requireMap.isEmpty()) {
                requireFilters = null;
            } else {
                requireFilters = DiscoveryNodeFilters.buildFromKeyValue(AND, requireMap);
            }
            Map<String, String> includeMap = INDEX_ROUTING_INCLUDE_GROUP_SETTING.getAsMap(settings);
            final DiscoveryNodeFilters includeFilters;
            if (includeMap.isEmpty()) {
                includeFilters = null;
            } else {
                includeFilters = DiscoveryNodeFilters.buildFromKeyValue(OR, includeMap);
            }
            Map<String, String> excludeMap = INDEX_ROUTING_EXCLUDE_GROUP_SETTING.getAsMap(settings);
            final DiscoveryNodeFilters excludeFilters;
            if (excludeMap.isEmpty()) {
                excludeFilters = null;
            } else {
                excludeFilters = DiscoveryNodeFilters.buildFromKeyValue(OR, excludeMap);
            }
            Map<String, String> initialRecoveryMap = INDEX_ROUTING_INITIAL_RECOVERY_GROUP_SETTING.getAsMap(settings);
            final DiscoveryNodeFilters initialRecoveryFilters;
            if (initialRecoveryMap.isEmpty()) {
                initialRecoveryFilters = null;
            } else {
                initialRecoveryFilters = DiscoveryNodeFilters.buildFromKeyValue(OR, initialRecoveryMap);
            }
            Version indexCreatedVersion = Version.indexCreated(settings);
            Version indexUpgradedVersion = settings.getAsVersion(IndexMetadata.SETTING_VERSION_UPGRADED, indexCreatedVersion);

            if (primaryTerms == null) {
                initializePrimaryTerms();
            } else if (primaryTerms.length != numberOfShards) {
                throw new IllegalStateException("primaryTerms length is [" + primaryTerms.length
                    + "] but should be equal to number of shards [" + numberOfShards() + "]");
            }

            final ActiveShardCount waitForActiveShards = SETTING_WAIT_FOR_ACTIVE_SHARDS.get(settings);
            if (waitForActiveShards.validate(numberOfReplicas) == false) {
                throw new IllegalArgumentException("invalid " + SETTING_WAIT_FOR_ACTIVE_SHARDS.getKey() +
                                                   "[" + waitForActiveShards + "]: cannot be greater than " +
                                                   "number of shard copies [" + (numberOfReplicas + 1) + "]");
            }

            return new IndexMetadata(
                new Index(indexName, indexUUID),
                version,
                mappingVersion,
                settingsVersion,
                primaryTerms,
                state,
                numberOfShards,
                numberOfReplicas,
                tmpSettings,
                mapping,
                tmpAliases.build(),
                partitionValues,
                filledInSyncAllocationIds.build(),
                requireFilters,
                initialRecoveryFilters,
                includeFilters,
                excludeFilters,
                indexCreatedVersion,
                indexUpgradedVersion,
                getRoutingNumShards(),
                routingPartitionSize,
                waitForActiveShards
            );
        }

        public static IndexMetadata fromXContent(XContentParser parser) throws IOException {
            if (parser.currentToken() == null) { // fresh parser? move to the first token
                parser.nextToken();
            }
            if (parser.currentToken() == XContentParser.Token.START_OBJECT) {  // on a start object move to next token
                parser.nextToken();
            }
            if (parser.currentToken() != XContentParser.Token.FIELD_NAME) {
                throw new IllegalArgumentException("expected field name but got a " + parser.currentToken());
            }
            Builder builder = new Builder();
            builder.indexName(parser.currentName());

            String currentFieldName = null;
            XContentParser.Token token = parser.nextToken();
            if (token != XContentParser.Token.START_OBJECT) {
                throw new IllegalArgumentException("expected object but got a " + token);
            }
            boolean mappingVersion = false;
            boolean settingsVersion = false;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (token == XContentParser.Token.START_OBJECT) {
                    if (KEY_SETTINGS.equals(currentFieldName)) {
                        builder.settings(Settings.fromXContent(parser));
                    } else if (KEY_MAPPINGS.equals(currentFieldName)) {
                        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                            if (token == XContentParser.Token.FIELD_NAME) {
                                currentFieldName = parser.currentName();
                            } else if (token == XContentParser.Token.START_OBJECT) {
                                String mappingType = currentFieldName;
                                Map<String, Object> mappingSource = MapBuilder.<String, Object>newMapBuilder().put(mappingType, parser.mapOrdered()).map();
                                builder.putMapping(new MappingMetadata(mappingSource));
                            } else {
                                throw new IllegalArgumentException("Unexpected token: " + token);
                            }
                        }
                    } else if (KEY_ALIASES.equals(currentFieldName)) {
                        while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                            builder.putAlias(AliasMetadata.Builder.fromXContent(parser));
                        }
                    } else if (KEY_IN_SYNC_ALLOCATIONS.equals(currentFieldName)) {
                        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                            if (token == XContentParser.Token.FIELD_NAME) {
                                currentFieldName = parser.currentName();
                            } else if (token == XContentParser.Token.START_ARRAY) {
                                String shardId = currentFieldName;
                                Set<String> allocationIds = new HashSet<>();
                                while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                                    if (token == XContentParser.Token.VALUE_STRING) {
                                        allocationIds.add(parser.text());
                                    }
                                }
                                builder.putInSyncAllocationIds(Integer.valueOf(shardId), allocationIds);
                            } else {
                                throw new IllegalArgumentException("Unexpected token: " + token);
                            }
                        }
                    } else if ("warmers".equals(currentFieldName)) {
                        // TODO: do this in 6.0:
                        // throw new IllegalArgumentException("Warmers are not supported anymore - are you upgrading from 1.x?");
                        // ignore: warmers have been removed in 5.0 and are
                        // simply ignored when upgrading from 2.x
                        assert Version.CURRENT.major <= 5;
                        parser.skipChildren();
                    } else {
                        // assume it's custom index metadata
                        parser.mapStrings();
                    }
                } else if (token == XContentParser.Token.START_ARRAY) {
                    if (KEY_MAPPINGS.equals(currentFieldName)) {
                        while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                            if (token == XContentParser.Token.VALUE_EMBEDDED_OBJECT) {
                                builder.putMapping(new MappingMetadata(new CompressedXContent(parser.binaryValue())));
                            } else {
                                Map<String, Object> mapping = parser.mapOrdered();
                                if (mapping.size() == 1) {
                                    builder.putMapping(new MappingMetadata(mapping));
                                }
                            }
                        }
                    } else if (KEY_PRIMARY_TERMS.equals(currentFieldName)) {
                        LongArrayList list = new LongArrayList();
                        while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                            if (token == XContentParser.Token.VALUE_NUMBER) {
                                list.add(parser.longValue());
                            } else {
                                throw new IllegalStateException("found a non-numeric value under [" + KEY_PRIMARY_TERMS + "]");
                            }
                        }
                        builder.primaryTerms(list.toArray());
                    } else {
                        throw new IllegalArgumentException("Unexpected field for an array " + currentFieldName);
                    }
                } else if (token.isValue()) {
                    if (KEY_STATE.equals(currentFieldName)) {
                        builder.state(State.fromString(parser.text()));
                    } else if (KEY_VERSION.equals(currentFieldName)) {
                        builder.version(parser.longValue());
                    } else if (KEY_MAPPING_VERSION.equals(currentFieldName)) {
                        mappingVersion = true;
                        builder.mappingVersion(parser.longValue());
                    } else if (KEY_SETTINGS_VERSION.equals(currentFieldName)) {
                        settingsVersion = true;
                        builder.settingsVersion(parser.longValue());
                    } else if (KEY_ROUTING_NUM_SHARDS.equals(currentFieldName)) {
                        builder.setRoutingNumShards(parser.intValue());
                    } else {
                        throw new IllegalArgumentException("Unexpected field [" + currentFieldName + "]");
                    }
                } else {
                    throw new IllegalArgumentException("Unexpected token " + token);
                }
            }
            if (Assertions.ENABLED) {
                assert mappingVersion : "mapping version should be present for indices created on or after 6.5.0";
            }
            if (Assertions.ENABLED) {
                assert settingsVersion : "settings version should be present for indices created on or after 6.5.0";
            }

            String indexUUID = builder.settings.get(SETTING_INDEX_UUID, INDEX_UUID_NA_VALUE);
            builder.indexUUID(indexUUID);
            return builder.build();
        }
    }

    /**
     * State format for {@link IndexMetadata} to write to and load from disk
     */
    public static final MetadataStateFormat<IndexMetadata> FORMAT = new MetadataStateFormat<IndexMetadata>(INDEX_STATE_FILE_PREFIX) {

        @Override
        public IndexMetadata fromXContent(XContentParser parser) throws IOException {
            return Builder.fromXContent(parser);
        }

        @Override
        public IndexMetadata readFrom(StreamInput in) throws IOException {
            return IndexMetadata.readFrom(in);
        }
    };

    /**
     * Returns the number of shards that should be used for routing. This basically defines the hash space we use in
     * {@link org.elasticsearch.cluster.routing.OperationRouting#generateShardId(IndexMetadata, String, String)} to route documents
     * to shards based on their ID or their specific routing value. The default value is {@link #getNumberOfShards()}. This value only
     * changes if and index is shrunk.
     */
    public int getRoutingNumShards() {
        return routingNumShards;
    }

    /**
     * Returns the routing factor for this index. The default is {@code 1}.
     *
     * @see #getRoutingFactor(int, int) for details
     */
    public int getRoutingFactor() {
        return routingFactor;
    }

    /**
     * Returns the source shard ID to split the given target shard off
     * @param shardId the id of the target shard to split into
     * @param sourceIndexMetadata the source index metadata
     * @param numTargetShards the total number of shards in the target index
     * @return a the source shard ID to split off from
     */
    public static ShardId selectSplitShard(int shardId, IndexMetadata sourceIndexMetadata, int numTargetShards) {
        int numSourceShards = sourceIndexMetadata.getNumberOfShards();
        if (shardId >= numTargetShards) {
            throw new IllegalArgumentException("the number of target shards (" + numTargetShards + ") must be greater than the shard id: "
                + shardId);
        }
        final int routingFactor = getRoutingFactor(numSourceShards, numTargetShards);
        assertSplitMetadata(numSourceShards, numTargetShards, sourceIndexMetadata);
        return new ShardId(sourceIndexMetadata.getIndex(), shardId / routingFactor);
    }

    private static void assertSplitMetadata(int numSourceShards, int numTargetShards, IndexMetadata sourceIndexMetadata) {
        if (numSourceShards > numTargetShards) {
            throw new IllegalArgumentException("the number of source shards [" + numSourceShards
                + "] must be less that the number of target shards [" + numTargetShards + "]");
        }
        // now we verify that the numRoutingShards is valid in the source index
        // note: if the number of shards is 1 in the source index we can just assume it's correct since from 1 we can split into anything
        // this is important to special case here since we use this to validate this in various places in the code but allow to split form
        // 1 to N but we never modify the sourceIndexMetadata to accommodate for that
        int routingNumShards = numSourceShards == 1 ? numTargetShards : sourceIndexMetadata.getRoutingNumShards();
        if (routingNumShards % numTargetShards != 0) {
            throw new IllegalStateException("the number of routing shards ["
                + routingNumShards + "] must be a multiple of the target shards [" + numTargetShards + "]");
        }
        // this is just an additional assertion that ensures we are a factor of the routing num shards.
        assert sourceIndexMetadata.getNumberOfShards() == 1 // special case - we can split into anything from 1 shard
            || getRoutingFactor(numTargetShards, routingNumShards) >= 0;
    }

    /**
     * Selects the source shards for a local shard recovery. This might either be a split or a shrink operation.
     * @param shardId the target shard ID to select the source shards for
     * @param sourceIndexMetadata the source metadata
     * @param numTargetShards the number of target shards
     */
    public static Set<ShardId> selectRecoverFromShards(int shardId, IndexMetadata sourceIndexMetadata, int numTargetShards) {
        if (sourceIndexMetadata.getNumberOfShards() > numTargetShards) {
            return selectShrinkShards(shardId, sourceIndexMetadata, numTargetShards);
        } else if (sourceIndexMetadata.getNumberOfShards() < numTargetShards) {
            return Collections.singleton(selectSplitShard(shardId, sourceIndexMetadata, numTargetShards));
        }
        throw new IllegalArgumentException("can't select recover from shards if both indices have the same number of shards");
    }

    /**
     * Returns the source shard ids to shrink into the given shard id.
     * @param shardId the id of the target shard to shrink to
     * @param sourceIndexMetadata the source index metadata
     * @param numTargetShards the total number of shards in the target index
     * @return a set of shard IDs to shrink into the given shard ID.
     */
    public static Set<ShardId> selectShrinkShards(int shardId, IndexMetadata sourceIndexMetadata, int numTargetShards) {
        if (shardId >= numTargetShards) {
            throw new IllegalArgumentException("the number of target shards (" + numTargetShards + ") must be greater than the shard id: "
                + shardId);
        }
        if (sourceIndexMetadata.getNumberOfShards() < numTargetShards) {
            throw new IllegalArgumentException("the number of target shards [" + numTargetShards
                + "] must be less that the number of source shards [" + sourceIndexMetadata.getNumberOfShards() + "]");
        }
        int routingFactor = getRoutingFactor(sourceIndexMetadata.getNumberOfShards(), numTargetShards);
        Set<ShardId> shards = HashSet.newHashSet(routingFactor);
        for (int i = shardId * routingFactor; i < routingFactor * shardId + routingFactor; i++) {
            shards.add(new ShardId(sourceIndexMetadata.getIndex(), i));
        }
        return shards;
    }

    /**
     * Returns the routing factor for and shrunk index with the given number of target shards.
     * This factor is used in the hash function in
     * {@link org.elasticsearch.cluster.routing.OperationRouting#generateShardId(IndexMetadata, String, String)} to guarantee consistent
     * hashing / routing of documents even if the number of shards changed (ie. a shrunk index).
     *
     * @param sourceNumberOfShards the total number of shards in the source index
     * @param targetNumberOfShards the total number of shards in the target index
     * @return the routing factor for and shrunk index with the given number of target shards.
     * @throws IllegalArgumentException if the number of source shards is less than the number of target shards or if the source shards
     * are not divisible by the number of target shards.
     */
    public static int getRoutingFactor(int sourceNumberOfShards, int targetNumberOfShards) {
        final int factor;
        if (sourceNumberOfShards < targetNumberOfShards) { // split
            factor = targetNumberOfShards / sourceNumberOfShards;
            if (factor * sourceNumberOfShards != targetNumberOfShards || factor <= 1) {
                throw new IllegalArgumentException("the number of source shards [" + sourceNumberOfShards + "] must be a must be a " +
                    "factor of ["
                    + targetNumberOfShards + "]");
            }
        } else if (sourceNumberOfShards > targetNumberOfShards) { // shrink
            factor = sourceNumberOfShards / targetNumberOfShards;
            if (factor * targetNumberOfShards != sourceNumberOfShards || factor <= 1) {
                throw new IllegalArgumentException("the number of source shards [" + sourceNumberOfShards + "] must be a must be a " +
                    "multiple of ["
                    + targetNumberOfShards + "]");
            }
        } else {
            factor = 1;
        }
        return factor;
    }


    public static boolean isIndexVerifiedBeforeClosed(final IndexMetadata indexMetadata) {
        return indexMetadata.getState() == IndexMetadata.State.CLOSE
            && VERIFIED_BEFORE_CLOSE_SETTING.exists(indexMetadata.getSettings())
            && VERIFIED_BEFORE_CLOSE_SETTING.get(indexMetadata.getSettings());
    }
}
