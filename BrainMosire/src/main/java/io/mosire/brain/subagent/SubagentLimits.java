package io.mosire.brain.subagent;

/**
 * 繁殖预算（D28 基础项，S5-A）：深度上限 + 单实例直系上限 + 全局在管实例上限。
 *
 * <p><b>口径（易错，逐条写死）</b>：
 *
 * <ul>
 *   <li>{@code maxDepth} 是<b>总层数</b>（含主 Agent 那一层），不是"子 Agent 深度上限"——所以子深度上限是 {@link
 *       #maxChildDepth()} = {@code maxDepth - 1}。裁决口径为 3 层（主 → 子 → 孙），即子深度 ≤ 2； 写成"4"是旧常量的口径（子深度 ≤
 *       4 = 5 层），<b>本项落地时按裁决收严</b>。
 *   <li>{@code maxChildrenPerInstance} 数的是<b>该调用者的在管子实例</b>（{@code parentInstanceId} 相同的非终态实例）。
 *       终态实例不再占额度——"顺序起 10 个、每个都跑完"不是跑飞，"同时挂 10 个"才是。
 *   <li>{@code maxInstances} 数的是<b>全进程在管子实例</b>（同一个 manager 下的所有非终态实例）。
 * </ul>
 *
 * <p><b>这是预算不是安全边界</b>：它拦的是"跑飞"（失控扇出），不是"越权"——越权由权限单调性（红线 1）与档位单调性
 * （S6）拦。三者不要互相替代：把额度调到很大不会让越权变得可能，把额度调到很小也不构成权限收窄。
 *
 * <p><b>缺省值的来源</b>：{@code maxDepth = 3} 来自 D28 裁决；{@code maxChildrenPerInstance = 4} 与 {@code
 * maxInstances = 8} 是<b>本段自拟的临时值</b>（裁决只说了"要有上限"，没给数）——它们可被配置覆盖，且不影响安全性（见上条）。
 *
 * @param maxDepth 总层数上限（含主 Agent；≥ 1）
 * @param maxChildrenPerInstance 单个调用者实例的直系在管子实例上限（≥ 1）
 * @param maxInstances 全进程在管子实例上限（≥ 1）
 */
public record SubagentLimits(int maxDepth, int maxChildrenPerInstance, int maxInstances) {

  /**
   * 配置段名（{@code subagents.*}）。<b>刻意不叫 {@code agents.*}</b>：那一族键的语法是 {@code agents.<id>.<key>} （见
   * {@code ConfigAuth}），{@code agents.maxDepth} 会被读成"agent id = maxDepth"的键而形状不合法。
   */
  public static final String SECTION = "subagents";

  /** 总层数上限的配置键。 */
  public static final String KEY_MAX_DEPTH = SECTION + ".maxDepth";

  /** 单调用者直系上限的配置键。 */
  public static final String KEY_MAX_CHILDREN = SECTION + ".maxChildrenPerInstance";

  /** 全局在管上限的配置键。 */
  public static final String KEY_MAX_INSTANCES = SECTION + ".maxInstances";

  public SubagentLimits {
    if (maxDepth < 1) {
      throw new IllegalArgumentException("maxDepth（总层数，含主 Agent）必须 ≥ 1: " + maxDepth);
    }
    if (maxChildrenPerInstance < 1) {
      throw new IllegalArgumentException(
          "maxChildrenPerInstance 必须 ≥ 1: " + maxChildrenPerInstance);
    }
    if (maxInstances < 1) {
      throw new IllegalArgumentException("maxInstances 必须 ≥ 1: " + maxInstances);
    }
  }

  /** 子 Agent 的深度上限（= 总层数 - 1；主 Agent 占 depth 0 那一层）。 */
  public int maxChildDepth() {
    return maxDepth - 1;
  }

  /**
   * 缺省预算：{@code maxDepth = 3}（D28 裁决口径）、{@code maxChildrenPerInstance = 4}、{@code maxInstances = 8}
   * （后两者为本段自拟，见类 javadoc）。
   */
  public static SubagentLimits defaults() {
    return new SubagentLimits(3, 4, 8);
  }
}
