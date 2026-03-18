/*
 *
 * Copyright 2021-2025 gematik GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 */
package de.gematik.test.tiger.proxy.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.gematik.rbellogger.RbelConversionPhase;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.test.tiger.common.RingBufferHashMap;
import de.gematik.test.tiger.common.RingBufferHashSet;
import de.gematik.test.tiger.common.data.config.tigerproxy.TigerProxyConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Additional focused tests for TigerRemoteProxyClient.scheduleAfterMessage behavior. These are
 * isolated (no Spring context) for speed and determinism.
 */
class TigerRemoteProxyClientSchedulingAdditionalTest {

  private TigerRemoteProxyClient client;

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
  }

  private TigerRemoteProxyClient newClient(int timeoutMs) {
    TigerProxyConfiguration cfg =
        TigerProxyConfiguration.builder()
            .downloadInitialTrafficFromEndpoints(false)
            .waitForPreviousMessageBeforeParsingInSeconds((float) (timeoutMs / 1000.0))
            .proxyLogLevel("WARN")
            .build();
    return new TigerRemoteProxyClient("http://localhost:0", cfg);
  }

  @Test
  @DisplayName("Immediate execution when previous message already COMPLETED")
  void scheduleAfterMessage_previousCompleted_executesImmediately() throws Exception {
    client = newClient(500);

    String prevUuid = "prev-completed-uuid";
    String curUuid = "current-msg-uuid";

    // Create and register a COMPLETED previous message in converter history
    RbelElement previous =
        RbelElement.builder()
            .uuid(prevUuid)
            .rawContent("X".getBytes(StandardCharsets.UTF_8))
            .build();
    previous.setConversionPhase(RbelConversionPhase.COMPLETED);
    client.getRbelLogger().getRbelConverter().addMessageToHistory(previous);

    CountDownLatch latch = new CountDownLatch(1);
    client.scheduleAfterMessage(prevUuid, latch::countDown, curUuid);

    assertThat(latch.await(150, TimeUnit.MILLISECONDS))
        .as("Parse task should execute immediately because previous is COMPLETED")
        .isTrue();

    // Verify NextMessageParsedFacet was added to previous (by simple class name check)
    assertThat(previous.hasFacet(TigerRemoteProxyClient.NextMessageParsedFacet.class))
        .as("Previous message should have NextMessageParsedFacet marker")
        .isTrue();
  }

  @Test
  @DisplayName("Immediate execution when previous message UUID is in removedMessageUuids")
  void scheduleAfterMessage_previousRemoved_executesImmediatelyAndCleansUp() throws Exception {
    client = newClient(500);

    String removedPrev = "removed-prev-uuid";
    String curUuid = "cur-msg-removed-prev";

    @SuppressWarnings("unchecked")
    RingBufferHashSet<String> removedMessageUuids =
        (RingBufferHashSet<String>) ReflectionTestUtils.getField(client, "removedMessageUuids");
    removedMessageUuids.add(removedPrev);

    CountDownLatch latch = new CountDownLatch(1);
    client.scheduleAfterMessage(removedPrev, latch::countDown, curUuid);

    assertThat(latch.await(150, TimeUnit.MILLISECONDS))
        .as("Task should run immediately because previous was removed")
        .isTrue();

    assertThat(removedMessageUuids.contains(removedPrev))
        .as("Removed previous UUID should be cleared after immediate scheduling")
        .isFalse();
  }

  @Test
  @DisplayName("Queued task executes upon signalNewCompletedMessage before timeout")
  void scheduleAfterMessage_signalCompletion_releasesQueuedTask() throws Exception {
    client = newClient(500); // ensure we are below timeout

    String prevUuid = "queued-prev-uuid";
    String curUuid = "queued-cur-uuid";

    CountDownLatch latch = new CountDownLatch(1);
    client.scheduleAfterMessage(prevUuid, latch::countDown, curUuid);

    // Assert it's still waiting (not executed yet) just before we signal
    assertThat(latch.getCount()).isEqualTo(1);

    // Now add the previous message as COMPLETED and signal
    RbelElement previous =
        RbelElement.builder()
            .uuid(prevUuid)
            .rawContent("Y".getBytes(StandardCharsets.UTF_8))
            .build();
    previous.setConversionPhase(RbelConversionPhase.COMPLETED);
    client.getRbelLogger().getRbelConverter().addMessageToHistory(previous);

    client.signalNewCompletedMessage(previous);

    assertThat(latch.await(200, TimeUnit.MILLISECONDS))
        .as("Queued task should execute promptly after completion signal")
        .isTrue();

    // Internal waiting map should no longer have an entry for prevUuid
    @SuppressWarnings("unchecked")
    RingBufferHashMap<String, List<Runnable>> waiting =
        (RingBufferHashMap<String, List<Runnable>>)
            ReflectionTestUtils.getField(client, "parsingTasksWaitingForUuid");
    assertThat(waiting.get(prevUuid)).as("Waiting entry for prev should be cleared").isEmpty();
  }

  @Test
  @DisplayName(
      "Multiple queued tasks behind same previous execute exactly once each on completion signal")
  void scheduleAfterMessage_multipleQueuedTasks_releaseAll() throws Exception {
    client = newClient(500);

    String prevUuid = "multi-prev-uuid";

    AtomicInteger executions = new AtomicInteger();
    int taskCount = 5;
    CountDownLatch latch = new CountDownLatch(taskCount);

    for (int i = 0; i < taskCount; i++) {
      String cur = "multi-cur-" + i;
      client.scheduleAfterMessage(
          prevUuid,
          () -> {
            executions.incrementAndGet();
            latch.countDown();
          },
          cur);
    }

    // Inject previous message and signal completion
    RbelElement previous =
        RbelElement.builder()
            .uuid(prevUuid)
            .rawContent("Z".getBytes(StandardCharsets.UTF_8))
            .build();
    previous.setConversionPhase(RbelConversionPhase.COMPLETED);
    client.getRbelLogger().getRbelConverter().addMessageToHistory(previous);

    client.signalNewCompletedMessage(previous);

    assertThat(latch.await(400, TimeUnit.MILLISECONDS))
        .as("All queued tasks should execute after signal")
        .isTrue();

    assertThat(executions.get()).isEqualTo(taskCount);

    // Ensure map cleaned
    @SuppressWarnings("unchecked")
    RingBufferHashMap<String, List<Runnable>> waiting =
        (RingBufferHashMap<String, List<Runnable>>)
            ReflectionTestUtils.getField(client, "parsingTasksWaitingForUuid");
    assertThat(waiting.get(prevUuid)).as("No residual waiting tasks for prevUuid").isEmpty();
  }

  @Test
  @DisplayName(
      "Immediate execution when previous message timestamp predates connection (mesh/reconnection"
          + " scenario)")
  void scheduleAfterMessage_previousMessageFromOldConnection_executesImmediately()
      throws Exception {
    client = newClient(5000); // long timeout to prove we don't wait for it

    String unknownPrev = "old-connection-prev-uuid";
    String curUuid = "cur-msg-old-prev";

    // Set connection start time to simulate a connection that's been active for 1 minute
    ZonedDateTime connectionStart = ZonedDateTime.now().minusMinutes(1);
    client.getWebSocketConnectionStartTime().set(connectionStart);

    // Simulate a previous message that was processed much earlier (before connection)
    ZonedDateTime oldTimestamp =
        connectionStart.minusMinutes(5); // 5 minutes before connection (6 minutes ago total)

    CountDownLatch latch = new CountDownLatch(1);
    client.scheduleAfterMessage(unknownPrev, latch::countDown, curUuid, oldTimestamp);

    assertThat(latch.await(200, TimeUnit.MILLISECONDS))
        .as(
            "Task should execute immediately because previous message timestamp predates"
                + " WebSocket connection start time (not waiting for the full 5s timeout)")
        .isTrue();
  }

  @Test
  @DisplayName("Queued task waits when previous message UUID was received but not yet completed")
  void scheduleAfterMessage_previousReceivedButNotCompleted_queues() throws Exception {
    client = newClient(5000);

    String prevUuid = "received-but-pending-uuid";
    String curUuid = "cur-msg-behind-pending";

    client.getRbelLogger().getRbelConverter().getKnownMessageUuids().add(prevUuid);

    CountDownLatch latch = new CountDownLatch(1);
    client.scheduleAfterMessage(prevUuid, latch::countDown, curUuid);

    // Should NOT execute immediately — it should wait because the message was received
    assertThat(latch.await(200, TimeUnit.MILLISECONDS))
        .as("Task should NOT execute immediately because previous was received (but not completed)")
        .isFalse();

    // Now complete the previous message and signal
    RbelElement previous =
        RbelElement.builder()
            .uuid(prevUuid)
            .rawContent("P".getBytes(StandardCharsets.UTF_8))
            .build();
    previous.setConversionPhase(RbelConversionPhase.COMPLETED);
    client.getRbelLogger().getRbelConverter().addMessageToHistory(previous);

    client.signalNewCompletedMessage(previous);

    assertThat(latch.await(200, TimeUnit.MILLISECONDS))
        .as("Task should execute after signal")
        .isTrue();
  }

  @Test
  @DisplayName("Time gap detection: old message + recent connection triggers immediate execution")
  void scheduleAfterMessage_timeGapDetection_executesImmediately() throws Exception {
    client = newClient(5000);

    String prevUuid = "time-gap-prev-uuid";
    String curUuid = "cur-msg-time-gap";

    // Set connection start time to simulate a connection that's been active for 30 seconds
    ZonedDateTime connectionStart = ZonedDateTime.now().minusSeconds(30);
    client.getWebSocketConnectionStartTime().set(connectionStart);

    // Simulate a previous message that was processed 2 minutes ago (exceeds 30-second threshold)
    ZonedDateTime oldTimestamp = ZonedDateTime.now().minusMinutes(2);

    CountDownLatch latch = new CountDownLatch(1);
    client.scheduleAfterMessage(prevUuid, latch::countDown, curUuid, oldTimestamp);

    assertThat(latch.await(200, TimeUnit.MILLISECONDS))
        .as(
            "Task should execute immediately due to time gap detection (old message + recent"
                + " connection)")
        .isTrue();
  }

  @Test
  @DisplayName("Grace period prevents false positives with small clock differences")
  void scheduleAfterMessage_gracePeriodHandling_queuesCorrectly() throws Exception {
    client = newClient(5000);

    String prevUuid = "grace-period-prev-uuid";
    String curUuid = "cur-msg-grace-period";

    // Set connection start time
    ZonedDateTime connectionStart = ZonedDateTime.now().minusSeconds(20);
    client.getWebSocketConnectionStartTime().set(connectionStart);

    // Simulate a previous message that was processed just 5 seconds before connection start
    // This should NOT trigger immediate execution due to grace period (accounts for clock skew)
    ZonedDateTime nearTimestamp = connectionStart.minusSeconds(5);

    CountDownLatch latch = new CountDownLatch(1);
    client.scheduleAfterMessage(prevUuid, latch::countDown, curUuid, nearTimestamp);

    // Should NOT execute immediately due to grace period protection
    assertThat(latch.await(200, TimeUnit.MILLISECONDS))
        .as("Task should NOT execute immediately due to grace period (5 sec < 10 sec grace period)")
        .isFalse();
  }

  @Test
  @DisplayName("Null timestamp falls back to timeout-based detection (backward compatibility)")
  void scheduleAfterMessage_nullTimestamp_fallsBackToTimeout() throws Exception {
    client = newClient(500); // short timeout for test speed

    String prevUuid = "null-timestamp-prev-uuid";
    String curUuid = "cur-msg-null-timestamp";

    // Set connection start time (should be ignored when timestamp is null)
    client.getWebSocketConnectionStartTime().set(ZonedDateTime.now().minusSeconds(10));

    CountDownLatch latch = new CountDownLatch(1);
    // Pass null timestamp to simulate legacy message without timestamp metadata
    client.scheduleAfterMessage(prevUuid, latch::countDown, curUuid, null);

    // Should NOT execute immediately - should use timeout fallback
    assertThat(latch.await(200, TimeUnit.MILLISECONDS))
        .as("Task should NOT execute immediately with null timestamp (legacy compatibility)")
        .isFalse();

    // But should execute after timeout (wait a bit longer)
    assertThat(latch.await(800, TimeUnit.MILLISECONDS))
        .as("Task should execute after timeout when previous message doesn't arrive")
        .isTrue();
  }

  @Test
  @DisplayName("No connection start time set falls back to timeout-based detection")
  void scheduleAfterMessage_noConnectionStartTime_fallsBackToTimeout() throws Exception {
    client = newClient(500);

    String prevUuid = "no-start-time-prev-uuid";
    String curUuid = "cur-msg-no-start-time";

    // Don't set webSocketConnectionStartTime (remains null)
    ZonedDateTime someTimestamp = ZonedDateTime.now().minusMinutes(5);

    CountDownLatch latch = new CountDownLatch(1);
    client.scheduleAfterMessage(prevUuid, latch::countDown, curUuid, someTimestamp);

    // Should NOT execute immediately without connection start time
    assertThat(latch.await(200, TimeUnit.MILLISECONDS))
        .as("Task should NOT execute immediately without connection start time")
        .isFalse();

    // But should execute after timeout
    assertThat(latch.await(800, TimeUnit.MILLISECONDS))
        .as("Task should execute after timeout when previous message doesn't arrive")
        .isTrue();
  }
}
