package io.mosire.main.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.brain.runtime.EventTypes;
import io.mosire.brain.runtime.StopReason;
import io.mosire.brain.runtime.TurnResult;
import io.mosire.brain.skills.SkillTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 技能能力在<b>生产装配</b>里的端到端闭环（离线脚本假 LLM，确定性）：配置的技能根 → 主 Agent 工具面 → 管线回合 → 工具结果。
 *
 * <p><b>为什么值得一条装配级用例</b>：本能力要修的第一个缺陷就是“{@code SkillCatalog} 存在但没接进真实调用链”。单测能证明
 * `SkillAccess`/`SkillTools` 自身对，但证明不了 `App` 把它们接上了——这条用例跑的是 {@code App.start} 的真实装配路径
 * （配置读取、工具注册、上下文来源、授权入口），任何一环没接上都会红。
 *
 * <p>判别性：把技能工具注册从 {@code App} 里去掉（或把技能根接错），{@code skill_list} 的回合会以 {@code TOOL_NOT_FOUND}
 * 收场、事件里没有技能名 ⇒ 转红；把主 Agent 的技能可见面接成 `none`，工具返回里也不会有技能名 ⇒ 同样转红。
 */
class AppSkillWiringTest {

  @TempDir Path tempDir;

  @Test
  void assembledRuntimeServesVisibleSkillsThroughTheRealPipeline() throws IOException {
    Path data = tempDir.resolve("data");
    // 生产缺省技能根 = <dataDir>/skills（与 App 的装配同源）
    Path skills = Files.createDirectories(data.resolve("skills"));
    writeSkill(skills, "alpha", "处理 Kafka 的技能", "ALPHA-正文");

    BootConfig config = new BootConfig(0, data, false, "", null, false, "127.0.0.1", 0);
    // 脚本：先调 skill_list，再用纯文本收尾（一回合两步，确定性）
    LlmClient script = FakeLlmScript.parse("tool:skill_list:{};text:已列出");
    try (App app = App.start(config, script)) {
      assertThat(app.runtime().registry().find(SkillTools.SKILL_LIST))
          .as("技能工具进了主 Agent 工具面")
          .isPresent();

      TurnResult result = app.runtime().chat("有哪些技能？");

      assertThat(result.stopReason()).isEqualTo(StopReason.FINISHED);
      List<Event> toolResults =
          app.queryEvents(new EventQuery("", EventTypes.TOOL_RESULT, "", -1, 20));
      assertThat(toolResults).isNotEmpty();
      String payload = toolResults.get(toolResults.size() - 1).payload();
      assertThat(payload).contains("\"ok\":true").contains("alpha").contains("处理 Kafka 的技能");
    }
  }

  private static void writeSkill(Path root, String name, String description, String body)
      throws IOException {
    Path dir = Files.createDirectories(root.resolve(name));
    Files.writeString(
        dir.resolve("SKILL.md"),
        "---\nname: " + name + "\ndescription: " + description + "\n---\n\n" + body + "\n");
  }
}
