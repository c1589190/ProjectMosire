package io.mosire.brain.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.agent.AgentConfig;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.EventWrite;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.store.SqliteConversationStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentRuntimeEmbeddingTest {

  private static final AgentSpec SPEC = new AgentSpec(AgentConfig.builder("embedded").build());

  @TempDir Path directory;

  @Test
  void reopeningOwnedStorageResumesTheResetConversationAndPreservesTheOldHistory() {
    try (AgentRuntime first =
        AgentRuntime.builder(SPEC, request -> LlmResponse.text("fixture"))
            .dataDirectory(directory)
            .initialConversationId("first")
            .build()) {
      assertThat(first.conversationId()).isEqualTo("first");
      assertThat(first.chat("旧会话").finalText()).contains("fixture");
      first.resetSession("active");
      assertThat(first.chat("新会话").finalText()).contains("fixture");
    }
    try (AgentRuntime reopened =
        AgentRuntime.builder(
                SPEC,
                request -> {
                  assertThat(request.messages())
                      .containsSubsequence(LlmMessage.user("新会话"), LlmMessage.user("重启后续聊"))
                      .doesNotContain(LlmMessage.user("旧会话"));
                  return LlmResponse.text("continued");
                })
            .dataDirectory(directory)
            .initialConversationId("unused")
            .build()) {
      assertThat(reopened.conversationId()).isEqualTo("active");
      assertThat(reopened.chat("重启后续聊").finalText()).contains("continued");
    }
    try (SqliteConversationStore store =
        SqliteConversationStore.open(directory.resolve("events.db"))) {
      assertThat(store.currentConversationId()).contains("active");
      assertThat(store.load("first")).contains(LlmMessage.user("旧会话"));
      assertThat(store.load("active"))
          .containsSubsequence(LlmMessage.user("新会话"), LlmMessage.user("重启后续聊"));
    }
  }

  @Test
  void borrowedStorageRemainsUsableAndDrainsItsBusBeforeClosingTheDatabase() throws Exception {
    CountDownLatch listening = new CountDownLatch(1);
    CountDownLatch allowWrite = new CountDownLatch(1);
    try (RuntimeStorage storage = RuntimeStorage.open(directory, false);
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      try (AgentRuntime runtime =
          AgentRuntime.builder(SPEC, request -> LlmResponse.text("fixture"))
              .storage(storage)
              .build()) {
        assertThat(runtime.chat("借用存储").finalText()).contains("fixture");
      }
      assertThat(storage.eventStore().count()).isPositive();

      ApprovalCoordinator approval =
          new ApprovalCoordinator(
              List.of(), List.of(), new PendingApprovals(), Duration.ZERO, storage.eventBus());
      assertThatThrownBy(
              () ->
                  AgentRuntime.builder(SPEC, request -> LlmResponse.text("unused"))
                      .storage(storage)
                      .approvalCoordinator(approval)
                      .build())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("审批")
          .hasMessageContaining("持久会话");

      storage
          .eventBus()
          .subscribe(
              event -> {
                listening.countDown();
                awaitWritePermission(allowWrite);
                storage.eventStore().append(EventWrite.of("test.mirrored", event.agent()));
              });
      Future<?> closing;
      try {
        storage
            .eventBus()
            .publish(storage.eventStore().append(EventWrite.of("test.source", "test")));
        assertThat(listening.await(5, TimeUnit.SECONDS)).isTrue();
        closing = workers.submit(storage::close);
        awaitBusRefusesSubscriptions(storage.eventBus());
      } finally {
        allowWrite.countDown();
      }
      closing.get(5, TimeUnit.SECONDS);
    }
    try (SqliteEventStore reopened = SqliteEventStore.open(directory.resolve("events.db"))) {
      assertThat(reopened.query(EventQuery.recent(20)))
          .extracting(Event::type)
          .contains("test.source", "test.mirrored");
    }
  }

  private static void awaitWritePermission(CountDownLatch permission) {
    try {
      assertThat(permission.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("等待镜像写入时中断", interrupted);
    }
  }

  private static void awaitBusRefusesSubscriptions(EventBus bus) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      try (var subscription = bus.subscribe(event -> {})) {
        // 回收拒绝订阅意味着已进入排空阶段。稍后重试，不提前放行镜像写入。
      } catch (IllegalStateException closed) {
        assertThat(closed).hasMessageContaining("EventBus 已关闭");
        return;
      }
      Thread.sleep(Duration.ofMillis(1));
    }
    throw new AssertionError("事件总线未进入关闭阶段");
  }
}
