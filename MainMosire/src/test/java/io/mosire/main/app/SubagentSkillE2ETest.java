package io.mosire.main.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.brain.runtime.EventTypes;
import io.mosire.brain.subagent.SubagentInstance;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 技能能力在<b>真实子进程</b>上的 E2E（离线、回环管道）：spawn 出来的子 Agent 用同一套技能工具，且只能看见父级给它的范围。
 *
 * <p>链：主 Agent 的技能面 = 配置的技能根（全可见）→ spawn 两个真实子进程（模板各自声明不同的 {@code allowedSkills}） → 子体经父侧 MCP 链接调
 * {@code skill_list}（工具在父进程执行、判定用<b>子体自己的</b>权限集）。
 *
 * <p><b>判别性</b>：技能范围若不随 spawn 物化/传递（例如忘了把两个命名空间写进子体权限集），两个子体的结果会一样 ⇒ 第二条断言转红；若父侧工具按主 Agent
 * 的权限判定（而不是链上子体的），隐藏技能会漏进子体 ⇒ 同样转红。
 */
class SubagentSkillE2ETest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  @Test
  void spawnedChildrenSeeExactlyTheSkillsTheirTemplateWasGiven() throws Exception {
    Path skills = tempDir.resolve("data").resolve("skills");
    writeSkill(skills, "alpha", "父级给第一个子体的技能");
    writeSkill(skills, "beta", "父级给第二个子体的技能");

    Path templates = Files.createDirectories(tempDir.resolve("templates"));
    writeTemplate(
        templates,
        "sees-alpha",
        List.of("skill_list"),
        List.of(skills.resolve("alpha").toString()));
    writeTemplate(
        templates, "sees-beta", List.of("skill_list"), List.of(skills.resolve("beta").toString()));

    FakeLlmClient mainLlm =
        FakeLlmClient.with(
            LlmResponse.toolCall(
                "m-1", "spawn_sub_agent", Map.of("templateId", "sees-alpha", "goal", "看技能")),
            LlmResponse.text("已派遣第一个"),
            LlmResponse.toolCall(
                "m-2", "spawn_sub_agent", Map.of("templateId", "sees-beta", "goal", "看技能")),
            LlmResponse.text("已派遣第二个"));

    Path dataDir = tempDir.resolve("data");
    BootConfig config =
        new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0, templates);
    try (App app = App.start(config, mainLlm, List.of())) {
      app.runtime().chat("派一个看得到 alpha 的子 Agent");
      SubagentInstance alphaChild = awaitChild(app, i -> "sees-alpha".equals(i.templateId()));
      String alphaMessage = awaitSkillListResult(dataDir, alphaChild.instanceId(), "父级给第一个子体的技能");

      app.runtime().chat("派一个看得到 beta 的子 Agent");
      SubagentInstance betaChild = awaitChild(app, i -> "sees-beta".equals(i.templateId()));
      String betaMessage = awaitSkillListResult(dataDir, betaChild.instanceId(), "父级给第二个子体的技能");

      // 各自只看得见模板给的那一个：可见面真的随 spawn 传到了链上（不是“子体一律看全部”）
      assertThat(alphaMessage).contains("alpha").doesNotContain("beta");
      assertThat(betaMessage).contains("beta").doesNotContain("alpha");
      // 没有任何权限拒绝：技能工具对这两个子体是“够得着”的（DENY 缺省没有误伤显式授权的调用者）
      assertThat(
              eventsOf(childEvents(dataDir, alphaChild.instanceId()), EventTypes.PERMISSION_DENIED))
          .isEmpty();
      assertThat(
              eventsOf(childEvents(dataDir, betaChild.instanceId()), EventTypes.PERMISSION_DENIED))
          .isEmpty();
    }
  }

  // ---------- 夹具 ----------

  /** 等子体事件库里的 {@code skill_list} 成功结果，且消息里出现期望的技能用途（返回该条消息）。 */
  private String awaitSkillListResult(Path dataDir, String instanceId, String expectedText)
      throws Exception {
    Path db = childEvents(dataDir, instanceId);
    await(
        () ->
            eventsOf(db, EventTypes.TOOL_RESULT).stream()
                .anyMatch(
                    e ->
                        "skill_list".equals(payload(e, "tool"))
                            && Boolean.TRUE.equals(payload(e, "ok"))
                            && String.valueOf(payload(e, "message")).contains(expectedText)),
        30_000,
        "等待子体 " + instanceId + " 的 skill_list 结果含「" + expectedText + "」");
    return eventsOf(db, EventTypes.TOOL_RESULT).stream()
        .filter(e -> "skill_list".equals(payload(e, "tool")))
        .map(e -> String.valueOf(payload(e, "message")))
        .reduce((first, second) -> second)
        .orElseThrow();
  }

  private SubagentInstance awaitChild(App app, Predicate<SubagentInstance> match) throws Exception {
    SubagentInstance[] found = new SubagentInstance[1];
    await(
        () -> {
          found[0] = app.subagents().stream().filter(match).findFirst().orElse(null);
          return found[0] != null;
        },
        30_000,
        "等待子 Agent 出现");
    return found[0];
  }

  private static void writeSkill(Path root, String name, String description) throws Exception {
    Path dir = Files.createDirectories(root.resolve(name));
    Files.writeString(
        dir.resolve("SKILL.md"),
        "---\nname: " + name + "\ndescription: " + description + "\n---\n\n正文\n");
  }

  /** 子 Agent 模板：脚本是“调一次 skill_list，然后文本收尾”；可见面 = 给定的技能路径。 */
  private static void writeTemplate(
      Path dir, String id, List<String> allowedTools, List<String> allowedSkills) throws Exception {
    Map<String, Object> template =
        Map.ofEntries(
            Map.entry("id", id),
            Map.entry("description", "技能 E2E 模板 " + id),
            Map.entry("systemPrompt", "你是测试子 Agent（模板 " + id + "）。"),
            Map.entry("model", "fake"),
            Map.entry("token", "DEFAULT"),
            Map.entry("allowedTools", allowedTools),
            Map.entry("deniedTools", List.of()),
            Map.entry("destructiveAllowed", false),
            Map.entry("sensitiveAllowed", false),
            Map.entry("readOnly", false),
            Map.entry("maxTurns", 10),
            Map.entry("maxToolCallsPerTurn", 5),
            Map.entry("timeBudgetSeconds", 120),
            Map.entry("quotaMaxTokens", 0),
            Map.entry("allowedSkills", allowedSkills),
            Map.entry(
                "script", List.of(Map.of("toolCall", "skill_list", "args", Map.of(), "text", ""))));
    Files.writeString(dir.resolve(id + ".json"), JSON.writeValueAsString(template));
  }

  private static Path childEvents(Path dataDir, String instanceId) {
    return dataDir.resolve("subagents").resolve(instanceId).resolve("events.db");
  }

  private static List<Event> eventsOf(Path db, String type) {
    try (SqliteEventStore store = SqliteEventStore.open(db)) {
      return store.query(new EventQuery("", type, "", -1, 100));
    }
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
