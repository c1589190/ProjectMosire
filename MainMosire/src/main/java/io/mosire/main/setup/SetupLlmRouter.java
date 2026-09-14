package io.mosire.main.setup;

import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import java.util.Objects;

/**
 * 可热切换的 LLM 客户端持有者（配置引导 D6）：装配期以<b>未配置态</b>注入进程，setup 成功后原地换成真客户端—— {@code AgentRuntime}/{@code
 * App} 的装配零改动（LLM 仍由 {@code Main} 经 {@code llmOverride} 注入）。
 *
 * <p><b>未配置态诚实口径（D24）</b>：{@link #model()} 如实报 {@code "unconfigured"}（不是 fake、不是 unknown 混同），
 * {@link #chat} 抛带导航文案的 {@link LlmException}——指引调用方走 HTTP setup 端点或重启进 CLI 向导。
 * 配置本身同步落盘，热切换只是<b>免重启</b>；重启后进程直接以真客户端装配。
 *
 * <p><b>线程模型</b>：{@code chat}/{@code model()} 读 volatile delegate（管线调用线程无需加锁）； {@link #swap}
 * synchronized（与自身读的竞态最多是"换了一半看到旧/新之一"，不存在中间态）。
 */
public final class SetupLlmRouter implements LlmClient {

  private static final String UNCONFIGURED_MODEL = "unconfigured";

  /** 换装锁（私有锁对象——this 经工厂逸出，intrinsic this 锁是 USO_UNSAFE_METHOD_SYNCHRONIZATION 面）。 */
  private final Object swapLock = new Object();

  private volatile LlmClient delegate;

  private SetupLlmRouter(LlmClient initial) {
    this.delegate = Objects.requireNonNull(initial, "initial");
  }

  /** 未配置态工厂：chat 响亮导航、model 如实报 {@code unconfigured}。 */
  public static SetupLlmRouter unconfigured() {
    return new SetupLlmRouter(new UnconfiguredClient());
  }

  /** 热切换（setup 成功后调用）：null 拒绝。 */
  public void swap(LlmClient next) {
    Objects.requireNonNull(next, "next");
    synchronized (swapLock) {
      this.delegate = next;
    }
  }

  /** 是否已配置（真客户端已就位）。 */
  public boolean configured() {
    return !(delegate instanceof UnconfiguredClient);
  }

  @Override
  public LlmResponse chat(LlmRequest request) throws LlmException {
    return delegate.chat(request);
  }

  @Override
  public String model() {
    return delegate.model();
  }

  /** 未配置态本体（导航文案是它的全部行为——失败也要告诉人下一步怎么走）。 */
  private static final class UnconfiguredClient implements LlmClient {

    private static final String GUIDANCE =
        "LLM 尚未配置（--setup 引导态）：POST http://127.0.0.1:<setup端口>/api/setup/llm "
            + "提交 OpenAI 兼容 provider（baseUrl/model/apiKey，端口见启动行 setup=…），"
            + "或重启进程走交互引导 / 手写 <dataDir>/config.json";

    @Override
    public LlmResponse chat(LlmRequest request) throws LlmException {
      throw new LlmException(GUIDANCE);
    }

    @Override
    public String model() {
      return UNCONFIGURED_MODEL;
    }
  }
}
