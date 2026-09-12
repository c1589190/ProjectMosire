package io.mosire.brain.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * bash 工具的三项配置（{@code tools.bash.*}，S4-C）：追加硬拒条目 / 追加需审批条目 / 命令落账口径。
 *
 * <p><b>配置只能往里加，删不掉内置条目（结构性）</b>：内置清单在 {@link BashCommandClassifier} 里是 {@code private static
 * final} 常量，本记录只承载两个 <b>Extra</b> 列表——装配层把它们的值交给分类器时是 {@code addAll} 进副本（见 {@code
 * BashCommandClassifier.classify} 的形参），<b>没有任何形参能覆盖/清空内置清单</b>。
 * 因此"关掉安全阀"不是一行配置能做到的事：要删条目必须改分类器源码（那是代码评审的事，不是配置的事）。
 *
 * <p><b>第三项 {@code commandLog} 是脱敏开关</b>：{@code digest}（缺省）= {@code tool.call} 事件只落 {@code
 * {digest,len,class}}；{@code full} = 原样落 {@code args}（含命令原文）。缺省口径是"不留明文"， 要逐字回放命令请显式改配置——{@code
 * digest} 只是<b>减少暴露面，不是保密</b>：短命令可被字典攻击还原。
 *
 * <p><b>键名与注入路径</b>：键是完整点分路径（{@code tools.bash.blockedExtra} 等），写在部署的 {@code config.json}
 * 里、由装配层（{@code App}）经 {@link BashToolConfigLoader} 读出，再塞进 {@code ToolContext.config} （D30
 * 的运行时缝：工具自身配置只经 {@code ToolContext.config} 注入，工具实现不反向索取）。本记录既是"配置的形态"， 也是"注入到 {@code
 * ToolContext.config} 的那几个键"的唯一定义处。
 */
public record BashToolConfig(
    List<String> blockedExtra, List<String> askExtra, CommandLog commandLog) {

  /** 配置树里的段（{@code tools.bash}）。 */
  public static final String SECTION = "tools.bash";

  /** 追加硬拒条目的配置键（**只能加**）。 */
  public static final String KEY_BLOCKED_EXTRA = SECTION + ".blockedExtra";

  /** 追加需审批条目的配置键（**只能加**）。 */
  public static final String KEY_ASK_EXTRA = SECTION + ".askExtra";

  /** 命令落账口径的配置键（{@code digest} | {@code full}）。 */
  public static final String KEY_COMMAND_LOG = SECTION + ".commandLog";

  /** 命令落账口径（闭词表；非法值在启动期响亮失败，见 {@link BashToolConfigLoader}）。 */
  public enum CommandLog {
    /** 缺省：事件里只落 {@code {digest,len,class}}——命令明文<b>永不</b>进事件库。 */
    DIGEST,
    /** 显式开关：事件里落原始 {@code args}（含命令原文）。 */
    FULL;

    /** 配置里的写法（小写闭词表）。 */
    public String wireName() {
      return name().toLowerCase(Locale.ROOT);
    }

    /** 解析配置值；不认识的值返回 {@code null}（由调用方响亮失败，不静默回退缺省）。 */
    public static CommandLog parse(String raw) {
      if (raw == null) {
        return null;
      }
      for (CommandLog value : values()) {
        if (value.wireName().equals(raw.strip())) {
          return value;
        }
      }
      return null;
    }
  }

  public BashToolConfig {
    blockedExtra = List.copyOf(blockedExtra == null ? List.of() : blockedExtra);
    askExtra = List.copyOf(askExtra == null ? List.of() : askExtra);
    commandLog = commandLog == null ? CommandLog.DIGEST : commandLog;
  }

  /** 缺省：无追加条目 + {@code digest}（不配 {@code tools.bash.*} 的部署就是这个形态）。 */
  public static BashToolConfig defaults() {
    return new BashToolConfig(List.of(), List.of(), CommandLog.DIGEST);
  }

  /** 注入 {@code ToolContext.config} 的键值（装配层用；键名就是配置里的完整点分路径）。 */
  public Map<String, Object> toToolConfig() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put(KEY_BLOCKED_EXTRA, blockedExtra);
    out.put(KEY_ASK_EXTRA, askExtra);
    out.put(KEY_COMMAND_LOG, commandLog.wireName());
    return Map.copyOf(out);
  }

  /**
   * 从 {@code ToolContext.config} 读回（工具侧用）：<b>缺省兜底</b>——没有这几个键（离线单测、注入面没接）时按 {@link #defaults()}。
   *
   * <p>类型不对一律<b>抛</b>（不静默忽略）：能走到这里说明装配层把错的类型注入了，静默忽略会得到一个"看起来配了、 其实没生效"的部署——那正是配置类缺陷里最难查的一种。生产装配由
   * {@link BashToolConfigLoader} 在启动期挡在前面。
   */
  public static BashToolConfig fromToolConfig(Map<String, Object> config) {
    if (config == null || config.isEmpty()) {
      return defaults();
    }
    return new BashToolConfig(
        strings(config.get(KEY_BLOCKED_EXTRA), KEY_BLOCKED_EXTRA),
        strings(config.get(KEY_ASK_EXTRA), KEY_ASK_EXTRA),
        commandLog(config.get(KEY_COMMAND_LOG)));
  }

  private static List<String> strings(Object raw, String key) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof Collection<?> collection)) {
      throw new IllegalArgumentException(key + " 必须是字符串数组，实际: " + raw.getClass().getName());
    }
    List<String> out = new ArrayList<>(collection.size());
    for (Object item : collection) {
      out.add(String.valueOf(item));
    }
    return List.copyOf(out);
  }

  private static CommandLog commandLog(Object raw) {
    if (raw == null) {
      return CommandLog.DIGEST;
    }
    CommandLog parsed = CommandLog.parse(String.valueOf(raw));
    if (parsed == null) {
      throw new IllegalArgumentException(KEY_COMMAND_LOG + " 只接受 digest|full，实际: " + raw);
    }
    return parsed;
  }
}
