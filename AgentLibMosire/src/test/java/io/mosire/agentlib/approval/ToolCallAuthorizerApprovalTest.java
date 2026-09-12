package io.mosire.agentlib.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * S4 B1 审批内核的离线判别性用例（<b>经 {@link ToolCallAuthorizer} 入口</b>，用桩通道，不碰 tty/HTTP）。
 *
 * <p>每条用例都回答"把它对应的那行代码改坏，这条会转红吗"：
 *
 * <ul>
 *   <li>T1 无可用通道 ⇒ 立即拒：改"立即拒"为放行 ⇒ {@code APPROVAL_DENIED}/工具体未跑转红；改为"挂到超时" ⇒ 耗时断言转红（超时设 5 s，断言
 *       &lt; 1 s）；
 *   <li>T2 通道到点没人答 ⇒ 拒：超时路径改放行 ⇒ 转红；
 *   <li>T3 通道故障 ⇒ 拒：去掉编排器的 {@code catch (Throwable) ⇒ DENY} ⇒ 异常穿透、用例转红；
 *   <li>T4 once 就一次：把 once 记成 session ⇒ 第二次不再问（{@code awaitCalls} 停住）⇒ 转红；
 *   <li>T5 会话键 = (调用者, classKey)：键改成工具名/全局 ⇒ 换 classKey 或换 caller 时不再问 ⇒ 转红；
 *   <li>T6 硬拒不进审批：让 {@code Block} 走审批 ⇒ 通道计数/待裁决快照转红；
 *   <li>T7 未装配编排器 ⇒ 拒：改成默认放行 ⇒ 转红；
 *   <li>T8 事件面：往 payload 里塞参数/摘要 ⇒ 字段集与明文断言转红；
 *   <li>T10 黑名单闸快拒：DenyGate 改成走通道 ⇒ 通道计数转红；
 *   <li>T11 闸位自报 {@code null} ⇒ {@code COMMAND_BLOCKED}（既不放行也不问人）：把这一跳改成 ALLOW ⇒ 转红；
 *   <li>T12 窗口内人答得上（{@code PendingBackedChannel} 把等待交给登记表）：把编排器传给通道的窗口改成 1 ms ⇒ 放行与"通道真的等过"断言转红；
 *   <li>T13 摘要挡住"渲染相同、类别不同"的碰撞（{@code "1"} 与 {@code 1}、列表与它的 {@code toString}），且同类同摘： 去掉类型标记 ⇒ 转红。
 * </ul>
 *
 * <p>反面对照贯穿各条：被拒的用例都配上"同一夹具够格时能过"（T1/T6 尾部的放行、T4 首次放行、T5 首次会话放行、 T8
 * 摘要里确实带上了暗记），否则"被拒"无法与"通道坏了/工具没注册/参数不对"区分。
 */
class ToolCallAuthorizerApprovalTest {

  private static final String TOOL = "bash";
  private static final String CLASS_KEY = "bash:ask:systemctl";
  private static final String SUMMARY = "重启系统服务";
  private static final String MARKER = "SECRET-MARKER-1234";

  private EventBus bus;
  private EventSink events;

  @BeforeEach
  void subscribeBus() {
    bus = new EventBus();
    events = new EventSink(bus);
  }

  @AfterEach
  void releaseBus() {
    events.close();
    bus.close();
  }

  /** 生产形态的闸链（B2 会在最前面再插一条配置黑名单闸）：会话放行 → 落到人。 */
  private ApprovalCoordinator coordinator(
      PendingApprovals pending, List<ApprovalChannel> channels, Duration timeout) {
    return new ApprovalCoordinator(
        List.of(new AutoApproveGate(pending), new ConfirmGate()), channels, pending, timeout, bus);
  }

  /** 桩工具：bash 的出厂姿态（DEFAULT 级、敏感 + 破坏双真）。 */
  private static StubTool bashTool() {
    return new StubTool(TOOL, ToolSpec.level(AccessToken.DEFAULT, true, true));
  }

  // ---------------------------------------------------------------- T1

