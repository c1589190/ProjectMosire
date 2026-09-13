package io.mosire.agentlib.approval;

import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.CommandMode;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>上级判定闸</b>：不完全权限档提升上来的请求，交本级（完全权限的 Agent）<b>真的跑一轮判断</b>定夺。
 *
 * <p>用户裁决的落地：子 Agent 的每条命令都要人批太累，于是"向上找第一个完全权限的 Agent"——它不是一个规则代批器， 而是一次真实的 LLM 判定（{@link
 * SuperiorJudgement}）。本闸只负责<b>分流</b>：
 *
 * <ul>
 *   <li>只判 {@link AskKind#MODE_LIMITED} 的请求。{@link AskKind#SENSITIVE} 一律 {@code empty}（= 交给后面的人）
 *       ——<b>敏感区永远到人</b>，完全权限的上级也不行。这条是裁决，不是实现细节：没有它，一个被派去"清理磁盘"的子 Agent 就能让上级点头执行 {@code rm -rf
 *       /etc}。
 *   <li>只判<b>自己派出去的下级</b>（{@code requesterId} 是具体实例）：外部 MCP 面（{@code external-mcp}）与"身份未穿透" 的
 *       {@code unknown} 一律 {@code empty}。前者不是本进程派的，后者答不出是谁——代批的前提是"我知道你是谁、我派你去干什么"，
 *       答不出就老老实实到人（fail-closed 朝向人，不是朝向放行）。
 *   <li>本级档位<b>现读</b>（{@link CommandModeHolder} 那条运行时缝）：本级不是 {@link CommandMode#FULL} 就 {@code
 *       empty}——沿派生链继续向上找下一个 Agent（跨进程：子体的调用本来就在父进程里判），链上没有完全权限的 Agent 则落到人。
 *   <li>放行只给 {@link ApprovalDecision#APPROVE_ONCE}。<b>永不</b> {@code APPROVE_SESSION}——会话级放行是"人批"的口子
 *       （用户裁决"LIMITED 档下只认人批的会话"），上级判定不产生可继承的放行。
 *   <li>判定失败/超时/答复看不懂 ⇒ {@link ApprovalDecision#DENY}（fail-closed）。不落回人：判定面挂了还去烦人，
 *       等于"每条普通命令都要人批"，那正是本功能要免掉的事。
 * </ul>
 *
 * <p><b>命中即终局</b>（同闸链契约）：本闸直接给出决定、不发 {@code approval.requested}。决议事件里的 {@code by} 取 {@link
 * #NAME}，审计能回答"这条是谁批的"；判定理由只进日志（{@link SuperiorJudgement.Decision#reason()}）。
 */
public final class SuperiorJudgeGate implements ApprovalGate {

  /** 决议事件里 {@code by} 的取值（审计口径："上级 Agent 判的"）。 */
  public static final String NAME = "superior-agent";

  private static final Logger LOG = LoggerFactory.getLogger(SuperiorJudgeGate.class);

  private final SuperiorJudgement judgement;
  private final Supplier<CommandMode> selfMode;

  /**
   * @param judgement 判定实现（真实装配走 {@link LlmSuperiorJudgement}）
   * @param selfMode 本级档位的<b>取数缝</b>（现读，不缓存——改档立即对下一次调用生效）
   */
  public SuperiorJudgeGate(SuperiorJudgement judgement, Supplier<CommandMode> selfMode) {
    this.judgement = Objects.requireNonNull(judgement, "judgement");
    this.selfMode = Objects.requireNonNull(selfMode, "selfMode");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Optional<ApprovalDecision> decide(ApprovalRequest req) {
    Objects.requireNonNull(req, "req");
    if (req.kind() != AskKind.MODE_LIMITED) {
      return Optional.empty();
    }
    if (AgentIdentity.EXTERNAL_ID.equals(req.requesterId())
        || AgentIdentity.UNKNOWN.instanceId().equals(req.requesterId())) {
      // 代批的对象是"我派出去的下级"：外部 MCP 客户端不是，身份没穿透的调用者也不是（见类 javadoc）
      LOG.debug("请求者身份={} 不属可代批范围 ⇒ 到人: class={}", req.requesterId(), req.classKey());
      return Optional.empty();
    }
    CommandMode mode = selfMode.get();
    if (mode != CommandMode.FULL) {
      LOG.debug("本级档位={} ⇒ 不代批，交给更上一跳/人: class={}", mode, req.classKey());
      return Optional.empty();
    }
    SuperiorJudgement.Decision decision;
    try {
      decision = judgement.judge(req);
    } catch (RuntimeException e) {
      // 判定实现自己该吞异常（见 SuperiorJudgement#judge）；它没吞，这里按拒办，但把异常写进日志
      LOG.warn(
          "上级判定抛异常 ⇒ 拒: requester={} class={} 异常={}",
          req.requesterId(),
          req.classKey(),
          e.toString());
      return Optional.of(ApprovalDecision.DENY);
    }
    if (decision == null || !decision.approved()) {
      LOG.warn(
          "上级判定拒绝: requester={} class={} 理由={}",
          req.requesterId(),
          req.classKey(),
          decision == null ? "（判定为 null）" : decision.reason());
      return Optional.of(ApprovalDecision.DENY);
    }
    LOG.info(
        "上级判定放行（一次）: requester={} class={} 理由={}",
        req.requesterId(),
        req.classKey(),
        decision.reason());
    return Optional.of(ApprovalDecision.APPROVE_ONCE);
  }
}
