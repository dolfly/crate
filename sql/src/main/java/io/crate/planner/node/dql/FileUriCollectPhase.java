/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
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

package io.crate.planner.node.dql;

import com.google.common.base.MoreObjects;
import io.crate.analyze.EvaluatingNormalizer;
import io.crate.analyze.WhereClause;
import io.crate.analyze.symbol.Symbol;
import io.crate.metadata.Routing;
import io.crate.metadata.RowGranularity;
import io.crate.operation.collect.files.FileReadingCollector;
import io.crate.planner.distribution.DistributionInfo;
import io.crate.planner.projection.Projection;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

public class FileUriCollectPhase extends CollectPhase {

    public static final ExecutionPhaseFactory<FileUriCollectPhase> FACTORY = new ExecutionPhaseFactory<FileUriCollectPhase>() {
        @Override
        public FileUriCollectPhase create() {
            return new FileUriCollectPhase();
        }
    };
    private Symbol targetUri;
    private String compression;
    private Boolean sharedStorage;

    private FileUriCollectPhase() {
        super();
    }

    public FileUriCollectPhase(UUID jobId,
                               int executionNodeId,
                               String name,
                               Routing routing,
                               RowGranularity rowGranularity, Symbol targetUri,
                               List<Symbol> toCollect,
                               List<Projection> projections,
                               String compression,
                               Boolean sharedStorage) {
        super(jobId, executionNodeId, name, routing, rowGranularity, toCollect, projections,
                WhereClause.MATCH_ALL,
                DistributionInfo.DEFAULT_BROADCAST);
        this.targetUri = targetUri;
        this.compression = compression;
        this.sharedStorage = sharedStorage;
    }

    public Symbol targetUri() {
        return targetUri;
    }

    public FileReadingCollector.FileFormat fileFormat() {
        return FileReadingCollector.FileFormat.JSON;
    }

    @Override
    public Type type() {
        return Type.FILE_URI_COLLECT;
    }

    @Override
    public FileUriCollectPhase normalize(EvaluatingNormalizer normalizer) {
        List<Symbol> normalizedToCollect = normalizer.normalize(toCollect());
        Symbol normalizedTargetUri = normalizer.normalize(targetUri);
        boolean changed = normalizedToCollect != toCollect() || (normalizedTargetUri != targetUri);
        if (!changed) {
            return this;
        }
        return new FileUriCollectPhase(
                jobId(),
                executionPhaseId(),
                name(),
                routing(),
                maxRowGranularity(),
                normalizedTargetUri,
                normalizedToCollect,
                projections(),
                compression(),
                sharedStorage());
    }

    @Nullable
    public String compression() {
        return compression;
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        compression = in.readOptionalString();
        sharedStorage = in.readOptionalBoolean();
        targetUri = Symbol.fromStream(in);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeOptionalString(compression);
        out.writeOptionalBoolean(sharedStorage);
        Symbol.toStream(targetUri, out);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", name())
                .add("targetUri", targetUri)
                .add("projections", projections)
                .add("outputTypes", outputTypes)
                .add("compression", compression)
                .add("sharedStorageDefault", sharedStorage)
                .toString();
    }

    @Nullable
    public Boolean sharedStorage() {
        return sharedStorage;
    }
}

