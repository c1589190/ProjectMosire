package io.mosire.agentlib.approval;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程内审批登记表（S4 内核）：待裁决快照 + 决议（幂等）+ 阻塞等待 + 会话级放行。
 *
 * <p><b>为什么<b>不</b>落库</b>：审批是"当下这个人的当下决定"——进程退出即失效是<b>对的</b>， 跨重启继承一个"上次同意过"是错的（设计 §2.3）。{@code
 * APPROVE_SESSION} 也只活在内存里、不跨进程。
 *
 * <p><b>等待用条件变量</b>（{@link ReentrantLock} + {@link Condition}）：自旋会烧 CPU，且会让"到点没人答 ⇒ 拒"
 * 的超时用例变得又脆又慢。{@link #await} 到点返回空 = 没人答（<b>不是</b>放行）。
 *
 * <p><b>幂等</b>：{@link #decide} 对同一 id 只认第一次（重复决议返回 {@code false}，HTTP 层据此返 409，B2）。 已决议的项从 {@link
 * #pending()} 消失（它不是"待裁决"了），但在过期前仍可被 {@link #get} 取到—— B2 要靠这个把"未知 id"(404) 与"重复决议"(409) 分开。
 *
 * <p><b>惰性清理 + 超时扫描</b>：过期项（{@code now >= deadlineEpochMs}）在每次读路径上被摘除， 因此"过期项从 {@link #pending()}
 * 消失、{@link #await} 返回空、{@link #decide} 返回 false"三处口径一致。
 *
 * <p>线程安全：全部读写走同一把锁（表小、操作 O(1)）；返回的都是不可变快照。
 */
public final class PendingApprovals {

  private static final Logger LOG = LoggerFactory.getLogger(PendingApprovals.class);

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition changed = lock.newCondition();
  private final Map<String, Entry> entries = new LinkedHashMap<>();
  private final Set<SessionKey> sessions = new HashSet<>();
  private final AtomicLong minted = new AtomicLong();

  /**
   * 登记一条请求（id 为空则补发一个）。返回生效的 id——调用方<b>必须</b>拿它去问人/等人/发事件， 而不是继续用自己手里那个可能为空的 id（见 {@link #withId}）。
   *
   * <p>同 id 重复登记保留首条（登记是幂等的——同一请求被两条路径登记不该变成两条待裁决）。
   *
   * <p>登记时顺手做一次惰性清理（{@link #prune}）：长驻的纯 tty 进程没有 HTTP 面来读 {@link #pending()}， 已决议项不会因为"没人读"就一直攒着。
   */
  public String submit(ApprovalRequest req) {
    Objects.requireNonNull(req, "req");
    lock.lock();
    try {
      prune(System.currentTimeMillis());
      String id = req.id() == null || req.id().isBlank() ? nextId() : req.id();
      ApprovalRequest stored = id.equals(req.id()) ? req : withId(req, id);
      entries.putIfAbsent(id, new Entry(stored));
      return id;
    } finally {
      lock.unlock();
    }
  }

  /**
   * 生成并登记一条请求（authorizer 的主入口：id 与 {@code createdAtEpochMs} 由本表发放）。
   *
   * <p>形参列表就是派发包 §一 里那个自由的 {@code submitRequest(...)}：{@code callerKey} 由宿主填 （见 {@link
   * ApprovalRequest#callerKey()}），{@code deadlineEpochMs} 与编排器超时同源 （{@code
   * ApprovalCoordinator#timeout()}）。
   */
  public ApprovalRequest submitRequest(
      String callerKey,
      String tool,
      String classKey,
      String summary,
      String digest,
      long deadlineEpochMs) {
    ApprovalRequest req =
        new ApprovalRequest(
            nextId(),
            tool,
            classKey,
            summary,
            digest,
            System.currentTimeMillis(),
            deadlineEpochMs,
            callerKey);
    submit(req);
    return req;
  }

  /**
   * 决议（<b>幂等</b>）：首次生效返回 {@code true}；同一 id 再次决议返回 {@code false} 且<b>不覆盖</b>首次决定。
   *
   * <p>未知 id、已过期 id 同样返回 {@code false}（口径与"没这条请求"一致）。
   *
   * @param by 决议来源（通道名 / 闸名 / 人工面的名字；只进审计，不参与判定）
   */
  public boolean decide(String id, ApprovalDecision decision, String by) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(decision, "decision");
    lock.lock();
    try {
      Entry entry = entries.get(id);
      if (entry == null || entry.decided() || expired(entry, System.currentTimeMillis())) {
        // 未决议项保留原决定（幂等）：重复决议 false 且不覆盖，B2 据此返 409
        return false;
      }
      entry.decision = decision;
      changed.signalAll();
      LOG.debug("审批决议登记 id={} decision={} by={}", id, decision, by);
      return true;
    } finally {
      lock.unlock();
    }
  }

  /**
   * 阻塞到有决议或到点。返回空 = 到点没人答（调用方按 fail-closed 处理，<b>不是</b>放行）。
   *
   * <p>用条件变量等待（不自旋）；被中断按"没人答"返回（并恢复中断位）——上层拿到空即 fail-closed。 {@link #drop} 摘掉的项同样按"没人答"返回（被摘 =
   * 不会再有人答，<b>不是</b>放行）。
   */
  public Optional<ApprovalDecision> await(String id, Duration wait) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(wait, "wait");
    long budgetNanos = Math.max(0L, wait.toNanos());
    lock.lock();
    try {
      long remainingNanos = budgetNanos;
      while (true) {
        Entry entry = entries.get(id);
        if (entry == null || expired(entry, System.currentTimeMillis())) {
          entries.remove(id);
          return Optional.empty();
        }
        if (entry.decided()) {
          return Optional.of(entry.decision);
        }
        if (remainingNanos <= 0) {
          return Optional.empty();
        }
        try {
          // 返回值 = 预计还需等待的纳秒数（≤0 = 到点）——它就是本循环的权威剩余预算，不必再用
          // System.nanoTime() 另算一套；而且"算了不读"正是 SpotBugs 的 RV_RETURN_VALUE_IGNORED。
          remainingNanos = changed.awaitNanos(remainingNanos);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return Optional.empty();
        }
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * 摘除一条请求：该 id 已经<b>不可能</b>再有消费者时，把它从登记表里删掉，否则它会一直以"待裁决"的样子挂在提示面上。
   *
   * <p><b>谁该调</b>：编排器的<b>非人工终局</b>路径（到点没人答、通道故障、兜底异常）。那几条路径上它已经按 fail-closed 拒了，可登记项还留着——HTTP
   * 面会展示它，人点"批准"拿到成功回执、却没有任何人在等这个决定（{@code decide} 本该返回 {@code false}，B2 据此返 404/409）。
   *
   * <p><b>谁不该调</b>：人真的答过（{@link #decide} 生效）的项——那是可查的历史，B2 要靠 {@link #get} 把"未知 id"与"重复决议"分开。
   *
   * <p>摘除会唤醒正卡在 {@link #await} 里的等待者（它们立刻按"没这条了"返回空），不必等满窗口。
   *
   * @return 摘掉了返回 {@code true}；本来就没有（或已被摘）返回 {@code false}
   */
  public boolean drop(String id) {
    Objects.requireNonNull(id, "id");
    lock.lock();
    try {
      Entry removed = entries.remove(id);
      if (removed != null) {
        changed.signalAll();
      }
      return removed != null;
    } finally {
      lock.unlock();
    }
  }

  /** 按 id 取请求（已决议、未过期也取得到——B2 靠它把"未知 id"与"重复决议"分开）；过期或不存在返回空。 */
  public Optional<ApprovalRequest> get(String id) {
    Objects.requireNonNull(id, "id");
    lock.lock();
    try {
      prune(System.currentTimeMillis());
      Entry entry = entries.get(id);
      return entry == null ? Optional.empty() : Optional.of(entry.request);
    } finally {
      lock.unlock();
    }
  }

  /** 待裁决快照（未决议 && 未过期），按登记顺序；已决议/已过期的不在其中。 */
  public List<ApprovalRequest> pending() {
    lock.lock();
    try {
      prune(System.currentTimeMillis());
      List<ApprovalRequest> snapshot = new ArrayList<>(entries.size());
      for (Entry entry : entries.values()) {
        if (!entry.decided()) {
          snapshot.add(entry.request);
        }
      }
      return List.copyOf(snapshot);
    } finally {
      lock.unlock();
    }
  }

  /**
   * 记一条会话级放行：键 = <b>(调用者, classKey) 二元组</b>，只对同一调用者 × 同一 {@code classKey} 生效。
   *
   * <p>不跨进程、不写配置、不写回权限集（"审批 ≠ 授权"）：进程退出即失效。
   *
   * <p><b>别拿它绕过身份唯一性</b>：{@code callerKey} 现在的粒度是身份<b>桶</b>（{@code DEFAULT} 桶 = 所有子 Agent，{@code
   * GUEST} 桶 = 所有外部 MCP 客户端），"同一 callerKey"<b>不</b>等于"同一个调用者"。把非唯一身份的批准记进来， 等于把 A 拿到的会话放行白送给同桶的
   * B。编排器因此在写键前做了 fail-closed 收窄（人给非唯一身份 "本会话"时降级为一次，见 {@code ApprovalCoordinator} 的 {@code
   * sessionGrantable} 参数）； 任何直接调本方法的旁路也必须守同一条规则。
   */
  public void grantSession(String callerKey, String classKey) {
    Objects.requireNonNull(callerKey, "callerKey");
    Objects.requireNonNull(classKey, "classKey");
    if (callerKey.isBlank() || classKey.isBlank()) {
      // 空白键会让归类退化成事实上的全局放行，构造期拒比事后追责便宜
      throw new IllegalArgumentException("callerKey / classKey 不得为空白");
    }
    lock.lock();
    try {
      sessions.add(new SessionKey(callerKey, classKey));
    } finally {
      lock.unlock();
    }
  }

  /** 该 (调用者, classKey) 是否已被会话级放行（入参有空白/空 ⇒ false，不抛——它是查询）。 */
  public boolean isSessionGranted(String callerKey, String classKey) {
    if (callerKey == null || classKey == null || callerKey.isBlank() || classKey.isBlank()) {
      return false;
    }
    lock.lock();
    try {
      return sessions.contains(new SessionKey(callerKey, classKey));
    } finally {
      lock.unlock();
    }
  }

  /** 惰性清理：摘除已过期的条目（调用方持锁）。 */
  private void prune(long now) {
    entries.values().removeIf(entry -> expired(entry, now));
  }

  private static boolean expired(Entry entry, long now) {
    return now >= entry.request.deadlineEpochMs();
  }

  private String nextId() {
    return "ap-" + minted.incrementAndGet();
  }

  /**
   * 把请求换成给定 id 的副本（其余字段逐字保留）。
   *
   * <p>给编排器用：{@link #submit} 对空 id 会补发一个 id，但<b>不会</b>改写调用方手里的那个请求对象——事件面/等待面必须统一到 <b>生效</b>
   * id，否则会出现"人答在补发 id 上、编排器却等在原 id 上"（一路挂到超时）。
   */
  public static ApprovalRequest withId(ApprovalRequest req, String id) {
    return new ApprovalRequest(
        id,
        req.tool(),
        req.classKey(),
        req.summary(),
        req.digest(),
        req.createdAtEpochMs(),
        req.deadlineEpochMs(),
        req.callerKey());
  }

  /** 登记项：请求 + 决议（{@code null} = 待裁决）。全部字段只在持锁时读写。 */
  private static final class Entry {
    private final ApprovalRequest request;
    private ApprovalDecision decision;

    Entry(ApprovalRequest request) {
      this.request = request;
    }

    boolean decided() {
      return decision != null;
    }
  }

  /** 会话级放行的键（二元组，顺序无关且不可与工具名混淆）。 */
  private record SessionKey(String callerKey, String classKey) {}
}
