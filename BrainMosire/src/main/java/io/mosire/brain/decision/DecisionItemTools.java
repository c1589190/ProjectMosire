package io.mosire.brain.decision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.decision.DecisionPackage;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.DecisionSubmission;
import io.mosire.agentlib.decision.DecisionSubmissionPort;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>条目级裁决 + 执行命令</b>（用户裁定 15 ②③ · 2026-10-17）：{@code decide_decision_item} 与 {@code
 * commit_decision_package} 两个工具。
 *
 * <p><b>为什么是两个工具、公一个类</b>：它们合起来才是用户口中那条链（「用 event 命令看、裁决完，用裁决包命令执行」）—— 看：{@link
 * DecisionHistoryTools}（{@code packageId + index}）；裁：{@code decide_decision_item}；执行：{@code
 * commit_decision_package}。把"裁"与"执行"分开正是用户原话的直接含义（「要不然分 event 命令和裁决包执行命令干什么？」）， 而两者的三要素（级别 / 敏感 /
 * 外发）与错误映射完全同源，所以放一个类里共用一处翻译。
 *
 * <p><b>★ 命名（设计 §5septies ⑦）</b>：用户口中的"event 命令"在本仓落到 {@code decision_history}（扩 {@code index} 与
 * {@code full}）+ {@code decide_decision_item}；"裁决包执行命令"落到 {@code commit_decision_package}。
 * <b>不叫</b> {@code event_*}——本仓"事件"专指事件存储（{@code event_query} 那一族），同名会混。
 *
 * <p><b>★ 三要素都是 {@code noExport = true}</b>：这两个工具<b>只对包真正住在哪个进程</b>成立—— 主进程的 Agent 裁主进程的包（{@code
 * App} 装配）。子体进程里包是它自己建的、结算走跨进程提交， 它的工具面由父侧桥填充；把这两个工具外发给子体只会让它对着一个它改不了的包（父侧那份临时簿子）发命令——
 * 那是"看起来能用、其实答非所问"的形状。裁决者在子体这一侧的表达是 {@code DECISION_QUEUED} 与回执流程，不是这两个工具。
 *
 * <p><b>★ 明文纪律</b>：两个工具的入参都只有 id / index / 枚举 / 可空一行话，<b>没有参数明文</b>；出参是状态机回执的投影 （状态 +
 * 逐条去向），也不含明文。要明文那是 {@code decision_history} 的 {@code full} 那一档的事 （并且要显式要求）。
 */
public final class DecisionItemTools {

