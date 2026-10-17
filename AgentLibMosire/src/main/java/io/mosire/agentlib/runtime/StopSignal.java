package io.mosire.agentlib.runtime;

/**
 * 宿主策略要求骨架终止时给它的信号：<b>终止原因</b> + <b>宿主自用的附注</b>。
 *
 * <p><b>为什么终止原因是库侧枚举、细节却是字符串</b>：{@link StopReason} 是骨架与宿主共享的七值词汇（结果摘要、事件、日志都按它分流，
 * 所以它必须在库里、只能有一份）；而"为什么走到这一步"的细节（配额账本的原文、时间预算的读数……）只有宿主解释得了。detail
 * 于是被定成<b>不透明载荷</b>：库不解释、不解析、不拼字符串，只负责在终止回调里把它原样交回宿主。这样宿主细节不会污染库的公开枚举， 新增一种"细节"也不必改库。
 *
 * <p><b>为什么 null 归一化成空串</b>：detail 是可选附注。把它标准化成非 null 之后，宿主在终止回调里拼事件 payload 时不必再判空——
 * 少一条空值分支，就少一种事件 shape。
 *
 * @param reason 终止原因（非 null；null ⇒ {@link IllegalArgumentException}）
 * @param detail 宿主自用附注（可为 null ⇒ 归一化为 {@code ""}；库不解释）
 */
public record StopSignal(StopReason reason, String detail) {

  public StopSignal {
    if (reason == null) {
      throw new IllegalArgumentException("reason 不能为空");
    }
    detail = detail == null ? "" : detail;
  }
}
