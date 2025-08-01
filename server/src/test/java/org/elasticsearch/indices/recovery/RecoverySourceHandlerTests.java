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

package org.elasticsearch.indices.recovery;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.elasticsearch.index.engine.Engine.Operation.Origin.PRIMARY;
import static org.elasticsearch.index.seqno.SequenceNumbers.UNASSIGNED_SEQ_NO;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.zip.CRC32;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.tests.store.BaseDirectoryWrapper;
import org.apache.lucene.util.SetOnce;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.PlainFuture;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.Numbers;
import org.elasticsearch.common.Randomness;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.FileSystemUtils;
import org.elasticsearch.common.lease.Releasable;
import org.elasticsearch.common.lucene.store.IndexOutputOutputStream;
import org.elasticsearch.common.lucene.uid.Versions;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.CancellableThreads;
import org.elasticsearch.common.util.concurrent.FutureUtils;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.engine.RecoveryEngineException;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.elasticsearch.index.mapper.SequenceIDFields;
import org.elasticsearch.index.mapper.Uid;
import org.elasticsearch.index.seqno.ReplicationTracker;
import org.elasticsearch.index.seqno.RetentionLease;
import org.elasticsearch.index.seqno.RetentionLeases;
import org.elasticsearch.index.seqno.SeqNoStats;
import org.elasticsearch.index.seqno.SequenceNumbers;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.IndexShardRelocatedException;
import org.elasticsearch.index.shard.IndexShardState;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.store.Store;
import org.elasticsearch.index.store.StoreFileMetadata;
import org.elasticsearch.index.translog.Translog;
import org.elasticsearch.test.CorruptionUtils;
import org.elasticsearch.test.DummyShardLock;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.IndexSettingsModule;
import org.elasticsearch.test.VersionUtils;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.crate.common.collections.Sets;
import io.crate.common.io.IOUtils;
import io.crate.common.unit.TimeValue;
import io.crate.concurrent.FutureActionListener;
import io.crate.metadata.doc.SysColumns;

