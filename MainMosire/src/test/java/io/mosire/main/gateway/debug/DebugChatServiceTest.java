package io.mosire.main.gateway.debug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.runtime.AgentRuntime;
import io.mosire.brain.runtime.StopReason;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P2-1 Task 2：{@link DebugChatService} 内存态对话运行记录（离线全链路）：submit → 共享单线程 chat 执行器串行执行 → {@code
 * runtime.chat} → 状态流转（RUNNING → FINISHED/CANCELLED/FAILED）与 {@code TurnResult} 投影、stop 取消、倒序分页。
 *
 * <p>取消用门控 LLM（借 AgentPipelineTest 的 GateLlmClient 形态）：第二次 LLM 调用挂起等放行——提供确定的取消同步点。
 */
class DebugChatServiceTest {

  @TempDir Path tempDir;

  /** 在线装配：SqliteEventStore（临时文件）+ FakeLlm/门控 LLM + 单线程虚拟线程 chat 执行器（共享、测试结束时统一关停）。 */
  private static final class Fixture implements AutoCloseable {
    final SqliteEventStore store;
    final EventBus bus;
    final AgentRuntime runtime;
    final ExecutorService chatExecutor;
    final DebugChatService service;

    Fixture(LlmClient llm, Path dataDir) {
      this.store = SqliteEventStore.open(dataDir.resolve("events.db"));
      this.bus = new EventBus();
      ToolRegistry tools = new ToolRegistry();
      tools.register(
          new AgentTool() {
            @Override
            public String name() {
              return "echo";
            }

            @Override
            public ToolResult execute(ToolContext context) {
              return ToolResult.ok("echo: " + context.arguments());
            }
          });
      this.runtime =
          new AgentRuntime(
              AgentConfig.builder("main")
                  .systemPrompt("你是 Mosire 主 Agent。")
                  .description("测试主 Agent")
                  .build(),
              llm,
              tools,
              store,
              bus,
              AgentPermissionSet.system());
      this.chatExecutor =
          Executors.newSingleThreadExecutor(Thread.ofVirtual().name("debug-chat-test-").factory());
      this.service = new DebugChatService(runtime, chatExecutor);
    }

    @Override
    public void close() {
      service.close();
      runtime.close();
      bus.close();
      store.close();
      chatExecutor.shutdownNow();
    }
  }

  /** 第 2 次 chat 调用挂起、等测试线程放行的门控脚本桩（运行中取消需要确定的同步点）。 */
  private static final class GateLlmClient implements LlmClient {

    final CountDownLatch enteredSecondCall = new CountDownLatch(1);
    final CountDownLatch releaseSecondCall = new CountDownLatch(1);

    private final Deque<LlmResponse> script = new ArrayDeque<>();
    private int calls;

    void enqueue(LlmResponse response) {
      script.addLast(response);
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      calls++;
      if (calls == 2) {
        enteredSecondCall.countDown();
        boolean released = false;
        try {
          released = releaseSecondCall.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        if (!released) {
          throw new LlmException("GateLlmClient 第二次调用未被放行（取消检查点未生效？）");
        }
      }
      LlmResponse response = script.pollFirst();
      if (response == null) {
        throw new LlmException("GateLlmClient 脚本已耗空");
      }
      return response;
    }
  }

  @Test
  void submitRunsToFinishedAndStoresTurnResult() throws Exception {
    try (Fixture fixture =
        new Fixture(
            FakeLlmClient.with(LlmResponse.text("你好，我在。"), LlmResponse.text("再聊")), tempDir)) {
      long runId1 = fixture.service.submit("第一条");
      long runId2 = fixture.service.submit("第二条");

      assertThat(runId2).isEqualTo(runId1 + 1); // runId 自增

      DebugChatRun finished1 = awaitTerminal(fixture.service, runId1);
      DebugChatRun finished2 = awaitTerminal(fixture.service, runId2);

      assertThat(finished1.status()).isEqualTo(DebugChatRun.Status.FINISHED);
      assertThat(finished1.message()).isEqualTo("第一条");
      assertThat(finished1.createdAt()).isNotNull();
      assertThat(finished1.result()).isNotNull();
      assertThat(finished1.result().stopReason()).isEqualTo(StopReason.FINISHED);
      assertThat(finished1.result().finalText()).contains("你好，我在。");
      assertThat(finished2.status()).isEqualTo(DebugChatRun.Status.FINISHED);

      // 已终态运行上的 stop 必须是 no-op（false）
      assertThat(fixture.service.stop(runId1)).isFalse();
      // 不存在的 runId 同样 false
      assertThat(fixture.service.stop(999L)).isFalse();
    }
  }

