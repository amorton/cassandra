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

package org.apache.cassandra.distributed.upgrade;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;


import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.BeforeClass;

import org.apache.cassandra.distributed.UpgradeableCluster;
import org.apache.cassandra.distributed.api.ICluster;
import org.apache.cassandra.distributed.api.IInstanceConfig;
import org.apache.cassandra.distributed.impl.Instance;
import org.apache.cassandra.distributed.shared.DistributedTestBase;
import org.apache.cassandra.distributed.shared.Versions;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.Pair;

import static org.apache.cassandra.distributed.shared.Versions.Major;
import static org.apache.cassandra.distributed.shared.Versions.Version;
import static org.apache.cassandra.distributed.shared.Versions.find;


public class UpgradeTestBase extends DistributedTestBase
{
    @After
    public void afterEach()
    {
        System.runFinalization();
        System.gc();
    }

    @BeforeClass
    public static void beforeClass() throws Throwable
    {
        ICluster.setup();
    }


    public UpgradeableCluster.Builder builder()
    {
        return UpgradeableCluster.build();
    }

    public static interface RunOnCluster
    {
        public void run(UpgradeableCluster cluster) throws Throwable;
    }

    public static interface RunOnClusterAndNode
    {
        public void run(UpgradeableCluster cluster, int node) throws Throwable;
    }

    public static final List<Pair<Versions.Major,Versions.Major>> SUPPORTED_UPGRADE_PATHS = ImmutableList.of(
        Pair.create(Versions.Major.v22, Versions.Major.v30),
        Pair.create(Versions.Major.v22, Versions.Major.v3X),
        Pair.create(Versions.Major.v30, Versions.Major.v3X),
        Pair.create(Versions.Major.v30, Versions.Major.v40),
        Pair.create(Versions.Major.v3X, Versions.Major.v40));

    public static final Versions.Major CURRENT = SUPPORTED_UPGRADE_PATHS.get(SUPPORTED_UPGRADE_PATHS.size() - 1).right;

    public static class TestVersions
    {
        final Version initial;
        final Version upgrade;

        public TestVersions(Version initial, Version upgrade)
        {
            this.initial = initial;
            this.upgrade = upgrade;
        }
    }

    public static class TestCase implements Instance.ThrowingRunnable
    {
        private final Versions versions;
        private final List<TestVersions> upgrade = new ArrayList<>();
        private int nodeCount = 3;
        private RunOnCluster setup;
        private RunOnClusterAndNode runBeforeNodeRestart;
        private RunOnClusterAndNode runAfterNodeUpgrade;
        private RunOnCluster runAfterClusterUpgrade;
        private final Set<Integer> nodesToUpgrade = new HashSet<>();
        private Consumer<IInstanceConfig> configConsumer;

        public TestCase()
        {
            this(find());
        }

        public TestCase(Versions versions)
        {
            this.versions = versions;
        }

        public TestCase nodes(int nodeCount)
        {
            this.nodeCount = nodeCount;
            return this;
        }

        /** Will test all supported upgrade paths, starting from **/
        public TestCase upgradesFrom(Major from)
        {
            return upgrades(from, CURRENT);
        }

        /** Will test all supported upgrade paths, in between from and to, inclusively **/
        public TestCase upgrades(Major from, Major to)
        {
            SUPPORTED_UPGRADE_PATHS.stream()
                .filter(upgradePath -> (upgradePath.left.compareTo(from) >= 0 && upgradePath.right.compareTo(to) <= 0))
                .forEachOrdered(upgradePath ->
                {
                    this.upgrade.add(
                            new TestVersions(versions.getLatest(upgradePath.left), versions.getLatest(upgradePath.right)));
                });
            return this;
        }

        /** Will test this specific upgrade path **/
        public TestCase singleUpgrade(Major from, Major to)
        {
            this.upgrade.add(new TestVersions(versions.getLatest(from), versions.getLatest(to)));
            return this;
        }

        public TestCase setup(RunOnCluster setup)
        {
            this.setup = setup;
            return this;
        }

        public TestCase runBeforeNodeRestart(RunOnClusterAndNode runBeforeNodeRestart)
        {
            this.runBeforeNodeRestart = runBeforeNodeRestart;
            return this;
        }

        public TestCase runAfterNodeUpgrade(RunOnClusterAndNode runAfterNodeUpgrade)
        {
            this.runAfterNodeUpgrade = runAfterNodeUpgrade;
            return this;
        }

        public TestCase runAfterClusterUpgrade(RunOnCluster runAfterClusterUpgrade)
        {
            this.runAfterClusterUpgrade = runAfterClusterUpgrade;
            return this;
        }

        public TestCase withConfig(Consumer<IInstanceConfig> config)
        {
            this.configConsumer = config;
            return this;
        }

        public void run() throws Throwable
        {
            if (setup == null)
                throw new AssertionError();
            if (upgrade.isEmpty())
                throw new AssertionError();
            if (runAfterClusterUpgrade == null && runAfterNodeUpgrade == null)
                throw new AssertionError();
            if (runBeforeNodeRestart == null)
                runBeforeNodeRestart = (c, n) -> {};
            if (runAfterClusterUpgrade == null)
                runAfterClusterUpgrade = (c) -> {};
            if (runAfterNodeUpgrade == null)
                runAfterNodeUpgrade = (c, n) -> {};
            if (nodesToUpgrade.isEmpty())
                for (int n = 1; n <= nodeCount; n++)
                    nodesToUpgrade.add(n);

            for (TestVersions upgrade : this.upgrade)
            {
                try (UpgradeableCluster cluster = init(UpgradeableCluster.create(nodeCount, upgrade.initial, configConsumer)))
                {
                    setup.run(cluster);

                    for (int n : nodesToUpgrade)
                    {
                        cluster.get(n).shutdown().get();
                        cluster.get(n).setVersion(upgrade.upgrade);
                        runBeforeNodeRestart.run(cluster, n);
                        cluster.get(n).startup();
                        runAfterNodeUpgrade.run(cluster, n);
                    }

                    runAfterClusterUpgrade.run(cluster);
                }

            }
        }
        public TestCase nodesToUpgrade(int ... nodes)
        {
            for (int n : nodes)
            {
                nodesToUpgrade.add(n);
            }
            return this;
        }
     }

    protected TestCase allUpgrades(int nodes, int... toUpgrade)
    {
        return new TestCase().nodes(nodes)
                             .upgradesFrom(Versions.Major.v22)
                             .nodesToUpgrade(toUpgrade);
    }

}