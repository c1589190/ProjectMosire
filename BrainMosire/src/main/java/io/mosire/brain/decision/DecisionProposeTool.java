package io.mosire.brain.decision;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.decision.DecisionPackage;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.DecisionProposable;
import io.mosire.agentlib.decision.ItemValidator;
import io.mosire.agentlib.decision.PackageItem;
import io.mosire.agentlib.decision.ProposeRequest;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 决策包提议工具 {@code decision_propose}（设计 §5.9 ②「提议工具（模型面）」）。
 *
 * <p><b>它做什么</b>：把模型在一个回合里点名的 N 条调用收成<b>一份决策包</b>（U6：一个子 Agent 一个包、确认者可选择性执行 +
 * 驳回、被批子集一次提交），逐条校验通过后向 {@link DecisionPackages} 登记，返回 {@code packageId} 与逐条摘要 （{@code
 * index/tool/digest}）。包一旦建立，就有身份、有到点时刻、有"同一实例最多一个未决包"的约束。
 *
 * <p><b>v1 只走显式提议</b>（设计 §10 第 2 条）：不做"一回合多条调用自动并包"——MCP 面只认单条 {@code
 * tools/call}，自动并包要求回合边界语义，风险高，登记为后续。所以模型要成包，只能显式调本工具。
 *
 * <p><b>身份来自宿主，模型给不出</b>：{@code proposerKey} = {@link ToolContext#identity()} 的 {@code
 * instanceId}（主 Agent {@code main} / 子 Agent {@code <模板id>-<hex>} / 外部面 {@code external-mcp}），
 * 由装配层在建链时绑定（{@code AgentPipeline}、{@code SubProcessExecutor}、{@code App}）。<b>拿不到身份（ {@link
 * AgentIdentity#UNKNOWN}）⇒ 具名拒</b>：用占位 id 当键，会让不同实例挤进同一个"一实例一个未决包"的槽位—— 那是把身份串号，也是 fail-closed 的反面。
 *
 * <p><b>到点时刻由工具给，不由模型给</b>：{@link ProposeRequest} 要求 {@code deadlineEpochMs > 0}，
 * 而一个"永远不到点"的包会把超时这条 fail-closed 出口悄悄关掉（设计 §5.6）。让模型填截止时间，等于让它给自己延长寿命； 所以本工具用 {@link
 * #DEFAULT_DEADLINE_MILLIS} 现算，schema 里<b>没有</b>这个参数。
 *
 * <p><b>条目上限 {@value #MAX_ITEMS} 条</b>：一个包投到审批面时是<b>一条</b>待裁决项（§5.9 ③），确认者要在一屏里读完并逐条裁；
 * 无上限的包会把审批面压垮，也会让"选择性执行"退化成"整包全批"。超限 ⇒ 具名拒，让模型自己拆包（不是静默截断： 截断 = 悄悄改掉模型的意图，本项目禁止）。
 *
 * <p><b>逐条校验，任一条不过 ⇒ 整包不建</b>（设计 §5.1 / RB1 红线 1）。顺序是刻意的：先结构（tool/args 形状）、再上限、 再逐条问校验器；结构面由 {@link
 * #structuralDenial(ToolRegistry, String)} 唯一实现，权限面由装配注入的 {@link ValidatorSource} 提供。{@link
 * DecisionPackages#propose} 收到的是同一个校验器（§5.11 第 10 条： RB2 若要给模型"哪一条、为什么"，就得自己先逐条问同一个校验器，再让 propose
 * 复检——两次调用的是同一份规则， 不是两份实现）。
 *
 * <p><b>拒是结构化字段，不是栈</b>：失败时出参是 JSON {@code {error, index?, tool?, reason}}， 与 {@link
 * ToolResult#code()} 同码；<b>不</b>把 {@link DecisionPackages.RejectionException} 的栈或消息当结果丢给模型
 * （模型据此改路，不需要栈）。条目级拒一定带 {@code index} 与 {@code tool}（{@code tool} 拿不到时省略）。
 *
 * <p><b>★ 密钥纪律（红线 6 / 设计 §5.5）</b>：参数明文<b>只</b>进包内 {@link PackageItem#args()} （内存态 + 提交口）。因此：①
 * {@code argsDigest} 只算<b>规范化 JSON 的 SHA-256 前 {@value #DIGEST_HEX_CHARS} 个十六进制字符</b>，{@code
 * summary} 只含<b>工具名 + 摘要</b>，两者都会被提示面/事件面引用； ② 本工具覆写 {@link #ledgerArgs(ToolContext)}，让 {@code
 * tool.call} 事件只落"几条、什么工具、摘要"—— 缺省实现会把模型给的整包明文（可能含命令/令牌）写进事件库。
 *
 * <p><b>★ 本单（RB2a）的边界</b>：只做"模型能提议、拿到 packageId"。<b>不含</b>审批面投递与裁决回填（RB2b）、 <b>不含</b>事件与 HTTP
 * 面（RB3）。所以提议成功只意味着包已登记（{@code PROPOSED}）——此刻没有任何 {@code PendingApprovals} 投递；没人裁的包到点由 {@link
 * DecisionPackages#expire} 置 {@code EXPIRED}（零落盘）。
 *
 * <p><b>★ 已知缺口（如实记，不许说成已判）</b>：{@link ItemValidator} 这条缝今天只能表达
 * <b>工具名级</b>权限（白名单/黑名单/桶/敏感/破坏/只读/角色，经 {@code PermissionChecker.allows} 这一个 oracle）。
 * <b>资源面准入</b>（调用者可达面 × 工具的资源声明，执行时由 {@code ResourceAuthorizer} 判）在提议时<b>没有对应输入</b>
 * ——提议发生在执行之前，判定者要等 {@code ToolCallAuthorizer} 在执行前建出来。故本版<b>不判资源面</b>，也不假称已判；
 * B-5（"包不绕单条闸链"）不受影响：被批子集执行时仍走 {@code ToolCallAuthorizer} 的完整判定链，资源面在那里照判。
 *
 * <p><b>roles() 不覆写</b>：本工具对本维<b>不表态</b>（{@code Set.of()} = 任意角色可调，设计 §4.2）。提议不敏感、不破坏；
 * 要问人的是<b>包</b>（裁决面），不是"提议"这个动作——所以 {@code gate} 报 {@link ToolGate#ALLOW}。
 */
public final class DecisionProposeTool implements AgentTool {

  private static final Logger LOG = LoggerFactory.getLogger(DecisionProposeTool.class);

  /** 工具名（注册进 {@code ToolRegistry} 的唯一键）。 */
  public static final String NAME = "decision_propose";

  /** 单包条目上限：超过 ⇒ 具名拒 {@value #ITEM_LIMIT_EXCEEDED}（见类注释"为什么要有上限"）。 */
  public static final int MAX_ITEMS = 20;

  /** 裁决窗口（毫秒）：{@code deadline = now + 这个值}。缺省 30 分钟——够人看一眼，也不让包挂到天荒地老。 */
  public static final long DEFAULT_DEADLINE_MILLIS = 30L * 60L * 1000L;

  /** 摘要长度（十六进制字符数 = 6 字节）。为什么不是全长：它进提示面/事件面，短摘要足够对账且不占地方。 */
  public static final int DIGEST_HEX_CHARS = 12;

  /** 拒码：{@code items} 缺失/非数组/为空/条目形状不对/顶层参数值为 null。 */
  public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";

  /** 拒码：条目数超过 {@link #MAX_ITEMS}（让模型拆包，不静默截断）。 */
  public static final String ITEM_LIMIT_EXCEEDED = "ITEM_LIMIT_EXCEEDED";

  /** 拒码：拿不到宿主绑定的实例身份（{@link AgentIdentity#UNKNOWN}）——决策包必须锚到实例，fail-closed。 */
  public static final String IDENTITY_UNAVAILABLE = "IDENTITY_UNAVAILABLE";

  private static final String ITEMS_FIELD = "items";
  private static final String TOOL_FIELD = "tool";
  private static final String ARGS_FIELD = "args";

  private final DecisionPackages packages;
  private final ToolRegistry registry;
  private final ValidatorSource validators;

  /**
   * @param packages 决策包登记表（装配层持有的<b>那一个</b>实例；本工具不建单例）
   * @param registry 工具目录（结构面：工具在不在、可不可被提议）
   * @param validators 按调用者权限集产出条目校验器（装配注入；权限面见 {@link ValidatorSource}）
   */
  public DecisionProposeTool(
      DecisionPackages packages, ToolRegistry registry, ValidatorSource validators) {
    this.packages = Objects.requireNonNull(packages, "packages");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.validators = Objects.requireNonNull(validators, "validators");
  }

  /**
   * 装配注入：<b>按调用者权限集</b>产出该次调用的条目校验器。
   *
   * <p><b>为什么不是一个固定的校验器</b>：{@link ItemValidator#deny} 的冻结签名（设计 §5.8）只有 {@code (toolName, args)}
   * 两个入参，没有权限集；而"这次调用是谁在提"只有 {@link ToolContext} 知道 （主 Agent = {@code system()}，子 Agent 经父侧桥 =
   * 它自己的权限集，见 {@code SubProcessExecutor}）。 若在装配期就把某个固定权限集烘进校验器，子 Agent 的提议就会被拿主 Agent
   * 的权限去判——那是权限放大。 所以本缝是"权限集 → 校验器"的函数，由<b>入口（本工具）</b>在每次调用时用 {@link ToolContext#permissions()}
   * 现取，装配层只提供规则。
   */
  @FunctionalInterface
  public interface ValidatorSource {

    /**
     * @param permissions 本次调用的调用者权限集（{@code ToolContext.permissions()}，非 null）
     */
    ItemValidator forCaller(AgentPermissionSet permissions);
  }

  /**
   * 结构面校验（① 工具在不在目录里、② 它有没有声明 {@link DecisionProposable}）的<b>唯一实现点</b>。
   *
   * <p>本工具自己的逐条预检与装配注入的校验器都调它——同一条规则不写两遍（"第二处真相"的形状）。
   *
   * @param registry 工具目录
   * @param toolName 待查工具名
   * @return 拒 ⇒ 带理由（理由会进结构化出参的 {@code reason}，只含工具名，不含参数明文）；放行 ⇒ 空
   */
  public static Optional<String> structuralDenial(ToolRegistry registry, String toolName) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(toolName, "toolName");
    AgentTool tool = registry.find(toolName).orElse(null);
    if (tool == null) {
      return Optional.of("工具不在工具目录里: " + toolName);
    }
    if (!(tool instanceof DecisionProposable)) {
      return Optional.of("该工具不可被提议（未实现 DecisionProposable）: " + toolName);
    }
    return Optional.empty();
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "把一批工具调用打包成一份决策包（一次裁决 + 一次提交，可选择执行其中一部分）。"
        + "入参 items = 调用数组（1.."
        + MAX_ITEMS
        + " 条，每条 {tool, args}）；出参 = packageId + 到点时刻 + 逐条摘要（index/tool/digest）。"
        + "任一条不过（工具不存在 / 不可被提议 / 调用者无权调用）⇒ 整包不建，出参给出第几条与理由。"
        + "提议成功只代表包已登记：裁决由确认者推进，没人裁到点即作废（零执行）。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    return Map.of(
        "type",
        "object",
        "properties",
        Map.of(
            ITEMS_FIELD,
            Map.of(
                "type",
                "array",
                "description",
                "本次提议的调用数组（1.." + MAX_ITEMS + " 条）。任一条不过 ⇒ 整包不建（不给注定失败的包发编号）。",
                "items",
                Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of(
                        TOOL_FIELD,
                        Map.of("type", "string", "description", "工具名（必须是工具目录里、且声明了可被提议的工具）"),
                        ARGS_FIELD,
                        Map.of("type", "object", "description", "该工具的调用参数（原样进包；明文不进事件与提示面，只进摘要）")),
                    "required",
                    List.of(TOOL_FIELD)))),
        "required",
        List.of(ITEMS_FIELD));
  }

  /**
   * 提议不敏感、不破坏：要问人的是<b>包</b>（裁决面，RB2b），不是"提议"这个动作。
   *
   * <p>报 {@code ALLOW} 的含义是"我判断这次调用不需要人看"（{@code ToolGate} 的诚实边界），不是"我证明了它安全"——
   * 包能不能落盘由裁决面与提交口回答，本工具只负责登记。
   */
  @Override
  public ToolGate gate(ToolContext context) {
    return ToolGate.ALLOW;
  }

  /** 提议工具本身是 {@code DEFAULT} 级、非敏感、非破坏（设计 §5.9 ② 的冻结形状；子 Agent 提议是有意开放的）。 */
  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, false, false);
  }

  /**
   * {@code tool.call} 事件的参数视图（覆写理由见类注释的密钥纪律）：<b>只落条目数 + 工具名 + 摘要</b>。
   *
   * <p>它由管线在<b>调判定链之前</b>调用（{@code AgentPipeline.executeToolCall}），此时参数还没经过任何校验，
   * 所以这里<b>必须容错</b>：形状不对就如实落"无法解析"，<b>绝不</b>因为脱敏失败把调用路径弄崩， 也绝不退回明文（缺省实现就是明文，那正是要避免的）。
   */
  @Override
  public Map<String, Object> ledgerArgs(ToolContext context) {
    Object raw = context.arguments().get(ITEMS_FIELD);
    if (!(raw instanceof List<?> list)) {
      return Map.of(ITEMS_FIELD, raw == null ? "未提供" : "无法解析（不是数组）");
    }
    List<String> tools = new ArrayList<>(list.size());
    List<String> digests = new ArrayList<>(list.size());
    for (Object element : list) {
      String tool = "";
      String digest = "";
      if (element instanceof Map<?, ?> item) {
        Object toolRaw = item.get(TOOL_FIELD);
        if (toolRaw instanceof String name && !name.isBlank()) {
          tool = name;
        }
        Object argsRaw = item.get(ARGS_FIELD);
        if (argsRaw instanceof Map<?, ?> map) {
          digest = digestQuietly(stringKeyed(map));
        }
      }
      tools.add(tool);
      digests.add(digest);
    }
    return Map.of(
        "itemCount", list.size(), "tools", List.copyOf(tools), "digests", List.copyOf(digests));
  }

  @Override
  public ToolResult execute(ToolContext context) {
    Objects.requireNonNull(context, "context");
    // ① 身份先判（fail-closed）：它不由模型给，缺了就说明装配没把这条链绑上，后面做什么都没意义
    AgentIdentity identity = context.identity();
    if (identity == null || identity.isUnknown()) {
      return refuse(
          IDENTITY_UNAVAILABLE,
          -1,
          null,
          "拿不到宿主绑定的实例身份（ToolContext.identity 未穿透）：决策包必须锚到一个实例，" + "不能用共享的占位 id 当 proposerKey");
    }
    // ② 结构 + 上限
    List<ProposedCall> calls;
    try {
      calls = parseItems(context.arguments());
    } catch (Refusal refusal) {
      return refuse(refusal.code(), refusal.index(), refusal.toolName(), refusal.reason());
    }
    // ③ 逐条校验：结构面（工具在不在 / 可不可提议）在这里预检，权限面问装配注入的校验器；
    //    交给 propose 的也是同一个校验器 ⇒ 两处调的是同一份规则，不是两份实现。
    ItemValidator validator =
        Objects.requireNonNull(
            validators.forCaller(context.permissions()),
            "ValidatorSource.forCaller 必须返回非 null 的 ItemValidator（校验不了 ≠ 通过）");
    for (int index = 0; index < calls.size(); index++) {
      ProposedCall call = calls.get(index);
      Optional<String> denial = structuralDenial(registry, call.toolName());
      if (denial.isEmpty()) {
        denial =
            Objects.requireNonNull(validator.deny(call.toolName(), call.args()), "deny 必须非 null");
      }
      if (denial.isPresent()) {
        // RB1 的具名拒码：某条目没过 ItemValidator ⇒ 整包不建（连包 id 都不发）
        return refuse(DecisionPackages.ITEM_DENIED, index, call.toolName(), denial.get());
      }
    }
    List<PackageItem> items = new ArrayList<>(calls.size());
    for (int index = 0; index < calls.size(); index++) {
      ProposedCall call = calls.get(index);
      String digest = argsDigest(call.args());
      items.add(
          new PackageItem(
              index, call.toolName(), call.args(), digest, call.toolName() + " " + digest));
    }
    ProposeRequest request =
        new ProposeRequest(
            identity.instanceId(),
            context.caller(),
            items,
            System.currentTimeMillis() + DEFAULT_DEADLINE_MILLIS);
    DecisionPackage created;
    try {
      created = packages.propose(request, validator);
    } catch (DecisionPackages.RejectionException rejection) {
      // 具名拒转结构化字段：码原样透出（PACKAGE_ALREADY_OPEN 等），不带栈
      return refuse(rejection.code(), -1, null, rejection.getMessage());
    }
    LOG.info(
        "决策包已登记 id={} proposerKey={} items={} deadlineEpochMs={}",
        created.id(),
        created.proposerKey(),
        created.items().size(),
        created.deadlineEpochMs());
    return ToolResult.ok(successJson(created));
  }

  /** 成功出参：包 id + 状态 + 到点时刻 + 逐条摘要（只含 index/tool/digest，绝不含参数明文）。 */
  private static String successJson(DecisionPackage decisionPackage) {
    List<Map<String, Object>> items = new ArrayList<>(decisionPackage.items().size());
    for (PackageItem item : decisionPackage.items()) {
      items.add(
          Map.of("index", item.index(), "tool", item.toolName(), "digest", item.argsDigest()));
    }
    return json(
        Map.of(
            "packageId", decisionPackage.id(),
            "state", decisionPackage.state().name(),
            "deadlineEpochMs", decisionPackage.deadlineEpochMs(),
            "items", items));
  }

  /** 具名拒 → 结构化 {@link ToolResult}（{@code code} 与出参里的 {@code error} 同码）。 */
  private static ToolResult refuse(String code, int index, String toolName, String reason) {
    String body =
        index < 0 ? refusalJson(code, reason) : itemRefusalJson(code, index, toolName, reason);
    return ToolResult.error(code, body);
  }

  private static String refusalJson(String code, String reason) {
    return json(Map.of("error", code, "reason", reason == null ? "" : reason));
  }

  /** 条目级具名拒：一定带 {@code index}；{@code tool} 拿得到才带（形状坏到连工具名都没有时省略）。 */
  private static String itemRefusalJson(String code, int index, String toolName, String reason) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", code);
    body.put("index", index);
    if (toolName != null && !toolName.isBlank()) {
      body.put("tool", toolName);
    }
    body.put("reason", reason == null ? "" : reason);
    return json(body);
  }

  /**
   * 解析并校验 {@code items} 的形状（不碰权限面）。
   *
   * <p>这里只做"是不是一个合法的调用数组"：条目数、每条的 tool 与 args 形状、顶层参数值不得为 null （{@link PackageItem} 的顶层不允许 null 值，同
   * {@code ToolDef}/{@code ToolCall} 口径——在这里拒， 好过让构造期抛一个不带条目信息的 NPE）。
   */
  private static List<ProposedCall> parseItems(Map<String, Object> arguments) {
    Object raw = arguments.get(ITEMS_FIELD);
    if (raw == null) {
      throw new Refusal(INVALID_ARGUMENTS, -1, null, "缺少 items 参数：它是本次提议的调用数组");
    }
    if (!(raw instanceof List<?> list)) {
      throw new Refusal(INVALID_ARGUMENTS, -1, null, "items 必须是数组，收到" + typeName(raw));
    }
    if (list.isEmpty()) {
      throw new Refusal(INVALID_ARGUMENTS, -1, null, "items 不得为空：一个包至少要有一条调用");
    }
    if (list.size() > MAX_ITEMS) {
      throw new Refusal(
          ITEM_LIMIT_EXCEEDED,
          -1,
          null,
          "items 共 " + list.size() + " 条，超过单包上限 " + MAX_ITEMS + " 条：请拆成多个包再提");
    }
    List<ProposedCall> calls = new ArrayList<>(list.size());
    for (int index = 0; index < list.size(); index++) {
      Object element = list.get(index);
      if (!(element instanceof Map<?, ?> item)) {
        throw new Refusal(
            INVALID_ARGUMENTS,
            index,
            null,
            "items[" + index + "] 必须是对象 {tool, args}，收到" + typeName(element));
      }
      Object toolRaw = item.get(TOOL_FIELD);
      if (!(toolRaw instanceof String tool) || tool.isBlank()) {
        throw new Refusal(INVALID_ARGUMENTS, index, null, "items[" + index + "].tool 必须是非空白字符串");
      }
      calls.add(new ProposedCall(tool, argsOf(item, index, tool)));
    }
    return List.copyOf(calls);
  }

  /** 单个条目的参数：缺省 = 无参数；给了就必须是对象，且顶层键/值都不得为 null。 */
  private static Map<String, Object> argsOf(Map<?, ?> item, int index, String tool) {
    Object raw = item.get(ARGS_FIELD);
    if (raw == null) {
      return Map.of();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new Refusal(
          INVALID_ARGUMENTS, index, tool, "items[" + index + "].args 必须是对象，收到" + typeName(raw));
    }
    Map<String, Object> args = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      Object key = entry.getKey();
      if (!(key instanceof String name) || name.isBlank()) {
        throw new Refusal(INVALID_ARGUMENTS, index, tool, "items[" + index + "].args 含空白或非字符串的参数名");
      }
      if (entry.getValue() == null) {
        throw new Refusal(
            INVALID_ARGUMENTS,
            index,
            tool,
            "items[" + index + "].args." + name + " 的值为 null（顶层参数不允许 null；要表达空值请省略该参数）");
      }
      args.put(name, entry.getValue());
    }
    return Map.copyOf(args);
  }

  /** 解析结果：一条待提议的调用（工具名 + 参数明文）。 */
  private record ProposedCall(String toolName, Map<String, Object> args) {}

  /** 形状/上限类具名拒的载体（不进 {@code DecisionPackages} 的状态机，纯参数面）。 */
  private static final class Refusal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final int index;
    private final String toolName;

    Refusal(String code, int index, String toolName, String reason) {
      super(reason);
      this.code = code;
      this.index = index;
      this.toolName = toolName;
    }

    String code() {
      return code;
    }

    /** {@code -1} = 不是条目级的拒。 */
    int index() {
      return index;
    }

    String toolName() {
      return toolName;
    }

    String reason() {
      return getMessage();
    }
  }

  /**
   * 参数摘要：<b>规范化 JSON</b> 的 SHA-256，取前 {@value #DIGEST_HEX_CHARS} 个十六进制字符（小写）。
   *
   * <p><b>为什么"规范化"是必需的</b>：同一个参数对象在两次调用里可能由不同的 Map 实现承载（插入序不同）， 直接对 {@code toString()}
   * 取哈希会让"同一份参数"得到两个摘要——摘要就对不上账了。规范化 = 键按字典序、无空白、 数字统一拼写（见 {@link #number(Number)}）。
   *
   * <p>自己实现（不引新依赖、也不用 Jackson 的默认序列化）：默认序列化保持插入序，恰是这里要消除的东西。
   */
  private static String argsDigest(Map<String, Object> args) {
    MessageDigest sha256;
    try {
      sha256 = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      // JVM 必须支持 SHA-256（JDK 规范要求）；真没有就是运行环境坏了，响亮失败
      throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
    }
    byte[] hash = sha256.digest(json(args).getBytes(StandardCharsets.UTF_8));
    int bytes = DIGEST_HEX_CHARS / 2;
    StringBuilder hex = new StringBuilder(DIGEST_HEX_CHARS);
    for (int i = 0; i < bytes; i++) {
      hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
      hex.append(Character.forDigit(hash[i] & 0xF, 16));
    }
    return hex.toString();
  }

  /** 摘要的容错版（{@code ledgerArgs} 用）：算不出来就空串，绝不因为脱敏把调用路径弄崩。 */
  private static String digestQuietly(Map<String, Object> args) {
    try {
      return argsDigest(args);
    } catch (RuntimeException e) {
      return "";
    }
  }

  /** 把任意 Map 的键收成字符串（{@code ledgerArgs} 只做展示，不承担判定）。 */
  private static Map<String, Object> stringKeyed(Map<?, ?> map) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      out.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    return out;
  }

  /** 人类可读的形状名（拒因文案用；只描述类型，不含值）。 */
  private static String typeName(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Map<?, ?>) {
      return "对象";
    }
    if (value instanceof List<?>) {
      return "数组";
    }
    if (value instanceof String) {
      return "字符串";
    }
    if (value instanceof Number) {
      return "数值";
    }
    if (value instanceof Boolean) {
      return "布尔";
    }
    return value.getClass().getSimpleName();
  }

  /** 规范化 JSON（键按字典序、无空白）——摘要的输入与给模型的出参共用同一个序列化器，不各写一套。 */
  private static String json(Object value) {
    StringBuilder out = new StringBuilder();
    writeJson(value, out);
    return out.toString();
  }

  private static void writeJson(Object value, StringBuilder out) {
    if (value == null) {
      out.append("null");
      return;
    }
    if (value instanceof String text) {
      writeString(text, out);
      return;
    }
    if (value instanceof Boolean flag) {
      out.append(flag.booleanValue());
      return;
    }
    if (value instanceof Number numeric) {
      out.append(number(numeric));
      return;
    }
    if (value instanceof Map<?, ?> map) {
      writeObject(map, out);
      return;
    }
    if (value instanceof Collection<?> collection) {
      writeArray(collection, out);
      return;
    }
    if (value.getClass().isArray()) {
      out.append('[');
      int length = Array.getLength(value);
      for (int i = 0; i < length; i++) {
        if (i > 0) {
          out.append(',');
        }
        writeJson(Array.get(value, i), out);
      }
      out.append(']');
      return;
    }
    // 兜底：非 JSON 类型（理论上到不了——参数来自 JSON 解析）按其文本形式当字符串，保证"同一个值同一个拼写"
    writeString(String.valueOf(value), out);
  }

  /** 对象：键按字典序（规范化的核心一步），值递归。 */
  private static void writeObject(Map<?, ?> map, StringBuilder out) {
    TreeMap<String, Object> sorted = new TreeMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      sorted.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    out.append('{');
    boolean first = true;
    for (Map.Entry<String, Object> entry : sorted.entrySet()) {
      if (!first) {
        out.append(',');
      }
      first = false;
      writeString(entry.getKey(), out);
      out.append(':');
      writeJson(entry.getValue(), out);
    }
    out.append('}');
  }

  private static void writeArray(Collection<?> collection, StringBuilder out) {
    out.append('[');
    boolean first = true;
    for (Object element : collection) {
      if (!first) {
        out.append(',');
      }
      first = false;
      writeJson(element, out);
    }
    out.append(']');
  }

  /**
   * 数字的统一拼写：整数族原样 {@code toString}；浮点族去掉科学计数法与尾随零 （{@code 1.0} 与 {@code 1} 都写成 {@code
   * 1}）——规范化要求"相等的值同一个拼写"。
   *
   * <p>{@code NaN}/{@code Infinity} 不是合法 JSON 数字：这里保留其字面量（摘要只需稳定）， 而它们<b>不可能</b>进响应面（响应只含
   * index/tool/digest，从不含参数值）。
   */
  private static String number(Number value) {
    if (value instanceof Double || value instanceof Float) {
      double raw = value.doubleValue();
      if (Double.isNaN(raw) || Double.isInfinite(raw)) {
        return value.toString();
      }
      return BigDecimal.valueOf(raw).stripTrailingZeros().toPlainString();
    }
    if (value instanceof BigDecimal decimal) {
      return decimal.stripTrailingZeros().toPlainString();
    }
    return value.toString();
  }

  private static void writeString(String text, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            String hex = Integer.toHexString(c);
            out.append("\\u").append("0".repeat(4 - hex.length())).append(hex);
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
