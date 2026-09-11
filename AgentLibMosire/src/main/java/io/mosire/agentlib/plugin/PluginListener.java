package io.mosire.agentlib.plugin;

/**
 * 插件生命周期监听（窄接口 SPI）：{@link PluginToolSource} 只调它，至于"事件怎么落库、怎么广播"由宿主决定。
 *
 * <p><b>为什么是接口而不是事件类型</b>：事件词汇表（{@code EventTypes}）与 {@code EventWrite}/{@code EventBus} 都在 Brain
 * （{@code io.mosire.brain.runtime}），AgentLib 不得反引（模块边界单向）；故此处照 T14 {@code ShellTool.OutputSink}、T18
 * {@code OpenAICompatibleLlmClient.ApiKeySource} 的先例注入一个窄接口。真实接线（把回调落成事件）归宿主装配。
 *
 * <p><b>事件词汇（宿主落库时用，本接口不落任何字符串）</b>：事件类型 {@code plugin.lifecycle}，payload 形如 {@code {pluginId,
 * version, state}}——照 {@code agent.lifecycle} 的既有范式（一个类型 + 一个 state 字段），不为 STARTED/STOPPED
 * 各造一个类型；{@code state} 取本接口 {@link State} 的名字。AG-UI 侧对未映射事件的处置（{@code AgUiEventTranslator} 显式返回空表 +
 * 用例表登记）归宿主装配任务，与本接口无关。
 *
 * <p>回调约定：状态真正生效后<b>同步</b>回调（STARTED 表示工具已登记进 {@code ToolRegistry}，STOPPED 表示工具已整组摘除）；实现
 * <b>勿阻塞、勿抛异常</b>——异常只被记日志，<b>不回滚</b>已生效的状态（与 {@code ToolRegistry.onChange} 同口径）。
 */
public interface PluginListener {

  /** 插件生命周期状态（本枚举是 AgentLib 的词汇表，与 PF4J 的 {@code PluginState} 解耦，宿主据此填事件的 state 字段）。 */
  enum State {
    /** 已装载并启动：该插件声明的工具已登记进全局工具目录。 */
    STARTED,
    /** 已停止并卸载：工具已从全局工具目录整组摘除；插件可再次 enable。 */
    STOPPED,
    /**
     * 未生效：已装载但没登记工具（id 冲突 / 工具登记失败 / 无扩展点等，本类已回滚 = 停止+卸载）， 或<b>描述符阶段就没装载成功</b>（坏 JAR / 缺描述符 /
     * Plugin-Id 重复，PF4J 连类加载器都没建）。 两种都以异常响亮抛出，且都回调本状态（标识口径见 {@link #onStateChanged}）。
     */
    FAILED
  }

  /**
   * 插件状态变更（每次变更回调一次，见接口注释的时序与异常约定）。
   *
   * <p><b>标识口径（宿主落库时按此判读，勿假定 pluginId 一定是 PF4J 插件 id）</b>：{@link State#STARTED}/{@link
   * State#STOPPED} 恒为 PF4J 插件 id；{@link State#FAILED} 分两种——
   *
   * <ol>
   *   <li><b>已识别出 pluginId 之后才失败</b>（扩展点缺失/多份、声明的 sourceId 非法或冲突、工具名冲突）：pluginId 与 version
   *       都是该插件真实值；
   *   <li><b>描述符阶段就失败</b>（JAR 不可读、缺 {@code Plugin-Id}、{@code Plugin-Id}
   *       与已装载插件重复）：此时<b>还没有可信的插件身份</b> ——pluginId 要么没读出来、要么已被先到者占用——{@code pluginId} 参数<b>退化为 JAR
   *       文件名</b>（含 {@code .jar} 后缀）， {@code version} 为空串。宿主据此仍能把它与"从未发现有这个 JAR"区分开，并在事件里定位到具体文件。
   * </ol>
   *
   * <p>一次失败尝试回调一次：失败的 JAR 不进"已发现"清单，故每次 {@code loadAll()} 重试都会再回调一次 {@code FAILED}（同一次 {@code
   * loadAll} 内按 jar 文件名序，与处理顺序一致）。
   *
   * @param pluginId PF4J 插件 id（来自插件 JAR 的描述符，非插件声明的 sourceId）；<b>描述符阶段失败时退化为 JAR 文件名</b>，见上
   * @param version 插件版本；描述符阶段失败时为空串，其余路径为描述符里的版本
   * @param state 新状态
   */
  void onStateChanged(String pluginId, String version, State state);

  /** 缺省实现：不监听（离线单测与尚未接线的宿主无需任何事件基础设施）。 */
  static PluginListener none() {
    return (pluginId, version, state) -> {};
  }
}
