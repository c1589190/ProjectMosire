package io.mosire.main.gateway.debug;

/**
 * 一次会话重置的不可变回报（P3-3 / D26）：新会话 id + 提交时刻仍生效的旧会话 id。
 *
 * <p><b>为什么两个 id 都要回报</b>：重置是"换新会话 id"而不是"删掉聊过的内容"——旧会话在库里原样留档，调用方拿到 {@code previousConversationId}
 * 才能继续追溯到它（例如把旧会话读回来核对，或把它当审计锚点）。只回新 id 会让"上一段聊去了哪"变成哑谜。
 *
 * <p><b>{@code previousConversationId} 的语义边界</b>：它是<b>提交重置请求的那一刻</b>生效的会话 id——真正的切换动作排在共享串行执行器上
 * （R11，见 {@link DebugChatService#resetSession()}），可能稍后才执行。因此：连续两次重置（第一次尚未执行）时，第二次回报的 {@code
 * previousConversationId} 仍是第一次提交时的那个 id（切换是动作，不是声明）。
 *
 * @param conversationId 重置后的新会话 id（此后的对话从零开始，落在它名下）
 * @param previousConversationId 提交时刻的会话 id（旧会话，库里留档、不删）
 */
public record DebugSessionReset(String conversationId, String previousConversationId) {}
