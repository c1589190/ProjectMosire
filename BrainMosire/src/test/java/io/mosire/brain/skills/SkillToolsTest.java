package io.mosire.brain.skills;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 技能工具面的端到端行为（离线）：<b>经工具调用唯一入口</b> {@link ToolCallAuthorizer} 走完整判定链 （工具名级 → 资源声明级 →
 * 逐路径级），断言的是模型实际拿到的错误码与结果，而不是内部方法。
 *
 * <p>本类钉住的是“硬安全边界”的<b>工具层</b>那半：没有可见面的技能列不出来、读不到；没有可编辑面的技能改不动； 未表态（没配技能面）的调用者连工具都够不着（{@code
 * RESOURCE_DENIED}）。文件工具那半在 {@code ShellToolSkillFenceTest}。
 */
class SkillToolsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private Path root;
  private SkillCatalog catalog;
  private ToolRegistry registry;
  private ToolCallAuthorizer authorizer;

  @BeforeEach
  void setUp() throws IOException {
    root = Files.createDirectories(tempDir.resolve("skills"));
    catalog = new SkillCatalog();
    catalog.scan(root);
    registry = new ToolRegistry();
    for (AgentTool tool : SkillTools.all(catalog, root)) {
      registry.register(tool);
    }
    authorizer = ToolCallAuthorizer.standard();
  }

  @Test
  void listShowsOnlyVisibleSkillsAndDisclosesBrokenOnes() throws IOException {
    writeSkill("alpha", "可见技能", "ALPHA 正文");
    writeSkill("secret", "隐藏技能", "SECRET 正文");
    writeSkill("broken", "坏技能", "BROKEN");
    Files.writeString(root.resolve("broken").resolve("SKILL.md"), "坏文件");

    ToolResult result =
        call(
            SkillTools.SKILL_LIST,
            Map.of(),
            scopes(ResourceScope.ofDir(root.resolve("alpha")), ResourceScope.none()));

    assertThat(result.success()).as(result.message()).isTrue();
    Map<String, Object> body = json(result.message());
    assertThat(body.get("count")).isEqualTo(1);
    String text = result.message();
    assertThat(text).contains("alpha").doesNotContain("SECRET 正文").doesNotContain("secret");
    // 坏技能被披露（不是静默跳过）——但它也没有把整次调用打回去
    assertThat(text).contains("broken");
  }

  @Test
  void callerWithoutAnySkillsDeclarationCannotEvenReachTheTool() {
    ToolResult result = call(SkillTools.SKILL_LIST, Map.of(), AgentPermissionSet.system());

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
  }

  @Test
  void loadHiddenSkillIsDeniedAndBodyNeverLeaks() throws IOException {
    writeSkill("alpha", "可见", "ALPHA 正文");
    writeSkill("secret", "隐藏", "SECRET-正文-哨兵");
    AgentPermissionSet permissions =
        scopes(ResourceScope.ofDir(root.resolve("alpha")), ResourceScope.none());

    ToolResult hidden = call(SkillTools.SKILL_LOAD, Map.of("name", "secret"), permissions);
    assertThat(hidden.success()).isFalse();
    assertThat(hidden.code()).isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
    assertThat(hidden.message()).doesNotContain("SECRET-正文-哨兵");

    ToolResult visible = call(SkillTools.SKILL_LOAD, Map.of("name", "alpha"), permissions);
    assertThat(visible.success()).as(visible.message()).isTrue();
    assertThat(visible.message()).contains("ALPHA 正文");
    assertThat(visible.message()).contains("\"name\":\"alpha\"");
  }

  @Test
  void searchOnlyReturnsVisibleHits() throws IOException {
    writeSkill("alpha", "处理 Kafka", "ALPHA 正文");
    writeSkill("secret", "隐藏的 Kafka 技能", "SECRET 正文");
    AgentPermissionSet permissions =
        scopes(ResourceScope.ofDir(root.resolve("alpha")), ResourceScope.none());

    ToolResult result = call(SkillTools.SKILL_SEARCH, Map.of("query", "kafka"), permissions);
    assertThat(result.success()).as(result.message()).isTrue();
    assertThat(result.message()).contains("alpha").doesNotContain("secret");
  }

  @Test
  void editWithoutEditableScopeIsDeniedAndFileUntouched() throws IOException {
    writeSkill("alpha", "用途", "原正文");
    Path file = root.resolve("alpha").resolve("SKILL.md");
    byte[] before = Files.readAllBytes(file);

    ToolResult denied =
        call(
            SkillTools.SKILL_EDIT,
            Map.of("name", "alpha", "body", "改写"),
            scopes(ResourceScope.unlimited(), ResourceScope.none()));
    assertThat(denied.success()).isFalse();
    assertThat(denied.code()).isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
    assertThat(Files.readAllBytes(file)).isEqualTo(before);
  }

  @Test
  void createWritesValidatedDocumentAndNextListSeesIt() throws IOException {
    AgentPermissionSet permissions = scopes(ResourceScope.unlimited(), ResourceScope.unlimited());

    ToolResult created =
        call(
            SkillTools.SKILL_CREATE,
            Map.of(
                "name",
                "newbie",
                "description",
                "新技能",
                "body",
                "正文内容",
                "allowedTools",
                List.of("Read")),
            permissions);
    assertThat(created.success()).as(created.message()).isTrue();
    assertThat(created.message()).contains("newbie");
    assertThat(Files.readString(root.resolve("newbie").resolve("SKILL.md")))
        .contains("name: newbie")
        .contains("正文内容");

    // 写入后同一运行时下一次列表可见（写路径内部重扫）
    ToolResult listed = call(SkillTools.SKILL_LIST, Map.of(), permissions);
    assertThat(listed.message()).contains("newbie");

    // 校验不过：响亮失败且盘上不留东西
    ToolResult invalid =
        call(
            SkillTools.SKILL_CREATE,
            Map.of("name", "bad", "description", "多行\n描述", "body", "x"),
            permissions);
    assertThat(invalid.success()).isFalse();
    assertThat(invalid.code()).isEqualTo(SkillException.INVALID_ARGUMENTS);
    assertThat(Files.exists(root.resolve("bad"))).isFalse();
  }

  @Test
  void unknownNameInsideVisibleScopeYieldsSkillNotFound() throws IOException {
    writeSkill("alpha", "用途", "正文");
    ToolResult result =
        call(
            SkillTools.SKILL_LOAD,
            Map.of("name", "ghost"),
            scopes(ResourceScope.unlimited(), ResourceScope.none()));
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo(SkillException.SKILL_NOT_FOUND);
  }

  @Test
  void readToolsAreNotDestructiveWhileWriteToolsAre() {
    assertThat(registry.find(SkillTools.SKILL_LIST).orElseThrow().spec().destructive()).isFalse();
    assertThat(registry.find(SkillTools.SKILL_LOAD).orElseThrow().spec().destructive()).isFalse();
    assertThat(registry.find(SkillTools.SKILL_CREATE).orElseThrow().spec().destructive()).isTrue();
    assertThat(registry.find(SkillTools.SKILL_EDIT).orElseThrow().spec().destructive()).isTrue();
  }

  /** 事件面不复制正文：写工具的 {@code ledgerArgs} 只报长度与元数据（与 bash 的脱敏同一条思路）。 */
  @Test
  void writeToolsDoNotPutTheWholeBodyIntoLedgerArgs() {
    AgentTool create = registry.find(SkillTools.SKILL_CREATE).orElseThrow();
    ToolContext context =
        new ToolContext(
            AccessToken.DEFAULT,
            scopes(ResourceScope.unlimited(), ResourceScope.unlimited()),
            Map.of(),
            Map.of("name", "x", "description", "用途", "body", "很长的正文".repeat(100)));
    Map<String, Object> ledger = create.ledgerArgs(context);
    assertThat(ledger).containsKey("bodyChars").doesNotContainKey("body");
    assertThat(ledger.get("bodyChars")).isEqualTo("很长的正文".repeat(100).length());
  }

  // ---------- 夹具 ----------

  private ToolResult call(String tool, Map<String, Object> args, AgentPermissionSet permissions) {
    ToolContext context =
        new ToolContext(
            permissions.grantedToken(),
            permissions,
            Map.of(),
            args,
            io.mosire.agentlib.permission.AgentIdentity.UNKNOWN);
    return authorizer.execute(registry, tool, context);
  }

  /** 调用者技能两个面（其余维取 system：白名单通配、破坏性放行——把变量收敛到技能这一维）。 */
  private static AgentPermissionSet scopes(ResourceScope visible, ResourceScope editable) {
    return SkillPermissions.withScopes(AgentPermissionSet.system(), visible, editable);
  }

  private void writeSkill(String name, String description, String body) throws IOException {
    Path dir = Files.createDirectories(root.resolve(name));
    Files.writeString(
        dir.resolve("SKILL.md"),
        "---\nname: " + name + "\ndescription: " + description + "\n---\n\n" + body + "\n");
  }

  private static Map<String, Object> json(String text) {
    try {
      return JSON.readValue(text, new TypeReference<Map<String, Object>>() {});
    } catch (IOException e) {
      throw new AssertionError("工具结果不是 JSON: " + text, e);
    }
  }
}
