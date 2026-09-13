package io.mosire.brain.subagent;

import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "谁能读/动谁的上下文"的<b>唯一判定点</b>（D30 立，D27 落地）。工具层与视图层都不再做第二处判定——包括 {@code target=self} 的放行，
 * 也在这里发生。今天判的是两维：<b>能力位</b>（{@code opToolName} 是不是在调用者权限集里）+ <b>血缘</b>（目标在不在调用者的 subtree 里）。
 *
 * <p><b>判定式</b>（{@code 设计-身份与血缘.md} §3.1）：
 *
 * <pre>
 * 允许(c, t, op) ⟺ op ∈ capabilities(c) ∧ t ∈ subtree(c)          （t = self 且 c 可解析时，subtree 条件恒真）
 * subtree(c) = { t | path(t) == path(c) ∨ path(t).startsWith(path(c) + "/") }   // 含自身，按段匹配
 * </pre>
 *
 * <p><b>path 的来源</b>：{@link SubagentInstance#lineagePath()}（形态 {@code main/a1/a1-1}），由 {@link
 * SubagentManager} 在派发那一刻写入。<b>调用者的 path 由 {@code context.identity().instanceId()} 查表得来</b>（main ⇒
 * {@code main}； 否则 {@link SubagentManager#get(String)}），<b>不读 token</b>——{@code AccessToken}
 * 只有三个桶，"主 Agent 与子 Agent 同为 {@code DEFAULT}"，答不出"这是谁在问"（这正是本类 D30 时记为"判不了"的那一维）。身份由宿主绑定，模型给不出。
 *
 * <p><b>★ 安全性声明（不许含糊）</b>：本判定把"<b>结构性不可能</b>"降级成"<b>判定式拒绝</b>"——2026-09-13 用户明确裁决 "子 Agent
 * 肯定要有能力读自己创建的子 Agent"（{@code 设计-身份与血缘.md} §四）。此前 {@code read_agent_context} 靠 {@code noExport}
 * 结构性隔离（子体手里根本没有这个句柄），{@code kill}/{@code list} 靠 {@code SYSTEM} 级别 + 工具内 {@code systemOnly}
 * 自查；今天三者都靠这条判定。<b>⇒ 判别性用例 + 变异是本条唯一的保证</b>（该设计 §七）：每条 fail-closed
 * 规则都要有"变异即转红"的用例，不允许出现"判定没被触达却读成通过"的验收。如果日后要收回这条降级，退路是恢复 {@code noExport} 并把用例改写成反向判据（保留在案）。
 *
 * <p><b>两个面分开</b>（f4060c1 已落地纪律）：{@link Decision#code()} 是给我们的（日志/事件，细节可留），给<b>模型</b>的文案只说
 * "这一类被拒"——不许把别支的存在、path、id 递出去（判定细节走 {@link #LOG} 的 WARN，日志里是 path 明文）。
 */
public final class ContextAccessJudge {

  private static final Logger LOG = LoggerFactory.getLogger(ContextAccessJudge.class);

  /** {@code target} 取该值时读调用者自己的上下文（与任何 instanceId 都不冲突：实例 id 形如 {@code <模板id>-<hex>}）。 */
  public static final String SELF = "self";

  /** 越出血缘范围（含身份解析不出/目标 path 未记录）的稳定错误码。 */
  public static final String SUBTREE_DENIED = "SUBTREE_DENIED";

  /** 目标不在本 Manager 已知实例内的稳定错误码（语义与 D30 相同，零改动）。 */
  public static final String UNKNOWN_INSTANCE = "UNKNOWN_INSTANCE";

  /** 血缘类拒绝的<b>模型面</b>文案（只陈述"这一类被拒"——不含 path/id/别支存在性；细节在日志里，f4060c1 口径）。 */
  public static final String OUT_OF_SCOPE_REASON = "拒绝：目标不在你的血缘范围内（只能访问自己与自己创建的子 Agent）";

  private ContextAccessJudge() {
    throw new AssertionError("No instances");
  }

  /**
   * 判定结果：{@code allowed=false} 时 {@code code} 为稳定的错误码（工具直接拿它构造 {@code ToolResult.error}）， {@code
   * reason} 为<b>模型可读</b>的原因。
   *
   * <p>{@code reason} 会进 LLM 可见的 tool output（以及事件库＝持久面），所以它只许说"这一类被拒"： 细节（哪个 path 不在谁的 subtree
   * 里、调用者身份解析出什么）只进日志。
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
   * 判定一次上下文读取（D30 的三参签名，逐字保留）：委托给带操作名的重载，操作名取 {@code read_agent_context}——既有调用点与用例零改动。
   *
   * @param context 调用上下文（权限集 + 身份）
   * @param manager 编排根（"已知子体"与血缘的事实来源；{@code null} = 装配缺失，任何非 self 目标都拒绝）
   * @param target {@code self} 或目标实例 id
   */
  public static Decision judge(ToolContext context, SubagentManager manager, String target) {
    return judge(context, manager, target, SubagentOrchestrationTools.READ_AGENT_CONTEXT);
  }

  /**
   * 判定一次按目标的操作（读上下文 / kill / list 的逐目标判定）：能力位按 {@code opToolName} 取那一维，血缘取 subtree。
   *
   * <p>规则顺序写死如下（{@code 设计-身份与血缘.md} §3.3 规则表逐条对应；顺序即优先级，<b>不许随手调换</b>）：
   *
   * <ol>
   *   <li>{@code target} 空 ⇒ {@code INVALID_ARGUMENTS}（既有码）；
   *   <li>{@code target=self}：调用者能解析出（main 或本 manager 已知实例）⇒ 放行，否则 ⇒ {@code SUBTREE_DENIED} （设计
   *       §3.3#4：今天挡"外部面借 self 直读主 Agent 自己的事件库"的是 SYSTEM 级别 + {@code noExport}， 两者都已卸掉 ⇒
   *       <b>这条是必须补上的</b>）；
   *   <li>目标不在 manager 里 ⇒ {@link #UNKNOWN_INSTANCE}（既有码）；
   *   <li>调用者 path 空（身份解析不出：{@code external-mcp}/{@code UNKNOWN}/空串/父未落账）⇒ {@code SUBTREE_DENIED}；
   *   <li>目标 path 空（老记录/夹具）⇒ {@code SUBTREE_DENIED}；
   *   <li>能力位缺（{@code permissions.isToolAllowed(opToolName)} 为 false）⇒ {@link
   *       ToolExecutionGuard#DENIED}；
   *   <li>path 不在 subtree 内 ⇒ {@code SUBTREE_DENIED}。
   * </ol>
   *
   * <p><b>为什么先判身份再判能力位</b>：身份解析不出 = "不知道你是谁"，此时任何"你有这个能力位"的结论都建立在自报数据上； 先拒更保守。判别性用例把两种拒因分开验（同血脉 +
   * 无能力位 ⇒ {@code DENIED}；有能力位 + 不同血脉 ⇒ {@code SUBTREE_DENIED}），两条都要求调用者身份能解析——这正是"探针必须真被走到那一步"的要求。
   *
   * @param opToolName 本次操作的工具名（能力位就是它的白名单位；{@code read_agent_context}/{@code kill_sub_agent}/
   *     {@code list_sub_agents} 各占一位）
   */
  public static Decision judge(
      ToolContext context, SubagentManager manager, String target, String opToolName) {
    // ① 参数：target 必填（既有码，语义不变）
    if (target == null || target.isBlank()) {
      return Decision.deny("INVALID_ARGUMENTS", "target 为必填参数（self 或子 Agent 实例 id）");
    }
    // ② self：不是实例，跳过"目标未知"那条；先判调用者是谁（设计 §3.3#4）
    if (SELF.equals(target)) {
      if (callerLineagePath(context, manager).isEmpty()) {
        logLineageDeny(opToolName, context, manager, target, null);
        return Decision.deny(SUBTREE_DENIED, OUT_OF_SCOPE_REASON);
      }
      return Decision.allow();
    }
    // ③ 目标未知（既有码；与调用者是谁无关——"不存在"是事实，不是权限结论）
    SubagentInstance instance = manager == null ? null : manager.get(target).orElse(null);
    if (instance == null) {
      return Decision.deny(UNKNOWN_INSTANCE, "目标不是本进程已知的子 Agent（用 list_sub_agents 看自己的下级）");
    }
    // ④ 调用者身份解析不出：fail-closed（不拿 token 顶替，也不"默认放行"）
    String callerPath = callerLineagePath(context, manager);
    if (callerPath.isEmpty()) {
      logLineageDeny(opToolName, context, manager, target, instance.lineagePath());
      return Decision.deny(SUBTREE_DENIED, OUT_OF_SCOPE_REASON);
    }
    // ⑤ 目标 path 未记录（老记录/夹具）：宁可拒，也不"没有 path 就当同血脉"
    String targetPath = instance.lineagePath();
    if (targetPath.isEmpty()) {
      logLineageDeny(opToolName, context, manager, target, targetPath);
      return Decision.deny(SUBTREE_DENIED, OUT_OF_SCOPE_REASON);
    }
    // ⑥ 能力位：读/杀/列各占一位，不随"能调这个工具"自动到手（D30 §六.2：读是独立能力位）
    if (!context.permissions().isToolAllowed(opToolName)) {
      return Decision.deny(ToolExecutionGuard.DENIED, "权限集未授予 " + opToolName + "（能力位）。");
    }
    // ⑦ 按段前缀匹配（含自身）——"+ /" 不许省：startsWith(path(c)) 会让 a1 命中 a10
    if (!inSubtree(callerPath, targetPath)) {
      logLineageDeny(opToolName, context, manager, target, targetPath);
      return Decision.deny(SUBTREE_DENIED, OUT_OF_SCOPE_REASON);
    }
    return Decision.allow();
  }

  /**
   * 实例 id → 血缘路径（{@code ""} = 解析不出）的<b>唯一身份解析</b>：{@link AgentIdentity#MAIN_ID} ⇒ {@code main}；否则查
   * {@link SubagentManager#get(String)} 取该实例的 {@code lineagePath}；查不到（含 {@code external-mcp}/{@code
   * UNKNOWN}/空串，以及父本身未落账）⇒ {@code ""}。
   *
   * <p><b>为什么不给第二处身份解析</b>：{@code list_sub_agents} 的"只列自己的子树"是同一件事的<b>范围形态</b>，{@link
   * SubagentManager#lineagePathOfCaller} 是同一件事的<b>写入形态</b>（拼子体 path 时也要问"父的 path 是什么"）——三处各写一遍就会分叉
   * （同族缺陷温床），故都取这一份。身份<b>不读 token</b>：{@code AccessToken} 只有三个桶，主 Agent 与子 Agent 同为 {@code
   * DEFAULT}。
   */
  public static String pathOf(String instanceId, SubagentManager manager) {
    if (AgentIdentity.MAIN_ID.equals(instanceId)) {
      return AgentIdentity.MAIN_ID;
    }
    if (manager == null) {
      return "";
    }
    return manager.get(instanceId).map(SubagentInstance::lineagePath).orElse("");
  }

  /**
   * 调用者的血缘路径（{@code ""} = 解析不出）：身份取 {@code context.identity().instanceId()}，其余交 {@link
   * #pathOf(String, SubagentManager)}（口径与"为什么不给第二处身份解析"见那里）。
   */
  public static String callerLineagePath(ToolContext context, SubagentManager manager) {
    return pathOf(context.identity().instanceId(), manager);
  }

  /**
   * 目标是否落在调用者 subtree 内（<b>按段</b>前缀匹配，含自身）：{@code path(t) == path(c) ∨ path(t).startsWith(path(c) +
   * "/")}。
   *
   * <p><b>{@code + "/"} 不许省</b>：{@code startsWith(path(c))} 会让兄弟分支 {@code a10} 落进 {@code a1} 的
   * subtree ——血缘判定直接崩坏（设计 §七.1 的判别性反例）。任一侧为空 ⇒ false（fail-closed：没有 path 就不认亲）。
   */
  public static boolean inSubtree(String callerPath, String targetPath) {
    return !callerPath.isEmpty()
        && !targetPath.isEmpty()
        && (targetPath.equals(callerPath) || targetPath.startsWith(callerPath + "/"));
  }

  /** 血缘类拒绝的细节面（日志）：两个 path 明文，供人回答"到底谁不在谁的子树里"。**不进**模型面与事件面。 */
  private static void logLineageDeny(
      String opToolName,
      ToolContext context,
      SubagentManager manager,
      String target,
      String targetPath) {
    LOG.warn(
        "上下文访问被血缘判定拒绝: op={} caller={} callerPath={} target={} targetPath={} targetKnown={}",
        opToolName,
        context.identity().instanceId(),
        callerLineagePath(context, manager),
        target,
        targetPath == null ? "<self>" : targetPath,
        manager != null && manager.get(target).isPresent());
  }
}
