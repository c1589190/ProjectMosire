package io.mosire.brain.subagent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 父侧<b>观测到的</b>子 Agent 终局（设计-子Agent终局与等取口 §2.4）：状态机仍由 {@link SubagentStatus} 单独承载， 本记录只回答"它为什么停"。
 *
 * <p><b>无值的字段一律 {@code null} ⇒ 序列化/落事件时不写这个键，不填假值</b>（{@link #fields()}）。四种来源各不相同，别混用：
 *
 * <ul>
 *   <li>{@code stopReason} 来自<b>子体自己的</b>终局记录（{@code agent.lifecycle/action=finished}，见 {@link
 *       SubagentManager#onChildExited}）——<b>只有子体真跑完一回合才有</b>；被杀/崩掉的子体永远没有这一项，父侧 也不许编一个（诚实边界，设计
 *       §2.2）；
 *   <li>{@code turns}/{@code toolCalls} 同源（子体流水线自己的计数器）；
 *   <li>{@code exitCode} 来自 {@link LaunchedSubagent#exitCode()}——<b>拿不到就是 {@code null}</b>（adopt
 *       形态、句柄自身缺失）， 不许写成 0（"拿不到码"与"码是 0"是两件事，设计 §四.6）。
 * </ul>
 *
 * <p><b>它不是可信输入的对外契约</b>（设计 §五.3）：{@code stopReason} 由子体的运行时写（模型只能间接影响"多跑几轮"）， 跨 Agent
 * 面若日后复用需重判——当下它只服务父侧编排与模型面的"正常/异常终局"可见性。
 */
public record TerminalOutcome(
    String stopReason, Integer turns, Integer toolCalls, Integer exitCode) {

  public TerminalOutcome {
    // 空白等于没有：老事件/坏 payload 里的空串不许冒充"有终局原因"
    stopReason = stopReason == null || stopReason.isBlank() ? null : stopReason;
  }

  /** 是否带子体自己交代的停因（= 子体留下了终局记录）。{@code false} ⇒ 异常/被终止终局。 */
  public boolean hasStopReason() {
    return stopReason != null;
  }

  /**
   * 有才写的字段表（{@code stopReason}/{@code turns}/{@code toolCalls}/{@code exitCode}）： {@code null}
   * 的字段<b>不出现</b>在返回表里—— 事件 payload 与工具返回体都按它拼，避免"某个字段填了个看着像真的假值"。
   */
  public Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    if (stopReason != null) {
      fields.put("stopReason", stopReason);
    }
    if (turns != null) {
      fields.put("turns", turns);
    }
    if (toolCalls != null) {
      fields.put("toolCalls", toolCalls);
    }
    if (exitCode != null) {
      fields.put("exitCode", exitCode);
    }
    return fields;
  }
}
