package io.mosire.brain.context;

/**
 * 上下文分层（开发计划-二期.md §四 Token Economy）：组装时各内容源落进固定顺序的层， 枚举声明顺序即 <b>prompt-cache
 * 前缀契约</b>——前缀必须逐回合字节稳定、只追加不改写， 因此层序一旦发布即冻结（新增层只能追加在末尾，不得重排）。
 */
public enum ContextLayer {
  /** 行为提示 + 工具目录 + 收尾指令（当前唯一有内容的层，恒为前缀）。 */
  SYSTEM,

  /** 规则注入（安全/风格等约束；后续波次填充）。 */
  RULES,

  /** Skill 目录（渐进披露第一档：只列名 + 短描述）。 */
  SKILL_INDEX,

  /** 已激活 Skill 正文（渐进披露第二档）。 */
  LOADED_SKILLS,

  /** 记忆检索结果（P2-4）。 */
  MEMORY,

  /** 会话历史（管线原样回灌，不经 assembler 重组）。 */
  CONVERSATION,

  /** 工具执行结果（P2-3 截断器落点）。 */
  TOOL_RESULTS,

  /** 压缩摘要（Compactor 产物；"持久层重注入 vs summary 保留"严格区分）。 */
  COMPACT_SUMMARY
}
