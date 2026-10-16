package io.mosire.brain.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.approval.ApprovalChannel;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 网关的<b>幂等缓存</b>（P3b，设计 §5quinquies ⑤-3）在 Brain 层的判据：同一个 {@code requestId} 重试
 * <b>不再投审批面</b>（人不会被问第二遍）；换了条目却复用同一个键 ⇒ 具名拒；没给键 ⇒ 每次都是新请求（P3a 形状不变）； 缓存有界（满即淘汰最久未用）；键带提出者（两个子体的
 * {@code dp-1} 不会互相命中）。
 *
 * <p><b>判别力（改坏哪里它会红）</b>：
 *
 * <ul>
 *   <li>把幂等缓存整段删掉（{@code submit} 直连 {@code settle}）⇒ {@link
 *       #resubmittingTheSameRequestIdNeverReachesTheApprovalFaceTwice()} 红（审批面看到两条请求）；
 *   <li>把内容指纹那一问去掉（同键不同内容也返回旧裁决）⇒ {@link
 *       #theSameRequestIdWithDifferentItemsIsRefusedInsteadOfReplayed()} 红；
 *   <li>把缓存键从"提出者 + 键"改成裸键 ⇒ {@link #theSameRequestIdFromTwoProposersIsNotShared()} 红；
 *   <li>把 {@code removeEldestEntry} 去掉（无界）⇒ {@link
 *       #theCacheIsBoundedSoAnOldRequestIdIsSettledAgain()} 红。
 * </ul>
 */
class CrossProcessDecisionGatewayTest {

  private PendingApprovals pending;
  private EventBus bus;
  private RecordingChannel channel;
  private CrossProcessDecisionGateway gateway;

  /** 记下"投了几条请求"的桩通道（每条请求必须有人答，否则编排器按 DENY 返回）。 */
  private static final class RecordingChannel implements ApprovalChannel {

    private final List<ApprovalRequest> published = new CopyOnWriteArrayList<>();
    private final Deque<ApprovalDecision> script = new ArrayDeque<>();
    private final AtomicInteger availableCalls = new AtomicInteger();

    RecordingChannel answer(ApprovalDecision decision) {
      script.addLast(decision);
      return this;
    }

    @Override
    public String name() {
      return "case-channel";
    }

    @Override
    public boolean available() {
      availableCalls.incrementAndGet();
      return true;
    }

    @Override
    public void publish(ApprovalRequest request) {
      published.add(request);
    }

    @Override
    public Optional<ApprovalDecision> await(String id, Duration wait) {
      return Optional.ofNullable(script.pollFirst());
    }
  }

  @BeforeEach
  void setUp() {
    pending = new PendingApprovals();
    bus = new EventBus();
    channel = new RecordingChannel();
    ApprovalCoordinator coordinator =
        new ApprovalCoordinator(
            List.of(), List.of(channel), pending, Duration.ofSeconds(5), bus, key -> true);
    // 没有级联（cascade=null）⇒ 整包到人：每次"真实结算"必然投一条审批请求，计数即"投了几次"
    gateway =
        new CrossProcessDecisionGateway(
            instanceId ->
                Optional.of(
                    new DecisionCascade.Level(
                        instanceId, AgentPermissionSet.system(), CommandMode.FULL, "main")),
            null,
            coordinator,
            pending);
  }

  @AfterEach
  void tearDown() {
    bus.close();
  }

  private static CrossProcessDecisionGateway.Item item(int index, String command) {
    return new CrossProcessDecisionGateway.Item(
        index, "bash", Map.of("command", command), "bash:ask", AskKind.SENSITIVE);
  }

  private static CrossProcessDecisionGateway.Request request(
      String proposerKey, String requestId, CrossProcessDecisionGateway.Item... items) {
    return new CrossProcessDecisionGateway.Request(proposerKey, List.of(items), requestId);
  }

  @Test
  void resubmittingTheSameRequestIdNeverReachesTheApprovalFaceTwice() {
    channel.answer(ApprovalDecision.APPROVE_ONCE);
    CrossProcessDecisionGateway.Request first =
        request("bash-agent-1", "dp-1", item(0, "systemctl restart a"));

    CrossProcessDecisionGateway.Verdict firstVerdict = gateway.submit(first);
    channel.answer(ApprovalDecision.APPROVE_ONCE);
    CrossProcessDecisionGateway.Verdict retry = gateway.submit(first);

    assertThat(firstVerdict.approvedIndexes()).containsExactly(0);
    assertThat(retry.approvedIndexes()).as("重试原样拿回上次裁决").containsExactly(0);
    assertThat(retry.deciderLevel()).isEqualTo(CrossProcessDecisionGateway.DECIDER_HUMAN);
    assertThat(channel.published).as("★ 幂等命中：不再投审批面（人不会被问第二遍）").hasSize(1);
  }

  @Test
  void theSameRequestIdWithDifferentItemsIsRefusedInsteadOfReplayed() {
    channel.answer(ApprovalDecision.APPROVE_ONCE);
    gateway.submit(request("bash-agent-1", "dp-1", item(0, "systemctl restart a")));
    channel.answer(ApprovalDecision.APPROVE_ONCE);

    CrossProcessDecisionGateway.Request changed =
        request("bash-agent-1", "dp-1", item(0, "rm -rf /"));

    assertThatThrownBy(() -> gateway.submit(changed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(CrossProcessDecisionGateway.INVALID_ITEMS);
    assertThat(channel.published).as("同键不同内容既不返回旧裁决、也不再投一次审批面").hasSize(1);
  }

  @Test
  void theSameRequestIdFromTwoProposersIsNotShared() {
    channel.answer(ApprovalDecision.APPROVE_ONCE);
    channel.answer(ApprovalDecision.DENY);
    gateway.submit(request("bash-agent-1", "dp-1", item(0, "systemctl restart a")));

    CrossProcessDecisionGateway.Verdict other =
        gateway.submit(request("bash-agent-2", "dp-1", item(0, "systemctl restart b")));

    assertThat(other.approvedIndexes()).as("★ 键带提出者：另一个子体同名的 dp-1 走它自己那次结算（人是拒的 ⇒ 零批准）").isEmpty();
    assertThat(channel.published).as("两次都真的投了审批面").hasSize(2);
  }

  @Test
  void withoutARequestIdEverySubmitIsANewRequest() {
    channel.answer(ApprovalDecision.APPROVE_ONCE);
    channel.answer(ApprovalDecision.APPROVE_ONCE);
    CrossProcessDecisionGateway.Request twoArg =
        new CrossProcessDecisionGateway.Request(
            "bash-agent-1", List.of(item(0, "systemctl restart a")));

    gateway.submit(twoArg);
    gateway.submit(twoArg);

    assertThat(channel.published).as("缺省不传 requestId ⇒ 保持 P3a 的'每次都是新请求'").hasSize(2);
  }

  @Test
  void theCacheIsBoundedSoAnOldRequestIdIsSettledAgain() {
    // 填满 + 1：第 0 条应当被淘汰（LRU）
    for (int i = 0; i <= CrossProcessDecisionGateway.IDEMPOTENCY_CACHE_LIMIT; i++) {
      channel.answer(ApprovalDecision.DENY);
      gateway.submit(request("bash-agent-1", "dp-" + i, item(0, "cmd-" + i)));
    }
    int publishedBefore = channel.published.size();

    channel.answer(ApprovalDecision.DENY);
    gateway.submit(request("bash-agent-1", "dp-0", item(0, "cmd-0")));

    assertThat(channel.published)
        .as("★ 有界：超过上限条后最久未用的那条被淘汰 ⇒ 它再来时重新结算（内存不随时长增长）")
        .hasSize(publishedBefore + 1);
  }

  @Test
  void anUnknownProposerIsStillRefusedAndNotCached() {
    CrossProcessDecisionGateway strict =
        new CrossProcessDecisionGateway(
            instanceId -> Optional.empty(),
            null,
            new ApprovalCoordinator(
                List.of(), List.of(channel), pending, Duration.ofSeconds(5), bus, key -> true),
            pending);

    assertThatThrownBy(() -> strict.submit(request("ghost", "dp-1", item(0, "x"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(CrossProcessDecisionGateway.PROPOSER_UNKNOWN);
    assertThat(channel.published).as("具名拒不入缓存、也不投审批面").isEmpty();
  }

  @Test
  void anOverlongRequestIdIsRefused() {
    String tooLong = "x".repeat(CrossProcessDecisionGateway.MAX_REQUEST_ID_CHARS + 1);
    assertThatThrownBy(() -> gateway.submit(request("bash-agent-1", tooLong, item(0, "x"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requestId");
    assertThat(channel.published).isEmpty();
  }

  @Test
  void theApprovalFaceNeverSeesArgumentPlaintext() {
    // 一条防回归的"密钥纪律"判据：网关把条目搬进临时包与判定面，但**发出去的那条请求**只有摘要与工具名
    channel.answer(ApprovalDecision.APPROVE_ONCE);
    gateway.submit(request("bash-agent-1", "dp-9", item(0, "SECRET-CMD")));

    ApprovalRequest published = channel.published.get(0);
    assertThat(published.summary()).doesNotContain("SECRET-CMD");
    assertThat(published.digest())
        .as("摘要由 DecisionItemDigest 派生（唯一实现点），不是明文")
        .doesNotContain("SECRET-CMD")
        .isNotBlank();
    assertThat(published.classKey()).isEqualTo(DecisionApprovalBridge.CLASS_KEY);
  }
}
