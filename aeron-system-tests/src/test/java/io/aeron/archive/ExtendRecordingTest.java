/*
 * Copyright 2014-2019 Real Logic Ltd.
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
package io.aeron.archive;

import io.aeron.*;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.ControlEventListener;
import io.aeron.archive.client.RecordingTransitionAdapter;
import io.aeron.archive.client.RecordingTransitionConsumer;
import io.aeron.archive.status.RecordingPos;
import io.aeron.driver.Configuration;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.CloseHelper;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.SystemUtil;
import org.agrona.collections.MutableInteger;
import org.agrona.concurrent.status.CountersReader;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;

import static io.aeron.archive.codecs.RecordingTransitionType.*;
import static io.aeron.archive.codecs.SourceLocation.LOCAL;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;

public class ExtendRecordingTest
{
    private static final String MESSAGE_PREFIX = "Message-Prefix-";
    private static final long MAX_CATALOG_ENTRIES = 1024;
    private static final int FRAGMENT_LIMIT = 10;
    private static final int TERM_BUFFER_LENGTH = 64 * 1024;
    private static final int MTU_LENGTH = Configuration.mtuLength();

    private static final int RECORDING_STREAM_ID = 33;
    private static final String RECORDING_CHANNEL = new ChannelUriStringBuilder()
        .media("udp")
        .endpoint("localhost:3333")
        .termLength(TERM_BUFFER_LENGTH)
        .build();

    private static final int REPLAY_STREAM_ID = 66;
    private static final String REPLAY_CHANNEL = new ChannelUriStringBuilder()
        .media("udp")
        .endpoint("localhost:6666")
        .build();

    private static final String SUBSCRIPTION_EXTEND_CHANNEL = new ChannelUriStringBuilder()
        .media("udp")
        .endpoint("localhost:3333")
        .build();

    private ArchivingMediaDriver archivingMediaDriver;
    private Aeron aeron;
    private File archiveDir;
    private AeronArchive aeronArchive;

    private final RecordingTransitionConsumer recordingTransitionConsumer = mock(RecordingTransitionConsumer.class);
    private final ArrayList<String> errors = new ArrayList<>();
    private final ControlEventListener controlEventListener =
        (controlSessionId, correlationId, relevantId, code, errorMessage) -> errors.add(errorMessage);

    @Before
    public void before()
    {
        launchAeronAndArchive();
    }

    @After
    public void after()
    {
        closeDownAndCleanMediaDriver();
        archivingMediaDriver.archive().context().deleteArchiveDirectory();
    }

    @Test(timeout = 10_000)
    public void shouldExtendRecordingAndReplay()
    {
        final long controlSessionId = aeronArchive.controlSessionId();
        final RecordingTransitionAdapter recordingTransitionAdapter;
        final int messageCount = 10;
        final long firstSubscriptionId;
        final long secondSubscriptionId;
        final long firstStopPosition;
        final long secondStopPosition;
        final long recordingId;
        final int initialTermId;

        try (Publication publication = aeron.addPublication(RECORDING_CHANNEL, RECORDING_STREAM_ID);
            Subscription subscription = aeron.addSubscription(RECORDING_CHANNEL, RECORDING_STREAM_ID))
        {
            initialTermId = publication.initialTermId();
            recordingTransitionAdapter = new RecordingTransitionAdapter(
                controlSessionId,
                controlEventListener,
                recordingTransitionConsumer,
                aeronArchive.controlResponsePoller().subscription(),
                FRAGMENT_LIMIT);

            firstSubscriptionId = aeronArchive.startRecording(RECORDING_CHANNEL, RECORDING_STREAM_ID, LOCAL);
            pollForTransition(recordingTransitionAdapter);

            try
            {
                offer(publication, 0, messageCount, MESSAGE_PREFIX);

                final CountersReader counters = aeron.countersReader();
                final int counterId = RecordingPos.findCounterIdBySession(counters, publication.sessionId());
                recordingId = RecordingPos.getRecordingId(counters, counterId);

                consume(subscription, 0, messageCount, MESSAGE_PREFIX);

                firstStopPosition = publication.position();
                while (counters.getCounterValue(counterId) < firstStopPosition)
                {
                    SystemTest.checkInterruptedStatus();
                    Thread.yield();
                }
            }
            finally
            {
                aeronArchive.stopRecording(firstSubscriptionId);
                pollForTransition(recordingTransitionAdapter);
            }
        }

        final String publicationExtendChannel = new ChannelUriStringBuilder()
            .media("udp")
            .endpoint("localhost:3333")
            .initialPosition(firstStopPosition, initialTermId, TERM_BUFFER_LENGTH)
            .mtu(MTU_LENGTH)
            .build();

        try (Publication publication = aeron.addExclusivePublication(publicationExtendChannel, RECORDING_STREAM_ID);
            Subscription subscription = aeron.addSubscription(SUBSCRIPTION_EXTEND_CHANNEL, RECORDING_STREAM_ID))
        {
            secondSubscriptionId = aeronArchive.extendRecording(
                recordingId, SUBSCRIPTION_EXTEND_CHANNEL, RECORDING_STREAM_ID, LOCAL);
            pollForTransition(recordingTransitionAdapter);

            try
            {
                offer(publication, messageCount, messageCount, MESSAGE_PREFIX);

                final CountersReader counters = aeron.countersReader();
                final int counterId = RecordingPos.findCounterIdBySession(counters, publication.sessionId());

                consume(subscription, messageCount, messageCount, MESSAGE_PREFIX);

                secondStopPosition = publication.position();
                while (counters.getCounterValue(counterId) < secondStopPosition)
                {
                    SystemTest.checkInterruptedStatus();
                    Thread.yield();
                }
            }
            finally
            {
                aeronArchive.stopRecording(secondSubscriptionId);
                pollForTransition(recordingTransitionAdapter);
            }
        }

        replay(messageCount, secondStopPosition, recordingId);
        assertEquals(Collections.EMPTY_LIST, errors);

        final InOrder inOrder = Mockito.inOrder(recordingTransitionConsumer);
        inOrder.verify(recordingTransitionConsumer)
            .onTransition(controlSessionId, recordingId, firstSubscriptionId, 0L, START);
        inOrder.verify(recordingTransitionConsumer)
            .onTransition(controlSessionId, recordingId, firstSubscriptionId, firstStopPosition, STOP);
        inOrder.verify(recordingTransitionConsumer)
            .onTransition(controlSessionId, recordingId, secondSubscriptionId, firstStopPosition, EXTEND);
        inOrder.verify(recordingTransitionConsumer)
            .onTransition(controlSessionId, recordingId, secondSubscriptionId, secondStopPosition, STOP);
    }

    private void replay(final int messageCount, final long secondStopPosition, final long recordingId)
    {
        final long fromPosition = 0L;
        final long length = secondStopPosition - fromPosition;

        try (Subscription subscription = aeronArchive.replay(
            recordingId, fromPosition, length, REPLAY_CHANNEL, REPLAY_STREAM_ID))
        {
            consume(subscription, 0, messageCount * 2, MESSAGE_PREFIX);
            assertEquals(secondStopPosition, subscription.imageAtIndex(0).position());
        }
    }

    private static void pollForTransition(final RecordingTransitionAdapter recordingTransitionAdapter)
    {
        while (0 == recordingTransitionAdapter.poll())
        {
            SystemTest.checkInterruptedStatus();
            Thread.yield();
        }
    }

    private static void offer(
        final Publication publication, final int startIndex, final int count, final String prefix)
    {
        final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer();

        for (int i = startIndex; i < (startIndex + count); i++)
        {
            final int length = buffer.putStringWithoutLengthAscii(0, prefix + i);

            while (publication.offer(buffer, 0, length) <= 0)
            {
                SystemTest.checkInterruptedStatus();
                Thread.yield();
            }
        }
    }

    private static void consume(
        final Subscription subscription, final int startIndex, final int count, final String prefix)
    {
        final MutableInteger received = new MutableInteger(startIndex);

        final FragmentHandler fragmentHandler = new FragmentAssembler(
            (buffer, offset, length, header) ->
            {
                final String expected = prefix + received.value;
                final String actual = buffer.getStringWithoutLengthAscii(offset, length);

                assertEquals(expected, actual);

                received.value++;
            });

        while (received.value < (startIndex + count))
        {
            if (0 == subscription.poll(fragmentHandler, FRAGMENT_LIMIT))
            {
                SystemTest.checkInterruptedStatus();
                Thread.yield();
            }
        }

        assertThat(received.get(), is(startIndex + count));
    }

    private void closeDownAndCleanMediaDriver()
    {
        CloseHelper.close(aeronArchive);
        CloseHelper.close(aeron);
        CloseHelper.close(archivingMediaDriver);
    }

    private void launchAeronAndArchive()
    {
        final String aeronDirectoryName = CommonContext.generateRandomDirName();

        if (null == archiveDir)
        {
            archiveDir = new File(SystemUtil.tmpDirName(), "archive");
        }

        archivingMediaDriver = ArchivingMediaDriver.launch(
            new MediaDriver.Context()
                .aeronDirectoryName(aeronDirectoryName)
                .termBufferSparseFile(true)
                .threadingMode(ThreadingMode.SHARED)
                .errorHandler(Throwable::printStackTrace)
                .spiesSimulateConnection(false)
                .dirDeleteOnShutdown(true)
                .dirDeleteOnStart(true),
            new Archive.Context()
                .maxCatalogEntries(MAX_CATALOG_ENTRIES)
                .aeronDirectoryName(aeronDirectoryName)
                .archiveDir(archiveDir)
                .fileSyncLevel(0)
                .threadingMode(ArchiveThreadingMode.SHARED));

        aeron = Aeron.connect(
            new Aeron.Context()
                .aeronDirectoryName(aeronDirectoryName));

        aeronArchive = AeronArchive.connect(
            new AeronArchive.Context()
                .aeron(aeron));
    }
}
