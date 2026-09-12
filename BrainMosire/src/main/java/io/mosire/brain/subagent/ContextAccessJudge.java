package io.mosire.brain.subagent;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;

/**
 * "谁能读谁的上下文"的<b>唯一判定点</b>（D30）。工具层与视图层都不再做第二处判定——包括 {@code target=self} 的放行， 也在这里发生。
 *
 * <p><b>今天能判的只有两件事</b>（2026-09-12 查码结论，非推测）：
 *
 * <ol>
 *   <li>{@code isToolAllowed("read_agent_context")}——读是与执行并列的<b>独立能力位</b>（D30 §六.2 裁定"占一个"）；
 *   <li>目标是不是<b>本 Manager 已知的同进程子体</b>（{@link SubagentManager#get(String)}）；{@code self} 恒放行
 *       （用户明确要求"也允许看自己的"）。
 * </ol>
 *
 * <p><b>D27 落地时只改这里</b>。今天判不了 D27 的两维，原因是结构性的、不是没接线：
 *
 * <ul>
 *   <li>{@code subtree(caller)} 无输入——{@link SubagentInstance} 只有 {@code depth}、<b>没有 parentId</b>，
 *       知道层级但不知道谁生的谁，血缘算不出来；
 *   <li>真身份无输入——{@link ToolContext#caller()} 是三级 {@link AccessToken}，<b>主 Agent 与子 Agent 同为 {@code
 *       DEFAULT}</b>，调用者是谁辨不出。下面那条 {@code caller == SYSTEM} 闸能挡住 {@code AccessToken.DEFAULT} 的子体（同
 *       {@link SubagentOrchestrationTools} 既有 {@code systemOnly} 的口径）， 但它<b>区分不了同为 SYSTEM
 *       的两个身份</b>——那要等 D27 的身份模型。
 * </ul>
 *
 * <p><b>读侧隔离今天靠结构而非判定</b>：本工具带 {@code noExport} 位，不进 MCP 桥接工具表 ⇒ 子体手里没有这个句柄， "a2 读 a1-1
 * 的上下文"在结构上就不可能（D27 落地后由真判定 + 真身份升级为可判）。
 */
public final class ContextAccessJudge {

  /** {@code target} 取该值时读调用者自己的上下文（与任何 instanceId 都不冲突：实例 id 形如 {@code <模板id>-<hex>}）。 */
  public static final String SELF = "self";

  private ContextAccessJudge() {
    throw new AssertionError("No instances");
  }

  /**
   * 判定结果：{@code allowed=false} 时 {@code code} 为稳定的错误码（工具直接拿它构造 {@code ToolResult.error}）， {@code
   * reason} 为可读原因（进 LLM 可见的 tool output 与事件）。
   */
  public record Decision(boolean allowed, String code, String reason) {

    static Decision allow() {
      return new Decision(true, null, "");
    }

    static Decision deny(String code, String reason) {
      return new Decision(false, code, reason);
    }
  }

  /**
   * 判定一次上下文读取。
   *
   * @param context 调用上下文（权限集 + 身份）
   * @param manager 编排根（"已知子体"的事实来源；{@code null} = 装配缺失，任何非 self 目标都拒绝）
   * @param target {@code self} 或目标实例 id
   */
  public static Decision judge(ToolContext context, SubagentManager manager, String target) {
    // ① 能力位：读权力不随"能调工具"自动到手（模板可以只授读、不授读——D30 §六.2）
    if (!context.permissions().isToolAllowed(SubagentOrchestrationTools.READ_AGENT_CONTEXT)) {
      return Decision.deny(
          ToolExecutionGuard.DENIED,
          "权限集未授予 " + SubagentOrchestrationTools.READ_AGENT_CONTEXT + "（读是独立能力位）");
    }
    // ② 第二道身份闸（MCP 路径不经管线 guard；与既有 systemOnly 同口径）。子体身份是 DEFAULT ⇒ 即便这只工具哪天
    //    被误外发，子体拿到也只能吃 PERMISSION_DENIED。D27 落地后这里换成真身份判定。
    if (context.caller() != AccessToken.SYSTEM) {
      return Decision.deny(ToolExecutionGuard.DENIED, "身份级别不足：子 Agent 上下文仅限主 Agent（SYSTEM）读取");
    }
    // ③ 参数
    if (target == null || target.isBlank()) {
      return Decision.deny("INVALID_ARGUMENTS", "target 为必填参数（self 或子 Agent 实例 id）");
    }
    // ④ 允许读自己（用户明确要求"也允许看自己的"）
    if (SELF.equals(target)) {
      return Decision.allow();
    }
    // ⑤ 只认本进程已知子体：未知 id 一律拒绝（顺带挡掉"拿 id 当路径读任意文件"——id 不做路径拼接前的信任）
    if (manager != null && manager.get(target).isPresent()) {
      return Decision.allow();
    }
    return Decision.deny(
        "UNKNOWN_INSTANCE", "目标不是本进程已知子 Agent: " + target + "（当前仅对同进程已知子体开放；跨 Agent 血缘判定待 D27 落地）");
  }
}
