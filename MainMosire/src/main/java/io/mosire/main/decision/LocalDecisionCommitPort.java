package io.mosire.main.decision;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.DecisionCommitPort;
import io.mosire.agentlib.decision.ResolvedCall;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>本机形态的裁决包执行者</b>（{@link DecisionCommitPort} 的单机实现；用户裁定 U10，设计 §5.3.1）。
 *
 * <p><b>它执行的是什么</b>：一次统一裁决之后，{@code DecisionPackages} 把<b>被批子集</b>还原成 {@link ResolvedCall}
 * 交给本类——本类逐条调<b>各条目自己的工具</b>（既有工具面），把每条的执行结果收回成一份 {@link CommitReceipt}。这就是 U10
 * 那句话的落点：「回应决策包的是一个统一的裁决包，裁决包里放的就是决策包里所有 tool 的执行结果，具体的执行结果交给裁决者 + 具体的 tool 负责」。
 *
 * <p><b>★ 执行期的判据链（P2b 补齐，设计 §5ter「裁定 13 补」）</b>——顺序照 {@code ToolCallAuthorizer.execute} 的前三段：
 *
 * <ol>
 *   <li>{@link ToolExecutionGuard#denial(ToolRegistry, String, ToolContext)}：身份/白名单/角色/敏感/破坏 ⇒ 拒（该条
 *       {@code NOT_COMMITTED}，理由是那个拒码全文）；
 *   <li>{@link AgentTool#gate(ToolContext)} == {@link ToolGate.Block} ⇒ 硬拒（{@code
 *       COMMAND_BLOCKED}）。硬拒按 B-5 <b>本不该进包</b>（命中硬拒的调用既不进队列也不进包）——遇到它说明包内数据是异常来源，这里照拒并如实记因；
 *   <li>{@link ResourceAuthorizer#of(AgentPermissionSet,
 *       io.mosire.agentlib.permission.ResourceManifest)} 的 {@code denial()}：够不着工具声明的资源面 ⇒ {@code
 *       RESOURCE_DENIED}；
 *   <li>过了 ⇒ 用 {@code context.withResources(resources)} 执行工具本体（工具内的 {@code require} 因此有判定者）。
 * </ol>
 *
 * <p><b>为什么必须补资源面</b>（P2a 发现 A）：技能面/文档面<b>只</b>用资源 SPI 在工具本体内断言，没有声明式命名空间 ⇒ 跳过资源注入 =
 * 那些工具在提交口<b>失去全部资源限制</b>（比"多拒一条"危险得多）。{@link ToolExecutionGuard} 是工具名级判定的老入口， <b>不含</b>资源面（见
 * {@code ToolCallAuthorizer} 的类注释：「资源维度只在本类上」），所以这一段必须在这里显式补上。
 *
 * <p><b>少了的那一步只有"要不要问人"</b>：裁决本身就是这一次的授权——被批条目执行时若再过一遍审批闸，等于对已经批过的东西再问一次人
 * （包内条目还可能二次触发审批：既多余，又会让"一次裁决 = 一次提交"退化成"每条各自等人"）。P2a 实测过反面：把提交口接到带 coordinator 的 authorizer
 * 上，已经批过的 {@code Ask} 条目会落进它的 fail-closed 分支拿 {@code APPROVAL_DENIED}。
 *
 * <p><b>★ 工具配置必须注入（P2b 补齐，P2a 发现 C）</b>：{@link ToolContext#config()} 早先在这里是空表，而管线那条路交的是装配期 {@code
 * toolConfig}——靠配置下发的工具（读子 Agent 库、自身事件库、将来的 {@code sandbox.maskedPaths} 技能遮蔽面）在这条路上会按"未接线"办，
 * 于是提交口就成了<b>技能遮蔽面 / 子库定位的绕过点</b>（AGENTS §0.3 登记的那类缺口）。现在装配层把<b>同一份</b> {@code toolConfig}
 * 注入本类，两条路的 {@code ToolContext.config} 逐字一致。
 *
 * <p><b>★ 执行身份 = 终局裁决者（P2b，设计 §5ter 裁定 11）</b>：构造参数从"一个 Supplier"改成"按<b>包 id</b> 取身份的函数"——
 * 装配层（{@code App}）按该包的<b>终局裁决者</b>（逐级预裁定的最后拍板那一级 / 人）现取那一级的身份与权限，而不是写死主 Agent。 本机形态今天链上只有主 Agent
 * 一级，所以取值看起来没变；但"这一包以谁的身份执行"从此是<b>每次现问</b>的，写死的那条路在类型上就不存在了。 取不到/不认识那个裁决者时装配层会给一个 fail-closed
 * 的身份（无白名单）⇒ 逐条 {@code PERMISSION_DENIED}，绝不借用主 Agent 的权限兜底。
 *
 * <p>★ <b>原子性的诚实边界（不许说成已解决）</b>：被批子集"全落或全不落"在<b>世界形态</b>里由世界的 revision 保证（设计 §2.4）；本机形态没有版本语义 ⇒
 * <b>只能逐条落</b>，中途失败就存在部分落盘。本类的 {@link CommitReceipt} 因此把每条的 真实去向逐条报出来（{@code COMMITTED} / {@code
 * NOT_COMMITTED}），并把失败原因写进逐条 {@code reason}——<b>不</b>假装整批原子。
 *
 * <p>★ <b>{@code revisionRef} 给什么</b>：契约要求 {@code COMMITTED} 必须带非空引用（设计 §5.11 第 7 条）。本机没有世界版本，
 * 于是给<b>本次执行的批次引用</b>（{@code batch:<packageId>}）——不透明、可对账、且<b>不编造</b>一个像版本号的字符串。
 */