public class RecoverySourceHandlerTests extends ESTestCase {
    private static final IndexSettings INDEX_SETTINGS = IndexSettingsModule.newIndexSettings(
        "index",
        Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, org.elasticsearch.Version.CURRENT)
            .build()
    );
    private final ShardId shardId = new ShardId(INDEX_SETTINGS.getIndex(), 1);
    private final ClusterSettings service = new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);

    private ThreadPool threadPool;
    private Executor recoveryExecutor;

    @Before
    public void setUpThreadPool() {
        threadPool = new TestThreadPool(getTestName());
        recoveryExecutor = threadPool.generic();
    }

    @After
    public void tearDownThreadPool() {
        terminate(threadPool);
    }

    @Test
    public void testSendFiles() throws Throwable {
        final RecoverySettings recoverySettings = new RecoverySettings(Settings.EMPTY, service);
        final StartRecoveryRequest request = getStartRecoveryRequest();
        Store store = newStore(createTempDir());
        Directory dir = store.directory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir, newIndexWriterConfig());
        int numDocs = randomIntBetween(10, 100);
        for (int i = 0; i < numDocs; i++) {
            Document document = new Document();
            document.add(new StringField("id", Integer.toString(i), Field.Store.YES));
            document.add(newField("field", randomUnicodeOfCodepointLengthBetween(1, 10), TextField.TYPE_STORED));
            writer.addDocument(document);
        }
        writer.commit();
        writer.close();

        Store.MetadataSnapshot metadata = store.getMetadata(null);
        List<StoreFileMetadata> metas = new ArrayList<>();
        for (StoreFileMetadata md : metadata) {
            metas.add(md);
        }
        Store targetStore = newStore(createTempDir());
        MultiFileWriter multiFileWriter = new MultiFileWriter(targetStore, mock(RecoveryState.Index.class), "", logger, () -> {});
        RecoveryTargetHandler target = new TestRecoveryTargetHandler() {

            @Override
            public void writeFileChunk(StoreFileMetadata md,
                                       long position,
                                       BytesReference content,
                                       boolean lastChunk,
                                       int totalTranslogOps,
                                       ActionListener<Void> listener) {
                try {
                    multiFileWriter.writeFileChunk(md, position, content, lastChunk);
                    listener.onResponse(null);
                } catch (Exception ex) {
                    listener.onFailure(ex);
                }
            }
        };
        RecoverySourceHandler handler = new RecoverySourceHandler(null, new AsyncRecoveryTarget(target, recoveryExecutor),
            threadPool, request, Math.toIntExact(recoverySettings.getChunkSize().getBytes()), between(1, 5), between(1, 5));
        PlainFuture<Void> sendFilesFuture = new PlainFuture<>();
        handler.sendFiles(store, metas.toArray(new StoreFileMetadata[0]), () -> 0, sendFilesFuture);
        FutureUtils.get(sendFilesFuture, (long) 5, TimeUnit.SECONDS);
        Store.MetadataSnapshot targetStoreMetadata = targetStore.getMetadata(null);
        Store.RecoveryDiff recoveryDiff = targetStoreMetadata.recoveryDiff(metadata);
        assertThat(recoveryDiff.identical.size()).isEqualTo(metas.size());
        assertThat(recoveryDiff.different.size()).isEqualTo(0);
        assertThat(recoveryDiff.missing.size()).isEqualTo(0);
        IndexReader reader = DirectoryReader.open(targetStore.directory());
        assertThat(reader.maxDoc()).isEqualTo(numDocs);
        IOUtils.close(reader, store, multiFileWriter, targetStore);
    }

    private StartRecoveryRequest getStartRecoveryRequest() throws IOException {
        Store.MetadataSnapshot metadataSnapshot = randomBoolean() ? Store.MetadataSnapshot.EMPTY :
            new Store.MetadataSnapshot(Collections.emptyMap(),
                                       Collections.singletonMap(Engine.HISTORY_UUID_KEY, UUIDs.randomBase64UUID()), randomIntBetween(0, 100));
        return new StartRecoveryRequest(
            shardId,
            null,
            new DiscoveryNode("b", buildNewFakeTransportAddress(), emptyMap(), emptySet(), Version.CURRENT),
            new DiscoveryNode("b", buildNewFakeTransportAddress(), emptyMap(), emptySet(), Version.CURRENT),
            metadataSnapshot,
            randomBoolean(),
            randomNonNegativeLong(),
            randomBoolean() || metadataSnapshot.getHistoryUUID() == null ?
                SequenceNumbers.UNASSIGNED_SEQ_NO : randomNonNegativeLong());
    }

    @Test
    public void testSendSnapshotSendsOps() throws IOException {
        final int fileChunkSizeInBytes = between(1, 4096);
        final StartRecoveryRequest request = getStartRecoveryRequest();
        final IndexShard shard = mock(IndexShard.class);
        when(shard.state()).thenReturn(IndexShardState.STARTED);
        final List<Translog.Operation> operations = new ArrayList<>();
        final int initialNumberOfDocs = randomIntBetween(10, 1000);
        for (int i = 0; i < initialNumberOfDocs; i++) {
            final Engine.Index index = getIndex(Integer.toString(i));
            operations.add(new Translog.Index(index, new Engine.IndexResult(1, 1, SequenceNumbers.UNASSIGNED_SEQ_NO, true)));
        }
        final int numberOfDocsWithValidSequenceNumbers = randomIntBetween(10, 1000);
        for (int i = initialNumberOfDocs; i < initialNumberOfDocs + numberOfDocsWithValidSequenceNumbers; i++) {
            final Engine.Index index = getIndex(Integer.toString(i));
            operations.add(new Translog.Index(index, new Engine.IndexResult(1, 1, i - initialNumberOfDocs, true)));
        }
        final long startingSeqNo = randomIntBetween(0, numberOfDocsWithValidSequenceNumbers - 1);
        final long endingSeqNo = randomLongBetween(startingSeqNo, numberOfDocsWithValidSequenceNumbers - 1);

        final Queue<Translog.Operation> shippedOps = new ConcurrentLinkedQueue<>();
        final AtomicLong checkpointOnTarget = new AtomicLong(SequenceNumbers.NO_OPS_PERFORMED);
        RecoveryTargetHandler recoveryTarget = new TestRecoveryTargetHandler() {
            @Override
            public void indexTranslogOperations(List<Translog.Operation> operations,
                                                int totalTranslogOps,
                                                long timestamp,
                                                long msu,
                                                RetentionLeases retentionLeases,
                                                long mappingVersion,
                                                ActionListener<Long> listener) {
                shippedOps.addAll(operations);
                if (randomBoolean()) {
                    checkpointOnTarget.addAndGet(between(1, 20));
                }
                listener.onResponse(checkpointOnTarget.get());
            }
        };
        RecoverySourceHandler handler = new RecoverySourceHandler(shard, new AsyncRecoveryTarget(recoveryTarget, threadPool.generic()),
            threadPool, request, fileChunkSizeInBytes, between(1, 10), between(1, 10));
        PlainFuture<RecoverySourceHandler.SendSnapshotResult> future = new PlainFuture<>();
        handler.phase2(
            startingSeqNo,
            endingSeqNo,
            newTranslogSnapshot(operations, Collections.emptyList()),
            randomNonNegativeLong(),
            randomNonNegativeLong(),
            RetentionLeases.EMPTY,
            randomNonNegativeLong(),
            future
        );
        final int expectedOps = (int) (endingSeqNo - startingSeqNo + 1);
        RecoverySourceHandler.SendSnapshotResult result = FutureUtils.get(future);
        assertThat(result.sentOperations).isEqualTo(expectedOps);
        List<Translog.Operation> sortedShippedOps = shippedOps.stream()
            .sorted(Comparator.comparing(Translog.Operation::seqNo))
            .toList();
        assertThat(shippedOps).hasSize(expectedOps);
        for (int i = 0; i < shippedOps.size(); i++) {
            assertThat(sortedShippedOps.get(i)).isEqualTo(operations.get(i + (int) startingSeqNo + initialNumberOfDocs));
        }
        assertThat(result.targetLocalCheckpoint).isEqualTo(checkpointOnTarget.get());
    }

    @Test
    public void testSendSnapshotStopOnError() throws Exception {
        final int fileChunkSizeInBytes = between(1, 10 * 1024);
        final StartRecoveryRequest request = getStartRecoveryRequest();
        final IndexShard shard = mock(IndexShard.class);
        when(shard.state()).thenReturn(IndexShardState.STARTED);
        final List<Translog.Operation> ops = new ArrayList<>();
        for (int numOps = between(1, 256), i = 0; i < numOps; i++) {
            final Engine.Index index = getIndex(Integer.toString(i));
            ops.add(new Translog.Index(index, new Engine.IndexResult(1, 1, i, true)));
        }
        final AtomicBoolean wasFailed = new AtomicBoolean();
        RecoveryTargetHandler recoveryTarget = new TestRecoveryTargetHandler() {
            @Override
            public void indexTranslogOperations(List<Translog.Operation> operations,
                                                int totalTranslogOps,
                                                long timestamp,
                                                long msu,
                                                RetentionLeases retentionLeases,
                                                long mappingVersion,
                                                ActionListener<Long> listener) {
                if (randomBoolean()) {
                    listener.onResponse(SequenceNumbers.NO_OPS_PERFORMED);
                } else {
                    listener.onFailure(new RuntimeException("test - failed to index"));
                    wasFailed.set(true);
                }
            }
        };
        RecoverySourceHandler handler = new RecoverySourceHandler(shard, new AsyncRecoveryTarget(recoveryTarget, threadPool.generic()),
            threadPool, request, fileChunkSizeInBytes, between(1, 10), between(1, 10));
        PlainFuture<RecoverySourceHandler.SendSnapshotResult> future = new PlainFuture<>();
        final long startingSeqNo = randomLongBetween(0, ops.size() - 1L);
        final long endingSeqNo = randomLongBetween(startingSeqNo, ops.size() - 1L);
        handler.phase2(
            startingSeqNo,
            endingSeqNo,
            newTranslogSnapshot(ops, Collections.emptyList()),
            randomNonNegativeLong(),
            randomNonNegativeLong(),
            RetentionLeases.EMPTY,
            randomNonNegativeLong(),
            future
        );
        if (wasFailed.get()) {
            assertThatThrownBy(future::get)
                .cause()
                .isExactlyInstanceOf(RecoveryEngineException.class)
                .hasMessage("Phase[2] failed to send/replay operations")
                .cause()
                .hasMessage("test - failed to index");
        }
    }

    @Test
    public void testSendOperationsConcurrently() throws Throwable {
        final IndexShard shard = mock(IndexShard.class);
        when(shard.state()).thenReturn(IndexShardState.STARTED);
        Set<Long> receivedSeqNos = Sets.newConcurrentHashSet();
        long maxSeenAutoIdTimestamp = randomBoolean() ? -1 : randomNonNegativeLong();
        long maxSeqNoOfUpdatesOrDeletes = randomBoolean() ? -1 : randomNonNegativeLong();
        RetentionLeases retentionLeases = new RetentionLeases(randomNonNegativeLong(), randomNonNegativeLong(), Collections.emptySet());
        long mappingVersion = randomNonNegativeLong();
        AtomicLong localCheckpoint = new AtomicLong(SequenceNumbers.NO_OPS_PERFORMED);
        int numOps = randomIntBetween(0, 1000);
        AtomicBoolean received = new AtomicBoolean();
        RecoveryTargetHandler target = new TestRecoveryTargetHandler() {
            @Override
            public void indexTranslogOperations(List<Translog.Operation> operations, int receivedTotalOps,
                                                long receivedMaxSeenAutoIdTimestamp, long receivedMaxSeqNoOfUpdatesOrDeletes,
                                                RetentionLeases receivedRetentionLease, long receivedMappingVersion,
                                                ActionListener<Long> listener) {
                received.set(true);
                assertThat(receivedMaxSeenAutoIdTimestamp).isEqualTo(maxSeenAutoIdTimestamp);
                assertThat(receivedMaxSeqNoOfUpdatesOrDeletes).isEqualTo(maxSeqNoOfUpdatesOrDeletes);
                assertThat(receivedRetentionLease).isEqualTo(retentionLeases);
                assertThat(receivedMappingVersion).isEqualTo(mappingVersion);
                assertThat(receivedTotalOps).isEqualTo(numOps);
                for (Translog.Operation operation : operations) {
                    receivedSeqNos.add(operation.seqNo());
                }
                if (randomBoolean()) {
                    localCheckpoint.addAndGet(randomIntBetween(1, 100));
                }
                listener.onResponse(localCheckpoint.get());
            }
        };

        PlainFuture<RecoverySourceHandler.SendSnapshotResult> sendFuture = new PlainFuture<>();
        long startingSeqNo = randomIntBetween(0, 1000);
        long endingSeqNo = startingSeqNo + randomIntBetween(0, 10000);
        List<Translog.Operation> operations = generateOperations(numOps);
        Randomness.shuffle(operations);
        List<Translog.Operation> skipOperations = randomSubsetOf(operations);
        Translog.Snapshot snapshot = newTranslogSnapshot(operations, skipOperations);
        RecoverySourceHandler handler = new RecoverySourceHandler(shard, new AsyncRecoveryTarget(target, recoveryExecutor),
            threadPool, getStartRecoveryRequest(), between(1, 10 * 1024), between(1, 5), between(1, 5));
        handler.phase2(startingSeqNo, endingSeqNo, snapshot, maxSeenAutoIdTimestamp, maxSeqNoOfUpdatesOrDeletes, retentionLeases,
            mappingVersion, sendFuture);
        RecoverySourceHandler.SendSnapshotResult sendSnapshotResult = FutureUtils.get(sendFuture);
        assertThat(received.get()).isTrue();
        assertThat(sendSnapshotResult.targetLocalCheckpoint).isEqualTo(localCheckpoint.get());
        assertThat(sendSnapshotResult.sentOperations).isEqualTo(receivedSeqNos.size());
        Set<Long> sentSeqNos = new HashSet<>();
        for (Translog.Operation op : operations) {
            if (startingSeqNo <= op.seqNo() && op.seqNo() <= endingSeqNo && skipOperations.contains(op) == false) {
                sentSeqNos.add(op.seqNo());
            }
        }
        assertThat(receivedSeqNos).isEqualTo(sentSeqNos);
    }

    private Engine.Index getIndex(final String id) {
        final Document document = new Document();
        document.add(new TextField("test", "test", Field.Store.YES));
        final Field idField = new Field(
            SysColumns.ID.COLUMN.name(),
            Uid.encodeId(id),
            SysColumns.ID.FIELD_TYPE
        );
        final Field versionField = new NumericDocValuesField("_version", Versions.MATCH_ANY);
        final SequenceIDFields seqID = SequenceIDFields.emptySeqID();
        document.add(idField);
        document.add(versionField);
        document.add(seqID.seqNo);
        document.add(seqID.seqNoDocValue);
        document.add(seqID.primaryTerm);
        final BytesReference source = new BytesArray(new byte[] { 1 });
        final ParsedDocument doc =
            new ParsedDocument(versionField, seqID, id, document, source);
        return new Engine.Index(
            new Term("_id", Uid.encodeId(doc.id())), doc, UNASSIGNED_SEQ_NO, 0,
            Versions.MATCH_ANY, VersionType.INTERNAL, PRIMARY, System.nanoTime(), -1, false, UNASSIGNED_SEQ_NO, 0);

    }

    @Test
    public void testHandleCorruptedIndexOnSendSendFiles() throws Throwable {
        Settings settings = Settings.builder()
            .put("indices.recovery.concurrent_streams", 1)
            .put("indices.recovery.concurrent_small_file_streams", 1)
            .build();
        final RecoverySettings recoverySettings = new RecoverySettings(settings, service);
        final StartRecoveryRequest request = getStartRecoveryRequest();
        Path tempDir = createTempDir();
        Store store = newStore(tempDir, false);
        AtomicBoolean failedEngine = new AtomicBoolean(false);
        Directory dir = store.directory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir, newIndexWriterConfig());
        int numDocs = randomIntBetween(10, 100);
        for (int i = 0; i < numDocs; i++) {
            Document document = new Document();
            document.add(new StringField("id", Integer.toString(i), Field.Store.YES));
            document.add(newField("field", randomUnicodeOfCodepointLengthBetween(1, 10), TextField.TYPE_STORED));
            writer.addDocument(document);
        }
        writer.commit();
        writer.close();

        Store.MetadataSnapshot metadata = store.getMetadata(null);
        List<StoreFileMetadata> metas = new ArrayList<>();
        for (StoreFileMetadata md : metadata) {
            metas.add(md);
        }

        CorruptionUtils.corruptFile(random(), FileSystemUtils.files(tempDir, (p) ->
            (p.getFileName().toString().equals("write.lock") ||
             p.getFileName().toString().startsWith("extra")) == false));
        Store targetStore = newStore(createTempDir(), false);
        MultiFileWriter multiFileWriter = new MultiFileWriter(targetStore, mock(RecoveryState.Index.class), "", logger, () -> {});
        RecoveryTargetHandler target = new TestRecoveryTargetHandler() {

            @Override
            public void writeFileChunk(StoreFileMetadata md,
                                       long position,
                                       BytesReference content,
                                       boolean lastChunk,
                                       int totalTranslogOps,
                                       ActionListener<Void> listener) {
                 try {
                     multiFileWriter.writeFileChunk(md, position, content, lastChunk);
                     listener.onResponse(null);
                 } catch (Exception ex) {
                     listener.onFailure(ex);
                 }
            }
        };
        RecoverySourceHandler handler = new RecoverySourceHandler(null, new AsyncRecoveryTarget(target, recoveryExecutor), threadPool,
            request, Math.toIntExact(recoverySettings.getChunkSize().getBytes()), between(1, 8), between(1, 8)) {
            @Override
            protected void failEngine(IOException cause) {
                assertThat(failedEngine.get()).isFalse();
                failedEngine.set(true);
            }
        };
        FutureActionListener<Void> future = new FutureActionListener<>();
        handler.sendFiles(store, metas.toArray(new StoreFileMetadata[0]), () -> 0, future);
        assertThat(future).failsWithin(1, TimeUnit.SECONDS)
            .withThrowableThat()
            .withRootCauseExactlyInstanceOf(CorruptIndexException.class);
        assertThat(failedEngine.get()).isTrue();
        // ensure all chunk requests have been completed; otherwise some files on the target are left open.
        IOUtils.close(() -> terminate(threadPool), () -> threadPool = null);
        IOUtils.close(store, multiFileWriter, targetStore);
    }

    @Test
    public void testHandleExceptionOnSendFiles() throws Throwable {
        final RecoverySettings recoverySettings = new RecoverySettings(Settings.EMPTY, service);
        final StartRecoveryRequest request = getStartRecoveryRequest();
        Path tempDir = createTempDir();
        Store store = newStore(tempDir, false);
        AtomicBoolean failedEngine = new AtomicBoolean(false);
        Directory dir = store.directory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir, newIndexWriterConfig());
        int numDocs = randomIntBetween(10, 100);
        for (int i = 0; i < numDocs; i++) {
            Document document = new Document();
            document.add(new StringField("id", Integer.toString(i), Field.Store.YES));
            document.add(newField("field", randomUnicodeOfCodepointLengthBetween(1, 10), TextField.TYPE_STORED));
            writer.addDocument(document);
        }
        writer.commit();
        writer.close();

        Store.MetadataSnapshot metadata = store.getMetadata(null);
        List<StoreFileMetadata> metas = new ArrayList<>();
        for (StoreFileMetadata md : metadata) {
            metas.add(md);
        }
        final boolean throwCorruptedIndexException = randomBoolean();
        RecoveryTargetHandler target = new TestRecoveryTargetHandler() {
            @Override
            public void writeFileChunk(StoreFileMetadata md,
                                       long position,
                                       BytesReference content,
                                       boolean lastChunk,
                                       int totalTranslogOps,
                                       ActionListener<Void> listener) {
                if (throwCorruptedIndexException) {
                    listener.onFailure(new RuntimeException(new CorruptIndexException("foo", "bar")));
                } else {
                    listener.onFailure(new RuntimeException("boom"));
                }
            }
        };
        RecoverySourceHandler handler = new RecoverySourceHandler(null, new AsyncRecoveryTarget(target, recoveryExecutor), threadPool,
            request, Math.toIntExact(recoverySettings.getChunkSize().getBytes()), between(1, 10), between(1, 4)) {
            @Override
            protected void failEngine(IOException cause) {
                assertThat(failedEngine.get()).isFalse();
                failedEngine.set(true);
            }
        };
        PlainFuture<Void> sendFilesFuture = new PlainFuture<>();
        handler.sendFiles(store, metas.toArray(new StoreFileMetadata[0]), () -> 0, sendFilesFuture);
        assertThatThrownBy(sendFilesFuture::get)
            .rootCause()
            .hasMessageContaining(throwCorruptedIndexException ? "[File corruption occurred on recovery but checksums are ok]" : "boom");
        assertThat(failedEngine.get()).isFalse();
        IOUtils.close(store);
    }

    @Test
    public void testThrowExceptionOnPrimaryRelocatedBeforePhase1Started() throws IOException {
        final RecoverySettings recoverySettings = new RecoverySettings(Settings.EMPTY, service);
        final StartRecoveryRequest request = getStartRecoveryRequest();
        final IndexShard shard = mock(IndexShard.class);
        when(shard.seqNoStats()).thenReturn(mock(SeqNoStats.class));
        when(shard.isRelocatedPrimary()).thenReturn(true);
        when(shard.acquireSafeIndexCommit()).thenReturn(mock(Engine.IndexCommitRef.class));
        doAnswer(invocation -> {
            //noinspection unchecked
            ((ActionListener<Releasable>)invocation.getArguments()[0]).onResponse(() -> {});
            return null;
        }).when(shard).acquirePrimaryOperationPermit(any(), anyString(), any());

        final IndexMetadata.Builder indexMetadata = IndexMetadata.builder("test").settings(Settings.builder()
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, between(0,5))
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, between(1,5))
            .put(IndexMetadata.SETTING_VERSION_CREATED, VersionUtils.randomVersion(random()))
            .put(IndexMetadata.SETTING_INDEX_UUID, UUIDs.randomBase64UUID(random())));
        if (randomBoolean()) {
            indexMetadata.state(IndexMetadata.State.CLOSE);
        }
        when(shard.indexSettings()).thenReturn(new IndexSettings(indexMetadata.build(), Settings.EMPTY));

        final AtomicBoolean phase1Called = new AtomicBoolean();
        final AtomicBoolean prepareTargetForTranslogCalled = new AtomicBoolean();
        final AtomicBoolean phase2Called = new AtomicBoolean();
        final RecoverySourceHandler handler = new RecoverySourceHandler(
                shard,
                mock(RecoveryTargetHandler.class),
                threadPool,
                request,
                Math.toIntExact(recoverySettings.getChunkSize().getBytes()),
                between(1, 8), between(1, 8)) {

            @Override
            void phase1(IndexCommit snapshot, long startingSeqNo, IntSupplier translogOps, ActionListener<SendFileResult> listener) {
                phase1Called.set(true);
                super.phase1(snapshot, startingSeqNo, translogOps, listener);
            }

            @Override
            void prepareTargetForTranslog(int totalTranslogOps,
                                          ActionListener<TimeValue> listener) {
                prepareTargetForTranslogCalled.set(true);
                super.prepareTargetForTranslog(totalTranslogOps, listener);
            }

            @Override
            void phase2(long startingSeqNo,
                        long endingSeqNo,
                        Translog.Snapshot snapshot,
                        long maxSeenAutoIdTimestamp,
                        long maxSeqNoOfUpdatesOrDeletes,
                        RetentionLeases retentionLeases,
                        long mappingVersion,
                        ActionListener<SendSnapshotResult> listener) throws IOException {
                phase2Called.set(true);
                super.phase2(
                    startingSeqNo,
                    endingSeqNo,
                    snapshot,
                    maxSeenAutoIdTimestamp,
                    maxSeqNoOfUpdatesOrDeletes,
                    retentionLeases,
                    mappingVersion,
                    listener);
            }

        };
        PlainFuture<RecoveryResponse> future = new PlainFuture<>();
        assertThatThrownBy(() -> {
            handler.recoverToTarget(future);
            FutureUtils.get(future);
        }).isExactlyInstanceOf(IndexShardRelocatedException.class);
        assertThat(phase1Called.get()).isFalse();
        assertThat(prepareTargetForTranslogCalled.get()).isFalse();
        assertThat(phase2Called.get()).isFalse();
    }

    @Test
    public void testSendFileChunksConcurrently() throws Exception {
        final IndexShard shard = mock(IndexShard.class);
        when(shard.state()).thenReturn(IndexShardState.STARTED);
        final List<FileChunkResponse> unrepliedChunks = new CopyOnWriteArrayList<>();
        final AtomicInteger sentChunks = new AtomicInteger();
        final TestRecoveryTargetHandler recoveryTarget = new TestRecoveryTargetHandler() {
            final AtomicLong chunkNumberGenerator = new AtomicLong();
            @Override
            public void writeFileChunk(StoreFileMetadata md, long position, BytesReference content, boolean lastChunk,
                                       int totalTranslogOps, ActionListener<Void> listener) {
                final long chunkNumber = chunkNumberGenerator.getAndIncrement();
                logger.info("--> write chunk name={} seq={}, position={}", md.name(), chunkNumber, position);
                unrepliedChunks.add(new FileChunkResponse(chunkNumber, listener));
                sentChunks.incrementAndGet();
            }
        };
        final int maxConcurrentChunks = between(1, 8);
        final int chunkSize = between(1, 32);
        final RecoverySourceHandler handler = new RecoverySourceHandler(shard, recoveryTarget, threadPool, getStartRecoveryRequest(),
            chunkSize, maxConcurrentChunks, between(1, 10));
        Store store = newStore(createTempDir(), false);
        List<StoreFileMetadata> files = generateFiles(store, between(1, 10), () -> between(1, chunkSize * 20));
        int totalChunks = files.stream().mapToInt(md -> ((int) md.length() + chunkSize - 1) / chunkSize).sum();
        PlainFuture<Void> sendFilesFuture = new PlainFuture<>();
        handler.sendFiles(store, files.toArray(new StoreFileMetadata[0]), () -> 0, sendFilesFuture);
        assertBusy(() -> {
            assertThat(sentChunks.get()).isEqualTo(Math.min(totalChunks, maxConcurrentChunks));
            assertThat(unrepliedChunks).hasSize(sentChunks.get());
        });

        List<FileChunkResponse> ackedChunks = new ArrayList<>();
        while (sentChunks.get() < totalChunks || unrepliedChunks.isEmpty() == false) {
            List<FileChunkResponse> chunksToAck = randomSubsetOf(between(1, unrepliedChunks.size()), unrepliedChunks);
            unrepliedChunks.removeAll(chunksToAck);
            ackedChunks.addAll(chunksToAck);
            ackedChunks.sort(Comparator.comparing(c -> c.chunkNumber));
            int checkpoint = -1;
            for (int i = 0; i < ackedChunks.size(); i++) {
                if (i != ackedChunks.get(i).chunkNumber) {
                    break;
                } else {
                    checkpoint = i;
                }
            }
            int chunksToSend = Math.min(
                totalChunks - sentChunks.get(),                             // limited by the remaining chunks
                maxConcurrentChunks - (sentChunks.get() - 1 - checkpoint)); // limited by the buffering chunks

            int expectedSentChunks = sentChunks.get() + chunksToSend;
            int expectedUnrepliedChunks = unrepliedChunks.size() + chunksToSend;
            chunksToAck.forEach(c -> c.listener.onResponse(null));
            assertBusy(() -> {
                assertThat(sentChunks.get()).isEqualTo(expectedSentChunks);
                assertThat(unrepliedChunks).hasSize(expectedUnrepliedChunks);
            });
        }
        FutureUtils.get(sendFilesFuture);
        store.close();
    }

    @Test
    public void testSendFileChunksStopOnError() throws Exception {
        final List<FileChunkResponse> unrepliedChunks = new CopyOnWriteArrayList<>();
        final AtomicInteger sentChunks = new AtomicInteger();
        final TestRecoveryTargetHandler recoveryTarget = new TestRecoveryTargetHandler() {
            final AtomicLong chunkNumberGenerator = new AtomicLong();
            @Override
            public void writeFileChunk(StoreFileMetadata md, long position, BytesReference content, boolean lastChunk,
                                       int totalTranslogOps, ActionListener<Void> listener) {
                final long chunkNumber = chunkNumberGenerator.getAndIncrement();
                logger.info("--> write chunk name={} seq={}, position={}", md.name(), chunkNumber, position);
                unrepliedChunks.add(new FileChunkResponse(chunkNumber, listener));
                sentChunks.incrementAndGet();
            }
        };
        final int maxConcurrentChunks = between(1, 4);
        final int chunkSize = between(1, 16);
        final RecoverySourceHandler handler = new RecoverySourceHandler(null, new AsyncRecoveryTarget(recoveryTarget, recoveryExecutor),
            threadPool, getStartRecoveryRequest(), chunkSize, maxConcurrentChunks, between(1, 5));
        Store store = newStore(createTempDir(), false);
        List<StoreFileMetadata> files = generateFiles(store, between(1, 10), () -> between(1, chunkSize * 20));
        int totalChunks = files.stream().mapToInt(md -> ((int) md.length() + chunkSize - 1) / chunkSize).sum();
        FutureActionListener<Void> future = new FutureActionListener<>();
        handler.sendFiles(store, files.toArray(new StoreFileMetadata[0]), () -> 0, future);
        assertBusy(() -> assertThat(sentChunks.get()).isEqualTo(Math.min(totalChunks, maxConcurrentChunks)));
        List<FileChunkResponse> failedChunks = randomSubsetOf(between(1, unrepliedChunks.size()), unrepliedChunks);
        CountDownLatch replyLatch = new CountDownLatch(failedChunks.size());
        failedChunks.forEach(c -> {
            c.listener.onFailure(new IllegalStateException("test chunk exception"));
            replyLatch.countDown();
        });
        replyLatch.await();
        unrepliedChunks.removeAll(failedChunks);
        unrepliedChunks.forEach(c -> {
            if (randomBoolean()) {
                c.listener.onFailure(new RuntimeException("test"));
            } else {
                c.listener.onResponse(null);
            }
        });
        assertThat(future).failsWithin(1, TimeUnit.SECONDS)
            .withThrowableThat()
            .withRootCauseExactlyInstanceOf(IllegalStateException.class)
            .withMessageContaining("test chunk exception");
        assertThat(sentChunks.get()).as("no more chunks should be sent").isEqualTo(Math.min(totalChunks, maxConcurrentChunks));
        store.close();
    }

    @Test
    public void testCancellationsDoesNotLeakPrimaryPermits() throws Exception {
        final CancellableThreads cancellableThreads = new CancellableThreads();
        final IndexShard shard = mock(IndexShard.class);
        final AtomicBoolean freed = new AtomicBoolean(true);
        when(shard.isRelocatedPrimary()).thenReturn(false);
        doAnswer(invocation -> {
            freed.set(false);
            //noinspection unchecked
            ((ActionListener<Releasable>)invocation.getArguments()[0]).onResponse(() -> freed.set(true));
            return null;
        }).when(shard).acquirePrimaryOperationPermit(any(), anyString(), any());

        Thread cancelingThread = new Thread(() -> cancellableThreads.cancel("test"));
        cancelingThread.start();
        try {
            RecoverySourceHandler.runUnderPrimaryPermit(() -> {}, "test", shard, cancellableThreads, logger);
        } catch (CancellableThreads.ExecutionCancelledException e) {
            // expected.
        }
        cancelingThread.join();
        // we have to use assert busy as we may be interrupted while acquiring the permit, if so we want to check
        // that the permit is released.
        assertBusy(() -> assertThat(freed.get()).isTrue());
    }

    @Test
    public void testCancelRecoveryDuringPhase1() throws Exception {
        Store store = newStore(createTempDir("source"), false);
        IndexShard shard = mock(IndexShard.class);
        when(shard.store()).thenReturn(store);
        Directory dir = store.directory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir, newIndexWriterConfig());
        int numDocs = randomIntBetween(10, 100);
        for (int i = 0; i < numDocs; i++) {
            Document document = new Document();
            document.add(new StringField("id", Integer.toString(i), Field.Store.YES));
            document.add(newField("field", randomUnicodeOfCodepointLengthBetween(1, 10), TextField.TYPE_STORED));
            writer.addDocument(document);
        }
        writer.commit();
        writer.close();
        AtomicBoolean wasCancelled = new AtomicBoolean();
        SetOnce<Runnable> cancelRecovery = new SetOnce<>();
        final TestRecoveryTargetHandler recoveryTarget = new TestRecoveryTargetHandler() {
            @Override
            public void receiveFileInfo(List<String> phase1FileNames, List<Long> phase1FileSizes, List<String> phase1ExistingFileNames,
                                        List<Long> phase1ExistingFileSizes, int totalTranslogOps, ActionListener<Void> listener) {
                recoveryExecutor.execute(() -> listener.onResponse(null));
                if (randomBoolean()) {
                    wasCancelled.set(true);
                    cancelRecovery.get().run();
                }
            }

            @Override
            public void writeFileChunk(StoreFileMetadata md,
                                       long position,
                                       BytesReference content,
                                       boolean lastChunk,
                                       int totalTranslogOps,
                                       ActionListener<Void> listener) {
                recoveryExecutor.execute(() -> listener.onResponse(null));
                if (rarely()) {
                    wasCancelled.set(true);
                    cancelRecovery.get().run();
                }
            }

            @Override
            public void cleanFiles(int totalTranslogOps,
                                   long globalCheckpoint,
                                   Store.MetadataSnapshot sourceMetadata,
                                   ActionListener<Void> listener) {
                recoveryExecutor.execute(() -> listener.onResponse(null));
                if (randomBoolean()) {
                    wasCancelled.set(true);
                    cancelRecovery.get().run();
                }
            }
        };
        StartRecoveryRequest startRecoveryRequest = getStartRecoveryRequest();
        final RecoverySourceHandler handler = new RecoverySourceHandler(
            shard, recoveryTarget, threadPool, startRecoveryRequest, between(1, 16), between(1, 4), between(1, 4)) {
            @Override
            void createRetentionLease(long startingSeqNo, ActionListener<RetentionLease> listener) {
                final String leaseId = ReplicationTracker.getPeerRecoveryRetentionLeaseId(startRecoveryRequest.targetNode().getId());
                listener.onResponse(new RetentionLease(
                    leaseId,
                    startingSeqNo,
                    threadPool.absoluteTimeInMillis(),
                    ReplicationTracker.PEER_RECOVERY_RETENTION_LEASE_SOURCE));
            }

        };
        cancelRecovery.set(() -> handler.cancel("test"));
        FutureActionListener<RecoverySourceHandler.SendFileResult> future = new FutureActionListener<>();
        handler.phase1(
            DirectoryReader.listCommits(dir).getFirst(),
            0,
            () -> 0,
            future
        );
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (Throwable t) {
            assertThat(wasCancelled).isTrue();
            assertThat(t).hasRootCauseExactlyInstanceOf(CancellableThreads.ExecutionCancelledException.class);
        }
        store.close();
    }

    @Test
    public void testVerifySeqNoStatsWhenRecoverWithSyncId() throws Exception {
        IndexShard shard = mock(IndexShard.class);
        when(shard.state()).thenReturn(IndexShardState.STARTED);
        RecoverySourceHandler handler = new RecoverySourceHandler(
            shard, new TestRecoveryTargetHandler(), threadPool, getStartRecoveryRequest(), between(1, 16), between(1, 4), between(1, 4));

        String syncId = UUIDs.randomBase64UUID();
        int numDocs = between(0, 1000);
        long localCheckpoint = randomLongBetween(SequenceNumbers.NO_OPS_PERFORMED, Long.MAX_VALUE);
        long maxSeqNo = randomLongBetween(SequenceNumbers.NO_OPS_PERFORMED, Long.MAX_VALUE);
        assertThat(handler.canSkipPhase1(
            newMetadataSnapshot(syncId, Long.toString(localCheckpoint), Long.toString(maxSeqNo), numDocs),
            newMetadataSnapshot(syncId, Long.toString(localCheckpoint), Long.toString(maxSeqNo), numDocs))).isTrue();

        assertThatThrownBy(() -> {
            long localCheckpointOnTarget = randomValueOtherThan(localCheckpoint,
                () -> randomLongBetween(SequenceNumbers.NO_OPS_PERFORMED, Long.MAX_VALUE));
            long maxSeqNoOnTarget = randomValueOtherThan(maxSeqNo,
                () -> randomLongBetween(SequenceNumbers.NO_OPS_PERFORMED, Long.MAX_VALUE));
            handler.canSkipPhase1(newMetadataSnapshot(syncId, Long.toString(localCheckpoint), Long.toString(maxSeqNo), numDocs),
                newMetadataSnapshot(syncId, Long.toString(localCheckpointOnTarget), Long.toString(maxSeqNoOnTarget), numDocs));
        }).isExactlyInstanceOf(AssertionError.class)
            .hasMessageContaining("try to recover [index/_na_][1] with sync id but seq_no stats are mismatched:");
    }

    private Store.MetadataSnapshot newMetadataSnapshot(String syncId, String localCheckpoint, String maxSeqNo, int numDocs) {
        HashMap<String, String> userData = new HashMap<>();
        userData.put(Engine.SYNC_COMMIT_ID, syncId);
        if (localCheckpoint != null) {
            userData.put(SequenceNumbers.LOCAL_CHECKPOINT_KEY, localCheckpoint);
        }
        if (maxSeqNo != null) {
            userData.put(SequenceNumbers.LOCAL_CHECKPOINT_KEY, maxSeqNo);
        }
        return new Store.MetadataSnapshot(Collections.emptyMap(), userData, numDocs);
    }

    private Store newStore(Path path) throws IOException {
        return newStore(path, true);
    }

    private Store newStore(Path path, boolean checkIndex) throws IOException {
        BaseDirectoryWrapper baseDirectoryWrapper = RecoverySourceHandlerTests.newFSDirectory(path);
        if (checkIndex == false) {
            baseDirectoryWrapper.setCheckIndexOnClose(false); // don't run checkindex we might corrupt the index in these tests
        }
        return new Store(shardId,  INDEX_SETTINGS, baseDirectoryWrapper, new DummyShardLock(shardId));
    }

    private record FileChunkResponse(long chunkNumber, ActionListener<Void> listener) {}

    private List<StoreFileMetadata> generateFiles(Store store,
                                                  int numFiles,
                                                  IntSupplier fileSizeSupplier) throws IOException {
        List<StoreFileMetadata> files = new ArrayList<>();
        for (int i = 0; i < numFiles; i++) {
            byte[] buffer = randomByteArrayOfLength(fileSizeSupplier.getAsInt());
            CRC32 digest = new CRC32();
            digest.update(buffer, 0, buffer.length);
            StoreFileMetadata md = new StoreFileMetadata("test-" + i,
                                                         buffer.length + 8,
                                                         Store.digestToString(digest.getValue()),
                                                         org.apache.lucene.util.Version.LATEST);
            try (OutputStream out =
                     new IndexOutputOutputStream(store.createVerifyingOutput(md.name(), md, IOContext.DEFAULT))) {
                out.write(buffer);
                out.write(Numbers.longToBytes(digest.getValue()));
            }
            store.directory().sync(Collections.singleton(md.name()));
            files.add(md);
        }
        return files;
    }

    class TestRecoveryTargetHandler implements RecoveryTargetHandler {

        @Override
        public void prepareForTranslogOperations(int totalTranslogOps,
                                                 ActionListener<Void> listener) {
        }

        @Override
        public void finalizeRecovery(long globalCheckpoint, long trimAboveSeqNo, ActionListener<Void> listener) {
        }

        @Override
        public void handoffPrimaryContext(ReplicationTracker.PrimaryContext primaryContext, ActionListener<Void> listener) {
        }

        @Override
        public void indexTranslogOperations(List<Translog.Operation> operations,
                                            int totalTranslogOps,
                                            long timestamp,
                                            long msu,
                                            RetentionLeases retentionLeases,
                                            long mappingVersion,
                                            ActionListener<Long> listener) {
        }

        @Override
        public void receiveFileInfo(List<String> phase1FileNames,
                                    List<Long> phase1FileSizes,
                                    List<String> phase1ExistingFileNames,
                                    List<Long> phase1ExistingFileSizes,
                                    int totalTranslogOps,
                                    ActionListener<Void> listener) {
        }

        @Override
        public void cleanFiles(int totalTranslogOps,
                               long globalCheckpoint,
                               Store.MetadataSnapshot sourceMetadata,
                               ActionListener<Void> listener) {
        }

        @Override
        public void writeFileChunk(StoreFileMetadata fileMetadata,
                                   long position,
                                   BytesReference content,
                                   boolean lastChunk,
                                   int totalTranslogOps,
                                   ActionListener<Void> listener) {
        }
    }

    private Translog.Snapshot newTranslogSnapshot(List<Translog.Operation> operations, List<Translog.Operation> operationsToSkip) {
        Iterator<Translog.Operation> iterator = operations.iterator();
        return new Translog.Snapshot() {
            int skippedCount = 0;

            @Override
            public int totalOperations() {
                return operations.size();
            }

            @Override
            public int skippedOperations() {
                return skippedCount;
            }

            @Override
            public Translog.Operation next() {
                while (iterator.hasNext()) {
                    Translog.Operation op = iterator.next();
                    if (operationsToSkip.contains(op)) {
                        skippedCount++;
                    } else {
                        return op;
                    }
                }
                return null;
            }

            @Override
            public void close() {
            }
        };
    }

    private static List<Translog.Operation> generateOperations(int numOps) {
        final List<Translog.Operation> operations = new ArrayList<>(numOps);
        final byte[] source = "{}".getBytes(StandardCharsets.UTF_8);
        final Set<Long> seqNos = new HashSet<>();
        for (int i = 0; i < numOps; i++) {
            final long seqNo = randomValueOtherThanMany(n -> seqNos.add(n) == false, ESTestCase::randomNonNegativeLong);
            final Translog.Operation op;
            if (randomBoolean()) {
                op = new Translog.Index("id", seqNo, randomNonNegativeLong(), randomNonNegativeLong(), source, -1);
            } else if (randomBoolean()) {
                op = new Translog.Delete("id", new Term("_id", Uid.encodeId("id")),
                    seqNo, randomNonNegativeLong(), randomNonNegativeLong());
            } else {
                op = new Translog.NoOp(seqNo, randomNonNegativeLong(), "test");
            }
            operations.add(op);
        }
        return operations;
    }
}
