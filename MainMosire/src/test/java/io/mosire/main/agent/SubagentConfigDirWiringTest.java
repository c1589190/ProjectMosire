package io.mosire.main.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.runtime.EventTypes;
import io.mosire.brain.subagent.SubagentInstance;
import io.mosire.main.app.App;
import io.mosire.main.app.BootConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 三期 S1-A2 的<b>装配面</b>判据：{@code App.start(config, llm, extraTools, subagentConfigDir)} 把 {@code
 * subagentConfigDir} 一路透传到子进程 argv（{@code App → wireSubagents → subagentCommand →
 * AgentCommand.argv}）。
 *
 * <p>判别力来自"子体产出的内容只能来自假端点"：主 Agent 用脚本假 LLM 派生一个真子进程，子进程经 {@code --config-dir} 指向的 {@code
 * config.json} 连上本用例的回环假端点（真模型形态），其回合文本因而<b>等于假端点的回复</b>——若装配层把 {@code null} 或
 * 漏传（"忘传"是最容易犯的错），子进程会退回模板脚本假 LLM（空脚本 ⇒ "子 Agent 无引导脚本…"），本用例立即变红。
 *
 * <p>全离线：假端点绑回环 {@code 127.0.0.1} + 临时端口，不碰任何真实供应商（D22）。
 */
class SubagentConfigDirWiringTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String CONFIG_MODEL = "stub-model";

  @TempDir Path tempDir;

  @Test
  void appPassesTheConfigDirSoTheChildSpeaksToTheRealModel() throws Exception {
    OpenAiStubServer stub = new OpenAiStubServer(CONFIG_MODEL);
    try (stub) {
      Path templates = tempDir.resolve("templates");
      Files.createDirectories(templates);
      writeTemplate(templates, "real");
      Path configRoot = configRoot(stub);
      Path dataDir = tempDir.resolve("data");
      BootConfig config =
          new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0, templates);
      FakeLlmClient mainLlm =
          FakeLlmClient.with(
              LlmResponse.toolCall(
                  "m-1", "spawn_sub_agent", Map.of("templateId", "real", "goal", "真模型自检")),
              LlmResponse.text("已派遣"));

      App app = App.start(config, mainLlm, List.of(), configRoot);
      try (app) {
        app.runtime().chat("派一个真模型子 Agent");
        SubagentInstance child = awaitChild(app, "real");

        await(
            () -> stub.served() >= 1,
            30_000,
            "等待子进程按 --config-dir 找到配置并请求真端点（假端点一次都没被请求 = 配置目录没传到）");
        // 子体回合文本 = 假端点的回复 ⇒ 子体确实走的是配置里的真模型（脚本假 LLM 只会回"无引导脚本"）
        awaitEventContaining(
            childEvents(dataDir, child.instanceId()),
            EventTypes.CONVERSATION_TURN,
            "output",
            OpenAiStubServer.DEFAULT_TEXT,
            30_000);
        assertThat(kill(app, child.instanceId()).success()).isTrue();
      }
    }
  }

  // ---------- 工具 ----------

  /** 空脚本、零工具白名单的最小模板（真模型形态下这两个字段都不参与行为——脚本不生效、工具面为空）。 */
  private void writeTemplate(Path dir, String id) throws Exception {
    Map<String, Object> template =
        Map.ofEntries(
            Map.entry("id", id),
            Map.entry("description", "S1-A2 装配面判据模板 " + id),
            Map.entry("systemPrompt", "你是测试子 Agent（模板 " + id + "）。"),
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
        dir.resolve(id + ".json"), JSON.writeValueAsString(template), StandardCharsets.UTF_8);
  }

  /** 配置根：{@code llm.*} 指向本用例的假端点；密钥是哨兵值（只活在这个 {@code @TempDir} 里）。 */
  private Path configRoot(OpenAiStubServer stub) throws Exception {
    Path root = tempDir.resolve("config-root");
    Files.createDirectories(root);
    Files.writeString(
        root.resolve("config.json"),
        "{\"keys\":{\"local\":\"sk-sentinel-not-a-real-key\"},\"llm\":{\"baseUrl\":\""
            + stub.baseUrl()
            + "\",\"model\":\""
            + CONFIG_MODEL
            + "\",\"credentialsRef\":\"keys.local\"}}",
        StandardCharsets.UTF_8);
    return root;
  }

  private ToolResult kill(App app, String instanceId) {
    return new ToolExecutionGuard()
        .execute(
            app.runtime().registry(),
            "kill_sub_agent",
            new ToolContext(
                AccessToken.SYSTEM,
                AgentPermissionSet.system(),
                Map.of(),
                Map.of("instanceId", instanceId)));
  }

  private SubagentInstance awaitChild(App app, String templateId) throws Exception {
    SubagentInstance[] found = new SubagentInstance[1];
    await(
        () -> {
          found[0] =
              app.subagents().stream()
                  .filter(i -> i.templateId().equals(templateId))
                  .findFirst()
                  .orElse(null);
          return found[0] != null;
        },
        30_000,
        "等待子 Agent " + templateId + " 出现");
    return found[0];
  }

  /** 轮询子体事件库里的事件（type + key 精确匹配、key2 子串匹配——子体进程与读取方并发访问 WAL 事件库）。 */
  private void awaitEventContaining(
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

  private static Path childEvents(Path dataDir, String instanceId) {
    return dataDir.resolve("subagents").resolve(instanceId).resolve("events.db");
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
