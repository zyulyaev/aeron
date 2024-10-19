/*
 * Copyright 2014-2024 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import io.aeron.test.EventLogExtension;
import io.aeron.test.InterruptAfter;
import io.aeron.test.InterruptingTestCallback;
import io.aeron.test.SystemTestWatcher;
import io.aeron.test.cluster.TestCluster;
import org.agrona.SemanticVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.aeron.test.cluster.TestCluster.aCluster;

@ExtendWith({ EventLogExtension.class, InterruptingTestCallback.class })
class MultiNodeVersionValidationTest
{
    @RegisterExtension
    final SystemTestWatcher systemTestWatcher = new SystemTestWatcher();

    @Test
    @InterruptAfter(20)
    void shouldSuccessfullyStartWithConfiguredMajorVersion()
    {
        final int appointedLeaderIndex = 1;

        final TestCluster cluster = aCluster()
            .withStaticNodes(3)
            .withAppointedLeader(appointedLeaderIndex)
            .withConsensusModuleAppVersion(SemanticVersion.compose(1, 0, 0))
            .start();
        systemTestWatcher.cluster(cluster);

        cluster.awaitLeader();
    }
}
