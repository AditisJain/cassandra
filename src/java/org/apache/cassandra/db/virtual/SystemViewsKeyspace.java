/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db.virtual;

import java.util.Collection;

import com.google.common.collect.ImmutableList;

import org.apache.cassandra.index.sai.virtual.AnalyzerView;
import org.apache.cassandra.index.sai.virtual.IndexesSystemView;
import org.apache.cassandra.index.sai.virtual.SSTablesSystemView;
import org.apache.cassandra.index.sai.virtual.SegmentsSystemView;
import org.apache.cassandra.nodes.virtual.LegacyPeersSystemView;
import org.apache.cassandra.nodes.virtual.LocalNodeSystemView;
import org.apache.cassandra.nodes.virtual.PeersSystemView;

import static org.apache.cassandra.schema.SchemaConstants.VIRTUAL_VIEWS;

public final class SystemViewsKeyspace extends VirtualKeyspace
{
    private static final boolean ONLY_LOCAL_AND_PEERS = Boolean.getBoolean("cassandra.system_view.only_local_and_peers_table");

    public static SystemViewsKeyspace instance = new SystemViewsKeyspace();

    private SystemViewsKeyspace()
    {
        super(VIRTUAL_VIEWS, buildTables());
    }

    private static Collection<VirtualTable> buildTables()
    {
        ImmutableList.Builder<VirtualTable> tables = new ImmutableList.Builder<>();
        if (!ONLY_LOCAL_AND_PEERS)
            tables.add(new CachesTable(VIRTUAL_VIEWS))
                  .add(new ClientsTable(VIRTUAL_VIEWS))
                  .add(new SettingsTable(VIRTUAL_VIEWS))
                  .add(new SystemPropertiesTable(VIRTUAL_VIEWS))
                  .add(new SSTableTasksTable(VIRTUAL_VIEWS))
                  .add(new ThreadPoolsTable(VIRTUAL_VIEWS))
                  .add(new InternodeOutboundTable(VIRTUAL_VIEWS))
                  .add(new InternodeInboundTable(VIRTUAL_VIEWS))
                  .add(new SSTablesSystemView(VIRTUAL_VIEWS))
                  .add(new SegmentsSystemView(VIRTUAL_VIEWS))
                  .add(new IndexesSystemView(VIRTUAL_VIEWS))
                  .add(new AnalyzerView(VIRTUAL_VIEWS))
                  .addAll(TableMetricTables.getAll(VIRTUAL_VIEWS));
        tables.add(new LocalNodeSystemView())
              .add(new PeersSystemView())
              .add(new LegacyPeersSystemView());
        return tables.build();
    }
}
