package io.mosire.agentlib.context;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * 一条<b>宿主发起的上下文注入</b>：告诉模型一件“上下文之外”的事（工具面变化、策略变化、系统提醒），但<b>不</b>把它写成 system 提示词，也<b>不</b>改写历史。
 *
 * <p><b>为什么单独成对象而不是直接 append 一条 {@code LlmMessage}</b>：
 *
 * <ul>
 *   <li>注入是<b>一次性投递</b>的（成功才清队列），和“历史消息”的生命周期不同；
 *   <li>它需要幂等键、优先级、可审计的结构化 {@code data}；直接塞消息会把这些语义压成一段不可解析的文本；
 *   <li>渲染 role/信封由宿主决定（{@link ContextInjectionRenderer}），数据对象不绑定任何 provider 形状。
 * </ul>
 *
 * <p><b>边界</b>：只有宿主/框架能创建并 {@link ContextWindow#inject(ContextInjection)}；{@code AgentTool} /
 * {@code ToolContext} 不暴露注入口——否则工具输出就能变成 prompt injection 的入口。正文由宿主保证不含密钥。
 *
 * @param id 幂等键（非空白；同一 id 只投递一次）
 * @param kind 类型（非空白；标准值见 {@link ContextInjectionKinds}，宿主可自定义）
 * @param priority 优先级（null ⇒ {@link Priority#NORMAL}）
 * @param summary 一行摘要（非空白；给事件/日志，不要求是正文的子串）
 * @param text 给模型的正文（非空白；上限 {@value #MAX_TEXT_CHARS} 字符）
 * @param data 结构化附加信息（null ⇒ 空 Map；立即不可变拷贝）
 * @param createdAt 创建时刻（null ⇒ 现在）
 */
public record ContextInjection(
    String id,
    String kind,
    Priority priority,
    String summary,
    String text,
    Map<String, Object> data,
    Instant createdAt) {

  /** 正文长度上限：防止一次注入挤爆上下文。 */
  public static final int MAX_TEXT_CHARS = 8 * 1024;

  /** 摘要长度上限。 */
  public static final int MAX_SUMMARY_CHARS = 512;

  /** 注入优先级；数字越小越先渲染。 */
  public enum Priority {
    HIGH,
    NORMAL,
    LOW
  }

  public ContextInjection {
    id = requireText(id, "id");
    kind = requireText(kind, "kind");
    priority = priority == null ? Priority.NORMAL : priority;
    summary = requireText(summary, "summary");
    text = requireText(text, "text");
    if (summary.length() > MAX_SUMMARY_CHARS) {
      throw new IllegalArgumentException(
          "ContextInjection.summary 超过上限 " + MAX_SUMMARY_CHARS + " 字符: " + summary.length());
    }
    if (text.length() > MAX_TEXT_CHARS) {
      throw new IllegalArgumentException(
          "ContextInjection.text 超过上限 " + MAX_TEXT_CHARS + " 字符: " + text.length());
    }
    data = data == null ? Map.of() : Map.copyOf(data);
    createdAt = createdAt == null ? Instant.now() : createdAt;
  }

  /** 便捷工厂：优先级 {@link Priority#NORMAL}、当前时刻。 */
  public static ContextInjection of(
      String id, String kind, String summary, String text, Map<String, Object> data) {
    return new ContextInjection(id, kind, Priority.NORMAL, summary, text, data, Instant.now());
  }

  /**
   * 结构化 {@code data} 里的 {@code revision}（Number 形态）；没有或不是数字 ⇒ 空。
   *
   * <p>工具面变化用它做“旧 revision 被新 revision 替换”的判据；宿主自定义 kind 可不用。
   */
  public OptionalLong revision() {
    Object value = data.get("revision");
    return value instanceof Number number
        ? OptionalLong.of(number.longValue())
        : OptionalLong.empty();
  }

  private static String requireText(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException("ContextInjection." + field + " 不得为空白");
    }
    return value;
  }
}
