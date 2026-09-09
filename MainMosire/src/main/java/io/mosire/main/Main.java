package io.mosire.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.main.agent.SubagentProcessMain;
import io.mosire.main.app.App;
import io.mosire.main.app.BootConfig;
import io.mosire.main.app.ContextReport;
import io.mosire.main.app.FakeLlmScript;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 入口/CLI 分发（手写子命令分派——决策 D3：不引 CLI 框架，保持零依赖）。
 *
 * <p>子命令：{@code run}（M1：网关守护 + --demo 自检回合）、{@code health}（当前进程自检）、 {@code events}（查看本地事件库尾部，便于无
 * sqlite3 环境验收）、{@code context}（llm.call token 记账汇总，D18 Token Economy 可观测）、 {@code agent}（W3b：子
 * Agent stdio 进程形态——runtime + MCP client，无网关）、 {@code doctor}/{@code config}（M3 实现）。
 */
public final class Main {

  private static final ObjectMapper JSON = new ObjectMapper();

  private Main() {}

  public static void main(String[] args) {
    try {
      int code = dispatch(args);
      if (code != 0) {
        System.exit(code);
      }
    } catch (IllegalArgumentException e) {
      System.err.println("参数错误: " + e.getMessage());
      System.exit(1);
    } catch (Exception e) {
      System.err.println("运行失败: " + e.getMessage());
      e.printStackTrace();
      System.exit(1);
    }
  }

  private static int dispatch(String[] args) throws InterruptedException {
    if (args.length == 0) {
      usage();
      return 2;
    }
    switch (args[0]) {
      case "run":
        return run(args);
      case "health":
        return health();
      case "events":
        return events(args);
      case "context":
        return context(args);
      case "agent":
        return agent(args);
      case "doctor":
        System.err.println("doctor 子命令在 M3 实现");
        return 1;
      case "config":
        System.err.println("config 子命令在 M3（ConfigStore）实现");
        return 1;
      default:
        usage();
        return 2;
    }
  }

  private static int run(String[] args) throws InterruptedException {
    BootConfig config = BootConfig.defaults();
    boolean fake = false;
    String fakeScript = null;
    for (int i = 1; i < args.length; i++) {
      switch (args[i]) {
        case "--port" -> config = withPort(config, Integer.parseInt(requireValue(args, ++i)));
        case "--data-dir" -> config = withDataDir(config, Path.of(requireValue(args, ++i)));
        case "--demo" -> config = withDemo(config, true);
        case "--demo-message" -> config = withDemoMessage(config, requireValue(args, ++i));
        case "--mcp-link" -> config = withMcpLinks(config, Path.of(requireValue(args, ++i)));
        case "--no-mcp-expose" -> config = withMcpExpose(config, false);
        case "--a2a-address" -> config = withA2aAddress(config, requireValue(args, ++i));
        case "--a2a-port" ->
            config = withA2aPort(config, Integer.parseInt(requireValue(args, ++i)));
        case "--agui-address" -> config = withAguiAddress(config, requireValue(args, ++i));
        case "--agui-port" ->
            config = withAguiPort(config, Integer.parseInt(requireValue(args, ++i)));
        case "--debug-port" ->
            config = withDebugPort(config, Integer.parseInt(requireValue(args, ++i)));
        case "--templates-dir" ->
            config = withTemplatesDir(config, Path.of(requireValue(args, ++i)));
        case "--fake" -> fake = true;
        case "--fake-script" -> fakeScript = requireValue(args, ++i);
        default -> {
          System.err.println("未知参数: " + args[i]);
          return 2;
        }
      }
    }
    if (fake && fakeScript != null) {
      throw new IllegalArgumentException("--fake 与 --fake-script 不能同时指定");
    }
    // W5：离线 LLM 显式声明面——smoke.sh 断言"断网可测"；非 demo 默认即 FakeLlm（App.scriptedLlm），显式开关是明示
    LlmClient llmOverride =
        fakeScript != null
            ? FakeLlmScript.parse(fakeScript)
            : fake ? FakeLlmClient.with(LlmResponse.text(App.DEFAULT_LLM_REPLY)) : null;
    App app = App.start(config, llmOverride);
    // stdout 保留给 MCP stdio 流（主 Agent 工具面默认在此暴露），用户可见消息走 stderr
    System.err.printf(
        "Mosire v%s 已启动: admin=http://127.0.0.1:%d a2a=http://%s:%d agui=http://%s:%d debug=http://127.0.0.1:%d%s%s%n",
        Version.VERSION,
        app.boundPort(),
        config.a2aHost(),
        app.a2aPort(),
        config.aguiHost(),
        app.aguiPort(),
        app.debugPort(),
        config.demo() ? "（demo 模式）" : "",
        config.templatesDir() != null ? " 子 Agent 编排=已启用" : "");
    app.awaitTermination();
    return 0;
  }

