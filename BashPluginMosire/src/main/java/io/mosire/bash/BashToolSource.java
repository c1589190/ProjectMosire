package io.mosire.bash;

import io.mosire.agentlib.config.ConfigStore;
import io.mosire.agentlib.plugin.HostServices;
import io.mosire.agentlib.plugin.ToolSource;
import io.mosire.agentlib.tool.AgentTool;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * bash 插件本体（{@code ToolSource} 扩展，PF4J {@code Plugin-Id=bash}）：把 {@link ShellTool} 作为本源唯一工具供给给宿主。
 *
 * <p><b>这是第一方参考实现</b>（设计 {@code 设计-插件系统与bash插件化.md} §四 的配方）：JAR 形态（{@code Plugin-Id}/{@code
 * Plugin-Version} + {@code @Extension} 生成的 {@code META-INF/extensions.idx}）、不打包宿主 API、一个插件恰好一个
 * {@code ToolSource}、配置在 {@link #init} 里装载并校验。
 *
 * <p><b>配置从哪来（两条路，一处实现）</b>：
 *
 * <ol>
 *   <li>{@link #init(HostServices)}（宿主装配路径）：{@code services.config()} 非空 ⇒ {@link
 *       BashToolConfigLoader#load} 读 {@code tools.bash.*}；<b>坏值在此响亮失败</b>（抛异常 ⇒ 装载失败 + 回滚 +
 *       启动响亮失败）——这正是 {@code init} 存在的理由，别把校验拖到第一次调用；
 *   <li>{@code config()} 为空（未装配的宿主 / 三参构造的 {@code PluginToolSource}）⇒ 退化到缺省口径 {@code
 *       BashToolConfig.fromToolConfig(Map.of())} 并记一条 warn：没装配服务面时读不到部署配置，那是<b>降级</b>，
 *       必须在日志里说出来（不许静默）。
 * </ol>
 *
 * <p>{@link #listTools()} 返回 {@link #init} 里建好的那个实例；{@code init} 还没被调过（直接构造本源的老宿主、离线单测）时
 * <b>惰性</b>按"无注入配置"口径建一次——{@code ShellTool} 的 {@code configOf} 在无注入时会现读 {@code
 * ToolContext.config}，与搬家前的行为逐字相同（设计 §五.1 行为不变的那条）。
 *
 * <p><b>不订阅</b>：工具集是静态的（一个 {@code ShellTool}，配置在装载期定型），故 {@link #onChange} 返回 no-op 句柄； 配置变更后要生效 =
 * 重启宿主或 {@code disable → enable}（后者会重新调 {@code init} 重新读配置）。{@link #close()} 亦为空实现
 * （本源不持有线程/句柄/子进程——命令进程的生命周期归 {@link ShellTool} 自己收尾）。
 *
 * <p><b>供给源 id = {@value #ID}</b>：与 PF4J 的 {@code Plugin-Id} 同名但是两件东西（一个是插件身份、一个是工具归属源），都取 {@code
 * bash}；不等于保留名 {@code builtin}（冒用会被 {@code PluginToolSource} 拒载）。
 */
@Extension
public final class BashToolSource implements ToolSource {

  private static final Logger LOG = LoggerFactory.getLogger(BashToolSource.class);

  /** 供给源 id（工具归属/审计/按源卸载用；与 PF4J 的 {@code Plugin-Id} 同名）。 */
  public static final String ID = "bash";

  private final Object lock = new Object();

  /** 装载期建好的工具实例；{@code null} = {@link #init} 尚未被调（老宿主/直接构造），见 {@link #listTools()}。 */
  private ShellTool tool;

  /** PF4J 的扩展实例化入口（无参构造）：配置等宿主服务面由 {@link #init} 后续注入，故这里不读任何配置。 */
  public BashToolSource() {}

  /**
   * 装载期注入：读并校验 {@code tools.bash.*}，把 {@link ShellTool} 实例建好。
   *
   * @param services 宿主服务面（非 null；{@code config()} 可能为 null = 未装配）
   * @throws RuntimeException 配置项存在但值非法（{@link BashToolConfigLoader} 的响亮失败）——该插件装载失败并回滚
   */
  @Override
  public void init(HostServices services) {
    Objects.requireNonNull(services, "services");
    ConfigStore store = services.config();
    BashToolConfig config;
    if (store == null) {
      LOG.warn(
          "未装配 HostServices（配置存储为空），bash 用缺省配置：tools.bash.* 不被读取（要读请让宿主经 HostServices 注入 ConfigStore）");
      config = BashToolConfig.fromToolConfig(Map.of());
    } else {
      config = BashToolConfigLoader.load(store); // 坏值在此响亮失败（不拖到第一次调用）
    }
    synchronized (lock) {
      tool = new ShellTool(config);
    }
    LOG.info(
        "bash 插件已装载：commandLog={} sandbox={}",
        config.commandLog().wireName(),
        config.sandbox().wireName());
  }

  @Override
  public String id() {
    return ID;
  }

  /**
   * 本源唯一工具（{@link ShellTool} 实例，装载期定型，多次调用返回同一个）。
   *
   * <p>{@code init} 未被调过时惰性建一次缺省实例（直接构造本源的老宿主、离线单测）：那条路上 {@code ShellTool} 会现读 {@code
   * ToolContext.config}，正是搬家前的既有口径——"直接构造"与"经插件装载"两种用法的行为由 {@code ShellTool.configOf} 一处决定。
   */
  @Override
  public List<AgentTool> listTools() {
    synchronized (lock) {
      if (tool == null) {
        tool = new ShellTool();
      }
      return List.of(tool);
    }
  }

  /** 工具集静态（一个 {@code ShellTool}，配置在装载期定型）：订阅是 no-op。 */
  @Override
  public AutoCloseable onChange(Runnable listener) {
    Objects.requireNonNull(listener, "listener");
    return () -> {};
  }

  /** 无持有资源（不持线程/句柄/进程——命令进程由 {@link ShellTool} 自己收尾）：空实现，但本方法<b>会</b>被宿主调用。 */
  @Override
  public void close() {}
}