  @Test
  void noAvailableChannelIsDeniedImmediatelyAndBodyNeverRuns() {
    PendingApprovals pending = new PendingApprovals();
    StubChannel tty = new StubChannel("tty").available(false);
    StubTool tool = bashTool();
    ToolRegistry registry = ApprovalStubs.registryWith(tool);
    ToolCallAuthorizer authorizer =
        ToolCallAuthorizer.of(
            new ToolExecutionGuard(), coordinator(pending, List.of(tty), Duration.ofSeconds(5)));
    tool.gate = new ToolGate.Ask(CLASS_KEY, SUMMARY);

    long startedNanos = System.nanoTime();
    ToolResult result =
        authorizer.execute(registry, TOOL, ApprovalStubs.context(AccessToken.SYSTEM));
    long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;

    assertThat(result.code()).isEqualTo(ToolCallAuthorizer.APPROVAL_DENIED);
    assertThat(tool.calls()).isZero();
    assertThat(tty.availableCalls()).isEqualTo(1);
    assertThat(tty.publishCalls()).isZero();
    assertThat(tty.awaitCalls()).isZero();
    assertThat(pending.pending()).isEmpty();
    // ★ 判别性：超时是 5 s，这里必须"立刻"拒——把它改成"挂到超时"，本条转红
    assertThat(elapsedMs).isLessThan(1_000L);
    assertThat(events.quietWithout(ApprovalEventTypes.REQUESTED, Duration.ofMillis(300))).isTrue();

    // ★ 反面对照：同一注册表、同一工具、同一闸位，只把通道恢复可用并让人答"本次放行" ⇒ 真的执行
    tty.available(true).thenAnswer(ApprovalDecision.APPROVE_ONCE);
    ToolResult allowed =
        authorizer.execute(registry, TOOL, ApprovalStubs.context(AccessToken.SYSTEM));
    assertThat(allowed.success()).isTrue();
    assertThat(tool.calls()).isEqualTo(1);
  }

  // ---------------------------------------------------------------- T2

  @Test
  void timeoutIsDeniedAndBodyNeverRuns() {
    PendingApprovals pending = new PendingApprovals();
    StubChannel tty = new StubChannel("tty"); // 可用，但脚本空 = 到点没人答
    StubTool tool = bashTool();
    ToolRegistry registry = ApprovalStubs.registryWith(tool);
    ToolCallAuthorizer authorizer =
        ToolCallAuthorizer.of(
            new ToolExecutionGuard(), coordinator(pending, List.of(tty), Duration.ofMillis(60)));
    tool.gate = new ToolGate.Ask(CLASS_KEY, SUMMARY);

    long startedNanos = System.nanoTime();
    ToolResult result =
        authorizer.execute(registry, TOOL, ApprovalStubs.context(AccessToken.SYSTEM));
    long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;

    assertThat(result.code()).isEqualTo(ToolCallAuthorizer.APPROVAL_DENIED);
    assertThat(tool.calls()).isZero();
    assertThat(tty.publishCalls()).isEqualTo(1); // 与 T1 的"没通道"可分辨：这里是真问过、没人答
    // ★ 判别性：确实等到了超时（不是一拿不到答案就放行），且不是永远等下去
    assertThat(elapsedMs).isGreaterThanOrEqualTo(50L).isLessThan(2_000L);
    Event decided =
        events.awaitType(ApprovalEventTypes.DECIDED, Duration.ofSeconds(2)).orElseThrow();
    assertThat(ApprovalStubs.payload(decided).get("decision").asText()).isEqualTo("DENY");
    assertThat(ApprovalStubs.payload(decided).get("by").asText())
        .isEqualTo(ApprovalCoordinator.SOURCE_TIMEOUT);
  }

  // ---------------------------------------------------------------- T3