  /**
   * 子 Agent stdio 进程形态（W3b）：运行模板驱动的 runtime；{@code --parent-link} 时以 MCP client 身份接管父侧建好的
   * stdin/stdout 链路，跑完任务后阻塞到父侧关停信号（EOF）。本进程不启动任何 HTTP 网关。
   */
  private static int agent(String[] args) {
    String instanceId = null;
    String templateId = null;
    String goal = null;
    Path templatesDir = null;
    Path dataDir = null;
    boolean parentLink = false;
    for (int i = 1; i < args.length; i++) {
      switch (args[i]) {
        case "--id" -> instanceId = requireValue(args, ++i);
        case "--template" -> templateId = requireValue(args, ++i);
        case "--goal" -> goal = requireValue(args, ++i);
        case "--templates-dir" -> templatesDir = Path.of(requireValue(args, ++i));
        case "--data-dir" -> dataDir = Path.of(requireValue(args, ++i));
        case "--parent-link" -> parentLink = true;
        default -> {
          System.err.println("未知参数: " + args[i]);
          return 2;
        }
      }
    }
    if (instanceId == null || templateId == null || goal == null) {
      System.err.println("agent 参数不完整: --id/--template/--goal 为必填");
      return 2;
    }
    try {
      return SubagentProcessMain.execute(
          new SubagentProcessMain.Options(
              instanceId, templateId, goal, templatesDir, dataDir, parentLink));
    } catch (IllegalArgumentException e) {
      System.err.println("参数错误: " + e.getMessage());
      return 2;
    }
  }

  private static String requireValue(String[] args, int index) {
    if (index >= args.length) {
      throw new IllegalArgumentException("缺少参数值（第 " + index + " 个）");
    }
    return args[index];
  }

  private static BootConfig withPort(BootConfig config, int port) {
    return new BootConfig(
        port,
        config.dataDir(),
        config.demo(),
        config.demoMessage(),
        config.mcpLinks(),
        config.mcpExpose(),
        config.a2aHost(),
        config.a2aPort(),
        config.templatesDir(),
        config.aguiHost(),
        config.aguiPort(),
        config.debugPort());
  }

  private static BootConfig withDataDir(BootConfig config, Path dataDir) {
    return new BootConfig(
        config.port(),
        dataDir,
        config.demo(),
        config.demoMessage(),
        config.mcpLinks(),
        config.mcpExpose(),
        config.a2aHost(),
        config.a2aPort(),
        config.templatesDir(),
        config.aguiHost(),
        config.aguiPort(),
        config.debugPort());
  }

  private static BootConfig withDemo(BootConfig config, boolean demo) {
    return new BootConfig(
        config.port(),
        config.dataDir(),
        demo,
        "",
        config.mcpLinks(),
        config.mcpExpose(),
        config.a2aHost(),
        config.a2aPort(),
        config.templatesDir(),
        config.aguiHost(),
        config.aguiPort(),
        config.debugPort());
  }

  private static BootConfig withDemoMessage(BootConfig config, String message) {
    return new BootConfig(
        config.port(),
        config.dataDir(),
        true,
        message,
        config.mcpLinks(),
        config.mcpExpose(),
        config.a2aHost(),
        config.a2aPort(),
        config.templatesDir(),
        config.aguiHost(),
        config.aguiPort(),
        config.debugPort());
  }

  private static BootConfig withMcpLinks(BootConfig config, Path mcpLinks) {
    return new BootConfig(
        config.port(),
        config.dataDir(),
        config.demo(),
        config.demoMessage(),
        mcpLinks,
        config.mcpExpose(),
        config.a2aHost(),
        config.a2aPort(),
        config.templatesDir(),
        config.aguiHost(),
        config.aguiPort(),
        config.debugPort());
  }

