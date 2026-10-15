package io.mosire.main.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.subagent.AgentTemplate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★ 装订期角色审计在<b>真 {@code App.start} 装配点</b>的行为（设计 §4.6；不变量 A-4 / A-5）：未知角色名 ⇒ <b>启动失败</b>； 报告项 ⇒ 只
 * WARN、照常启动。
 *
 * <p><b>为什么必须真启动一次</b>：{@code RoleCatalogAudit} 的单元用例只证明"它算得出失败"；"失败真的拦住启动"是 {@code App} 装配点 那一行
 * {@code throw new IllegalStateException(...)} 的性质——审计类改对了、装配点忘了读 {@code hasFailures()}， 单元用例一律照绿。
 *
 * <p><b>怎么造出"未登记角色名"</b>：{@code App.start} 的 3 参重载收 {@code extraTools}，且它们在审计之前注册进主注册表——注入一个声明了
 * 幽灵角色的合成工具即可，不必改生产代码。
 *
 * <p><b>判别性（变异自证）</b>：删掉 {@code RoleCatalogAudit} 的未登记角色检查（或让 {@code App} 忽略 {@code
 * hasFailures()}）⇒ {@link #unknownRoleDeclaredByAnInjectedToolFailsStartup} 转红（"启动失败"变成"启动成功"）。
 */
class AppRoleAuditTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  /** ★ A-4 主判据：工具声明未登记角色 ⇒ {@code App.start} 抛 {@code IllegalStateException}，明细点名工具 + 角色。 */
  @Test
  void unknownRoleDeclaredByAnInjectedToolFailsStartup() {
    BootConfig config = bootConfig(tempDir.resolve("data"), null);

    assertThatThrownBy(
            () ->
                App.start(
                    config,
                    FakeLlmClient.with(LlmResponse.text("不会被跑到")),
                    List.of(tool("ghost_tool", Set.of("ghost")))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("装订期角色审计失败")
        .hasMessageContaining("ghost_tool")
        .hasMessageContaining("ghost");
  }

  /**
   * 正向对照：同一个工具把角色换成配置里登记过的名字 ⇒ 启动成功，且主 Agent 的身份里真的是那个角色（配置 {@code agent.roles} 生效）。
   *
   * <p>没有这条，"启动失败"可能只是"这个 App 怎么都起不来"。
   */
  @Test
  void aRegisteredRoleStartsAndBecomesTheMainAgentIdentity() throws IOException {
    Path dataDir = tempDir.resolve("data");
    writeAgentRoles(dataDir.resolve("config.json"), "[\"gm\"]");
    BootConfig config = bootConfig(dataDir, null);

    try (App app =
        App.start(
            config,
            FakeLlmClient.with(LlmResponse.text("你好")),
            List.of(tool("gm_tool", Set.of("gm"))))) {
      assertThat(app.runtime().permissionSet().roles().roles()).containsExactly("gm");
      assertThat(app.runtime().registry().find("gm_tool")).isPresent();
    }
  }

  /** 报告项（角色底下没有工具）只 WARN、<b>不</b>拦启动：配了角色但这一维今天不起作用是正常部署态。 */
  @Test
  void aRoleThatNoToolCoversIsOnlyReportedAndStartupSucceeds() throws IOException {
    Path dataDir = tempDir.resolve("data");
    writeAgentRoles(dataDir.resolve("config.json"), "[\"gm\"]");

    try (App app =
        App.start(
            bootConfig(dataDir, null),
            FakeLlmClient.with(LlmResponse.text("你好")),
            List.of(tool("plain_tool", Set.of())))) {
      assertThat(app.runtime().permissionSet().roles().roles())
          .as("角色维照常生效（只是没有工具声明它）")
          .containsExactly("gm");
    }
  }

  /**
   * 装配层的"已知角色全集"并入<b>在场模板声明的角色</b>：配置里没有 {@code agent.roles}，模板声明了 {@code dm}， 注入的工具也声明 {@code dm}
   * ⇒ 不该被报成"未登记"。
   *
   * <p>判别性：把 {@code App} 里 {@code templateStore.all()} 那一段去掉 ⇒ 本用例转红（启动失败）。
   */
  @Test
  void templateDeclaredRolesJoinTheKnownRoleSet() throws IOException {
    Path dataDir = tempDir.resolve("data");
    Path templates = tempDir.resolve("templates");
    Files.createDirectories(templates);
    writeTemplate(templates, template("dm-reader", List.of("dm")));

    try (App app =
        App.start(
            bootConfig(dataDir, templates),
            FakeLlmClient.with(LlmResponse.text("你好")),
            List.of(tool("dm_tool", Set.of("dm"))))) {
      assertThat(app.runtime().registry().find("dm_tool")).isPresent();
    }
  }

  // ────────────────────────── 夹具 ──────────────────────────

  /** 非 demo、不暴露 MCP、端口 0：起真 App（真 HTTP 面）但不触网。 */
  private static BootConfig bootConfig(Path dataDir, Path templatesDir) {
    return new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0, templatesDir);
  }

  private static AgentTool tool(String name, Set<String> roles) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return "装订期审计探针工具 " + name;
      }

      @Override
      public Set<String> roles() {
        return roles;
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok(name);
      }
    };
  }

  private static void writeAgentRoles(Path configFile, String rolesJson) throws IOException {
    Files.createDirectories(configFile.getParent());
    Files.writeString(
        configFile, "{\"agent\": {\"roles\": " + rolesJson + "}}", StandardCharsets.UTF_8);
  }

  private static void writeTemplate(Path templatesDir, AgentTemplate template) {
    try {
      JSON.writeValue(templatesDir.resolve(template.id() + ".json").toFile(), template);
    } catch (IOException e) {
      throw new UncheckedIOException("模板写入失败: " + template.id(), e);
    }
  }

  /** 21 参规范构造（roles 在最后）。 */
  private static AgentTemplate template(String id, List<String> roles) {
    return new AgentTemplate(
        id,
        "测试模板 " + id,
        "你是只读子 Agent。",
        "fake",
        AccessToken.DEFAULT,
        Set.of("*"),
        Set.of(),
        false,
        false,
        false,
        2000,
        30,
        600,
        0,
        List.of(),
        null,
        null,
        null,
        null,
        null,
        roles);
  }
}
