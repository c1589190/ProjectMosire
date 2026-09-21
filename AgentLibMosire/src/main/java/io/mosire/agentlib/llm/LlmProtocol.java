package io.mosire.agentlib.llm;

import java.util.Locale;

/**
 * 供应商的<b>协议/方言</b>（A3 ②）：决定"{@code ModelRoute} 该怎么接线成 {@link LlmClient}"。
 *
 * <p>现在只有一种。之所以<b>先把字段立起来</b>而不是"等真要加了再说"：simos 的 GUI 配置页要把这一项渲染出来并落盘，没有字段就只能靠 "大家默契地都填
 * openai"；而<b>字段一旦进了用户的配置</b>，事后补课要迁移配置，事前立起来只是多一个常量。
 *
 * <p><b>今天不做的</b>（如实登记，不是"已支持"）：Ollama 原生 {@code /api/chat}、Anthropic Messages API 都<b>没有</b>实现，也没有
 * 各自的请求/响应结构。加一种方言 = 加一个枚举值 + {@link LlmRouteAssembler} 里的一条分支 + 一个 {@link LlmClient} 实现。
 *
 * <p><b>未知值一律响亮拒绝</b>（{@link #of}）：把拼错的方言当成默认方言，等于让用户以为"配了 Ollama 原生"，实际发的是 OpenAI 兼容请求
 * ——这类"静默走错协议"的排查成本极高。
 */
public enum LlmProtocol {

  /**
   * OpenAI 兼容：{@code POST {baseUrl}/chat/completions}，SSE 流式，{@code Authorization: Bearer}。
   *
   * <p>这是事实标准（DeepSeek、GLM、vLLM、Ollama 的 {@code /v1}、各类网关都提供），也是本项目唯一实现的方言。
   */
  OPENAI_COMPATIBLE;

  /** 配置里可接受的写法（大小写不敏感）：{@code openai} / {@code openai-compatible} / {@code openai_compatible}。 */
  public static LlmProtocol of(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("协议名不能为空（本项不猜缺省方言——缺省值由配置层填）");
    }
    String normalized = name.strip().toLowerCase(Locale.ROOT).replace('_', '-');
    if ("openai".equals(normalized) || "openai-compatible".equals(normalized)) {
      return OPENAI_COMPATIBLE;
    }
    throw new IllegalArgumentException(
        "不支持的协议/方言 \"" + name.strip() + "\"（当前只实现 " + OPENAI_COMPATIBLE + "）");
  }
}
