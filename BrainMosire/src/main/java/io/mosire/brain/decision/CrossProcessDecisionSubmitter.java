package io.mosire.brain.decision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.Decision;
import io.mosire.agentlib.decision.DecisionCommitPort;
import io.mosire.agentlib.decision.DecisionEventTypes;
import io.mosire.agentlib.decision.DecisionPackage;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.PackageItem;
import io.mosire.agentlib.decision.PackageState;
import io.mosire.agentlib.decision.PendingDecisionQueue;
import io.mosire.agentlib.decision.ProposeRequest;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventStore;
import io.mosire.agentlib.event.EventWrite;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.PermissionChecker;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.runtime.EventTypes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>子体侧的回合收尾口：把这一轮的待裁决队列<b>跨进程</b>交回父侧结算，回执落进<b>自己的</b>包表与事件库</b> （设计 §5quinquies ①/②/⑤，P3b）。
 *
 * <p>它是 {@code DecisionApprovalBridge#flush} 在<b>子体这一侧</b>的对应物：桥把包投给<b>本进程的</b>审批面，本类把包投给
 * <b>父进程的</b> {@code submit_decision_package}。两者都收"本轮队列"，都以"一个包"为决策单元，都同步等方式返回。
 *
 * <h2>五步的顺序就是本单的形状（设计 §5quinquies ⑤-3 的硬要求）</h2>
 *
 * <pre>
 * ① 队列非空 ⇒ 先在【自己的包表】里落一个 PROPOSED 包（拿到包 id；proposerKey = 本实例 id、caller = 本实例令牌）
 * ② 用该包 id 作 requestId 调 submit_decision_package（条目原样：toolName/args/classKey/askKind，顺序 = 登记顺序）
 * ③ 回执 ⇒ 回填自己的包（decide）+ 执行被批子集（注入的提交口，执行身份 = 本实例）+ 落自己的事件
 * ④ deciderLevel=NONE（软拒）⇒ 建包但零批准、零落盘（这是一次"没有通过的裁决"，该进历史）
 * ⑤ 硬拒（ToolResult.error 带码）⇒ 提交没被受理：不留未决包（当场具名作废 + WARN + expired 事件）
 * </pre>
 *
 * <p>★ <b>为什么必须"先落本地包"</b>（⑤-3）：{@code requestId} 就是那个包的 id。先有它，父侧才有一个稳定的幂等键 （重试不会让人再答一遍，见 {@link
 * CrossProcessDecisionGateway} 的幂等缓存），而且"我的历史里有这个包"这件事在提交之前就成立
 * ——提交成功但落库前崩掉时，历史上不会出现一个"父侧批过、子体从没有过"的幽灵包。
 *
 * <p>★ <b>⑤ 与"硬拒 ⇒ 不建包"的字面冲突，如实记</b>：字面上"提交没被受理"就不该建包，但 ① 要求包在提交之前就存在 （上面那条理由）⇒
 * 实现上只能"建了再当场作废"。作废（{@code PROPOSED ⇒ EXPIRED}、零落盘）+ 一条带原因的 {@code decision.package.expired}
 * 事件，读起来是"这个包提交没被受理"，不是一个悬着的未决包。
 *
 * <h2>缝在不在：{@link #available()}（装配层与管线共用这一个判据）</h2>
 *
 * <p>"有跨进程缝"= 本进程的 registry 里<b>查得到</b> {@value #SUBMIT_TOOL_NAME}，且本实例的权限集<b>判定面</b>放行它 （{@link
 * PermissionChecker#allows} 这一个 oracle，与可见面/判定面同源）。两者任一不成立 ⇒ <b>不绑本轮队列</b>， 管线的收尾退回既有 {@code
 * decisionBridge.flush(queue)}（无桥 ⇒ 与接线前逐字一致）。 这条是回归安全网：子体进程如果没有这条缝 （父侧没注册该工具 / 模板没给它），行为与 P3b
 * 之前<b>一字不差</b>。
 *
 * <p><b>为什么不是"先本地判一把"</b>（⑤-1）：子体档位默认 {@code LIMITED}，而能力判据要求裁决者 {@code FULL} ⇒
 * 子体本来就没有预裁定权，不存在"白跑一跳"。
 *
 * <h2>调用路径：既有工具调用路径，不造 MCP 客户端</h2>
 *
 * <p>提交走的是管线自己的那条入口 {@link ToolCallAuthorizer#execute(ToolRegistry, String,
 * ToolContext)}——与模型发起的工具调用 <b>同一条</b>（判定链 → 资源面 → 执行），工具本体由父侧经 MCP 桥同步进本进程的 registry。本类<b>不</b>新建
 * MCP 客户端、 <b>不</b>直接读写管道。
 *
 * <p><b>工具调用失败（TOOL_NOT_FOUND / PERMISSION_DENIED / 硬拒）不是"没人批"</b>：那时包内条目一条都没被受理， 于是走 ⑤（作废 +
 * 原因），而<b>不</b>是"当作零批准"——两者在历史里必须看得出区别。
 *
 * <h2>执行在子体、用子体自己的权限集（红线 R7）</h2>
 *
 * <p>被批子集由<b>注入的</b> {@link DecisionCommitPort} 执行（本机形态 = {@code LocalDecisionCommitPort}）：装配层必须用
 * <b>本实例的</b>权限集与身份构造它——父侧回传的 {@code finalizedBy} 只是"谁拍的板"，供记录与审计（设计 §5quinquies ①「父侧不得替子体执行」）。
 * 提交口未装配 ⇒ 裁决照样落库（{@code decided} 事件照发），但包保持 {@code DECIDED}、<b>零落盘</b>，并落一行 WARN
 * ——绝不把"批了但没人接盘"说成"已落定"（与 {@code DecisionApprovalBridge} 同一口径）。
 *
 * <h2>事件面（五类，载荷与既有形状同形）</h2>
 *
 * <p>{@code decision.package.proposed} / {@code .decided}（含 {@code trail}——<b>直接用回执里的那一份</b>，不另算）/
 * {@code .committed} / {@code .conflicted} / {@code .expired}，全部经<b>本进程的</b> {@link EventStore}
 * 落进子体自己的 {@code events.db}（{@code correlationId} = 包 id，与桥同源），另经可空的总线旁路通知。 载荷的键与顺序照 {@code
 * DecisionApprovalBridge#payload}：{@code packageId / proposerKey / itemCount / approvedCount /
 * rejectedCount / toolNames / digests}（+ {@code trail} / {@code revisionRef} / {@code outcomes} /
 * {@code reason}）。
 *
 * <p><b>"没被受理"那一档用既有的通用事件</b>（{@link EventTypes#DECISION}，{@code decision} 载荷 = 一个去向词 + {@code
 * detail}）：那时可能连包都没有（提议就被拒），不该借 {@code decision.package.*} 一族说话——新增第六种包事件会让"包的五态" 这张冻结表长出第六态。
 *
 * <h2>密钥纪律（红线 6）</h2>
 *
 * <p>参数明文只进 ① 包内 {@link PackageItem#args()} 与 ② 提交工具入参的 {@code items[].args}（本类把它们原样搬过去，
 * 不做任何加工）。事件载荷、日志、工具出参<b>一个字节的明文都没有</b>（{@code digests} 是逐条脱敏摘要，{@code trail}/理由 是裁决人与拒码）。
 */
public final class CrossProcessDecisionSubmitter {

  private static final Logger LOG = LoggerFactory.getLogger(CrossProcessDecisionSubmitter.class);

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 父侧那个结算口在本进程 registry 里的名字（不另抄一份字面量）。 */
  public static final String SUBMIT_TOOL_NAME = SubmitDecisionPackageTools.NAME;

  /**
   * {@code deciderLevel=NONE}（软拒）时回填进 {@link Decision#by()} 的"裁决人"。
   *
   * <p>它不是某一级，也不是人——是"<b>没人裁成</b>"。{@code Decision#by} 不得为空白，所以这里给一个<b>看得出是它</b>的值 （与父侧 {@code
   * DECIDER_NONE} 同一个语义），而不是借用任何真实身份。
   */
  public static final String BY_NONE = "cross-process:none";

  /** 作废原因里的一句前缀（硬拒 / 回执不可用那两条路）：只含拒码与短原因，不含参数明文。 */
  private static final String REASON_NOT_ACCEPTED = "跨进程提交未被受理";

  /** 硬拒的 `decision` 事件去向词（提议阶段就失败，连包都没建出来）。 */
  static final String DECISION_PROPOSE_REFUSED = "CROSS_PROCESS_PROPOSE_REFUSED";

  /** {@code action=refused} 的 stage 取值：提议没成功。 */
  private static final String STAGE_PROPOSE = "propose";

  private final DecisionPackages packages;

  /** 本机执行者（执行被批子集）；{@code Optional.empty()} = 未装配 ⇒ 只裁决、不落盘（见类注释）。 */
  private final Optional<DecisionCommitPort> commitPort;

  private final ToolRegistry registry;
  private final ToolCallAuthorizer authorizer;
  private final Map<String, Object> toolConfig;
  private final AccessToken caller;
  private final AgentPermissionSet permissionSet;

  /**
   * 本实例身份（= {@code proposerKey} 的来源，也是提交工具调用上下文的身份）。
   *
   * <p>它由装配层给（子体 = {@code AgentIdentity.subagent(实例 id, 档位, 目标, 深度)}）：<b>模型给不出、本类不猜</b>。
   * 父侧还会用它自报的这份与实例注册表核对（防冒充），所以这里用错身份不是"少一次核对"，而是当场具名拒。
   */
  private final AgentIdentity identity;

  private final EventStore events;

  /** 事件总线；<b>可 null</b>（null = 只落库、不旁路通知——同 {@code DecisionApprovalBridge} 的构造口径）。 */
  private final EventBus bus;

  /**
   * @param packages 本实例<b>自己的</b>包表（子体的那一张；<b>不是</b>父侧的——设计 §5quinquies ①「包表与历史只在子体」）
   * @param commitPort 本机执行者（{@code Optional.empty()} = 未装配：只裁决不执行，见类注释）
   * @param registry 本进程的工具注册表（父侧工具经 MCP 桥同步进来的那一张）
   * @param authorizer 工具调用唯一入口（与模型发起的调用同一条；本类不另写判定链）
   * @param toolConfig 装配期工具配置（与管线<b>同一份</b>：提交工具的上下文不该是"少一半配置"的第二条路）
   * @param caller 本实例令牌（①包内 {@code caller}；也是提交工具调用上下文的令牌）
   * @param permissionSet 本实例权限集（缝判定 + 提交工具调用上下文）
   * @param identity 本实例身份（{@code proposerKey} 的唯一来源）
   * @param events 本进程事件库（子体的 {@code events.db}；五类包事件落这里）
   * @param bus 事件总线（可 null；null = 不旁路通知）
   */
  public CrossProcessDecisionSubmitter(
      DecisionPackages packages,
      Optional<DecisionCommitPort> commitPort,
      ToolRegistry registry,
      ToolCallAuthorizer authorizer,
      Map<String, Object> toolConfig,
      AccessToken caller,
      AgentPermissionSet permissionSet,
      AgentIdentity identity,
      EventStore events,
      EventBus bus) {
    this.packages = Objects.requireNonNull(packages, "packages");
    this.commitPort = Objects.requireNonNull(commitPort, "commitPort");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
    this.toolConfig = toolConfig == null ? Map.of() : Map.copyOf(toolConfig);
    this.caller = Objects.requireNonNull(caller, "caller");
    this.permissionSet = Objects.requireNonNull(permissionSet, "permissionSet");
    this.identity = Objects.requireNonNull(identity, "identity");
    this.events = Objects.requireNonNull(events, "events");
    this.bus = bus;
  }

  /** 本实例键（{@code proposerKey}；子体自报、父侧核注册表的那个键）。 */
  public String proposerKey() {
    return identity.instanceId();
  }

  /**
   * <b>跨进程缝在不在</b>（管线在回合开始据此决定"绑不绑本轮队列"，见类注释）。
   *
   * <p>判据两条，都是现读（不缓存）：① registry 里查得到 {@value #SUBMIT_TOOL_NAME}；② 本实例权限集的判定面放行它 （{@link
   * PermissionChecker#allows}——与可见面同一个 oracle，不会出现"看得见却一调就拒"的形状）。
   *
   * <p>身份未穿透（{@link AgentIdentity#isUnknown()}）也判<b>不在</b>：自报一个 {@code unknown} 会被父侧具名拒， 那不是一条能用的缝。
   */
  public boolean available() {
    if (identity.isUnknown()) {
      LOG.warn("跨进程决策缝不可用：本实例身份未穿透（identity=unknown）——本轮不绑待裁决队列");
      return false;
    }
    Optional<AgentTool> tool = registry.find(SUBMIT_TOOL_NAME);
    if (tool.isEmpty()) {
      // 正常形态（主 Agent 进程 / 没接父链接的进程）：不绑队列，Ask 走既有那一跳
      LOG.debug("跨进程决策缝不在（registry 里没有 {}）：收尾走既有桥那条路", SUBMIT_TOOL_NAME);
      return false;
    }
    boolean allowed =
        PermissionChecker.allows(
            permissionSet, SUBMIT_TOOL_NAME, tool.get().spec(), tool.get().roles());
    if (!allowed) {
      // 看得见名字却不放行 = 部署漏配（模板 allowedTools 少了它）⇒ 响亮，否则表现是"敏感调用悄悄不执行"
      LOG.warn(
          "跨进程决策缝不可用：{} 在目录里，但本实例权限集不放行它（模板 allowedTools 缺了这一条？）" + "——本轮不绑待裁决队列，Ask 走既有那一跳",
          SUBMIT_TOOL_NAME);
    }
    return allowed;
  }

  /**
   * 回合收尾：把本轮队列跨进程交回父侧结算，并把回执落进自己的包表与事件库（类注释的五步）。
   *
   * <p><b>空队列 = 零副作用</b>：不建包、不发事件、不提交——返回 {@code Optional.empty()}（与桥的空队列口径逐字一致）。
   *
   * @param queue 本轮待裁决队列（非 null；管线在回合开始时建、收尾时交到这里）
   * @return 该包的最终快照；空队列 ⇒ {@code Optional.empty()}
   */
  public Optional<DecisionPackage> close(PendingDecisionQueue queue) {
    Objects.requireNonNull(queue, "queue");
    List<PendingDecisionQueue.PendingEntry> entries = queue.snapshot();
    if (entries.isEmpty()) {
      return Optional.empty();
    }
    Optional<ProposeRequest> request = queue.toProposeRequest();
    if (request.isEmpty()) {
      // 队列在两次取数之间被清空（理论上到不了）：不建包、不留痕——但要说出来
      LOG.warn("本轮待裁决队列的提议请求为空（队列条数={}）⇒ 不提交、不建包", entries.size());
      return Optional.empty();
    }
    // ① 先落自己的包（拿到包 id；它同时是 ② 的幂等键）
    DecisionPackage proposed;
    try {
      proposed = packages.propose(request.orElseThrow());
    } catch (DecisionPackages.RejectionException refused) {
      // 建包被拒（典型：本实例已有一个未决包）⇒ 没有包 id 就没有幂等键，这次提交不发出去（fail-closed）
      LOG.warn(
          "跨进程收尾：本实例建包被拒（本轮不提交、零落盘）proposerKey={} code={} 理由={}",
          proposerKey(),
          refused.code(),
          refused.getMessage());
      emitRefused(STAGE_PROPOSE, refused.code());
      return Optional.empty();
    }
    if (proposed.state() != PackageState.PROPOSED) {
      // 到点建出来的包（deadline 已经过去）：它从来没有进入决策面 ⇒ 不提交、不发 proposed（同桥的口径）
      LOG.warn("跨进程收尾：包不在待裁决态（不提交、零落盘）packageId={} state={}", proposed.id(), proposed.state());
      return Optional.of(proposed);
    }
    emitPackageEvent(DecisionEventTypes.PROPOSED, payload(proposed, null, null), proposed.id());

    // ② 提交（requestId = 刚拿到的包 id）
    ToolResult result;
    try {
      result = submit(proposed, entries);
    } catch (RuntimeException failure) {
      // 提交路径自己炸了（桥断/工具实现抛异常）：与"没被受理"同办——当场作废，绝不留一个悬着的未决包
      LOG.error(
          "跨进程收尾：提交抛出未捕获异常（零落盘，当场作废）packageId={} proposerKey={}",
          proposed.id(),
          proposerKey(),
          failure);
      return expire(proposed, REASON_NOT_ACCEPTED + "：提交抛出异常");
    }
    if (!result.success()) {
      // ⑤ 硬拒 / 工具不可达：这次提交没被受理 ⇒ 当场作废（绝不"当作零批准"，两者在历史里必须分得开）
      String code = result.code() == null ? "SUBMIT_REFUSED" : result.code();
      LOG.warn(
          "跨进程收尾：提交未被受理（零落盘，当场作废）packageId={} proposerKey={} code={} message={}",
          proposed.id(),
          proposerKey(),
          code,
          result.message());
      return expire(proposed, REASON_NOT_ACCEPTED + "：" + code);
    }

    Optional<Verdict> verdict = parseVerdict(result.message());
    if (verdict.isEmpty()) {
      // 回执不可解析 ⇒ 与"没被受理"同办（绝不能把一段读不懂的文本当成一次批准）
      LOG.error("跨进程收尾：回执无法解析（零落盘，当场作废）packageId={} proposerKey={}", proposed.id(), proposerKey());
      return expire(proposed, REASON_NOT_ACCEPTED + "：回执无法解析");
    }
    return apply(proposed, verdict.orElseThrow());
  }

  /**
   * ③/④：把回执落进自己的包（{@code decide}）⇒ 执行被批子集 ⇒ 落 {@code decided}/{@code committed} 事件。
   *
   * <p>软拒（{@code deciderLevel=NONE}）与正常裁决在这里<b>只差一个集合</b>：前者被批子集为空 ⇒ {@code decide} 一次 零批准、{@code
   * commit} 直接判 {@code REJECTED}（包层不调 port，结构上就落不了盘）。
   */
  private Optional<DecisionPackage> apply(DecisionPackage proposed, Verdict verdict) {
    Set<Integer> approved = verdict.approvedIndexes();
    String finalizedBy = verdict.finalizedBy();
    boolean none = CrossProcessDecisionGateway.DECIDER_NONE.equals(verdict.deciderLevel());
    if (!approved.isEmpty() && (finalizedBy == null || finalizedBy.isBlank())) {
      // "批了东西却没人拍板" = 回执违反契约：不能编一个裁决人把它洗成合法裁决（fail-closed）
      LOG.error(
          "跨进程收尾：回执批了 {} 条却没有裁决者（零落盘，当场作废）packageId={} proposerKey={}",
          approved.size(),
          proposed.id(),
          proposerKey());
      return expire(proposed, REASON_NOT_ACCEPTED + "：回执缺少裁决者");
    }
    String by = finalizedBy == null || finalizedBy.isBlank() ? BY_NONE : finalizedBy;
    String note =
        "跨进程裁决回填：deciderLevel="
            + verdict.deciderLevel()
            + "，批准 "
            + approved.size()
            + "/"
            + proposed.items().size()
            + " 条"
            + (none ? "（fail-closed 零批准：提交被受理了，但没有任何裁决者）" : "");
    DecisionPackage decided;
    try {
      decided = packages.decide(proposed.id(), new Decision(approved, by, note));
    } catch (DecisionPackages.RejectionException refused) {
      // 状态机具名拒（典型：等待期间包已到点 EXPIRED）⇒ 不回填、不落盘；到点那一档补一条 expired，历史才闭合
      LOG.warn(
          "跨进程收尾：回填被拒（零落盘）packageId={} code={} 理由={}",
          proposed.id(),
          refused.code(),
          refused.getMessage());
      if (DecisionPackages.PACKAGE_EXPIRED.equals(refused.code())) {
        DecisionPackage expired = packages.find(proposed.id()).orElse(proposed);
        emitPackageEvent(
            DecisionEventTypes.EXPIRED,
            payload(expired, null, null, null, expiredReason()),
            expired.id());
      }
      return packages.find(proposed.id());
    }
    emitPackageEvent(
        DecisionEventTypes.DECIDED, payload(decided, null, verdict.trail()), decided.id());
    LOG.info(
        "跨进程收尾：裁决已回填 packageId={} proposerKey={} deciderLevel={} finalizedBy={} approved={} rejected={}",
        decided.id(),
        proposerKey(),
        verdict.deciderLevel(),
        finalizedBy,
        approved.size(),
        decided.items().size() - approved.size());
    commit(decided);
    return packages.find(decided.id());
  }

  /**
   * 执行被批子集（一次提交、一份统一回执；被驳条目永不进 calls——包层保证）。
   *
   * <p>提交口未装配 ⇒ 只落 WARN、包保持 {@code DECIDED}（"批了但没人接盘"必须看得见）；port 抛异常 ⇒ 记 ERROR 且 <b>一条 {@code
   * committed}/{@code conflicted} 都不发</b>；回执 {@code REJECTED}（零落盘）⇒ 也不发（与 {@code
   * DecisionApprovalBridge#commitAfterDecision} 逐条同口径）。
   */
  private void commit(DecisionPackage decided) {
    if (commitPort.isEmpty()) {
      LOG.warn(
          "跨进程收尾：提交口未装配（DecisionCommitPort 未注入）⇒ 包保持 DECIDED、不执行被批子集、零落盘"
              + " packageId={} items={}",
          decided.id(),
          decided.items().size());
      return;
    }
    CommitReceipt receipt;
    try {
      receipt = packages.commit(decided.id(), commitPort.orElseThrow());
    } catch (DecisionPackages.RejectionException refused) {
      LOG.error(
          "跨进程收尾：提交未落定（包保持 DECIDED；一条 committed/conflicted 都不发）packageId={} code={} 理由={}",
          decided.id(),
          refused.code(),
          refused.getMessage());
      return;
    }
    LOG.info(
        "跨进程收尾：被批子集已执行 packageId={} status={} outcomes={} revisionRef={}",
        decided.id(),
        receipt.status(),
        receipt.outcomes().size(),
        receipt.revisionRef());
    switch (receipt.status()) {
      case COMMITTED ->
          emitPackageEvent(
              DecisionEventTypes.COMMITTED,
              payload(decided, receipt.revisionRef(), null, receipt),
              decided.id());
      case CONFLICTED ->
          // 本机形态不会冲突（世界形态才有版本语义）；真出现就照形状发，revisionRef 记空串（不编一个引用）
          emitPackageEvent(
              DecisionEventTypes.CONFLICTED, payload(decided, "", null, receipt), decided.id());
      case REJECTED ->
          LOG.info("跨进程收尾：提交结局=REJECTED（零落盘，不发 committed/conflicted）packageId={}", decided.id());
    }
  }

  /**
   * ② 调父侧的 {@code submit_decision_package}（既有工具调用路径；见类注释）。
   *
   * <p>入参的两个关键字面都不含身份声明以外的自由度：{@code proposerKey} 取本实例身份（模型给不出），{@code requestId} 取<b>刚建的包
   * id</b>（①的产物）。条目顺序 = 队列登记顺序，{@code index} = 包内条目身份（两处同源，不重编号）。
   */
  private ToolResult submit(
      DecisionPackage proposed, List<PendingDecisionQueue.PendingEntry> entries) {
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("proposerKey", proposerKey());
    arguments.put("requestId", proposed.id());
    List<Map<String, Object>> items = new ArrayList<>(entries.size());
    for (PendingDecisionQueue.PendingEntry entry : entries) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("index", entry.packageItem().index());
      row.put("toolName", entry.toolName());
      row.put("args", entry.args());
      row.put("classKey", entry.classKey());
      row.put("askKind", entry.askKind().name());
      items.add(row);
    }
    arguments.put("items", items);
    ToolContext context = new ToolContext(caller, permissionSet, toolConfig, arguments, identity);
    LOG.info(
        "跨进程收尾：提交本轮决策包 packageId={} proposerKey={} items={}（条目明文只进这次工具调用）",
        proposed.id(),
        proposerKey(),
        entries.size());
    return authorizer.execute(registry, SUBMIT_TOOL_NAME, context);
  }

  /**
   * 回执 → {@link Verdict}（父侧出参形状的读口，逐字段读；读不出必需字段 ⇒ {@code Optional.empty()} = 不可用）。
   *
   * <p>只读六个键，形状全是 id / 计数 / 索引 / 轨迹（<b>没有</b>参数明文）：{@code approvedIndexes} / {@code finalizedBy} /
   * {@code deciderLevel} / {@code trail}（原样带走，落 {@code decided} 事件）。 {@code rejectedIndexes} 与
   * {@code perItemReasons} 不参与本类判定（"被驳"在状态机里就是"不在被批集合里"）， 逐条理由随父侧日志与决策面。
   */
  private Optional<Verdict> parseVerdict(String body) {
    JsonNode root;
    try {
      root = JSON.readTree(body);
    } catch (Exception unreadable) {
      LOG.warn("跨进程收尾：回执不是合法 JSON（零批准）message={}", unreadable.getMessage());
      return Optional.empty();
    }
    JsonNode approved = root == null ? null : root.get("approvedIndexes");
    if (approved == null || !approved.isArray()) {
      return Optional.empty();
    }
    Set<Integer> approvedIndexes = new LinkedHashSet<>();
    for (JsonNode index : approved) {
      if (!index.canConvertToInt()) {
        return Optional.empty();
      }
      approvedIndexes.add(index.asInt());
    }
    String deciderLevel = text(root.get("deciderLevel"));
    String finalizedBy = text(root.get("finalizedBy"));
    if (deciderLevel == null) {
      // 没有 deciderLevel ⇒ 认不出这是"谁裁的"⇒ 一律按不可用办（fail-closed 那一侧）
      return Optional.empty();
    }
    Map<String, Object> trail = row(root.get("trail"));
    return Optional.of(new Verdict(approvedIndexes, finalizedBy, deciderLevel, trail));
  }

  /**
   * 父侧回执里本类要用的那几项（内部读口；形状与 {@code submit_decision_package} 的出参一致）。
   *
   * @param approvedIndexes 终局被批的条目（只准执行这些）
   * @param finalizedBy 拍板者（{@code null} = 没人拍板）
   * @param deciderLevel {@code AUTO} / {@code HUMAN} / {@code NONE}
   * @param trail 级联轨迹行（{@code null} = 没有；<b>原样</b>进 {@code decided} 事件载荷）
   */
  private record Verdict(
      Set<Integer> approvedIndexes,
      String finalizedBy,
      String deciderLevel,
      Map<String, Object> trail) {}

  /** 文本取值（缺 / JSON null / 空白 ⇒ {@code null}）。 */
  private static String text(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    String value = node.asText();
    return value == null || value.isBlank() ? null : value;
  }

  /** 一个 JSON 对象节点 ⇒ 不可变 Map（非对象 ⇒ {@code null}：轨迹只有"在"与"不在"两态）。 */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> row(JsonNode node) {
    if (node == null || !node.isObject()) {
      return null;
    }
    return JSON.convertValue(node, Map.class);
  }

  /** 作废（{@code PROPOSED ⇒ EXPIRED}，零落盘）+ {@code expired} 事件；非 {@code PROPOSED} 的包原样保留。 */
  private Optional<DecisionPackage> expire(DecisionPackage proposed, String reason) {
    DecisionPackage after;
    try {
      after = packages.expireNow(proposed.id(), System.currentTimeMillis());
    } catch (DecisionPackages.RejectionException refused) {
      LOG.warn(
          "跨进程收尾：具名作废被拒（包状态未被改写，也未被报成落定）packageId={} code={} 理由={}",
          proposed.id(),
          refused.code(),
          refused.getMessage());
      return packages.find(proposed.id());
    }
    if (after.state() == PackageState.EXPIRED) {
      emitPackageEvent(
          DecisionEventTypes.EXPIRED, payload(after, null, null, null, reason), after.id());
      LOG.warn(
          "跨进程收尾：包已当场具名作废（EXPIRED、零落盘；该 proposerKey 的未决名额已释放）packageId={} reason={}",
          after.id(),
          reason);
    } else {
      LOG.warn(
          "跨进程收尾：包已不是 PROPOSED ⇒ 不作废、原样保留（不覆盖既有裁决/回执）packageId={} state={}",
          after.id(),
          after.state());
    }
    return packages.find(after.id());
  }

  /** 到点作废的原因文字（等待跨进程裁决期间 deadline 走完那一档）。 */
  private static String expiredReason() {
    return "等待跨进程裁决期间到点（零落盘）";
  }

  // ---------- 事件面：载荷与 DecisionApprovalBridge 同形（键与顺序照抄那一个实现点） ----------

  private void emitPackageEvent(String type, Map<String, Object> payload, String packageId) {
    emit(type, payload, packageId);
  }

  /**
   * "这次提交没被受理"的通用事件（{@link EventTypes#DECISION}）：连包都没有的时候，不该借 {@code decision.package.*}
   * 一族说话（见类注释）。
   */
  private void emitRefused(String stage, String code) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("decision", DECISION_PROPOSE_REFUSED);
    payload.put(
        "detail",
        Map.of("stage", stage, "code", code == null ? "" : code, "proposerKey", proposerKey()));
    emit(EventTypes.DECISION, payload, "");
  }

  /**
   * 落一条事件：<b>先 append 进本进程的事件库</b>（这是子体唯一的历史面），再尽力旁路通知总线。
   *
   * <p>与桥有一处刻意的不同：桥只 {@code bus.publish}（落库由装配层订阅负责），而<b>子体进程的总线没有落库订阅</b> （{@code
   * SubagentProcessMain} 只建裸 {@code EventBus}）⇒ 这里必须自己 append，否则五类事件一条都留不下。
   *
   * <p><b>审计面故障不得改变裁决</b>（同桥的口径）：append/发布失败只 WARN。
   */
  private void emit(String type, Map<String, Object> payload, String packageId) {
    Event event;
    try {
      String body = JSON.writeValueAsString(payload);
      event =
          events.append(
              EventWrite.of(type, proposerKey(), body, packageId == null ? "" : packageId));
    } catch (RuntimeException | JsonProcessingException failure) {
      LOG.warn("决策包事件写入失败（不影响裁决与执行）type={} packageId={}", type, packageId, failure);
      return;
    }
    if (bus != null) {
      bus.publish(event);
    }
  }

  /**
   * 五类事件的载荷（严格同形：{@code packageId / proposerKey / itemCount / approvedCount / rejectedCount /
   * toolNames / digests} + 可选 {@code trail} / {@code revisionRef} / {@code outcomes}）。
   *
   * <p><b>还没裁决的包两个计数都是 0</b>（{@code proposed} 事件）：用 {@code itemCount - approvedCount} 去凑 {@code
   * rejectedCount} 会把它写成"全被驳了"，与事实相反（桥那边踩过同一个坑）。
   *
   * <p><b>绝不写明文</b>：{@code digests} 是逐条脱敏摘要（{@link PackageItem#argsDigest()}），{@code toolNames}
   * 是工具名。本方法<b>从不</b>读 {@link PackageItem#args()}。
   */
  private static Map<String, Object> payload(
      DecisionPackage pkg, String revisionRef, Map<String, Object> trail) {
    return payload(pkg, revisionRef, trail, null, null);
  }

  /** 同上 + 可选逐条去向（{@code receipt != null}；提交回执那条路用）。 */
  private static Map<String, Object> payload(
      DecisionPackage pkg, String revisionRef, Map<String, Object> trail, CommitReceipt receipt) {
    return payload(pkg, revisionRef, trail, receipt, null);
  }

  /** 同上 + 可选逐条去向（{@code receipt != null}）与可选作废原因（{@code reason != null}）。 */
  private static Map<String, Object> payload(
      DecisionPackage pkg,
      String revisionRef,
      Map<String, Object> trail,
      CommitReceipt receipt,
      String reason) {
    int itemCount = pkg.items().size();
    Decision decision = pkg.decision();
    int approvedCount = decision == null ? 0 : decision.approvedIndexes().size();
    int rejectedCount = decision == null ? 0 : itemCount - approvedCount;
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("packageId", pkg.id());
    payload.put("proposerKey", pkg.proposerKey());
    payload.put("itemCount", itemCount);
    payload.put("approvedCount", approvedCount);
    payload.put("rejectedCount", rejectedCount);
    payload.put("toolNames", toolNames(pkg));
    payload.put("digests", digests(pkg));
    if (trail != null) {
      payload.put("trail", trail);
    }
    if (revisionRef != null) {
      payload.put("revisionRef", revisionRef);
    }
    if (receipt != null) {
      payload.put("outcomes", outcomeRows(pkg, receipt));
    }
    if (reason != null) {
      payload.put("reason", reason);
    }
    return payload;
  }

  /** 逐条去向行（形状同桥）：{@code index / toolName / outcome / reason}（可空）。 */
  private static List<Map<String, Object>> outcomeRows(DecisionPackage pkg, CommitReceipt receipt) {
    List<CommitReceipt.ItemResult> results = receipt.itemResults();
    List<Map<String, Object>> rows = new ArrayList<>(pkg.items().size());
    for (int i = 0; i < pkg.items().size(); i++) {
      PackageItem item = pkg.items().get(i);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("index", item.index());
      row.put("toolName", item.toolName());
      if (i < results.size()) {
        row.put("outcome", results.get(i).outcome().name());
        row.put("reason", results.get(i).reason());
      } else {
        row.put("outcome", CommitReceipt.ItemOutcome.NOT_COMMITTED.name());
        row.put("reason", "回执未覆盖该条（宿主少报）");
      }
      rows.add(row);
    }
    return rows;
  }

  /** 包内工具名（顺序 = 条目顺序；重复不折叠）。 */
  private static List<String> toolNames(DecisionPackage pkg) {
    List<String> names = new ArrayList<>(pkg.items().size());
    for (PackageItem item : pkg.items()) {
      names.add(item.toolName());
    }
    return List.copyOf(names);
  }

  /** 包内逐条脱敏摘要（顺序 = 条目顺序）。 */
  private static List<String> digests(DecisionPackage pkg) {
    List<String> digests = new ArrayList<>(pkg.items().size());
    for (PackageItem item : pkg.items()) {
      digests.add(item.argsDigest());
    }
    return List.copyOf(digests);
  }
}
