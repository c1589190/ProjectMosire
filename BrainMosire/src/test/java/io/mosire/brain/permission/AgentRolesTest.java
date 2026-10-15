package io.mosire.brain.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.FileConfigStore;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.brain.subagent.AgentTemplate;
import io.mosire.brain.subagent.SubagentLaunchRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 角色维的<b>读入面与 argv 文本线</b>（设计 §4.5，RA2）：配置 {@code agent.roles} 的读法、{@code encode}/{@code decode}
 * 这对逆运算、 以及"含逗号的名字在三个入口响亮失败"。
 *
 * <p><b>两个方向的 fail-closed</b>：①<b>缺省 ⇒ ⊥</b>（"我是什么"没有回落这一说，"漏配"与"这人没有角色"在配置面上长得一模一样，所以
 * <b>缺省读作⊥、非法读作启动失败</b>）；②<b>含分隔符的名字 ⇒ 响亮拒绝</b>——静默切分会把 {@code {"a,b"}} 变成 {@code {"a","b"}}，
 * 子体因此拿到父级没有的角色，正面撞红线 1（权限单调性）。
 *
 * <p><b>判别性（变异自证）</b>：把 {@code load} 的"项存在但非法 ⇒ 抛"改成"静默收成空集" ⇒ {@link
 * #loadRejectsNonArrayNonTextualBlankAndCommaNames} 的每一条断言转红；把 {@code decode(null)} 改成 {@code
 * RoleSet.of(...)} 之外的"不限"语义（本类型没有"不限"取值，改法只能是抛或给具体集合）⇒ {@link #decodeTreatsNullAndBlankAsBottom}
 * 转红。
 */
class AgentRolesTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path configRoot;

  // ────────────────────────── 文本线：encode / decode ──────────────────────────

  /** 往返：{@code decode(encode(x)) == x}，且文本形态是<b>逗号连接的升序名</b>（对外文本必须稳定，与 RoleSet.summary 同源）。 */
  @Test
  void encodeDecodeRoundTripKeepsTheSetAndSortsTheText() {
    RoleSet roles = RoleSet.of("gm", "DM", "ops");

    String encoded = AgentRoles.encode(roles);

    assertThat(encoded).isEqualTo("DM,gm,ops");
    assertThat(AgentRoles.decode(encoded).roles()).isEqualTo(roles.roles());
    assertThat(RoleSet.of("gm").summary()).isEqualTo(AgentRoles.encode(RoleSet.of("gm")));
  }

  /** ⊥ 的往返：{@code encode(⊥) = ""}，{@code decode("") = ⊥}——空串不是"不限"。 */
  @Test
  void bottomRoundTripsThroughTheEmptyString() {
    assertThat(AgentRoles.encode(RoleSet.none())).isEmpty();
    assertThat(AgentRoles.decode(AgentRoles.encode(RoleSet.none())).roles()).isEmpty();
  }

  /** {@code decode(null)} / 全空白 ⇒ ⊥（旗标缺席的读法，fail-closed）；两侧空白被归一。 */
  @Test
  void decodeTreatsNullAndBlankAsBottom() {
    assertThat(AgentRoles.decode(null).roles()).isEmpty();
    assertThat(AgentRoles.decode("").roles()).isEmpty();
    assertThat(AgentRoles.decode("   ").roles()).isEmpty();
    assertThat(AgentRoles.decode(" gm , dm ").roles())
        .as("逐名去首尾空白（与 RoleSet.of 同一口径）")
        .containsExactlyInAnyOrder("gm", "dm");
    assertThat(AgentRoles.decode("gm,gm").roles()).as("重复名去重").containsExactly("gm");
  }

  /** 含分隔符的名字无法无损下发 ⇒ {@code encode} 当场响亮失败（不静默切分）。 */
  @Test
  void encodeRejectsACommaContainingNameLoudly() {
    assertThatThrownBy(() -> AgentRoles.encode(RoleSet.of("a,b")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得含");
  }

  // ────────────────────────── 配置读入：agent.roles ──────────────────────────

  /** 缺省 / JSON null / 显式空数组 ⇒ ⊥（三者同值；这一维<b>没有</b>"不限"这个取值）。 */
  @Test
  void missingNullAndEmptyConfigAllReadAsBottom() throws IOException {
    assertThat(AgentRoles.load(store())).isEqualTo(RoleSet.none());

    write("\"roles\": null");
    assertThat(AgentRoles.load(store())).isEqualTo(RoleSet.none());

    write("\"roles\": []");
    assertThat(AgentRoles.load(store())).isEqualTo(RoleSet.none());
  }

  /** 正常读入：数组、去重复、去空白，结果按集合语义。 */
  @Test
  void aTextArrayIsReadAndNormalized() throws IOException {
    write("\"roles\": [\" gm \", \"dm\", \"gm\"]");

    assertThat(AgentRoles.load(store()).roles()).containsExactlyInAnyOrder("gm", "dm");
  }

  /**
   * ★ 项存在但值非法 ⇒ {@code E_AGENT_ROLES} <b>启动期响亮失败</b>（不是静默收成空集）：非数组 / 元素非文本 / 空白名 / 含逗号名。
   *
   * <p>为什么"漏配"与"写错"必须可区分：两者若都读成 ⊥，一个拼错的名字会让角色门工具静默失效——正是设计 §4.6 说的"漂移无症状"。
   */
  @Test
  void loadRejectsNonArrayNonTextualBlankAndCommaNames() throws IOException {
    for (String value :
        List.of(
            "\"gm\"", // 非数组
            "[\"gm\", 5]", // 元素非文本
            "[\"gm\", \"  \"]", // 空白名
            "[\"a,b\"]", // 含分隔符（文本线的分隔符）
            "{\"gm\": true}")) { // 对象
      write("\"roles\": " + value);
      assertThatThrownBy(() -> AgentRoles.load(store()))
          .as("agent.roles = %s 必须响亮失败", value)
          .isInstanceOf(ConfigException.class)
          .hasMessageContaining(AgentRoles.E_AGENT_ROLES)
          .hasMessageContaining(AgentRoles.FULL_KEY);
    }
  }

  /**
   * ★ 反向对照：判定面可写的 {@code agents.main.roles} <b>不是</b>角色维的读入口——写进去了，{@code AgentRoles.load} 仍然读 ⊥。
   *
   * <p>这条把"Agent 不能给自己提权"钉死在<b>读侧</b>：即使调用者经合法授权（DEFAULT + 归属匹配）写了一个叫 roles 的键， 它也不会变成身份。角色只从
   * {@code agent.roles}（运维面：配置文件 / env 覆盖层）来。
   */
  @Test
  void writableAgentsPrefixIsNotARoleSideDoor() throws IOException {
    FileConfigStore store = store();
    store.put(
        "agents.main",
        "roles",
        JSON.readTree("[\"gm\"]"),
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build(),
        "main",
        JSON.readTree("{\"type\":\"object\"}"));

    assertThat(store.get("agents.main", "roles")).isPresent();
    assertThat(AgentRoles.load(store))
        .as("写 agents.main.roles 成功 ≠ 身份被改成 gm：读侧只认 agent.roles")
        .isEqualTo(RoleSet.none());
  }

  /** env 覆盖层（运维面）写的 {@code agent.roles} 能读到——读入口是"配置文件 + env 覆盖层"，与本条不矛盾。 */
  @Test
  void envOverlayOfAgentRolesIsHonored() throws IOException {
    FileConfigStore store =
        new FileConfigStore(
            configRoot,
            configRoot.resolve("schemas"),
            java.util.Map.of("agent.roles", "[\"gm\"]"),
            io.mosire.agentlib.config.ConfigListener.none());

    assertThat(AgentRoles.load(store).roles()).containsExactly("gm");
  }

  // ────────────────────────── 三处入口：含逗号 ⇒ 响亮失败 ──────────────────────────

  /** 入口②：模板装载（构造期）——含逗号的名字在出生点失败，不流到 argv。 */
  @Test
  void templateConstructionRejectsACommaContainingRole() {
    assertThatThrownBy(() -> template(List.of("a,b")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得含");

    assertThat(template(List.of("gm")).roles()).containsExactly("gm");
    assertThat(template(null).roles()).isNull();
    assertThat(template(List.of()).declaredRoles()).isEqualTo(RoleSet.none());
  }

  /** 入口③：spawn 请求（构造期）——同一条校验、同一处实现（不另写一套口径）。 */
  @Test
  void requestConstructionRejectsACommaContainingRole() {
    assertThatThrownBy(() -> request(List.of("a,b")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得含");

    assertThatThrownBy(() -> request(List.of("  ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("非空白");
    assertThat(request(List.of("gm")).roles()).containsExactly("gm");
    assertThat(request(null).roles()).isNull();
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private FileConfigStore store() {
    return new FileConfigStore(configRoot);
  }

  /** 写 config.json 的 {@code agent} 段（{@code inner} 形如 {@code "roles": [...]}）。 */
  private void write(String inner) throws IOException {
    Files.createDirectories(configRoot);
    Files.writeString(
        configRoot.resolve("config.json"), "{\"agent\": {" + inner + "}}", StandardCharsets.UTF_8);
  }

  /** 21 参规范构造（roles 在最后）。 */
  private static AgentTemplate template(List<String> roles) {
    return new AgentTemplate(
        "reader",
        "测试模板",
        "你是只读子 Agent。",
        "fake",
        AccessToken.DEFAULT,
        Set.of("*"),
        Set.of(),
        false,
        false,
        false,
        10,
        5,
        60,
        0,
        List.of(),
        null,
        null,
        null,
        null,
        null,
        roles);
  }

  /** 14 参规范构造（roles 在最后）。 */
  private static SubagentLaunchRequest request(List<String> roles) {
    return new SubagentLaunchRequest(
        "reader", "目标", null, null, null, null, null, null, null, null, null, null, null, roles);
  }
}
