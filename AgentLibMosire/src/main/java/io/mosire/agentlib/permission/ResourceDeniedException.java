package io.mosire.agentlib.permission;

/**
 * 资源级拒绝（S5-C）：{@link ResourceAuthorizer#require} 判否时抛出，由工具调用唯一入口 {@code ToolCallAuthorizer}
 * 接住并转成错误码 {@code RESOURCE_DENIED}。
 *
 * <p><b>为什么走异常而不是返回值</b>：{@code require} 是"关键访问点"的一次断言（设计 §2.5.2），调用点在工具深处，返回码会被逐层
 * 传给调用方（忘记检查就静默继续）——异常把"忘了处理"变成"响亮的失败"，这正是 fail-closed 要的方向。
 *
 * <p><b>工具不得吞掉它</b>：本异常是"工具内访问点 → 唯一入口"的唯一回传路径。工具若为了兜底捕获 {@code RuntimeException}， 必须原样重抛（吞掉 =
 * 把拒绝降级成"读到空数据"，是 fail-open 的经典形态）。
 *
 * <p><b>消息面向模型</b>：模型要据此改路（换个够得着的资源、换个操作），故消息带命名空间、操作、调用者可达面摘要——这些都是配置面
 * 已写明的东西，不是秘密。真正的秘密（凭据、命令明文）在任何判定路径上都不进消息。
 */
public class ResourceDeniedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ResourceDeniedException(String message) {
    super(message);
  }
}
