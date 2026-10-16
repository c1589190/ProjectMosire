package io.mosire.brain.decision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <b>{@code submit_decision_package}</b>——子 Agent 把"这一轮要问的事"跨进程交回父侧结算的<b>唯一入口</b> （设计 §5quinquies ②
 * 的工具面那一半，P3a）。
 *
 * <p><b>它替谁问</b>：子体这一轮攒下的待裁决条目（它自己进程里的那个包，子体的 {@code PendingDecisionQueue}） 经父侧 MCP
 * 桥送到这里，父侧跑级联（{@link DecisionCascade}）、必要时投既有审批面等人，再把<b>终局裁决</b> 交回子体。结算是 {@link
 * CrossProcessDecisionGateway} 做的，本类只负责"形状 + 身份核对 + 出参拼装"。
 *
 * <h2>★ 它不是 {@code DecisionProposable}（那个接口已随裁定 6 删除）</h2>
 *
 * <p>它就是一只普通的、<b>不报 {@code Ask}</b> 的工具：它做的事本来就是"<b>替子体问</b>"——它的内部会去调审批面，
 * 但那是它<b>实现的一部分</b>，不是它自己要走一次闸。若它自己再报一个 {@code Ask}，父侧就会为"替子体问"这件事 再问一次，而这次问又会走同一条路 ⇒ <b>死循环</b>。所以
 * {@code gate()} 保持缺省（{@code ALLOW}）， 三要素取 {@link ToolSpec#DEFAULT}（guest 级、非敏感非破坏、<b>可外发</b>）。
 *
 * <h2>★ 可见面（设计 §5quinquies ② + A-6）</h2>
 *
 * <p>{@code ToolSpec.DEFAULT} 的 {@code noExport = false} ⇒ 它进得了父侧 MCP 桥的外发面。子体的可见面 =
 * <b>它自己的判定面</b>（{@code SubProcessExecutor} 用 {@code PermissionChecker.allows} 这一个 oracle 收窄），
 * 所以"子体看得见"与"子体调得动"是同一件事，不会出现"看见一调就吃 PERMISSION_DENIED"的形状。主 Agent 也可见 （同一条链：它自己的工具面就是这个 registry）。
 *
 * <h2>★ 两层身份核对（防冒充）</h2>
 *
 * <ol>
 *   <li><b>本类</b>：{@code proposerKey} 必须与<b>调用者身份</b>（{@link ToolContext#identity()}）一致——
 *       子体只能替<b>自己</b>提交（{@code proposerKey == identity.instanceId()}），主 Agent 用自己的 id （{@link
 *       AgentIdentity#MAIN_ID}）提交。身份由父侧为每条 MCP 链接绑好，模型给不出、子进程改不了；
 *   <li><b>网关</b>：{@code proposerKey} 必须在<b>实例注册表</b>里解析得出（{@link CrossProcessDecisionGateway}），
 *       认不出 ⇒ 具名拒 {@link CrossProcessDecisionGateway#PROPOSER_UNKNOWN}。
 * </ol>
 *
 * <p>核对通过之后，级联用的权限集 / 档位 / 父级<b>全部取自注册表那一份</b>——入参里没有任何身份字段可被信任 （{@code items[].*} 一个都不参与"我是谁 /
 * 我能干什么"的判定）。
 *
 * <h2>★ 出参（结构化 JSON，<b>不含参数明文</b>）</h2>
 *
 * <pre>
 * {"approvedIndexes":[0,2],
 *  "rejectedIndexes":[1],
 *  "perItemReasons":{"0":"级联预裁定批准（该级有能力执行这条）", …},   // fail-closed 时为 null
 *  "trail":{"levels":[{"decider":…,"mode":…,"approved":[…],"rejected":[…],"reason":…}],
 *           "escalatedToHuman":[…],"finalApproved":[…],"finalizedBy":…},
 *  "finalizedBy":"human",
 *  "deciderLevel":"HUMAN"}
 * </pre>
 *
 * <p>失败（无可用通道 / 无人答复 / 判定失败）⇒ <b>零批准</b>：{@code approvedIndexes} 为空、全部条目进 {@code
 * rejectedIndexes}、{@code trail} 与 {@code finalizedBy} 为 null、{@code deciderLevel = "NONE"}， 并带一个
 * {@code code} 说明是哪一档（子体据此零落盘）。形状错误 / 提出者认不出 ⇒ {@link ToolResult#error} （具名拒<b>原样带回子体</b>），同一个 JSON
 * 形状照给（{@code approvedIndexes} 空 = 零批准）。
 *
 * <h2>密钥纪律（红线 6）</h2>
 *
 * <p>入参里的 {@code items[].args} 是明文，它只被搬进临时包内的条目（内存态，一趟调用即弃）；本类的出参、日志与 错误消息里<b>一个字节的明文都没有</b>（只有
 * index、工具名、理由与轨迹）。
 */
public final class SubmitDecisionPackageTools {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 工具名（父侧注册；子体经 MCP 桥看得见它）。 */
  public static final String NAME = "submit_decision_package";

  /** 条目数上限：不是限额，是"调用方拼错了"的响亮出口（一轮的待裁决队列本身也有上限）。 */
  private static final int MAX_ITEMS = 512;

  private SubmitDecisionPackageTools() {
    throw new AssertionError("No instances");
  }

  /**
   * 工具（宿主注册进自己的 registry）。
   *
   * @param gateway 结算口（父侧装配好的那一个；见 {@link CrossProcessDecisionGateway}）
   */
  public static AgentTool tool(CrossProcessDecisionGateway gateway) {
    Objects.requireNonNull(gateway, "gateway");
    return new AgentTool() {
      @Override
      public String name() {
        return NAME;
      }

      @Override
      public String description() {
        return "把本轮的待裁定条目跨进程交回父侧结算（父侧跑逐级预裁定、必要时问人），返回终局裁决："
            + "approvedIndexes（只准执行这些）/ rejectedIndexes / perItemReasons / trail（谁裁的）/ "
            + "finalizedBy / deciderLevel。proposerKey 必须是你自己的实例 id。"
            + "父侧只回裁决、不替你执行；失败一律零批准（approvedIndexes 为空）。不含参数明文。";
      }

      @Override
      public Map<String, Object> jsonSchema() {
        return Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "proposerKey",
                Map.of("type", "string", "description", "你自己的实例 id（父侧与实例注册表核对；认不出即具名拒）"),
                "requestId",
                Map.of(
                    "type",
                    "string",
                    "description",
                    "可选幂等键：你本地那个包的 id。父侧按它缓存上次裁决——重试同一个 requestId 不会让人再答一遍"
                        + "（同一个 requestId 必须带同一批条目；换了条目请换 requestId）。省略 = 每次都是新请求。"),
                "items",
                Map.of(
                    "type",
                    "array",
                    "description",
                    "待裁定的条目（至少一条；index 是它在你自己包内的身份）",
                    "items",
                    Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                            "toolName", Map.of("type", "string", "description", "要执行的工具名"),
                            "args", Map.of("type", "object", "description", "该次调用的参数（明文只进父侧临时包）"),
                            "classKey", Map.of("type", "string", "description", "闸位类键（父侧不参与判定）"),
                            "askKind",
                                Map.of(
                                    "type",
                                    "string",
                                    "description",
                                    "为什么问：MODE_LIMITED（可被逐级预裁定）| SENSITIVE（强制到人）；"
                                        + "省略 = SENSITIVE"),
                            "index",
                                Map.of(
                                    "type", "integer", "description", "条目在你包内的身份（省略 = 数组下标）"))))));
      }

      @Override
      public ToolSpec spec() {
        // ★ DEFAULT：guest 级、非敏感非破坏、noExport=false（子体经 MCP 桥必须看得见它，见类注释的可见面一节）
        return ToolSpec.DEFAULT;
      }

      @Override
      public ToolResult execute(ToolContext context) {
        // ★ 第一层身份核对：调用者身份由父侧绑在 MCP 链接上（模型给不出、子进程改不了）⇒ 它是唯一的身份来源
        String callerId =
            context.identity() == null || context.identity().isUnknown()
                ? null
                : context.identity().instanceId();
        if (callerId == null || callerId.isBlank()) {
          // 身份没穿透 ⇒ 不猜、不代任何实例提交（fail-closed）
          return error("NO_IDENTITY", "调用者身份未穿透（instanceId=unknown）：无法核对 proposerKey，拒绝代提交");
        }
        CrossProcessDecisionGateway.Request request;
        try {
          request = requestOf(context, callerId);
        } catch (IllegalArgumentException bad) {
          return error("INVALID_ARGUMENTS", bad.getMessage());
        }
        try {
          CrossProcessDecisionGateway.Verdict verdict = gateway.submit(request);
          return ToolResult.ok(JSON.writeValueAsString(body(verdict)));
        } catch (IllegalArgumentException refused) {
          // ★ 具名拒（提出者不在注册表 / 入参形状）——原样带回子体：同一个 JSON 形状照给，零批准照给
          String message = String.valueOf(refused.getMessage());
          String code =
              message.startsWith(CrossProcessDecisionGateway.PROPOSER_UNKNOWN)
                  ? CrossProcessDecisionGateway.PROPOSER_UNKNOWN
                  : CrossProcessDecisionGateway.INVALID_ITEMS;
          return error(code, message);
        } catch (JsonProcessingException impossible) {
          // 序列化自己的出参失败（理论上到不了：全是 String/List/Map）：响亮失败，绝不吞成"成功"
          return error("RESULT_UNSERIALIZABLE", "结算结果无法序列化: " + impossible.getMessage());
        }
      }
    };
  }

  /**
   * 入参 → 结算请求（<b>只做形状与身份，不做任何权限判定</b>——那是网关与级联的事）。
   *
   * @param callerId 调用者实例 id（来自 {@link ToolContext#identity()}，<b>不可自报</b>）
   * @throws IllegalArgumentException 形状错误，或 {@code proposerKey} 与调用者身份不一致
   */
  private static CrossProcessDecisionGateway.Request requestOf(
      ToolContext context, String callerId) {
    Object rawProposer = context.arguments().get("proposerKey");
    String proposerKey = rawProposer == null ? "" : String.valueOf(rawProposer).strip();
    if (proposerKey.isBlank()) {
      throw new IllegalArgumentException("proposerKey 不得为空：它必须是<b>你自己的</b>实例 id（" + callerId + "）");
    }
    if (!proposerKey.equals(callerId)) {
      // 子体只能替自己提交：自报一个别的 id 不是"提别人的包"，是冒充（网关那一层还会再核一遍注册表）
      throw new IllegalArgumentException(
          "proposerKey 与调用者身份不一致：你只能以自己的实例 id（" + callerId + "）提交，收到 " + proposerKey);
    }
    Object rawItems = context.arguments().get("items");
    if (!(rawItems instanceof List<?> list)) {
      throw new IllegalArgumentException("items 必须是数组（每条形如 {toolName, args, classKey, askKind}）");
    }
    if (list.isEmpty()) {
      throw new IllegalArgumentException("items 不得为空：一个包至少要有一条待裁定的调用");
    }
    if (list.size() > MAX_ITEMS) {
      throw new IllegalArgumentException("items 超过上限 " + MAX_ITEMS + " 条（几乎肯定是拼错了）：" + list.size());
    }
    List<CrossProcessDecisionGateway.Item> items = new ArrayList<>(list.size());
    for (int i = 0; i < list.size(); i++) {
      items.add(itemOf(list.get(i), i));
    }
    // ★ P3b：可选幂等键（= 子体本地那个包的 id）。空白 = 没给 ⇒ 每次都是新请求（P3a 的形状逐字保留）。
    //   长度上限由网关的 validate 统一判（这里不写第二份判据）：超限同样落进 INVALID_ARGUMENTS 那一档。
    Object rawRequestId = context.arguments().get("requestId");
    String requestId = rawRequestId == null ? "" : String.valueOf(rawRequestId).strip();
    return new CrossProcessDecisionGateway.Request(proposerKey, items, requestId);
  }

  /** 单条条目（{@code index} 缺省 = 数组下标；{@code askKind} 缺省 = SENSITIVE，最保守的一侧）。 */
  private static CrossProcessDecisionGateway.Item itemOf(Object raw, int position) {
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("items[" + position + "] 必须是对象");
    }
    Object toolName = map.get("toolName");
    if (toolName == null || String.valueOf(toolName).isBlank()) {
      throw new IllegalArgumentException("items[" + position + "].toolName 不得为空");
    }
    Object rawArgs = map.get("args");
    if (rawArgs != null && !(rawArgs instanceof Map<?, ?>)) {
      throw new IllegalArgumentException("items[" + position + "].args 必须是对象（省略 = 无参数）");
    }
    Map<String, Object> args = new LinkedHashMap<>();
    if (rawArgs != null) {
      // ★ 这里用强转而不是再写一次 instanceof：上面那一问已经定死了"非 null ⇒ 一定是 Map"，
      //   重复问一次会被 SpotBugs 判成 BC_VACUOUS_INSTANCEOF——那不是误报，是同一个判断写了两遍。
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawArgs).entrySet()) {
        if (entry.getKey() == null) {
          throw new IllegalArgumentException("items[" + position + "].args 含 null 键");
        }
        args.put(String.valueOf(entry.getKey()), entry.getValue());
      }
    }
    Object classKey = map.get("classKey");
    Object askKind = map.get("askKind");
    return new CrossProcessDecisionGateway.Item(
        intOf(map.get("index"), position, "items[" + position + "].index"),
        String.valueOf(toolName).strip(),
        args,
        classKey == null ? "" : String.valueOf(classKey),
        // 缺省 = SENSITIVE（强制到人）：没说明"为什么问"的条目落进人手里，不落进任何 Agent 的代批面
        askKind == null || String.valueOf(askKind).isBlank()
            ? AskKind.SENSITIVE
            : askKindOf(String.valueOf(askKind).strip(), position));
  }

  /** {@code askKind} 的取值（认不出的值**不**静默降级：响亮拒——静默降级会把它变成"可以交上级代批"那一侧）。 */
  private static AskKind askKindOf(String value, int position) {
    try {
      return AskKind.valueOf(value);
    } catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException(
          "items[" + position + "].askKind 只认 MODE_LIMITED / SENSITIVE，收到: " + value);
    }
  }

  /** 整数取值（缺省 = {@code fallback}；非整数 ⇒ 抛）。 */
  private static int intOf(Object value, int fallback, String field) {
    if (value == null) {
      return fallback;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value).strip());
    } catch (NumberFormatException bad) {
      throw new IllegalArgumentException(field + " 必须是整数，收到: " + value);
    }
  }

  /**
   * 失败结果（{@code code} + 一行消息 + 一个<b>零批准</b>的 JSON 形状；消息里不含参数明文）。
   *
   * <p>{@code rejectedIndexes} 在真拒时是<b>空表</b>而不是"全部条目"：这里拒的是<b>整次提交</b>（形状不成立 /
   * 提出者认不出）——那时"包里有哪几条"本身就没有可信答案，编一份"这几条被拒了"是把一个坏请求洗成一条裁决。 子体看到空表 + 非空 {@code code}
   * 就知道：<b>这次提交没有被受理</b>，一切照旧（零落盘）。
   */
  private static ToolResult error(String code, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("approvedIndexes", List.of());
    body.put("rejectedIndexes", List.of());
    body.put("perItemReasons", null);
    body.put("trail", null);
    body.put("finalizedBy", null);
    body.put("deciderLevel", CrossProcessDecisionGateway.DECIDER_NONE);
    body.put("code", code);
    try {
      return ToolResult.error(code, message + "；" + JSON.writeValueAsString(body));
    } catch (JsonProcessingException impossible) {
      // 兜底：连回执都拼不出来时也要给一个带码的失败（绝不吞成成功）
      return ToolResult.error(code, message);
    }
  }

  /**
   * 出参体（结构化 JSON，<b>不含参数明文</b>）：设计 §5quinquies ② 的六项。
   *
   * <p>{@code trail} 的形状与 {@code decision.package.decided} 载荷里那个 {@code trail} <b>逐字同形</b>
   * （同一套键：{@code levels / escalatedToHuman / finalApproved / finalizedBy}）——子体把回执落进自己的
   * 事件库时，读侧（{@code decision_history}）不必认识第二套轨迹词汇。
   *
   * <p><b>为什么没有 {@code code} 键</b>：fail-closed 的那几档在网关里是<b>零批准的 {@link
   * CrossProcessDecisionGateway.Verdict}</b>，它对子体要说的就是"一条都没批"（{@code deciderLevel = NONE}）；
   * 具体是哪一档写在父侧日志里。真拒（提出者认不出 / 形状错）走 {@link ToolResult#error}，码在 {@code ToolResult.code()} 上、同形的
   * JSON 在消息里——两处一个来源，不在这里再编一个码。
   */
  private static Map<String, Object> body(CrossProcessDecisionGateway.Verdict verdict) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("approvedIndexes", List.copyOf(verdict.approvedIndexes()));
    body.put("rejectedIndexes", List.copyOf(verdict.rejectedIndexes()));
    body.put("perItemReasons", reasonRows(verdict.perItemReasons()));
    body.put("trail", trailRow(verdict.trail()));
    body.put("finalizedBy", verdict.finalizedBy());
    body.put("deciderLevel", verdict.deciderLevel());
    return body;
  }

  /** 逐条理由行（{@code index / reason}；{@code null} = 未逐条说明，键在而值为 null——不编理由）。 */
  private static List<Map<String, Object>> reasonRows(Map<Integer, String> reasons) {
    if (reasons == null) {
      return null;
    }
    List<Map<String, Object>> rows = new ArrayList<>(reasons.size());
    for (Map.Entry<Integer, String> entry : reasons.entrySet()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("index", entry.getKey());
      row.put("reason", entry.getValue());
      rows.add(row);
    }
    return rows;
  }

  /** 轨迹行（与 {@code DecisionApprovalBridge#trailRow} 逐字同形；没有轨迹 ⇒ {@code null}）。 */
  private static Map<String, Object> trailRow(DecisionCascade.DecisionTrail trail) {
    if (trail == null) {
      return null;
    }
    List<Map<String, Object>> levels = new ArrayList<>(trail.levels().size());
    for (DecisionCascade.PreDecision level : trail.levels()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("decider", level.deciderId());
      row.put("mode", level.deciderMode().name());
      row.put("approved", List.copyOf(level.approvedIndexes()));
      row.put("rejected", List.copyOf(level.rejectedIndexes()));
      row.put("reason", level.reason());
      levels.add(row);
    }
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("levels", levels);
    row.put("escalatedToHuman", List.copyOf(trail.escalatedToHuman()));
    row.put("finalApproved", List.copyOf(trail.finalApproved()));
    row.put("finalizedBy", trail.finalizedBy());
    return row;
  }
}
