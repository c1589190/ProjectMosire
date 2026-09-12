package io.mosire.agentlib.approval;

import java.util.Objects;

/**
 * 一次审批请求——<b>全部字段由宿主（{@code ToolCallAuthorizer}）填</b>：模型给不出也改不了（不可自报身份，设计 §2.3）。
 *
 * <p>{@code digest} = 命令/参数的脱敏摘要（C 包接 bash 后与命令落账同源），{@code summary} = 给人看的一行说明：
 * 两者<b>只进内存态提示面</b>（tty/HTTP 通道）。事件面只带 {@code {id, tool, classKey, digest}} （见 {@link
 * ApprovalEventTypes#REQUESTED}），<b>不带</b> {@code summary} 与 {@code args}——D23/D24：
 * 密钥与完整命令不得落到任何持久面（{@code summary} 由宿主填，宿主可能把明文塞进去，所以事件层不收它）。
 *
 * <p><b>{@code callerKey} 的存在理由</b>：会话级放行按 {@code (调用者, classKey)} 二元组归类（{@link
 * PendingApprovals#grantSession(String, String)}），而 {@link ApprovalGate#decide(ApprovalRequest)} 与
 * {@link ApprovalCoordinator#decide(ApprovalRequest)} 都只收本记录 ⇒ 调用者键必须由请求自身承载，
 * 否则快判链看不到调用者，"换调用者仍要问"无处落地。取值口径由宿主定：本仓 AgentLib 层<b>唯一</b>的调用者身份是 {@link
 * io.mosire.agentlib.permission.AccessToken}（{@code ToolContext} 不含 agent 实例 id）， 故 authorizer 填
 * {@code context.caller().name()}；要按 agent 实例细分得先给 ToolContext 加身份（S5）。
 *
 * <p><b>与派发包 §一 的偏离（本包唯一一处接口偏离）</b>：派发包冻结的 7 个组件 {@code
 * id/tool/classKey/summary/digest/createdAtEpochMs/deadlineEpochMs} <b>逐字保留在原位置</b>， {@code
 * callerKey} 追加在末尾。理由见上段（冻结面自相矛盾：既要 {@code (调用者, classKey)} 归类，
 * 又不给快判链任何调用者入口）。通道实现按访问器读字段，追加组件不影响它们。
 *
 * @param id 宿主生成的请求 id（不得含命令明文/密钥；可用 {@code ap-<n>} 或 UUID）
 * @param tool 工具名（宿主自报，不是模型给的字符串）
 * @param classKey 会话级 remember 的键（如 {@code bash:ask:systemctl}）
 * @param summary 给人看的一行说明（提示面专用，不进事件）
 * @param digest 脱敏摘要（提示面与事件都只带它，不带命令明文）
 * @param createdAtEpochMs 请求生成时刻（墙钟）
 * @param deadlineEpochMs 到点没人答即失效（与编排器超时同源，见 {@code ApprovalCoordinator#timeout()}）
 * @param callerKey 调用者身份键（会话级放行的左半键）
 */
public record ApprovalRequest(
    String id,
    String tool,
    String classKey,
    String summary,
    String digest,
    long createdAtEpochMs,
    long deadlineEpochMs,
    String callerKey) {

  public ApprovalRequest {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tool, "tool");
    Objects.requireNonNull(classKey, "classKey");
    Objects.requireNonNull(callerKey, "callerKey");
    if (classKey.isBlank() || callerKey.isBlank()) {
      // 两个键为空会让"按 (调用者, classKey) 归类"退化成事实上的全局放行，必须在构造期就响亮拒
      throw new IllegalArgumentException("classKey / callerKey 不得为空白");
    }
    summary = summary == null ? "" : summary;
    digest = digest == null ? "" : digest;
  }
}