  @Test
  void stopCancelsLongRunningRun() throws Exception {
    GateLlmClient llm = new GateLlmClient();
    // 放行后第二个响应仍是工具调用——取消检查点在 executeToolCall 之前，c-2 不得执行（同 AgentPipelineTest 取消形态）
    llm.enqueue(LlmResponse.toolCall("c-1", "echo", Map.of("text", "hi")));
    llm.enqueue(LlmResponse.toolCall("c-2", "echo", Map.of("text", "again")));
    llm.enqueue(LlmResponse.text("恢复后回答"));
    try (Fixture fixture = new Fixture(llm, tempDir)) {
      long runId = fixture.service.submit("取消我");

      // 等运行进入第二次 LLM 调用（挂起中）——此刻状态必为 RUNNING
      assertThat(llm.enteredSecondCall.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(fixture.service.get(runId)).isPresent();
      assertThat(fixture.service.get(runId).orElseThrow().status())
          .isEqualTo(DebugChatRun.Status.RUNNING);

      assertThat(fixture.service.stop(runId)).isTrue();
      llm.releaseSecondCall.countDown();

      DebugChatRun cancelled = awaitTerminal(fixture.service, runId);
      assertThat(cancelled.status()).isEqualTo(DebugChatRun.Status.CANCELLED);
      assertThat(cancelled.result()).isNotNull();
      assertThat(cancelled.result().stopReason()).isEqualTo(StopReason.CANCELLED);
    }
  }

  @Test
  void listResultsPaginatesNewestFirst() throws Exception {
    FakeLlmClient llm =
        FakeLlmClient.with(
            LlmResponse.text("一"),
            LlmResponse.text("二"),
            LlmResponse.text("三"),
            LlmResponse.text("四"),
            LlmResponse.text("五"));
    try (Fixture fixture = new Fixture(llm, tempDir)) {
      long first = fixture.service.submit("1");
      fixture.service.submit("2");
      fixture.service.submit("3");
      fixture.service.submit("4");
      long last = fixture.service.submit("5");

      for (long id = first; id <= last; id++) {
        awaitTerminal(fixture.service, id);
      }

      List<DebugChatRun> page0 = fixture.service.listResults(0, 2);
      List<DebugChatRun> page1 = fixture.service.listResults(1, 2);
      List<DebugChatRun> page2 = fixture.service.listResults(2, 2);

      assertThat(page0).extracting(DebugChatRun::runId).containsExactly(last, last - 1);
      assertThat(page1).extracting(DebugChatRun::runId).containsExactly(last - 2, last - 3);
      assertThat(page2).extracting(DebugChatRun::runId).containsExactly(first);

      assertThatThrownBy(() -> fixture.service.listResults(-1, 2))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> fixture.service.listResults(0, 0))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void submitAfterCloseThrows() {
    try (Fixture fixture = new Fixture(FakeLlmClient.with(LlmResponse.text("你好")), tempDir)) {
      fixture.service.close();
      assertThatThrownBy(() -> fixture.service.submit("晚了"))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void llmErrorStopReasonMarksRunFailedWithResultStored() throws Exception {
    // 脚本耗空 → FakeLlmClient 抛 LlmException → 管线优雅终止为 StopReason.LLM_ERROR → 状态 FAILED、TurnResult 已存
    try (Fixture fixture = new Fixture(FakeLlmClient.with(), tempDir)) {
      long runId = fixture.service.submit("会失败");
      DebugChatRun failed = awaitTerminal(fixture.service, runId);
      assertThat(failed.status()).isEqualTo(DebugChatRun.Status.FAILED);
      assertThat(failed.result()).isNotNull();
      assertThat(failed.result().stopReason()).isEqualTo(StopReason.LLM_ERROR);
    }
  }

  @Test
  void unexpectedExceptionMarksRunFailedWithNullResult() throws Exception {
    // 非 LlmException 的 RuntimeException 穿透管线 → 服务就地记 FAILED、无 TurnResult
    LlmClient boom =
        request -> {
          throw new IllegalStateException("模拟运行时崩溃");
        };
    try (Fixture fixture = new Fixture(boom, tempDir)) {
      long runId = fixture.service.submit("会炸");
      DebugChatRun failed = awaitTerminal(fixture.service, runId);
      assertThat(failed.status()).isEqualTo(DebugChatRun.Status.FAILED);
      assertThat(failed.result()).isNull();
    }
  }

  /** 轮询等运行到达终态（执行器异步推进，无终态事件可订阅——内存态轮询即最简同步点）。 */
  private static DebugChatRun awaitTerminal(DebugChatService service, long runId)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Optional<DebugChatRun> run = service.get(runId);
      if (run.isPresent() && run.get().status() != DebugChatRun.Status.RUNNING) {
        return run.get();
      }
      TimeUnit.MILLISECONDS.sleep(10);
    }
    throw new AssertionError("运行未在超时内到达终态: " + runId);
  }
}
