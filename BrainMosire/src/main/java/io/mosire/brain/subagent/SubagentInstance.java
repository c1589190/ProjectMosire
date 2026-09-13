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
    String parentInstanceId) {

  public SubagentInstance {
    mode = mode == null ? CommandMode.LIMITED : mode;
    parentInstanceId = parentInstanceId == null ? "" : parentInstanceId;
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
        parentInstanceId);
  }
}
