package io.mosire.brain.subagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 三个内置编排工具（中期计划 W3 组件 4；仅主 Agent 可调——注册三要素 {@code ToolSpec.level(SYSTEM, sensitive=false,
 * destructive=true)}，GUEST/DEFAULT 在管线 guard 处即被拒绝）。
 *
 * <p><strong>工具级第二道身份校验</strong>：MCP 路径（W3b 子 Agent 经父侧暴露的工具调用）不经管线 guard——{@link AgentToMcpServer}
 * 直接把调用交给工具。因此本类工具在 execute 入口再做一次 {@code caller == SYSTEM} 校验（红线 R7：子体拿父侧 SYSTEM 工具即提权，结果必须是
 * {@code PERMISSION_DENIED} 而不是执行）。
 *
 * <p>错误映射契约：参数缺失/非法 → {@code INVALID_ARGUMENTS}；编排层拒绝（单调性/深度，对应 {@link SubagentRejectedException}）→
 * {@code PERMISSION_DENIED}（可读原因经 message 透出）；未知实例 → {@code UNKNOWN_INSTANCE}；启动失败 → {@code
 * LAUNCH_FAILED}。
 *
 * <p>返回值：spawn = 新实例快照 JSON（instanceId/templateId/status/depth），kill = 终止确认文本， list =
 * 实例快照数组。所有工具幂等安全（重复 kill = 空操作成功）。
 */
public final class SubagentOrchestrationTools {