  private static final Logger LOG = LoggerFactory.getLogger(DecisionItemTools.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 条目级裁决工具名（模型面唯一入口）。 */
  public static final String DECIDE_NAME = "decide_decision_item";

  /** 执行命令工具名（模型面唯一入口）。 */
  public static final String COMMIT_NAME = "commit_decision_package";

  /** {@code by} 的缺省值：身份没穿透时用它（审计面不编一个人名）。 */
  static final String UNKNOWN_BY = "unknown";

  private DecisionItemTools() {
    throw new AssertionError("No instances");
  }

  /**
   * 条目级裁决（用户裁定 15 ②）：入参 {@code {packageId, index, verdict: approve|reject, note?}}。
   *
   * <p>它<b>只表态</b>：要把包推到 {@code DECIDED}（全部条目都表了态）与发 {@code item_decided} 事件， 而<b>不</b>执行任何东西——执行是
   * {@link #commitTool} 那一步。裁决人取 {@link ToolContext#identity()} 的实例 id （模型给不出、改不了）。
   *
   * @param submissions 决策面写路径口（实现方 = 决策包桥；见 {@link DecisionSubmissionPort}）
   */
  public static AgentTool decideTool(DecisionSubmissionPort submissions) {
    Objects.requireNonNull(submissions, "submissions");
    return new AgentTool() {
      @Override
      public String name() {
        return DECIDE_NAME;
      }

      @Override
      public String description() {
        return "对决策包里**一条**具体决策表态（批准/驳回）。这是「一次裁决」的分段表达：包始终只有一个，"
            + "全部条目都表态完 ⇒ 包进入 DECIDED（等执行），**但不会自动执行**——执行要显式调 "
            + COMMIT_NAME
            + "。未表态的条目既不算批准、也不算驳回（保持未决）。入参不含参数明文（要看明文用 "
            + DecisionHistoryTools.NAME
            + " 的 full）。";
      }

      @Override
      public Map<String, Object> jsonSchema() {
        return Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "packageId",
                Map.of("type", "string", "description", "决策包 id（decision_history 里看到的那个）"),
                "index",
                Map.of("type", "integer", "description", "要表态的条目 index（条目的身份，不是数组下标）"),
                "verdict",
                Map.of(
                    "type",
                    "string",
                    "enum",
                    List.of("approve", "reject"),
                    "description",
                    "批准还是驳回"),
                "note",
                Map.of("type", "string", "description", "可选：一行理由（只进记录面，不参与判定）")),
            "required",
            List.of("packageId", "index", "verdict"));
      }

      @Override
      public ToolSpec spec() {
        // ★★ 只对"包真正住在本进程"的主 Agent 成立（见类注释）⇒ 要求 **SYSTEM** 级 + noExport 两道
        //   （2026-10-17 控制方裁定 3：这两道都不许撤）：
        //   ① noExport=true：**结构性**那道——工具永不进 AgentToMcpServer 的暴露面。判定落点是
        //      `AgentToMcpServer.exportable = include.test(name) && !spec.noExport()`，而**两个**外发面
        //      （子体桥 / 外部 MCP 面）走的是同一个装配段与同一个谓词 ⇒ 一处生效、两面都生效。
        //      ★ 2026-10-17 收口单复核更正：P4 收口记录曾把"DEFAULT + noExport 时这两个工具列在外部面上"
        //        写成实测机制——回代码核**不成立**（noExport 对外部面同样求交；当时真正红的是
        //        `AppMcpLinkTest#linksExternalMcpServerIntoMainRegistryAndCleansUpOnClose` 的
        //        "主 Agent 内置工具面 hasSize(20) vs 22"，与 noExport 无关）。判别力落在
        //
        // `AppMcpLinkTest#externalMcpFaceIntersectsNoExportEvenWhenTheJudgmentFaceWouldAllowIt`：
        //        include 谓词全放行时，带 noExport 的工具仍不出现在真外部面上。
        //   ② SYSTEM：**判定面**那道——外部 MCP 面的调用者缺省 GUEST（`agent.externalMcp.token`），而 A-6
        //      的可见面 = 判定面 ⇒ 万一将来有人把 noExport 漏掉（或新加一条绕过 exportable 的暴露路），
        //      这道闸仍然让外部客户端调不动这两个"裁决动作"工具。
        //   主 Agent 自己的权限集是 system() ⇒ 这两道都不影响它调用。
        return ToolSpec.level(AccessToken.SYSTEM, false, false, true);
      }

      @Override
      public ToolResult execute(ToolContext context) {
        try {
          String packageId = requiredText(context, "packageId");
          int index = requiredInt(context, "index");
          boolean approve = verdictOf(requiredText(context, "verdict"));
          DecisionSubmission submission =
              submissions.itemDecided(
                  packageId, index, approve, by(context), optionalText(context, "note"));
          return ToolResult.ok(JSON.writeValueAsString(receiptOf(submission, "itemDecided")));
        } catch (DecisionPackages.RejectionException refusal) {
          return ToolResult.error(refusal.code(), refusal.getMessage());
        } catch (IllegalArgumentException | JsonProcessingException bad) {
          return ToolResult.error("INVALID_ARGUMENTS", bad.getMessage());
        } catch (RuntimeException failure) {
          LOG.error("{} 执行失败（未落定）", DECIDE_NAME, failure);
          return ToolResult.error("DECISION_FACE_FAILED", String.valueOf(failure.getMessage()));
        }
      }
    };
  }

  /**
   * 执行命令（用户裁定 15 ③）：入参 {@code {packageId}}。
   *
   * <p>只有这一步会让被批子集落盘：{@code DECIDED} 直接执行；{@code DECIDING} 由包层先把没表态的条目按裁定 14 归成被驳
   * （零落盘、理由如实记"未表态"）再执行；一条都没批 ⇒ 具名拒 {@code NO_ITEM_DECIDED}（不是"执行了零条"）。 重复调 ⇒ 幂等（拿到同一份回执，不重复执行）。
   *
   * @param submissions 决策面写路径口（实现方 = 决策包桥）
   */
  public static AgentTool commitTool(DecisionSubmissionPort submissions) {
    Objects.requireNonNull(submissions, "submissions");
    return new AgentTool() {
      @Override
      public String name() {
        return COMMIT_NAME;
      }

      @Override
      public String description() {
        return "执行一个已裁决的决策包（两段式的第二段）：把**被批准的条目**恰好一次提交出去（"
            + "被驳回的条目零落盘）。只有 DECIDED 的包可执行；重复调用是幂等的（不会重复执行）。"
            + "★ 表态（approveAll/rejectAll/decide_decision_item）都不会自动执行——执行必须显式调它。";
      }

      @Override
      public Map<String, Object> jsonSchema() {
        return Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "packageId",
                Map.of("type", "string", "description", "要执行的决策包 id（必须是已裁决 DECIDED 的包）")),
            "required",
            List.of("packageId"));
      }

      @Override
      public ToolSpec spec() {
        // ★★ 同 decideTool：SYSTEM 级 + noExport 两道（机制复核与判别力见那里）
        return ToolSpec.level(AccessToken.SYSTEM, false, false, true);
      }

      @Override
      public ToolResult execute(ToolContext context) {
        try {
          String packageId = requiredText(context, "packageId");
          DecisionSubmission submission = submissions.commitPackage(packageId, by(context));
          return ToolResult.ok(JSON.writeValueAsString(receiptOf(submission, "commit")));
        } catch (DecisionPackages.RejectionException refusal) {
          return ToolResult.error(refusal.code(), refusal.getMessage());
        } catch (IllegalArgumentException | JsonProcessingException bad) {
          return ToolResult.error("INVALID_ARGUMENTS", bad.getMessage());
        } catch (RuntimeException failure) {
          LOG.error("{} 执行失败（未落定）", COMMIT_NAME, failure);
          return ToolResult.error("DECISION_FACE_FAILED", String.valueOf(failure.getMessage()));
        }
      }
    };
  }

  /**
   * 回执投影（工具出参，<b>不含明文</b>）：包 id / 状态 / 被批与被驳 index / 逐条去向 + 可空理由 /（执行那一步的）回执。
   *
   * <p>形状与决策面 HTTP 的回执面同源（同一件事不该有两个说法）：只把"下一步该干什么"所需的东西给全。
   */
  private static Map<String, Object> receiptOf(DecisionSubmission submission, String step) {
    DecisionPackage pkg = submission.pkg();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("step", step);
    body.put("packageId", pkg.id());
    body.put("state", pkg.state().name());
    List<Integer> approved = new ArrayList<>();
    List<Integer> rejected = new ArrayList<>();
    if (pkg.decision() != null) {
      for (var item : pkg.items()) {
        if (pkg.decision().approves(item.index())) {
          approved.add(item.index());
        } else {
          rejected.add(item.index());
        }
      }
    }
    body.put("approved", approved);
    body.put("rejected", rejected);
    body.put("itemCount", pkg.items().size());
    if (submission.receipt() == null) {
      body.put("commit", null);
      body.put("warning", submission.commitNote());
      return body;
    }
    Map<String, Object> commit = new LinkedHashMap<>();
    commit.put("status", submission.receipt().status().name());
    commit.put(
        "revisionRef",
        submission.receipt().revisionRef() == null ? "" : submission.receipt().revisionRef());
    commit.put("reason", submission.receipt().reason());
    List<Map<String, Object>> results = new ArrayList<>();
    for (var result : submission.receipt().itemResults()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("outcome", result.outcome().name());
      row.put("reason", result.reason());
      results.add(row);
    }
    commit.put("itemResults", results);
    body.put("commit", commit);
    return body;
  }

  /** 裁决人（{@link ToolContext#identity()} 的实例 id；没穿透 ⇒ {@value #UNKNOWN_BY}，但<b>不</b>编一个人名）。 */
  private static String by(ToolContext context) {
    String instanceId = context.identity() == null ? null : context.identity().instanceId();
    return instanceId == null || instanceId.isBlank() ? UNKNOWN_BY : instanceId;
  }

  private static String requiredText(ToolContext context, String name) {
    String value = optionalText(context, name);
    if (value == null) {
      throw new IllegalArgumentException(name + " 必填");
    }
    return value;
  }

  private static String optionalText(ToolContext context, String name) {
    return optional(context, name)
        .map(String::valueOf)
        .map(String::strip)
        .filter(text -> !text.isEmpty())
        .orElse(null);
  }

  private static int requiredInt(ToolContext context, String name) {
    Object value = context.arguments().get(name);
    if (value == null) {
      throw new IllegalArgumentException(name + " 必填（条目在包内的身份）");
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value).strip());
    } catch (NumberFormatException bad) {
      throw new IllegalArgumentException(name + " 必须是整数，收到: " + value);
    }
  }

  private static boolean verdictOf(String verdict) {
    return switch (verdict) {
      case "approve", "approved", "yes" -> true;
      case "reject", "rejected", "no" -> false;
      default -> throw new IllegalArgumentException("verdict 只接受 approve|reject，收到: " + verdict);
    };
  }

  private static Optional<Object> optional(ToolContext context, String name) {
    return Optional.ofNullable(context.arguments().get(name));
  }
}
