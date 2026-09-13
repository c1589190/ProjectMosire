package io.mosire.brain.subagent;

/**
 * 一个已启动子 Agent 的存活句柄（进程/执行体的最小控制面）。
 *
 * <p>为什么只有 {@code isAlive()} 和 {@code close()}：编排核心（Manager）只做生命周期编排， 不关心子进程的 stdio/退出码细节——那是 W3b
 * 真实实现与后续监控的事；控制面越窄，假实现越可信。
 *
 * <p>实现约定：{@link #close()} 必须幂等（kill 流程可能对同一句柄触发多次关闭）。
 */
public interface LaunchedSubagent extends AutoCloseable {

  /** 执行体是否仍存活（终态判定不依赖它，供观测/诊断）。 */
  boolean isAlive();

  /**
   * <b>异常退出</b>的诊断文本（正常退出 / 仍在运行 / 这条窄缝拿不到 ⇒ 空）。S5-E 新增。
   *
   * <p>为什么需要它：子体在 {@code RUNNING} 之后退出时，父侧过去只看得到一个 {@code finished} 生命周期事件——**完成工作后的正常退出**
   * 与**启动即崩**在事件面上同形，日志里也没有一行。2026-09-13 实测：父配了工作目录围栏导致子体的出生目录不是父的 CWD，子体拿到一个 解析不到的相对 {@code
   * --templates-dir} 当场退出，整个链路上唯一的痕迹就是那个 {@code finished}。
   *
   * <p>实现约定：只在"非零退出且拿得到"时返回文本；<b>不得抛异常</b>（它在退出观测线程里被调用）。文本是<b>诊断面</b>（子进程 stderr
   * 尾部，长度由实现自限），不是审计面——假实现/其它实现返回空是合法形态，调用方不得据此判定生死。
   */
  default java.util.Optional<String> exitDiagnostics() {
    return java.util.Optional.empty();
  }

  /**
   * 子执行体的<b>退出码</b>（设计-子Agent终局与等取口 §2.3）；跑完即退之后，码仍是诊断面而不是状态判据（父侧判终局读的是子库终局事件）。
   *
   * <p><b>拿不到 ⇒ {@link java.util.OptionalInt#empty()}，绝不编 0</b>（设计 §四.6）：仍在运行没有码，adopt 形态没有 {@code
   * Process} 句柄也没有码——"拿不到"与"码是 0"是两件事，写成 0 会把"未知"伪装成"正常退出"。调用方（{@link
   * SubagentManager}）据此只在该字段有值时才写事件键。
   *
   * <p>实现约定：<b>不得抛异常</b>（它在退出观测线程/终局收束路径里被调用），且只在"进程已退出且拿得到码"时返回值。
   */
  default java.util.OptionalInt exitCode() {
    return java.util.OptionalInt.empty();
  }

  /** 请求终止执行体；幂等。 */
  @Override
  void close();
}
