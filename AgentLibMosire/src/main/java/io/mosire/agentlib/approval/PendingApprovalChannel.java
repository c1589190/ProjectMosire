package io.mosire.agentlib.approval;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 外部审批面的通道侧：等的就是<b>同一份</b> {@link PendingApprovals}——外部适配器（MCP 工具 / 本机 UI / 其他传输）
 * 写入决议，这里被条件变量唤醒。传输层归宿主，本类不含任何 HTTP/TLS/端口。
 *
 * <p><b>为什么它也算一条"通道"</b>：编排器按通道列表并行等待"谁先答"。外部审批面没有"外发"这一步（待裁决列表就是登记表的快照， 人/工具自己去读），所以 {@link
 * #publish} 是空实现；但等待面必须进场——否则外部适配器答了，编排器那边没人接。两通道看到的是 <b>同一 id</b>（登记表发的那个），谁先答谁赢。
 *
 * <p><b>可用性由装配层打开</b>：本类<b>不</b>持有任何 server。装配层在外部审批面真正就绪之后调 {@link #markUp()}——在那之前 {@code
 * available()} 为 {@code false}，编排器不会把请求发进一个还不存在的面。
 *
 * <p><b>失败语义</b>：{@link #await} 只有"有决议"与"到点（返回空）"两种结果，返回空对编排器即 DENY（fail-closed）。 本类不产生任何"放行"分支。
 */
public final class PendingApprovalChannel implements ApprovalChannel, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(PendingApprovalChannel.class);

  private final PendingApprovals pending;

  /** 面是否真的就绪（装配层在外部适配器接上后打开；关停时合上）。 */
  private volatile boolean open;

  private volatile boolean closed;

  /**
   * 持有<b>进程内共享</b>的那一份登记表（同一进程只能有一个 {@link PendingApprovals}）：本类只读它、不写它（写决议的是外部
   * 适配器）。这里<b>有意</b>持实时引用而非快照——快照会看不见后提交的请求。
   */
  public PendingApprovalChannel(PendingApprovals pending) {
    this.pending = Objects.requireNonNull(pending, "pending");
  }

  @Override
  public String name() {
    return "pending";
  }

  @Override
  public boolean available() {
    return open && !closed;
  }

  @Override
  public void publish(ApprovalRequest req) {
    // 外部审批面不走"推送"：待裁决列表直接读登记表快照，人/工具自己来取
    LOG.debug("外部审批面等待人答复（不推送） id={}", req.id());
  }

  @Override
  public Optional<ApprovalDecision> await(String id, Duration wait) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(wait, "wait");
    if (!available()) {
      return Optional.empty();
    }
    return pending.await(id, wait);
  }

  /** 装配层专用：外部审批面已就绪、开始接受裁决。 */
  public void markUp() {
    open = true;
    LOG.debug("外部审批通道已就绪");
  }

  @Override
  public void close() {
    closed = true;
    LOG.info("外部审批通道已关闭");
  }
}
