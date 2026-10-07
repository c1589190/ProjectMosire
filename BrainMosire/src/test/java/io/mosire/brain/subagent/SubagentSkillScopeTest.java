package io.mosire.brain.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.runtime.EventTypes;
import io.mosire.brain.skills.SkillPermissions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 技能范围在 spawn 链上的传播契约（全离线）：
 *
 * <ul>
 *   <li><b>逐层求交</b>：子体生效面 = 调用者 ∩ 模板建议 ∩ 请求，且可编辑面恒被夹进可见面；
 *   <li><b>请求面是要求</b>：超出调用者可达面 ⇒ {@link SkillScopeDeniedException}（{@code SKILL_SCOPE_DENIED}）+
 *       {@code permission.denied} 事件，不静默截断；
 *   <li><b>多级只减不增</b>：孙代以子体权限集为对照物——子体给不出的，孙代也拿不到；
 *   <li><b>未表态不放大</b>：调用者没配技能面时，模板写了技能范围也不会凭空给子体。
 * </ul>
 */
class SubagentSkillScopeTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private SqliteEventStore store;
  private EventBus bus;
  private Path agentsDir;
  private Path skillsRoot;

  @BeforeEach
  void setUp() throws IOException {
    agentsDir = Files.createDirectories(tempDir.resolve("agents"));
    skillsRoot = Files.createDirectories(tempDir.resolve("skills"));
    Files.createDirectories(skillsRoot.resolve("alpha"));
    Files.createDirectories(skillsRoot.resolve("beta"));
    store = SqliteEventStore.open(tempDir.resolve("events.db"));
    bus = new EventBus();
  }

  @AfterEach
  void tearDown() {
    bus.close();
    store.close();
  }

  @Test
  void childScopesAreTheIntersectionOfCallerTemplateAndRequest() throws IOException {
    write(template("skilled", List.of(skillsRoot.resolve("alpha").toString()), null));
    SubagentManager manager =
        manager(callerWithSkills(ResourceScope.ofDir(skillsRoot), ResourceScope.ofDir(skillsRoot)));

    SubagentInstance child =
        manager.spawn(
            new SubagentLaunchRequest("skilled", "目标", Set.of(), null, null, null),
            AgentIdentity.main(CommandMode.FULL),
            callerWithSkills(ResourceScope.ofDir(skillsRoot), ResourceScope.ofDir(skillsRoot)));

    ResourceScope visible = skillScopeOf(child, ResourceScopeMap.SKILLS);
    ResourceScope editable = skillScopeOf(child, ResourceScopeMap.SKILLS_EDIT);
    assertThat(visible.allowsDir(skillsRoot.resolve("alpha").resolve("SKILL.md"))).isTrue();
    assertThat(visible.allowsDir(skillsRoot.resolve("beta").resolve("SKILL.md")))
        .as("模板没给 beta ⇒ 求交掉")
        .isFalse();
    assertThat(editable.allowsDir(skillsRoot.resolve("alpha").resolve("SKILL.md"))).isTrue();
    assertThat(editable.allowsDir(skillsRoot.resolve("beta").resolve("SKILL.md"))).isFalse();
    // 可编辑面恒 ⊆ 可见面
    assertThat(visible.covers(editable)).isTrue();
  }

  @Test
  void requestBeyondCallerIsRejectedLoudlyWithDedicatedEvent() throws IOException {
    write(template("skilled", null, null));
    AgentPermissionSet caller =
        callerWithSkills(ResourceScope.ofDir(skillsRoot.resolve("alpha")), ResourceScope.none());
    SubagentManager manager = manager(caller);

    SkillScopeDeniedException denied =
        catchThrowableOfType(
            () ->
                manager.spawn(
                    new SubagentLaunchRequest(
                        "skilled",
                        "目标",
                        Set.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(skillsRoot.resolve("beta").toString()),
                        null),
                    AgentIdentity.main(CommandMode.FULL),
                    caller),
            SkillScopeDeniedException.class);

    assertThat(denied.getMessage()).contains("SKILL_SCOPE_DENIED").contains("beta");
    List<Event> events = store.query(new EventQuery("", EventTypes.PERMISSION_DENIED, "", -1, 50));
    assertThat(events).isNotEmpty();
    assertThat(payloadOf(events.get(events.size() - 1))).containsEntry("face", "allowedSkills");
  }

  @Test
  void grandchildCanOnlyTightenFurther() throws IOException {
    write(template("skilled", null, null));
    AgentPermissionSet root =
        callerWithSkills(ResourceScope.ofDir(skillsRoot), ResourceScope.ofDir(skillsRoot));
    SubagentManager manager = manager(root);

    // 第一跳：调用者把子体收窄到 alpha
    SubagentInstance child =
        manager.spawn(
            new SubagentLaunchRequest(
                "skilled",
                "子目标",
                Set.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(skillsRoot.resolve("alpha").toString()),
                null),
            AgentIdentity.main(CommandMode.FULL),
            root);
    AgentPermissionSet childPermissions = child.permissions();

    // 第二跳：子体（作为调用者）想把孙代放到 beta ⇒ 拒（它自己都没有 beta）
    SkillScopeDeniedException denied =
        catchThrowableOfType(
            () ->
                manager.spawn(
                    new SubagentLaunchRequest(
                        "skilled",
                        "孙目标",
                        Set.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(skillsRoot.resolve("beta").toString()),
                        null),
                    AgentIdentity.subagent(child.instanceId(), CommandMode.LIMITED, "子目标", 1),
                    childPermissions),
            SkillScopeDeniedException.class);
    assertThat(denied.getMessage()).contains("beta");

    // 不加范围：孙代继承子体的 alpha（只减不增的“不增”一侧）
    SubagentInstance grandchild =
        manager.spawn(
            new SubagentLaunchRequest("skilled", "孙目标", Set.of(), null, null, null),
            AgentIdentity.subagent(child.instanceId(), CommandMode.LIMITED, "子目标", 1),
            childPermissions);
    assertThat(
            skillScopeOf(grandchild, ResourceScopeMap.SKILLS)
                .allowsDir(skillsRoot.resolve("alpha").resolve("SKILL.md")))
        .isTrue();
    assertThat(
            skillScopeOf(grandchild, ResourceScopeMap.SKILLS)
                .allowsDir(skillsRoot.resolve("beta").resolve("SKILL.md")))
        .isFalse();
  }

  /** 调用者没表态技能面 ⇒ 子体拿到 none（不是“不限”）；模板写了范围也救不回来（交集为空）。 */
  @Test
  void undeclaredCallerYieldsNoneEvenWhenTemplateDeclaresSkills() throws IOException {
    write(template("skilled", List.of(skillsRoot.toString()), List.of(skillsRoot.toString())));
    SubagentManager manager = manager(AgentPermissionSet.system());

    SubagentInstance child =
        manager.spawn(
            new SubagentLaunchRequest("skilled", "目标", Set.of(), null, null, null),
            AgentIdentity.main(CommandMode.FULL),
            AgentPermissionSet.system());

    assertThat(skillScopeOf(child, ResourceScopeMap.SKILLS)).isEqualTo(ResourceScope.none());
    assertThat(skillScopeOf(child, ResourceScopeMap.SKILLS_EDIT)).isEqualTo(ResourceScope.none());
  }

  /** argv 传递面：有表态才追加，值就是实例权限集里那一份（父级收紧过的）。 */
  @Test
  void agentCommandCarriesSkillScopesFromInstancePermissions() throws IOException {
    write(template("skilled", null, null));
    SubagentManager manager =
        manager(callerWithSkills(ResourceScope.ofDir(skillsRoot), ResourceScope.ofDir(skillsRoot)));
    SubagentInstance child =
        manager.spawn(
            new SubagentLaunchRequest("skilled", "目标", Set.of(), null, null, null),
            AgentIdentity.main(CommandMode.FULL),
            callerWithSkills(ResourceScope.ofDir(skillsRoot), ResourceScope.ofDir(skillsRoot)));

    AgentCommand command =
        new AgentCommand(
            "java",
            List.of(),
            List.of("-cp", "x", "Main", "agent"),
            "--id",
            null,
            null,
            true,
            null,
            skillsRoot.toString());
    List<String> argv = command.argv(child);
    assertThat(argv).containsSubsequence("--skills-dir", skillsRoot.toString());
    assertThat(argv).contains("--skill-visible");
    assertThat(argv).contains("--skill-editable");
    assertThat(argv).contains(skillsRoot.toAbsolutePath().normalize().toString());
  }

  /** 编排工具面：越界落独立错误码 {@code SKILL_SCOPE_DENIED}（与 {@code DIR_NOT_ALLOWED} 同一条理由）。 */
  @Test
  void spawnToolMapsSkillScopeDenialToItsOwnCode() throws IOException {
    write(template("skilled", null, null));
    AgentPermissionSet caller =
        callerWithSkills(ResourceScope.ofDir(skillsRoot.resolve("alpha")), ResourceScope.none());
    SubagentManager manager = manager(caller);
    ToolRegistry registry = new ToolRegistry();
    for (AgentTool tool : SubagentOrchestrationTools.of(manager)) {
      registry.register(tool);
    }
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("templateId", "skilled");
    args.put("goal", "目标");
    args.put("allowedSkills", List.of(skillsRoot.resolve("beta").toString()));
    ToolContext context =
        new ToolContext(
            AccessToken.DEFAULT, caller, Map.of(), args, AgentIdentity.main(CommandMode.FULL));

    ToolResult result =
        ToolCallAuthorizer.standard()
            .execute(registry, SubagentOrchestrationTools.SPAWN_SUB_AGENT, context);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("SKILL_SCOPE_DENIED");
    assertThat(result.message()).contains("beta");
  }

  // ---------- 夹具 ----------

  private SubagentManager manager(AgentPermissionSet callerPermissions) {
    AgentTemplateStore templateStore = new AgentTemplateStore(agentsDir);
    templateStore.load();
    return new SubagentManager(
        templateStore,
        new InProcessExecutor(),
        store,
        bus,
        AgentConfig.builder("main").build(),
        callerPermissions,
        0);
  }

  /** 调用者权限集：技能两个面按给定值，其余维取 system（把变量收敛到技能这一维）。 */
  private static AgentPermissionSet callerWithSkills(
      ResourceScope visible, ResourceScope editable) {
    return SkillPermissions.withScopes(AgentPermissionSet.system(), visible, editable);
  }

  private static ResourceScope skillScopeOf(SubagentInstance instance, String namespace) {
    ResourceScope scope = instance.permissions().resourceScopes().declaredScope(namespace);
    return scope == null ? ResourceScope.none() : scope;
  }

  private static AgentTemplate template(
      String id, List<String> allowedSkills, List<String> editableSkills) {
    return new AgentTemplate(
        id,
        "技能范围测试模板",
        "你是测试子 Agent。",
        "fake",
        AccessToken.DEFAULT,
        Set.of("skill_list"),
        Set.of(),
        true,
        false,
        false,
        2000,
        30,
        600,
        0,
        List.of(),
        null,
        allowedSkills,
        editableSkills);
  }

  private void write(AgentTemplate template) throws IOException {
    JSON.writeValue(agentsDir.resolve(template.id() + ".json").toFile(), template);
  }

  private static Map<String, Object> payloadOf(Event event) {
    try {
      return JSON.readValue(event.payload(), new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (IOException e) {
      throw new AssertionError("事件 payload 解析失败: " + event.payload(), e);
    }
  }
}
