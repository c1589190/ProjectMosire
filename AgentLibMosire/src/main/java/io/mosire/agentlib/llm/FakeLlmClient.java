package io.mosire.agentlib.llm;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * 脚本化假 LLM——离线测试/演示共用（响应序列由调用方编排）。
 *
 * <p>约定：按 {@link #enqueue} 顺序逐条出队；脚本耗尽后调用 {@link #chat} 抛 {@link
 * LlmException}（让"跑飞"的循环在测试里立刻暴露，而不是静默复用最后一条）。
 */
public final class FakeLlmClient implements LlmClient {

  private final Deque<LlmResponse> script = new ArrayDeque<>();
  private int calls;

  public static FakeLlmClient with(LlmResponse... responses) {
    FakeLlmClient client = new FakeLlmClient();
    for (LlmResponse response : responses) {
      client.enqueue(response);
    }
    return client;
  }

  public void enqueue(LlmResponse response) {
    script.addLast(Objects.requireNonNull(response, "response"));
  }

  /** 当前已排队条数（测试断言用）。 */
  public int queued() {
    return script.size();
  }

  /** 已消耗条数（测试断言用）。 */
  public int calls() {
    return calls;
  }

  /** 观测名如实报 {@code fake}：本类就是"离线假模型"本身（{@code --demo} 是它唯一的生产正途）。 */
  @Override
  public String model() {
    return "fake";
  }

  @Override
  public LlmResponse chat(LlmRequest request) {
    calls++;
    LlmResponse response = script.pollFirst();
    if (response == null) {
      throw new LlmException("FakeLlmClient 脚本已耗空（第 " + calls + " 次调用）——疑似循环跑飞，请补脚本");
    }
    return response;
  }
}
