package io.mosire.main.approval;

import io.mosire.agentlib.approval.ApprovalChannel;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.PendingApprovals;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP 审批面的通道侧（三期 S4-B2）：等的就是<b>同一份</b> {@link PendingApprovals}——人经 {@code POST
 * /api/approvals/{id}} 写决议，这里被条件变量唤醒。后端实现是 {@link ApprovalHttpServer}。
 *
 * <p><b>为什么它也算一条"通道"</b>：编排器按通道列表并行等待"谁先答"。HTTP 面没有"外发"这一步（待裁决列表就是登记表的快照， 人自己去读），所以 {@link
 * #publish} 是空实现；但等待面必须进场——否则人从 HTTP 答了，编排器那边没人接。两通道看到的是<b>同一 id</b> （登记表发的那个），谁先答谁赢。
 *
 * <p><b>可用性由装配层打开</b>：本类<b>不</b>持有 HTTP server（server 才需要 {@code ApprovalCoordinator} 去算生效
 * scope，装配顺序上晚于本类创建）。装配层在 server 真正绑定成功之后调 {@link #markUp()}——在那之前 {@code available()} 为 {@code
 * false}，编排器不会把请求发进一个还不存在的面。可用性认的是"端口真的在监听"（{@link #markUp()}），不是"配置说该开"。
 *
 * <p><b>失败语义</b>：{@link #await} 只有"有决议"与"到点（返回空）"两种结果，返回空对编排器即 DENY（fail-closed）。本类不产生任何 "放行"分支。
 */
public final class HttpApprovalChannel implements ApprovalChannel, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(HttpApprovalChannel.class);

  private final PendingApprovals pending;

  /** 面是否真的在监听（装配层在 server 绑定成功后打开；关停时合上）。 */
  private volatile boolean open;

  private volatile boolean closed;

  /**
   * 持有<b>进程内共享</b>的那一份登记表（同一进程只能有一个 {@link PendingApprovals}）：本类只读它、不写它（写决议的是 HTTP
   * handler）。这里<b>有意</b>持实时引用而非快照——快照会看不见后提交的请求（H1 判的就是这一点）。
   *
   * <p>不加 {@code @SuppressFBWarnings("EI_EXPOSE_REP2")}：本构造器的存引用经 {@link Objects#requireNonNull}
   * 中转， SpotBugs 未在此报告该模式（实测加了标注反被 {@code US_USELESS_SUPPRESSION_ON_METHOD} 判红）。将来若真被报告，抑制要加在
   * <b>持有引用的这个构造器</b>上，别挂到只做委托的重载上。
   */
  public HttpApprovalChannel(PendingApprovals pending) {
    this.pending = Objects.requireNonNull(pending, "pending");
  }

  @Override
  public String name() {
    return "http";
  }

  @Override
  public boolean available() {
    return open && !closed;
  }

  @Override
  public void publish(ApprovalRequest req) {
    // HTTP 面不走"推送"：待裁决列表直接读登记表快照（ApprovalHttpServer.handleList），人自己来取
    LOG.debug("HTTP 审批面等待人答复（不推送） id={}", req.id());
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

  /** 装配层专用：HTTP 面绑定成功、开始接受裁决。 */
  public void markUp() {
    open = true;
    LOG.debug("HTTP 审批通道已就绪");
  }

  @Override
  public void close() {
    closed = true;
    LOG.info("HTTP 审批通道已关闭");
  }
}