  @Test
  void channelFailureIsDeniedNotPropagatedAndNotAllowed() {
    StubTool tool = bashTool();
    ToolRegistry registry = ApprovalStubs.registryWith(tool);
    tool.gate = new ToolGate.Ask(CLASS_KEY, SUMMARY);

    // ① publish 抛异常（异常的 publish 不得穿透，更不得当成放行）
    PendingApprovals pendingA = new PendingApprovals();
    StubChannel brokenPublish = new StubChannel("tty").failingPublish();
    ToolCallAuthorizer authorizerA =
        ToolCallAuthorizer.of(
            new ToolExecutionGuard(),
            coordinator(pendingA, List.of(brokenPublish), Duration.ofMillis(200)));
    ToolResult resultA =
        authorizerA.execute(registry, TOOL, ApprovalStubs.context(AccessToken.SYSTEM));
    assertThat(resultA.code()).isEqualTo(ToolCallAuthorizer.APPROVAL_DENIED);
    assertThat(tool.calls()).isZero();

    // ② await 抛异常
    PendingApprovals pendingB = new PendingApprovals();
    StubChannel brokenAwait = new StubChannel("http").failingAwait();
    ToolCallAuthorizer authorizerB =
        ToolCallAuthorizer.of(
            new ToolExecutionGuard(),
            coordinator(pendingB, List.of(brokenAwait), Duration.ofMillis(200)));
    ToolResult resultB =
        authorizerB.execute(registry, TOOL, ApprovalStubs.context(AccessToken.SYSTEM));
    assertThat(resultB.code()).isEqualTo(ToolCallAuthorizer.APPROVAL_DENIED);
    assertThat(tool.calls()).isZero();

    // 两条 fail-closed 路径都留痕（by=error），且都不是放行
    List<Event> decided = events.awaitAtLeast(ApprovalEventTypes.DECIDED, 2, Duration.ofSeconds(2));
    assertThat(decided).hasSize(2);
    assertThat(decided)
        .allSatisfy(
            event -> {
              JsonNode payload = ApprovalStubs.payload(event);
              assertThat(payload.get("decision").asText()).isEqualTo("DENY");
              assertThat(payload.get("by").asText()).isEqualTo(ApprovalCoordinator.SOURCE_ERROR);
            });
  }

  // ---------------------------------------------------------------- T4

  @Test
  void approveOnceRunsBodyAndTheNextSameCallAsksAgain() {
    PendingApprovals pending = new PendingApprovals();
    StubChannel tty =
        new StubChannel("tty")
            .thenAnswer(ApprovalDecision.APPROVE_ONCE)
            .thenAnswer(ApprovalDecision.DENY);
    StubTool tool = bashTool();
    ToolRegistry registry = ApprovalStubs.registryWith(tool);
    ToolCallAuthorizer authorizer =
        ToolCallAuthorizer.of(
            new ToolExecutionGuard(), coordinator(pending, List.of(tty), Duration.ofSeconds(2)));
    tool.gate = new ToolGate.Ask(CLASS_KEY, SUMMARY);

    ToolResult first =
        authorizer.execute(registry, TOOL, ApprovalStubs.context(AccessToken.SYSTEM));
    assertThat(first.success()).isTrue();
    assertThat(tool.calls()).isEqualTo(1);
    assertThat(tty.awaitCalls()).isEqualTo(1);

    // ★ 判别性：once 不得被记成 session——同类调用必须再问一次（把 once 记成 session，本段转红）
    ToolResult second =
        authorizer.execute(registry, TOOL, ApprovalStubs.context(AccessToken.SYSTEM));
    assertThat(second.code()).isEqualTo(ToolCallAuthorizer.APPROVAL_DENIED);
    assertThat(tool.calls()).isEqualTo(1);
    assertThat(tty.awaitCalls()).isEqualTo(2);

    List<Event> decided = events.awaitAtLeast(ApprovalEventTypes.DECIDED, 2, Duration.ofSeconds(2));
    assertThat(decided).hasSize(2);
    assertThat(ApprovalStubs.payload(decided.get(0)).get("scope").asText()).isEqualTo("once");
    assertThat(ApprovalStubs.payload(decided.get(1)).get("scope").asText()).isEqualTo("none");
  }

  // ---------------------------------------------------------------- T5

