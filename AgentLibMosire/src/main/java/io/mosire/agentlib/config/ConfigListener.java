package io.mosire.agentlib.config;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 配置变更监听（窄接口 SPI）：{@link FileConfigStore} 只调它，"变更如何落事件库/广播"由宿主决定。
 *
 * <p><b>为什么是接口而不是事件类型</b>：事件词汇表（{@code EventTypes}）与事件落库在 Brain （{@code
 * io.mosire.brain.runtime}），AgentLib 不得反引（模块边界单向）；故照 T14 {@code ShellTool.OutputSink}、T18 {@code
 * OpenAICompatibleLlmClient.ApiKeySource}、T19 {@code PluginListener} 的先例注入窄接口。真实接线（把回调落成 {@code
 * config.changed} 事件——该字面量只允许 在 Brain 的 {@code EventTypes} 定义一处，由 T23 装配时接线）归宿主。
 *
 * <p><b>回调约定</b>：变更<b>落盘成功后</b>同步回调（回调时刻盘上已能读到新值——先写后播，否则事件 声称的变更可能没发生）；实现<b>勿阻塞、勿抛异常</b>——{@link
 * RuntimeException} 只被记日志， <b>不回滚</b>已生效的写入（与 {@code PluginListener}/{@code ToolRegistry.onChange}
 * 同口径）。 env 覆盖层（读时叠加、永不落盘）<b>不会</b>触发本回调；校验失败/越权拒绝也不会触发（失败信号是 {@link ConfigException} 的错误码，见 {@link
 * ConfigException}）。
 */
@FunctionalInterface
public interface ConfigListener {

  /**
   * 一次成功落盘的配置变更。
   *
   * @param key 全量点分键（如 {@code agents.bob.temperature}、{@code runtime.logLevel}）
   * @param oldValue 该键在<b>目标文件</b>中的盘上旧值；{@code null} 表示该位置此前不存在（首次写入）。
   *     注意：这是<b>文件层</b>旧值，不是分层合并后的有效旧值（env 覆盖层不参与、也不产生回调）
   * @param newValue 本次写入的值（恒非 null）；监听器<b>不得修改</b>该节点（实现可能复用写入节点）
   */
  void onChanged(String key, JsonNode oldValue, JsonNode newValue);

  /** 缺省实现：不监听（离线单测与尚未接线的宿主无需任何事件基础设施）。 */
  static ConfigListener none() {
    return (key, oldValue, newValue) -> {};
  }
}
