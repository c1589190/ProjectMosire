package io.mosire.main.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.runtime.EventTypes;
import io.mosire.brain.subagent.SubagentInstance;
import io.mosire.brain.subagent.SubagentOrchestrationTools;
import io.mosire.main.Main;
import io.mosire.main.MainStartAppTestSeam;
import io.mosire.main.app.App;
import io.mosire.main.app.BootConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 三期 S1-A2 修复轮 <b>MED-1</b> 的门禁：{@code Main run} 生产装配（{@link Main#startApp}）里那个"子 Agent 走真模型还是模板脚本
 * 假 LLM"的唯一开关（R-A2-6 的三元）——被压成 {@code null} 时本类必须变红。
 *
 * <p><b>为什么需要它</b>：{@code SubagentConfigDirWiringTest} 从 {@code App.start} 的 4 参入口注入配置根，覆盖的是 <b>4
 * 参入口之后</b>的链路；而"生产 {@code run} 是否把配置根算出来并交给那个入口"此前零覆盖——把三元整体压成 {@code null}，全套门禁仍全绿 （评审实测
 * M10）。后果是静默退化：{@code configDir == null} ⇒ 子体退回 {@code template.scriptedFakeLlm()} （D24
 * 要防的形态），而下一任务 S1-B 的前提正是"子体跑的是真模型"。
 *
 * <p><b>机制（端到端，不是纯函数）</b>：经<b>生产装配入口</b> {@link Main#startApp} 起主装配（配置根 = 生产里的 {@code --data-dir}，其
 * {@code config.json} 的 {@code llm.baseUrl} 指向本用例的回环假端点），再经编排工具 {@code spawn_sub_agent}
 * 起一个<b>真子进程</b>，断言子体真的打到那个假端点、且它落库的回合文本<b>含假端点的回复</b>——脚本假 LLM 只会回"子 Agent 无引导脚本…"。压成 {@code null}
 * ⇒ 子体走脚本假 LLM ⇒ 端点零请求、回合文本变样 ⇒ 红。反向再钉三个离线开关 （{@code --fake}/ {@code --fake-script}/{@code
 * --demo}）：子体仍必须留在模板脚本上、端点零请求（专抓"无条件传配置根"的改法）。
 *
 * <p><b>跨包可见性</b>：装配入口 {@link Main#startApp} 是包私有的生产内部缝（同 {@code selectLlm}/{@code
 * realLlm}）；本用例必须复用包私有的 {@link OpenAiStubServer}，故不能落在 {@code io.mosire.main} 包——两档约束由测试期的 {@link
 * MainStartAppTestSeam} 桥接（只活在 {@code src/test/java}，见其 Javadoc），生产类不为此提权。
 *
 * <p><b>全离线</b>：假端点绑回环 {@code 127.0.0.1} + 临时端口，配置根与模板都在 {@code @TempDir}，密钥是哨兵串（D22/D23； 真连通性归
 * S1-B 探索测试）。
 */
class MainRunSubagentConfigDirGateTest {

  /** 哨兵密钥值：只活在 {@code @TempDir} 的假配置里（真密钥从不进测试）。 */
  private static final String SENTINEL_KEY = "sk-sentinel-not-a-real-key";

  /** 配置里的模型名（模板里写的是离线哨兵 {@code "fake"}；真模式下不得把它当模型名发出去，R-A2-7）。 */
  private static final String CONFIG_MODEL = "stub-model";

  /** 子体模板的 {@code systemPrompt} 标记：请求体里出现它即证明该请求来自<b>子体</b>（本用例主 Agent 从不 chat）。 */
  private static final String CHILD_MARKER = "S1-A2 MED-1 判据子 Agent";

  /** 空脚本模板假 LLM 的固定回复（{@code configDir} 缺失/离线形态下子体的回合文本）。 */
  private static final String SCRIPT_FAKE_REPLY = "子 Agent 无引导脚本，请向我描述任务。";

  private static final String TEMPLATE_ID = "real";

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private OpenAiStubServer stub;

  @BeforeEach
  void startStub() throws Exception {
    stub = new OpenAiStubServer(CONFIG_MODEL);
  }

  @AfterEach
  void stopStub() {
    stub.close();
  }

  // ---------- 生产真模型路径：配置根必须一路走到子体（M10 判别力所在） ----------

  /**
   * 生产形态（无 {@code --demo}/{@code --fake}/{@code --fake-script}）：{@link Main#startApp}
   * 把配置根交给编排层，子体据此从 {@code <配置根>/config.json} 读出端点并打到回环假端点，回合文本含假端点的回复。
   *
   * <p>判别力：把 {@code startApp} 里的三元压成 {@code null} ⇒ 子进程不带 {@code --config-dir} ⇒ 退回模板脚本假 LLM（空脚本 ⇒
   * {@link #SCRIPT_FAKE_REPLY}）⇒ 端点零请求、本用例在第一条 await 上超时变红。
   *
   * <p>下方 {@code requestBodies()} 的 {@code isNotEmpty()} 在绿路径上<b>恒真</b>（假端点先记录请求体再计数：上一条 {@code
   * served() >= 1} 的 await 一过，请求体必然非空），故它<b>不是本用例的判别力来源</b>，只是"每次请求都被留证、供下面查
   * systemPrompt"的意图声明；判别力在 {@code served() >= 1} 的 await 与 {@code anySatisfy(CHILD_MARKER)}（同
   * S1-A1 已裁的 G1 先例：恒真断言保留但注明）。
   */
  @Test
  void productionRunHandsTheConfigRootToTheChildWhichThenSpeaksToTheRealModel() throws Exception {
    Path configRoot = configRoot();
    BootConfig config = bootConfig(configRoot, writeTemplates(), false);

    App app = MainStartAppTestSeam.startApp(config, false, null);
    try (app) {
      spawn(app);
      SubagentInstance child = awaitChild(app);

      await(() -> stub.served() >= 1, 30_000, "等待子进程按 --config-dir 找到配置并请求真端点（假端点零请求 = 配置根没传到子体）");
      awaitEventContaining(
          childEvents(configRoot, child.instanceId()),
          EventTypes.CONVERSATION_TURN,
          "output",
          OpenAiStubServer.DEFAULT_TEXT,
          30_000);
      assertThat(stub.requestBodies())
          .as("请求体里必须出现子体模板的 systemPrompt（证明这次请求来自子体，而非主 Agent）")
          .isNotEmpty()
          .anySatisfy(body -> assertThat(body).contains(CHILD_MARKER));
      assertThat(kill(app, child.instanceId()).success()).isTrue();
    }
  }

  /** 三个离线开关（{@code --fake} / {@code --fake-script} / {@code --demo}）的参数面。 */
  static Stream<Arguments> offlineShapes() {
    return Stream.of(
        Arguments.of("--fake", true, null, false),
        Arguments.of("--fake-script", false, "text:离线脚本回复", false),
        Arguments.of("--demo", false, null, true));
  }

  /**
   * 离线形态（{@code --fake}/{@code --fake-script}/{@code --demo}）：子体必须仍留在模板脚本假 LLM 上——假端点零请求、回合文本 =
   * 空脚本的固定回复 （两条判据各自独立，专抓"无条件传配置根"的改法，那会让离线门禁与 smoke.sh 下的子体去打真端点）。
   *
   * <p><b>断言顺序（复审 R5「遮蔽项 2」）</b>：{@code served() == 0} 必须排在"回合文本 =
   * 固定回复"<b>之前</b>，且它前面只允许"子体回合事件已落库"这种 <b>中性的同步点</b>——若前一条 await 直接判回合文案，变异下它会先超时变红、{@code
   * served()} 那条<b>永远执行不到</b>（判别力被遮蔽，归因错位：{@code served()}
   * 不能记作"专抓无条件传根"的守卫）。同步点取"存在回合事件"而非某条文案，是因为真模型与脚本假模型两种形态都会落这条事件，且真模型的请求必然<b>先于</b>
   * 它自己的回合事件——同步点一过，{@code served() == 0} 即是无竞态的定论。
   *
   * <p>{@code --demo} 形态下 {@code App.start} 还会先跑一条自检回合（主 Agent 的脚本假 LLM），同样不该碰端点。
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("offlineShapes")
  void offlineSwitchesKeepTheChildOnTheTemplateScriptSoTheEndpointIsNeverCalled(
      String shape, boolean fake, String fakeScript, boolean demo) throws Exception {
    Path configRoot = configRoot();
    BootConfig config = bootConfig(configRoot, writeTemplates(), demo);

    App app = MainStartAppTestSeam.startApp(config, fake, fakeScript);
    try (app) {
      spawn(app);
      SubagentInstance child = awaitChild(app);

      // 同步点：先等子体自己的回合事件落库（两种形态都会落——真模型的回复 / 模板脚本的固定回复），
      // 此后"端点仍零请求"才算定论（无竞态：真模型的请求必然先于它自己的回合事件）
      await(
          () -> hasEvent(childEvents(configRoot, child.instanceId()), EventTypes.CONVERSATION_TURN),
          30_000,
          "等待子体回合事件落库（真模型/模板脚本两种形态都会落）");
      // 判据一（专抓"无条件传配置根"）：端点零请求——置于同步点之后、文案判据之前，变异下由本条独立变红
      assertThat(stub.served()).as("%s 形态下子体必须留在模板脚本假 LLM 上，假端点不得收到任何请求", shape).isZero();
      // 判据二：回合文本 = 空脚本模板假 LLM 的固定回复（同步点已保证事件在库里，此处是即时判定）
      awaitEventContaining(
          childEvents(configRoot, child.instanceId()),
          EventTypes.CONVERSATION_TURN,
          "output",
          SCRIPT_FAKE_REPLY,
          30_000);
      assertThat(kill(app, child.instanceId()).success()).isTrue();
    }
  }

  // ---------- 工具 ----------

  /**
   * 经编排工具起一个真子进程（与主 Agent 经 LLM 调用同一入口；本用例主 Agent 从不 chat，故端点请求只可能来自子体）。
   *
   * <p>身份必须与生产同源（{@code AgentPipeline} 装配的 main 身份；本夹具没配 {@code CommandModeHolder} ⇒ {@code
   * FULL}）：{@code spawn} 以 {@code context.identity()} 为调用者拼 {@code lineagePath}，4 参构造的 {@code
   * UNKNOWN} 解析不出父 path ⇒ 子体 {@code lineagePath} 记空串，之后连主 Agent 都 kill 不动它（判定⑤：目标 path 空）。
   */
  private void spawn(App app) {
    ToolResult result =
        new ToolExecutionGuard()
            .execute(
                app.runtime().registry(),
                SubagentOrchestrationTools.SPAWN_SUB_AGENT,
                new ToolContext(
                    AccessToken.SYSTEM,
                    AgentPermissionSet.system(),
                    Map.of(),
                    Map.of("templateId", TEMPLATE_ID, "goal", "MED-1 判据任务"),
                    AgentIdentity.main(CommandMode.FULL)));
    assertThat(result.success()).as("spawn 必须成功: %s", result.message()).isTrue();
  }

  private ToolResult kill(App app, String instanceId) {
    return new ToolExecutionGuard()
        .execute(
            app.runtime().registry(),
            SubagentOrchestrationTools.KILL_SUB_AGENT,
            new ToolContext(
                AccessToken.SYSTEM,
                AgentPermissionSet.system(),
                Map.of(),
                Map.of("instanceId", instanceId),
                AgentIdentity.main(CommandMode.FULL)));
  }

  private SubagentInstance awaitChild(App app) throws Exception {
    SubagentInstance[] found = new SubagentInstance[1];
    await(
        () -> {
          found[0] =
              app.subagents().stream()
                  .filter(i -> i.templateId().equals(TEMPLATE_ID))
                  .findFirst()
                  .orElse(null);
          return found[0] != null;
        },
        30_000,
        "等待子 Agent " + TEMPLATE_ID + " 出现");
    return found[0];
  }

  /** 生产语义的配置根 = {@code --data-dir}（子体的数据目录是它底下的 {@code subagents/<id>}，因而必须显式拿到路径）。 */
  private BootConfig bootConfig(Path configRoot, Path templates, boolean demo) {
    return new BootConfig(0, configRoot, demo, "", null, false, "127.0.0.1", 0, templates);
  }

  /**
   * 写配置根：{@code llm.*} 指向本用例的假端点，密钥为哨兵值。返回的目录<b>同时是主 Agent 的数据目录</b>（生产 {@code --data-dir}
   * 即配置根），子进程的数据目录落在它底下的 {@code subagents/<id>}——那里没有 {@code config.json}，故 {@code --config-dir}
   * 是子体拿到端点的唯一来源。
   */
  private Path configRoot() throws Exception {
    Path root = tempDir.resolve("config-root");
    Files.createDirectories(root);
    Files.writeString(
        root.resolve("config.json"),
        "{\"keys\":{\"local\":\""
            + SENTINEL_KEY
            + "\"},\"llm\":{\"baseUrl\":\""
            + stub.baseUrl()
            + "\",\"model\":\""
            + CONFIG_MODEL
            + "\",\"credentialsRef\":\"keys.local\"}}",
        StandardCharsets.UTF_8);
    return root;
  }

  /**
   * 写子 Agent 模板：{@code model} 有意写成离线哨兵 {@code "fake"}（真模式下必须被忽略，R-A2-7）；{@code script} 有意留空——
   * 真模型形态下脚本不生效，而离线形态下空脚本正是 {@link #SCRIPT_FAKE_REPLY} 的来源（两种形态都能判别）。
   */
  private Path writeTemplates() throws Exception {
    Path dir = tempDir.resolve("templates");
    Files.createDirectories(dir);
    Map<String, Object> template =
        Map.ofEntries(
            Map.entry("id", TEMPLATE_ID),
            Map.entry("description", "S1-A2 MED-1 判据模板"),
            Map.entry("systemPrompt", CHILD_MARKER + "：你是 MED-1 判据的子 Agent。"),
            Map.entry("model", "fake"),
            Map.entry("token", "DEFAULT"),
            Map.entry("allowedTools", List.of()),
            Map.entry("deniedTools", List.of()),
            Map.entry("destructiveAllowed", false),
            Map.entry("sensitiveAllowed", false),
            Map.entry("readOnly", false),
            Map.entry("maxTurns", 5),
            Map.entry("maxToolCallsPerTurn", 5),
            Map.entry("timeBudgetSeconds", 60),
            Map.entry("quotaMaxTokens", 0),
            Map.entry("script", List.of()));
    Files.writeString(
        dir.resolve(TEMPLATE_ID + ".json"),
        JSON.writeValueAsString(template),
        StandardCharsets.UTF_8);
    return dir;
  }

  /** 子进程自己的事件库（父装配层注入的 {@code --data-dir} = {@code <配置根>/subagents/<id>}）。 */
  private static Path childEvents(Path configRoot, String instanceId) {
    return configRoot.resolve("subagents").resolve(instanceId).resolve("events.db");
  }

  /** 子体事件库里是否已出现该类型的事件（中性同步点用：不约束 payload 内容，两种 LLM 形态都会落回合事件）。 */
  private static boolean hasEvent(Path db, String type) {
    try (SqliteEventStore store = SqliteEventStore.open(db)) {
      return !store.query(new EventQuery("", type, "", -1, 100)).isEmpty();
    } catch (RuntimeException notReadyYet) {
      return false; // 库刚被（子进程）创建/正被写入：视为"尚未落库"，轮询会再来
    }
  }

  /** 轮询子体事件库里的事件（type + key 精确匹配、值子串匹配——子进程与读取方并发访问 WAL 事件库）。 */
  private static void awaitEventContaining(
      Path db, String type, String key, String contained, long timeoutMillis) throws Exception {
    await(
        () -> {
          try (SqliteEventStore store = SqliteEventStore.open(db)) {
            for (Event event : store.query(new EventQuery("", type, "", -1, 100))) {
              Object actual = payload(event, key);
              if (actual != null && String.valueOf(actual).contains(contained)) {
                return true;
              }
            }
            return false;
          }
        },
        timeoutMillis,
        "等待事件 " + type + " 的 " + key + " 含 [" + contained + "]");
  }

  private static Object payload(Event event, String key) {
    try {
      return JSON.readValue(event.payload(), Map.class).get(key);
    } catch (Exception e) {
      throw new AssertionError("事件 payload 无法解析: " + event.payload(), e);
    }
  }

  private static void await(BooleanSupplier condition, long timeoutMillis, String what)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("超时（" + timeoutMillis + "ms）: " + what);
  }
}