  @Test
  void approveSessionIsKeyedByCallerAndClassKeyNotByToolName() {
    PendingApprovals pending = new PendingApprovals();
    StubChannel tty =
        new StubChannel("tty")
            .thenAnswer(ApprovalDecision.APPROVE_SESSION)
            .thenAnswer(ApprovalDecision.DENY)
            .thenAnswer(ApprovalDecision.DENY);
    StubTool tool = bashTool();
    ToolRegistry registry = ApprovalStubs.registryWith(tool);
    ToolCallAuthorizer authorizer =
        ToolCallAuthorizer.of(
            new ToolExecutionGuard(), coordinator(pending, List.of(tty), Duration.ofSeconds(2)));

    // ① SYSTEM × classKey=systemctl ⇒ 这次问了人，人给"本会话"
    tool.gate = new ToolGate.Ask(CLASS_KEY, SUMMARY);
    assertThat(
            authorizer.execute(registry, TOOL, ApprovalStubs.context(AccessToken.SYSTEM)).success())
        .isTrue();
    assertThat(tool.calls()).isEqualTo(1);
    assertThat(tty.awaitCalls()).isEqualTo(1);

    // ★ ② 同 caller × 同 classKey ⇒ 不再问（键命中）
    assertThat(
            authorizer.execute(registry, TOOL, ApprovalStubs.context(AccessToken.SYSTEM)).success())
        .isTrue();
    assertThat(tool.calls()).isEqualTo(2);
    assertThat(tty.awaitCalls()).isEqualTo(1);

    // ★ ③ 换 classKey（同一调用者、同一工具）⇒ 仍要问（把键改成工具名，本段转红）
    tool.gate = new ToolGate.Ask("bash:ask:apt", "安装软件包");
    assertThat(authorizer.execute(registry, TOOL, ApprovalStubs.context(AccessToken.SYSTEM)).code())
        .isEqualTo(ToolCallAuthorizer.APPROVAL_DENIED);
    assertThat(tool.calls()).isEqualTo(2);
    assertThat(tty.awaitCalls()).isEqualTo(2);

    // ★ ④ 换 caller（同一 classKey、同一工具）⇒ 仍要问（把键改成全局，本段转红）
    tool.gate = new ToolGate.Ask(CLASS_KEY, SUMMARY);
    assertThat(
            authorizer.execute(registry, TOOL, ApprovalStubs.context(AccessToken.DEFAULT)).code())
        .isEqualTo(ToolCallAuthorizer.APPROVAL_DENIED);
    assertThat(tool.calls()).isEqualTo(2);
    assertThat(tty.awaitCalls()).isEqualTo(3);
  }

  // ---------------------------------------------------------------- T6

  @Test
  void blockIsCommandBlockedAndNeverEntersApproval() {
    PendingApprovals pending = new PendingApprovals();
    StubChannel tty = new StubChannel("tty").thenAnswer(ApprovalDecision.APPROVE_ONCE);
    StubTool tool = bashTool();
    ToolRegistry registry = ApprovalStubs.registryWith(tool);
    ToolCallAuthorizer authorizer =
        ToolCallAuthorizer.of(
            new ToolExecutionGuard(), coordinator(pending, List.of(tty), Duration.ofSeconds(2)));
    tool.gate = new ToolGate.Block("bash:blocked:rm-rf", "硬拒：不可回滚且几乎不可能是任务本意");

    ToolResult result =
        authorizer.execute(registry, TOOL, ApprovalStubs.context(AccessToken.SYSTEM));

    assertThat(result.code()).isEqualTo(ToolCallAuthorizer.COMMAND_BLOCKED);
    assertThat(tool.calls()).isZero();
    // ★ 判别性：硬拒连"通道可用吗"都没问过（让 Block 走审批，三个计数立刻转红）
    assertThat(tty.availableCalls()).isZero();
    assertThat(tty.publishCalls()).isZero();
    assertThat(tty.awaitCalls()).isZero();
    assertThat(pending.pending()).isEmpty();
    // 事件面：连审批面都没进（requested 一条不在；decided 也没有——根本没进编排器）
    assertThat(events.quietWithout(ApprovalEventTypes.REQUESTED, Duration.ofMillis(300))).isTrue();
    assertThat(events.ofType(ApprovalEventTypes.DECIDED)).isEmpty();

    // ★ 反面对照：同一夹具、同一工具，把闸位从 Block 换成 Ask ⇒ 审批面立刻活了（证明"没进审批"来自 Block 语义）
    tool.gate = new ToolGate.Ask(CLASS_KEY, SUMMARY);
    assertThat(
            authorizer.execute(registry, TOOL, ApprovalStubs.context(AccessToken.SYSTEM)).success())
        .isTrue();
    assertThat(tty.publishCalls()).isEqualTo(1);
  }

