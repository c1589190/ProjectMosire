package io.mosire.main.app;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.ConfigStore;
import io.mosire.agentlib.permission.AccessToken;
import java.util.Locale;
import java.util.Objects;

/**
 * 外部 MCP 面的<b>调用者令牌级别</b>（配置 {@code agent.externalMcp.token}；2026-10-16 用户裁定）。
 *
 * <p><b>用户原话</b>：「我想了一下还是GUEST吧，然后搞个配置文件，用户想配置System就配置System，System等级留着有用」/ 「如上，做可配置」——即：<b>缺省仍是
 * {@code GUEST}</b>（既有行为逐字不变），部署方可以自己把这个面的身份级别提上去。
 *
 * <p><b>为什么需要这个键</b>：外部 MCP 面（{@code mcpExpose=true} 时那条 stdio 暴露面）的调用者上下文写死了 {@code
 * AccessToken.GUEST}，而生产工具逐个把级别提到 {@code ToolSpec.level(AccessToken.DEFAULT, …)} ⇒ 该面**一个工具都执行不了**
 * （实测：19 个工具 0 个可通过，全部卡在 {@code PermissionChecker} 的"身份级别不足"那道闸）。这不是安全洞（fail-closed），
 * 但它让这个面事实上成了摆设；而"要不要让它能用"是<b>部署方的选择</b>——本机自用的单用户部署与对外暴露的部署答案相反，所以做成配置。
 *
 * <p><b>读法与失败语义</b>（照 {@code AgentRoles#load} 的既有口径）：
 *
 * <ul>
 *   <li>整项缺失 ⇒ {@link AccessToken#GUEST}（保守缺省，"没配"读作"不动"）；
 *   <li>项存在但值非法（不是文本 / 不是三个取值之一）⇒ <b>启动期响亮失败</b>（{@link #E_EXTERNAL_MCP_TOKEN}）—— 静默回落会让人以为"配了
 *       DEFAULT 却没生效"，而配置面上看不出区别；
 *   <li>取值<b>大小写不敏感</b>（{@code default}/{@code Default} 都认），但空白与未知词一律拒。
 * </ul>
 *
 * <p><b>这个键改的是什么、不改什么</b>：它只改"以什么身份调用工具"（令牌级别）。它<b>不改</b>该面的可见面口径——可见面按 A-6 与判定面同源（同一个 {@code
 * PermissionChecker.allows} 谓词），令牌提上去之后可见面自然跟着放开；把它当"开关"用也不会 关掉这个面（那是 {@code --no-mcp-expose} 的事）。
 */
public final class AgentExternalMcpToken {

  /** 配置段名（与 {@code agent.roles} 同族前缀）。 */
  public static final String SECTION = "agent";

  /** 子段名（{@code agent.externalMcp}）。 */
  public static final String SUB_SECTION = "externalMcp";

  /** 段内的键名。 */
  public static final String KEY = "token";

  /** 全量点分键（写进消息与配置文档用）。 */
  public static final String FULL_KEY = SECTION + "." + SUB_SECTION + "." + KEY;

  /** {@code agent.externalMcp.token} 项存在但值非法（类型/取值）。消息只含键名与原值（本项不是密钥）。 */
  public static final String E_EXTERNAL_MCP_TOKEN = "E_EXTERNAL_MCP_TOKEN";

  private AgentExternalMcpToken() {}

  /**
   * 读外部 MCP 面的令牌级别。
   *
   * @param store 配置存储（本进程配置根上的实例）
   * @return 缺省 {@link AccessToken#GUEST}；显式配置则取配置值
   * @throws ConfigException {@code E_EXTERNAL_MCP_TOKEN}（项存在但类型/取值非法）
   */
  public static AccessToken load(ConfigStore store) {
    Objects.requireNonNull(store, "store");
    // 读法与 AgentRoles#valueOf 同源：store.get(prefix, key 点分可嵌套)，三层都没有 ⇒ 空
    JsonNode value = store.get(SECTION, SUB_SECTION + "." + KEY).orElse(null);
    if (value == null || value.isNull()) {
      return AccessToken.GUEST;
    }
    if (!value.isTextual()) {
      throw invalid("必须是文本（GUEST / DEFAULT / SYSTEM 之一）", value.toString());
    }
    String text = value.asText().strip();
    for (AccessToken candidate : AccessToken.values()) {
      if (candidate.name().equals(text.toUpperCase(Locale.ROOT))) {
        return candidate;
      }
    }
    throw invalid("取值只能是 GUEST / DEFAULT / SYSTEM（大小写不敏感）", value.toString());
  }

  /** 非法取值的响亮失败（消息带上全量键名与原值；本项不是密钥 ⇒ 原值可以进消息）。 */
  private static ConfigException invalid(String why, String actual) {
    return new ConfigException(E_EXTERNAL_MCP_TOKEN, FULL_KEY + " 非法：" + why + "，实际=" + actual);
  }
}
