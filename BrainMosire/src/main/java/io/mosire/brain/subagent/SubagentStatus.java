package io.mosire.brain.subagent;

/**
 * 子 Agent 实例生命周期状态（有限状态机，推进规则见 {@link #canTransition}）。
 *
 * <p>为什么用显式状态机而不是散落的布尔标记：spawn/kill/launch 失败可能从不同线程到达， 任何"当前在跑吗"的推断都会出现中间态歧义；一个可校验的单向状态图让每次推进都可审计
 * （每次推进都伴随 {@code agent.lifecycle} 事件），非法推进直接抛错而非静默漂移。
 */
public enum SubagentStatus {

  /** 模板已装配、实例记录已建立，尚未调用 launcher。 */
  CONFIGURED,

  /** launcher.launch 执行中（真实进程尚未确认存活）。 */
  SPAWNING,

  /** 子进程已确认存活。 */
  RUNNING,

  // BLOCKED 预留：4.4 审批门控（AG-UI 未来项），本波不含——若需落地，插在 RUNNING 与 TERMINATING 之间
  // （图 RUNNING→BLOCKED→RUNNING），迁移点仅 SubagentStatus 与 canTransition；评审裁定 W3 省略可接受。

  /** 终止流程进行中（已请求停进程、尚未确认）。 */
  TERMINATING,

  /** 自然完成（终态，幂等可重入）。 */
  FINISHED,

  /** 失败（launch 失败或 kill-before-running；终态，幂等可重入）。 */
  FAILED,

  /** 被 kill 终止（终态，幂等可重入）。 */
  KILLED;

  /** 是否终态（终态只允许向自身幂等推进）。 */
  public boolean isFinal() {
    return this == FINISHED || this == FAILED || this == KILLED;
  }

  /**
   * 状态机推进合法性：合法返回 true，否则 false。
   *
   * <p>图：CONFIGURED→SPAWNING；SPAWNING→RUNNING|FAILED；RUNNING→TERMINATING|FINISHED|FAILED；
   * TERMINATING→KILLED|FAILED；终态→同终态（幂等）。其余一律拒绝——调用方负责把 false 转成 异常或忽略（幂等场景）。
   *
   * @param from 当前状态（null 视为非法）
   * @param to 目标状态（null 视为非法）
   */
  public static boolean canTransition(SubagentStatus from, SubagentStatus to) {
    if (from == null || to == null) {
      return false;
    }
    return switch (from) {
      case CONFIGURED -> to == SPAWNING;
      case SPAWNING -> to == RUNNING || to == FAILED;
      case RUNNING -> to == TERMINATING || to == FINISHED || to == FAILED;
      case TERMINATING -> to == KILLED || to == FAILED;
      case FINISHED, FAILED, KILLED -> from == to;
    };
  }
}
