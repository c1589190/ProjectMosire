package io.mosire.agentlib.llm;

import java.time.Duration;
import java.util.Objects;

/**
 * 一条 {@link ModelRoute} 的<b>接法</b>：协议方言 + 超时 + 思考模式是否要求回传 {@code reasoning_content}（A3 ①②；A6
 * 修复版能力位）。
 *
 * <p><b>为什么把超时从“客户端构造器参数”挪到“路由数据”</b>：超时是<b>供应商的属性</b>而不是调用方的属性——本地 Ollama
 * 与跨洋网关的合理超时差一个数量级，配置页要让用户逐 provider 填（simos 侧的 {@code timeoutMs} 就是这个位置）。原先它只在 {@code
 * OpenAICompatibleLlmClient} 的构造器上，结果是“谁能设超时”取决于“谁负责 new 客户端”——配置装配路径反而设不了。
 *
 * <p><b>两个超时各管一段</b>（2026-10-01 语义修正：长推理不再按总时长切断）：
 *
 * <ul>
 *   <li>{@code connectTimeout}：与供应商<b>建连</b>的上限（{@link java.net.http.HttpClient} 的 connectTimeout）。
 *   <li>{@code readTimeout}：<b>流空闲超时</b>——进入 SSE 后，两次“读到新数据”的间隔上限；只要 data 帧/SSE
 *       心跳还在到达，总时长不设上限。reasoning 模型单次生成数分钟是合法形态。<b>注意</b>：它不再是整个交换的总时长上限。
 * </ul>
 *
 * <p><b>{@code echoReasoningContent}</b>（A6 修复版）：思考模式供应商要求历史里的 assistant 消息把上一轮的 {@code
 * reasoning_content} 原样带回；当上一轮没有思维链内容时，这个键也必须以空串形式存在。该位为 {@code true} 时，发送侧对每条 assistant
 * 消息恒发该键（无内容发 {@code ""}）；为 {@code false} 时维持“有思维链正文才发”的历史行为，避免给不认该字段的供应商塞未知键。
 * 能力位属于路由/供应商，不属于单条消息——同一条历史消息在思考路由与普通路由上的线形态本就不同。
 *
 * <p>缺省 {@link #defaults()} 与 {@code OpenAICompatibleLlmClient} 的历史常量逐字相同：老调用点不写 transport 时行为不变
 * （{@code echoReasoningContent=false}）。
 *
 * <p><b>本记录不做“未设置”语义</b>（有意）：{@link #defaults()} 就是补默认值的地方，记录本身永远持有确定的正值——否则“没配”会一路
 * 漂到客户端里，每一层都要问一次“这到底是没配还是配了 0”。
 */
public record LlmTransport(
    LlmProtocol protocol,
    Duration connectTimeout,
    Duration readTimeout,
    boolean echoReasoningContent) {

  /** 默认建连超时。 */
  public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

  /** 默认读取超时。 */
  public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(60);

  public LlmTransport {
    Objects.requireNonNull(protocol, "protocol");
    positive(connectTimeout, "connectTimeout");
    positive(readTimeout, "readTimeout");
  }

  /** 三参兼容构造：{@code echoReasoningContent=false}，与 A6 之前的历史行为逐字相同。 */
  public LlmTransport(LlmProtocol protocol, Duration connectTimeout, Duration readTimeout) {
    this(protocol, connectTimeout, readTimeout, false);
  }

  /** 全缺省接法：OpenAI 兼容 + 默认超时 + 不回传空思维链键。 */
  public static LlmTransport defaults() {
    return new LlmTransport(
        LlmProtocol.OPENAI_COMPATIBLE, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, false);
  }

  /** 只改流空闲超时（最常见的诉求：给慢供应商放宽“多久没数据才算死”）；思维链回传位保持 {@code false}。 */
  public static LlmTransport withReadTimeout(Duration readTimeout) {
    return new LlmTransport(
        LlmProtocol.OPENAI_COMPATIBLE, DEFAULT_CONNECT_TIMEOUT, readTimeout, false);
  }

  /** 流空闲超时的显式别名（与 {@link #readTimeout()} 同一值）：新代码用它表达“只要还在输出就不切”。 */
  public Duration streamIdleTimeout() {
    return readTimeout;
  }

  /** 换思考模式回传位（其余组件不变）——配置加载与手动装配都用它，避免逐字段抄。 */
  public LlmTransport withEchoReasoningContent(boolean newEchoReasoningContent) {
    return new LlmTransport(protocol, connectTimeout, readTimeout, newEchoReasoningContent);
  }

  private static Duration positive(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " 必须为正: " + value);
    }
    return value;
  }
}
