package io.mosire.main.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.ConfigApiKeySource;
import io.mosire.agentlib.llm.LlmRouteLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 三期 S1-A2：{@code Main agent --config-dir <父的配置根>} 的<b>真实子进程</b>判据（全程离线）。
 *
 * <p>被测面是"子 Agent 进程在真模型形态下的行为"，不是网络连通性（真连通性归 S1-B 探索测试，D22）：假 OpenAI 端点绑回环 {@code 127.0.0.1} +
 * 临时端口，子进程经 {@code --config-dir} 指向的 {@code config.json} 拿到该端口——<b>路径</b>经参数面传递、
 * <b>密钥值</b>由子进程自己从配置文件读（D23）。
 *
 * <p>四条硬顶各一条用例（R-A2-8 要求实测，不接受"读代码看起来是"）：假端点永不主动收尾时，唯一能让子进程停下的就是硬顶； 断言压在"端点被请求了几次"与"落库的 decision
 * 事件"这两个可观测事实上。
 */
class SubagentRealModelTest {

  /** 哨兵密钥值：只活在 {@code @TempDir} 的假配置里（真密钥从不进测试）。 */
  private static final String SENTINEL_KEY = "sk-sentinel-not-a-real-key";

  /** 配置里的模型名（模板里写的是离线哨兵 {@code "fake"}——真模式下不得把它当模型名发出去，R-A2-7）。 */
  private static final String CONFIG_MODEL = "stub-model";

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

  // ---------- ④ 在场但配置不可用 ⇒ 响亮（绝不静默回退脚本假 LLM） ----------

  /**
   * {@code --config-dir} 指向一个<b>没有 config.json</b> 的目录：子进程必须非零退出、stderr 有非敏感错误码，且<b>没有</b>任何
   * "悄悄用假模型跑完了"的迹象。
   *
   * <p>判别力：假形态（{@code template.scriptedFakeLlm()}）遇空脚本会回一句默认话并打印"子 Agent 回合完成"——"配置读不到就退回假模型"
   * 的实现会因该行出现而变红。
   */
  @Test
  void configDirWithoutConfigFileFailsLoudlyInsteadOfFallingBackToTheFakeModel() throws Exception {
    Path templates = writeTemplate("probe", 5, 5, 60, 0);
    Path configRoot = tempDir.resolve("empty-config-root");
    Files.createDirectories(configRoot); // 目录在、config.json 不在

    ChildRun run =
        run("missing-config", "probe", templates, tempDir.resolve("child-a"), configRoot);

    assertThat(run.finished()).as("配置缺失必须立刻失败，不得挂起").isTrue();
    assertThat(run.exitCode()).as("缺 llm.* ⇒ 非零退出（R-A2-2/D24）").isNotZero();
    assertThat(run.stderr()).contains(LlmRouteLoader.E_LLM_CONFIG_MISSING);
    assertThat(run.stderr()).as("绝不静默回退脚本假 LLM（退回假模型会走完一回合并打印该行）").doesNotContain("回合完成");
  }

  /** 配置在、{@code llm.*} 全，但被引用的 {@code keys.*} 没有可取用值 ⇒ 同样非零退出，且在建连之前就失败。 */
  @Test
  void configDirWithUnresolvableKeyFailsLoudlyBeforeAnyCall() throws Exception {
    Path templates = writeTemplate("probe", 5, 5, 60, 0);
    Path configRoot = configRoot("keys.missing", Map.of("other", SENTINEL_KEY));

    ChildRun run = run("missing-key", "probe", templates, tempDir.resolve("child-b"), configRoot);

    assertThat(run.finished()).isTrue();
    assertThat(run.exitCode()).as("缺密钥 ⇒ 非零退出（R-A2-2/D24）").isNotZero();
    assertThat(run.stderr()).contains(ConfigApiKeySource.E_KEY_MISSING);
    assertThat(run.stderr()).as("绝不当成匿名部署继续跑").doesNotContain("回合完成");
    assertThat(stub.served()).as("取密钥失败发生在建连之前：假端点一次也不该被请求").isZero();
    assertThat(run.stderr()).as("夹具里的哨兵值不得随子进程输出回流").doesNotContain(SENTINEL_KEY);
  }

  // ---------- R-A2-8：真模型形态下四条硬顶仍然拦得住 ----------