  // ---------------------------------------------------------------- T7

  @Test
  void askWithoutCoordinatorIsDenied() {
    StubTool tool = bashTool();
    ToolRegistry registry = ApprovalStubs.registryWith(tool);
    tool.gate = new ToolGate.Ask(CLASS_KEY, SUMMARY);

    // 三个"没装配审批"的入口形态都必须 fail-closed
    List<ToolCallAuthorizer> authorizers =
        List.of(
            ToolCallAuthorizer.standard(),
            ToolCallAuthorizer.of(new ToolExecutionGuard()),
            ToolCallAuthorizer.of(new ToolExecutionGuard(), null));
    for (ToolCallAuthorizer authorizer : authorizers) {
      ToolResult result =
          authorizer.execute(registry, TOOL, ApprovalStubs.context(AccessToken.SYSTEM));
      assertThat(result.code()).isEqualTo(ToolCallAuthorizer.APPROVAL_DENIED);
    }
    assertThat(tool.calls()).isZero();

    // ★ 反面对照：同一工具 + 装上编排器（人放行）⇒ 能过（证明上面的拒来自"没装配"，不是工具本身坏）
    ToolCallAuthorizer wired =
        ToolCallAuthorizer.of(
            new ToolExecutionGuard(),
            coordinator(
                new PendingApprovals(),
                List.of(new StubChannel("tty").thenAnswer(ApprovalDecision.APPROVE_ONCE)),
                Duration.ofSeconds(2)));
    assertThat(wired.execute(registry, TOOL, ApprovalStubs.context(AccessToken.SYSTEM)).success())
        .isTrue();
    assertThat(tool.calls()).isEqualTo(1);
  }

  // ---------------------------------------------------------------- T8

  @Test
  void approvalEventsCarryTheExactFieldsAndNoPlaintext() {
    PendingApprovals pending = new PendingApprovals();
    StubChannel tty = new StubChannel("tty").thenAnswer(ApprovalDecision.APPROVE_ONCE);
    StubTool tool = bashTool();
    ToolRegistry registry = ApprovalStubs.registryWith(tool);
    ToolCallAuthorizer authorizer =
        ToolCallAuthorizer.of(
            new ToolExecutionGuard(), coordinator(pending, List.of(tty), Duration.ofSeconds(2)));
    tool.gate = new ToolGate.Ask(CLASS_KEY, SUMMARY + " " + MARKER);

    ToolResult result =
        authorizer.execute(
            registry,
            TOOL,
            ApprovalStubs.context(
                AccessToken.SYSTEM,
                Map.of("command", "systemctl restart nginx --token=" + MARKER, "cwd", "/srv")));
    assertThat(result.success()).isTrue();

    Event requestedEvent =
        events.awaitType(ApprovalEventTypes.REQUESTED, Duration.ofSeconds(2)).orElseThrow();
    Event decidedEvent =
        events.awaitType(ApprovalEventTypes.DECIDED, Duration.ofSeconds(2)).orElseThrow();
    JsonNode requested = ApprovalStubs.payload(requestedEvent);
    JsonNode decided = ApprovalStubs.payload(decidedEvent);

    assertThat(ApprovalStubs.fields(requested))
        .containsExactlyInAnyOrder("id", "tool", "classKey", "digest");
    assertThat(ApprovalStubs.fields(decided))
        .containsExactlyInAnyOrder(
            "id", "tool", "classKey", "digest", "decision", "scope", "by", "latencyMs");
    assertThat(requested.get("tool").asText()).isEqualTo(TOOL);
    assertThat(requested.get("classKey").asText()).isEqualTo(CLASS_KEY);
    assertThat(decided.get("decision").asText()).isEqualTo("APPROVE_ONCE");
    assertThat(decided.get("scope").asText()).isEqualTo("once");
    assertThat(decided.get("by").asText()).isEqualTo("tty");
    assertThat(decided.get("latencyMs").isNumber()).isTrue();
    assertThat(decided.get("latencyMs").asLong()).isGreaterThanOrEqualTo(0L);
    assertThat(decided.get("id").asText()).isEqualTo(requested.get("id").asText());

    // ★ 正面对照：暗记<b>确实</b>进了提示面（摘要）——没有这一条，"事件里没有明文"可能只是因为明文压根没进来
    ApprovalRequest published = tty.published().get(0);
    assertThat(published.summary()).contains(MARKER);
    // ★ 判别性：事件面、摘要面、id 都不得带明文（往 payload 里塞 args/summary 或把 digest 换成明文，本条转红）
    assertThat(requestedEvent.payload()).doesNotContain(MARKER);
    assertThat(decidedEvent.payload()).doesNotContain(MARKER);
    assertThat(published.digest()).doesNotContain(MARKER);
    assertThat(published.id()).doesNotContain(MARKER);
    // 事件与请求同一条（id 一致，correlationId 以审批 id 串起 requested/decided）
    assertThat(decidedEvent.correlationId()).isEqualTo(published.id());
    assertThat(requestedEvent.correlationId()).isEqualTo(published.id());
  }

