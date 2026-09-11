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
    /** 已装载但未生效（id 冲突 / 工具登记失败 / 无扩展点等）：本类已回滚（停止+卸载），失败同时以异常响亮抛出。 */
    FAILED
  }

  /**
   * 插件状态变更（每次变更回调一次，见接口注释的时序与异常约定）。
   *
   * @param pluginId PF4J 插件 id（来自插件 JAR 的描述符，非插件声明的 sourceId）
   * @param version 插件版本（描述符缺失时不会再回调到此处；正常路径非 null）
   * @param state 新状态
   */
  void onStateChanged(String pluginId, String version, State state);

  /** 缺省实现：不监听（离线单测与尚未接线的宿主无需任何事件基础设施）。 */
  static PluginListener none() {
    return (pluginId, version, state) -> {};
  }
}
