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

package org.apache.cassandra.distributed.test;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import org.junit.Test;

import org.apache.cassandra.concurrent.DebuggableThreadPoolExecutor;
import org.apache.cassandra.db.SystemKeyspace;
import org.apache.cassandra.distributed.Cluster;
import org.apache.cassandra.distributed.api.ConsistencyLevel;
import org.apache.cassandra.distributed.api.NodeToolResult;
import org.apache.cassandra.locator.InetAddressAndPort;
import org.apache.cassandra.service.ActiveRepairService;
import org.apache.cassandra.streaming.StreamSession;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import static org.apache.cassandra.distributed.api.Feature.GOSSIP;
import static org.apache.cassandra.distributed.api.Feature.NETWORK;

public class RepairErrorsTest extends TestBaseImpl
{
    @SuppressWarnings("Convert2MethodRef")
    @Test
    public void testRemoteSyncFailure() throws Exception
    {
        try (Cluster cluster = init(Cluster.build(3)
                                           .withConfig(config -> config.with(GOSSIP)
                                                                       .with(NETWORK)
                                                                       .set("disk_failure_policy", "stop")
                                                                       .set("disk_access_mode", "mmap_index_only"))
                                           .withInstanceInitializer(ByteBuddyHelper::installStreamPlanExecutionFailure).start()))
        {
            cluster.schemaChange("create table " + KEYSPACE + ".tbl (id int primary key, x int)");
            
            // On repair, this data layout will require two (local) syncs from node 1 and one remote sync from node 2:
            cluster.get(1).executeInternal("insert into " + KEYSPACE + ".tbl (id, x) VALUES (?,?)", 1, 1);
            cluster.get(2).executeInternal("insert into " + KEYSPACE + ".tbl (id, x) VALUES (?,?)", 2, 2);
            cluster.get(3).executeInternal("insert into " + KEYSPACE + ".tbl (id, x) VALUES (?,?)", 3, 3);
            cluster.forEach(i -> i.flush(KEYSPACE));
            
            // Flush system.peers_v2, or there won't be any SSTables...
            cluster.forEach(i -> i.flush("system"));
            
            // The remote sync started from node 2 will fail on plan execution and propagate the error...
            NodeToolResult result = cluster.get(1).nodetoolResult("repair", KEYSPACE);
            result.asserts().failure().errorContains("Sync failed between /127.0.0.2:7012 and /127.0.0.3:7012");

            // Before CASSANDRA-17466 added an abort mechanism for local sync tasks and switched the repair task
            // executor to shut down without interrupting its threads, we could trigger the disk failure policy, as
            // interruption could accidentally close shared channels in the middle of a blocking operation. To see
            // this, simply revert those changes in RepairJob (aborting sync tasks) and RepairSession (shutdown()
            // rather than shutdownNow() on failure).
            assertTrue(cluster.get(1).logs().grep("Stopping transports as disk_failure_policy is stop").getResult().isEmpty());
            assertTrue(cluster.get(1).logs().grep("FSReadError").getResult().isEmpty());
            assertTrue(cluster.get(1).logs().grep("ClosedByInterruptException").getResult().isEmpty());

            // Make sync unnecessary, and repair should succeed:
            cluster.coordinator(1).execute("insert into " + KEYSPACE + ".tbl (id, x) VALUES (?,?)", ConsistencyLevel.ALL, 1, 1);
            cluster.coordinator(1).execute("insert into " + KEYSPACE + ".tbl (id, x) VALUES (?,?)", ConsistencyLevel.ALL, 2, 2);
            cluster.coordinator(1).execute("insert into " + KEYSPACE + ".tbl (id, x) VALUES (?,?)", ConsistencyLevel.ALL, 3, 3);
            cluster.forEach(i -> i.flush(KEYSPACE));
            result = cluster.get(1).nodetoolResult("repair", KEYSPACE);
            result.asserts().success();

            // Make sure we've cleaned up sessions and parent sessions:
            Integer parents = cluster.get(1).callOnInstance(() -> ActiveRepairService.instance.parentRepairSessionCount());
            assertEquals(0, parents.intValue());
            Integer sessions = cluster.get(1).callOnInstance(() -> ActiveRepairService.instance.sessionCount());
            assertEquals(0, sessions.intValue());
        }
    }

    public static class ByteBuddyHelper
    {
        public static void installStreamPlanExecutionFailure(ClassLoader cl, int nodeNumber)
        {
            if (nodeNumber == 2)
            {
                new ByteBuddy().rebase(StreamSession.class)
                               .method(named("onInitializationComplete"))
                               .intercept(MethodDelegation.to(ByteBuddyHelper.class))
                               .make()
                               .load(cl, ClassLoadingStrategy.Default.INJECTION);
            }

            if (nodeNumber == 1)
            {
                new ByteBuddy().rebase(SystemKeyspace.class)
                        .method(named("getPreferredIP"))
                        .intercept(MethodDelegation.to(ByteBuddyHelper.class))
                        .make()
                        .load(cl, ClassLoadingStrategy.Default.INJECTION);

                new ByteBuddy().rebase(DebuggableThreadPoolExecutor.class)
                        .method(named("extractThrowable").and(takesArguments(Future.class)))
                        .intercept(MethodDelegation.to(ByteBuddyHelper.class))
                        .make()
                        .load(cl, ClassLoadingStrategy.Default.INJECTION);
            }
        }

        @SuppressWarnings("unused")
        public static void onInitializationComplete()
        {
            throw new RuntimeException("Failing stream session initialization from test!");
        }

        @SuppressWarnings("unused")
        public static InetAddressAndPort getPreferredIP(InetAddressAndPort ep, @SuperCall Callable<InetAddressAndPort> zuper) throws Exception
        {
            if (Thread.currentThread().getName().contains("RepairJobTask"))
            {
                try
                {
                    TimeUnit.SECONDS.sleep(60);
                }
                catch (InterruptedException e)
                {
                    // Leave the interrupt flag intact for the ChannelProxy downstream...
                    Thread.currentThread().interrupt();
                }
            }

            return zuper.call();
        }

        @SuppressWarnings({"unused", "ResultOfMethodCallIgnored"})
        public static Throwable extractThrowable(Future<?> future, @SuperCall Callable<Throwable> zuper) throws Exception
        {
            if (Thread.currentThread().getName().contains("RepairJobTask"))
                // Clear the interrupt flag so the FSReadError is propagated correctly in DebuggableThreadPoolExecutor:
                Thread.interrupted();
            
            return zuper.call();
        }
    }
}
