package io.mosire.agentlib.context;

import io.mosire.agentlib.llm.LlmMessage;

/**
 * 把一条 {@link ContextInjection} 渲染成一次请求里的 {@link LlmMessage}。
 *
 * <p><b>为什么是可替换接口而不是固定格式</b>：OpenAI 兼容线没有“上下文注入字段”，注入最终只能变成一条普通消息；不同宿主对 role、信封、语言 的偏好不同（默认 `user`
 * + `<system-reminder>`）。本接口只钉住“数据 → 一条消息”这一步，队列/去重/事件都不在这里。
 *
 * <p><b>默认渲染器</b>：{@link #systemReminder()}，固定 `user` 角色。若宿主确认某 provider 支持对话中途的 system 消息，可自行实现并返回
 * {@link LlmMessage#system(String)}；provider 兼容性由宿主承担。
 */
@FunctionalInterface
public interface ContextInjectionRenderer {

  /** 渲染一条注入；返回非 null。 */
  LlmMessage render(ContextInjection injection);

  /**
   * 默认渲染器：`user` 角色 + `<system-reminder kind="..." id="...">` 信封。
   *
   * <p>信封只是给模型的可读标记，不是协议字段；正文里的闭合标签会被转义，避免宿主自定义文本破坏信封。
   */
  static ContextInjectionRenderer systemReminder() {
    return injection -> LlmMessage.user(format(injection));
  }

  /** 默认信封的纯文本形态（供测试/自定义渲染器复用）。 */
  static String format(ContextInjection injection) {
    return "<system-reminder kind=\""
        + escapeAttribute(injection.kind())
        + "\" id=\""
        + escapeAttribute(injection.id())
        + "\">\n"
        + escapeClosingTag(injection.text())
        + "\n</system-reminder>";
  }

  private static String escapeAttribute(String value) {
    return value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }

  private static String escapeClosingTag(String text) {
    return text.replace("</system-reminder>", "<\\/system-reminder>");
  }
}
