package io.mosire.agentlib.decision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.mosire.agentlib.approval.ApprovalHttpEndpoint;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 决策包的<b>决策面 HTTP 端点</b>（设计 §5.10② + §5.13(e)(f)）：未决包列表、包详情（逐条<b>明文</b>）、提交统一裁决。
 *
 * <p>端点（与既有审批面<b>同端口、同安全基线</b>——用 {@link ApprovalHttpEndpoint#createContext(String, HttpHandler)}
 * 挂在<b>同一台</b> server 上，<b>不</b>另起第二台 server/端口）：
 *
 * <ul>
 *   <li>{@code GET /api/decision-packages} → {@code
 *       {"pending":[{id,proposerKey,itemCount,items:[{index,toolName,summary}],createdAtEpochMs,deadlineEpochMs}]}}
 *       ——未决（{@code PROPOSED}）包的快照，<b>不含参数</b>；
 *   <li>{@code GET /api/decision-packages/{id}} → 包详情：逐条 {@code
 *       {index,toolName,summary,args}}，<b>{@code args} 是明文</b>。这是用户 2026-10-16
 *       的明确要求（原话「肯定展示全部内容啊」）：人要看清自己批的是什么， 而脱敏摘要（{@code argsDigest}）回答不了"这条到底改什么"。明文<b>只</b>经本面（回环
 *       HTTP）出面——不进事件库、不进 {@code ApprovalRequest}、不进日志；
 *       <p><b>★ 详情的两级取数（P3e）</b>：先查 {@link DecisionPackages#find(String)}，未命中再查<b>在途取数口</b> {@link
 *       InFlightDecisionPackages}——跨进程那条路的临时包（{@code xp-…}，建即裁、不进包登记表，见设计 §5sexies）
 *       由它回答，逐条仍是同一形状（{@code args} 是父侧自己收到的明文）。★ 这条回退<b>只服务 GET</b>：{@code POST}
 *       与列表都不认在途包（它<b>不是</b>决策单元，不构成第二条决策路径——不变量 1）；
 *   <li>{@code POST /api/decision-packages/{id}} → body {@code {approveAll:true | rejectAll:true |
 *       items:{index:"approve"|"reject"}, by, note}}：成功 {@code 200} +
 *       回执（含<b>实际生效</b>的裁决与逐条去向）；未知/已过期 id {@code 404}；重复决议或不可决议状态 {@code 409}；坏 body {@code
 *       400}；方法不符 {@code 405}（带 {@code Allow} 头，同既有惯例）。
 * </ul>
 *
 * <p><b>★ 写路径必须经桥</b>（设计 §5.13(e)）：{@code POST} 调的是注入的 {@link DecisionSubmissionPort}（实现方 = 决策包桥），
 * <b>不</b>直调 {@link DecisionPackages#decide(String, Decision)}。直调会让 {@code
 * decision.package.decided} 不落、 提交口不被调、{@code COMMITTED}/{@code CONFLICTED} 永不产生，而 HTTP 面还会回报
 * 200——那是最坏的一类假成功。 读路径（列表/详情）走 {@link DecisionPackages#pending()}/{@link
 * DecisionPackages#find(String)}：读不是写， 它不改变任何状态机状态。
 *
 * <p><b>裁决形态三选一</b>：
 *
 * <ul>
 *   <li>{@code "approveAll":true} —— 整包批（条目集合由<b>包自己</b>回答：处理器只把 id 对应的 index 展开成显式集合，
 *       发编号之后包内条目不可变，所以这里不存在"与包内容不一致的第二处真相"）；
 *   <li>{@code "rejectAll":true} —— 整包驳（零落盘，包以 {@code REJECTED} 终局）；
 *   <li>{@code "items":{"0":"approve","2":"reject"}} —— 逐条例外：<b>只有列出 {@code approve} 的条目被批</b>，
 *       没列出的、以及列成 {@code reject} 的，一律按被驳处理（与 {@link Decision#approvedIndexes()} 的 fail-closed
 *       方向一致：缺席 = 驳回，不是放行）。{@code items} 为空对象是非法 body（要整包驳请用 {@code rejectAll}）——
 *       不让"我一条都没勾"和"我全驳"长得一样。
 * </ul>
 *
 * <p>{@code by} 只是审计注记（原样进 {@link Decision#by()} 与回执，<b>不参与</b>任何判定——不存在"body 里自称 root
 * 所以放行"这类路径），缺省填 {@code "http"}（本通道名）。{@code note} 同理，只进 {@link Decision#note()}（内存态提示面）。
 *
 * <p><b>过期不会被伪装成不存在</b>：详情面对已到点的包回 {@code 200} + {@code state=EXPIRED}（它查得到，且状态说得清 为什么没落盘）；只有
 * {@code POST} 把"未知"与"已过期"一并回 {@code 404}（设计 §5.11 第 11 条）， 并由响应体里的 {@code code} 把两者分开。
 *
 * <p><b>判定顺序（先查后决，同审批面口径）</b>：{@code POST} 上 {@code find(id)} 为空 ⇒ 直接 {@code
 * 404}（从未存在；在途口<b>不</b>参与写路径）； {@code GET} 详情多一步在途回退（见 {@link #handleDetail}）；有值 ⇒ 解析 body ⇒
 * 交桥回填。桥抛的状态机具名拒按 {@code code} 映射状态码（{@code PACKAGE_NOT_FOUND}/{@code PACKAGE_EXPIRED} ⇒ 404、
 * {@code PACKAGE_NOT_PROPOSED} ⇒ 409、{@code INVALID_DECISION} ⇒ 400）；<b>认不出的拒码 ⇒ 500</b>
 * （那是缺陷，不该伪装成"客户端写错了"）。提交侧的失败（{@code COMMIT_FAILED}）不走异常：裁决已生效、包只是没落定， 回执里用 {@code warning} 说清楚。
 *
 * <p><b>明文纪律（本类的落点）</b>：只有详情/列表响应与回执带内容；日志只记 id / 状态 / 条数 / 裁决人，<b>一行明文都不打印</b>。
 */
public final class DecisionPackagesHttpHandler implements HttpHandler {

  private static final Logger LOG = LoggerFactory.getLogger(DecisionPackagesHttpHandler.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 决策面根路径（{@code GET} 列表；{@code GET}/{@code POST} + {@code /{id}} 详情与裁决）。 */
  public static final String PATH = "/api/decision-packages";

  /** 请求体上限（byte）：逐条例外最多 20 条 + 一行 note，给足余量后超限直接 400（不做流式解析）。 */
  private static final int MAX_BODY_BYTES = 65536;

  /** {@code by} 缺省值 = 本通道名（审计注记，不参与判定）。 */
  private static final String DEFAULT_BY = "http";

  private static final String APPROVE = "approve";
  private static final String REJECT = "reject";

  private final DecisionPackages packages;
  private final DecisionSubmissionPort submissions;

  /**
   * <b>上报到人的条目视图</b>（P2b，设计 §5quater ④）：包 id ⇒ 该包<b>还需要人裁</b>的条目 index（逐级预裁定跑完之后剩下的那些）。
   *
   * <p>{@code null}（缺省）= 没有这个视图 ⇒ 按"整包到人"渲染（本单之前的行为，逐字不变）。视图存在但某个包查不到 ⇒
   * 同样按"整包到人"（不知道就别过滤，宁可多显示也不静默藏条目）。
   */
  private final Function<String, Set<Integer>> escalatedIndexes;

  /**
   * <b>在途包取数口</b>（P3e，设计 §5sexies⑤★★）：{@code null}（缺省）= 没有这个口 ⇒ 详情面只认 {@link
   * DecisionPackages}（本单之前的行为，逐字不变）。接了它之后，{@code GET /{id}} 在主表未命中时再问它一次—— 跨进程临时包（{@code
   * xp-…}）因此也读得到逐条明文。<b>只有 GET 走这条回退</b>（见 {@link #handleDecide}）。
   */
  private final InFlightDecisionPackages inFlight;

  /**
   * @param packages 决策包登记表（装配层持有的<b>那一个</b>实例：HTTP 面看到的必须是待裁决面看到的同一个表）
   * @param submissions 裁决提交口（实现方 = 决策包桥；见 {@link DecisionSubmissionPort} 的"为什么不能直调状态机"）
   */
  public DecisionPackagesHttpHandler(
      DecisionPackages packages, DecisionSubmissionPort submissions) {
    this(packages, submissions, null, null);
  }

  /**
   * @param escalatedIndexes 上报视图（见字段注释；{@code null} = 不做过滤与标记）
   */
  public DecisionPackagesHttpHandler(
      DecisionPackages packages,
      DecisionSubmissionPort submissions,
      Function<String, Set<Integer>> escalatedIndexes) {
    this(packages, submissions, escalatedIndexes, null);
  }

  /**
   * 全参装配（P3e 新增）：在既有三件之外接上<b>在途包取数口</b>。
   *
   * @param inFlight 在途包取数口（{@code null} = 没有这个口，详情面只认包登记表）
   */
  public DecisionPackagesHttpHandler(
      DecisionPackages packages,
      DecisionSubmissionPort submissions,
      Function<String, Set<Integer>> escalatedIndexes,
      InFlightDecisionPackages inFlight) {
    this.packages = Objects.requireNonNull(packages, "packages");
    this.submissions = Objects.requireNonNull(submissions, "submissions");
    this.escalatedIndexes = escalatedIndexes;
    this.inFlight = inFlight;
  }

  /**
   * 把决策面挂到<b>同一台</b>审批 server 上（不另起第二台 server/端口；形态照 {@code CommandModeHttpHandler.register} 的先例）。
   *
   * <p>两个参数都<b>不得</b>为 null：缺了包登记表就答不出列表/详情，缺了提交口就会让 {@code POST} 变成"能批但不落"的假面。
   * 装配层若还没接上桥，就不要挂这个面——那比挂一个假面诚实。
   */
  public static void register(
      ApprovalHttpEndpoint endpoint,
      DecisionPackages packages,
      DecisionSubmissionPort submissions) {
    register(endpoint, packages, submissions, null, null);
  }

  /** 带"上报视图"的注册（P2b）：列表只列上报条目、逐条打 {@code escalated} 标记，见 {@link #escalatedIndexes}。 */
  public static void register(
      ApprovalHttpEndpoint endpoint,
      DecisionPackages packages,
      DecisionSubmissionPort submissions,
      Function<String, Set<Integer>> escalatedIndexes) {
    register(endpoint, packages, submissions, escalatedIndexes, null);
  }

  /**
   * 全参注册（P3e）：再带上<b>在途包取数口</b>——跨进程临时包因此也能在决策面上读到逐条明文（只读，见 {@link #inFlight}）。
   *
   * @param inFlight 在途包取数口（{@code null} = 不接）
   */
  public static void register(
      ApprovalHttpEndpoint endpoint,
      DecisionPackages packages,
      DecisionSubmissionPort submissions,
      Function<String, Set<Integer>> escalatedIndexes,
      InFlightDecisionPackages inFlight) {
    Objects.requireNonNull(endpoint, "endpoint");
    endpoint.createContext(
        PATH,
        new DecisionPackagesHttpHandler(
            Objects.requireNonNull(packages, "packages"),
            Objects.requireNonNull(submissions, "submissions"),
            escalatedIndexes,
            inFlight));
  }

  /** 路径后缀分发（同审批面 {@code handleApprovals} 的写法）：{@code ""} = 列表，{@code /{id}} = 详情或裁决。 */
  @Override
  public void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    if (path == null || !path.startsWith(PATH)) {
      send(exchange, 404, Map.of("error", "未知路径"));
      return;
    }
    String suffix = path.substring(PATH.length());
    if (suffix.isEmpty()) {
      if (!requireMethod(exchange, "GET")) {
        return;
      }
      handleList(exchange);
      return;
    }
    // 只认 "/{id}"：多余的路径段（/a/b）与裸斜杠（/）都不猜，一律 404
    if (suffix.length() < 2 || suffix.charAt(0) != '/' || suffix.indexOf('/', 1) >= 0) {
      send(exchange, 404, Map.of("error", "未知路径"));
      return;
    }
    String id = suffix.substring(1);
    switch (exchange.getRequestMethod()) {
      case "GET" -> handleDetail(exchange, id);
      case "POST" -> handleDecide(exchange, id);
      default -> methodNotAllowed(exchange, "GET, POST");
    }
  }

  /** 未决包快照：源就是登记表（不读副本——副本会与人的裁决分叉）；每项只带工具名与条目人读摘要，<b>不带参数</b>。 */
  private void handleList(HttpExchange exchange) throws IOException {
    send(exchange, 200, Map.of("pending", packageRows()));
  }

  /** 未决包列表行（{@link DecisionPackages#pending()} 的顺序 = 登记顺序）。 */
  private List<Map<String, Object>> packageRows() {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (DecisionPackage pkg : packages.pending()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", pkg.id());
      row.put("proposerKey", pkg.proposerKey());
      row.put("itemCount", pkg.items().size());
      Optional<Set<Integer>> escalated = escalatedOf(pkg);
      row.put("escalated", escalated.map(DecisionPackagesHttpHandler::sorted).orElse(null));
      // ★ P2b：列表是"人的工作清单" ⇒ 只列上报给他的条目（§5quater ④「决策面只列被上报的那些条目」）；
      //   没有上报视图（未装配级联 / 该包查不到）⇒ 照旧列全部（宁可多显示，不静默藏条目）
      row.put("items", itemRows(pkg, false, escalated.orElse(null)));
      row.put("createdAtEpochMs", pkg.createdAtEpochMs());
      row.put("deadlineEpochMs", pkg.deadlineEpochMs());
      rows.add(row);
    }
    return rows;
  }

  /**
   * 包详情：逐条 {@code {index,toolName,summary,args,escalated}}——{@code args} <b>明文</b>（用户 2026-10-16
   * 的要求）。
   *
   * <p><b>详情面刻意列全部条目</b>（P2b）：人对整包有知情权（"这个包里还有哪些是下级已经批掉的"是判断的依据）， 所以这里不做过滤，只逐条打 {@code escalated}
   * 标记（{@code true} = 这条还需要你裁）。过滤只发生在列表那条工作清单上。
   *
   * <p><b>两级取数（P3e）</b>：先 {@link DecisionPackages#find(String)}，未命中再问 {@link #inFlight}（在途的跨进程临时包，
   * {@code xp-…}）。★ 两级都<b>只读</b>：不建包、不改状态、不进列表；非在途/已结算 ⇒ 与"从未存在"同一个 {@code 404}。 ★ {@code POST}
   * <b>不</b>走这条回退（{@link #handleDecide} 仍只认包登记表）：在途包不是决策单元，不构成第二条决策路径（不变量 1）。
   *
   * <p>未知 id ⇒ {@code 404}；已到点的包 ⇒ {@code 200} + {@code state=EXPIRED}（过期不伪装成不存在）。
   */
  private void handleDetail(HttpExchange exchange, String id) throws IOException {
    Optional<DecisionPackage> found = packages.find(id);
    if (found.isEmpty()) {
      found = inFlightOf(id);
    }
    if (found.isEmpty()) {
      send(exchange, 404, Map.of("error", "未知的决策包 id"));
      return;
    }
    send(exchange, 200, detailRow(found.get()));
  }

  /**
   * 在途包的取数（P3e）：没接口 / 查不到 / 接口自己炸了 ⇒ 空值（照旧 {@code 404}）。
   *
   * <p>接口异常按<b>取不到</b>办：在途视图是"多给一条看全文的路"，它坏了不该让详情面变成 500——但也不该静默，落一行 WARN （同 {@link #escalatedOf}
   * 的口径）。
   */
  private Optional<DecisionPackage> inFlightOf(String id) {
    if (inFlight == null) {
      return Optional.empty();
    }
    Optional<DecisionPackage> found;
    try {
      found = inFlight.find(id);
    } catch (RuntimeException failure) {
      LOG.warn("取在途决策包失败（按未知 id 处理） packageId={}", id, failure);
      return Optional.empty();
    }
    return found == null ? Optional.empty() : found;
  }

  /** 该包还需要人裁的条目（见 {@link #escalatedIndexes}；没有视图 / 查不到 ⇒ 空 Optional = 不过滤）。 */
  private Optional<Set<Integer>> escalatedOf(DecisionPackage pkg) {
    if (escalatedIndexes == null) {
      return Optional.empty();
    }
    Set<Integer> indexes;
    try {
      indexes = escalatedIndexes.apply(pkg.id());
    } catch (RuntimeException failure) {
      LOG.warn("取上报视图失败（按「整包到人」渲染，不过滤） packageId={}", pkg.id(), failure);
      return Optional.empty();
    }
    return indexes == null ? Optional.empty() : Optional.of(indexes);
  }

  /** 详情行：包级身份与状态 + 逐条明文 + 已有裁决（用于"这个包后来怎么了"）。 */
  private Map<String, Object> detailRow(DecisionPackage pkg) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", pkg.id());
    row.put("proposerKey", pkg.proposerKey());
    row.put("caller", pkg.caller().name());
    row.put("itemCount", pkg.items().size());
    row.put("state", pkg.state().name());
    row.put("createdAtEpochMs", pkg.createdAtEpochMs());
    row.put("deadlineEpochMs", pkg.deadlineEpochMs());
    row.put("decision", decisionRow(pkg));
    // 详情面：列全部条目（人对整包有知情权），但逐条带 escalated 标记；没有上报视图（未装配级联）⇒ 全标 true
    row.put("items", itemRows(pkg, true, escalatedOf(pkg).orElse(null)));
    return row;
  }

  /**
   * 条目行：{@code plaintext == false} 时省略 {@code args}（列表面不带明文，只有详情与判定提示带）； {@code escalatedFilter} 非
   * null ⇒ 只列该集合里的条目（P2b 的列表过滤）。
   *
   * <p>{@code escalated} 标记：{@code true} = 这条还需要人裁（逐级预裁定之后剩下的），{@code false} = 已由某一级预裁定批掉。
   */
  private static List<Map<String, Object>> itemRows(
      DecisionPackage pkg, boolean plaintext, Set<Integer> escalatedFilter) {
    List<Map<String, Object>> items = new ArrayList<>(pkg.items().size());
    for (PackageItem item : pkg.items()) {
      boolean escalated = escalatedFilter == null || escalatedFilter.contains(item.index());
      if (escalatedFilter != null && !escalated) {
        continue;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("index", item.index());
      row.put("toolName", item.toolName());
      row.put("summary", item.summary());
      row.put("escalated", escalated);
      if (plaintext) {
        row.put("args", item.args());
      }
      items.add(row);
    }
    return items;
  }

  /** index 集合的稳定排序副本（列表响应里给人看的 {@code escalated} 字段；JSON 数组顺序稳定，便于肉眼对账）。 */
  private static List<Integer> sorted(Set<Integer> indexes) {
    List<Integer> sorted = new ArrayList<>(indexes);
    sorted.sort(Integer::compareTo);
    return List.copyOf(sorted);
  }

  /** 已生效的裁决（{@code null} = 还没人裁）：被批/被驳的 index 都<b>显式</b>给出，不让人自己推。 */
  private static Map<String, Object> decisionRow(DecisionPackage pkg) {
    Decision decision = pkg.decision();
    if (decision == null) {
      return null;
    }
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("approved", approvedIndexes(pkg));
    row.put("rejected", rejectedIndexes(pkg));
    row.put("by", decision.by());
    row.put("note", decision.note());
    return row;
  }

  /**
   * 人的裁决：先查后决（未知 id 直接 404）⇒ 解析 body ⇒ 交桥回填 ⇒ 回执。
   *
   * <p><b>★ 只认包登记表</b>（P3e 的边界）：{@link #inFlight} 那条在途口<b>不</b>参与 {@code POST}——在途的跨进程临时包 （{@code
   * xp-…}）是"父侧为一次提交现造的临时包"，它没有状态机、没有提交口、也不该被这条 HTTP 路裁决。 让它可裁决就等于给跨进程那条路开出第二条决策路径（不变量 1）。在途包 id
   * 在这里与未知 id 同归 {@code 404}。
   */
  private void handleDecide(HttpExchange exchange, String id) throws IOException {
    Optional<DecisionPackage> found = packages.find(id);
    if (found.isEmpty()) {
      send(exchange, 404, Map.of("error", "未知的决策包 id", "code", DecisionPackages.PACKAGE_NOT_FOUND));
      return;
    }
    Decision decision;
    try {
      decision = readDecision(exchange, found.get());
    } catch (BadBody badBody) {
      send(exchange, 400, Map.of("error", badBody.getMessage()));
      return;
    }
    DecisionSubmission submission;
    try {
      submission = submissions.submitDecision(id, decision);
    } catch (DecisionPackages.RejectionException refusal) {
      refuseByCode(exchange, refusal);
      return;
    } catch (RuntimeException failure) {
      // 兜底：编排/桥的意外异常不得让连接悬着，也不得被说成"客户端写错了"
      LOG.warn("决策面裁决提交异常（未落定） packageId={} 异常={}", id, failure.toString());
      send(exchange, 500, Map.of("error", "裁决提交失败（服务器侧异常）"));
      return;
    }
    LOG.info(
        "决策面收到裁决 packageId={} state={} approved={} rejected={} by={}",
        id,
        submission.pkg().state(),
        approvedIndexes(submission.pkg()).size(),
        rejectedIndexes(submission.pkg()).size(),
        decision.by());
    send(exchange, 200, receiptRow(submission));
  }

  /** 状态机具名拒 ⇒ HTTP 状态码（设计 §5.11 第 11 条）；认不出的码 ⇒ 500（缺陷不该伪装成客户端错误）。 */
  private void refuseByCode(HttpExchange exchange, DecisionPackages.RejectionException refusal)
      throws IOException {
    String code = refusal.code();
    int status =
        switch (code) {
          case DecisionPackages.PACKAGE_NOT_FOUND, DecisionPackages.PACKAGE_EXPIRED -> 404;
          case DecisionPackages.INVALID_DECISION -> 400;
          case DecisionPackages.PACKAGE_NOT_PROPOSED -> 409;
          default -> 500;
        };
    if (status == 500) {
      LOG.warn("决策面收到未映射的状态机拒码（按服务器侧缺陷处理） code={} 理由={}", code, refusal.getMessage());
    }
    String message =
        switch (status) {
          case 404 -> "未知或已失效的决策包 id（含：已超时作废）";
          case 400 -> "裁决不合法：" + refusal.getMessage();
          case 409 -> "该包已裁决或已终局（一次终局，重复决议不覆盖首次）";
          default -> "裁决未生效（服务器侧异常）";
        };
    send(exchange, status, Map.of("error", message, "code", code));
  }

  /** 裁决回执：实际生效的裁决 + 逐条去向；没有回执时用 {@code warning} 说清为什么。 */
  private static Map<String, Object> receiptRow(DecisionSubmission submission) {
    DecisionPackage pkg = submission.pkg();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", pkg.id());
    body.put("state", pkg.state().name());
    body.put("decision", decisionRow(pkg));
    CommitReceipt receipt = submission.receipt();
    if (receipt == null) {
      // 桥没落定（提交口未装配 / COMMIT_FAILED）：显式 null + 一句诚实说明，绝不静默
      body.put("commit", null);
      body.put("warning", submission.commitNote());
      return body;
    }
    Map<String, Object> commit = new LinkedHashMap<>();
    commit.put("status", receipt.status().name());
    commit.put("revisionRef", receipt.revisionRef() == null ? "" : receipt.revisionRef());
    commit.put("reason", receipt.reason());
    List<String> outcomes = new ArrayList<>(receipt.outcomes().size());
    for (CommitReceipt.ItemOutcome outcome : receipt.outcomes()) {
      outcomes.add(outcome.name());
    }
    commit.put("outcomes", outcomes);
    // ★ P2b（裁定 8 补充 V14）：逐条"去向 + 可空理由"的并列读口（outcomes 是冻结形状，一个字不动）。
    //   渲染口径：reason 为 null ⇒ 只报去向（键在，值为 null——不编一句话）
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

  /** 解析并校验请求体（非法 ⇒ {@link BadBody}，由调用方转 400）。 */
  private Decision readDecision(HttpExchange exchange, DecisionPackage pkg) throws IOException {
    byte[] raw = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
    if (raw.length > MAX_BODY_BYTES) {
      throw new BadBody("请求体过大（上限 " + MAX_BODY_BYTES + " 字节）");
    }
    JsonNode node;
    try {
      node = raw.length == 0 ? null : JSON.readTree(raw);
    } catch (JsonProcessingException e) {
      throw new BadBody("请求体不是合法 JSON");
    }
    if (node == null || !node.isObject()) {
      throw new BadBody("请求体必须是 JSON 对象");
    }
    // 形态位读成 Optional：**缺席**（人没选这一形态）与**显式 false** 在判定上是同一件事，
    // 但类型上必须分得清——可空 Boolean 让 SpotBugs 判 NP_BOOLEAN_RETURN_NULL（真发现：可空布尔是易漏判的形状）
    boolean approveAllTrue = flag(node, "approveAll").orElse(false);
    boolean rejectAllTrue = flag(node, "rejectAll").orElse(false);
    JsonNode itemsNode = node.get("items");
    if (itemsNode != null && !itemsNode.isObject()) {
      throw new BadBody("items 必须是对象 {index: approve|reject}");
    }
    int forms = (approveAllTrue ? 1 : 0) + (rejectAllTrue ? 1 : 0) + (itemsNode == null ? 0 : 1);
    if (forms == 0) {
      throw new BadBody(
          "缺少裁决形态：三选一——approveAll:true / rejectAll:true / items:{index:approve|reject}");
    }
    if (forms > 1) {
      throw new BadBody("裁决形态只能三选一：approveAll / rejectAll / items 不得同时给（不猜人的意思）");
    }
    Set<Integer> approved;
    if (approveAllTrue) {
      // 整包批：条目集合由包自己回答（发编号后包内条目不可变，所以这里不是"第二处真相"）
      approved = new LinkedHashSet<>();
      for (PackageItem item : pkg.items()) {
        approved.add(item.index());
      }
    } else if (rejectAllTrue) {
      approved = Set.of();
    } else {
      approved = readItems(itemsNode);
    }
    return new Decision(approved, textField(node, "by", DEFAULT_BY), textField(node, "note", ""));
  }

  /**
   * {@code true}/{@code false} 形态键：给了就必须是布尔（给 {@code false} = 没选这一形态）。
   *
   * <p><b>返回 {@code Optional} 而不是可空 {@code Boolean}</b>：缺省情况下"这一形态没被选"是正常输入，不是错误，所以要有一个
   * 表达"缺席"的载体；可空布尔能表达，但它把"忘了判空"留成了默认失败模式（SpotBugs {@code NP_BOOLEAN_RETURN_NULL} 抓的就是它），而 {@code
   * Optional} 强制调用点表态。
   */
  private static Optional<Boolean> flag(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isBoolean()) {
      throw new BadBody(field + " 必须是布尔（true = 选这一形态）");
    }
    return Optional.of(value.booleanValue());
  }

  /** 逐条例外：只有 {@code approve} 的条目进被批集合；{@code reject} 与缺席都是被驳（fail-closed 方向）。 */
  private static Set<Integer> readItems(JsonNode itemsNode) {
    Set<Integer> approved = new LinkedHashSet<>();
    Set<Map.Entry<String, JsonNode>> fields = itemsNode.properties();
    if (fields.isEmpty()) {
      throw new BadBody("items 不得为空：要整包驳请用 rejectAll；未列出的条目一律按被驳处理");
    }
    for (Map.Entry<String, JsonNode> entry : fields) {
      String key = entry.getKey() == null ? "" : entry.getKey().strip();
      int index;
      try {
        index = Integer.parseInt(key);
      } catch (NumberFormatException e) {
        throw new BadBody("items 的键必须是非负整数 index（条目在包内的身份），收到: " + key);
      }
      if (index < 0) {
        throw new BadBody("items 的键必须是非负整数 index，收到: " + key);
      }
      JsonNode value = entry.getValue();
      if (value == null || !value.isTextual()) {
        throw new BadBody("items[" + key + "] 必须是文本 approve|reject");
      }
      String verdict = value.asText().strip();
      switch (verdict) {
        case APPROVE -> approved.add(index);
        case REJECT -> {
          // 被驳：不进 approvedIndexes（Decision 的方向就是"缺席 = 驳回"）
        }
        default -> throw new BadBody("items[" + key + "] 只接受 approve|reject，收到: " + verdict);
      }
    }
    return approved;
  }

  /** 可选文本字段：缺省/空串 ⇒ {@code fallback}；给了就必须是文本（不猜数字/布尔的意思）。 */
  private static String textField(JsonNode node, String field, String fallback) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return fallback;
    }
    if (!value.isTextual()) {
      throw new BadBody(field + " 必须是文本（它只是审计注记，不参与判定）");
    }
    String text = value.asText().strip();
    return text.isEmpty() ? fallback : text;
  }

  /** 被批 index（按包内条目顺序，稳定可读；不依赖 Set 的迭代序）。 */
  private static List<Integer> approvedIndexes(DecisionPackage pkg) {
    List<Integer> indexes = new ArrayList<>();
    if (pkg.decision() == null) {
      return indexes;
    }
    for (PackageItem item : pkg.items()) {
      if (pkg.decision().approves(item.index())) {
        indexes.add(item.index());
      }
    }
    return indexes;
  }

  /** 被驳 index（= 不在被批集合里的条目；被驳条目零落盘）。 */
  private static List<Integer> rejectedIndexes(DecisionPackage pkg) {
    List<Integer> indexes = new ArrayList<>();
    if (pkg.decision() == null) {
      return indexes;
    }
    for (PackageItem item : pkg.items()) {
      if (!pkg.decision().approves(item.index())) {
        indexes.add(item.index());
      }
    }
    return indexes;
  }

  /** 端点方法门禁：非声明方法 405（带 {@code Allow} 头），响应体缺省（同既有网关惯例）。 */
  private static boolean requireMethod(HttpExchange exchange, String method) throws IOException {
    if (method.equals(exchange.getRequestMethod())) {
      return true;
    }
    methodNotAllowed(exchange, method);
    return false;
  }

  private static void methodNotAllowed(HttpExchange exchange, String allow) throws IOException {
    exchange.getResponseHeaders().set("Allow", allow);
    exchange.sendResponseHeaders(405, -1);
    exchange.close();
  }

  /** 响应发送（JSON + {@code Content-Type}；缺省无 body 的 405 走 {@link #methodNotAllowed}）。 */
  private static void send(HttpExchange exchange, int code, Object body) throws IOException {
    byte[] bytes;
    try {
      bytes = JSON.writeValueAsBytes(body);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("决策面响应序列化失败", e);
    }
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(code, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /** 坏 body（400）的载体：只带一行给人/脚本读的理由，<b>不含</b>参数明文。 */
  private static final class BadBody extends RuntimeException {

    private static final long serialVersionUID = 1L;

    BadBody(String reason) {
      super(reason);
    }
  }
}