  /**
   * {@code maxTurns} 硬顶：假端点<b>每次</b>都回一个工具调用（LLM 永不主动收尾）⇒ 唯一能让子进程停下的就是轮次硬顶。
   *
   * <p>判别力：硬顶被绕过（例如真模式下改用默认 {@code AgentConfig}，其 {@code maxTurns}=2000）时端点会被请求到远超 2 次——本用例 按"恰好 2
   * 次 + TURN_LIMIT 落库"断言，不依赖"看起来停下来了"。
   *
   * <p>同时钉住 R-A2-7：请求体里的模型名必须是<b>配置</b>的，模板里的 {@code "fake"} 不得出现。
   */
  @Test
  void templateMaxTurnsStopsAnEndlessRealModel() throws Exception {
    stub.withToolCalls(1);
    Path templates = writeTemplate("capped", 2, 5, 60, 0);
    Path configRoot = configRoot("keys.local", Map.of("local", SENTINEL_KEY));
    Path dataDir = tempDir.resolve("child-turn-cap");

    ChildRun run = run("turn-cap", "capped", templates, dataDir, configRoot);

    assertThat(run.finished()).as("硬顶必须让子进程自己停下——被绕过就是无限跑").isTrue();
    assertThat(run.exitCode()).isZero();
    assertThat(stub.served()).as("maxTurns=2 ⇒ 假端点恰好被请求 2 次").isEqualTo(2);
    assertThat(decisions(dataDir))
        .anySatisfy(
            payload -> assertThat(payload).contains("TURN_LIMIT").contains("\"maxTurns\":2"));
    assertThat(stub.requestBodies())
        .isNotEmpty()
        .allSatisfy(body -> assertThat(body).contains("\"model\":\"" + CONFIG_MODEL + "\""))
        .allSatisfy(body -> assertThat(body).doesNotContain("\"model\":\"fake\""));
  }

  /** {@code maxToolCallsPerTurn} 硬顶：单次响应 2 个工具调用、上限 1 ⇒ 第一回合即终止（端点只被请求一次）。 */
  @Test
  void templateMaxToolCallsPerTurnStopsTheRealModelPath() throws Exception {
    stub.withToolCalls(2);
    Path templates = writeTemplate("tool-capped", 5, 1, 60, 0);
    Path configRoot = configRoot("keys.local", Map.of("local", SENTINEL_KEY));
    Path dataDir = tempDir.resolve("child-tool-cap");

    ChildRun run = run("tool-cap", "tool-capped", templates, dataDir, configRoot);

    assertThat(run.exitCode()).isZero();
    assertThat(stub.served()).as("单回合工具调用超限 ⇒ 第一回合就终止").isEqualTo(1);
    assertThat(decisions(dataDir))
        .anySatisfy(
            payload -> assertThat(payload).contains("TOOL_CALL_LIMIT").contains("\"max\":1"));
  }

  /** {@code quotaMaxTokens} 硬顶：账本吃真模型响应里的 usage，越界由管线终止为 QUOTA。 */
  @Test
  void templateQuotaStopsTheRealModelPath() throws Exception {
    stub.withToolCalls(1).withUsage(500, 500);
    Path templates = writeTemplate("quota-capped", 5, 5, 60, 100);
    Path configRoot = configRoot("keys.local", Map.of("local", SENTINEL_KEY));
    Path dataDir = tempDir.resolve("child-quota");

    ChildRun run = run("quota", "quota-capped", templates, dataDir, configRoot);

    assertThat(run.exitCode()).isZero();
    assertThat(stub.served()).as("配额越界 ⇒ 第一回合就终止").isEqualTo(1);
    assertThat(decisions(dataDir)).anySatisfy(payload -> assertThat(payload).contains("QUOTA"));
  }

  /** {@code timeBudgetSeconds} 硬顶：每响应延迟 1.5s、预算 1s ⇒ 第一回合后即因超时终止。 */
  @Test
  void templateTimeBudgetStopsTheRealModelPath() throws Exception {
    stub.withToolCalls(1).withDelay(Duration.ofMillis(1500));
    Path templates = writeTemplate("time-capped", 5, 5, 1, 0);
    Path configRoot = configRoot("keys.local", Map.of("local", SENTINEL_KEY));
    Path dataDir = tempDir.resolve("child-time-budget");

    ChildRun run = run("time-budget", "time-capped", templates, dataDir, configRoot);

    assertThat(run.exitCode()).isZero();
    assertThat(stub.served()).as("预算 1s 内只跑完一次真模型调用").isEqualTo(1);
    assertThat(decisions(dataDir))
        .anySatisfy(payload -> assertThat(payload).contains("TIME_BUDGET"));
  }

  // ---------- 工具 ----------

