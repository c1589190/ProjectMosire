package io.mosire.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.config.FileConfigStore;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.ConfigApiKeySource;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.llm.LlmRouteLoader;
import io.mosire.agentlib.llm.ModelRoute;
import io.mosire.agentlib.llm.OpenAICompatibleLlmClient;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
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
    // W5：离线 LLM 显式声明面（smoke.sh 断言"断网可测"）；三期 S1-A1：以上开关都不给时一律真模型（缺配置/缺密钥响亮失败，不退化）
    App app = startApp(config, fake, fakeScript);
    // stdout 保留给 MCP stdio 流（主 Agent 工具面默认在此暴露），用户可见消息走 stderr
    // S4-B2：审批面端口（恒 loopback）。approval.http=false 时整段不打印——0 不是合法端口，不拿它冒充"已启用"
    int approvalPort = app.approvalPort();
    String approvals = approvalPort == 0 ? "" : " approvals=http://127.0.0.1:" + approvalPort;
    System.err.printf(
        "Mosire v%s 已启动: admin=http://127.0.0.1:%d a2a=http://%s:%d agui=http://%s:%d debug=http://127.0.0.1:%d%s%s%s%n",
        Version.VERSION,
        app.boundPort(),
        config.a2aHost(),
        app.a2aPort(),
        config.aguiHost(),
        app.aguiPort(),
        app.debugPort(),
        approvals,
        config.demo() ? "（demo 模式）" : "",
        config.templatesDir() != null ? " 子 Agent 编排=已启用" : "");
    app.awaitTermination();
    return 0;
  }

  /**
   * 生产装配（{@code run} 的唯一出口）：把解析出的开关折算成交给 {@link App#start} 的四个入参——主 Agent 的 LLM 形态（{@link
   * #selectLlm}）与子 Agent 的配置根（下方三元）。
   *
   * <p><b>为什么把这几行单列出来（评审 MED-1）</b>：下方三元是"子 Agent 走真模型还是模板脚本假 LLM"的<b>唯一生产开关</b>（R-A2-6）； 压成 {@code
   * null} 会让子体静默退回模板脚本假模型（{@code configDir == null} ⇒ {@code template.scriptedFakeLlm()}——正是 D24
   * 要防的静默退化，也正是 S1-B"子体跑真模型"的前提）。{@code App.start} 之后的链路已有 进程层用例覆盖（{@code
   * SubagentConfigDirWiringTest} 从 4 参入口起真子进程、断言子体打到假端点），但"这条开关本身被绕过"只有从
   * <b>生产装配入口</b>驱动才判得了——故开关与交参合成一条语句：{@code run} 不再持有配置根变量，绕过去只能改这里。
   *
   * <p><b>子 Agent 配置根（R-A2-6）</b>：只有生产真模型路径才把父的配置根交给子 Agent（子体的 {@code --data-dir} 是隔离目录，底下没有
   * {@code config.json}）；{@code --demo}/{@code --fake}/{@code --fake-script} 三种离线形态下子体仍是模板脚本假
   * LLM——离线门禁与 smoke.sh 不受影响。
   *
   * <p><b>可见性（包私有）</b>：与 {@link #selectLlm}/{@link #realLlm(Path)} 一致——本方法是生产装配的内部缝，不是公开 API。
   * 驱动本方法的判据用例必须复用既有回环假端点夹具 {@code OpenAiStubServer}，它位于 {@code io.mosire.main.agent}
   * 包（包私有），用例因而不能落在本包； 跨包这一档由<b>测试源码树</b>里的 {@code MainStartAppTestSeam} 桥接（{@code
   * src/test/java}，不进产物）——生产类不为了测试提权。
   *
   * @param config 启动配置（{@code --data-dir} 即配置根）
   * @param fake {@code --fake} 是否给出
   * @param fakeScript {@code --fake-script} 的脚本（非 null 时优先于 {@code fake}）
   * @return 装配完成的实例；入参语义与 {@code run} 解析出的开关一一对应
   */
  static App startApp(BootConfig config, boolean fake, String fakeScript) {
    Path subagentConfigDir =
        (fakeScript == null && !fake && !config.demo()) ? config.dataDir() : null;
    return App.start(config, selectLlm(config, fake, fakeScript), List.of(), subagentConfigDir);
  }

  /**
   * LLM 覆盖选择（{@code run} 的 LLM 语义唯一入口）：离线脚本 &gt; 显式 {@code --fake} &gt; {@code --demo}（交回 {@code
   * App.scriptedLlm} 的骨架回复）&gt; 生产真模型。
   *
   * <p>抽成包私有静态纯函数的理由：本期唯一的实质行为变更是"<b>什么开关都不给 ⇒ 真模型</b>"（D24：缺配置/缺密钥一律响亮失败，绝不静默退化回 {@link
   * FakeLlmClient}）。这条不变量必须能在单元层被钉住——只靠进程级用例，覆盖会随 {@code run} 的重构无声消失。
   *
   * @param config 启动配置（本函数只读 {@code demo} 与 {@code dataDir}）
   * @param fake {@code --fake} 是否给出
   * @param fakeScript {@code --fake-script} 的脚本（非 null 时优先于 {@code fake}）
   * @return 交给 {@code App.start} 的 LLM 覆盖；{@code null} 表示不覆盖（仅 demo 路径）
   */
  static LlmClient selectLlm(BootConfig config, boolean fake, String fakeScript) {
    return fakeScript != null
        ? FakeLlmScript.parse(fakeScript)
        : fake
            ? FakeLlmClient.with(LlmResponse.text(App.DEFAULT_LLM_REPLY))
            : config.demo() ? null : realLlm(config.dataDir());
  }

  /**
   * 生产装配：从 {@code <configRoot>/config.json} 取路由与密钥，构造真实 LLM 客户端（{@code configRoot} 即 {@code
   * --data-dir}）。
   *
   * <p><b>走哪条路由</b>（多 provider 并存，2026-09-14）：{@code agents.main.llm.route} → {@code llm.route} →
   * {@code "default"}（{@link LlmRouteLoader#routeName}）；点到不存在的名字 ⇒ {@code E_LLM_ROUTE_UNKNOWN}
   * 装配期响亮，<b>绝不</b>回落到别的路由。默认形态（扁平 {@code llm.*}）一字不改。
   *
   * <p><b>失败一律响亮（D24），绝不退化回 {@link FakeLlmClient}</b>：缺 {@code llm.baseUrl}/{@code llm.model}
   * 在<b>装配期</b> 抛（{@code E_LLM_CONFIG_MISSING}）；缺密钥在<b>取密钥时</b>抛（{@code E_KEY_MISSING}）——SPI 契约要求每次
   * {@code chat} 现取一次密钥以 支持轮换/过期感知，故密钥不在构造期固化（详见 {@link ConfigApiKeySource}），取不到时客户端按调用失败响亮抛出。
   *
   * <p><b>不预检密钥</b>（与 {@code SubagentProcessMain.realLlm} 的唯一有意差异）：主进程取密钥失败发生在首次 {@code chat}，由
   * {@code run} 的回合收敛路径报出；子进程必须预检的理由见那边的 javadoc。
   *
   * <p>包私有：只给 {@code run} 与同包测试用（既有测试入口 {@code App.start(...)} 不经过这里，故离线门禁不受影响）。
   */
  static LlmClient realLlm(Path configRoot) {
    FileConfigStore store = new FileConfigStore(configRoot);
    ModelRoute route =
        LlmRouteLoader.load(store, LlmRouteLoader.routeName(store, AgentIdentity.MAIN_ID));
    return new OpenAICompatibleLlmClient(
        route, new ConfigApiKeySource(store, route.credentialsRef(), AccessToken.SYSTEM));
  }

  /**
   * 子 Agent stdio 进程形态（W3b）：运行模板驱动的 runtime；{@code --parent-link} 时以 MCP client 身份接管父侧建好的
   * stdin/stdout 链路，跑完任务后阻塞到父侧关停信号（EOF）。本进程不启动任何 HTTP 网关。
   *
   * <p>S1-A2：{@code --config-dir <父的配置根>}（可选）在场 ⇒ 真模型形态（本进程自己从 {@code <config-dir>/config.json}
   * 读路由与密钥——只传路径，D23）；缺席 ⇒ 保持原样的模板脚本假 LLM。
   */
  private static int agent(String[] args) {
    String instanceId = null;
    String templateId = null;
    String goal = null;
    Path templatesDir = null;
    Path dataDir = null;
    boolean parentLink = false;
    Path configDir = null;
    for (int i = 1; i < args.length; i++) {
      switch (args[i]) {
        case "--id" -> instanceId = requireValue(args, ++i);
        case "--template" -> templateId = requireValue(args, ++i);
        case "--goal" -> goal = requireValue(args, ++i);
        case "--templates-dir" -> templatesDir = Path.of(requireValue(args, ++i));
        case "--data-dir" -> dataDir = Path.of(requireValue(args, ++i));
        case "--config-dir" -> configDir = Path.of(requireValue(args, ++i));
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
              instanceId, templateId, goal, templatesDir, dataDir, parentLink, configDir));
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
                               （以上离线开关都不给时一律真模型：路由与密钥取自 <data-dir>/config.json
                               的 llm.baseUrl/llm.model 与 keys.<name>；缺 llm.* 启动即响亮失败，
                               缺密钥在首次调用响亮失败，都不退回假 LLM。--demo 不读配置）
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
             --config-dir <p> 父的配置根：在场则按 <p>/config.json 的 llm.* 走真模型（只传路径，
                              密钥由本进程自己读；缺席 = 模板引导脚本假 LLM）
             --parent-link   以 MCP client 身份接管 stdin/stdout（父侧已建链接）
          doctor          （M3）自检
          config          （M3）配置管理
        """
            .replace("{version}", Version.VERSION));
  }
}
