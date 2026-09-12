package io.mosire.agentlib.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 审批编排器（S4 内核的核心）：<b>fail-closed</b>——任何异常、超时、无可用通道都归到 {@link
 * ApprovalDecision#DENY}，<b>绝不放行、绝不静默降级</b>。
 *
 * <p>{@link #decide(ApprovalRequest)} 的判定顺序（逐条都有判别性用例，见 {@code ApprovalCoordinatorTest}）：
 *
 * <ol>
 *   <li><b>快判链</b>：{@code gates} 逐个 {@code decide}，第一个非空的终局决定胜出。{@link DenyGate} 命中 ⇒ {@code DENY}
 *       直接返回，<b>不发 {@code approval.requested}</b>（快拒不打扰人）；链上命中也不碰通道；
 *   <li><b>通道</b>：{@code available()} 的通道全部 {@code publish}（两通道看到<b>同一 id</b>），再 {@code await}
 *       第一个决议；<b>无任何可用通道 ⇒ 立即 DENY</b>（不是挂到超时、更不是放行）；
 *   <li>决议 = {@link ApprovalDecision#APPROVE_SESSION} ⇒ 除返回外调 {@link
 *       PendingApprovals#grantSession(String, String)}（键 = (调用者, classKey)，见 {@link
 *       ApprovalRequest#callerKey()}）——但<b>只对身份唯一的调用者</b>成立（见构造器 {@code sessionGrantable}
 *       参数：非唯一身份上的"本会话"降级为一次）；
 *   <li>{@code await} 到点没人答 ⇒ DENY，并把登记项摘掉（{@link PendingApprovals#drop(String)}：没人答 =
 *       没人会是这条请求的消费者）；
 *   <li><b>{@code catch (Throwable)} ⇒ DENY</b>：通道/闸/登记表抛出的异常不得穿透到调用方，更不得被当成放行（同样摘掉登记项）。
 * </ol>
 *
 * <p><b>审计</b>：请求真的发给人时才发 {@code approval.requested}；每条终局路径都发 {@code approval.decided}（含快判与
 * fail-closed 的路径——"谁拒的、为什么拒"必须可查）。事件经 {@link EventBus#publish} 尽力投递，<b>本层只有总线、没有事件库</b>：{@code
 * seq} 记 0、{@code agent} 记空串（本层不知道 agent 身份）， 落库与 agent 归属由 B2 装配时接（见派发包 §一「事件」）。
 */
public final class ApprovalCoordinator {

  private static final Logger LOG = LoggerFactory.getLogger(ApprovalCoordinator.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** {@code by} 取值：到点没人答。 */
  public static final String SOURCE_TIMEOUT = "timeout";

  /** {@code by} 取值：一个可用通道都没有（立即拒）。 */
  public static final String SOURCE_NO_CHANNEL = "no-channel";

  /** {@code by} 取值：编排过程抛异常（fail-closed 兜底）。 */
  public static final String SOURCE_ERROR = "error";

  /** {@code by} 取值：通道没给自己起名字（{@code name() == null}）时的兜底名——{@code by} 不落成 JSON {@code null}。 */
  public static final String UNNAMED_CHANNEL = "unnamed";

  /**
   * 缺省的会话级放行判定：只认身份唯一的调用者。字面量 {@code "SYSTEM"} 与 {@code
   * io.mosire.agentlib.permission.AccessToken#SYSTEM} 的名字一致（审批层只拿到 {@code callerKey} 字符串，
   * 不引权限包的类型；{@code ApprovalCoordinatorTest} 有用例把这两个钉在一起）。
   */
  private static final Predicate<String> DEFAULT_SESSION_GRANTABLE = "SYSTEM"::equals;

  /** 事件里的 agent 字段：本层拿不到 agent 身份（无该输入），记空串而不是编一个名字。 */
  private static final String AGENT_UNKNOWN = "";

  private final List<ApprovalGate> gates;
  private final List<ApprovalChannel> channels;
  private final PendingApprovals pending;
  private final Duration timeout;
  private final EventBus bus;
  private final Predicate<String> sessionGrantable;

  /**
   * @param gates 快判链（顺序生效；建议 {@code DenyGate → AutoApproveGate → ConfirmGate}）
   * @param channels 审批通道（可用者全部收同一 id）
   * @param pending 进程内登记表（会话级放行也在它手里）
   * @param timeout 等待人答的上限；到点即 DENY（{@code null} 视为零，即"没人答得起" ⇒ 立刻拒）
   * @param bus 事件总线；<b>可 null</b>（null = 不发事件，判定语义不变）
   */
  public ApprovalCoordinator(
      List<ApprovalGate> gates,
      List<ApprovalChannel> channels,
      PendingApprovals pending,
      Duration timeout,
      EventBus bus) {
    this(gates, channels, pending, timeout, bus, DEFAULT_SESSION_GRANTABLE);
  }

  /**
   * @param sessionGrantable 有资格被记<b>会话级</b>放行的 {@code callerKey}（缺省 = 只认 {@code "SYSTEM"}， 见 {@link
   *     #DEFAULT_SESSION_GRANTABLE}）。这不是"可配置的便利开关"，是一条<b>临时的 fail-closed 收窄</b>： {@code callerKey}
   *     现在的粒度是身份<b>桶</b>（{@code DEFAULT} 桶 = 所有子 Agent，{@code GUEST} 桶 = 所有外部 MCP 客户端），"同一
   *     callerKey"<b>不</b>等于"同一个调用者"——于是"子 Agent 之间不继承"这条红线在桶粒度上是<b>假</b>的：A 拿到的 {@code (DEFAULT,
   *     bash:ask:apt)} 会白送给子 Agent B。收窄的代价是人得再点一次；不收窄的代价是留着一个已知不成立的不变式—— 后者糟得多。S5
   *     把身份做到实例级（通道/网关带上实例身份）之后，这里传 {@code key -> true} 即可放开。
   */
  public ApprovalCoordinator(
      List<ApprovalGate> gates,
      List<ApprovalChannel> channels,
      PendingApprovals pending,
      Duration timeout,
      EventBus bus,
      Predicate<String> sessionGrantable) {
    this.gates = List.copyOf(gates == null ? List.of() : gates);
    this.channels = List.copyOf(channels == null ? List.of() : channels);
    this.pending = Objects.requireNonNull(pending, "pending");
    this.timeout = timeout == null ? Duration.ZERO : timeout;
    this.bus = bus;
    this.sessionGrantable = sessionGrantable == null ? DEFAULT_SESSION_GRANTABLE : sessionGrantable;
  }

  /** 等待上限（authorizer 用它给请求盖 {@code deadlineEpochMs}，让"提示面显示的到点时刻"与"实际等待上限"同源， 不各算一套）。 */
  public Duration timeout() {
    return timeout;
  }

  /** 阻塞直到有决定；任何异常/超时/无可用通道 ⇒ {@link ApprovalDecision#DENY}。 */
  public ApprovalDecision decide(ApprovalRequest req) {
    if (req == null) {
      return ApprovalDecision.DENY;
    }
    long startedNanos = System.nanoTime();
    // 生效请求（id 可能由登记表补发）+ 它的 id：非人工终局路径要靠后者摘除登记项，catch 块也要用
    ApprovalRequest live = req;
    String registeredId = null;
    try {
      for (ApprovalGate gate : gates) {
        Optional<ApprovalDecision> decided = gate.decide(req);
        if (decided.isPresent()) {
          // 快判链也能给出"会话级放行"（AutoApproveGate 就是）；与通道决议同一条规则：过 settle 才落账
          return finish(req, settle(req, decided.get()), gate.name(), startedNanos);
        }
      }
      List<ApprovalChannel> usable = new ArrayList<>();
      for (ApprovalChannel channel : channels) {
        if (channel.available()) {
          usable.add(channel);
        }
      }
      if (usable.isEmpty()) {
        return finish(req, ApprovalDecision.DENY, SOURCE_NO_CHANNEL, startedNanos);
      }
      // 只在这条路径登记：登记 = "确实要去问人了"。快判命中/无通道都不到这里，于是待裁决列表里不会有幽灵项。
      // ★ 用 submit 返回的<b>生效 id</b>：请求 id 为空时由登记表补发，事件面/等待面必须统一到它——
      //   否则会出现"人答在补发 id 上、编排器还等在原 id 上"，一路挂到超时（评审 F6）。
      registeredId = pending.submit(req);
      if (!registeredId.equals(live.id())) {
        live = PendingApprovals.withId(live, registeredId);
      }
      emitRequested(live);
      for (ApprovalChannel channel : usable) {
        channel.publish(live);
      }
      Optional<Answer> answer = awaitFirst(usable, registeredId);
      if (answer.isEmpty()) {
        // 到点没人答 = 非人工终局：把登记项摘掉，别让它在提示面上以"待裁决"的样子等着一个不存在的消费者
        dropQuietly(registeredId);
        return finish(live, ApprovalDecision.DENY, SOURCE_TIMEOUT, startedNanos);
      }
      return finish(live, settle(live, answer.get().decision()), answer.get().by(), startedNanos);
    } catch (Throwable failure) {
      LOG.warn("审批编排异常，按 fail-closed 拒 tool={} id={}", req.tool(), live.id(), failure);
      dropQuietly(registeredId);
      return finish(live, ApprovalDecision.DENY, SOURCE_ERROR, startedNanos);
    }
  }

  /**
   * 该调用者 × 该请求决议 <b>实际会生效</b>的决议——会话级收窄的<b>唯一实现</b>（{@link #settle} 也调它，别在两处各写一遍降级规则）。
   *
   * <p>只有 {@link ApprovalDecision#APPROVE_SESSION} 会被收窄：{@code sessionGrantable} 认的调用者原样生效，其余
   * <b>降级为一次</b>（批准照跑，但会话键不写）。其余决议（{@code APPROVE_ONCE}/{@code DENY}）原样返回。
   *
   * <p><b>给谁用</b>：人面（HTTP 审批面的 {@code POST} 回执）需要回报"实际生效的 scope"，而人说的"本会话"未必等于生效的
   * ——本方法就是那个诚实的取法。调用方<b>不得</b>自行复制收窄规则或 {@code "SYSTEM"} 字面量：那样收窄一改，
   * 响应就会对人说谎（"以为批了整个会话，实际只批了一次"且响应面看不出来）。
   *
   * @param callerKey 发起该次工具调用的调用者（取自 {@link ApprovalRequest#callerKey()}，<b>不可自报</b>）
   * @param requested 请求的决议（通常来自人）
   */
  public ApprovalDecision effectiveDecision(String callerKey, ApprovalDecision requested) {
    if (requested != ApprovalDecision.APPROVE_SESSION) {
      return requested;
    }
    if (!sessionGrantable.test(callerKey)) {
      LOG.debug("会话级放行对该调用者不适用，降级为一次 caller={}", callerKey);
      return ApprovalDecision.APPROVE_ONCE;
    }
    return ApprovalDecision.APPROVE_SESSION;
  }

  /**
   * 决议落账（<b>会话键唯一的写键口</b>）：先经 {@link #effectiveDecision} 收窄，再对真正生效的 SESSION 写键。
   * 返回给调用方与事件面的也就是收窄后的决议——事件里的 {@code scope} 因此与"真的记了什么"一致， 不会出现"审计显示本会话放行、实际没记"。
   *
   * <p><b>收窄不变式（改动者必读）</b>：本方法的收窄效力依赖它<b>仍是唯一的写键口</b>——{@code pending.grantSession} 只在此处被调。
   * 若将来新增第三条产出路径（新 Gate 直接在 {@code decide} 里写表、或绕过 {@code settle} 调 {@code grantSession}），
   * 收窄会<b>静默失效且现有用例不会转红</b>（它们只覆盖本方法与 {@code gates} 链两条路径）。新增路径一律<b>过本方法</b>。
   */
  private ApprovalDecision settle(ApprovalRequest req, ApprovalDecision decision) {
    ApprovalDecision effective = effectiveDecision(req.callerKey(), decision);
    if (effective != ApprovalDecision.APPROVE_SESSION) {
      return effective;
    }
    pending.grantSession(req.callerKey(), req.classKey());
    return effective;
  }

  /** 非人工终局路径上摘除登记项；清理本身失败不得把 fail-closed 的 DENY 变成异常穿透（{@code id} 为 null = 还没登记）。 */
  private void dropQuietly(String id) {
    if (id == null) {
      return;
    }
    try {
      pending.drop(id);
    } catch (Throwable failure) {
      LOG.warn("清理待裁决登记项失败（不影响 fail-closed 拒） id={}", id, failure);
    }
  }

  /**
   * 全通道并行等待<b>第一个</b>决议：每个通道一条虚拟线程，谁先答谁赢（两通道看到同一 id，人从哪条答都算数）。
   *
   * <p>空结果 = 到点没人答（上层 ⇒ DENY）。通道抛异常 ⇒ 抛 {@link IllegalStateException}（上层 catch(Throwable) ⇒
   * DENY）——<b>不</b>改成"问另一条通道"：通道故障必须显式拒，不是换条路继续。
   *
   * <p><b>两通道同时到达时结果不确定</b>（先入队列者胜）：HTTP 面是调试面，同一个人只答一次即可，不必为此设计仲裁。 等待线程靠 {@code finally} 里的 {@code
   * interrupt()} 收尾，因此 {@link ApprovalChannel#await} 必须响应中断（见该方法的硬约束）。
   */
  private Optional<Answer> awaitFirst(List<ApprovalChannel> usable, String id) {
    BlockingQueue<Outcome> outcomes = new LinkedBlockingQueue<>();
    List<Thread> workers = new ArrayList<>(usable.size());
    for (ApprovalChannel channel : usable) {
      // 通道名进事件 by（审计要回答"谁答的"）：name() 为 null 时给个确定的兜底名，别让 by 落成 JSON null
      String channelName = channel.name() == null ? UNNAMED_CHANNEL : channel.name();
      Thread worker =
          Thread.ofVirtual()
              .name("approval-await-" + id + "-" + channelName)
              .start(
                  () -> {
                    try {
                      outcomes.add(
                          new Outcome(channelName, channel.await(id, timeout).orElse(null), null));
                    } catch (Throwable failure) {
                      outcomes.add(new Outcome(channelName, null, failure));
                    }
                  });
      workers.add(worker);
    }
    try {
      long deadlineNanos = System.nanoTime() + Math.max(0L, timeout.toNanos());
      while (true) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
          return Optional.empty();
        }
        Outcome outcome = outcomes.poll(remaining, TimeUnit.NANOSECONDS);
        if (outcome == null) {
          return Optional.empty();
        }
        if (outcome.failure() != null) {
          throw new IllegalStateException("审批通道故障: " + outcome.channel(), outcome.failure());
        }
        if (outcome.decision() != null) {
          return Optional.of(new Answer(outcome.channel(), outcome.decision()));
        }
        // 该通道到点没答（空）⇒ 继续等别的通道，总预算不变
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("审批等待被中断", interrupted);
    } finally {
      for (Thread worker : workers) {
        worker.interrupt();
      }
    }
  }

  /** 终局收尾：发 {@code approval.decided}（事件失败不影响决议）并返回决定。 */
  private ApprovalDecision finish(
      ApprovalRequest req, ApprovalDecision decision, String by, long startedNanos) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", req.id());
    payload.put("tool", req.tool());
    payload.put("classKey", req.classKey());
    payload.put("digest", req.digest());
    payload.put("decision", decision.name());
    payload.put("scope", scopeOf(decision));
    payload.put("by", by);
    payload.put("latencyMs", (System.nanoTime() - startedNanos) / 1_000_000L);
    emit(ApprovalEventTypes.DECIDED, payload, req);
    return decision;
  }

  private void emitRequested(ApprovalRequest req) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", req.id());
    payload.put("tool", req.tool());
    payload.put("classKey", req.classKey());
    payload.put("digest", req.digest());
    emit(ApprovalEventTypes.REQUESTED, payload, req);
  }

  /** {@code scope} 口径：批准才有作用域，拒记 {@code none}（不给"拒"编一个它没有的作用域）。 */
  private static String scopeOf(ApprovalDecision decision) {
    return switch (decision) {
      case APPROVE_ONCE -> "once";
      case APPROVE_SESSION -> "session";
      case DENY -> "none";
    };
  }

  private void emit(String type, Map<String, Object> payload, ApprovalRequest req) {
    if (bus == null) {
      return;
    }
    try {
      bus.publish(new Event(0L, Instant.now(), type, AGENT_UNKNOWN, toJson(payload), req.id()));
    } catch (Throwable failure) {
      // 审计面故障不得改变决议（更不能把一次 DENY 变成异常穿透）
      LOG.warn("审批事件投递失败（不影响决议） type={} id={}", type, req.id(), failure);
    }
  }

  private static String toJson(Map<String, Object> payload) {
    try {
      return JSON.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      LOG.error("审批事件 payload 序列化失败 type={}", payload, e);
      return "{}";
    }
  }

  /** 通道等待结果（通道名 + 决定 或 故障；{@code decision} 为 null = 该通道到点没人答）。 */
  private record Outcome(String channel, ApprovalDecision decision, Throwable failure) {}

  /** 有人答了：答的通道名（进事件 {@code by}）+ 决定。 */
  private record Answer(String by, ApprovalDecision decision) {}
}
