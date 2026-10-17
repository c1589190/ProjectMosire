package io.mosire.agentlib.decision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
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
 * 决策包的 MCP 工具面（U4，上游契约 {@code docs/upstream/agentlib-decision-contract.md} §7.1）。
 *
 * <p><b>它是谁</b>：把「列包、看包、整包表态、逐条表态、执行已批子集」做成五个通用 {@link AgentTool}。工具只依赖 AgentLib 的决策协议与工具 SPI，不拥有
 * HTTP server、不 import 宿主的 Brain/Main 类型；装配层把同一个 {@link DecisionPackages}、写路径口 {@link
 * DecisionSubmissionPort} 与可选的在途包口传进来，即可注册到任意 MCP 工具面。
 *
 * <p><b>★ 调用者身份不从参数取</b>：模型可以伪造 {@code by} 字段，但工具只看 {@link ToolContext#identity()} 的实例 id。模型给的
 * {@code by} 从不读取、从不写进审计。身份没穿透时沿用 {@link AgentIdentity#UNKNOWN} 的中立 id，而不是编一个 人名。
 *
 * <p><b>★ 默认不泄露参数明文</b>：list 与 get 的概览档只返回包身份、状态、条目数、工具名、参数个数和摘要；{@link PackageItem#summary()}
 * 是由参数值渲染出来的一行，因此也<b>不</b>出现在任何输出里。只有显式 {@code full=true} 的 get 才给逐条 {@code args}，并且每次这样的读取都会调用
 * {@link DecisionReadAudit#detailRead(String, List, String)} 留下“谁看了哪些 index”的审计（审计口未装配时按接口契约不落审计）。
 *
 * <p><b>★ 写路径只有一条</b>：主包表的三个写工具全部经 {@link DecisionSubmissionPort}（整包表态 / 条目表态 / 执行）； 在途包（跨进程 {@code
 * xp-…}）不伪造第二个状态机，只走可选注入的 {@link InFlightDecisionActions}。主表未命中时才问在途口； 两个口都没有这个 id ⇒ 与“从未存在”同一个
 * {@code PACKAGE_NOT_FOUND}，不给存在性 oracle。
 *
 * <p><b>★ 错误边界</b>：所有可预期失败都映射成 {@link ToolResult#error(String, String)} 的稳定代码；异常不抛出 MCP
 * 边界。服务端意外只记录异常类名，不记录异常消息/堆栈/参数值，避免把明文带进日志。
 */
public final class DecisionPackageTools {

  /** 列包工具名。 */
  public static final String LIST_NAME = "decision_package_list";

  /** 看包工具名。 */
  public static final String GET_NAME = "decision_package_get";

  /** 整包表态工具名。 */
  public static final String DECIDE_NAME = "decision_package_decide";

  /** 逐条表态工具名。 */
  public static final String DECIDE_ITEM_NAME = "decision_package_decide_item";

  /** 执行命令工具名。 */
  public static final String COMMIT_NAME = "decision_package_commit";

  /** 五个工具名的不可变集合（便于装配层做白名单/目录核对）。 */
  public static final Set<String> TOOL_NAMES =
      Set.of(LIST_NAME, GET_NAME, DECIDE_NAME, DECIDE_ITEM_NAME, COMMIT_NAME);

  /** 参数/输入错误（稳定错误码；错误消息只描述形状，不回显参数值）。 */
  public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";

  /** 决策动作落定时的服务端意外（稳定错误码；不把异常消息透给模型）。 */
  public static final String DECISION_TOOL_FAILED = "DECISION_TOOL_FAILED";

  private static final Logger LOG = LoggerFactory.getLogger(DecisionPackageTools.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String APPROVE = "approve";
  private static final String REJECT = "reject";
  private static final String UNKNOWN_BY = "unknown";

  private final DecisionPackages packages;
  private final DecisionSubmissionPort submissions;
  private final InFlightDecisionPackages inFlight;
  private final InFlightDecisionActions inFlightActions;
  private final DecisionReadAudit readAudit;
  private final Set<String> approverRoles;
  private final List<AgentTool> tools;

  /**
   * 最小装配：只有包表、写路径口与审批角色。
   *
   * @param packages 决策包登记表（必须与真正的待决面是同一个实例）
   * @param submissions 决策写路径口（实现方通常是宿主的决策包桥）
   * @param approverRoles 允许调用决策工具的宿主角色（不得为空；空集无法表达“谁能审批”）
   */
  public DecisionPackageTools(
      DecisionPackages packages, DecisionSubmissionPort submissions, Set<String> approverRoles) {
    this(packages, submissions, approverRoles, null, null, null);
  }

  /**
   * 全参装配：在主口之外接上在途包只读口、在途包写口与明文读取审计口。
   *
   * @param packages 决策包登记表
   * @param submissions 主包表的写路径口
   * @param approverRoles 允许调用决策工具的宿主角色（不得为空）
   * @param inFlight 在途包只读口（{@code null} = 没有这个口）
   * @param inFlightActions 在途包写口（{@code null} = 在途包不可写）
   * @param readAudit 明文读取审计口（{@code null} = 不落审计；接口允许的降级）
   */
  public DecisionPackageTools(
      DecisionPackages packages,
      DecisionSubmissionPort submissions,
      Set<String> approverRoles,
      InFlightDecisionPackages inFlight,
      InFlightDecisionActions inFlightActions,
      DecisionReadAudit readAudit) {
    this.packages = Objects.requireNonNull(packages, "packages");
    this.submissions = Objects.requireNonNull(submissions, "submissions");
    if (approverRoles == null || approverRoles.isEmpty()) {
      throw new IllegalArgumentException("approverRoles 不得为空：决策工具必须绑定至少一个审批角色（空集不能默认可被任何人调用）");
    }
    for (String role : approverRoles) {
      if (role == null || role.isBlank()) {
        throw new IllegalArgumentException("approverRoles 不得包含 null/空白角色名");
      }
    }
    this.approverRoles = Set.copyOf(approverRoles);
    this.inFlight = inFlight;
    this.inFlightActions = inFlightActions;
    this.readAudit = readAudit;

    List<AgentTool> built = new ArrayList<>(5);
    built.add(
        tool(
            LIST_NAME,
            "列出待裁决与已批未执行的决策包。只返回概览（不含参数明文）；已批未执行单列一档。",
            objectSchema(Map.of(), List.of()),
            ToolSpec.level(AccessToken.DEFAULT, true, false, false),
            this::executeList));
    built.add(
        tool(
            GET_NAME,
            "查看一个决策包。默认概览，不含参数明文；只有显式 full=true 才返回逐条 args，并落明文读取审计。",
            objectSchema(
                properties(
                    "packageId", Map.of("type", "string", "description", "决策包 id"),
                    "full",
                        Map.of(
                            "type",
                            "boolean",
                            "description",
                            "true = 显式要求逐条参数明文（会写审计）；缺省/ false = 概览")),
                List.of("packageId")),
            ToolSpec.level(AccessToken.DEFAULT, true, false, false),
            this::executeGet));
    built.add(
        tool(
            DECIDE_NAME,
            "对决策包做一次整包表态（不执行）。可给 approvedIndexes 做逐条例外；缺席或空数组 = 全驳。执行请显式调 " + COMMIT_NAME + "。",
            objectSchema(
                properties(
                    "packageId", Map.of("type", "string", "description", "决策包 id"),
                    "approvedIndexes",
                        Map.of(
                            "type",
                            "array",
                            "items",
                            Map.of("type", "integer", "minimum", 0),
                            "description",
                            "要批准的稳定条目 index；缺省/空数组 = 全部驳回（缺席 = 驳回）"),
                    "note", Map.of("type", "string", "description", "可选：一行理由")),
                List.of("packageId")),
            ToolSpec.level(AccessToken.DEFAULT, true, false, false),
            this::executeDecide));
    built.add(
        tool(
            DECIDE_ITEM_NAME,
            "对决策包的一条条目表态（approve/reject）。这是同一次裁决的分段表达：不产生第二个包，也不会自动执行。",
            objectSchema(
                properties(
                    "packageId", Map.of("type", "string", "description", "决策包 id"),
                    "index", Map.of("type", "integer", "minimum", 0, "description", "条目的稳定 index"),
                    "verdict",
                        Map.of(
                            "type",
                            "string",
                            "enum",
                            List.of(APPROVE, REJECT),
                            "description",
                            "approve = 批准，reject = 驳回"),
                    "note", Map.of("type", "string", "description", "可选：一行理由")),
                List.of("packageId", "index", "verdict")),
            ToolSpec.level(AccessToken.DEFAULT, true, false, false),
            this::executeDecideItem));
    built.add(
        tool(
            COMMIT_NAME,
            "执行一个已裁决的决策包：把被批子集恰好一次交给宿主的提交口。重复调用应取得同一份回执。",
            objectSchema(
                properties("packageId", Map.of("type", "string", "description", "要执行的决策包 id")),
                List.of("packageId")),
            ToolSpec.level(AccessToken.DEFAULT, true, true, false),
            this::executeCommit));
    this.tools = List.copyOf(built);
  }

  /** 便捷工厂：等价于三参构造。 */
  public static DecisionPackageTools of(
      DecisionPackages packages, DecisionSubmissionPort submissions, Set<String> approverRoles) {
    return new DecisionPackageTools(packages, submissions, approverRoles);
  }

  /** 静态工厂：直接返回五个工具的不可变列表（三参装配）。 */
  public static List<AgentTool> all(
      DecisionPackages packages, DecisionSubmissionPort submissions, Set<String> approverRoles) {
    return new DecisionPackageTools(packages, submissions, approverRoles).tools();
  }

  /** 静态工厂：直接返回五个工具的不可变列表（全参装配）。 */
  public static List<AgentTool> all(
      DecisionPackages packages,
      DecisionSubmissionPort submissions,
      Set<String> approverRoles,
      InFlightDecisionPackages inFlight,
      InFlightDecisionActions inFlightActions,
      DecisionReadAudit readAudit) {
    return new DecisionPackageTools(
            packages, submissions, approverRoles, inFlight, inFlightActions, readAudit)
        .tools();
  }

  /** 便捷工厂：等价于全参构造。 */
  public static DecisionPackageTools of(
      DecisionPackages packages,
      DecisionSubmissionPort submissions,
      Set<String> approverRoles,
      InFlightDecisionPackages inFlight,
      InFlightDecisionActions inFlightActions,
      DecisionReadAudit readAudit) {
    return new DecisionPackageTools(
        packages, submissions, approverRoles, inFlight, inFlightActions, readAudit);
  }

  /** 五个工具的不可变列表（顺序 = list/get/decide/decideItem/commit）。 */
  public List<AgentTool> tools() {
    return tools;
  }

  /** {@link #all()} 的静态别名：等价于 {@code new ... .tools()}。 */
  public static List<AgentTool> tools(
      DecisionPackages packages, DecisionSubmissionPort submissions, Set<String> approverRoles) {
    return all(packages, submissions, approverRoles);
  }

  /** {@link #tools()} 的别名，便于宿主按“all”惯例装配。 */
  public List<AgentTool> all() {
    return tools;
  }

  /** 列包工具。 */
  public AgentTool listTool() {
    return tools.get(0);
  }

  /** 看包工具。 */
  public AgentTool getTool() {
    return tools.get(1);
  }

  /** 整包表态工具。 */
  public AgentTool decideTool() {
    return tools.get(2);
  }

  /** 逐条表态工具。 */
  public AgentTool decideItemTool() {
    return tools.get(3);
  }

  /** 执行命令工具。 */
  public AgentTool commitTool() {
    return tools.get(4);
  }

  private ToolResult executeList(ToolContext context) throws JsonProcessingException {
    List<DecisionPackage> pending = new ArrayList<>();
    List<DecisionPackage> awaitingCommit = new ArrayList<>();
    Map<String, Boolean> seen = new LinkedHashMap<>();

    for (DecisionPackage pkg : packages.pending()) {
      if (seen.putIfAbsent(pkg.id(), Boolean.TRUE) == null) {
        pending.add(pkg);
      }
    }
    for (DecisionPackage pkg : packages.all()) {
      if (seen.containsKey(pkg.id())) {
        continue;
      }
      if (pkg.state() == PackageState.DECIDING) {
        pending.add(pkg);
        seen.put(pkg.id(), Boolean.TRUE);
      } else if (pkg.state() == PackageState.DECIDED) {
        awaitingCommit.add(pkg);
        seen.put(pkg.id(), Boolean.TRUE);
      }
    }
    // 在途包是列表的另一来源：主表命中过同一个 id 时只保留主表那条，避免重复/两套真相。
    for (DecisionPackage pkg : inFlightList()) {
      if (seen.containsKey(pkg.id())) {
        continue;
      }
      if (pkg.state() == PackageState.PROPOSED || pkg.state() == PackageState.DECIDING) {
        pending.add(pkg);
        seen.put(pkg.id(), Boolean.TRUE);
      } else if (pkg.state() == PackageState.DECIDED) {
        awaitingCommit.add(pkg);
        seen.put(pkg.id(), Boolean.TRUE);
      }
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("pending", overviewRows(pending));
    body.put("awaitingCommit", overviewRows(awaitingCommit));
    return ToolResult.ok(JSON.writeValueAsString(body));
  }

  private ToolResult executeGet(ToolContext context) throws JsonProcessingException {
    String packageId = requiredText(context, "packageId");
    boolean full = fullFlag(context);
    Optional<LocatedPackage> found = findReadable(packageId, full);
    if (found.isEmpty()) {
      return packageNotFound();
    }
    LocatedPackage located = found.get();
    String by = callerOf(context);
    if (full && readAudit != null) {
      auditDetailRead(packageId, located.pkg(), by);
    }
    List<DecisionPackages.ItemVerdict> verdicts = verdictsOf(located);
    Map<String, Object> body = detailRow(located, verdicts, full);
    return ToolResult.ok(JSON.writeValueAsString(body));
  }

  private ToolResult executeDecide(ToolContext context) throws JsonProcessingException {
    String packageId = requiredText(context, "packageId");
    Set<Integer> approved = approvedIndexes(context);
    String note = optionalText(context, "note");
    String by = callerOf(context);

    Optional<DecisionPackage> main = packages.find(packageId);
    if (main.isPresent()) {
      List<Integer> unknown = unknownApprovedIndexes(main.get(), approved);
      if (!unknown.isEmpty()) {
        return ToolResult.error(
            DecisionPackages.INVALID_DECISION, "approvedIndexes 引用了包内不存在的条目 index=" + unknown);
      }
      DecisionSubmission submission =
          submissions.submitDecision(packageId, new Decision(approved, by, note));
      return ToolResult.ok(JSON.writeValueAsString(receiptRow(submission, "decide")));
    }

    Optional<DecisionPackage> inFlightPackage = findInFlight(packageId, false);
    if (inFlightActions == null || inFlightPackage.isEmpty()) {
      return packageNotFound();
    }
    DecisionPackage pkg = inFlightPackage.get();
    List<Integer> unknown = unknownApprovedIndexes(pkg, approved);
    if (!unknown.isEmpty()) {
      return ToolResult.error(
          DecisionPackages.INVALID_DECISION, "approvedIndexes 引用了包内不存在的条目 index=" + unknown);
    }

    DecisionSubmission last = null;
    for (PackageItem item : pkg.items()) {
      last =
          inFlightActions.itemDecided(
              packageId, item.index(), approved.contains(item.index()), by, note);
    }
    if (last == null) {
      return ToolResult.error(DecisionPackages.PACKAGE_NOT_FOUND, "在途决策包没有可表态的条目");
    }
    return ToolResult.ok(JSON.writeValueAsString(receiptRow(last, "decide")));
  }

  private ToolResult executeDecideItem(ToolContext context) throws JsonProcessingException {
    String packageId = requiredText(context, "packageId");
    int index = requiredInt(context, "index");
    boolean approve = verdictOf(requiredText(context, "verdict"));
    String note = optionalText(context, "note");
    String by = callerOf(context);

    Optional<DecisionPackage> main = packages.find(packageId);
    if (main.isPresent()) {
      if (itemOf(main.get(), index) == null) {
        return ToolResult.error(DecisionPackages.INVALID_ITEM_INDEX, "该包里没有这个条目 index=" + index);
      }
      DecisionSubmission submission = submissions.itemDecided(packageId, index, approve, by, note);
      return ToolResult.ok(JSON.writeValueAsString(receiptRow(submission, "itemDecided")));
    }
    if (inFlightActions == null) {
      return packageNotFound();
    }
    if (inFlight != null && findInFlight(packageId, false).isEmpty()) {
      return packageNotFound();
    }
    DecisionSubmission submission =
        inFlightActions.itemDecided(packageId, index, approve, by, note);
    return ToolResult.ok(JSON.writeValueAsString(receiptRow(submission, "itemDecided")));
  }

  private ToolResult executeCommit(ToolContext context) throws JsonProcessingException {
    String packageId = requiredText(context, "packageId");
    String by = callerOf(context);

    if (packages.find(packageId).isPresent()) {
      DecisionSubmission submission = submissions.commitPackage(packageId, by);
      return ToolResult.ok(JSON.writeValueAsString(receiptRow(submission, "commit")));
    }
    if (inFlightActions == null) {
      return packageNotFound();
    }
    if (inFlight != null && findInFlight(packageId, false).isEmpty()) {
      return packageNotFound();
    }
    DecisionSubmission submission = inFlightActions.commitPackage(packageId, by);
    return ToolResult.ok(JSON.writeValueAsString(receiptRow(submission, "commit")));
  }

  private static List<Integer> unknownApprovedIndexes(DecisionPackage pkg, Set<Integer> approved) {
    Set<Integer> known = new LinkedHashSet<>();
    for (PackageItem item : pkg.items()) {
      known.add(item.index());
    }
    return approved.stream().filter(index -> !known.contains(index)).sorted().toList();
  }

  private static PackageItem itemOf(DecisionPackage pkg, int index) {
    for (PackageItem item : pkg.items()) {
      if (item.index() == index) {
        return item;
      }
    }
    return null;
  }

  private Optional<LocatedPackage> findReadable(String packageId, boolean full) {
    Optional<DecisionPackage> main = packages.find(packageId);
    if (main.isPresent()) {
      return Optional.of(new LocatedPackage(main.get(), true));
    }
    return findInFlight(packageId, full).map(pkg -> new LocatedPackage(pkg, false));
  }

  private Optional<DecisionPackage> findInFlight(String packageId, boolean full) {
    if (inFlight == null) {
      return Optional.empty();
    }
    try {
      Optional<DecisionPackage> found = inFlight.find(packageId, full);
      return found == null ? Optional.empty() : found;
    } catch (RuntimeException failure) {
      LOG.warn("读取在途决策包失败（按未知 id 处理） exceptionType={}", failure.getClass().getName());
      return Optional.empty();
    }
  }

  private List<DecisionPackage> inFlightList() {
    if (inFlight == null) {
      return List.of();
    }
    try {
      List<DecisionPackage> rows = inFlight.list();
      return rows == null ? List.of() : rows;
    } catch (RuntimeException failure) {
      LOG.warn("列在途决策包失败（按空列表处理） exceptionType={}", failure.getClass().getName());
      return List.of();
    }
  }

  private List<DecisionPackages.ItemVerdict> verdictsOf(LocatedPackage located) {
    if (located.main()) {
      return packages.itemVerdicts(located.pkg().id());
    }
    if (inFlightActions == null) {
      return List.of();
    }
    try {
      List<DecisionPackages.ItemVerdict> verdicts =
          inFlightActions.itemVerdicts(located.pkg().id());
      return verdicts == null ? List.of() : verdicts;
    } catch (RuntimeException failure) {
      LOG.warn("读取在途包表态失败（按未表态处理） exceptionType={}", failure.getClass().getName());
      return List.of();
    }
  }

  private void auditDetailRead(String packageId, DecisionPackage pkg, String by) {
    List<Integer> indexes = new ArrayList<>(pkg.items().size());
    for (PackageItem item : pkg.items()) {
      indexes.add(item.index());
    }
    try {
      readAudit.detailRead(packageId, List.copyOf(indexes), by);
    } catch (RuntimeException failure) {
      LOG.warn("决策包明文读取审计失败（不影响读取） exceptionType={}", failure.getClass().getName());
    }
  }

  private List<Map<String, Object>> overviewRows(List<DecisionPackage> packagesToRender) {
    List<Map<String, Object>> rows = new ArrayList<>(packagesToRender.size());
    for (DecisionPackage pkg : packagesToRender) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", pkg.id());
      row.put("proposerKey", pkg.proposerKey());
      row.put("state", pkg.state().name());
      row.put("itemCount", pkg.items().size());
      row.put("createdAtEpochMs", pkg.createdAtEpochMs());
      row.put("deadlineEpochMs", pkg.deadlineEpochMs());
      row.put("items", itemOverviewRows(pkg));
      rows.add(row);
    }
    return rows;
  }

  private Map<String, Object> detailRow(
      LocatedPackage located, List<DecisionPackages.ItemVerdict> verdicts, boolean full) {
    DecisionPackage pkg = located.pkg();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", pkg.id());
    row.put("proposerKey", pkg.proposerKey());
    row.put("caller", pkg.caller().name());
    row.put("state", pkg.state().name());
    row.put("itemCount", pkg.items().size());
    row.put("createdAtEpochMs", pkg.createdAtEpochMs());
    row.put("deadlineEpochMs", pkg.deadlineEpochMs());
    row.put("decision", decisionRow(pkg.decision()));
    row.put("verdicts", verdictRows(verdicts));
    row.put("undecided", undecidedIndexes(pkg, verdicts));
    if (located.main()) {
      packages.expiryReason(pkg.id()).ifPresent(reason -> row.put("expiryReason", reason));
    }
    row.put("items", itemRows(pkg, verdicts, full));
    return row;
  }

  private List<Map<String, Object>> itemOverviewRows(DecisionPackage pkg) {
    List<Map<String, Object>> rows = new ArrayList<>(pkg.items().size());
    for (PackageItem item : pkg.items()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("index", item.index());
      row.put("toolName", item.toolName());
      row.put("argCount", item.args().size());
      row.put("digest", item.argsDigest());
      rows.add(row);
    }
    return rows;
  }

  private List<Map<String, Object>> itemRows(
      DecisionPackage pkg, List<DecisionPackages.ItemVerdict> verdicts, boolean full) {
    Map<Integer, DecisionPackages.ItemVerdict> byIndex = indexVerdicts(verdicts);
    List<Map<String, Object>> rows = new ArrayList<>(pkg.items().size());
    for (PackageItem item : pkg.items()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("index", item.index());
      row.put("toolName", item.toolName());
      row.put("argCount", item.args().size());
      row.put("digest", item.argsDigest());
      if (full) {
        // 明文只走这一个显式分支；summary 是参数值派生文本，任何档位都不输出。
        row.put("args", item.args());
      }
      DecisionPackages.ItemVerdict verdict = byIndex.get(item.index());
      row.put("verdict", verdict == null ? null : verdict.approved() ? APPROVE : REJECT);
      row.put("decidedBy", verdict == null ? null : verdict.by());
      row.put("decisionNote", verdict == null || verdict.note().isBlank() ? null : verdict.note());
      rows.add(row);
    }
    return rows;
  }

  private static Map<String, Object> decisionRow(Decision decision) {
    if (decision == null) {
      return null;
    }
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("approvedIndexes", decision.approvedIndexes().stream().sorted().toList());
    row.put("by", decision.by());
    row.put("note", decision.note());
    return row;
  }

  private static List<Map<String, Object>> verdictRows(
      List<DecisionPackages.ItemVerdict> verdicts) {
    List<DecisionPackages.ItemVerdict> sorted = new ArrayList<>(verdicts);
    sorted.sort((left, right) -> Integer.compare(left.index(), right.index()));
    List<Map<String, Object>> rows = new ArrayList<>(sorted.size());
    for (DecisionPackages.ItemVerdict verdict : sorted) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("index", verdict.index());
      row.put("verdict", verdict.approved() ? APPROVE : REJECT);
      row.put("by", verdict.by());
      row.put("note", verdict.note());
      rows.add(row);
    }
    return rows;
  }

  private static Map<Integer, DecisionPackages.ItemVerdict> indexVerdicts(
      List<DecisionPackages.ItemVerdict> verdicts) {
    Map<Integer, DecisionPackages.ItemVerdict> byIndex = new LinkedHashMap<>();
    for (DecisionPackages.ItemVerdict verdict : verdicts) {
      byIndex.put(verdict.index(), verdict);
    }
    return byIndex;
  }

  private static List<Integer> undecidedIndexes(
      DecisionPackage pkg, List<DecisionPackages.ItemVerdict> verdicts) {
    Set<Integer> decided = new LinkedHashSet<>();
    for (DecisionPackages.ItemVerdict verdict : verdicts) {
      decided.add(verdict.index());
    }
    List<Integer> undecided = new ArrayList<>();
    for (PackageItem item : pkg.items()) {
      if (!decided.contains(item.index())) {
        undecided.add(item.index());
      }
    }
    return undecided;
  }

  private static Map<String, Object> receiptRow(DecisionSubmission submission, String step) {
    DecisionPackage pkg = submission.pkg();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("step", step);
    body.put("packageId", pkg.id());
    body.put("state", pkg.state().name());
    body.put("itemCount", pkg.items().size());
    body.put("approved", approvedIndexes(pkg));
    body.put("rejected", rejectedIndexes(pkg));
    CommitReceipt receipt = submission.receipt();
    if (receipt == null) {
      body.put("commit", null);
      body.put("warning", submission.commitNote());
      return body;
    }
    Map<String, Object> commit = new LinkedHashMap<>();
    commit.put("status", receipt.status().name());
    commit.put("revisionRef", receipt.revisionRef() == null ? "" : receipt.revisionRef());
    commit.put("reason", receipt.reason());
    List<Map<String, Object>> itemResults = new ArrayList<>(receipt.itemResults().size());
    for (CommitReceipt.ItemResult result : receipt.itemResults()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("outcome", result.outcome().name());
      row.put("reason", result.reason());
      itemResults.add(row);
    }
    commit.put("itemResults", itemResults);
    body.put("commit", commit);
    return body;
  }

  private static List<Integer> approvedIndexes(DecisionPackage pkg) {
    if (pkg.decision() == null) {
      return List.of();
    }
    List<Integer> indexes = new ArrayList<>();
    for (PackageItem item : pkg.items()) {
      if (pkg.decision().approves(item.index())) {
        indexes.add(item.index());
      }
    }
    return indexes;
  }

  private static List<Integer> rejectedIndexes(DecisionPackage pkg) {
    if (pkg.decision() == null) {
      return List.of();
    }
    List<Integer> indexes = new ArrayList<>();
    for (PackageItem item : pkg.items()) {
      if (!pkg.decision().approves(item.index())) {
        indexes.add(item.index());
      }
    }
    return indexes;
  }

  private AgentTool tool(
      String name, String description, Map<String, Object> schema, ToolSpec spec, ToolBody body) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return description;
      }

      @Override
      public Map<String, Object> jsonSchema() {
        // 工具目录是只读投影：返回浅拷贝，避免调用方拿到构造期内部表。
        return Map.copyOf(schema);
      }

      @Override
      public ToolSpec spec() {
        return spec;
      }

      @Override
      public Set<String> roles() {
        return Set.copyOf(approverRoles);
      }

      @Override
      public ToolResult execute(ToolContext context) {
        try {
          return body.execute(context);
        } catch (DecisionPackages.RejectionException refusal) {
          String refusalMessage =
              refusal.getMessage() == null || refusal.getMessage().isBlank()
                  ? "决策操作被拒绝"
                  : refusal.getMessage();
          return ToolResult.error(refusal.code(), refusalMessage);
        } catch (BadArgumentsException bad) {
          return ToolResult.error(INVALID_ARGUMENTS, bad.getMessage());
        } catch (JsonProcessingException serializationFailure) {
          LOG.error("决策包工具结果序列化失败 tool={}", name);
          return ToolResult.error(DECISION_TOOL_FAILED, "决策包结果序列化失败");
        } catch (IllegalArgumentException badInternalValue) {
          // 不把异常消息透出去：它可能来自宿主实现并带任意值。
          LOG.error("决策包工具收到非法内部值 tool={}", name);
          return ToolResult.error(INVALID_ARGUMENTS, "参数非法");
        } catch (RuntimeException failure) {
          LOG.error("决策包工具执行失败 tool={} exceptionType={}", name, failure.getClass().getName());
          return ToolResult.error(DECISION_TOOL_FAILED, "决策包操作失败（服务端异常）");
        }
      }
    };
  }

  private static Map<String, Object> objectSchema(
      Map<String, Object> properties, List<String> required) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    schema.put("required", required);
    return schema;
  }

  private static Map<String, Object> properties(Object... keyValues) {
    Map<String, Object> properties = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      properties.put((String) keyValues[i], keyValues[i + 1]);
    }
    return properties;
  }

  private static String callerOf(ToolContext context) {
    AgentIdentity identity = context.identity();
    if (identity == null || identity.instanceId() == null || identity.instanceId().isBlank()) {
      return UNKNOWN_BY;
    }
    return identity.instanceId();
  }

  private static String requiredText(ToolContext context, String name) {
    Object raw = context.arguments().get(name);
    if (!(raw instanceof String text) || text.isBlank()) {
      throw new BadArgumentsException(name + " 必填且必须是非空字符串");
    }
    return text.strip();
  }

  private static String optionalText(ToolContext context, String name) {
    Object raw = context.arguments().get(name);
    if (raw == null) {
      return "";
    }
    if (!(raw instanceof String text)) {
      throw new BadArgumentsException(name + " 必须是字符串");
    }
    return text.strip();
  }

  private static int requiredInt(ToolContext context, String name) {
    Object raw = context.arguments().get(name);
    if (!(raw instanceof Number number)) {
      throw new BadArgumentsException(name + " 必填且必须是整数");
    }
    if (!(raw instanceof Integer
        || raw instanceof Long
        || raw instanceof Short
        || raw instanceof Byte)) {
      throw new BadArgumentsException(name + " 必须是整数");
    }
    long value = number.longValue();
    if (value < 0 || value > Integer.MAX_VALUE) {
      throw new BadArgumentsException(name + " 必须是非负 32 位整数");
    }
    return (int) value;
  }

  private static Set<Integer> approvedIndexes(ToolContext context) {
    Object raw = context.arguments().get("approvedIndexes");
    if (raw == null) {
      return Set.of();
    }
    if (!(raw instanceof List<?> values)) {
      throw new BadArgumentsException("approvedIndexes 必须是整数数组");
    }
    Set<Integer> approved = new LinkedHashSet<>();
    for (Object value : values) {
      if (!(value instanceof Number number)
          || !(value instanceof Integer
              || value instanceof Long
              || value instanceof Short
              || value instanceof Byte)) {
        throw new BadArgumentsException("approvedIndexes 的元素必须是非负整数");
      }
      long index = number.longValue();
      if (index < 0 || index > Integer.MAX_VALUE) {
        throw new BadArgumentsException("approvedIndexes 的元素必须是非负 32 位整数");
      }
      if (!approved.add((int) index)) {
        throw new BadArgumentsException("approvedIndexes 不得包含重复 index");
      }
    }
    return Set.copyOf(approved);
  }

  private static boolean verdictOf(String verdict) {
    if (APPROVE.equals(verdict)) {
      return true;
    }
    if (REJECT.equals(verdict)) {
      return false;
    }
    throw new BadArgumentsException("verdict 只接受 approve|reject");
  }

  private static boolean fullFlag(ToolContext context) {
    Object raw = context.arguments().get("full");
    if (raw == null) {
      return false;
    }
    if (raw instanceof Boolean value) {
      return value;
    }
    if (raw instanceof String text) {
      String value = text.strip();
      if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
        return true;
      }
      if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
        return false;
      }
    }
    throw new BadArgumentsException("full 必须是布尔值");
  }

  private static ToolResult packageNotFound() {
    return ToolResult.error(DecisionPackages.PACKAGE_NOT_FOUND, "未知的决策包 id");
  }

  private record LocatedPackage(DecisionPackage pkg, boolean main) {}

  @FunctionalInterface
  private interface ToolBody {
    ToolResult execute(ToolContext context) throws JsonProcessingException;
  }

  private static final class BadArgumentsException extends IllegalArgumentException {
    private BadArgumentsException(String message) {
      super(message);
    }
  }
}
