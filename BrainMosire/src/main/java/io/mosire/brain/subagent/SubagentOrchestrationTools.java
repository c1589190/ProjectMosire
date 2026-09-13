package io.mosire.brain.subagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolResult;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 四个内置编排工具（中期计划 W3 组件 4 + 三期 D30）。
 *
 * <p><b>身份级别（S5-A 起不齐了，逐个看）</b>：{@code spawn_sub_agent} 是 {@code DEFAULT}（子 Agent 也能派孙代， D28
 * 裁决"开放派生 + 检查锚到调用者"）；{@code kill_sub_agent}/{@code list_sub_agents} 仍是 {@code SYSTEM}（窄面：
 * 子体不能终止/枚举别家的实例）；{@code read_agent_context} 是 {@code SYSTEM} + {@code sensitive} + {@code
 * noExport}（禁外发：桥不转发它，子体拿不到句柄）。后三个对 GUEST/DEFAULT 调用者在管线 guard 处即被拒。
 *
 * <p>前三个（{@code spawn_sub_agent}/{@code kill_sub_agent}/{@code list_sub_agents}）形态不变；第四个 {@code
 * read_agent_context}（D30）按实例 id 直读子体上下文，spec 多一个 {@code noExport=true}（禁外发：桥不转发它， 子体拿不到句柄）+ {@code
 * sensitive=true}（读别家上下文需显式放行）。
 *
 * <p><strong>工具级身份校验（纵深防御副本，已非权威判定）</strong>：本类工具在 execute 入口校验 {@code caller == SYSTEM} （红线
 * R7：子体拿父侧 SYSTEM 工具即提权，结果必须是 {@code PERMISSION_DENIED} 而不是执行）。<b>2026-09-12 S4-A 判定点统一后， 权威判定已上移到
 * {@link io.mosire.agentlib.tool.ToolCallAuthorizer}</b>——它同时收口管线与 MCP 两条路径（此前 {@link
 * AgentToMcpServer} 是裸 {@code tool.execute}，本类这道闸正是为补那个洞而写；洞已堵，本闸保留为纵深防御，不再是唯一防线）。 判定读的是工具自己的
 * {@code spec()}，与 authorizer 同源、不会分叉。{@code read_agent_context} 的同性质校验收敛在 {@link
 * ContextAccessJudge}（同为其唯一判定点的纵深副本）。
 *
 * <p>错误映射契约：参数缺失/非法 → {@code INVALID_ARGUMENTS}；编排层拒绝（单调性/深度，对应 {@link SubagentRejectedException}）→
 * {@code PERMISSION_DENIED}（可读原因经 message 透出）；<b>工作目录越界</b>（{@link WorkingDirDeniedException}）→
 * {@code DIR_NOT_ALLOWED}（S5-B：与"你不该用这个工具"分码——它要说的是"你要的目录不在上级可达面里"）；<b>繁殖预算耗尽</b>（{@link
 * SubagentBudgetExceededException}）→ {@code
 * BUDGET_EXHAUSTED}（与越权分码：前者"现在不行"、后者"你不该要"，见该异常的类注释）；未知实例 → {@code UNKNOWN_INSTANCE}；启动失败 → {@code
 * LAUNCH_FAILED}；读上下文失败（库不存在/配置未注入）→ {@code CONTEXT_UNAVAILABLE}（响亮，绝不返回空结果冒充成功）。
 *
 * <p>返回值：spawn = 新实例快照 JSON（instanceId/templateId/status/depth），kill = 终止确认文本， list =
 * 实例快照数组，read_agent_context = 结构化视图体（视图/条目/分页游标）。除 read 外均幂等安全（重复 kill = 空操作成功）。
 */
public final class SubagentOrchestrationTools {