  private static BootConfig withMcpExpose(BootConfig config, boolean mcpExpose) {
    return new BootConfig(
        config.port(),
        config.dataDir(),
        config.demo(),
        config.demoMessage(),
        config.mcpLinks(),
        mcpExpose,
        config.a2aHost(),
        config.a2aPort(),
        config.templatesDir(),
        config.aguiHost(),
        config.aguiPort(),
        config.debugPort());
  }

  private static BootConfig withA2aAddress(BootConfig config, String a2aAddress) {
    return new BootConfig(
        config.port(),
        config.dataDir(),
        config.demo(),
        config.demoMessage(),
        config.mcpLinks(),
        config.mcpExpose(),
        a2aAddress,
        config.a2aPort(),
        config.templatesDir(),
        config.aguiHost(),
        config.aguiPort(),
        config.debugPort());
  }

  private static BootConfig withA2aPort(BootConfig config, int a2aPort) {
    return new BootConfig(
        config.port(),
        config.dataDir(),
        config.demo(),
        config.demoMessage(),
        config.mcpLinks(),
        config.mcpExpose(),
        config.a2aHost(),
        a2aPort,
        config.templatesDir(),
        config.aguiHost(),
        config.aguiPort(),
        config.debugPort());
  }

  private static BootConfig withTemplatesDir(BootConfig config, Path templatesDir) {
    return new BootConfig(
        config.port(),
        config.dataDir(),
        config.demo(),
        config.demoMessage(),
        config.mcpLinks(),
        config.mcpExpose(),
        config.a2aHost(),
        config.a2aPort(),
        templatesDir,
        config.aguiHost(),
        config.aguiPort(),
        config.debugPort());
  }

  private static BootConfig withAguiAddress(BootConfig config, String aguiAddress) {
    return new BootConfig(
        config.port(),
        config.dataDir(),
        config.demo(),
        config.demoMessage(),
        config.mcpLinks(),
        config.mcpExpose(),
        config.a2aHost(),
        config.a2aPort(),
        config.templatesDir(),
        aguiAddress,
        config.aguiPort(),
        config.debugPort());
  }

  private static BootConfig withAguiPort(BootConfig config, int aguiPort) {
    return new BootConfig(
        config.port(),
        config.dataDir(),
        config.demo(),
        config.demoMessage(),
        config.mcpLinks(),
        config.mcpExpose(),
        config.a2aHost(),
        config.a2aPort(),
        config.templatesDir(),
        config.aguiHost(),
        aguiPort,
        config.debugPort());
  }

  private static BootConfig withDebugPort(BootConfig config, int debugPort) {
    return new BootConfig(
        config.port(),
        config.dataDir(),
        config.demo(),
        config.demoMessage(),
        config.mcpLinks(),
        config.mcpExpose(),
        config.a2aHost(),
        config.a2aPort(),
        config.templatesDir(),
        config.aguiHost(),
        config.aguiPort(),
        debugPort);
  }

  private static int health() {
    try {
      System.out.println(
          JSON.writeValueAsString(
              Map.of(
                  "status",
                  "ok",
                  "artifact",
                  Version.ARTIFACT_ID,
                  "version",
                  Version.VERSION,
                  "message",
                  "本命令仅报告当前进程信息；运行态健康检查请访问 AdminREST /health")));
      return 0;
    } catch (Exception e) {
      System.err.println("health 自检失败: " + e.getMessage());
      return 1;
    }
  }

  /** 本地事件库尾部最近 N 条（默认 10）：无 sqlite3 环境的验收通道。 */
  private static int events(String[] args) {
    Path dataDir = BootConfig.DEFAULT_DATA_DIR;
    int limit = 10;
    for (int i = 1; i < args.length; i += 2) {
      if (i + 1 >= args.length) {
        System.err.println("events 参数成对出现: --data-dir <dir> [--limit <n>]");
        return 2;
      }
      switch (args[i]) {
        case "--data-dir" -> dataDir = Path.of(args[i + 1]);
        case "--limit" -> limit = Integer.parseInt(args[i + 1]);
        default -> {
          System.err.println("未知参数: " + args[i]);
          return 2;
        }
      }
    }
    try (SqliteEventStore store = SqliteEventStore.open(dataDir.resolve("events.db"))) {
      List<Event> events = store.query(EventQuery.recent(limit));
      System.out.println("seq | ts | type | agent | correlationId");
      for (Event event : events) {
        System.out.printf(
            "%d | %s | %s | %s | %s%n",
            event.seq(),
            DateTimeFormatter.ISO_INSTANT.format(event.ts()),
            event.type(),
            event.agent(),
            event.correlationId());
      }
      System.out.printf("[共 %d 条，显示最近 %d 条]%n", store.count(), events.size());
      return 0;
    } catch (Exception e) {
      System.err.println("读取事件库失败: " + e.getMessage());
      return 1;
    }
  }

