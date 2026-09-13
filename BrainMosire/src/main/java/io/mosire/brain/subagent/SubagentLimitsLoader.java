package io.mosire.brain.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.ConfigStore;
import java.util.Objects;
import java.util.Optional;

/**
 * 把配置里的 {@code subagents.*} 读成一份 {@link SubagentLimits}（读法照 {@code BashToolConfigLoader} / {@code
 * CommandModeLoader}）。
 *
 * <p><b>整项缺失 ⇒ 缺省值</b>（不是错误）：没写过 {@code subagents.*} 的部署照常起来，拿到 {@link
 * SubagentLimits#defaults()}。JSON {@code null} 与缺失同口径。
 *
 * <p><b>项存在但值非法 ⇒ 启动期响亮失败</b>（{@link #E_SUBAGENT_LIMITS}，消息点名键与原值）：把 {@code maxDepth} 写成 {@code
 * "3"}（字符串）或 {@code 0} 时静默回退缺省，会得到一个"看起来配了、其实没生效"的部署——而这三个键决定
 * <b>能派多少</b>，读错方向的后果是预算形同虚设（写成字符串却按缺省办）或整个派生面被静默锁死（写成 0 却按缺省办）。 两种都不是"猜错的代价"该付的。
 *
 * <p>取值范围不在这里判（{@link SubagentLimits} 的构造期守卫是唯一真值）：本类只做"读 JSON → 交给记录"， 越界值同样以 {@link
 * ConfigException} 形式响亮失败（包一层是为了给"哪个键"这个信息）。
 */
public final class SubagentLimitsLoader {

  /** {@code subagents.*} 项存在但值非法（类型/取值）。消息只含键名与原值（本段配置无密钥）。 */
  public static final String E_SUBAGENT_LIMITS = "E_SUBAGENT_LIMITS";

  private SubagentLimitsLoader() {}

  /**
   * 读一份繁殖预算。
   *
   * @param store 配置存储（本进程配置根上的实例，如 {@code new FileConfigStore(dataDir)}）
   * @throws ConfigException {@code E_SUBAGENT_LIMITS}（项存在但类型/取值非法）
   */
  public static SubagentLimits load(ConfigStore store) {
    Objects.requireNonNull(store, "store");
    SubagentLimits defaults = SubagentLimits.defaults();
    int maxDepth = positiveInt(store, SubagentLimits.KEY_MAX_DEPTH, defaults.maxDepth());
    int maxChildren =
        positiveInt(store, SubagentLimits.KEY_MAX_CHILDREN, defaults.maxChildrenPerInstance());
    int maxInstances =
        positiveInt(store, SubagentLimits.KEY_MAX_INSTANCES, defaults.maxInstances());
    return new SubagentLimits(maxDepth, maxChildren, maxInstances);
  }

  /** 正整数项：缺失 ⇒ {@code fallback}；存在但非整数 / 非正 ⇒ 响亮。 */
  private static int positiveInt(ConfigStore store, String key, int fallback) {
    Optional<JsonNode> found = store.get(SubagentLimits.SECTION, shortName(key));
    JsonNode value = found.orElse(null);
    if (value == null || value.isNull()) {
      return fallback;
    }
    if (!value.isIntegralNumber()) {
      throw invalid(key, "必须是整数", value.toString());
    }
    int parsed = value.asInt();
    if (parsed < 1) {
      throw invalid(key, "必须是正整数（≥ 1）", value.toString());
    }
    return parsed;
  }

  /** {@code subagents.maxDepth} → {@code maxDepth}（{@code ConfigStore.get} 收段名 + 段内键）。 */
  private static String shortName(String fullKey) {
    int dot = fullKey.indexOf('.');
    return dot < 0 ? fullKey : fullKey.substring(dot + 1);
  }

  /** 非法值一律同一个错误码（"哪一项配错了"由消息点名，不靠码区分）。消息不含任何密钥。 */
  private static ConfigException invalid(String key, String reason, String rawValue) {
    return new ConfigException(
        E_SUBAGENT_LIMITS, "繁殖预算配置不可用：" + key + " " + reason + "（实际值: " + rawValue + "）");
  }
}
