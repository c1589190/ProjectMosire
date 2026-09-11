package io.mosire.agentlib.permission;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 配置键前缀授权（纯函数，红线 1 的第二个实现点）：判定"某身份 + 某归属者"能否写某配置键。
 *
 * <p>与 {@link PermissionChecker} 同包且同构（纯函数、无 I/O 无状态、拒绝时返回可读中文原因），但维度 <b>正交</b>：{@link
 * PermissionChecker#denialReason} 判<b>工具名</b>（白名单/三要素），本类判<b>配置 键前缀</b>。二者互不改语义。编排（拒绝 → 发 {@code
 * permission.denied} 事件 → 抛异常）归调用方； {@code ConfigStore.put} 在入口调用本判定并抛 {@code
 * ConfigException(E_PREFIX_DENIED)}。
 *
 * <p>规则（deny-by-default，与 {@link PermissionChecker} 的白名单口径一致）：
 *
 * <ol>
 *   <li>{@code runtime.*} / {@code keys.*}：仅 {@link AccessToken#SYSTEM} 可写；
 *   <li>{@code agents.<id>.*}：要求身份 ≥ {@link AccessToken#DEFAULT}（GUEST 拒绝）且 {@code id equals
 *       ownerId}；{@link AccessToken#SYSTEM} 例外放行——运行时自身可管理任意 Agent 配置，是"身份级别足够"的上界；
 *   <li>其余前缀 / 不合规键（空键、空段、{@code .}/{@code ..} 段、非法 id 字符）：默认拒绝。
 * </ol>
 *
 * <p><b>消息安全</b>：拒绝原因<b>不回显未经校验的调用方字符串</b>（凭据可能以键/归属者形态出现—— T18 评审 Minor 3 升级条件的同源约束）：键全文、不合规的 id
 * 段、{@code ownerId} 原文都不进消息； 只有<b>通过字符集校验的 id</b>（{@code [A-Za-z0-9][A-Za-z0-9_-]*}，构造不出 URL）会被引用。
 *
 * <p><b>{@code ownerId} 是调用方自报的归属身份</b>：本函数只做"键归属 ≟ 自报归属"的比对，不验证调用者 真实身份（该绑定是上层——工具调用上下文——的职责，与
 * {@link AccessToken} 本身是受信输入同理）。
 */
public final class ConfigAuth {

  /** Agent id 的合法字符集（文件名安全、构造不出 URL）：字母数字开头，后续可含字母/数字/{@code _}/{@code -}。 */
  private static final Pattern AGENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

  private ConfigAuth() {}

  /**
   * 写入判定；允许返回空，拒绝返回可读中文原因（可进 {@code PERMISSION_DENIED} 事件的 reason， 与 {@link
   * PermissionChecker#denialReason} 同风格）。
   *
   * @param token 调用者身份级别（null 视为无身份 → 拒绝）
   * @param ownerId 调用者自报的归属 agent id（非 Agent 调用方如运行时自身为 null）
   * @param key 全量点分键（如 {@code agents.bob.temperature}）
   */
  public static Optional<String> writeDenialReason(AccessToken token, String ownerId, String key) {
    if (token == null) {
      return Optional.of("无身份令牌（权限集为空）");
    }
    if (key == null || key.isBlank()) {
      return Optional.of("配置键为空");
    }
    if (key.startsWith("keys.") || key.startsWith("runtime.")) {
      String rest = key.substring(key.indexOf('.') + 1);
      if (!validSegments(rest)) {
        return Optional.of("配置键段不合法（不得为空、. 或 ..）");
      }
      if (!token.atLeast(AccessToken.SYSTEM)) {
        return Optional.of(key.substring(0, key.indexOf('.')) + ".* 仅 SYSTEM 可写，调用者为 " + token);
      }
      return Optional.empty();
    }
    if (key.startsWith("agents.")) {
      String body = key.substring("agents.".length());
      int dot = body.indexOf('.');
      if (dot <= 0 || dot == body.length() - 1) {
        return Optional.of("agents.* 键必须形如 agents.<id>.<key>");
      }
      String id = body.substring(0, dot);
      String rest = body.substring(dot + 1);
      if (!AGENT_ID.matcher(id).matches()) {
        return Optional.of("agents.* 的 id 段不合法（仅允许字母/数字开头，后续字母/数字/_/-）");
      }
      if (!validSegments(rest)) {
        return Optional.of("agents.* 的键段不合法（不得为空、. 或 ..）");
      }
      if (token.atLeast(AccessToken.SYSTEM)) {
        return Optional.empty(); // 运行时自身可管理任意 Agent 配置
      }
      if (!token.atLeast(AccessToken.DEFAULT)) {
        return Optional.of("身份级别不足：写 agents.<id>.* 至少需要 DEFAULT，调用者为 " + token);
      }
      if (!id.equals(ownerId)) {
        return Optional.of("键归属 agent「" + id + "」与调用者自报归属不一致");
      }
      return Optional.empty();
    }
    return Optional.of("键前缀不在可写范围（deny-by-default：仅 agents.<id>.* / runtime.* / keys.* 可写）");
  }

  /**
   * 从全量点分键解析 agent id：形如 {@code agents.<id>.<rest>} 且 id 字符集合法、rest 键段合法 → id；否则空（用于决定落盘位置：agent 文件
   * vs 全局文件——与授权判定用同一解析）。
   */
  public static Optional<String> agentIdOf(String key) {
    if (key == null || !key.startsWith("agents.")) {
      return Optional.empty();
    }
    String body = key.substring("agents.".length());
    int dot = body.indexOf('.');
    if (dot <= 0 || dot == body.length() - 1) {
      return Optional.empty();
    }
    String id = body.substring(0, dot);
    String rest = body.substring(dot + 1);
    if (!AGENT_ID.matcher(id).matches() || !validSegments(rest)) {
      return Optional.empty();
    }
    return Optional.of(id);
  }

  private static boolean validSegments(String rest) {
    if (rest.isEmpty() || rest.startsWith(".") || rest.endsWith(".") || rest.contains("..")) {
      return false;
    }
    return true;
  }
}