  /** context 子命令：llm.call 记账 token 汇总（D18 Token Economy 可观测）。 */
  private static int context(String[] args) {
    Path dataDir = BootConfig.DEFAULT_DATA_DIR;
    for (int i = 1; i < args.length; i += 2) {
      if (i + 1 >= args.length) {
        System.err.println("context 参数成对出现: --data-dir <dir>");
        return 2;
      }
      switch (args[i]) {
        case "--data-dir" -> dataDir = Path.of(args[i + 1]);
        default -> {
          System.err.println("未知参数: " + args[i]);
          return 2;
        }
      }
    }
    try (SqliteEventStore store = SqliteEventStore.open(dataDir.resolve("events.db"))) {
      System.out.print(ContextReport.render(store));
      return 0;
    } catch (Exception e) {
      System.err.println("统计 LLM 调用 token 失败: " + e.getMessage());
      return 1;
    }
  }

  private static void usage() {
    // 占位符 + replace 而非 %s + formatted：文本块是普通字符串，避免 SpotBugs VA_FORMAT_STRING_USES_NEWLINE（改用 %n
    // 的提醒）
    System.out.println(
        """
        Mosire v{version} —— Java 模块化可自管理 Agent（P0）

        用法: java -jar mosire.jar <子命令> [选项]

        子命令:
          run             启动网关守护并执行主 Agent 主循环
             --port <n>      监听端口（默认 8080；0=自动分配）
             --data-dir <p>  数据目录（默认 .work/mosire）
             --demo          启动时先跑一条脚本问答（自检：事件入库）
             --demo-message <t> 启动时跑指定内容的脚本问答
             --mcp-link <p>    外部 MCP stdio server 配置（mcp-links.json，数组格式）
             --no-mcp-expose   关闭主 Agent 工具面经 stdio MCP server 暴露（默认启用）
             --a2a-address <h>  A2A 网关绑定地址（默认 127.0.0.1；对外打开须显式配置）
             --a2a-port <n>     A2A 网关端口（默认 0 = 空闲端口自动分配）
             --agui-address <h>  AG-UI 网关绑定地址（默认 127.0.0.1；对外打开须显式配置）
             --agui-port <n>    AG-UI 网关端口（默认 0 = 空闲端口自动分配）
             --debug-port <n>   调试对话网关端口（默认 0 = 空闲端口自动分配；恒绑 127.0.0.1）
             --templates-dir <p> 子 Agent 模板目录（configs/agents；缺省不启用编排）
             --fake          显式启用离线假 LLM（FakeLlmClient 骨架回复；不触任何网络）
             --fake-script <s> 离线脚本 LLM：分号分隔步骤，text:<回复> 或 tool:<工具>:<args JSON>；
                               args 字符串值 $spawnedId 替换为最近一次 spawn_sub_agent 结果实例 id
          health          当前进程健康自检（进程内信息）
          events          查看本地事件库尾部
             --data-dir <p>  数据目录（默认 .work/mosire）
             --limit <n>     显示条数（默认 10）
          context         查看 LLM 调用 token 统计（llm.call 事件汇总）
             --data-dir <p>  数据目录（默认 .work/mosire）
          agent           W3b 子 Agent stdio 进程形态（runtime + MCP client；无网关）
             --id <id>       实例 id（必填，父装配层生成）
             --template <t>  模板 id（必填）
             --goal <g>      目标任务（必填）
             --templates-dir <p> 模板目录（默认 configs/agents）
             --data-dir <p>  数据目录（默认 .work/mosire）
             --parent-link   以 MCP client 身份接管 stdin/stdout（父侧已建链接）
          doctor          （M3）自检
          config          （M3）配置管理
        """
            .replace("{version}", Version.VERSION));
  }
}
