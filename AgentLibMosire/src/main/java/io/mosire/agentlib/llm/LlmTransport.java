package io.mosire.agentlib.llm;

import java.time.Duration;
import java.util.Objects;

/**
 * 一条 {@link ModelRoute} 的<b>接法</b>：协议方言 + 超时（A3 ①②）。
 *
 * <p><b>为什么把超时从"客户端构造器参数"挪到"路由数据"</b>：超时是<b>供应商的属性</b>而不是调用方的属性——本地 Ollama
 * 与跨洋网关的合理超时差一个数量级，配置页要让用户逐 provider 填（simos 侧的 {@code timeoutMs} 就是这个位置）。原先它只在 {@code
 * OpenAICompatibleLlmClient} 的构造器上，结果是"谁能设超时"取决于"谁负责 new 客户端"——配置装配路径反而设不了。
 *
 * <p><b>两个超时各管一段</b>（合起来是一次 {@code chat} 的全过程）：
 *
 * <ul>
 *   <li>{@code connectTimeout}：与供应商<b>建连</b>的上限（{@link java.net.http.HttpClient} 的 connectTimeout）。
 *   <li>{@code readTimeout}：一次调用的<b>整体</b>硬上限——建连 + 响应头 + 流式读取（含 {@code [DONE]} 之前的阻塞读）全算在内。
 * </ul>
 *
 * <p>缺省 {@link #defaults()} 与 {@code OpenAICompatibleLlmClient} 的历史常量逐字相同：老调用点不写 transport 时行为不变。
 *
 * <p><b>本记录不做"未设置"语义</b>（有意）：{@link #defaults()} 就是补默认值的地方，记录本身永远持有确定的正值——否则"没配"会一路
 * 漂到客户端里，每一层都要问一次"这到底是没配还是配了 0"。
 */
public record LlmTransport(LlmProtocol protocol, Duration connectTimeout, Duration readTimeout) {

  /** 默认建连超时。 */
  public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

  /** 默认读取超时。 */
  public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(60);

  public LlmTransport {
    Objects.requireNonNull(protocol, "protocol");
    positive(connectTimeout, "connectTimeout");
    positive(readTimeout, "readTimeout");
  }

  /** 全缺省接法：OpenAI 兼容 + 默认超时。 */
  public static LlmTransport defaults() {
    return new LlmTransport(
        LlmProtocol.OPENAI_COMPATIBLE, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
  }

  /** 只改读取超时（最常见的诉求：给慢供应商放宽整体上限）。 */
  public static LlmTransport withReadTimeout(Duration readTimeout) {
    return new LlmTransport(LlmProtocol.OPENAI_COMPATIBLE, DEFAULT_CONNECT_TIMEOUT, readTimeout);
  }

  private static Duration positive(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " 必须为正: " + value);
    }
    return value;
  }
}