  private static final Logger LOG = LoggerFactory.getLogger(SubagentOrchestrationTools.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  public static final String SPAWN_SUB_AGENT = "spawn_sub_agent";
  public static final String KILL_SUB_AGENT = "kill_sub_agent";
  public static final String LIST_SUB_AGENTS = "list_sub_agents";

  /** D30：按实例 id 读子 Agent 上下文（禁外发——见 {@link #read(SubagentManager)} 的 spec）。 */
  public static final String READ_AGENT_CONTEXT = "read_agent_context";

  /** 注册排序（名字字典序）：kill / list / read / spawn——测试与目录稳定。 */
  public static List<AgentTool> of(SubagentManager manager) {
    return List.of(kill(manager), list(manager), read(manager), spawn(manager));
  }

  private SubagentOrchestrationTools() {}

  /**
   * spawn：子 Agent 模板起实例（<b>DEFAULT 级</b>，S5-A 起对子 Agent 开放派发）。
   *
   * <p><b>为什么从 SYSTEM 降到 DEFAULT</b>：spec 的 {@code requiredLevel} 在 S4-A 判定点统一后<b>真的生效</b>（此前 MCP
   * 路径裸调 {@code tool.execute}，SYSTEM 只是个装饰），不降则子体永远派不了孙代——而"开放派生 + 检查锚到调用者"是 D28
   * 的裁决之一。开放的安全性由三道闸承担（都在 {@link SubagentManager}，且对照物是<b>调用者</b>）：权限单调性、档位 单调性、深度与繁殖预算。本工具内不再自查
   * {@code caller == SYSTEM}（那会让降级毫无意义）。
   */
  static AgentTool spawn(SubagentManager manager) {
    return tool(
        SPAWN_SUB_AGENT,
        "派生一个子 Agent（由模板驱动；深度/权限/档位/工作目录/cap 均经<b>调用者</b>收紧）。",
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "templateId", Map.of("type", "string"),
                "goal", Map.of("type", "string"),
                "extraDenied", Map.of("type", "array", "items", Map.of("type", "string")),
                // S5-B：子体的工作目录（可选）。给 = 明确要求（超出调用者可达面直接拒，码 DIR_NOT_ALLOWED）；
                // 空数组 = 要求"哪里都不许"；不给 = 不额外收窄（调用者 ∩ 模板说了算）。
                "allowedDirs", Map.of("type", "array", "items", Map.of("type", "string")),
                "maxTurnsCap", Map.of("type", "integer"),
                "timeBudgetSecondsCap", Map.of("type", "integer"),
                "quotaMaxTokensCap", Map.of("type", "integer"),
                // S6：命令档位（可选）。不给 = 子 Agent 缺省 LIMITED（所有命令都要审批）；要 FULL 得父级本身是 FULL，
                // 否则 spawn 直接拒（单调性，见 SubagentManager#resolveMode）——"能要"不等于"能拿到"。
                "mode", Map.of("type", "string", "enum", List.of("full", "limited"))),
            "required",
            List.of("templateId", "goal")),
        // S5-A：DEFAULT 级（子 Agent 也能派孙代）；敏感/破坏位与其余编排工具同形
        ToolSpec.level(AccessToken.DEFAULT, false, true),
        context -> {
          String templateId = strArg(context, "templateId");
          String goal = strArg(context, "goal");
          if (templateId == null || goal == null) {
            return ToolResult.error("INVALID_ARGUMENTS", "templateId 与 goal 为必填参数");
          }
          // 档位：坏值<b>不</b>静默当"没给"（那会把"我要 FULL"读成"缺省"，与 CommandMode.parse 的口径一致）
          String rawMode = strArg(context, "mode");
          CommandMode mode = rawMode == null ? null : CommandMode.parse(rawMode);
          if (rawMode != null && mode == null) {
            return ToolResult.error("INVALID_ARGUMENTS", "mode 只能是 full 或 limited: " + rawMode);
          }
          // S6 单调性（<b>调用者</b>侧）：档位不得超过<b>调用者自己</b>的档位。身份由宿主在建链时绑定
          // （主 Agent = 运行时缝现读；经 MCP 链接进来的下级 = 父侧为它绑的实例身份），模型改不了。
          // 与 SubagentManager#resolveMode 的父级闸是两个维度：那条管"本级给不给"，这条管"谁在要"——
          // 链上每一跳都受约束，否则一个 LIMITED 的下级能借 SYSTEM 模板经本级要出 FULL 的下级。
          CommandMode callerMode = context.identity().mode();
          if (mode != null && !callerMode.covers(mode)) {
            return ToolResult.error(
                ToolExecutionGuard.DENIED, "档位超出调用者许可（单调性）: 调用者=" + callerMode + " 请求=" + mode);
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
                    longArg(context, "quotaMaxTokensCap"),
                    mode,
                    strListArg(context, "allowedDirs"));
          } catch (IllegalArgumentException e) {
            // 字段存在但非数字：与类 Javadoc 一致 → INVALID_ARGUMENTS（而非静默当"未收紧"——那会无抱怨地放宽上限）
            return ToolResult.error("INVALID_ARGUMENTS", e.getMessage());
          }
          SubagentInstance instance;
          try {
            // S5-A：以**调用者自己的**身份与权限集派生——三个守卫都对照它（不是 manager 的构造期常量）
            instance = manager.spawn(request, context.identity(), context.permissions());
          } catch (WorkingDirDeniedException e) {
            // S5-B：目录越界单独一码（DIR_NOT_ALLOWED）——模型据此改成"要一个够得着的目录"，
            // 而不是像看到 PERMISSION_DENIED 那样去换模板/换身份接着撞
            return ToolResult.error("DIR_NOT_ALLOWED", e.getMessage());
          } catch (SubagentBudgetExceededException e) {
            // 预算与越权分码：越权 = "你不该要"，预算 = "现在不行"（模型据此才会等/收敛，而不是换条路接着撞）
            return ToolResult.error("BUDGET_EXHAUSTED", e.getMessage());
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

  /**
   * read_agent_context：按 instanceId 直读子 Agent 的上下文（D30，补 U6 的"子体真干活、父拿不到结果"缺口）。
   *
   * <p><b>只读</b>：库经 {@link SqliteEventStore#openReadOnly} 打开（{@code mode=ro} 优先、退化 {@code PRAGMA
   * query_only}），不建表/不迁移/不写；子体可能正在写同一个库（WAL），读侧不打断它。
   *
   * <p><b>禁外发</b>（{@code noExport=true}）：本工具不进 MCP 桥接工具表——桥把整个 Registry 交给子体，若外发则
   * "每个子体都能读别家子体的上下文"（D27 读侧明令禁止）。子体拿不到句柄 = 读权力不外包。
   *
   * <p><b>判定</b>：收敛到 {@link ContextAccessJudge#judge} 一处（能力位 + 已知子体；D27 落地时只改那里）。
   *
   * <p><b>描述文案的诚实口径</b>：明写"当前仅对同进程已知子体开放；跨 Agent 血缘判定待 D27 落地"—— 不把"暂不支持"粉饰成"已按权限模型保护"。
   */
  static AgentTool read(SubagentManager manager) {
    return tool(
        READ_AGENT_CONTEXT,
        "读取一个 Agent 的上下文（子 Agent 按 instanceId，或 target=self 读自己）——直读其事件库，只读。"
            + "视图：summary(缺省，含最后一条 output 与 token 汇总)/turns(逐条输入+输出)/results(只要结果)"
            + "/search(关键词命中)/events(原始事件)。"
            + "【边界如实说明】当前仅对同进程已知子体开放；跨 Agent 血缘判定待 D27 落地。",
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "target",
                Map.of("type", "string", "description", "self 或子 Agent 实例 id（见 list_sub_agents）"),
                "view",
                Map.of(
                    "type", "string",
                    "enum", AgentContextReader.VIEWS,
                    "description", "视图；缺省 summary"),
                "q",
                Map.of(
                    "type", "string",
                    "description", "关键词（substring，CJK 不分词）；仅 search 视图，必填"),
                "type",
                Map.of(
                    "type", "string", "description", "事件类型过滤（如 conversation.turn）；仅 events/search"),
                "limit",
                Map.of(
                    "type",
                    "integer",
                    "description",
                    "页大小，缺省 "
                        + AgentContextReader.DEFAULT_LIMIT
                        + "、上限 "
                        + AgentContextReader.MAX_LIMIT),
                "sinceSeq",
                Map.of(
                    "type", "integer",
                    "description", "翻页游标：只取 seq 大于它的条目，缺省 0；续页传上一页的 nextSinceSeq")),
            "required",
            List.of("target")),
        // D30 安全必需：SYSTEM 级（仅主 Agent）+ 敏感（读别家上下文需显式放行）+ 非破坏 + 禁外发
        ToolSpec.level(AccessToken.SYSTEM, true, false, true),
        context -> {
          String target = strArg(context, "target");
          // 判定收敛在 ContextAccessJudge（不假设"能进来就一定有权限"——独立入口，不靠调用链上游）。
          // 2026-09-12 S4-A 后 ToolCallAuthorizer 已成为工具调用唯一入口（管线 + MCP 两条路径都经它），
          // 本判定是与 spec() 同源的纵深副本，不再是唯一防线（见类注释）。
          ContextAccessJudge.Decision decision = ContextAccessJudge.judge(context, manager, target);
          if (!decision.allowed()) {
            return ToolResult.error(decision.code(), decision.reason());
          }
          AgentContextReader.Request request;
          try {
            request =
                new AgentContextReader.Request(
                    strArg(context, "view"),
                    strArg(context, "q"),
                    strArg(context, "type"),
                    intArgOr(context, "limit", AgentContextReader.DEFAULT_LIMIT),
                    longArgOr(context, "sinceSeq", 0L));
          } catch (IllegalArgumentException e) {
            return ToolResult.error("INVALID_ARGUMENTS", e.getMessage());
          }
          try {
            Map<String, Object> config = context.config();
            Path dbPath;
            String agentId;
            SubagentInstance instance = null;
            if (ContextAccessJudge.SELF.equals(target)) {
              dbPath =
                  requiredPath(config, AgentContextReader.CONFIG_SELF_EVENTS_DB, "target=self 需要它");
              Object selfId = config.get(AgentContextReader.CONFIG_SELF_AGENT_ID);
              if (selfId == null || String.valueOf(selfId).isBlank()) {
                throw new IllegalStateException(
                    "工具配置缺失 "
                        + AgentContextReader.CONFIG_SELF_AGENT_ID
                        + "（target=self 需要它过滤事件的 agent 列）");
              }
              agentId = String.valueOf(selfId);
            } else {
              Path root = requiredPath(config, AgentContextReader.CONFIG_SUBAGENTS_ROOT, "子库根目录");
              dbPath = childDbPath(root, target);
              agentId = target;
              instance = manager.get(target).orElse(null);
            }
            if (!Files.isRegularFile(dbPath)) {
              // 响亮：库不存在 = 报错（谁都没读过/目录被清理），绝不返回空结果冒充"这个子体没干活"
              throw new IllegalStateException("事件库不存在（该 Agent 可能尚未落任何事件，或其数据目录已被清理）: " + dbPath);
            }
            Map<String, Object> body = AgentContextReader.read(dbPath, agentId, request, instance);
            return ToolResult.ok(toJson(body));
          } catch (IllegalArgumentException e) {
            return ToolResult.error("INVALID_ARGUMENTS", e.getMessage());
          } catch (IllegalStateException | UncheckedIOException e) {
            LOG.warn("read_agent_context 读取失败 target={}", target, e);
            return ToolResult.error("CONTEXT_UNAVAILABLE", e.getMessage());
          }
        });
  }

