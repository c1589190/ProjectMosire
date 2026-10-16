package io.mosire.agentlib.decision;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * <b>父侧的「该子体本轮队列」缝</b>（设计 §5sexies③④⑥，P3d 的落点）：一个父进程可以派多个子 Agent，而一个子 Agent 只有一个父 ⇒ 键取<b>子体实例
 * id</b>（{@link io.mosire.agentlib.permission.AgentIdentity#instanceId()}，链上绑死、模型给不出），
 * "这一轮该谁结算、该谁被问"由此唯一钉死。
 *
 * <p><b>为什么在 AgentLib 定义接口、实现留在宿主</b>：通用层不得 import 领域模块（AGENTS §0.1）。本接口只说清"父侧要提供哪几件事"，
 * 具体那份簿子（惰性建、收尾清、到点作废、一次性放行的账）由 Brain/Main 实现——<b>可选注入；缺省 = 今天行为逐字不变</b> （{@code
 * ToolCallAuthorizer} 与 {@code CrossProcessDecisionGateway} 的既有装配都不传它）。
 *
 * <p><b>它替谁记账</b>：真子进程里子体的工具全部是经 MCP 桥来的 {@code McpToolAdapter}（不覆写 {@code gate()} ⇒ 恒 {@code
 * ALLOW}），MCP 的 {@code tools/list} 也不带闸位 ⇒ 子体侧看不见 {@code Ask}、本地队列恒空。而<b>父侧本来就是子体每次调用的必经点</b>
 * （它为每个子体起了专属的 {@code AgentToMcpServer} 焊在子体的 stdin/stdout 上）⇒ "要不要裁决"的知识在父侧，于是由父侧记这一笔： 子体侧只认
 * {@code DECISION_QUEUED} 这个<b>具名码</b>（不许匹配文案）。
 *
 * <p><b>它<b>不是</b>第二条决策路径</b>（不变量 1）：这份簿子不进 {@code pending()}、不落父侧历史、不发 {@code decision.package.*}
 * 事件；正式记录（包 + 裁决 + 事件）只在提出者（子体）自己的包表与 {@code events.db} 里。父侧这份<b>建即裁、裁完即弃</b>， 只为"不丢条目 + 能对账 +
 * 一次性放行"三件事存在。
 *
 * <p><b>实现方必须保证的语义</b>：
 *
 * <ol>
 *   <li>{@link #defer} 只在"来者确实是<b>本进程派出去的</b>子体"时返回 {@code true}——判不出来就返回 {@code false}
 *       （调用方退回既有审批闸，fail-closed 朝向"问人"，绝不静默丢掉一条待裁决调用）；
 *   <li>{@link #pendingEntries} 的条目顺序 = 登记顺序，且每条都带<b>父侧自己收到的明文 args</b>（裁决者照旧看全文，设计 §5sexies⑤）；
 *   <li>{@link #release} 的放行<b>恰好一次</b>：重复使用必须被拒（{@link Release#allowed()} 为假 + 具名码）；
 *   <li>三条清空路径：该子体收尾结算完成（{@link #settled}）/ 子体进程结束或被回收（{@link #recycle}）/ 到点作废 （实现内部的 TTL，理由如实记）。
 * </ol>
 *
 * <p><b>线程</b>：{@link #defer}/{@link #release} 与结算口可能落在不同线程上（每个子体一条 MCP 链的传输线程 + 管线线程），
 * 实现必须自己保证可见性与互斥。
 */
public interface SubagentDecisionLedger {

  /**
   * ★ 第三条路：把这次 {@code Ask} 记进<b>该子体本轮队列</b>（不投审批面、不执行、不阻塞）。
   *
   * @param tool 真工具（父侧注册表里那个；工具名与闸位都取自它，子体给不出）
   * @param context 这次调用的上下文（身份取 {@link ToolContext#identity()} = 链上绑定的子体身份）
   * @param ask 工具自报的闸位（{@code classKey}/{@code summary}/{@code kind} 的权威来源）
   * @return {@code true} = 记下了（调用方回 {@code DECISION_QUEUED}）；{@code false} = 本缝不为这次调用记账 （来者不是子体 /
   *     簿子不可用）⇒ 调用方走既有那两条路（逐字不变）
   */
  boolean defer(AgentTool tool, ToolContext context, ToolGate.Ask ask);

  /**
   * 一次性放行的<b>探测</b>（只读，不改状态）：认这次调用携带的 {@link DecisionReleaseMarker}。
   *
   * <p>三种结果：① 空值 = 这次调用没带标记（照常判定）；② {@link Release#allowed()} 为真 = 标记成立（调用方据此剥掉标记、 记 {@link
   * #consumeRelease} 后执行）；③ {@link Release#allowed()} 为假 = 标记在场但不成立（伪造 / 已被用过 / 端口对不上） ⇒
   * 调用方必须<b>具名拒</b>，绝不落到审批闸（否则"伪造一个标记"就成了一次免审批的执行尝试）。
   *
   * <p><b>为什么探测与消费分开</b>：探测发生在闸位判定之前（要在剥掉标记之后求闸位），而"标记已用"必须发生在<b>真的要去执行</b>那一刻
   * ——中间任何一条提前返回（硬拒、资源拒）都不该烧掉一次放行。
   */
  Optional<Release> release(ToolContext context);

  /** 把一条成立的放行记为"已用"（幂等；重复调用无事）。 */
  void consumeRelease(Release release);

  /**
   * 父侧结算口的读口：该实例本轮已记下的条目（顺序 = 登记顺序，带父侧自己的明文 args）。
   *
   * <p>没有队列 / 已到点作废 ⇒ <b>空表</b>（调用方按"对不上账"办，fail-closed）。
   */
  List<PendingDecisionQueue.PendingEntry> pendingEntries(String instanceId);

  /**
   * 结算完成：给<b>被批条目</b>记一次性放行（键 = 实例 + 包 id + 条目序号），并清掉该实例本轮队列。
   *
   * @param instanceId 子体实例 id（= 提出者键）
   * @param packageId 这一次的包 id（子体自报的 {@code requestId}；一次性放行的键的中间一段）
   * @param approvedIndexes 终局被批的条目（其余不记放行 ⇒ 重调必然具名拒）
   */
  void settled(String instanceId, String packageId, Set<Integer> approvedIndexes);

  /** 本次提交未被受理（具名拒 / 形状错 / 提出者认不出）：清掉该实例本轮队列，<b>不</b>记放行。 */
  void discard(String instanceId);

  /** 该子体进程结束 / 被回收：清掉它的本轮队列与<b>尚未使用</b>的放行（设计 §5sexies③②）。 */
  void recycle(String instanceId);

  /**
   * 一次放行判定的结果。
   *
   * @param allowed 标记成立 ⇒ 真（调用方剥标记、消费、执行）；标记在场但不成立 ⇒ 假（调用方具名拒）
   * @param arguments {@code allowed} 时 = <b>剥掉标记后</b>的参数（就是要交给工具的那一份）；否则 = 原参数
   * @param code 具名拒码（{@code allowed} 时为空串）
   * @param message 具名拒的一行人读说明（不含参数明文；{@code allowed} 时为空串）
   * @param instanceId 提出者实例 id（放行的键）
   * @param packageId 包 id（放行的键）
   * @param itemIndex 条目序号（放行的键）
   */
  record Release(
      boolean allowed,
      Map<String, Object> arguments,
      String code,
      String message,
      String instanceId,
      String packageId,
      int itemIndex) {

    public Release {
      arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
      code = code == null ? "" : code;
      message = message == null ? "" : message;
      Objects.requireNonNull(instanceId, "instanceId");
      packageId = packageId == null ? "" : packageId;
    }

    /** 成立：{@code arguments} 必须是<b>剥掉标记后</b>的那一份。 */
    public static Release allowed(
        Map<String, Object> arguments, String instanceId, String packageId, int itemIndex) {
      return new Release(true, arguments, "", "", instanceId, packageId, itemIndex);
    }

    /** 不成立：{@code code} 是给子体看的具名码（不许为空）。 */
    public static Release denied(
        String code, String message, String instanceId, String packageId, int itemIndex) {
      if (code == null || code.isBlank()) {
        throw new IllegalArgumentException("不成立的放行必须带具名码");
      }
      return new Release(false, Map.of(), code, message, instanceId, packageId, itemIndex);
    }
  }
}
