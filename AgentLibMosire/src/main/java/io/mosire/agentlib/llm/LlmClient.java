package io.mosire.agentlib.llm;

import java.util.Optional;

/**
 * LLM 模型连接接口——agentlib 对外暴露的"模型最小契约"。
 *
 * <p>实现约定：{@link #chat} 必须是纯同步、一次请求一次返回的调用；流式（P0 之前）由其他 接口承担/暂缓。Brain 的主循环只依赖本接口（离线测试注入 {@link
 * FakeLlmClient}）。
 */
@FunctionalInterface
public interface LlmClient {

  /** 发起一次补全；实现应保证耗时且失败抛 {@link LlmException}，不吞错、不重试（策略在调用方）。 */
  LlmResponse chat(LlmRequest request) throws LlmException;

  /**
   * 本客户端实际在用的模型名（<b>观测用</b>：{@code agent.lifecycle} 事件据此记录"这个进程在跟谁说话"）。
   *
   * <p><b>实现约定：不许谎报</b>。说的必须是本客户端真会用的模型；说不出来就返回 {@code "unknown"}——"没声明模型"与"在用假模型"是两件事，
   * 混同会让"以为在用真模型 / 以为在用假模型"两个方向同时失去判据（D24 口径：宁可响亮地不确定，不可安静地说错）。
   *
   * <p><b>与 {@code llm.call} 的 {@code model} 字段口径不同，别把差异当 bug</b>（2026-09-12 使用模式实跑实测）：
   * 这里报的是<b>客户端侧的声明</b>（配置的路由模型，如 {@code deepseek-chat}），而 {@code llm.call} 的 {@code model} 取自
   * {@link LlmResponse#model()}＝<b>服务端自报</b>（同一网关可能自报成版本别名，实测为 {@code deepseek-flash}）。
   * 两者**都不说谎**，只是回答的问题不同："我们在跟谁说话" vs "对方自称是谁"。
   */
  default String model() {
    return "unknown";
  }

  /**
   * 便捷法（B1）：只在需要"一段文本"时用——发起补全并取回正文。
   *
   * <p><b>拿不到正文时抛 {@link LlmException}（{@link LlmException.Kind#NO_TEXT}），绝不返回空串或纯空白串</b>。这条是刻意的：
   * 调用方用本方法的前提就是"我要的是文本"，此时返回 {@code ""} 等于把"模型调了工具"或"模型什么都没说"这两种完全不同的情况， 与"模型答了一个空字符串"混成同一个值——正是
   * A1 那个坑（LLM 明明答了、我们读到空）的形态。宁可响亮抛错，让调用方看见真实原因。
   *
   * <p>三类拿不到正文的原因，消息里分得清：
   *
   * <ul>
   *   <li>响应只有工具调用（正常行为，但**只想要文本**的调用方要不到）——提示"想要纯文本补全就别给工具目录"。
   *   <li>响应有文本部件但**全是空白**——{@code LlmResponse.text("")} 这类手工构造可达（模板步文本为空即触发），
   *       而"答了一段空白"与"什么都没说"在调用方看来本就是同一件事。消息里只报字符数，<b>不回显内容</b>。
   *   <li>响应既无文本也无工具调用（供应商空产出）——提示这是空完成。
   * </ul>
   *
   * <p>思维链不算"文本"：它已在 {@link LlmResponse#reasoning()} 里如实保留；若本次是"正文由思维链折叠而来" （{@link
   * LlmResponse#reasoningOnly()}），本方法<b>照常返回</b>折叠后的文本（这是 A1 默认策略的效果），调用方可自行判 {@code
   * reasoningOnly()} 决定要不要采信。
   *
   * @param request 请求（消息 + 可选工具 + 采样参数）
   * @return 助手消息里的第一条文本（非空白）
   * @throws LlmException 调用失败，或响应没有文本（{@link LlmException.Kind#NO_TEXT}）
   */
  default String text(LlmRequest request) throws LlmException {
    LlmResponse response = chat(request);
    Optional<String> text = response.textPart();
    if (text.filter(part -> !part.isBlank()).isPresent()) {
      return text.get();
    }
    boolean toolOnly =
        response.assistantMessage().content().stream()
            .anyMatch(ContentPart.ToolCall.class::isInstance);
    String reason;
    if (toolOnly) {
      reason = "响应没有文本正文（只有工具调用）：需要\"一段文本\"的调用方不应给模型工具目录";
    } else if (text.isPresent()) {
      reason = "响应只有空白文本（" + text.get().length() + " 个字符，全为空白）：与\"什么都没说\"在调用方看来是同一件事";
    } else {
      reason = "响应没有文本正文，也没有工具调用（供应商空产出）";
    }
    throw new LlmException(reason, LlmException.Kind.NO_TEXT);
  }
}
