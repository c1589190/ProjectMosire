package io.mosire.brain.permission;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.ConfigStore;
import io.mosire.agentlib.permission.RoleSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Agent 角色维（设计 §4.5 / §4.3.1）的<b>读入与文本线</b>：主 Agent 的角色从配置 {@code agent.roles} 读，子 Agent 的角色经 argv
 * 文本线下发给子进程。
 *
 * <p><b>两种读法，方向都是收紧</b>：
 *
 * <ul>
 *   <li>{@link #load(ConfigStore)}：配置读入（装配期，{@code Main} 调用）。<b>整项缺失 ⇒ {@link
 *       RoleSet#none()}</b>——调用者侧的角色维 fail-closed（"我是什么"没有回落这一说，设计 §4.3）；项存在但值非法（不是数组 / 元素不是角色名文本 /
 *       空白名）⇒ <b>启动期响亮失败</b>（{@link #E_AGENT_ROLES}）——静默收成空集会让"漏配"看起来像"这人没有角色"，而两者在配置面上长得一模一样；
 *   <li>{@link #encode} / {@link #decode}：argv 文本线的一对逆运算（旗标 {@code --roles}，写侧见 {@code
 *       AgentCommand#argv}，读侧见 {@code SubagentProcessMain}）。<b>旗标缺席 / 空白 ⇒ {@link
 *       RoleSet#none()}</b>（fail-closed，不是"不限"）； 子体拿到的角色恒等于父侧算出的生效值（{@code 调用者 ∩ 模板 ∩ 请求}），逐跳只减不增。
 * </ul>
 *
 * <p><b>文本线形状</b>：逗号连接的升序角色名（就是 {@link RoleSet#summary()} 的形态）。角色名对通用层是不透明标签，但逗号是这条线的分隔符 ⇒
 * 含逗号的角色名在这里<b>响亮拒绝</b>（{@link #requireWireSafe}）：静默切分会把 {@code {"a,b"}} 变成 {@code
 * {"a","b"}}——子体拿到父级没有的角色， 正面撞红线 1。拒绝落在三个入口（配置读入、模板装载、spawn
 * 请求构造），于是"这个名字送不到"在出生点就响亮，而不是在子进程里悄悄换了身份。
 *
 * <p><b>角色名不是密钥</b>（设计 §4.3.1），所以它可以进 argv；但本类<b>不打印、不记事件</b>：角色名不进日志/事件面是本责任区的明文面纪律，身份细节由装订期审计承担。
 */
public final class AgentRoles {

  /** 配置段名（配置键 {@code agent.roles}；注意是单数 {@code agent}，与 {@code agents.<id>.*} 族不同前缀）。 */
  public static final String SECTION = "agent";

  /** 段内的键名。 */
  public static final String KEY = "roles";

  /** 全量点分键（写进消息与配置文档用）。 */
  public static final String FULL_KEY = SECTION + "." + KEY;

  /** {@code agent.roles} 项存在但值非法（类型/取值）。消息只含键名与原值（本项无密钥）。 */
  public static final String E_AGENT_ROLES = "E_AGENT_ROLES";

  /** argv 文本线的分隔符（与 {@link RoleSet#summary()} 的形态同源——文本线只有这一份口径）。 */
  public static final String DELIMITER = ",";

  private AgentRoles() {}

  /**
   * 读主 Agent 的角色（配置 {@code agent.roles}，角色名的字符串数组）。
   *
   * <p><b>缺省 ⇒ ⊥（谁都不是）</b>：没配角色的部署拿不到角色门工具，而不是把它们全开（设计 §4.3 的 fail-closed）。显式空数组与缺省同值——这一维
   * <b>没有"不限"这个取值</b>（那是资源面的层叠语义，身份没有回落）。
   *
   * @param store 配置存储（本进程配置根上的实例，如 {@code new FileConfigStore(dataDir)}）
   * @throws ConfigException {@code E_AGENT_ROLES}（项存在但类型/取值非法）
   */
  public static RoleSet load(ConfigStore store) {
    Objects.requireNonNull(store, "store");
    JsonNode value = valueOf(store);
    if (value == null) {
      return RoleSet.none();
    }
    if (!value.isArray()) {
      throw invalid("必须是角色名的字符串数组（没有角色就别写这一项）", value.toString());
    }
    Set<String> names = new LinkedHashSet<>();
    for (JsonNode item : value) {
      if (!item.isTextual() || item.asText().isBlank()) {
        throw invalid("数组元素必须是角色名文本", value.toString());
      }
      names.add(item.asText());
    }
    if (names.isEmpty()) {
      return RoleSet.none(); // 显式空数组 = 谁都不是（与缺省同值）
    }
    try {
      return requireWireSafe(RoleSet.of(names.toArray(String[]::new)));
    } catch (IllegalArgumentException e) {
      throw invalid("角色名不可用: " + e.getMessage(), value.toString());
    }
  }

  /**
   * 角色集合 → argv 文本线的值（逗号连接的升序名）。
   *
   * @throws IllegalArgumentException 角色名含分隔符（见 {@link #requireWireSafe}）
   */
  public static String encode(RoleSet roles) {
    return requireWireSafe(roles).summary();
  }

  /**
   * argv 文本线 → 角色集合：{@code null} / 空白 ⇒ ⊥（fail-closed，不是"不限"）；否则按分隔符切分，逐名走 {@link RoleSet#of}
   * 的同一口径（去首尾空白、去重；空白名响亮失败，不静默丢掉那一项）。
   */
  public static RoleSet decode(String text) {
    if (text == null || text.isBlank()) {
      return RoleSet.none();
    }
    return RoleSet.of(text.split(DELIMITER, -1));
  }

  /**
   * 角色名必须能<b>无损</b>落到文本线上：含分隔符 ⇒ 响亮拒绝（返回入参本身，便于串联）。
   *
   * <p><b>为什么不是"切分后各算各的"</b>：父级角色 {@code {"a,b"}} 经这条线会被切成 {@code
   * {"a","b"}}，子体因此拿到父级没有的角色——权限单调性（红线 1）在这一维当场破，且没有任何征兆。宁可让含分隔符的名字在配置 / 模板 / 请求三处入口就失败（"拼错的角色 =
   * 永远不可达的工具"，设计 §4.6 的同一理由），也不让它悄悄变成别的角色。
   */
  public static RoleSet requireWireSafe(RoleSet roles) {
    Objects.requireNonNull(roles, "roles");
    for (String role : roles.roles()) {
      if (role.contains(DELIMITER)) {
        throw new IllegalArgumentException(
            "角色名不得含 '"
                + DELIMITER
                + "'（它是角色 argv 文本线的分隔符；含分隔符的名字无法无损下发，"
                + "静默切分会让子体拿到父级没有的角色——红线 1）");
      }
    }
    return roles;
  }

  /** 取键值：缺失与 JSON null 同口径（返回 null = 用缺省 ⊥）。 */
  private static JsonNode valueOf(ConfigStore store) {
    Optional<JsonNode> found = store.get(SECTION, KEY);
    JsonNode node = found.orElse(null);
    return node == null || node.isNull() ? null : node;
  }

  private static ConfigException invalid(String reason, String rawValue) {
    return new ConfigException(
        E_AGENT_ROLES, "角色配置不可用：" + FULL_KEY + " " + reason + "（实际值: " + rawValue + "）");
  }
}
