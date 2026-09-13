package io.mosire.agentlib.tool;

import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourceDeniedException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>工具调用的唯一入口</b>：权限判定 → 命令闸（硬拒/需审批）→ 执行。
 *
 * <p><b>为什么需要它</b>（2026-09-12 S4 调查实测）：本仓有<b>两个</b>工具调用入口——Brain 管线（{@code
 * AgentPipeline.executeToolCall}）与 MCP 桥（{@code AgentToMcpServer.handleCall}）。前者经 {@link
 * ToolExecutionGuard} 判定，后者<b>直接 {@code tool.execute(context)}、连 {@link ToolExecutionGuard}
 * 都不经</b>（这正是各系统级工具不得不在工具体里 自造 "第二道闸" 的原因）。两条路各判各的，语义必然分叉：任何新判定（审批、资源作用域、未来的额度）只落一条路，另一条就没有它。
 * 本类把"解析工具 → 判定 → 执行"收成一条，两个入口都只调这里。
 *
 * <p><b>五段顺序不可换</b>：
 *
 * <ol>
 *   <li><b>工具不存在</b> ⇒ {@code TOOL_NOT_FOUND}——终局；
 *   <li><b>硬拒</b>（{@link ToolExecutionGuard} 权限判定）⇒ {@code PERMISSION_DENIED}——终局，<b>不进审批</b>。
 *       否则"审批放行"会把一个本就无权执行的调用洗白；
 *   <li><b>资源判定</b>（S5-C，{@link ResourceAuthorizer}）：①声明式前置闸——工具声明的命名空间调用者一个都够不着 ⇒ {@link
 *       #RESOURCE_DENIED}（终局，<b>不进审批</b>：人解决不了"够不着"）；②够得着则用"调用者权限集 × 工具声明的资源面"建判定者注入 {@link
 *       ToolContext#resources()}，工具自己那一次次的 {@code require} 便有了判定者；
 *   <li><b>命令闸</b>（工具自报 {@link ToolGate}）：{@link ToolGate.Block} ⇒ {@link #COMMAND_BLOCKED}
 *       （同样<b>不进审批</b>：硬拒不可审批，防"审批把硬拒洗白"）；{@link ToolGate.Ask} ⇒ 问人（{@link
 *       ApprovalCoordinator}），未放行 ⇒ {@link #APPROVAL_DENIED}；
 *   <li><b>执行</b>（工具内 {@code require} 判否抛的 {@link ResourceDeniedException} 在这里接住 ⇒ {@link
 *       #RESOURCE_DENIED}）。
 * </ol>
 *
 * <p><b>资源维度只在本类上</b>：{@link ToolExecutionGuard#execute} 是工具名级判定的老入口，不含资源面——给它补上等于造出第二个判定点
 * （正是本类要消灭的东西）。要判资源就走这里。
 *
 * <p><b>不装配编排器时 {@code Ask} 一律拒（fail-closed）</b>：{@link #of(ToolExecutionGuard)} / {@link
 * #standard()} 不带编排器，此时"需审批"的调用<b>不可能</b>静默放行——它直接得到 {@link #APPROVAL_DENIED}。
 * 这条与"审批面的任何故障都归拒"同源：本包在任何一条路上都不默认同意。
 *
 * <p><b>不持有的东西</b>：不写事件（审批事件由 {@link ApprovalCoordinator} 发，权限拒绝的审计仍归调用方，与 guard 同口径）；不持有存储。
 */
public final class ToolCallAuthorizer {

  private static final Logger LOG = LoggerFactory.getLogger(ToolCallAuthorizer.class);

  /** 工具自报硬拒（{@link ToolGate.Block}）：终局，不进审批。 */
  public static final String COMMAND_BLOCKED = "COMMAND_BLOCKED";

  /** 审批未放行：拒 / 超时 / 无可用通道 / 通道故障 / 未装配编排器，一律 fail-closed 归此码。 */
  public static final String APPROVAL_DENIED = "APPROVAL_DENIED";

  /**
   * 资源级拒绝（S5-C）：声明式前置闸判否，或工具内 {@code require} 抛 {@link ResourceDeniedException} 被本类接住。
   *
   * <p><b>与 {@link ToolExecutionGuard#DENIED} 分码</b>（同 S5-B 的 {@code DIR_NOT_ALLOWED} 理由）：模型看到
   * {@code PERMISSION_DENIED} 会以为"我没资格用这个工具"（改换工具/收手），看到 {@code RESOURCE_DENIED} 才知道"工具没问题，是我要碰的这块
   * 资源够不着"——后者它可以改路（换个够得着的资源、换个操作），前者改不了。
   */
  public static final String RESOURCE_DENIED = "RESOURCE_DENIED";

  private final ToolExecutionGuard guard;
  private final ApprovalCoordinator coordinator;
  private final Function<ToolContext, String> digest;

  public ToolCallAuthorizer(ToolExecutionGuard guard) {
    this(guard, null, ToolCallAuthorizer::defaultDigest);
  }

  private ToolCallAuthorizer(
      ToolExecutionGuard guard,
      ApprovalCoordinator coordinator,
      Function<ToolContext, String> digest) {
    this.guard = Objects.requireNonNull(guard, "guard");
    this.coordinator = coordinator;
    this.digest = Objects.requireNonNull(digest, "digest");
  }

  /** 以给定 guard 装配（等价于构造器；供调用点读起来像配置而非 new）。无审批编排器 ⇒ {@code Ask} 拒。 */
  public static ToolCallAuthorizer of(ToolExecutionGuard guard) {
    return new ToolCallAuthorizer(guard);
  }

  /**
   * 以给定 guard + 审批编排器装配。{@code coordinator} 为 {@code null} 时合法（= 上面那个工厂）： 此时 {@link ToolGate.Ask}
   * 一律拒（fail-closed），{@link ToolGate.Allow} 行为逐字不变。
   */
  public static ToolCallAuthorizer of(ToolExecutionGuard guard, ApprovalCoordinator coordinator) {
    return new ToolCallAuthorizer(guard, coordinator, ToolCallAuthorizer::defaultDigest);
  }

  /**
   * 同上，但指定脱敏摘要实现（C 包接 bash 后换成真正的命令脱敏：这是"摘要逻辑只有一处"的注入点， {@link #defaultDigest(ToolContext)}
   * 是缺省件）。摘要进 {@code approval.requested} 事件与提示面， 故实现<b>不得</b>回传命令明文。
   */
  public static ToolCallAuthorizer of(
      ToolExecutionGuard guard,
      ApprovalCoordinator coordinator,
      Function<ToolContext, String> digest) {
    return new ToolCallAuthorizer(guard, coordinator, digest);
  }

  /** 默认装配（默认三要素判定规则）。 */
  public static ToolCallAuthorizer standard() {
    return new ToolCallAuthorizer(new ToolExecutionGuard());
  }

  /**
   * 执行一次工具调用。
   *
   * @param registry 工具注册表
   * @param toolName 工具名（模型给出；不受信）
   * @param context 调用上下文（调用者身份 + 权限集 + 工具配置 + 参数）
   */
  public ToolResult execute(ToolRegistry registry, String toolName, ToolContext context) {
    Optional<ToolResult> denied = guard.denial(registry, toolName, context);
    if (denied.isPresent()) {
      return denied.get();
    }
    AgentTool tool = registry.find(toolName).orElseThrow();
    // 资源判定（S5-C）：先声明式前置闸，再把判定者注入上下文交给工具的命令式断言。
    // 判定者是"调用者权限集 × 工具声明的资源面"当场建出来的——锚在调用者身份上（S5-A 的锚点纪律），不是构造期常量。
    ResourceAuthorizer resources = ResourceAuthorizer.of(context.permissions(), tool.resources());
    Optional<String> resourceDenial = resources.denial();
    if (resourceDenial.isPresent()) {
      // 终局：不进审批（"够不着"不是人能裁决的事）。拒因是配置面的数据（命名空间 + 可达面摘要），不含模型自造串。
      LOG.warn(
          "工具调用被资源前置闸拒了（不进审批）: tool={} 工具声明={} 调用者可达={}",
          tool.name(),
          tool.resources().summary(),
          context.permissions().resourceScopes().summary());
      return ToolResult.error(RESOURCE_DENIED, resourceDenial.get());
    }
    ToolContext scoped = context.withResources(resources);
    ToolGate gate = tool.gate(scoped);
    if (gate == null) {
      // 契约是"缺省 ALLOW"，返回 null 是工具实现坏了：按 fail-closed 处理，不猜它的意思
      return ToolResult.error(
          COMMAND_BLOCKED, "工具的闸位自报为空（ToolGate 不得为 null），按 fail-closed 硬拒: " + toolName);
    }
    switch (gate) {
      case ToolGate.Allow ignored -> {
        // 直放：既有工具（不覆写 gate）行为逐字不变
      }
      case ToolGate.Block blocked -> {
        // 硬拒不进审批：不登记、不发 approval.requested、通道里看不到它（防"审批把硬拒洗白"）
        // 命中细节（规则 + 命中值）只进日志，供人调试"为什么这条被拒"；
        // 给模型的消息只留"这一类被拒 + classKey"——参数本来就是模型自己写的，回显零信息增益，
        // 而这条消息会落进事件库（持久面）。
        LOG.warn(
            "工具调用被硬拒（不可审批）: tool={} class={} reason={}",
            tool.name(),
            blocked.classKey(),
            blocked.reason());
        return ToolResult.error(COMMAND_BLOCKED, "命令被硬拒（不可审批）: class=" + blocked.classKey());
      }
      case ToolGate.Ask ask -> {
        // 审批面看的是同一个上下文（resources 已注入）——同一份判定者贯穿五段，不在半路换掉
        Optional<ToolResult> approvalDenied = askForApproval(tool, scoped, ask);
        if (approvalDenied.isPresent()) {
          return approvalDenied.get();
        }
      }
    }
    try {
      return tool.execute(scoped);
    } catch (ResourceDeniedException resourceDenied) {
      // 工具内关键访问点的命令式断言（require）判否，在本类的边界上转成工具错误码——异常不外泄给管线/MCP
      LOG.warn("工具内资源断言判否: tool={} 拒因={}", tool.name(), resourceDenied.getMessage());
      return ToolResult.error(RESOURCE_DENIED, resourceDenied.getMessage());
    }
  }

  /**
   * 需要人裁决的一次调用：由本类（宿主侧）构造 {@link ApprovalRequest} 并交给编排器。
   *
   * <p><b>不可自报身份</b>：{@code tool}/ {@code classKey}/{@code callerKey}/{@code requesterId}/{@code
   * kind}/{@code goal} 全部取自宿主已知的东西 （工具实例、工具自报的闸位、调用上下文），模型给不出也改不了。
   *
   * <p>返回空 = 已放行；非空 = 拒（含未装配编排器的 fail-closed 情形）。
   */
  private Optional<ToolResult> askForApproval(
      AgentTool tool, ToolContext context, ToolGate.Ask ask) {
    if (coordinator == null) {
      return Optional.of(
          ToolResult.error(
              APPROVAL_DENIED,
              "需人审批但未装配审批编排器（fail-closed 拒，不静默放行）: tool="
                  + tool.name()
                  + " class="
                  + ask.classKey()));
    }
    long now = System.currentTimeMillis();
    ApprovalRequest request =
        new ApprovalRequest(
            "ap-" + UUID.randomUUID(),
            tool.name(),
            ask.classKey(),
            ask.summary(),
            digest.apply(context),
            now,
            now + coordinator.timeout().toMillis(),
            // 桶键（会话放行的左半键）与实例身份（S6：上级判定要回答"谁在问、被派去干什么"）都取自宿主已知的
            // ToolContext——模型给不出也改不了。kind 取工具自报的闸位（只有分类器知道"为什么问"）。
            context.caller().name(),
            context.identity().instanceId(),
            ask.kind(),
            context.identity().goal());
    ApprovalDecision decision = coordinator.decide(request);
    if (decision == ApprovalDecision.APPROVE_ONCE || decision == ApprovalDecision.APPROVE_SESSION) {
      return Optional.empty();
    }
    // decision 为 null（编排器违约）也走到这里：没拿到显式批准就是拒
    return Optional.of(
        ToolResult.error(
            APPROVAL_DENIED,
            "审批未放行（" + decision + "）: tool=" + tool.name() + " class=" + ask.classKey()));
  }

  /**
   * 默认脱敏摘要：{@code sha256:<32 hex>}，算的是<b>按参数名排序</b>后的规范化串（同参同摘，便于对账）。
   *
   * <p><b>实现已上提</b>（C 包接 bash 时）：规范化写法与哈希算法都在 {@link Digest}——命令落账摘要（{@code ShellTool.ledgerArgs}，现
   * {@code io.mosire.bash.ShellTool}）与本方法必须共用同一段代码，否则同一条命令会有两个指纹。 本方法只剩"喂什么进去"这一件事：
   * <b>全部参数</b>（不是只有命令）。
   *
   * <p><b>为什么是这个输入面</b>：摘要进 {@code approval.requested} 事件（持久面）与提示面，人要看的是"这次调用整体是什么"，
   * 故覆盖全部参数；命令落账要的是"命令文本的指纹"，输入面只有 {@code command} 一项——两者不同是有意的（见 {@link Digest} 的类
   * javadoc），但哈希写法同源。要换实现走 {@link #of(ToolExecutionGuard, ApprovalCoordinator, Function)} 注入，
   * 不要就地改这里。
   *
   * <p><b>留一条诚实的边界</b>：sha256 是单向的，但参数里若有<b>低熵</b>密钥（短口令），摘要仍可被字典攻击反推——
   * 本方法只是"不留明文"的缺省件，不是"密钥可安全入摘要"的证明。
   */
  public static String defaultDigest(ToolContext context) {
    return Digest.ofArguments(context.arguments());
  }
}
