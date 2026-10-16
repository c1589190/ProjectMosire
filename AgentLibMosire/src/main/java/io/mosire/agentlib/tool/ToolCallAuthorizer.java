package io.mosire.agentlib.tool;

import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.decision.DecisionTurnScope;
import io.mosire.agentlib.decision.PendingDecisionQueue;
import io.mosire.agentlib.decision.SubagentDecisionLedger;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourceDeniedException;
import java.util.Map;
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
 * <p><b>★ P3d：{@code Ask} 分支的<b>第三条路</b>（父侧按子体本轮攒包，设计 §5sexies③④⑥）</b>：来者是本进程派出去的<b>子体</b> （身份绑在它的
 * MCP 链上）、且装配了父侧队列缝 {@link SubagentDecisionLedger} 时，这次调用<b>不执行、不问人</b>—— 记进"该子体本轮队列"并回具名码 {@link
 * #DECISION_QUEUED}（子体侧据此把这条收进自己的包）。既有两条路<b>逐字未动</b>： ① 线程作用域队列（{@link
 * DecisionTurnScope}，进程内管线）优先；② 都没有 ⇒ {@link #askForApproval} 当场问人。缝不注入 ⇒ 本类 与接线前完全一致。
 *
 * <p>同一条 {@code Ask} 的<b>第二次</b>调用（子体执行被批子集时经同一条 MCP 链回来）带放行标记 {@link
 * io.mosire.agentlib.decision.DecisionReleaseMarker}：父侧认出"包 P 的第 i 条、已批且尚未执行" ⇒ 剥掉标记、记"已用"、放行 （重复使用
 * ⇒ 具名拒）。
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

  /**
   * <b>父侧已把这次调用记进「该子体本轮队列」</b>（P3d，设计 §5sexies④）：不执行、不投审批面，收尾时与同轮其它条目收成一个包。
   *
   * <p><b>为什么是一个"失败"形状的码</b>：本仓 {@link ToolResult} 的契约是 {@code code == null} ⇒ 成功（见该类注释），而这条
   * <b>不是</b>成功——这次调用现在没有发生，将来是否发生取决于裁决者的裁决。子体识别"被记下了"必须靠这个<b>具名码</b>，
   * <b>不得</b>匹配中文文案（文案改一个字子体就瞎了）。
   */
  public static final String DECISION_QUEUED = "DECISION_QUEUED";

  /** 放行标记在场但父侧簿子里查不到这一条（端口/包/条目对不上，或簿子已被清/到点）⇒ 具名拒。 */
  public static final String DECISION_RELEASE_UNKNOWN = "DECISION_RELEASE_UNKNOWN";

  /** 放行标记已被用过（一次性）⇒ 具名拒；重复使用同一张放行条绝不等于第二次授权。 */
  public static final String DECISION_RELEASE_USED = "DECISION_RELEASE_USED";

  private final ToolExecutionGuard guard;
  private final ApprovalCoordinator coordinator;
  private final Function<ToolContext, String> digest;

  /**
   * <b>父侧「该子体本轮队列」缝</b>（P3d，设计 §5sexies③④⑥）：<b>可 null</b> = 未接线 ⇒ 本类行为与接线前<b>逐字不变</b> （{@code Ask}
   * 仍走既有那两条路）。见 {@link SubagentDecisionLedger} 的类注释。
   */
  private final SubagentDecisionLedger ledger;

  public ToolCallAuthorizer(ToolExecutionGuard guard) {
    this(guard, null, ToolCallAuthorizer::defaultDigest, null);
  }

  private ToolCallAuthorizer(
      ToolExecutionGuard guard,
      ApprovalCoordinator coordinator,
      Function<ToolContext, String> digest,
      SubagentDecisionLedger ledger) {
    this.guard = Objects.requireNonNull(guard, "guard");
    this.coordinator = coordinator;
    this.digest = Objects.requireNonNull(digest, "digest");
    this.ledger = ledger;
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
    return new ToolCallAuthorizer(guard, coordinator, ToolCallAuthorizer::defaultDigest, null);
  }

  /**
   * 同上，但指定脱敏摘要实现（C 包接 bash 后换成真正的命令脱敏：这是"摘要逻辑只有一处"的注入点， {@link #defaultDigest(ToolContext)}
   * 是缺省件）。摘要进 {@code approval.requested} 事件与提示面， 故实现<b>不得</b>回传命令明文。
   */
  public static ToolCallAuthorizer of(
      ToolExecutionGuard guard,
      ApprovalCoordinator coordinator,
      Function<ToolContext, String> digest) {
    return new ToolCallAuthorizer(guard, coordinator, digest, null);
  }

  /**
   * <b>全参装配（P3d 新增）</b>：在既有三参之上接<b>父侧「该子体本轮队列」缝</b>——这是父侧为子 Agent 建的 MCP 桥那一份 authorizer 的形态
   * （子体的工具全是桥来的代理 ⇒ 闸位知识只在父侧，见 {@link SubagentDecisionLedger}）。
   *
   * <p>{@code ledger} 为 {@code null} ⇒ 与 {@link #of(ToolExecutionGuard, ApprovalCoordinator,
   * Function)} 逐字等价（既有装配一行不改）。
   *
   * @param ledger 父侧队列簿（本进程只有父侧那一份；子进程不接线）
   */
  public static ToolCallAuthorizer of(
      ToolExecutionGuard guard,
      ApprovalCoordinator coordinator,
      Function<ToolContext, String> digest,
      SubagentDecisionLedger ledger) {
    return new ToolCallAuthorizer(guard, coordinator, digest, ledger);
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
    // ★ P3d（设计 §5sexies⑥）：一次性放行的**探测**——只认子体链上带回来的放行标记（包 id + 条目序号），
    //   不带标记的调用在这里拿到的就是空值，判定链一个字节都不变。
    //   标记在场但不成立 ⇒ 具名拒，**绝不**落到审批闸：否则"伪造一个标记"就成了一次免审批的执行尝试。
    Optional<SubagentDecisionLedger.Release> release = release(scoped);
    if (release.isPresent() && !release.get().allowed()) {
      LOG.warn("工具调用携带的放行标记不成立（具名拒，不进审批）: tool={} code={}", tool.name(), release.get().code());
      return ToolResult.error(release.get().code(), release.get().message());
    }
    // 标记成立 ⇒ 交给闸位/工具的是**剥掉标记**之后的那份参数（标记不是工具的参数）
    ToolContext effective =
        release.isPresent() ? withArguments(scoped, release.get().arguments()) : scoped;
    ToolGate gate = tool.gate(effective);
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
        // ★ 设计 §5 裁定 1+2（2026-10-16）：进包口径 = 全部 Ask。本轮的待裁决队列若已绑定（管线在回合开始时绑），
        //   这次调用<b>不执行、不阻塞、不走审批闸</b>——登记进队列，收尾时与其它命中一起收成一个包投递；
        //   模型当场只拿到"已加入决策包"。
        // ★ 回归安全网（没绑定队列时<b>逐字</b>保持今天的行为）：离线夹具、未接线的装配、以及本单未接线的子 Agent 路径
        //   （见 Brain 的 AgentPipeline：没有审批桥就不绑队列）都走下面这条老路——Ask ⇒ 问人，未放行 ⇒ APPROVAL_DENIED。
        //   没有这条网，"忘了接线"会表现为"敏感调用悄悄不执行了"，那是最贵的假绿。
        // ★ P3d：放行标记成立时这条**就是**要执行的那一条（它已经在父侧包里批过了）⇒ 不登记、不问人，直接放行。
        if (release.isEmpty()) {
          Optional<PendingDecisionQueue> queue = DecisionTurnScope.current();
          if (queue.isPresent()) {
            return queuedForDecision(queue.get(), tool, effective, ask);
          }
          // ★ P3d 第三条路（设计 §5sexies③④）：来者是**本进程派出去的子体**（身份在链上绑死）且父侧簿子可用 ⇒
          //   登记进"该子体本轮队列"，回具名码 DECISION_QUEUED，**不投审批面、不执行**。
          //   上面那条（线程作用域队列）与下面那条（当场问人）逐字未动——缝不在就一点也不经过这里。
          if (ledger != null && ledger.defer(tool, effective, ask)) {
            return queuedByParent(tool, ask);
          }
          // 审批面看的是同一个上下文（resources 已注入）——同一份判定者贯穿五段，不在半路换掉
          Optional<ToolResult> approvalDenied = askForApproval(tool, effective, ask);
          if (approvalDenied.isPresent()) {
            return approvalDenied.get();
          }
        }
      }
    }
    if (release.isPresent()) {
      // 走到这里 = 这一条真的要去执行了 ⇒ 把那张放行条记"已用"（重复使用 ⇒ 下一次具名拒）
      ledger.consumeRelease(release.get());
    }
    ToolResult result;
    try {
      result = tool.execute(effective);
    } catch (ResourceDeniedException resourceDenied) {
      // 工具内关键访问点的命令式断言（require）判否，在本类的边界上转成工具错误码——异常不外泄给管线/MCP
      LOG.warn("工具内资源断言判否: tool={} 拒因={}", tool.name(), resourceDenied.getMessage());
      return ToolResult.error(RESOURCE_DENIED, resourceDenied.getMessage());
    }
    registerQueuedByParent(result, tool, effective);
    return result;
  }

  /**
   * ★ <b>子体侧认标记 ⇒ 记本地条目</b>（P3d，设计 §5sexies④）：工具执行结果带 {@link #DECISION_QUEUED} 且本轮队列已绑 ⇒ 与 {@code
   * Ask} 分支<b>同一份</b> {@link PendingDecisionQueue} 语义登记一条（收尾时它才收得成包）。
   *
   * <p><b>没有队列 ⇒ 逐字旧行为</b>：不登记、结果照原样给模型（模型看到的就是父侧那句"已记入本轮决策包"）。离线夹具、主 Agent
   * 进程、以及本单之前的装配都不会有队列，因此这条对它们是<b>空操作</b>。
   *
   * <p><b>判据是码不是文案</b>：改文案子体仍必须认得（判别力用例归 P3c）。
   */
  private static void registerQueuedByParent(
      ToolResult result, AgentTool tool, ToolContext context) {
    if (!DECISION_QUEUED.equals(result.code())) {
      return;
    }
    Optional<PendingDecisionQueue> queue = DecisionTurnScope.current();
    if (queue.isEmpty()) {
      return;
    }
    try {
      queue.get().registerDeferred(tool.name(), context.arguments());
    } catch (RuntimeException failure) {
      // 登记失败（队列已产出过提议 / 超上限）：不静默——本侧少一条，父侧那份对账会在收尾时具名拒（fail-closed）
      LOG.warn("父侧已记下的条目在本侧登记失败（本轮的包会与父侧对不上账，收尾时会被具名拒） tool={}", tool.name(), failure);
    }
  }

  /**
   * 一次性放行的探测（P3d）：没接缝 / 没带标记 ⇒ 空值（判定链逐字不变）。
   *
   * <p>异常按<b>不成立</b>办：簿子自己炸了绝不能让调用"因为读不到账就直放"——fail-closed 朝向"具名拒"那一侧。
   */
  private Optional<SubagentDecisionLedger.Release> release(ToolContext context) {
    if (ledger == null) {
      return Optional.empty();
    }
    try {
      return ledger.release(context);
    } catch (RuntimeException failure) {
      LOG.warn("放行探测失败（按不成立办：不执行、不进审批）", failure);
      return Optional.of(
          SubagentDecisionLedger.Release.denied(
              DECISION_RELEASE_UNKNOWN,
              "放行簿读取失败（fail-closed 拒，不执行）: tool 调用被拒",
              context.identity().instanceId(),
              "",
              -1));
    }
  }

  /** 换参数（其余分量逐字不变）——剥标记用；冻结形状不许加字段，但构造新上下文是合法的。 */
  private static ToolContext withArguments(ToolContext context, Map<String, Object> arguments) {
    return new ToolContext(
        context.caller(),
        context.permissions(),
        context.config(),
        arguments,
        context.identity(),
        context.resources());
  }

  /**
   * 把这次 {@code Ask} 调用登记进<b>本轮待裁决队列</b>并返回给模型的人读结果（设计 §5 裁定 1+2）。
   *
   * <p><b>这一步只做一件事：记账</b>——不执行工具（{@link AgentTool#execute} 根本不在这里）、不阻塞（没有等人、没有通道）、 不登记 {@code
   * PendingApprovals}（那条投递留到回合收尾，一个包一条请求）。参数明文只进队列（→ 包内 {@link
   * io.mosire.agentlib.decision.PackageItem#args()}）；本方法<b>不</b>打日志、<b>不</b>发事件（事件面只带脱敏摘要，密钥纪律）。
   *
   * <p><b>返回值为什么是成功侧</b>：调用<b>被受理了</b>（受理 = 收进包），不是被拒。写成失败会让模型以为这条路走不通而改路重试
   * ——重试只会排出第二个包。文案明确说"尚未裁决、本轮不会执行"（设计 §5 裁定 2 的原话口径）：它答的是"这条现在不会发生"。
   *
   * <p><b>为什么不带错误码</b>：{@link ToolResult} 的契约是 {@code code != null} ⇒ 失败（见该类注释），而这里不是失败—— 硬塞一个码就得改
   * {@code ToolResult} 的契约或加新字段，两者都超出本单的"只做新增、不改冻结形状"。
   *
   * <p>★ <b>与 P3d 那条（父侧记账）的分工</b>：进程内这条路是"本进程管线自己攒"，模型看得到队列的语义，返回成功侧即可；跨进程那条必须让<b>另一个进程</b>
   * 认出"被记下了"，所以走具名码 {@link #DECISION_QUEUED}（{@link #queuedByParent}）——判据是码，不是文案。
   */
  private static ToolResult queuedForDecision(
      PendingDecisionQueue queue, AgentTool tool, ToolContext context, ToolGate.Ask ask) {
    queue.register(tool.name(), context.arguments(), ask);
    return ToolResult.ok(
        "已加入决策包（尚未裁决，结果下一轮自行查看）: tool="
            + tool.name()
            + " class="
            + ask.classKey()
            + "。★ 本次调用不会在现在执行，也没有去问审批人——它已随本轮的其它待裁决调用收进同一个决策包，"
            + "由确认者整包裁决（可整包批 / 整包驳 / 逐条例外）；被批准的部分在回合结束时执行。"
            + "不要因为这条结果重试同一个调用（那会排出第二个包），也不要假定它已经生效。");
  }

  /**
   * <b>父侧已记下这次调用</b>（P3d，设计 §5sexies③④）：回具名码 {@link #DECISION_QUEUED}，让子体侧靠<b>码</b>（不是文案）认出
   * "这条被记下了"，从而在收尾时把它收进自己的包。
   *
   * <p><b>为什么不在这里执行、也不在这里问人</b>：父侧这一笔记的是"这一轮要不要裁决、由谁裁决"——裁决在收尾时整包做（一条包 = 一条审批请求），
   * 本轮只攒条目。这一条与进程内那条（{@link #queuedForDecision}）是同一条口径，只是记账的人从子体换成了父侧（子体看不见闸位，见 {@link
   * SubagentDecisionLedger}）。
   *
   * <p><b>文案与码的分工</b>：码是子体的判据（稳定标识）；文案只服务模型（"现在不会执行、别重试"）。改文案不影响任何判定。
   */
  private static ToolResult queuedByParent(AgentTool tool, ToolGate.Ask ask) {
    LOG.debug(
        "子体的 Ask 调用已记入父侧本轮队列（本轮不执行、不阻塞、不投审批面）: tool={} class={}", tool.name(), ask.classKey());
    return ToolResult.error(
        DECISION_QUEUED,
        "已记入本轮决策包（尚未裁决，本轮不会执行）: tool="
            + tool.name()
            + " class="
            + ask.classKey()
            + "。★ 本次调用不会在现在执行，也没有去问审批人——它与本轮的其它待裁决调用会在回合收尾时收进同一个决策包，"
            + "由确认者整包裁决。不要因为这条结果重试同一个调用（那会排出第二个包），也不要假定它已经生效。");
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