  @Test
  void defaultDigestIsStablePerArgumentsAndNeverPlaintext() {
    ToolContext first =
        ApprovalStubs.context(
            AccessToken.SYSTEM, Map.of("command", "systemctl restart nginx", "cwd", "/srv"));
    ToolContext sameArgumentsOtherOrder =
        ApprovalStubs.context(
            AccessToken.SYSTEM, Map.of("cwd", "/srv", "command", "systemctl restart nginx"));
    ToolContext other =
        ApprovalStubs.context(AccessToken.SYSTEM, Map.of("command", "systemctl stop nginx"));

    String digest = ToolCallAuthorizer.defaultDigest(first);
    assertThat(ToolCallAuthorizer.defaultDigest(sameArgumentsOtherOrder)).isEqualTo(digest);
    assertThat(ToolCallAuthorizer.defaultDigest(other)).isNotEqualTo(digest);
    assertThat(digest).startsWith("sha256:").doesNotContain("systemctl");
  }

  // ---------------------------------------------------------------- T10

  @Test
  void denyGateIsTerminalAndNeverAsksTheChannels() {
    PendingApprovals pending = new PendingApprovals();
    StubChannel tty = new StubChannel("tty").thenAnswer(ApprovalDecision.APPROVE_ONCE);
    StubTool tool = bashTool();
    ToolRegistry registry = ApprovalStubs.registryWith(tool);
    // 生产形态的完整链：配置黑名单 → 会话放行 → 落到人
    ApprovalCoordinator coordinator =
        new ApprovalCoordinator(
            List.of(
                new DenyGate(req -> req.classKey().startsWith("bash:blocked")),
                new AutoApproveGate(pending),
                new ConfirmGate()),
            List.of(tty),
            pending,
            Duration.ofSeconds(5),
            bus);
    ToolCallAuthorizer authorizer = ToolCallAuthorizer.of(new ToolExecutionGuard(), coordinator);
    tool.gate = new ToolGate.Ask("bash:blocked:curl-sh", "下载即执行");

    ToolResult result =
        authorizer.execute(registry, TOOL, ApprovalStubs.context(AccessToken.SYSTEM));

    assertThat(result.code()).isEqualTo(ToolCallAuthorizer.APPROVAL_DENIED);
    assertThat(tool.calls()).isZero();
    // ★ 判别性：快判命中 ⇒ 不碰通道（DenyGate 改成走通道，三个计数立刻转红）
    assertThat(tty.availableCalls()).isZero();
    assertThat(tty.publishCalls()).isZero();
    assertThat(tty.awaitCalls()).isZero();
    assertThat(pending.pending()).isEmpty();
    assertThat(events.quietWithout(ApprovalEventTypes.REQUESTED, Duration.ofMillis(300))).isTrue();
    // 但它仍是"有决定"：谁拒的必须可查（by=闸名）
    Event decidedEvent =
        events.awaitAtLeast(ApprovalEventTypes.DECIDED, 1, Duration.ofSeconds(2)).stream()
            .findFirst()
            .orElseThrow();
    JsonNode decided = ApprovalStubs.payload(decidedEvent);
    assertThat(decided.get("decision").asText()).isEqualTo("DENY");
    assertThat(decided.get("by").asText()).isEqualTo(DenyGate.NAME);
  }

