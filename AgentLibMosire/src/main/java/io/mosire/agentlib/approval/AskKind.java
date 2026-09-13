package io.mosire.agentlib.approval;

/**
 * <b>「为什么问」</b>：一次审批请求的理由分类（2026-09-13 用户裁决的落点）。
 *
 * <p>它是<b>分流</b>的依据，不是安全等级——两个取值都表示"这次调用需要批准"，区别只在<b>谁有资格批</b>：
 *
 * <ul>
 *   <li>{@link #SENSITIVE}：系统敏感区（分类器判"需审批"的那一类，如服务/包管理/写系统目录）。<b>永远到人</b>：
 *       完全权限的上级也<b>不许</b>代批——用户裁决原文"敏感区只能到人"。没有这一档，一个被派去"清理磁盘"的子 Agent 就能让上级点头执行 {@code rm -rf
 *       /etc}：审批链上全部是 Agent，人只看到"某个 Agent 批了"。
 *   <li>{@link #MODE_LIMITED}：<b>档位提升</b>（{@link io.mosire.agentlib.permission.CommandMode#LIMITED}
 *       把本该"直放" 的命令提升为审批）。<b>可以</b>交沿派生链向上第一个完全权限的 Agent 判定（{@code SuperiorJudgeGate}， 它真的跑一轮 LLM
 *       判断，不是规则代批）；链上没有完全权限的 Agent ⇒ 照旧到人。
 * </ul>
 *
 * <p><b>缺省取 {@link #SENSITIVE}</b>：这不是"敏感区自证"，而是"<b>没说明理由 ⇒ 不上代批台</b>"的保守缺省—— 新工具报 {@link
 * ToolGate.Ask} 时若还没想清自己的问询该由谁批，它落到人手里，而不是落进 Agent 的代批面。
 */
public enum AskKind {

  /** 系统敏感区：只能到人（上级 Agent 不得代批）。 */
  SENSITIVE,

  /** 档位提升（不完全权限档）：可交向上第一个完全权限的 Agent 判定。 */
  MODE_LIMITED
}
