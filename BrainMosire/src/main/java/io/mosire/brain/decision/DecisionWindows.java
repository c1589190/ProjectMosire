package io.mosire.brain.decision;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.ConfigStore;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.PendingDecisionQueue;
import java.util.Objects;
import java.util.Optional;

/**
 * <b>决策链路的两个等待窗</b>（配置项：{@code decision.package.deadlineMs} / {@code decision.commit.waitMs}）。
 *
 * <p><b>为什么要有这一类</b>（2026-10-17 用户指令「这玩意他妈不是可配置项吗？」+「太变态了直接等30分钟，改！」）： 两段式（用户裁定
 * 15）之下"等"分成<b>性质完全不同的两段</b>，而这两段一度都是编译期常量 ⇒ 部署与演示都没法把它们压到毫秒级：
 *
 * <ol>
 *   <li>{@link #deadlineMillis}——<b>等人答复</b>的窗口，同时是包的 {@code deadlineEpochMs}（裁定 10：等待窗口 = 包自己的
 *       deadline，不取 {@code approval.timeout()} 的较小者）。归 {@link PendingDecisionQueue} 持有，经 {@code
 *       App} → {@code AgentPipeline} 下发。缺省 {@link DecisionPackages#DEFAULT_DEADLINE_MILLIS}（30 分钟）；
 *   <li>{@link #commitWaitMillis}——<b>人已表态、只差敲一下执行命令</b>那一段的上界。归 {@link
 *       CrossProcessDecisionGateway} 持有。缺省 {@link
 *       CrossProcessDecisionGateway#DEFAULT_COMMIT_WAIT_MILLIS}（30 秒）——它<b>不继承</b> 30
 *       分钟：等一个"执行命令"按小时算没有道理。
 * </ol>
 *
 * <p>本类只是这两把钥匙的<b>读口</b>（{@link #load(ConfigStore)}）；缺省值仍归各自的所有者（队列与网关），不在这里复制一份
 * ——两个缺省常量各有唯一出处，本类引用它们。
 *
 * <p><b>缺省即现状</b>：整项缺失（或 JSON {@code null}）⇒ 两个缺省值，行为逐字不变。<b>项存在但值非法 ⇒ 启动期响亮失败</b> （{@link
 * #E_DECISION_WINDOWS}，消息点名键与原值）：把 {@code deadlineMs} 写成 {@code "600000"}（字符串）或 {@code 0}
 * 时静默回退缺省，会得到一个"看起来配了、其实没生效"的部署——而这两个值决定<b>一次敏感调用要等多久才被拒绝</b>，
 * 读错方向的代价是"以为压到毫秒级、实际挂满半小时"（本键的存在理由本身就是这个）。
 */
public record DecisionWindows(long deadlineMillis, long commitWaitMillis) {

  /** {@code decision.*} 项存在但值非法（类型 / 取值）。消息只含键名与原值（本段配置无密钥）。 */
  public static final String E_DECISION_WINDOWS = "E_DECISION_WINDOWS";

  /** 等人答复窗口的配置键（全量点分键：段 {@code decision}，段内键 {@code package.deadlineMs}）。 */
  public static final String DEADLINE_KEY = "decision.package.deadlineMs";

  /** 等执行命令窗口的配置键（全量点分键：段 {@code decision}，段内键 {@code commit.waitMs}）。 */
  public static final String COMMIT_WAIT_KEY = "decision.commit.waitMs";

  /**
   * {@code ConfigStore.get(prefix, key)} 收"段名 + 段内键"，而本类的两个键是<b>全量</b>点分键（要原样出现在 {@code
   * config.json} 里，用户看到的也是这个）⇒ 在这里切一刀。
   */
  private static final String SECTION = "decision";

  /** 校验：两个窗都必须为正（不合法就抛，绝不"回退成缺省"——那正是本类要消灭的静默）。 */
  public DecisionWindows {
    if (deadlineMillis <= 0) {
      throw new IllegalArgumentException("deadlineMillis 必须为正（毫秒）: " + deadlineMillis);
    }
    if (commitWaitMillis <= 0) {
      throw new IllegalArgumentException("commitWaitMillis 必须为正（毫秒）: " + commitWaitMillis);
    }
  }

  /** 两个窗都取缺省（{@code decision.*} 整项没配时的形态）。 */
  public static DecisionWindows defaults() {
    return new DecisionWindows(
        DecisionPackages.DEFAULT_DEADLINE_MILLIS,
        CrossProcessDecisionGateway.DEFAULT_COMMIT_WAIT_MILLIS);
  }

  /**
   * 读一份决策窗口配置。
   *
   * @param store 配置存储（本进程配置根上的实例，如 {@code new FileConfigStore(dataDir)}）
   * @throws ConfigException {@code E_DECISION_WINDOWS}（项存在但类型 / 取值非法）
   */
  public static DecisionWindows load(ConfigStore store) {
    Objects.requireNonNull(store, "store");
    DecisionWindows fallback = defaults();
    long deadline = positiveMillis(store, DEADLINE_KEY, fallback.deadlineMillis());
    long commitWait = positiveMillis(store, COMMIT_WAIT_KEY, fallback.commitWaitMillis());
    return new DecisionWindows(deadline, commitWait);
  }

  /** 正整数毫秒项：缺失 / JSON null ⇒ {@code fallback}；存在但非整数 / 非正 ⇒ 响亮。 */
  private static long positiveMillis(ConfigStore store, String fullKey, long fallback) {
    int dot = fullKey.indexOf('.');
    Optional<JsonNode> found = store.get(SECTION, fullKey.substring(dot + 1));
    JsonNode value = found.orElse(null);
    if (value == null || value.isNull()) {
      return fallback;
    }
    if (!value.isIntegralNumber()) {
      throw invalid(fullKey, "必须是整数（毫秒）", value.toString());
    }
    long parsed = value.asLong();
    if (parsed < 1L) {
      throw invalid(fullKey, "必须是正整数（≥ 1 毫秒）", value.toString());
    }
    return parsed;
  }

  /** 非法值一律同一个错误码（"哪一项配错了"由消息点名，不靠码区分）。消息不含任何密钥。 */
  private static ConfigException invalid(String key, String reason, String rawValue) {
    return new ConfigException(
        E_DECISION_WINDOWS, "决策窗口配置不可用：" + key + " " + reason + "（实际值: " + rawValue + "）");
  }
}
