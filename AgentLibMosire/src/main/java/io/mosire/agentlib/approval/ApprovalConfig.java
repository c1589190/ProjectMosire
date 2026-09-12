package io.mosire.agentlib.approval;

import java.time.Duration;
import java.util.Objects;

/**
 * 审批装配参数（配置段 {@code approval.*}，三期 S4-B2）：HTTP 审批面开关 / 等待人答的上限 / HTTP 面端口。
 *
 * <p><b>为什么是配置而不是常量</b>：等待上限决定"没人答时工具调用挂多久"——它是运维语义（用户裁决默认 300 s），不是代码语义；
 * 端口要给"端口冲突换一个"留出口。三项都只影响<b>装配</b>，不影响内核判定（{@link ApprovalCoordinator} 只收一个 {@link
 * Duration}，不认识配置）。
 *
 * <p><b>{@code timeout == 0} 是合法值</b>：等价于"人不答就直接拒"（编排器零预算 ⇒ 立即 DENY）——它<b>不是</b>关闭审批、
 * 更不是放行；需要审批的调用照样走完通道与审计，只是不会真的等到人。（关掉审批只能靠不给工具报 {@link ToolGate.Ask}，或让装配层不传编排器——那时 {@code Ask} 一律
 * fail-closed 拒。）
 *
 * <p><b>不变量</b>：{@code timeout} 非负、{@code httpPort} 在 0..65535（0 = 由系统分配）。非法值在<b>构造期</b>响亮抛出——
 * 装配层读配置时就要失败，而不是等到第一次审批才发现"配错了"。
 *
 * @param http HTTP 审批面是否启用（缺省 {@code true}，用户裁决；恒仅 loopback，无 host 参数）
 * @param timeout 等待人答的上限（{@code 0} = 立刻拒；缺省 300 s）
 * @param httpPort HTTP 审批面端口（{@code 0} = 自动分配；缺省 0）
 */
public record ApprovalConfig(boolean http, Duration timeout, int httpPort) {

  /** 缺省开关：HTTP 审批面<b>默认开</b>（用户裁决 2026-09-12——"待人裁决"默认要真的有人能答）。 */
  public static final boolean DEFAULT_HTTP = true;

  /** 缺省等待上限（秒）：300 s（用户裁决）。 */
  public static final long DEFAULT_TIMEOUT_SECONDS = 300L;

  /** 缺省端口：0 = 系统分配（实际端口由装配层 LOG.info 打出）。 */
  public static final int DEFAULT_HTTP_PORT = 0;

  public ApprovalConfig {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("approval.timeout 不得为负: " + timeout);
    }
    if (httpPort < 0 || httpPort > 65535) {
      throw new IllegalArgumentException("approval.httpPort 越界: " + httpPort);
    }
  }

  /** 缺省配置（整项缺失时的取值，见 {@link ApprovalConfigLoader}）。 */
  public static ApprovalConfig defaults() {
    return new ApprovalConfig(
        DEFAULT_HTTP, Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS), DEFAULT_HTTP_PORT);
  }
}
