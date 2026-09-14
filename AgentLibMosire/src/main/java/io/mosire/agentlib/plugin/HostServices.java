package io.mosire.agentlib.plugin;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.config.ConfigStore;
import java.util.Optional;

/**
 * 装载期注入给插件的<b>宿主服务面</b>（当前最小集：只有配置读）——插件据此解析自己的配置节、在 {@link ToolSource#init} 里做<b>装载期校验</b>。
 *
 * <p><b>为什么是"装载期注入"而不是别的通道</b>（设计 {@code 设计-插件系统与bash插件化.md} §D5 的两条理由，记在案）：
 *
 * <ol>
 *   <li><b>装载期校验不回退</b>：本仓既有红线是"配置项存在但值非法 ⇒ <b>启动</b>响亮失败"（{@code BashToolConfigLoader} / {@code
 *       SubagentLimitsLoader} 同一口径）。若把 store 塞进 {@code ToolContext.config}，配置只能在<b>第一次调用</b>时解析 ⇒
 *       坏配置变成"跑到那一步才炸"，是一次<b>静默降级</b>；
 *   <li><b>配置是插件级的事实，不是调用级的</b>：塞进调用上下文会让每个工具<b>每次调用</b>都重解析一遍，且"谁在什么时候读配置"变得不可判。
 * </ol>
 *
 * <p><b>能力边界（照实记）</b>：{@link ConfigStore} 是 AgentLib 既有接口，本类型<b>不新增任何读权限</b>——插件与宿主同进程同权限（红线 4
 * "信任边界 = 进程边界"），多给一个接口句柄不增加实际能力。但代价照实写：插件因此读得到配置里的全部键（含 {@code
 * llm.routes.*.apiKey}），也能尝试写配置（{@code put} 按传入的 {@code AgentPermissionSet} 判授权）。这是"进程内插件 =
 * 可信代码"的推论， <b>不是新泄漏</b>；"插件目录只放自审代码"这条运维前提不因此放松。
 *
 * <p><b>{@link #config()} 可能为 null</b>：未装配（{@link #none()}、或旧宿主经 {@code PluginToolSource} 的 3
 * 参构造器）时就是 null。<b>插件不得假设它非空</b>——要么响亮失败、要么退化到缺省并在日志里说明（bash 插件选后者）。要按 Optional 处理请用 {@link
 * #configOpt()}。
 *
 * <p>为什么记录类型而不是接口：宿主服务面<b>只会增字段、不会多实现</b>，且"注入什么"应当一眼可见（一个 record 的构造式就是全部服务面的清单）。
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "ConfigStore 本体按设计 §D5 交给插件（宿主与插件同进程同权限——红线 4，接口句柄不新增实际能力；"
            + "插件据此在装载期解析自己的配置节）。防御性拷贝既不可能（接口类型）也无意义（要共享的就是宿主那一份实时配置），属设计意图而非泄漏。")
public record HostServices(ConfigStore config) {

  /**
   * 未装配形态：{@code config() == null}。
   *
   * <p>3 参构造的 {@link PluginToolSource}（离线单测、旧宿主）用的就是它；插件拿到这个实例时<b>不该</b>假设配置可用。
   */
  public static HostServices none() {
    return new HostServices(null);
  }

  /** 配置存储；{@code null} = 未装配（见类注释"config() 可能为 null"）。 */
  @Override
  public ConfigStore config() {
    return config;
  }

  /** {@link #config()} 的 Optional 形态（未装配 = 空），供不想在插件里判 null 的调用方使用。 */
  public Optional<ConfigStore> configOpt() {
    return Optional.ofNullable(config);
  }
}
