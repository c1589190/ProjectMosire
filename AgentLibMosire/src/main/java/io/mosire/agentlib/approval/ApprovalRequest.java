package io.mosire.agentlib.approval;

import io.mosire.agentlib.permission.AgentIdentity;
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
 * 否则快判链看不到调用者，"换调用者仍要问"无处落地。取值口径由宿主定：{@code callerKey} 是等级<b>桶</b>（{@link
 * io.mosire.agentlib.permission.AccessToken#name()}，三个取值），故 authorizer 填 {@code
 * context.caller().name()}——它<b>不是</b>实例身份，实例级身份见 {@link #requesterId()}。
 *
 * <p><b>与派发包 §一 的偏离（本包唯一一处接口偏离）</b>：派发包冻结的 7 个组件 {@code
 * id/tool/classKey/summary/digest/createdAtEpochMs/deadlineEpochMs} <b>逐字保留在原位置</b>， {@code
 * callerKey} 追加在末尾。理由见上段（冻结面自相矛盾：既要 {@code (调用者, classKey)} 归类，
 * 又不给快判链任何调用者入口）。通道实现按访问器读字段，追加组件不影响它们。
 *
 * <p><b>S6 追加的三个组件</b>（{@code requesterId/kind/goal}，同样只追加在末尾）：上级 Agent 代批这条路上，
 * "谁在问"（实例）、"为什么问"（{@link AskKind}，决定能不能代批）、"它被派去干什么"（{@code goal}）三件事必须 由请求自身承载——快判链只收本记录，看不到
 * {@code ToolContext}。三者与 {@code summary} 同档：<b>只进内存态</b>， 不进事件面（{@link
 * ApprovalEventTypes#REQUESTED} 的载荷逐字不变）。
 *
 * @param id 宿主生成的请求 id（不得含命令明文/密钥；可用 {@code ap-<n>} 或 UUID）
 * @param tool 工具名（宿主自报，不是模型给的字符串）
 * @param classKey 会话级 remember 的键（如 {@code bash:ask:systemctl}）
 * @param summary 给人看的一行说明（提示面专用，不进事件）
 * @param digest 脱敏摘要（提示面与事件都只带它，不带命令明文）
 * @param createdAtEpochMs 请求生成时刻（墙钟）
 * @param deadlineEpochMs 到点没人答即失效（与编排器超时同源，见 {@code ApprovalCoordinator#timeout()}）
 * @param callerKey 调用者身份键（会话级放行的左半键）
 * @param requesterId 请求者的<b>实例级</b>身份（{@link
 *     io.mosire.agentlib.permission.AgentIdentity#instanceId()}：主 Agent {@code main}、子 Agent {@code
 *     <模板id>-<hex>}、外部面 {@code external-mcp}）。{@code callerKey} 是等级<b>桶</b>（三个取值）， 答不出"这是哪个子 Agent
 *     在问"——上级判定要能把"谁在问、被派去干什么"讲清楚，故另立此字段（S6）
 * @param kind 为什么问（{@link AskKind}）：<b>分流</b>依据——敏感区只能到人，档位提升可交上级 Agent 判定
 * @param goal 请求者被派去干的事（子 Agent = 父给它的任务；主 Agent/外部面 = 空串）。<b>只进内存态判定提示</b> （上级判定要回答"这条命令与任务相符吗"，见
 *     {@code SuperiorJudgeGate}），不进事件库
 */
public record ApprovalRequest(
    String id,
    String tool,
    String classKey,
    String summary,
    String digest,
    long createdAtEpochMs,
    long deadlineEpochMs,
    String callerKey,
    String requesterId,
    AskKind kind,
    String goal) {

  /** 8 参兼容构造：请求者身份取"未知"、理由取 {@link AskKind#SENSITIVE}（= 本功能引入前的行为：任何 {@code Ask} 都落到人）。 */
  public ApprovalRequest(
      String id,
      String tool,
      String classKey,
      String summary,
      String digest,
      long createdAtEpochMs,
      long deadlineEpochMs,
      String callerKey) {
    this(
        id,
        tool,
        classKey,
        summary,
        digest,
        createdAtEpochMs,
        deadlineEpochMs,
        callerKey,
        AgentIdentity.UNKNOWN.instanceId(),
        AskKind.SENSITIVE,
        "");
  }

  public ApprovalRequest {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tool, "tool");
    Objects.requireNonNull(classKey, "classKey");
    Objects.requireNonNull(callerKey, "callerKey");
    if (classKey.isBlank() || callerKey.isBlank()) {
      // 两个键为空会让"按 (调用者, classKey) 归类"退化成事实上的全局放行，必须在构造期就响亮拒
      throw new IllegalArgumentException("classKey / callerKey 不得为空白");
    }
    requesterId = requesterId == null ? AgentIdentity.UNKNOWN.instanceId() : requesterId;
    if (requesterId.isBlank()) {
      throw new IllegalArgumentException("requesterId 不得为空白（它答「是哪个实例在问」）");
    }
    kind = kind == null ? AskKind.SENSITIVE : kind;
    goal = goal == null ? "" : goal;
    summary = summary == null ? "" : summary;
    digest = digest == null ? "" : digest;
  }
}
