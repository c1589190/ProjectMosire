package io.mosire.bash;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * bash 工具的五项配置（{@code tools.bash.*}）：追加硬拒条目 / 追加需审批条目 / 命令落账口径（S4-C）+ 沙箱开关 / 沙箱只读面（S5-D）。
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
    List<String> blockedExtra,
    List<String> askExtra,
    CommandLog commandLog,
    Sandbox sandbox,
    List<Path> sandboxReadOnly) {

  /** 配置树里的段（{@code tools.bash}）。 */
  public static final String SECTION = "tools.bash";

  /** 追加硬拒条目的配置键（**只能加**）。 */
  public static final String KEY_BLOCKED_EXTRA = SECTION + ".blockedExtra";

  /** 追加需审批条目的配置键（**只能加**）。 */
  public static final String KEY_ASK_EXTRA = SECTION + ".askExtra";

  /** 命令落账口径的配置键（{@code digest} | {@code full}）。 */
  public static final String KEY_COMMAND_LOG = SECTION + ".commandLog";

  /** 沙箱开关的配置键（{@code off} | {@code require}）。<b>受限调用者一律沙箱，本键管不着它们</b>（见 {@link Sandbox}）。 */
  public static final String KEY_SANDBOX = SECTION + ".sandbox";

  /** 沙箱只读面（系统可见目录）的配置键。 */
  public static final String KEY_SANDBOX_READ_ONLY = SECTION + ".sandboxReadOnly";

  /** 缺省只读面（与 {@link BashSandbox#DEFAULT_READ_ONLY} 同一份常量——两处写两份就会分叉）。 */
  public static final List<Path> DEFAULT_SANDBOX_READ_ONLY = BashSandbox.DEFAULT_READ_ONLY;

  /**
   * 沙箱开关（闭词表）。
   *
   * <p><b>{@code off} 管不着受限调用者</b>：fs 可达面受限的调用者<b>一律</b>要沙箱，配成 {@code off} 也一样（裁决 ③）——
   * 否则"子体被限制在某个目录"这句话在 {@code off} 下就成了参数面契约（L1/L2），而那<b>不是</b>围栏。沙箱不可用时受限调用者得到 {@code
   * SANDBOX_UNAVAILABLE}，<b>绝不</b>静默退回无围栏执行。
   *
   * <p>{@code require} 把<b>不限</b> scope 的调用者一起沙箱化：它没有声明过可达面，取工具基准工作目录当围栏面（见 {@code ShellTool} 的组装）。
   */
  public enum Sandbox {
    /** 缺省：不限 scope 的调用者直接跑（L1/L2 也不参与——它没有可达面），受限调用者照样沙箱。 */
    OFF,
    /** 连不限 scope 的调用者一起沙箱化。 */
    REQUIRE;

    /** 配置里的写法（小写闭词表）。 */
    public String wireName() {
      return name().toLowerCase(Locale.ROOT);
    }

    /** 解析配置值；不认识的值返回 {@code null}（由调用方响亮失败，不静默回退缺省）。 */
    public static Sandbox parse(String raw) {
      if (raw == null) {
        return null;
      }
      for (Sandbox value : values()) {
        if (value.wireName().equals(raw.strip())) {
          return value;
        }
      }
      return null;
    }
  }

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
    sandbox = sandbox == null ? Sandbox.OFF : sandbox;
    // 单条 List.copyOf 包住整个分支（不是三元里挑一条再 copy）：SpotBugs 的不可变性分析要能直接看见
    // "记录分量恒来自 copyOf"——经三元间接赋值会被它判成 EI_EXPOSE_REP（AgentPermissionSet 同款先例）
    sandboxReadOnly =
        List.copyOf(
            sandboxReadOnly == null || sandboxReadOnly.isEmpty()
                ? DEFAULT_SANDBOX_READ_ONLY
                : sandboxReadOnly);
  }

  /** S4-C 的三项形态（S5-D 之前就在用的调用点不必改）：沙箱取 {@code off} + 缺省只读面。 */
  public BashToolConfig(List<String> blockedExtra, List<String> askExtra, CommandLog commandLog) {
    this(blockedExtra, askExtra, commandLog, Sandbox.OFF, DEFAULT_SANDBOX_READ_ONLY);
  }

  /** 缺省：无追加条目 + {@code digest} + 沙箱 {@code off}（不配 {@code tools.bash.*} 的部署就是这个形态）。 */
  public static BashToolConfig defaults() {
    return new BashToolConfig(List.of(), List.of(), CommandLog.DIGEST);
  }

  /** 注入 {@code ToolContext.config} 的键值（装配层用；键名就是配置里的完整点分路径）。 */
  public Map<String, Object> toToolConfig() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put(KEY_BLOCKED_EXTRA, blockedExtra);
    out.put(KEY_ASK_EXTRA, askExtra);
    out.put(KEY_COMMAND_LOG, commandLog.wireName());
    out.put(KEY_SANDBOX, sandbox.wireName());
    out.put(KEY_SANDBOX_READ_ONLY, sandboxReadOnly.stream().map(Path::toString).toList());
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
        commandLog(config.get(KEY_COMMAND_LOG)),
        sandbox(config.get(KEY_SANDBOX)),
        readOnlyDirs(config.get(KEY_SANDBOX_READ_ONLY)));
  }

  private static Sandbox sandbox(Object raw) {
    if (raw == null) {
      return Sandbox.OFF;
    }
    Sandbox parsed = Sandbox.parse(String.valueOf(raw));
    if (parsed == null) {
      throw new IllegalArgumentException(KEY_SANDBOX + " 只接受 off|require，实际: " + raw);
    }
    return parsed;
  }

  private static List<Path> readOnlyDirs(Object raw) {
    if (raw == null) {
      return DEFAULT_SANDBOX_READ_ONLY;
    }
    if (!(raw instanceof Collection<?> collection)) {
      throw new IllegalArgumentException(
          KEY_SANDBOX_READ_ONLY + " 必须是字符串数组，实际: " + raw.getClass().getName());
    }
    List<Path> out = new ArrayList<>(collection.size());
    for (Object item : collection) {
      out.add(readOnlyDir(String.valueOf(item)));
    }
    return List.copyOf(out);
  }

  /**
   * 一条只读面条目：绝对路径、非 {@code /}、不含 {@code .}/{@code ..} 段。
   *
   * <p>三条都必须在配置面挡死（而不是留给沙箱运行时）：<b>相对路径</b>在沙箱里会指向新根下的同名位置（绑定源却按宿主解释，语义分裂）； <b>{@code /}</b>
   * 把宿主根整体绑进沙箱，等于取消目录隐藏面；<b>{@code .}/{@code ..}</b> 让"配的路径"与"绑的路径"不是同一个字符串，
   * 审计对不上。相对路径本可拒绝得更早，但这里必须显式判（{@code Path.of} 不会替我们拒绝）。
   */
  static Path readOnlyDir(String raw) {
    String trimmed = raw == null ? "" : raw.strip();
    for (String segment : trimmed.split("/")) {
      if (segment.equals(".") || segment.equals("..")) {
        throw new IllegalArgumentException(KEY_SANDBOX_READ_ONLY + " 不接受含 . / .. 的路径: " + raw);
      }
    }
    Path path = Path.of(trimmed);
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException(KEY_SANDBOX_READ_ONLY + " 必须是绝对路径: " + raw);
    }
    Path normalized = path.normalize();
    if (normalized.getParent() == null) {
      throw new IllegalArgumentException(KEY_SANDBOX_READ_ONLY + " 不接受 /（把宿主根整体绑进沙箱等于取消目录隐藏面）");
    }
    return normalized;
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
