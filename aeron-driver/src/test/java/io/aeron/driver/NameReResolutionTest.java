/*
 * Copyright 2014-2024 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.driver;

import io.aeron.Aeron;
import io.aeron.ChannelUri;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.test.InterruptAfter;
import io.aeron.test.InterruptingTestCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.function.Predicate;

import static io.aeron.test.Tests.sleep;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.regex.Pattern.compile;
import static org.agrona.CloseHelper.closeAll;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(InterruptingTestCallback.class)
public class NameReResolutionTest
{
    static final Predicate<String> IP_PREDICATE = compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$").asPredicate();
    static final int STREAM_ID = 123;

    //  should be unroutable
    private volatile InetAddress address;

    {
        try
        {
            address = InetAddress.getByName("192.0.2.0");
        }
        catch (final UnknownHostException ex)
        {
            throw new RuntimeException(ex);
        }
    }

    private final NameResolver nameResolver = (name, uriParamName, isReResolution) ->
    {
        if (IP_PREDICATE.test(name))
        {
            try
            {
                return InetAddress.getByName(name);
            }
            catch (final UnknownHostException ex)
            {
                return null;
            }
        }
        return address;
    };

    private final MediaDriver mediaDriver =
        MediaDriver.launchEmbedded(new MediaDriver.Context().nameResolver(nameResolver));
    private final Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(mediaDriver.aeronDirectoryName()));

    @AfterEach
    void shutdown()
    {
        closeAll(aeron, mediaDriver, mediaDriver.context()::deleteDirectory);
    }

    @Test
    @InterruptAfter(30)
    void publicationRecreatedEverySecondShouldEventuallySwitchToTheNewIp()
    {
        final Subscription subscription = aeron.addSubscription("aeron:udp?endpoint=127.0.0.1:0", STREAM_ID);

        final ChannelUri subscriptionChannel = ChannelUri.parse(subscription.tryResolveChannelEndpointPort());
        final ChannelUri publicationChannel = ChannelUri.parse("aeron:udp?endpoint=somehost.test:0");
        publicationChannel.replaceEndpointWildcardPort(subscriptionChannel.get("endpoint"));

        aeron.addPublication(publicationChannel.toString(), STREAM_ID).close();

        // when
        address = InetAddress.getLoopbackAddress();

        // then
        while (!Thread.interrupted())
        {
            try (Publication publication = aeron.addPublication(publicationChannel.toString(), STREAM_ID))
            {
                final long deadline = System.currentTimeMillis() + SECONDS.toMillis(1);
                while (deadline - System.currentTimeMillis() > 0)
                {
                    if (publication.isConnected())
                    {
                        return; // connected - success
                    }

                    sleep(1);
                }
            }
        }

        fail("Publication did not connect");
    }
}