  // ---------------------------------------------------------------- 顺序：硬拒不进审批

  @Test
  void permissionDenialHappensBeforeApprovalAndIsNotWashedByIt() {
    PendingApprovals pending = new PendingApprovals();
    StubChannel tty = new StubChannel("tty").thenAnswer(ApprovalDecision.APPROVE_ONCE);
    ToolCallAuthorizer authorizer =
        ToolCallAuthorizer.of(
            new ToolExecutionGuard(), coordinator(pending, List.of(tty), Duration.ofSeconds(2)));
    StubTool sensitive =
        new StubTool("write_file", ToolSpec.level(AccessToken.DEFAULT, true, false));
    sensitive.gate = new ToolGate.Ask("tool:ask:write_file", "写文件");
    ToolRegistry registry = ApprovalStubs.registryWith(sensitive);

    AgentPermissionSet noSensitive =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allowAll()
            .sensitiveAllowed(false)
            .destructiveAllowed(true)
            .build();
    ToolResult denied =
        authorizer.execute(
            registry,
            "write_file",
            new ToolContext(AccessToken.DEFAULT, noSensitive, Map.of(), Map.of()));

    // 权限硬拒在前：审批面根本不该被碰（顺序颠倒 ⇒ 通道计数转红 = "审批把硬拒洗白"）
    assertThat(denied.code()).isEqualTo(ToolExecutionGuard.DENIED);
    assertThat(tty.availableCalls()).isZero();
    assertThat(tty.publishCalls()).isZero();
    assertThat(sensitive.calls()).isZero();

    // ★ 反面对照：同一工具、同一闸位，只把敏感放行位打开 ⇒ 走到审批并放行
    ToolResult allowed =
        authorizer.execute(registry, "write_file", ApprovalStubs.context(AccessToken.DEFAULT));
    assertThat(allowed.success()).isTrue();
    assertThat(sensitive.calls()).isEqualTo(1);
    assertThat(tty.awaitCalls()).isEqualTo(1);
  }

  // ---------------------------------------------------------------- T11：闸位自报 null

  @Test
  void nullGateIsCommandBlockedAndBodyNeverRuns() {
    PendingApprovals pending = new PendingApprovals();
    StubChannel tty = new StubChannel("tty").thenAnswer(ApprovalDecision.APPROVE_ONCE);
    StubTool tool = bashTool();
    ToolRegistry registry = ApprovalStubs.registryWith(tool);
    ToolCallAuthorizer authorizer =
        ToolCallAuthorizer.of(
            new ToolExecutionGuard(), coordinator(pending, List.of(tty), Duration.ofSeconds(2)));
    tool.nullGate();

    ToolResult result =
        authorizer.execute(registry, TOOL, ApprovalStubs.context(AccessToken.SYSTEM));

    // 闸位 null = 工具违约：既不是"默认放行"，也不是"要不要问人"——按 fail-closed 一律硬拒
    assertThat(result.code()).isEqualTo(ToolCallAuthorizer.COMMAND_BLOCKED);
    assertThat(tool.calls()).isZero();
    // ★ 判别性：null 不得被当成 ALLOW（改成 ALLOW，上面两行转红）；"问人"也不是它的语义（改成 Ask，通道计数转红）
    assertThat(tty.availableCalls()).isZero();
    assertThat(tty.publishCalls()).isZero();
    assertThat(tty.awaitCalls()).isZero();
    assertThat(pending.pending()).isEmpty();

    // ★ 反面对照：同一夹具、同一工具，把闸位给回 Ask 且人放行 ⇒ 工具体真的跑了（"没跑"来自 null 闸位，不是夹具坏）
    tool.gate = new ToolGate.Ask(CLASS_KEY, SUMMARY);
    assertThat(
            authorizer.execute(registry, TOOL, ApprovalStubs.context(AccessToken.SYSTEM)).success())
        .isTrue();
    assertThat(tool.calls()).isEqualTo(1);
    assertThat(tty.awaitCalls()).isEqualTo(1);
  }

