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

package org.elasticsearch.index.shard;

import java.io.IOException;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.index.Index;

/**
 * Allows for shard level components to be injected with the shard id.
 */
public class ShardId implements Writeable, Comparable<ShardId> {

    private final Index index;
    private final int shardId;
    private final int hashCode;

    public ShardId(Index index, int shardId) {
        this.index = index;
        this.shardId = shardId;
        this.hashCode = computeHashCode();
    }

    public ShardId(String index, String indexUUID, int shardId) {
        this(new Index(index, indexUUID), shardId);
    }

    public ShardId(StreamInput in) throws IOException {
        index = new Index(in);
        shardId = in.readVInt();
        hashCode = computeHashCode();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        index.writeTo(out);
        out.writeVInt(shardId);
    }

    public Index getIndex() {
        return index;
    }

    /**
     * Any indexName usage is deprecated, it may even be removed in the future.
     * All index resolving must be done using the index UUID instead.
     *
     * @deprecated Use {@link {@link #getIndexUUID()} instead.
     */
    @Deprecated
    public String getIndexName() {
        return index.getName();
    }

    public String getIndexUUID() {
        return index.getUUID();
    }

    public int id() {
        return this.shardId;
    }

    @Override
    public String toString() {
        return "[" + index.getName() + "/" + index.getUUID() + "][" + shardId + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ShardId shardId1 = (ShardId) o;
        return shardId == shardId1.shardId && index.equals(shardId1.index);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    private int computeHashCode() {
        int result = index != null ? index.hashCode() : 0;
        result = 31 * result + shardId;
        return result;
    }

    @Override
    public int compareTo(ShardId o) {
        if (o.id() == shardId) {
            int compare = index.getUUID().compareTo(o.getIndex().getUUID());
            if (compare != 0) {
                return compare;
            }
            return index.getName().compareTo(o.getIndex().getName());
        }
        return Integer.compare(shardId, o.id());
    }
}