public final class LocalDecisionCommitPort implements DecisionCommitPort {

  private static final Logger LOG = LoggerFactory.getLogger(LocalDecisionCommitPort.class);

  private final ToolRegistry registry;
  private final ToolExecutionGuard guard;

  /** 装配期工具配置（与管线用的<b>同一份</b>；只读快照，见类 javadoc 的"工具配置必须注入"）。 */
  private final Map<String, Object> toolConfig;

  /**
   * 按<b>包 id</b>取执行身份（终局裁决者的那一级，裁定 11）。函数而不是常量：包的裁决者是<b>每次现问</b>的 ——写死一个身份就等于"永远以主 Agent
   * 的权限执行别人的裁决"，那正是这条裁定要消灭的形状。
   */
  private final Function<String, ExecutingIdentity> executingIdentity;

  /**
   * @param registry 工具注册表（执行被批子集用的那一张，与管线/MCP 面同一张）
   * @param guard 纯判定链（判定那一段；<b>不含</b>审批闸，见类 javadoc）——由装配层注入，不在本类里 {@code new}：
   *     "执行时到底过哪条链"是本类的一处真实依赖，藏进类里就看不见了
   * @param toolConfig 工具自身配置（与管线同一个装配来源；{@code null} 视作空表——那只是"这个装配没配工具"，不是"随便用"）
   * @param executingIdentity 包 id ⇒ 执行身份（终局裁决者那一级；每次提交现问，见类 javadoc）
   */
  public LocalDecisionCommitPort(
      ToolRegistry registry,
      ToolExecutionGuard guard,
      Map<String, Object> toolConfig,
      Function<String, ExecutingIdentity> executingIdentity) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.guard = Objects.requireNonNull(guard, "guard");
    this.toolConfig = toolConfig == null ? Map.of() : Map.copyOf(toolConfig);
    this.executingIdentity = Objects.requireNonNull(executingIdentity, "executingIdentity");
  }

  /**
   * 执行者身份三元组：调用者令牌 + 权限集 + 身份（{@code AgentIdentity}）。
   *
   * <p>单独包一个 record 而不是散三个字段：这三样必须<b>同源</b>（身份由装配层绑定，工具面里"我是谁"只有这一处真相）。
   *
   * <p>★ 这里装的是<b>终局裁决者那一级</b>的身份（裁定 11，用户原话「很显然理论上是批准的人动手」）：父 Agent 批的包以父那一级的权限执行、 人批的包以提出者/主 Agent
   * 那一级执行。今天（主进程内）链上只有主 Agent 一级，所以取值看起来与写死等价；区别在于取值是 <b>每次现问</b>的，装配层可以按当次裁决者换人。
   */
  public record ExecutingIdentity(
      AccessToken caller, AgentPermissionSet permissions, AgentIdentity identity) {

    public ExecutingIdentity {
      Objects.requireNonNull(caller, "caller");
      Objects.requireNonNull(permissions, "permissions");
      Objects.requireNonNull(identity, "identity");
    }
  }

  @Override
  public CommitReceipt commit(String packageId, List<ResolvedCall> calls) {
    Objects.requireNonNull(packageId, "packageId");
    Objects.requireNonNull(calls, "calls");
    ExecutingIdentity who = Objects.requireNonNull(executingIdentity.apply(packageId), "执行身份");
    List<CommitReceipt.ItemOutcome> outcomes = new ArrayList<>(calls.size());
    List<java.util.Optional<String>> itemReasons = new ArrayList<>(calls.size());
    List<String> failures = new ArrayList<>();
    int executed = 0;
    for (ResolvedCall call : calls) {
      AgentTool tool = registry.find(call.toolName()).orElse(null);
      if (tool == null) {
        // 提议面与执行面之间工具被下架（插件装卸 / MCP 桥断开）⇒ 该条没执行；整批不因此中止（其余条目照常执行）
        notCommitted(outcomes, itemReasons, failures, call, "工具已不在目录里");
        continue;
      }
      ToolContext context =
          new ToolContext(who.caller(), who.permissions(), toolConfig, call.args(), who.identity());
      // 资源判定者与它注入后的上下文：闸位与工具体看到的是<b>同一个</b>（与 ToolCallAuthorizer 同形——
      // 工具若在 gate() 里读 context.resources()，拿到的就不是缺省的 denying()）
      ResourceAuthorizer resources = ResourceAuthorizer.of(who.permissions(), tool.resources());
      ToolContext scoped = context.withResources(resources);
      String failure = judgementFailure(tool, scoped, resources);
      if (failure != null) {
        // 判据链的前三段（裁定 13 补）：任何一段判否 ⇒ 这一条不执行，其余条目照常
        notCommitted(outcomes, itemReasons, failures, call, failure);
        continue;
      }
      ToolResult result;
      try {
        result = tool.execute(scoped);
      } catch (ResourceDeniedException resourceDenied) {
        // 工具内命令式断言判否（require）：与唯一入口同口径转成资源拒绝的逐条理由
        notCommitted(
            outcomes,
            itemReasons,
            failures,
            call,
            "RESOURCE_DENIED " + resourceDenied.getMessage());
        continue;
      } catch (RuntimeException failure2) {
        // 工具抛异常：算这一条没落定，但不能让异常穿出去把整包的回执丢掉（其余条目已经执行了，必须对账）
        LOG.warn(
            "裁决包执行：工具抛异常（该条记未落定，其余条目照常） packageId={} tool={} index={}",
            packageId,
            call.toolName(),
            call.index(),
            failure2);
        notCommitted(
            outcomes, itemReasons, failures, call, "执行抛异常 " + failure2.getClass().getSimpleName());
        continue;
      }
      if (result.success()) {
        outcomes.add(CommitReceipt.ItemOutcome.COMMITTED);
        // 成功条目不编理由：reason=null = 未说明理由（V14 的可空语义）
        itemReasons.add(java.util.Optional.empty());
        executed++;
      } else {
        notCommitted(outcomes, itemReasons, failures, call, result.code() + " " + result.message());
      }
    }
    String reference = "batch:" + packageId;
    if (executed == 0) {
      // 被批子集一条都没执行成功 = 这次裁决没有产生任何改动 ⇒ 零落盘（B-2 的同一侧）
      return new CommitReceipt(
          CommitReceipt.Status.REJECTED,
          "",
          List.copyOf(outcomes),
          reasons(itemReasons),
          String.join("；", failures));
    }
    if (!failures.isEmpty()) {
      // 部分成功：不假装原子（见类 javadoc），把失败逐条写进逐条理由 + 整批 reason 让确认者看得见
      LOG.warn(
          "裁决包执行：部分条目未落定（本机形态无原子性保证） packageId={} 已执行={}/{} 原因={}",
          packageId,
          executed,
          calls.size(),
          failures);
    }
    return new CommitReceipt(
        CommitReceipt.Status.COMMITTED,
        reference,
        List.copyOf(outcomes),
        reasons(itemReasons),
        String.join("；", failures));
  }

  /**
   * 判据链的前三段（不含"要不要问人"那一步，见类 javadoc）：判否返回<b>拒因文字</b>，全过返回 {@code null}。
   *
   * <p>顺序是刻意的，与 {@code ToolCallAuthorizer.execute} 的前三段逐字对齐：身份/白名单/角色/敏感/破坏 ⇒ 硬拒 ⇒ 资源面。
   * 硬拒排在资源面前面：它连问都不该问（"审批把硬拒洗白"的反面——资源判定也不该替一条命中硬拒的调用兜底）。
   *
   * @param scoped 已注入资源判定者的上下文（闸位与工具体共用<b>同一份</b>，见调用点）
   * @param resources 与 {@code scoped} 同源的判定者（声明式前置闸问它）
   */
  private String judgementFailure(
      AgentTool tool, ToolContext scoped, ResourceAuthorizer resources) {
    Optional<ToolResult> denied = guard.denial(tool, scoped);
    if (denied.isPresent()) {
      return denied.get().code() + " " + denied.get().message();
    }
    ToolGate gate;
    try {
      gate = tool.gate(scoped);
    } catch (ResourceDeniedException resourceDenied) {
      // 工具在闸位里就调了 require（少见但合法）：按资源拒办——不让异常穿透把整批变成 COMMIT_FAILED
      return "RESOURCE_DENIED " + resourceDenied.getMessage();
    }
    if (gate == null) {
      // 契约是"缺省 ALLOW"；返回 null = 工具实现坏了 ⇒ fail-closed 硬拒（与 authorizer 同口径）
      return "COMMAND_BLOCKED 工具的闸位自报为空（ToolGate 不得为 null），按 fail-closed 硬拒";
    }
    if (gate instanceof ToolGate.Block blocked) {
      LOG.warn("裁决包执行：条目命中硬拒（硬拒本不该进包，包内数据异常） tool={} class={}", tool.name(), blocked.classKey());
      return "COMMAND_BLOCKED 命令被硬拒（不可审批）: class=" + blocked.classKey();
    }
    Optional<String> resourceDenial = resources.denial();
    if (resourceDenial.isPresent()) {
      LOG.warn(
          "裁决包执行：条目被资源前置闸拒（够不着工具声明的资源面） tool={} 工具声明={} 调用者可达={}",
          tool.name(),
          tool.resources().summary(),
          scoped.permissions().resourceScopes().summary());
      return "RESOURCE_DENIED " + resourceDenial.get();
    }
    return null;
  }

  /** 逐条理由的不可变快照："有没有理由"由 {@code Optional} 表达 ⇒ 可以安全 {@code List.copyOf}。 */
  private static List<java.util.Optional<String>> reasons(
      List<java.util.Optional<String>> itemReasons) {
    return List.copyOf(itemReasons);
  }

  /** 记一条"没落定"：去向 + 理由（逐条）+ 整批失败清单（对账用）。 */
  private static void notCommitted(
      List<CommitReceipt.ItemOutcome> outcomes,
      List<java.util.Optional<String>> itemReasons,
      List<String> failures,
      ResolvedCall call,
      String reason) {
    outcomes.add(CommitReceipt.ItemOutcome.NOT_COMMITTED);
    itemReasons.add(java.util.Optional.ofNullable(reason));
    failures.add("条目 " + call.index() + " " + call.toolName() + "：" + reason);
  }
}