  // ---------------------------------------------------------------- T12：窗口内人答得上

  @Test
  void pendingBackedChannelLetsTheHumanAnswerInsideTheWindow() throws InterruptedException {
    PendingApprovals pending = new PendingApprovals();
    PendingBackedChannel tty = new PendingBackedChannel("tty", pending);
    StubTool tool = bashTool();
    ToolRegistry registry = ApprovalStubs.registryWith(tool);
    ToolCallAuthorizer authorizer =
        ToolCallAuthorizer.of(
            new ToolExecutionGuard(), coordinator(pending, List.of(tty), Duration.ofSeconds(3)));
    tool.gate = new ToolGate.Ask(CLASS_KEY, SUMMARY);

    // "人"（另一线程）：看到请求 80 ms 后答"本次放行"——答复写进登记表，通道由条件变量唤醒
    AtomicBoolean answered = new AtomicBoolean();
    Thread answerer =
        Thread.ofVirtual()
            .start(
                () -> {
                  ApprovalRequest published = tty.awaitPublished(Duration.ofSeconds(2));
                  ApprovalStubs.sleepQuietly(80L);
                  answered.set(
                      pending.decide(published.id(), ApprovalDecision.APPROVE_ONCE, "tty"));
                });

    ToolResult result =
        authorizer.execute(registry, TOOL, ApprovalStubs.context(AccessToken.SYSTEM));
    answerer.join(2_000L);

    assertThat(answered.get()).isTrue();
    assertThat(result.success()).isTrue();
    assertThat(tool.calls()).isEqualTo(1);
    assertThat(tty.awaitCalls()).isEqualTo(1);
    // ★ 判别性：人是在"窗口内"被等到的——通道侧确实等了这一段（把编排器传给 await 的 wait 改成 1 ms，
    //   通道立刻返回空、这次调用挂到超时 ⇒ 上面三行与本行一起转红）
    assertThat(tty.awaitElapsedMs()).isGreaterThanOrEqualTo(50L);
  }

  // ---------------------------------------------------------------- T13：摘要的类别碰撞

  @Test
  void digestSeparatesValuesThatRenderAlikeAndKeepsContainersStable() {
    // 渲染成同一串、其实是两回事（评审实测曾同摘）
    String textOne =
        ToolCallAuthorizer.defaultDigest(
            ApprovalStubs.context(AccessToken.SYSTEM, Map.of("a", "1")));
    String numberOne =
        ToolCallAuthorizer.defaultDigest(ApprovalStubs.context(AccessToken.SYSTEM, Map.of("a", 1)));
    assertThat(numberOne).isNotEqualTo(textOne);

    String textList =
        ToolCallAuthorizer.defaultDigest(
            ApprovalStubs.context(AccessToken.SYSTEM, Map.of("x", "[a, b]")));
    String realList =
        ToolCallAuthorizer.defaultDigest(
            ApprovalStubs.context(AccessToken.SYSTEM, Map.of("x", List.of("a", "b"))));
    assertThat(realList).isNotEqualTo(textList);

    // 同一逻辑参数换个容器实现 ⇒ 同一摘要（标记按类别而非实现类名，否则对账口径会碎）
    String arrayList =
        ToolCallAuthorizer.defaultDigest(
            ApprovalStubs.context(
                AccessToken.SYSTEM, Map.of("x", new ArrayList<>(List.of("a", "b")))));
    assertThat(arrayList).isEqualTo(realList);
  }
}
