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

package io.crate.sql.tree;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import java.util.List;

public class CharFilters extends AnalyzerElement {

    private final List<NamedProperties> charFilters;

    public CharFilters(List<NamedProperties> charFilters) {
        this.charFilters = charFilters;
    }

    public List<NamedProperties> charFilters() {
        return charFilters;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(charFilters);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        CharFilters that = (CharFilters) o;

        if (!charFilters.equals(that.charFilters)) return false;

        return true;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("charFilters", charFilters).toString();
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitCharFilters(this, context);
    }
}
