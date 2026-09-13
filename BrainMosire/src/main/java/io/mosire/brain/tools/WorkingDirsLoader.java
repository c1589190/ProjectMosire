package io.mosire.brain.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.ConfigStore;
import io.mosire.agentlib.permission.ResourceScope;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 把配置里的 {@code agents.workingDirs} 读成主 Agent 的 {@code fs} 作用域（S5-B；读法照 {@code BashToolConfigLoader}
 * / {@code SubagentLimitsLoader}）。
 *
 * <p><b>整项缺失 ⇒ 不限</b>（不是错误，也不是"哪里都不许"）：这是裁决 ① 的口径——主 Agent 缺省不限， 此后逐跳只减不增。JSON {@code null} 与缺失同口径。
 *
 * <p><b>给了空数组 ⇒ 哪里都不许</b>（{@link ResourceScope#none()}）。与 {@code allowedTools: []} = "一个工具都不给"同形同义：
 * 显式写的空集是"不给"，不是"没写"。想表达"不限"就<b>别写这一项</b>——两种意图各有各的写法，不存在"看创建者心情"的中间态。
 *
 * <p><b>项存在但值非法 ⇒ 启动期响亮失败</b>（{@link #E_WORKING_DIRS}，消息点名键与原值）：把 {@code workingDirs} 写成字符串、
 * 元素写成数字、或路径里含 {@code ..} 段时静默回退成"不限"，会得到一个<b>看起来围住了、其实敞着</b>的部署—— 而这一项正是目录围栏的唯一来源。相对路径按<b>本进程
 * cwd</b> 绝对化（守护进程的 cwd，不是子进程的）。
 *
 * <p><b>本键读得动、写不进去</b>：{@code ConfigAuth} 的 {@code agents.*} 语法是 {@code agents.<id>.<key>}（三段）， 两段的
 * {@code agents.workingDirs} 会被它判为"agent id = workingDirs"的不合法键 ⇒ 经 HTTP 配置面的写入一律 {@code
 * E_PREFIX_DENIED}。生效途径只有配置文件/环境层，这是<b>已知并接受</b>的形状约束（账本记档）。
 */
public final class WorkingDirsLoader {

  /** {@code agents.workingDirs} 项存在但值非法（类型/取值）。消息只含键名与原值（本项无密钥）。 */
  public static final String E_WORKING_DIRS = "E_WORKING_DIRS";

  /** 键所在的段（{@code ConfigStore.get} 收段名 + 段内键；{@code agents} 是既有族名）。 */
  public static final String SECTION = "agents";

  /** 段内的键名。 */
  public static final String KEY = "workingDirs";

  /** 全量点分键（写进消息与配置文档用）。 */
  public static final String FULL_KEY = SECTION + "." + KEY;

  private WorkingDirsLoader() {}

  /**
   * 读主 Agent 的 {@code fs} 作用域。
   *
   * @param store 配置存储（本进程配置根上的实例，如 {@code new FileConfigStore(dataDir)}）
   * @throws ConfigException {@code E_WORKING_DIRS}（项存在但类型/取值非法，或路径无法归一）
   */
  public static ResourceScope load(ConfigStore store) {
    Objects.requireNonNull(store, "store");
    JsonNode value = valueOf(store);
    if (value == null) {
      return ResourceScope.unlimited(); // 裁决 ①：缺省不限
    }
    if (!value.isArray()) {
      throw invalid("必须是目录路径的字符串数组（要'不限'请删掉这一项）", value.toString());
    }
    List<String> texts = new ArrayList<>();
    for (JsonNode item : value) {
      if (!item.isTextual() || item.asText().isBlank()) {
        throw invalid("数组元素必须是绝对或相对的目录路径文本", value.toString());
      }
      texts.add(item.asText().strip());
    }
    if (texts.isEmpty()) {
      return ResourceScope.none(); // 显式空数组 = 哪里都不许（与 allowedTools: [] 同口径）
    }
    try {
      return ResourceScope.ofDirs(texts.stream().map(Path::of).toList());
    } catch (IllegalArgumentException | UncheckedIOException e) {
      // 形态非法（如含 NUL 的路径：InvalidPathException 是 IllegalArgumentException 的子类）/
      // 归一化失败（空前缀 / realpath 取不到）一律点名键与原值，不把裸异常抛给启动期
      throw invalid("目录路径不可用: " + e.getMessage(), value.toString());
    }
  }

  /** 取键值：缺失与 JSON null 同口径（返回 null = 用缺省）。 */
  private static JsonNode valueOf(ConfigStore store) {
    Optional<JsonNode> found = store.get(SECTION, KEY);
    JsonNode node = found.orElse(null);
    return node == null || node.isNull() ? null : node;
  }

  private static ConfigException invalid(String reason, String rawValue) {
    return new ConfigException(
        E_WORKING_DIRS, "工作目录配置不可用：" + FULL_KEY + " " + reason + "（实际值: " + rawValue + "）");
  }
}
