package io.mosire.brain.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.agent.AgentConfig;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.PermissionChecker;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.brain.permission.AgentRoles;
import io.mosire.brain.runtime.EventTypes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * spawn 链上的角色单调性（设计 §4.5；不变量 A-1 / A-2）——<b>端到端走真 {@code SubagentManager.spawn}</b>，不是纯函数。
 *
 * <p><b>为什么必须在 Manager 这一层验</b>：{@code RoleSet.isSubset}/{@code covers} 的纯函数用例（RA1）证明不了"spawn
 * 真的调了它"—— 守卫没接线、接线接在错的锚点上（对 manager 的构造期常量而不是对调用者），纯函数用例一律照绿。这里的每一条都断言"派发结果" 与"事件面"。
 *
 * <p><b>三个来源的分工</b>（同资源面/技能面）：调用者 = 身份真值；模板 = 基线（越界<b>静默求交</b>）；请求 = 要求（越界<b>响亮拒绝</b>， 不静默截断）。 生效值
 * = 调用者 ∩ 模板 ∩ 请求，逐跳只减不增。
 *
 * <p><b>判别性（变异自证）</b>：把 {@code effectiveRoles} 的"请求越界 ⇒ 抛"换成"请求优先"（去掉守卫、直接取 requested） ⇒ {@link
 * #requestBeyondCallerIsRejectedWithPermissionDeniedAndNoInstance} 与 {@link
 * #requestBeyondCallerIsRejectedEvenWhenTheTemplateDeclaresIt} 双双转红。
 */
class SubagentRolesSpawnTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private SqliteEventStore store;
  private EventBus bus;
  private Path agentsDir;

  @BeforeEach
  void setUp() throws IOException {
    agentsDir = tempDir.resolve("agents");
    Files.createDirectories(agentsDir);
    store = SqliteEventStore.open(tempDir.resolve("events.db"));
    bus = new EventBus();
  }

  @AfterEach
  void tearDown() {
    bus.close();
    store.close();
  }

  // ────────────────────────── A-1：请求越界 ⇒ 拒 + 事件 + 不建实例 ──────────────────────────

  /**
   * ★ A-1 主判据：请求点名调用者没有的角色 ⇒ {@link SubagentRejectedException} + 一条 {@code permission.denied}、
   * <b>不建实例、不调 launcher</b>。
   *
   * <p>判别性（本轮变异靶 ①）：把 {@code effectiveRoles} 的越界守卫换成"请求优先"⇒ 这里不再抛，用例转红。
   */
  @Test
  void requestBeyondCallerIsRejectedWithPermissionDeniedAndNoInstance() {
    write(template("reader", null));
    RecordingLauncher launcher = new RecordingLauncher();
    AgentPermissionSet caller = AgentPermissionSet.system().withRoles(RoleSet.of("gm"));
    SubagentManager manager = manager(launcher, caller);

    assertThatThrownBy(() -> manager.spawn(request("reader", List.of("dm"))))
        .isInstanceOf(SubagentRejectedException.class)
        .hasMessageContaining("角色越界")
        .hasMessageContaining("单调性");

    assertThat(launcher.instances).as("越界请求在派发即拒：launcher 一次都不该被调到").isEmpty();
    assertThat(manager.list()).as("不建实例").isEmpty();
    assertThat(query(EventTypes.AGENT_LIFECYCLE)).as("连 configured 事件都不该有").isEmpty();

    List<Event> denied = query(EventTypes.PERMISSION_DENIED);
    assertThat(denied).hasSize(1);
    Event event = denied.get(0);
    assertThat(event.agent()).as("agent = 被拒的调用者（不是 manager 的构造期身份）").isEqualTo("main");
    assertThat(event.correlationId()).startsWith("reader");
    Map<String, Object> payload = payloadOf(event);
    assertThat(payload)
        .containsEntry("template", "reader")
        .satisfies(p -> assertThat(p.get("reason").toString()).contains("角色越界"));
    assertThat(event.payload())
        .as("★ 角色名不进事件面（明文面纪律：只说'这一类被拒'）")
        .doesNotContain("dm")
        .doesNotContain("gm");
  }

  /** A-1 的同一条判定：模板声明了这个角色也<b>不</b>构成请求的正当性——越界判据只看调用者。 */
  @Test
  void requestBeyondCallerIsRejectedEvenWhenTheTemplateDeclaresIt() {
    write(template("reader", List.of("gm", "dm")));
    RecordingLauncher launcher = new RecordingLauncher();
    SubagentManager manager =
        manager(launcher, AgentPermissionSet.system().withRoles(RoleSet.of("gm")));

    assertThatThrownBy(() -> manager.spawn(request("reader", List.of("dm"))))
        .isInstanceOf(SubagentRejectedException.class);

    assertThat(launcher.instances).isEmpty();
    assertThat(query(EventTypes.PERMISSION_DENIED)).hasSize(1);
  }

  /** A-1 的底：调用者 ⊥ ⇒ 请求点任何角色都越界（"父不表态、子自称管理员"的放大天然被封住）。 */
  @Test
  void bottomCallerCannotGrantAnyRequestedRole() {
    write(template("reader", null));
    RecordingLauncher launcher = new RecordingLauncher();
    SubagentManager manager = manager(launcher, AgentPermissionSet.system());

    assertThatThrownBy(() -> manager.spawn(request("reader", List.of("gm"))))
        .isInstanceOf(SubagentRejectedException.class);

    assertThat(launcher.instances).isEmpty();
  }

  // ────────────────────────── A-1：求交（模板越界静默、请求 ⊆ 调用者） ──────────────────────────

  /** 生效值 = 调用者 ∩ 模板 ∩ 请求；结果必须仍被调用者涵盖（{@code isSubset} 的独立复核，不只信 manager）。 */
  @Test
  void effectiveRolesIsTheIntersectionOfCallerTemplateAndRequest() {
    write(template("reader", List.of("gm", "dm")));
    RecordingLauncher launcher = new RecordingLauncher();
    AgentPermissionSet caller =
        AgentPermissionSet.system().withRoles(RoleSet.of("gm", "dm", "ops"));
    SubagentManager manager = manager(launcher, caller);

    SubagentInstance child = manager.spawn(request("reader", List.of("dm", "ops")));

    assertThat(child.permissions().roles().roles()).containsExactlyInAnyOrder("dm");
    assertThat(PermissionChecker.isSubset(child.permissions(), caller))
        .as("子体权限集必须仍 ⊆ 调用者（单调性守卫的独立复核）")
        .isTrue();
    assertThat(child.permissions().roles().covers(RoleSet.of("dm"))).isTrue();
  }

  /** 模板越界 ⇒ <b>静默求交</b>（模板是通用配置，被上级收紧是常态，不是错误）。 */
  @Test
  void templateRolesBeyondTheCallerAreSilentlyIntersected() {
    write(template("reader", List.of("gm", "ops")));
    RecordingLauncher launcher = new RecordingLauncher();
    SubagentManager manager =
        manager(launcher, AgentPermissionSet.system().withRoles(RoleSet.of("gm")));

    SubagentInstance child = manager.spawn(request("reader", null));

    assertThat(child.permissions().roles().roles()).containsExactly("gm");
    assertThat(query(EventTypes.PERMISSION_DENIED)).isEmpty();
  }

  /** 模板 {@code []} = 显式"谁都不是"（与"没写"是两种取值）：即使调用者与请求都有角色，子体仍 ⊥。 */
  @Test
  void emptyTemplateRolesMeansNobodyAndIsNotSilence() {
    write(template("reader", List.of()));
    SubagentManager manager =
        manager(new RecordingLauncher(), AgentPermissionSet.system().withRoles(RoleSet.of("gm")));

    SubagentInstance child = manager.spawn(request("reader", List.of("gm")));

    assertThat(child.permissions().roles().roles()).isEmpty();
  }

  /** 请求 {@code []} = 显式"谁都不是"（合法取值，不是越界）：不抛，子体 ⊥。 */
  @Test
  void emptyRequestedRolesMeansNobodyAndIsNotAnEscalation() {
    write(template("reader", List.of("gm")));
    SubagentManager manager =
        manager(
            new RecordingLauncher(), AgentPermissionSet.system().withRoles(RoleSet.of("gm", "dm")));

    SubagentInstance child = manager.spawn(request("reader", List.of()));

    assertThat(child.permissions().roles().roles()).isEmpty();
    assertThat(query(EventTypes.PERMISSION_DENIED)).isEmpty();
  }

  /** 请求/模板都不表态（{@code null}）⇒ 生效值逐字是调用者的角色（"不额外收窄"）。 */
  @Test
  void nullOnBothSidesLeavesTheCallerRolesUntouched() {
    write(template("reader", null));
    SubagentManager manager =
        manager(
            new RecordingLauncher(),
            AgentPermissionSet.system().withRoles(RoleSet.of("gm", "ops")));

    SubagentInstance child = manager.spawn(request("reader", null));

    assertThat(child.permissions().roles().roles()).containsExactlyInAnyOrder("gm", "ops");
  }

  // ────────────────────────── A-2：没配 agent.roles 的部署 ⇒ 子体仍 ⊥ ──────────────────────────

  /**
   * ★ A-2 的"整条链路"回归钉（设计 §4.3 的 fail-closed 落点）：<b>没配 {@code agent.roles} 的部署</b>，主 Agent 的角色由
   * {@link AgentRoles#load} 读出 ⊥ ⇒ spawn 出来的子体 roles 仍是 ⊥——既不"回落成不限"，也不因为模板/请求而白拿。
   */
  @Test
  void aDeploymentWithoutAgentRolesSpawnsChildrenThatStayBottom() throws IOException {
    write(template("reader", List.of("gm", "dm")));
    var configStore = new io.mosire.agentlib.config.FileConfigStore(tempDir.resolve("config"));
    assertThat(AgentRoles.load(configStore)).as("缺省配置 = ⊥（fail-closed）").isEqualTo(RoleSet.none());
    AgentPermissionSet caller = AgentPermissionSet.system().withRoles(AgentRoles.load(configStore));
    SubagentManager manager = manager(new RecordingLauncher(), caller);

    SubagentInstance child = manager.spawn(request("reader", null));

    assertThat(child.permissions().roles().roles()).isEmpty();
    assertThat(child.permissions().roles()).isEqualTo(RoleSet.none());
    assertThat(query(EventTypes.PERMISSION_DENIED)).isEmpty();
  }

  /** A-2 的另一半：调用者 ⊥、请求 {@code []}（显式谁都不是）⇒ 照常派发、子体 ⊥（不是错误路径）。 */
  @Test
  void bottomCallerWithExplicitEmptyRequestStillSpawns() {
    write(template("reader", null));
    SubagentManager manager = manager(new RecordingLauncher(), AgentPermissionSet.system());

    SubagentInstance child = manager.spawn(request("reader", List.of()));

    assertThat(child.permissions().roles().roles()).isEmpty();
  }

  // ────────────────────────── argv 文本线（写侧接线，同一份生效值） ──────────────────────────

  /**
   * 子进程通道拿到的<b>就是</b>父侧算出的生效值：{@code AgentCommand.argv} 的 {@code --roles} 值 decode 回来必须逐字相等； ⊥
   * 时<b>不追加旗标</b>（子进程侧缺省读法就是 ⊥，既有 argv 逐字不变）。
   */
  @Test
  void argvCarriesExactlyTheEffectiveRolesAndOmitsTheFlagWhenBottom() {
    write(template("reader", List.of("gm", "dm")));
    SubagentManager manager =
        manager(
            new RecordingLauncher(), AgentPermissionSet.system().withRoles(RoleSet.of("gm", "dm")));
    AgentCommand command = AgentCommand.javaJar("/usr/bin/java", "/opt/mosire/mosire.jar");

    SubagentInstance child = manager.spawn(request("reader", List.of("dm")));
    List<String> argv = command.argv(child);

    int flag = argv.indexOf("--roles");
    assertThat(flag).isGreaterThanOrEqualTo(0);
    assertThat(AgentRoles.decode(argv.get(flag + 1)))
        .as("argv 的 --roles 值 decode 回来 = 实例权限集里的生效角色")
        .isEqualTo(child.permissions().roles());
    assertThat(argv.get(flag + 1)).isEqualTo("dm");

    // ⊥ 侧：不追加旗标
    SubagentInstance bottomChild =
        manager(new RecordingLauncher(), AgentPermissionSet.system())
            .spawn(request("reader", null));
    assertThat(command.argv(bottomChild)).doesNotContain("--roles");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private SubagentManager manager(RecordingLauncher launcher, AgentPermissionSet caller) {
    AgentTemplateStore templateStore = new AgentTemplateStore(agentsDir);
    templateStore.load();
    return new SubagentManager(
        templateStore,
        launcher,
        store,
        bus,
        AgentConfig.builder("main").build(),
        caller,
        0,
        () -> CommandMode.FULL,
        SubagentLimits.defaults(),
        tempDir.resolve("subagents"));
  }

  private void write(AgentTemplate template) {
    try {
      JSON.writeValue(agentsDir.resolve(template.id() + ".json").toFile(), template);
    } catch (IOException e) {
      throw new UncheckedIOException("模板写入失败: " + template.id(), e);
    }
  }

  private List<Event> query(String type) {
    return new ArrayList<>(store.query(new EventQuery("", type, "", -1, 200)));
  }

  private static Map<String, Object> payloadOf(Event e) {
    try {
      return JSON.readValue(e.payload(), new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (Exception ex) {
      throw new AssertionError("事件 payload 解析失败: " + e.payload(), ex);
    }
  }

  /** 记录型 launcher：只记下被拉起的实例，不跑任何子体（"不建实例"的判据直接读它）。 */
  private static final class RecordingLauncher implements SubagentLauncher {

    private final List<SubagentInstance> instances = new ArrayList<>();

    @Override
    public LaunchedSubagent launch(SubagentInstance instance) {
      instances.add(instance);
      return new LaunchedSubagent() {
        @Override
        public boolean isAlive() {
          return true;
        }

        @Override
        public void close() {}
      };
    }

    @Override
    public void close() {}
  }

  /** 21 参规范构造：{@code roles == null} = 不额外收窄。 */
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

  /** 14 参规范构造：{@code roles == null} = 不指定。 */
  private static SubagentLaunchRequest request(String templateId, List<String> roles) {
    return new SubagentLaunchRequest(
        templateId, "目标", null, null, null, null, null, null, null, null, null, null, null, roles);
  }
}
