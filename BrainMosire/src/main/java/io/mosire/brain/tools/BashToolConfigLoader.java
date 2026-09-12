package io.mosire.brain.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.ConfigStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 把配置里的 {@code tools.bash.*} 读成一份 {@link BashToolConfig}（读法照 {@code LlmRouteLoader} / {@code
 * ApprovalConfigLoader}）：{@code blockedExtra} / {@code askExtra} / {@code commandLog}。
 *
 * <p><b>整项缺失 ⇒ 缺省值</b>（不是错误）：没写过任何 {@code tools.bash.*} 的部署照常起来（无追加条目 + {@code digest}）。 JSON
 * {@code null} 与缺失同口径。
 *
 * <p><b>项存在但值非法 ⇒ 启动期响亮失败</b>（{@link #E_BASH_CONFIG}，消息点名是哪个键）：把 {@code commandLog} 写成 {@code
 * "false"}、或把 {@code blockedExtra} 写成字符串而不是数组时，静默回退缺省会得到一个"看起来配了、其实没生效"的部署——
 * 而这两个键恰好是安全相关的（一个决定命令是否落明文，一个决定哪些命令被硬拒）。
 *
 * <p><b>本类只做"读"，不做"合并"</b>：内置清单在 {@link BashCommandClassifier} 里，本类读到的只是两个<b>追加</b>列表；
 * 这里<b>没有</b>任何"删除/覆盖内置条目"的键——结构性（见 {@link BashToolConfig} 的类 javadoc）。
 */
public final class BashToolConfigLoader {

  /** {@code tools.bash.*} 项存在但值非法（类型/取值）。消息只含键名与（文本键的）原值。 */
  public static final String E_BASH_CONFIG = "E_BASH_CONFIG";

  private static final String KEY_BLOCKED_EXTRA = "blockedExtra";
  private static final String KEY_ASK_EXTRA = "askExtra";
  private static final String KEY_COMMAND_LOG = "commandLog";

  private BashToolConfigLoader() {}

  /**
   * 读一份 bash 工具配置。
   *
   * @param store 配置存储（本进程配置根上的实例，如 {@code new FileConfigStore(dataDir)}）
   * @throws ConfigException {@code E_BASH_CONFIG}（项存在但类型/取值非法）
   */
  public static BashToolConfig load(ConfigStore store) {
    Objects.requireNonNull(store, "store");
    return new BashToolConfig(
        names(store, KEY_BLOCKED_EXTRA), names(store, KEY_ASK_EXTRA), commandLog(store));
  }

  /** 追加条目：缺失 ⇒ 空表；存在但非数组或含非文本元素 ⇒ 响亮。 */
  private static List<String> names(ConfigStore store, String key) {
    JsonNode value = valueOf(store, key);
    if (value == null) {
      return List.of();
    }
    if (!value.isArray()) {
      throw invalid(key, "必须是字符串数组", value.toString());
    }
    List<String> out = new ArrayList<>();
    for (JsonNode item : value) {
      if (!item.isTextual() || item.asText().isBlank()) {
        throw invalid(key, "数组元素必须是非空白文本（可执行名）", value.toString());
      }
      out.add(item.asText().strip());
    }
    return List.copyOf(out);
  }

  /** 落账口径：缺失 ⇒ {@code digest}；存在但非文本或取值不在闭词表 ⇒ 响亮。 */
  private static BashToolConfig.CommandLog commandLog(ConfigStore store) {
    JsonNode value = valueOf(store, KEY_COMMAND_LOG);
    if (value == null) {
      return BashToolConfig.CommandLog.DIGEST;
    }
    if (!value.isTextual()) {
      throw invalid(KEY_COMMAND_LOG, "必须是文本（digest | full）", value.toString());
    }
    BashToolConfig.CommandLog parsed = BashToolConfig.CommandLog.parse(value.asText());
    if (parsed == null) {
      throw invalid(KEY_COMMAND_LOG, "只接受 digest | full", value.asText());
    }
    return parsed;
  }

  /** 取键值：缺失与 JSON null 同口径（返回 null = 用缺省）。 */
  private static JsonNode valueOf(ConfigStore store, String key) {
    Optional<JsonNode> value = store.get(BashToolConfig.SECTION, key);
    JsonNode node = value.orElse(null);
    return node == null || node.isNull() ? null : node;
  }

  /** 非法值一律同一个错误码（"哪一项配错了"由消息点名，不靠码区分）。消息不含任何密钥（本段配置里也没有密钥）。 */
  private static ConfigException invalid(String key, String reason, String rawValue) {
    return new ConfigException(
        E_BASH_CONFIG,
        "bash 工具配置不可用："
            + BashToolConfig.SECTION
            + "."
            + key
            + " "
            + reason
            + "（实际值: "
            + rawValue
            + "）");
  }
}