  /**
   * 起一个真实子进程：与生产 argv 同形（{@code java -Xmx128m -cp <classpath> io.mosire.main.Main agent ...}；
   * {@code --config-dir} 缺席时与既有用例的命令完全一致）。超时则强杀并如实记录为"未 finished"。
   */
  private ChildRun run(String tag, String templateId, Path templates, Path dataDir, Path configRoot)
      throws Exception {
    List<String> argv =
        new ArrayList<>(
            List.of(
                "java",
                "-Xmx128m",
                "-cp",
                System.getProperty("java.class.path"),
                "io.mosire.main.Main",
                "agent",
                "--id",
                "probe-1",
                "--template",
                templateId,
                "--goal",
                "真模型自检",
                "--templates-dir",
                templates.toString(),
                "--data-dir",
                dataDir.toString()));
    if (configRoot != null) {
      argv.add("--config-dir");
      argv.add(configRoot.toString());
    }
    Path stderrFile = tempDir.resolve(tag + "-stderr.txt");
    Process child =
        new ProcessBuilder(argv)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(stderrFile.toFile())
            .start();
    boolean finished = child.waitFor(60, TimeUnit.SECONDS);
    if (!finished) {
      child.destroyForcibly();
      child.waitFor(10, TimeUnit.SECONDS);
    }
    return new ChildRun(
        finished, child.exitValue(), Files.readString(stderrFile, StandardCharsets.UTF_8));
  }

  /** 子进程落库的 {@code decision} 事件 payload（硬顶判据的可观测面）。 */
  private static List<String> decisions(Path dataDir) {
    try (SqliteEventStore store = SqliteEventStore.open(dataDir.resolve("events.db"))) {
      return store.query(new EventQuery("", "decision", "", -1, 100)).stream()
          .map(Event::payload)
          .toList();
    }
  }

  /**
   * 写一份子 Agent 模板（字段名与 {@code AgentTemplate} 组件同名）并返回模板目录。{@code model} 有意写成离线哨兵 {@code "fake"}
   * （真模式下必须被忽略，R-A2-7）；{@code script} 有意留空——真模型形态下脚本<b>不生效</b>，留空正好证明"子体不是靠脚本跑起来的"。
   */
  private Path writeTemplate(
      String id, int maxTurns, int maxToolCallsPerTurn, long timeBudgetSeconds, long quotaMaxTokens)
      throws Exception {
    Path dir = tempDir.resolve("templates");
    Files.createDirectories(dir);
    Map<String, Object> template =
        Map.ofEntries(
            Map.entry("id", id),
            Map.entry("description", "S1-A2 真模型判据模板 " + id),
            Map.entry("systemPrompt", "你是测试子 Agent（模板 " + id + "）。"),
            Map.entry("model", "fake"),
            Map.entry("token", "DEFAULT"),
            Map.entry("allowedTools", List.of(OpenAiStubServer.TOOL_NAME)),
            Map.entry("deniedTools", List.of()),
            Map.entry("destructiveAllowed", false),
            Map.entry("sensitiveAllowed", false),
            Map.entry("readOnly", false),
            Map.entry("maxTurns", maxTurns),
            Map.entry("maxToolCallsPerTurn", maxToolCallsPerTurn),
            Map.entry("timeBudgetSeconds", timeBudgetSeconds),
            Map.entry("quotaMaxTokens", quotaMaxTokens),
            Map.entry("script", List.of()));
    Files.writeString(
        dir.resolve(id + ".json"), JSON.writeValueAsString(template), StandardCharsets.UTF_8);
    return dir;
  }

  /** 写配置根：{@code llm.*} 指向假端点，{@code credentialsRef} = {@code refName}，{@code keys} 段由调用方给出。 */
  private Path configRoot(String refName, Map<String, String> keys) throws Exception {
    Path root = tempDir.resolve("config-root");
    Files.createDirectories(root);
    StringBuilder keysJson = new StringBuilder();
    for (Map.Entry<String, String> entry : keys.entrySet()) {
      keysJson
          .append(keysJson.isEmpty() ? "" : ",")
          .append(JSON.writeValueAsString(entry.getKey()))
          .append(":")
          .append(JSON.writeValueAsString(entry.getValue()));
    }
    Files.writeString(
        root.resolve("config.json"),
        "{\"keys\":{"
            + keysJson
            + "},\"llm\":{\"baseUrl\":\""
            + stub.baseUrl()
            + "\",\"model\":\""
            + CONFIG_MODEL
            + "\",\"credentialsRef\":\""
            + refName
            + "\"}}",
        StandardCharsets.UTF_8);
    return root;
  }

  /** 一次子进程运行的观测结果。 */
  private record ChildRun(boolean finished, int exitCode, String stderr) {}
}
