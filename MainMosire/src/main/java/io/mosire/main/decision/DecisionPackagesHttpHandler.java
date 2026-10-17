package io.mosire.main.decision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.Decision;
import io.mosire.agentlib.decision.DecisionPackage;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.DecisionReadAudit;
import io.mosire.agentlib.decision.DecisionSubmission;
import io.mosire.agentlib.decision.DecisionSubmissionPort;
import io.mosire.agentlib.decision.InFlightDecisionActions;
import io.mosire.agentlib.decision.InFlightDecisionPackages;
import io.mosire.agentlib.decision.PackageItem;
import io.mosire.agentlib.decision.PackageState;
import io.mosire.main.approval.ApprovalHttpEndpoint;
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
 * 决策包的<b>决策面 HTTP 端点</b>（设计 §5.10② + §5.13(e)(f) + §5septies，用户裁定 15 · 2026-10-17）：
 * 未决包列表（含"已批未执行"单列一档）、包/条目两级查看（<b>默认概览、{@code full=1} 才给明文</b>）、 条目级表态、执行命令（两段式）。
 *
 * <p>端点（与既有审批面<b>同端口、同安全基线</b>——用 {@link ApprovalHttpEndpoint#createContext(String, HttpHandler)}
 * 挂在<b>同一台</b> server 上，<b>不</b>另起第二台 server/端口）：
 *
 * <ul>
 *   <li>{@code GET /api/decision-packages} → {@code {"pending":[…],"awaitingCommit":[…]}} ——
 *       未决（{@code PROPOSED}）包 + <b>已批未执行</b>（{@code DECIDED}）包各一档；逐条只给 {@code
 *       {index,toolName,summary}}， <b>不含参数</b>；
 *   <li>{@code GET /api/decision-packages/{id}} → 包详情：逐条 {@code
 *       {index,toolName,summary,escalated,verdict}}； {@code ?full=1} 才逐条附带 {@code
 *       args}（<b>明文</b>），且每一次 full 读取落一条 {@code decision.package.detail_read} 审计事件（只记谁/哪个包/哪些
 *       index，<b>不记明文</b>）；
 *   <li>{@code GET /api/decision-packages/{id}/items/{index}} → 条目详情（同一套两档口径，{@code ?full=1} 才给明文）；
 *   <li>{@code POST /api/decision-packages/{id}} → body {@code {approveAll:true | rejectAll:true |
 *       items:{index:"approve"|"reject"}, by, note}}：<b>只表态、不执行</b>（两段式的第一段）；
 *   <li>{@code POST /api/decision-packages/{id}/items/{index}} → body {@code
 *       {verdict:"approve"|"reject", by, note}}：条目级表态（"event 命令看一条、裁一条"的人侧形态）；
 *   <li>{@code POST /api/decision-packages/{id}/commit} → body {@code
 *       {by}}（可空）：<b>执行命令</b>（两段式的第二段）。
 * </ul>
 *
 * <p><b>★ 为什么"裁决即执行"被拆成两段</b>（用户裁定 15 ③）：{@code approveAll}/{@code rejectAll}/{@code items}/
 * 条目级表态<b>都只表态</b>；只有 {@code commit} 会让被批子集落盘。全部条目都表态 ⇒ 包自动进 {@code DECIDED}（等执行）； {@code DECIDED}
 * 一直没人下执行命令、到点 ⇒ {@code EXPIRED} + 如实记因"已批未执行" + 零落盘（见 {@code DecisionPackages#expire(long)}）。
 *
 * <p><b>★ 明文纪律（本类的落点）</b>：只有 {@code full=1} 的详情响应带明文；列表、默认详情、日志<b>一行明文都不打印</b>。 每次 full 读取都把"被返回的那些
 * index"报给 {@link DecisionReadAudit}（{@code null} = 未装配 ⇒ 不落审计，见那个接口）。
 *
 * <p><b>★ 写路径必须经桥</b>（设计 §5.13(e)）：{@code POST} 调的是注入的 {@link DecisionSubmissionPort} / {@link
 * InFlightDecisionActions}，<b>不</b>直调 {@link DecisionPackages#decide(String, Decision)}。直调会让 {@code
 * decision.package.*} 事件不落、提交口不被调、{@code COMMITTED}/{@code CONFLICTED} 永不产生，而 HTTP 面还会回报
 * 200——那是最坏的一类假成功。
 *
 * <p><b>★ 两级取数（P3e）与"条目级操作也作用在在途包上"（裁定 15）</b>：先查 {@link DecisionPackages#find(String)}，
 * 未命中再查<b>在途取数口</b> {@link InFlightDecisionPackages}（跨进程那条路的临时包 {@code xp-…}）。写路径同理： 包表未命中时问 {@link
 * InFlightDecisionActions}（{@code null} = 没有这个口 ⇒ 在途包不可写，与 P3e 的行为逐字一致）。 两条回退都<b>不</b>改变不变量
 * 1：在途包不进包表、不进 {@code pending()}、不落盘、不发事件。
 *
 * <p><b>过期不会被伪装成不存在</b>：详情面对已到点的包回 {@code 200} + {@code state=EXPIRED}（它查得到，且状态说得清 为什么没落盘）；只有
 * {@code POST} 把"未知"与"已过期"一并回 {@code 404}（设计 §5.11 第 11 条）， 并由响应体里的 {@code code} 把两者分开。
 *
 * <p><b>判定顺序（先查后决，同审批面口径）</b>：{@code POST} 上 {@code find(id)} 为空且在途口也认不出 ⇒ 直接 {@code 404}；有值 ⇒ 解析
 * body ⇒ 交桥/在途口回填。桥抛的状态机具名拒按 {@code code} 映射状态码（{@code PACKAGE_NOT_FOUND}/{@code PACKAGE_EXPIRED} ⇒
 * 404、{@code PACKAGE_NOT_PROPOSED}/{@code ITEM_ALREADY_DECIDED} ⇒ 409、{@code
 * INVALID_DECISION}/{@code INVALID_ITEM_INDEX}/{@code NO_ITEM_DECIDED} ⇒ 400）；<b>认不出的拒码 ⇒
 * 500</b>（那是缺陷，不该伪装成"客户端写错了"）。提交侧的失败（{@code COMMIT_FAILED}）不走异常：裁决已生效、包只是没落定， 回执里用 {@code warning}
 * 说清楚。
 */
public final class DecisionPackagesHttpHandler implements HttpHandler {

  private static final Logger LOG = LoggerFactory.getLogger(DecisionPackagesHttpHandler.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 决策面根路径（{@code GET} 列表；{@code GET}/{@code POST} + {@code /{id}} 详情与裁决）。 */
  public static final String PATH = "/api/decision-packages";

  /** 条目级详情/表态的子路径段（{@code /{id}/items/{index}} 与 {@code /{id}/commit}）。 */
  public static final String ITEMS_SEGMENT = "items";

  /** 执行命令的子路径段（{@code /{id}/commit}）。 */
  public static final String COMMIT_SEGMENT = "commit";

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

  /** <b>在途包的写口</b>（裁定 15 ②③）：{@code null} = 没有这个口 ⇒ 在途包的条目级表态与执行命令都回 {@code 404} （与本能力引入前逐字一致）。 */
  private final InFlightDecisionActions inFlightActions;

  /** 明文读取审计口（裁定 15 ①）：{@code null} = 未装配 ⇒ 明细面照常给明文、不落审计事件（见 {@link DecisionReadAudit}）。 */
  private final DecisionReadAudit readAudit;

  /**
   * @param packages 决策包登记表（装配层持有的<b>那一个</b>实例：HTTP 面看到的必须是待裁决面看到的同一个表）
   * @param submissions 裁决提交口（实现方 = 决策包桥；见 {@link DecisionSubmissionPort} 的"为什么不能直调状态机"）
   */
  public DecisionPackagesHttpHandler(
      DecisionPackages packages, DecisionSubmissionPort submissions) {
    this(packages, submissions, null, null, null, null);
  }

  /**
   * @param escalatedIndexes 上报视图（见字段注释；{@code null} = 不做过滤与标记）
   */
  public DecisionPackagesHttpHandler(
      DecisionPackages packages,
      DecisionSubmissionPort submissions,
      Function<String, Set<Integer>> escalatedIndexes) {
    this(packages, submissions, escalatedIndexes, null, null, null);
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
    this(packages, submissions, escalatedIndexes, inFlight, null, null);
  }

  /**
   * 全参装配（裁定 15 · 2026-10-17）：再带上<b>在途包的写口</b>与<b>明文读取审计口</b>。
   *
   * @param inFlightActions 在途包的条目级表态 / 执行命令口（{@code null} = 在途包不可写）
   * @param readAudit 明文读取审计口（{@code null} = 不落审计事件；生产装配必须接，见 {@link DecisionReadAudit}）
   */
  public DecisionPackagesHttpHandler(
      DecisionPackages packages,
      DecisionSubmissionPort submissions,
      Function<String, Set<Integer>> escalatedIndexes,
      InFlightDecisionPackages inFlight,
      InFlightDecisionActions inFlightActions,
      DecisionReadAudit readAudit) {
    this.packages = Objects.requireNonNull(packages, "packages");
    this.submissions = Objects.requireNonNull(submissions, "submissions");
    this.escalatedIndexes = escalatedIndexes;
    this.inFlight = inFlight;
    this.inFlightActions = inFlightActions;
    this.readAudit = readAudit;
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
    register(endpoint, packages, submissions, null, null, null, null);
  }

  /** 带"上报视图"的注册（P2b）：列表只列上报条目、逐条打 {@code escalated} 标记，见 {@link #escalatedIndexes}。 */
  public static void register(
      ApprovalHttpEndpoint endpoint,
      DecisionPackages packages,
      DecisionSubmissionPort submissions,
      Function<String, Set<Integer>> escalatedIndexes) {
    register(endpoint, packages, submissions, escalatedIndexes, null, null, null);
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
    register(endpoint, packages, submissions, escalatedIndexes, inFlight, null, null);
  }

  /**
   * 全参注册（裁定 15）：在 P3e 的五参之上再带<b>在途包的写口</b>与<b>明文读取审计口</b>—— 条目级操作与"看了明文留痕"因此对在途包同样成立（见 {@link
   * InFlightDecisionActions} / {@link DecisionReadAudit}）。
   *
   * @param inFlightActions 在途包的条目级表态 / 执行命令口（{@code null} = 在途包不可写）
   * @param readAudit 明文读取审计口（{@code null} = 不落审计事件）
   */
  public static void register(
      ApprovalHttpEndpoint endpoint,
      DecisionPackages packages,
      DecisionSubmissionPort submissions,
      Function<String, Set<Integer>> escalatedIndexes,
      InFlightDecisionPackages inFlight,
      InFlightDecisionActions inFlightActions,
      DecisionReadAudit readAudit) {
    Objects.requireNonNull(endpoint, "endpoint");
    endpoint.createContext(
        PATH,
        new DecisionPackagesHttpHandler(
            Objects.requireNonNull(packages, "packages"),
            Objects.requireNonNull(submissions, "submissions"),
            escalatedIndexes,
            inFlight,
            inFlightActions,
            readAudit));
  }

  /**
   * 路径后缀分发（裁定 15 起支持四种形状）：{@code ""} = 列表，{@code /{id}} = 详情或整包裁决， {@code /{id}/items/{index}} =
   * 条目级详情或表态，{@code /{id}/commit} = 执行命令。
   */
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
    // 只认 "/{id}"、"/{id}/items/{index}"、"/{id}/commit"：其余（/a/b/c、裸斜杠 /）都不猜，一律 404
    if (suffix.length() < 2 || suffix.charAt(0) != '/') {
      send(exchange, 404, Map.of("error", "未知路径"));
      return;
    }
    String[] segments = suffix.substring(1).split("/", -1);
    if (segments.length == 1 && !segments[0].isEmpty()) {
      handlePackage(exchange, segments[0]);
      return;
    }
    if (segments.length == 3
        && !segments[0].isEmpty()
        && ITEMS_SEGMENT.equals(segments[1])
        && !segments[2].isEmpty()) {
      handleItem(exchange, segments[0], segments[2]);
      return;
    }
    if (segments.length == 2 && !segments[0].isEmpty() && COMMIT_SEGMENT.equals(segments[1])) {
      handleCommit(exchange, segments[0]);
      return;
    }
    send(exchange, 404, Map.of("error", "未知路径"));
  }

  /** 包级：{@code GET} 详情，{@code POST} 整包/逐条表态（<b>不执行</b>）。 */
  private void handlePackage(HttpExchange exchange, String id) throws IOException {
    switch (exchange.getRequestMethod()) {
      case "GET" -> handleDetail(exchange, id);
      case "POST" -> handleDecide(exchange, id);
      default -> methodNotAllowed(exchange, "GET, POST");
    }
  }

  /**
   * 未决包 + <b>已批未执行</b>包两档快照：源就是登记表（不读副本——副本会与人的裁决分叉）；每项只带工具名与条目人读摘要， <b>不带参数</b>。
   *
   * <p><b>★ 用户裁定 15 ④：「已批未执行」单列一档</b>：{@code DECIDED} 的包既不在 {@code pending()}（它不是"还没人裁"）， 也不在
   * {@code committed}（还没落盘）——不单列就<b>看不见</b>，而"批了没跑"恰恰是最需要被看见的一档。
   */
  private void handleList(HttpExchange exchange) throws IOException {
    List<Map<String, Object>> pending = new ArrayList<>();
    List<Map<String, Object>> awaiting = new ArrayList<>();
    for (DecisionPackage pkg : packages.pending()) {
      pending.add(packageRow(pkg));
    }
    for (DecisionPackage pkg : awaitingCommit()) {
      awaiting.add(packageRow(pkg));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("pending", pending);
    body.put("awaitingCommit", awaiting);
    send(exchange, 200, body);
  }

  /** 已批未执行的包（{@code DECIDED}）：订单 = 登记顺序（与 {@code pending()} 同口径）。 */
  private List<DecisionPackage> awaitingCommit() {
    List<DecisionPackage> rows = new ArrayList<>();
    for (DecisionPackage pkg : packages.all()) {
      if (pkg.state() == PackageState.DECIDED) {
        rows.add(pkg);
      }
    }
    for (DecisionPackage pkg : inFlightList()) {
      if (pkg.state() == PackageState.DECIDED || pkg.state() == PackageState.DECIDING) {
        rows.add(pkg);
      }
    }
    return rows;
  }

  /** 未决包列表行（{@link DecisionPackages#pending()} 的顺序 = 登记顺序）。 */
  private Map<String, Object> packageRow(DecisionPackage pkg) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", pkg.id());
    row.put("proposerKey", pkg.proposerKey());
    row.put("state", pkg.state().name());
    row.put("itemCount", pkg.items().size());
    Optional<Set<Integer>> escalated = escalatedOf(pkg);
    row.put("escalated", escalated.map(DecisionPackagesHttpHandler::sorted).orElse(null));
    row.put("decided", verdictRows(pkg));
    row.put("undecided", undecidedIndexes(pkg));
    // ★ P2b：列表是"人的工作清单" ⇒ 只列上报给他的条目（§5quater ④「决策面只列被上报的那些条目」）；
    //   没有上报视图（未装配级联 / 该包查不到）⇒ 照旧列全部（宁可多显示，不静默藏条目）
    row.put("items", itemRows(pkg, false, escalated.orElse(null)));
    row.put("createdAtEpochMs", pkg.createdAtEpochMs());
    row.put("deadlineEpochMs", pkg.deadlineEpochMs());
    // ★ 裁定 15 ④：到点原因要读得到（"已批未执行"与"没人裁"必须分得开）
    packages.expiryReason(pkg.id()).ifPresent(reason -> row.put("expiryReason", reason));
    return row;
  }

  /**
   * 包详情：逐条 {@code {index,toolName,summary,escalated,verdict,decidedBy}}； {@code ?full=1} 才附带 {@code
   * args}（<b>明文</b>，并落审计事件）。
   *
   * <p><b>★ 两档查看（用户裁定 15 ①）</b>：默认<b>概览</b>（不含明文）——用户原话「肯定是笼统的，除非要求 {@code full} 查看」。 这<b>收窄</b>了
   * 2026-10-16「肯定展示全部内容啊」的落地口径：旧原话仍有效（裁决者<b>能</b>看全文）， 新口径管的是"默认不给、要来才给"。
   *
   * <p><b>详情面刻意列全部条目</b>（P2b）：人对整包有知情权（"这个包里还有哪些是下级已经批掉的"是判断的依据）， 所以这里不做过滤，只逐条打 {@code escalated}
   * 标记（{@code true} = 这条还需要你裁）。过滤只发生在列表那条工作清单上。
   *
   * <p><b>两级取数（P3e）</b>：先 {@link DecisionPackages#find(String)}，未命中再问 {@link #inFlight}（在途的跨进程临时包，
   * {@code xp-…}）。两级都<b>只读</b>：不建包、不改状态、不进列表；非在途/已结算 ⇒ 与"从未存在"同一个 {@code 404}。
   *
   * <p>未知 id ⇒ {@code 404}；已到点的包 ⇒ {@code 200} + {@code state=EXPIRED}（过期不伪装成不存在）。
   */
  private void handleDetail(HttpExchange exchange, String id) throws IOException {
    boolean full = wantsFull(exchange);
    Optional<DecisionPackage> found = packages.find(id);
    if (found.isEmpty()) {
      found = inFlightOf(id, full);
    }
    if (found.isEmpty()) {
      send(exchange, 404, Map.of("error", "未知的决策包 id"));
      return;
    }
    DecisionPackage pkg = found.get();
    auditFullRead(id, pkg, full);
    send(exchange, 200, detailRow(pkg, full));
  }

  /**
   * 条目级：{@code GET} 单条详情，{@code POST} 单条表态。
   *
   * <p>它是用户口中"用 event 命令查看某一条具体的决策，进行裁决"的<b>人侧形态</b>（Agent 侧是 {@code decision_history{index}} +
   * {@code decide_decision_item}）。
   */
  private void handleItem(HttpExchange exchange, String id, String rawIndex) throws IOException {
    int index;
    try {
      index = Integer.parseInt(rawIndex.strip());
    } catch (NumberFormatException notANumber) {
      send(exchange, 400, Map.of("error", "条目 index 必须是非负整数，收到: " + rawIndex));
      return;
    }
    if (index < 0) {
      send(exchange, 400, Map.of("error", "条目 index 必须是非负整数，收到: " + rawIndex));
      return;
    }
    switch (exchange.getRequestMethod()) {
      case "GET" -> handleItemDetail(exchange, id, index);
      case "POST" -> handleItemDecide(exchange, id, index);
      default -> methodNotAllowed(exchange, "GET, POST");
    }
  }

  /** 条目详情：与包详情同一套两档口径（{@code ?full=1} 才给明文并落审计）。 */
  private void handleItemDetail(HttpExchange exchange, String id, int index) throws IOException {
    boolean full = wantsFull(exchange);
    Optional<DecisionPackage> found = packages.find(id);
    if (found.isEmpty()) {
      found = inFlightOf(id, full);
    }
    if (found.isEmpty()) {
      send(exchange, 404, Map.of("error", "未知的决策包 id"));
      return;
    }
    DecisionPackage pkg = found.get();
    PackageItem item = itemOf(pkg, index);
    if (item == null) {
      send(
          exchange,
          404,
          Map.of("error", "该包里没有这个条目 index", "code", DecisionPackages.INVALID_ITEM_INDEX));
      return;
    }
    if (full) {
      auditFullRead(id, pkg, true);
    }
    // 单条详情也带 escalated 标记（口径与包详情一致：没有上报视图 ⇒ 一律 true）
    boolean escalated = escalatedOf(pkg).map(indexes -> indexes.contains(index)).orElse(true);
    send(exchange, 200, itemRow(pkg, item, full, escalated));
  }

  /**
   * 整包裁决（{@code POST /{id}}）：<b>只表态</b>（用户裁定 15 ③）。
   *
   * <p><b>★ 只认包登记表或在途写口</b>（P3e 的边界 + 裁定 15 的扩展）：在途包（{@code xp-…}）没有状态机、不进包表，
   * 但它是"人此刻正在裁决的那个包"——没有在途写口（{@code null}）时它与未知 id 同归 {@code 404}（与本能力引入前逐字一致）。
   */
  private void handleDecide(HttpExchange exchange, String id) throws IOException {
    Optional<DecisionPackage> found = packages.find(id);
    if (found.isEmpty()) {
      if (inFlightActions == null || inFlightOf(id, false).isEmpty()) {
        send(
            exchange,
            404,
            Map.of("error", "未知的决策包 id", "code", DecisionPackages.PACKAGE_NOT_FOUND));
        return;
      }
      decideInFlightPackage(exchange, id);
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
        "决策面收到裁决 packageId={} state={} approved={} rejected={} by={}（两段式：表决权不在本步执行）",
        id,
        submission.pkg().state(),
        approvedIndexes(submission.pkg()).size(),
        rejectedIndexes(submission.pkg()).size(),
        decision.by());
    send(exchange, 200, receiptRow(submission));
  }

  /** 在途包的整包/逐条表态：拆成逐条调在途写口（在途包没有"整包一次裁"的状态机，逐条就是它的等价形态）。 */
  private void decideInFlightPackage(HttpExchange exchange, String id) throws IOException {
    Map<Integer, String> perItem;
    String by;
    String note;
    try {
      JsonNode node = readBody(exchange);
      boolean approveAll = flag(node, "approveAll").orElse(false);
      boolean rejectAll = flag(node, "rejectAll").orElse(false);
      JsonNode itemsNode = node.get("items");
      if (itemsNode != null && !itemsNode.isObject()) {
        throw new BadBody("items 必须是对象 {index: approve|reject}");
      }
      int forms = (approveAll ? 1 : 0) + (rejectAll ? 1 : 0) + (itemsNode == null ? 0 : 1);
      if (forms == 0) {
        throw new BadBody(
            "缺少裁决形态：三选一——approveAll:true / rejectAll:true / items:{index:approve|reject}");
      }
      if (forms > 1) {
        throw new BadBody("裁决形态只能三选一：approveAll / rejectAll / items 不得同时给（不猜人的意思）");
      }
      by = textField(node, "by", DEFAULT_BY);
      note = textField(node, "note", "");
      perItem = new LinkedHashMap<>();
      if (approveAll || rejectAll) {
        Optional<DecisionPackage> pkg = inFlightOf(id, false);
        if (pkg.isEmpty()) {
          send(
              exchange,
              404,
              Map.of("error", "未知或已结算的在途决策包 id", "code", DecisionPackages.PACKAGE_NOT_FOUND));
          return;
        }
        for (PackageItem item : pkg.get().items()) {
          perItem.put(item.index(), approveAll ? APPROVE : REJECT);
        }
      } else {
        for (Map.Entry<String, JsonNode> entry : itemsNode.properties()) {
          String key = entry.getKey() == null ? "" : entry.getKey().strip();
          int index;
          try {
            index = Integer.parseInt(key);
          } catch (NumberFormatException e) {
            throw new BadBody("items 的键必须是非负整数 index（条目在包内的身份），收到: " + key);
          }
          JsonNode value = entry.getValue();
          if (value == null || !value.isTextual()) {
            throw new BadBody("items[" + key + "] 必须是文本 approve|reject");
          }
          String verdict = value.asText().strip();
          if (!APPROVE.equals(verdict) && !REJECT.equals(verdict)) {
            throw new BadBody("items[" + key + "] 只接受 approve|reject，收到: " + verdict);
          }
          perItem.put(index, verdict);
        }
        if (perItem.isEmpty()) {
          throw new BadBody("items 不得为空：要整包驳请用 rejectAll；未列出的条目一律按未表态处理");
        }
      }
    } catch (BadBody badBody) {
      send(exchange, 400, Map.of("error", badBody.getMessage()));
      return;
    }
    DecisionSubmission last = null;
    try {
      for (Map.Entry<Integer, String> entry : perItem.entrySet()) {
        last =
            inFlightActions.itemDecided(
                id, entry.getKey(), APPROVE.equals(entry.getValue()), by, note);
      }
    } catch (DecisionPackages.RejectionException refusal) {
      refuseByCode(exchange, refusal);
      return;
    } catch (RuntimeException failure) {
      LOG.warn("决策面在途包裁决异常（未落定） packageId={} 异常={}", id, failure.toString());
      send(exchange, 500, Map.of("error", "裁决提交失败（服务器侧异常）"));
      return;
    }
    if (last == null) {
      send(exchange, 400, Map.of("error", "没有可表态的条目"));
      return;
    }
    LOG.info("决策面收到在途包裁决 packageId={} state={} 条数={}", id, last.pkg().state(), perItem.size());
    send(exchange, 200, receiptRow(last));
  }

  /**
   * 条目级表态（{@code POST /{id}/items/{index}}）：body {@code {verdict:"approve"|"reject", by, note}}。
   *
   * <p>包表命中 ⇒ 走桥的 {@link DecisionSubmissionPort#itemDecided}；未命中 ⇒ 问在途写口。两条路都<b>只表态、不执行</b>。
   */
  private void handleItemDecide(HttpExchange exchange, String id, int index) throws IOException {
    boolean approve;
    String by;
    String note;
    try {
      JsonNode node = readBody(exchange);
      JsonNode verdictNode = node.get("verdict");
      if (verdictNode == null || !verdictNode.isTextual()) {
        throw new BadBody("verdict 必须是文本 approve|reject");
      }
      String verdict = verdictNode.asText().strip();
      if (!APPROVE.equals(verdict) && !REJECT.equals(verdict)) {
        throw new BadBody("verdict 只接受 approve|reject，收到: " + verdict);
      }
      approve = APPROVE.equals(verdict);
      by = textField(node, "by", DEFAULT_BY);
      note = textField(node, "note", "");
    } catch (BadBody badBody) {
      send(exchange, 400, Map.of("error", badBody.getMessage()));
      return;
    }
    DecisionSubmission submission;
    try {
      if (packages.find(id).isPresent()) {
        submission = submissions.itemDecided(id, index, approve, by, note);
      } else if (inFlightActions != null && inFlightOf(id, false).isPresent()) {
        submission = inFlightActions.itemDecided(id, index, approve, by, note);
      } else {
        send(
            exchange,
            404,
            Map.of("error", "未知的决策包 id", "code", DecisionPackages.PACKAGE_NOT_FOUND));
        return;
      }
    } catch (DecisionPackages.RejectionException refusal) {
      refuseByCode(exchange, refusal);
      return;
    } catch (RuntimeException failure) {
      LOG.warn("决策面条目级表态异常（未落定） packageId={} index={} 异常={}", id, index, failure.toString());
      send(exchange, 500, Map.of("error", "条目级表态失败（服务器侧异常）"));
      return;
    }
    LOG.info(
        "决策面条目级表态 packageId={} index={} approve={} state={} by={}",
        id,
        index,
        approve,
        submission.pkg().state(),
        by);
    send(exchange, 200, receiptRow(submission));
  }

  /**
   * 执行命令（{@code POST /{id}/commit}）：<b>两段式的第二段</b>（用户裁定 15 ③）。
   *
   * <p>只有这一步会让被批子集落盘。重复 {@code commit} ⇒ 幂等（包层 {@code entry.receipt} 早返回，第二次不重复执行）； 非可执行态（{@code
   * PROPOSED} / 一条都没批的分段态 / 已终局）⇒ 具名拒，由 {@link #refuseByCode} 映射状态码。
   */
  private void handleCommit(HttpExchange exchange, String id) throws IOException {
    if (!requireMethod(exchange, "POST")) {
      return;
    }
    String by;
    try {
      JsonNode node = readBody(exchange);
      by = node == null ? DEFAULT_BY : textField(node, "by", DEFAULT_BY);
    } catch (BadBody badBody) {
      send(exchange, 400, Map.of("error", badBody.getMessage()));
      return;
    }
    DecisionSubmission submission;
    try {
      if (packages.find(id).isPresent()) {
        submission = submissions.commitPackage(id, by);
      } else if (inFlightActions != null && inFlightOf(id, false).isPresent()) {
        submission = inFlightActions.commitPackage(id, by);
      } else {
        send(
            exchange,
            404,
            Map.of("error", "未知的决策包 id", "code", DecisionPackages.PACKAGE_NOT_FOUND));
        return;
      }
    } catch (DecisionPackages.RejectionException refusal) {
      refuseByCode(exchange, refusal);
      return;
    } catch (RuntimeException failure) {
      LOG.warn("决策面执行命令异常（未落定） packageId={} 异常={}", id, failure.toString());
      send(exchange, 500, Map.of("error", "执行命令失败（服务器侧异常）"));
      return;
    }
    LOG.info(
        "决策面收到执行命令 packageId={} state={} 落定={} by={}",
        id,
        submission.pkg().state(),
        submission.receipt() == null ? "未落定（见 warning）" : submission.receipt().status(),
        by);
    send(exchange, 200, receiptRow(submission));
  }

  /**
   * 在途包的取数（P3e + 裁定 15 ①）：没接口 / 查不到 / 接口自己炸了 ⇒ 空值（照旧 {@code 404}）。
   *
   * <p>接口异常按<b>取不到</b>办：在途视图是"多给一条看全文的路"，它坏了不该让详情面变成 500——但也不该静默，落一行 WARN （同 {@link #escalatedOf}
   * 的口径）。
   */
  private Optional<DecisionPackage> inFlightOf(String id, boolean full) {
    if (inFlight == null) {
      return Optional.empty();
    }
    Optional<DecisionPackage> found;
    try {
      found = full ? inFlight.find(id, true) : inFlight.find(id);
    } catch (RuntimeException failure) {
      LOG.warn("取在途决策包失败（按未知 id 处理） packageId={}", id, failure);
      return Optional.empty();
    }
    return found == null ? Optional.empty() : found;
  }

  /** 在途包列表（概览面，<b>不含</b>明文）：接口不在 / 炸了 ⇒ 空列表（列表不因此变 500）。 */
  private List<DecisionPackage> inFlightList() {
    if (inFlight == null) {
      return List.of();
    }
    try {
      List<DecisionPackage> rows = inFlight.list();
      return rows == null ? List.of() : rows;
    } catch (RuntimeException failure) {
      LOG.warn("列在途决策包失败（按空列表处理）", failure);
      return List.of();
    }
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

  /** 详情行：包级身份与状态 + 逐条（默认概览 / {@code full} 才带明文）+ 已有裁决 + 条目级表态。 */
  private Map<String, Object> detailRow(DecisionPackage pkg, boolean full) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", pkg.id());
    row.put("proposerKey", pkg.proposerKey());
    row.put("caller", pkg.caller().name());
    row.put("itemCount", pkg.items().size());
    row.put("state", pkg.state().name());
    row.put("createdAtEpochMs", pkg.createdAtEpochMs());
    row.put("deadlineEpochMs", pkg.deadlineEpochMs());
    row.put("decision", decisionRow(pkg));
    row.put("decided", verdictRows(pkg));
    row.put("undecided", undecidedIndexes(pkg));
    packages.expiryReason(pkg.id()).ifPresent(reason -> row.put("expiryReason", reason));
    // 详情面：列全部条目（人对整包有知情权），但逐条带 escalated 标记；没有上报视图（未装配级联）⇒ 全标 true
    row.put("items", itemRows(pkg, full, escalatedOf(pkg).orElse(null)));
    return row;
  }

  /**
   * 条目行：{@code plaintext == false} ⇒ <b>概览档</b>——只给 {@code {index, toolName, overview}}， {@code
   * args} 与 {@code summary} <b>都不出现</b>（用户裁定 15 ①「肯定是笼统的，除非要求 full 查看」）； {@code plaintext == true}
   * ⇒ <b>明文档</b>——{@code args}（明文）与 {@code summary}（人读一行，它就是参数值渲染出来的）一并给出。 {@code escalatedFilter}
   * 非 null ⇒ 只列该集合里的条目（P2b 的列表过滤）。
   *
   * <p><b>★ 为什么 {@code summary} 在概览档也要收起</b>：{@link PackageItem#summary()} 是<b>由参数值渲染</b>的 （{@code
   * DecisionItemDigest#itemSummary} 产出形如 {@code "doc_edit path=x.md …"} 的一行），它<b>就是</b>一份截断后的参数。
   * 用户裁定 15 ① 把"默认不给明文"收窄成硬口径，而摘要里那截参数恰恰最容易把令牌/路径带出去——概览因此只回答 "这一条是哪个工具、第几条、有几个参数"（{@link
   * #overviewOf}），要看内容就显式要 {@code full}。
   *
   * <p>{@code escalated} 标记：{@code true} = 这条还需要人裁（逐级预裁定之后剩下的），{@code false} = 已由某一级预裁定批掉。
   */
  private List<Map<String, Object>> itemRows(
      DecisionPackage pkg, boolean plaintext, Set<Integer> escalatedFilter) {
    List<Map<String, Object>> items = new ArrayList<>(pkg.items().size());
    for (PackageItem item : pkg.items()) {
      boolean escalated = escalatedFilter == null || escalatedFilter.contains(item.index());
      if (escalatedFilter != null && !escalated) {
        continue;
      }
      items.add(itemRow(pkg, item, plaintext, escalated));
    }
    return items;
  }

  /** 单条条目行（包详情与条目详情共用一处渲染：两处各写一份必然漂移）。 */
  private Map<String, Object> itemRow(
      DecisionPackage pkg, PackageItem item, boolean plaintext, boolean escalated) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("index", item.index());
    row.put("toolName", item.toolName());
    if (plaintext) {
      // 明文档：人读一行 + 参数明文（明文只经这条回环 HTTP 出面）
      row.put("summary", item.summary());
      row.put("args", item.args());
    } else {
      // 概览档：只有"哪个工具、第几条"这类不含参数值的说明（见 itemRows 的注释）
      row.put("overview", overviewOf(item));
    }
    row.put("escalated", escalated);
    row.put("digest", item.argsDigest());
    // 条目级表态快照（裁定 15 ②）："这一条定了没有、谁定的"——未表态 ⇒ null（不编一个"驳回"）
    Map<Integer, DecisionPackages.ItemVerdict> verdicts = verdictsOf(pkg);
    DecisionPackages.ItemVerdict verdict = verdicts.get(item.index());
    row.put("verdict", verdict == null ? null : verdict.approved() ? APPROVE : REJECT);
    row.put("decidedBy", verdict == null ? null : verdict.by());
    row.put("decisionNote", verdict == null || verdict.note().isBlank() ? null : verdict.note());
    return row;
  }

  /** 该包已表态的条目（按 index；{@code DECIDED} 之后由裁决反推，见 {@code DecisionPackages#itemVerdicts}）。 */
  private Map<Integer, DecisionPackages.ItemVerdict> verdictsOf(DecisionPackage pkg) {
    Map<Integer, DecisionPackages.ItemVerdict> verdicts = new LinkedHashMap<>();
    for (DecisionPackages.ItemVerdict verdict : packages.itemVerdicts(pkg.id())) {
      verdicts.put(verdict.index(), verdict);
    }
    return verdicts;
  }

  /** 逐条表态行（列表/详情共用）：{@code [{index, verdict, by, note}]}，按 index 升序。 */
  private List<Map<String, Object>> verdictRows(DecisionPackage pkg) {
    List<DecisionPackages.ItemVerdict> verdicts = new ArrayList<>(packages.itemVerdicts(pkg.id()));
    verdicts.sort((left, right) -> Integer.compare(left.index(), right.index()));
    List<Map<String, Object>> rows = new ArrayList<>(verdicts.size());
    for (DecisionPackages.ItemVerdict verdict : verdicts) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("index", verdict.index());
      row.put("verdict", verdict.approved() ? APPROVE : REJECT);
      row.put("by", verdict.by());
      row.put("note", verdict.note());
      rows.add(row);
    }
    return rows;
  }

  /** 还没表态的条目 index（裁定 15 ④：未表态 <b>≠</b> 拒绝——它要单独看得出来）。 */
  private List<Integer> undecidedIndexes(DecisionPackage pkg) {
    Set<Integer> decided = verdictsOf(pkg).keySet();
    List<Integer> rows = new ArrayList<>();
    for (PackageItem item : pkg.items()) {
      if (!decided.contains(item.index())) {
        rows.add(item.index());
      }
    }
    return rows;
  }

  /**
   * 概览档的一行说明（<b>不含参数值</b>；用户裁定 15 ①）：能说的只有"哪个工具、第几条"以及参数<b>有几个</b>。
   *
   * <p>为什么连参数个数都给：它帮人判断"这条是不是比我以为的复杂"，而个数本身不是内容（{@code args} 的键名与值一个都不露）。
   */
  private static String overviewOf(PackageItem item) {
    if (item.args().isEmpty()) {
      return item.toolName() + "（无参数）";
    }
    return item.toolName() + "（含 " + item.args().size() + " 个参数；要看内容请用 full）";
  }

  /** 包内某条（不存在 ⇒ {@code null}）。 */
  private static PackageItem itemOf(DecisionPackage pkg, int index) {
    for (PackageItem item : pkg.items()) {
      if (item.index() == index) {
        return item;
      }
    }
    return null;
  }

  /**
   * 明文读取的审计（用户裁定 15 ①）：只报"谁 / 哪个包 / 哪些 index"，<b>明文本身不进事件</b>。
   *
   * <p>没装配审计口 ⇒ 什么也不做（有意保留的降级，见 {@link DecisionReadAudit}）；审计口抛异常 ⇒ 只 WARN ——审计面故障不得把一次正常读取变成 500。
   */
  private void auditFullRead(String packageId, DecisionPackage pkg, boolean full) {
    if (!full || readAudit == null) {
      return;
    }
    List<Integer> indexes = new ArrayList<>(pkg.items().size());
    for (PackageItem item : pkg.items()) {
      indexes.add(item.index());
    }
    try {
      readAudit.detailRead(packageId, List.copyOf(indexes), DEFAULT_BY);
    } catch (RuntimeException failure) {
      LOG.warn("决策面明文读取审计失败（不影响本次读取） packageId={}", packageId, failure);
    }
  }

  /** {@code ?full=1} 的判据（只认 {@code 1}/{@code true}；其余值一律当"没要求"——不猜）。 */
  private static boolean wantsFull(HttpExchange exchange) {
    String query = exchange.getRequestURI().getQuery();
    if (query == null || query.isBlank()) {
      return false;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      String key = eq < 0 ? pair : pair.substring(0, eq);
      String value = eq < 0 ? "" : pair.substring(eq + 1);
      if ("full".equals(key.strip())) {
        String normalized = value.strip();
        return "1".equals(normalized) || "true".equalsIgnoreCase(normalized);
      }
    }
    return false;
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

  /** 状态机具名拒 ⇒ HTTP 状态码（设计 §5.11 第 11 条）；认不出的码 ⇒ 500（缺陷不该伪装成客户端错误）。 */
  private void refuseByCode(HttpExchange exchange, DecisionPackages.RejectionException refusal)
      throws IOException {
    String code = refusal.code();
    int status =
        switch (code) {
          case DecisionPackages.PACKAGE_NOT_FOUND, DecisionPackages.PACKAGE_EXPIRED -> 404;
          case DecisionPackages.INVALID_DECISION,
              DecisionPackages.INVALID_ITEM_INDEX,
              DecisionPackages.NO_ITEM_DECIDED ->
              400;
          case DecisionPackages.PACKAGE_NOT_PROPOSED,
              DecisionPackages.PARTIAL_DECISION,
              DecisionPackages.ITEM_ALREADY_DECIDED ->
              409;
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

  /** 回执：实际生效的裁决 + 逐条去向 +（执行命令那一步的）提交回执；没有回执时用 {@code warning} 说清为什么。 */
  private static Map<String, Object> receiptRow(DecisionSubmission submission) {
    DecisionPackage pkg = submission.pkg();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", pkg.id());
    body.put("state", pkg.state().name());
    body.put("decision", decisionRow(pkg));
    CommitReceipt receipt = submission.receipt();
    if (receipt == null) {
      // 桥没落定（提交口未装配 / COMMIT_FAILED / 两段式的第一段还没执行）：显式 null + 一句诚实说明，绝不静默
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

  /** 解析并校验整包裁决请求体（非法 ⇒ {@link BadBody}，由调用方转 400）。 */
  private Decision readDecision(HttpExchange exchange, DecisionPackage pkg) throws IOException {
    JsonNode node = readBody(exchange);
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

  /** 读 + 解析请求体（空体 ⇒ 空对象；过大 / 非 JSON / 非对象 ⇒ {@link BadBody}）。 */
  private static JsonNode readBody(HttpExchange exchange) throws IOException {
    byte[] raw = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
    if (raw.length > MAX_BODY_BYTES) {
      throw new BadBody("请求体过大（上限 " + MAX_BODY_BYTES + " 字节）");
    }
    if (raw.length == 0) {
      return JSON.createObjectNode();
    }
    JsonNode node;
    try {
      node = JSON.readTree(raw);
    } catch (JsonProcessingException e) {
      throw new BadBody("请求体不是合法 JSON");
    }
    if (node == null || !node.isObject()) {
      throw new BadBody("请求体必须是 JSON 对象");
    }
    return node;
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
