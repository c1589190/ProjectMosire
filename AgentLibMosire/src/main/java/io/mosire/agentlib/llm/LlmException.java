package io.mosire.agentlib.llm;

/**
 * LLM 调用失败（网络/协议/认证/限额等）。
 *
 * <p><b>{@link Kind} 是本类对 B3 的正面回答</b>：调用方（如 simos 的决策判定器 N13 降级路径）不该靠 {@code catch
 * (RuntimeException)} 一刀切——"该降级"与"该炸"是两回事。分类<b>由抛出点定</b>（它才知道上下文），调用方只做判断，不要靠解析 message
 * 文本（先例：{@link io.mosire.agentlib.config.ConfigException} 的错误码）。
 *
 * <p><b>两个判据刻意分开</b>（{@link Kind#retryable()} 与 {@link Kind#degradable()}）：
 *
 * <ul>
 *   <li>"重试有没有意义"是<b>时间</b>维度：同一路由退避后再来一次，可能就成功了。
 *   <li>"能不能降级"是<b>降级面</b>维度：本次拿不到结果时，调用方是走保守默认值继续跑，还是把失败冒泡成致命错误。
 * </ul>
 *
 * <p>二者不一致的典型是 {@link Kind#AUTH}：密钥错了，重试一万次也没用（{@code retryable()=false}），但"供应商今天用不了"这件事完全
 * 可以由调用方降级兜住（{@code degradable()=true}）。
 *
 * <p><b>{@link QuotaExceededException} 不在此列</b>：它是<b>本地账本</b>超限（防跑飞硬顶），既不可重试也不可降级——见 {@link
 * LlmQuota}。
 */
public class LlmException extends RuntimeException {

  /** 失败分类。分类是"抛出点对上下文的知识"，不是对 message 的猜测。 */
  public enum Kind {

    /** 网络/连接层失败（DNS、连接被拒、TLS、连接重置）——本次没接通，与供应商是否健康无关。 */
    TRANSPORT(true, true),

    /** 超时（建连/响应头/整段交换）——对方可能只是慢。 */
    TIMEOUT(true, true),

    /** 供应商限流（HTTP 429）——退避后重试是标准解法。 */
    RATE_LIMIT(true, true),

    /** 供应商 5xx——对方内部故障，过一会儿可能就好了。 */
    PROVIDER_ERROR(true, true),

    /**
     * 鉴权失败（HTTP 401/403）。
     *
     * <p>{@code retryable=false}：密钥错了或没配，重试一万次也一样。{@code degradable=true}：这条路由今天用不了，但调用方走保守
     * 默认值继续跑是合理的——**前提是失败被记录/上报**，否则就成了静默降级（本项目最坏的失败模式）。
     */
    AUTH(false, true),

    /** 请求被供应商拒绝（4xx 非 429：模型不存在、参数非法、余额不足等）——重试无用，改配置才治本。 */
    REQUEST_REJECTED(false, true),

    /**
     * 协议违约（SSE 不合法、流在 {@code [DONE]} 前被截断、{@code arguments} 不是合法 JSON）。
     *
     * <p>{@code degradable=true} 但**强烈建议降级前先响亮记录**：这类失败意味着"供应商在骗我们"或"接错了端点"，同一个错误会在
     * 日志里反复出现——静默降级会让它永远查不出来。{@code retryable=false}<b>不是</b>因为重试一定失败，而是因为本项目把重试定为调用方 策略（{@link
     * LlmClient#chat} 的契约是"一次调用 = 一次请求"），分类只回答"重试<b>值不值得</b>"。
     */
    PROTOCOL(false, true),

    /** 本地配置/装配错误（{@code baseUrl} 非法、取密钥失败）——重试无用，且不是供应商的问题。 */
    CONFIG(false, false),

    /**
     * 调用方要的响应形态没拿到：{@link LlmClient#text} 遇到"只有工具调用、没有文本"、"只有空白文本"或"什么都没产出"。
     *
     * <p><b>这不是供应商故障</b>——模型选择调工具是正常行为，只是"只想要一段文本"的调用方要不到。故单列一类：{@code
     * retryable=false}，不该被当成供应商抖动去重试。
     *
     * <p>{@code degradable=true}：本仓这条判据的含义是"调用方<b>走保守默认值继续跑是合理的</b>"（同 {@link #AUTH}），
     * <b>不是</b>"换个供应商就能好"。拿不到文本时退回一条兜底文案或非 LLM 路径，正是合理的降级——<b>前提是失败被
     * 记录/上报</b>，否则就成了静默降级（本项目最坏的失败模式）。
     */
    NO_TEXT(false, true),

    /**
     * 调用被取消/中断（调用线程被 interrupt，或本次调用已由超时路径取消）。
     *
     * <p>不可重试、不可降级：取消是<b>调用方自己的决定</b>（关停、上层放弃），把它当成供应商故障去重试或降级，等于把"我要停"翻译成 "再试一次"。
     */
    CANCELLED(false, false),

    /** 本类内部/未分类——**保守判"该炸"**：不确定的失败不许悄悄重试，也不许悄悄降级。 */
    INTERNAL(false, false);

    private final boolean retryable;
    private final boolean degradable;

    Kind(boolean retryable, boolean degradable) {
      this.retryable = retryable;
      this.degradable = degradable;
    }

    /** 退避后重试<b>值不值得</b>（不是"重试一定成功"）。 */
    public boolean retryable() {
      return retryable;
    }

    /** 调用方能否走"无 LLM"降级路径继续跑（{@code false} = 该炸，必须冒泡）。 */
    public boolean degradable() {
      return degradable;
    }
  }

  private final Kind kind;

  /** 未分类失败（{@link Kind#INTERNAL}）——既有抛出点逐字不动。 */
  public LlmException(String message) {
    this(message, Kind.INTERNAL, null);
  }

  public LlmException(String message, Throwable cause) {
    this(message, Kind.INTERNAL, cause);
  }

  /** 带分类的失败（新抛出点的正途）。 */
  public LlmException(String message, Kind kind) {
    this(message, kind, null);
  }

  public LlmException(String message, Kind kind, Throwable cause) {
    super(message, cause);
    this.kind = kind == null ? Kind.INTERNAL : kind;
  }

  /** 失败分类（永不为 null：null 一律按 {@link Kind#INTERNAL} 收口）。 */
  public Kind kind() {
    return kind;
  }

  /** 便捷：退避后重试值不值得。等价于 {@code kind().retryable()}。 */
  public boolean retryable() {
    return kind.retryable();
  }

  /** 便捷：能否走降级路径。等价于 {@code kind().degradable()}。 */
  public boolean degradable() {
    return kind.degradable();
  }
}
