package io.mosire.brain.subagent;

import io.mosire.agentlib.permission.AgentPermissionSet;
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
 * @param depth 层级（父 depth + 1）
 * @param status 当前状态快照（以 Manager 内 map 中的最新记录为准）
 */
public record SubagentInstance(
    String instanceId,
    String templateId,
    String goal,
    AgentConfig config,
    AgentPermissionSet permissions,
    int depth,
    SubagentStatus status) {

  /** 换状态返回新实例（record 无 wither，收敛到一处避免散落的拷贝构造）。 */
  SubagentInstance withStatus(SubagentStatus newStatus) {
    return new SubagentInstance(
        instanceId, templateId, goal, config, permissions, depth, newStatus);
  }
}
