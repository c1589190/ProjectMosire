package io.mosire.agentlib.permission;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.ConfigStore;
import java.util.Objects;
import java.util.Optional;

/**
 * 把配置里的 {@code commands.mode} 读成主 Agent 的<b>初始档位</b>（读法照 {@code BashToolConfigLoader}——现 {@code
 * io.mosire.bash.BashToolConfigLoader}，随 bash 插件化搬入插件模块——/ {@code ApprovalConfigLoader}）。
 *
 * <p><b>整项缺失 ⇒ {@link CommandMode#FULL}</b>（不是错误）：没写过这一项的部署照常起来，行为等于本功能引入前。 JSON {@code null}
 * 与缺失同口径。
 *
 * <p><b>项存在但值非法 ⇒ 启动期响亮失败</b>：把 {@code "partial"} 静默读成某一档，就是一个"看起来配了、其实配错了"的部署—— 而这一项直接决定命令闸的宽严（读成
 * FULL 等于无声放开审批，读成 LIMITED 等于把主 Agent 每条命令都送去审批）， 两种都不是"猜错的代价"该付的。
 */
public final class CommandModeLoader {

  /** {@code commands.mode} 项存在但值非法（类型/取值）。消息只含键名与原值（本项不是密钥）。 */
  public static final String E_COMMAND_MODE = "E_COMMAND_MODE";

  /** 配置段名。 */
  public static final String SECTION = "commands";

  private static final String KEY_MODE = "mode";

  private CommandModeLoader() {}

  /**
   * 读主 Agent 的初始档位。
   *
   * @param store 配置存储（本进程配置根上的实例，如 {@code new FileConfigStore(dataDir)}）
   * @throws ConfigException {@code E_COMMAND_MODE}（项存在但类型/取值非法）
   */
  public static CommandMode load(ConfigStore store) {
    Objects.requireNonNull(store, "store");
    Optional<JsonNode> found = store.get(SECTION, KEY_MODE);
    JsonNode value = found.orElse(null);
    if (value == null || value.isNull()) {
      return CommandMode.FULL;
    }
    if (!value.isTextual() || CommandMode.parse(value.asText()) == null) {
      throw new ConfigException(
          E_COMMAND_MODE,
          "档位配置不可用：" + SECTION + "." + KEY_MODE + " 只接受 full | limited（实际值: " + value + "）");
    }
    return CommandMode.parse(value.asText());
  }
}