  /**
   * 子库路径：{@code <root>/<instanceId>/events.db}（与装配层交给子进程的 {@code --data-dir} 同源）， 并挡掉越出根目录的 id（模板
   * id 由模板文件给出，不排除被人为写成 {@code ../..}；判定层只认"已知实例"， 这里是纵深防御）。
   */
  static Path childDbPath(Path root, String instanceId) {
    Path normalizedRoot = root.normalize();
    Path dir = normalizedRoot.resolve(instanceId).normalize();
    if (!dir.startsWith(normalizedRoot)) {
      throw new IllegalStateException("实例 id 越出子库根目录: " + instanceId);
    }
    return dir.resolve("events.db");
  }

  private static Path requiredPath(Map<String, Object> config, String key, String hint) {
    Object value = config.get(key);
    if (value == null || String.valueOf(value).isBlank()) {
      throw new IllegalStateException("工具配置缺失 " + key + "（" + hint + "）——装配层未注入，不做路径猜测");
    }
    return Path.of(String.valueOf(value));
  }

  /**
   * 身份校验（<b>纵深防御副本</b>——2026-09-12 S4-A 判定点统一后权威判定已上移到 {@code ToolCallAuthorizer}， 它同时收口管线与 MCP
   * 两条路径；本方法保留但不承重，且与 {@code spec()} 的 SYSTEM 级别同源、不会分叉。见类注释）。
   */
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
    return tool(name, description, schema, ToolSpec.level(AccessToken.SYSTEM, false, true), fn);
  }

  /** 带显式三要素的形态（D30：read_agent_context 需要"禁外发"位，与三个编排工具的 spec 不同）。 */
  private static AgentTool tool(
      String name,
      String description,
      Map<String, Object> schema,
      ToolSpec spec,
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
        return spec;
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

  /**
   * 字符串数组参数（S5-B 的 {@code allowedDirs}）：字段缺失 → {@code null}（"没提这一嘴"，与"给了空数组 = 哪里都不许"不同口径）； 给了集合 →
   * 逐元素取字符串（元素为空/空白 ⇒ 抛 {@link IllegalArgumentException}，调用方落 {@code INVALID_ARGUMENTS}）； 给了单个标量 →
   * 当成单元素表（与 {@code strSetArg} 同惯例）。
   */
  private static java.util.List<String> strListArg(ToolContext context, String name) {
    Object value = context.arguments().get(name);
    if (value == null) {
      return null;
    }
    java.util.List<String> out = new java.util.ArrayList<>();
    if (value instanceof java.util.Collection<?> collection) {
      for (Object item : collection) {
        out.add(dirText(name, item));
      }
    } else {
      out.add(dirText(name, value));
    }
    return java.util.List.copyOf(out);
  }

  private static String dirText(String name, Object raw) {
    String text = raw == null ? "" : String.valueOf(raw).strip();
    if (text.isEmpty()) {
      throw new IllegalArgumentException("参数 " + name + " 的元素必须是非空白路径文本");
    }
    return text;
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

  /** 同 {@link #intArg(ToolContext, String)}，但缺省取 {@code fallback}（可选参数）；非数字仍抛错。 */
  private static int intArgOr(ToolContext context, String name, int fallback) {
    Integer value = intArg(context, name);
    return value == null ? fallback : value;
  }

  /** 同 {@link #longArg(ToolContext, String)}，但缺省取 {@code fallback}（可选参数）；非数字仍抛错。 */
  private static long longArgOr(ToolContext context, String name, long fallback) {
    Long value = longArg(context, name);
    return value == null ? fallback : value;
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
