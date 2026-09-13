package io.mosire.brain.subagent;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.brain.runtime.AgentConfig;

/**
 * 子 Agent 实例快照：不可变数据 + status——状态变更时由 {@link SubagentManager} 整体替换记录 （record
 * 不可变、并发可见），而不是可变字段（volatile 单字段无法保证"数据+状态"的原子读）。
 *
 * @param instanceId 实例 id（事件 correlationId；模板 id + 随机后缀）
 * @param templateId 派生模板 id
 * @param goal 任务目标全文（只在实例内存里；事件 payload 只记长度）
 * @param config 收紧后的运行配置（id = instanceId）
 * @param permissions 收紧后的权限集（已过单调性守卫）
 * @param mode <b>命令档位</b>（S6）：已过派生单调性守卫（父 {@code FULL} 可给子任一档，父 {@code LIMITED} 只能给 {@code
 *     LIMITED}）。父侧为它建 MCP 链接时绑进调用身份——子体的每条 shell 命令都按这一档判
 * @param depth 层级（<b>调用者</b> depth + 1——S5-A 起锚到调用者的身份，不再是 manager 的构造期常量）
 * @param status 当前状态快照（以 Manager 内 map 中的最新记录为准）
 * @param parentInstanceId <b>谁派的它</b>（调用者实例 id；主 Agent 派的就是 {@code main}）。S5-A 起承载：单实例直系额度按它计数
 *     （{@link SubagentManager#spawn}），也是 D27 血缘判定的原料之一。空串 = 未记录（老构造路径/测试夹具）， 不计入任何调用者的额度
 * @param lineagePath <b>血缘路径</b>（D27）：{@code /} 分段的实例链，形态 {@code main/a1/a1-1}——主 Agent 的 path 是
 *     {@code main}（= {@link io.mosire.agentlib.permission.AgentIdentity#MAIN_ID}），子实例是 {@code 父path
 *     + "/" + 自己的 instanceId}。<b>只有 manager 写</b>（派发那一刻拼进实例记录，见 {@link
 *     SubagentManager#spawn}）：子体<b>不参与</b>、 也给不出（I4 不可自报），因此判定永远发生在父进程、读的是 manager
 *     里的这一份，而不是子体自报的任何东西。判定只做<b>按段前缀匹配</b> （{@code path(t).equals(path(c)) ||
 *     path(t).startsWith(path(c) + "/")}，见 {@link ContextAccessJudge}）。 空串 = 未记录（兼容构造/老记录/父身份解析不出）
 *     ⇒ 判定面 fail-closed（拒绝），<b>不拒派发</b>
 */
public record SubagentInstance(
    String instanceId,
    String templateId,
    String goal,
    AgentConfig config,
    AgentPermissionSet permissions,
    CommandMode mode,
    int depth,
    SubagentStatus status,
    String parentInstanceId,
    String lineagePath) {

  public SubagentInstance {
    mode = mode == null ? CommandMode.LIMITED : mode;
    parentInstanceId = parentInstanceId == null ? "" : parentInstanceId;
    lineagePath = lineagePath == null ? "" : lineagePath;
  }

  /** 7 参兼容构造（S6 之前）：档位取 {@link CommandMode#LIMITED}——<b>子 Agent 的缺省档</b>（用户裁决"必须是子 Agent 的默认"）。 */
  public SubagentInstance(
      String instanceId,
      String templateId,
      String goal,
      AgentConfig config,
      AgentPermissionSet permissions,
      int depth,
      SubagentStatus status) {
    this(instanceId, templateId, goal, config, permissions, CommandMode.LIMITED, depth, status, "");
  }

  /** 8 参兼容构造（S5-A 之前）：父实例未记录（空串）。 */
  public SubagentInstance(
      String instanceId,
      String templateId,
      String goal,
      AgentConfig config,
      AgentPermissionSet permissions,
      CommandMode mode,
      int depth,
      SubagentStatus status) {
    this(instanceId, templateId, goal, config, permissions, mode, depth, status, "");
  }

  /** 9 参兼容构造（D27 之前）：血缘路径未记录（空串 = 判定面 fail-closed，见类 javadoc）。 */
  public SubagentInstance(
      String instanceId,
      String templateId,
      String goal,
      AgentConfig config,
      AgentPermissionSet permissions,
      CommandMode mode,
      int depth,
      SubagentStatus status,
      String parentInstanceId) {
    this(
        instanceId,
        templateId,
        goal,
        config,
        permissions,
        mode,
        depth,
        status,
        parentInstanceId,
        "");
  }

  /** 换状态返回新实例（record 无 wither，收敛到一处避免散落的拷贝构造）。 */
  SubagentInstance withStatus(SubagentStatus newStatus) {
    return new SubagentInstance(
        instanceId,
        templateId,
        goal,
        config,
        permissions,
        mode,
        depth,
        newStatus,
        parentInstanceId,
        lineagePath);
  }
}