  private static final Logger LOG = LoggerFactory.getLogger(SubagentOrchestrationTools.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  public static final String SPAWN_SUB_AGENT = "spawn_sub_agent";
  public static final String KILL_SUB_AGENT = "kill_sub_agent";
  public static final String LIST_SUB_AGENTS = "list_sub_agents";

  /** 注册排序（名字字典序）：kill / list / spawn——测试与目录稳定。 */
  public static List<AgentTool> of(SubagentManager manager) {
    return List.of(kill(manager), list(manager), spawn(manager));
  }

  private SubagentOrchestrationTools() {}

  /** spawn：子 Agent 模板起实例（SYSTEM 级编排入口）。 */
  static AgentTool spawn(SubagentManager manager) {
    return tool(
        SPAWN_SUB_AGENT,
        "派生一个子 Agent（由模板驱动；深度/权限/cap 均经父级收紧）。",
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "templateId", Map.of("type", "string"),
                "goal", Map.of("type", "string"),
                "extraDenied", Map.of("type", "array", "items", Map.of("type", "string")),
                "maxTurnsCap", Map.of("type", "integer"),
                "timeBudgetSecondsCap", Map.of("type", "integer"),
                "quotaMaxTokensCap", Map.of("type", "integer")),
            "required",
            List.of("templateId", "goal")),
        context -> {
          ToolResult denied = systemOnly(context);
          if (denied != null) {
            return denied;
          }
          String templateId = strArg(context, "templateId");
          String goal = strArg(context, "goal");
          if (templateId == null || goal == null) {
            return ToolResult.error("INVALID_ARGUMENTS", "templateId 与 goal 为必填参数");
          }
          SubagentLaunchRequest request;
          try {
            request =
                new SubagentLaunchRequest(
                    templateId,
                    goal,
                    strSetArg(context, "extraDenied"),
                    intArg(context, "maxTurnsCap"),
                    longArg(context, "timeBudgetSecondsCap"),
                    longArg(context, "quotaMaxTokensCap"));
          } catch (IllegalArgumentException e) {
            // 字段存在但非数字：与类 Javadoc 一致 → INVALID_ARGUMENTS（而非静默当"未收紧"——那会无抱怨地放宽上限）
            return ToolResult.error("INVALID_ARGUMENTS", e.getMessage());
          }
          SubagentInstance instance;
          try {
            instance = manager.spawn(request);
          } catch (SubagentRejectedException e) {
            return ToolResult.error(ToolExecutionGuard.DENIED, e.getMessage());
          } catch (SubagentLaunchException e) {
            LOG.warn("spawn_sub_agent 启动失败 template={}", templateId, e);
            return ToolResult.error("LAUNCH_FAILED", e.getMessage());
          }
          return ToolResult.ok(
              toJson(
                  Map.of(
                      "instanceId", instance.instanceId(),
                      "templateId", instance.templateId(),
                      "status", instance.status().name(),
                      "depth", instance.depth())));
        });
  }

  /** kill：按实例 id 终止一个在管子 Agent（终态幂等）。 */
  static AgentTool kill(SubagentManager manager) {
    return tool(
        KILL_SUB_AGENT,
        "终止一个在管子 Agent（三层关停：关送 stdin → SIGTERM → 宽限 → 树级 KILL）。",
        Map.of(
            "type", "object",
            "properties", Map.of("instanceId", Map.of("type", "string")),
            "required", List.of("instanceId")),
        context -> {
          ToolResult denied = systemOnly(context);
          if (denied != null) {
            return denied;
          }
          String instanceId = strArg(context, "instanceId");
          if (instanceId == null) {
            return ToolResult.error("INVALID_ARGUMENTS", "instanceId 为必填参数");
          }
          try {
            manager.kill(instanceId);
          } catch (IllegalArgumentException e) {
            return ToolResult.error("UNKNOWN_INSTANCE", e.getMessage());
          }
          return ToolResult.ok("已发出子 Agent 终止请求: " + instanceId);
        });
  }

  /** list：全部子 Agent 实例快照（含非终态；按实例 id 升序）。 */
  static AgentTool list(SubagentManager manager) {
    return tool(
        LIST_SUB_AGENTS,
        "列出全部子 Agent 实例（id/模板/层级/状态快照）。",
        Map.of("type", "object"),
        context -> {
          ToolResult denied = systemOnly(context);
          if (denied != null) {
            return denied;
          }
          List<Map<String, Object>> rows = new ArrayList<>();
          for (SubagentInstance instance : manager.list()) {
            rows.add(
                Map.of(
                    "instanceId", instance.instanceId(),
                    "templateId", instance.templateId(),
                    "depth", instance.depth(),
                    "status", instance.status().name()));
          }
          return ToolResult.ok(toJson(rows));
        });
  }

  /** 身份校验（第二道闸——管线 guard 之外的 MCP 路径防线；见类注释）。 */
  private static ToolResult systemOnly(ToolContext context) {
    if (context.caller() == AccessToken.SYSTEM) {
      return null;
    }
    return ToolResult.error(ToolExecutionGuard.DENIED, "身份级别不足：系统级编排工具仅限主 Agent（SYSTEM）调用");
  }

  private static AgentTool tool(
      String name,
      String description,
      Map<String, Object> schema,
      java.util.function.Function<ToolContext, ToolResult> fn) {
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
        // 防御性拷贝（快照 schema 多为 Map.of 常量；copyOf 对不可变映射零拷贝、对可变映射挡住外部改写）
        return Map.copyOf(schema);
      }

      @Override
      public ToolSpec spec() {
        return ToolSpec.level(AccessToken.SYSTEM, false, true);
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return fn.apply(Objects.requireNonNull(context, "context"));
      }
    };
  }

  private static String strArg(ToolContext context, String name) {
    Object value = context.arguments().get(name);
    if (value == null) {
      return null;
    }
    if (value instanceof String s) {
      return s.isBlank() ? null : s;
    }
    return String.valueOf(value);
  }

  private static java.util.Set<String> strSetArg(ToolContext context, String name) {
    Object value = context.arguments().get(name);
    if (value == null) {
      return java.util.Set.of();
    }
    if (value instanceof java.util.Collection<?> collection) {
      java.util.Set<String> set = new java.util.HashSet<>();
      for (Object item : collection) {
        set.add(String.valueOf(item));
      }
      return java.util.Set.copyOf(set);
    }
    return java.util.Set.of(String.valueOf(value));
  }

  /**
   * 整数参数：字段缺失 → {@code null}（调用方按"未收紧"处理，合法）；字段存在但非数字 → 抛 {@link IllegalArgumentException}（调用方落地
   * {@code INVALID_ARGUMENTS}——绝不静默退化为"未收紧"，那会无抱怨地放宽上限）。
   */
  private static Integer intArg(ToolContext context, String name) {
    Object value = context.arguments().get(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Number n) {
      return n.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("参数 " + name + " 必须是整数: " + value);
    }
  }

  /** 同 {@link #intArg(ToolContext, String)} 的 long 版。 */
  private static Long longArg(ToolContext context, String name) {
    Object value = context.arguments().get(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Number n) {
      return n.longValue();
    }
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("参数 " + name + " 必须是整数: " + value);
    }
  }

  private static String toJson(Object payload) {
    try {
      return JSON.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("编排工具响应序列化失败", e);
    }
  }
}
