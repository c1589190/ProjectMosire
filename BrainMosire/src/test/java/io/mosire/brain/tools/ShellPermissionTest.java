package io.mosire.brain.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.context.BasicContextAssembler;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.runtime.AgentPipeline;
import io.mosire.brain.runtime.EventTypes;
import io.mosire.brain.runtime.StopReason;
import io.mosire.brain.runtime.TurnResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code bash} 的权限门禁契约（P2-5 / T15）：把既有的门禁链（{@link AgentPipeline} → {@link ToolExecutionGuard} →
 * {@code PermissionChecker}）对 {@link ShellTool} <b>钉住</b>。
 *
 * <p><b>为什么是三个状态而不是一个</b>：{@code PermissionChecker.denialReason} 的分支顺序是硬事实——只读 → denied 名单 → <b>白名单
 * deny-default</b> → 身份级别 → <b>sensitive/destructive 需显式放行</b>。前几个分支任一先命中，后面的就永远走不到。 故"空白名单 →
 * 被拒"证明不了"破坏性工具默认不可用"（那是白名单分支的功劳）；本类按 R8 逐条把拒绝<b>归因</b>到预期分支：
 *
 * <ol>
 *   <li>guest（白名单<b>刻意</b>含 bash）→ 身份级别分支；
 *   <li>DEFAULT 且 bash 已入白名单、未显式放行 → 破坏性需显式放行分支（<b>"破坏性工具默认不可用"的真正保证</b>）；
 *   <li>显式放行 → 全分支通过，调用真的到达工具执行。
 * </ol>
 *
 * <p><b>两处观测点，各有各的不可替代性</b>：
 *
 * <ul>
 *   <li>管线（{@link AgentPipeline}）：唯一能证明"拒绝真的产出 {@code permission.denied} 审计事件"的层——该事件由管线在 {@code
 *       ToolExecutionGuard.DENIED.equals(result.code())} 时发出，payload 为 {@code
 *       {"tool":..,"reason":..}}， reason 即 {@code denialReason}
 *       原文，是"落在哪个分支"的可辨证据；同时它也是"模型看到的是失败的工具结果"这一语义的现场。
 *   <li>守卫（{@link ToolExecutionGuard}，同一权限集/同一注册表，非 mock）：管线把守卫返回的 {@link ToolResult}
 *       吞在内部（只留事件），拒绝路径上工具根本没被调到、也就没有工具侧结果——故{@code code == PERMISSION_DENIED} 这条断言
 *       由其侧直接取一次（拒绝路径无副作用：判定在工具执行之前）。
 * </ul>
 *
 * <p>全离线（只跑 {@code echo}）、用例自清（{@code bash -c "echo …"} 立即退出、不留后代），{@link Timeout} 兜底防挂死。
 */
class ShellPermissionTest {

  /** 出现在成功结果里 = 命令真的跑过（拒绝路径上绝不能出现）。 */
  private static final String RAN_SENTINEL = "SHELL-PERMISSION-RAN";

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private SqliteEventStore store;
  private EventBus bus;

  @BeforeAll
  static void requirePosix() {
    assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "仅 POSIX（用 bash）");
  }

  @BeforeEach
  void setUp() {
    store = SqliteEventStore.open(tempDir.resolve("events.db"));
    bus = new EventBus();
  }

  @AfterEach
  void tearDown() {
    bus.close();
    store.close();
  }

  /**
   * 状态 ① guest：白名单<b>刻意</b>含 bash，好把拒绝归因从"白名单 deny-default"分支移到"身份级别"分支——空白名单时白名单分支
   * 先拒，这条用例就退化成"白名单拒绝"的空转（R8 点名的失败形态）。
   */
  @Test
  @Timeout(30)
  void guestIsDeniedByIdentityLevelNotByWhitelist() throws Exception {
    AgentPermissionSet permissions =
        AgentPermissionSet.builder(AccessToken.GUEST).allow(ShellTool.NAME).build();
    ToolRegistry registry = new ToolRegistry();

    ShellCall call = runBash(registry, permissions, "echo " + RAN_SENTINEL);

    assertThat(call.turn().stopReason()).isEqualTo(StopReason.FINISHED);
    assertThat(guardVerdict(registry, permissions, "echo " + RAN_SENTINEL).code())
        .isEqualTo(ToolExecutionGuard.DENIED);
    assertThat(call.toolResult()).as("拒绝即终局：工具一次都不该被调到").isNull();
    assertThat(reasonOf(call)).contains("身份级别不足").doesNotContain("白名单");
  }

  /**
   * 状态 ② DEFAULT 身份、bash 已在白名单内，但未显式放行 sensitive/destructive → "需显式放行"分支。
   * 这条才是"破坏性工具默认不可用"的真正保证：进白名单 ≠ 破坏性放行，DEFAULT 身份本身也不构成放行。
   */
  @Test
  @Timeout(30)
  void whitelistedBashStillNeedsExplicitDestructiveAllowForDefaultToken() throws Exception {
    AgentPermissionSet permissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow(ShellTool.NAME).build();
    ToolRegistry registry = new ToolRegistry();

    ShellCall call = runBash(registry, permissions, "echo " + RAN_SENTINEL);

    assertThat(call.turn().stopReason()).isEqualTo(StopReason.FINISHED);
    assertThat(guardVerdict(registry, permissions, "echo " + RAN_SENTINEL).code())
        .isEqualTo(ToolExecutionGuard.DENIED);
    assertThat(call.toolResult()).as("拒绝即终局：工具一次都不该被调到").isNull();
    assertThat(reasonOf(call)).contains("破坏性操作，需显式放行").doesNotContain("白名单");
  }

  /**
   * 状态 ③ 显式放行（白名单含 bash + sensitiveAllowed + destructiveAllowed，身份级别足够）→ 全分支通过，调用真的到达工具执行。
   *
   * <p>刻意不用 {@code AgentPermissionSet.unrestricted(...)}／{@code system()}：它们放行一切，会绕开本用例要钉的那条路径。
   */
  @Test
  @Timeout(30)
  void explicitAllowLetsTheCallReachTheTool() {
    AgentPermissionSet permissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allow(ShellTool.NAME)
            .sensitiveAllowed(true)
            .destructiveAllowed(true)
            .build();
    ToolRegistry registry = new ToolRegistry();

    ShellCall call = runBash(registry, permissions, "echo " + RAN_SENTINEL);

    assertThat(call.toolResult().code()).isNotEqualTo(ToolExecutionGuard.DENIED);
    assertThat(call.toolResult().success()).isTrue();
    assertThat(call.toolResult().message()).contains("exitCode: 0").contains(RAN_SENTINEL);
    assertThat(call.deniedEvents()).as("放行路径不得产出 permission.denied").isEmpty();
    assertThat(call.executions()).isEqualTo(1);
  }

  /**
   * 跑一次真实管线（不 mock 权限）：脚本 = "模型要求执行 bash" + "模型收尾文本"。
   *
   * <p>注册进 registry 的是 {@link RecordingShellTool}——{@code name/description/jsonSchema/spec}
   * 全部原样委托给真实 {@link ShellTool}（门禁读到的就是它的权限元数据，被钉住的也是它），只额外记录结果与"是否真的执行到工具"。
   */
  private ShellCall runBash(ToolRegistry registry, AgentPermissionSet permissions, String command) {
    RecordingShellTool tool = new RecordingShellTool(new ShellTool(tempDir));
    registry.register(tool);
    FakeLlmClient llm =
        FakeLlmClient.with(
            LlmResponse.toolCall("c-1", ShellTool.NAME, Map.of("command", command)),
            LlmResponse.text("收尾"));
    AgentPipeline pipeline =
        new AgentPipeline(
            AgentConfig.builder("main").maxTurns(3).build(),
            llm,
            registry,
            new ToolExecutionGuard(),
            new BasicContextAssembler(),
            store,
            bus,
            permissions.grantedToken(),
            permissions);

    TurnResult turn = pipeline.run("执行一条命令");

    return new ShellCall(
        turn, tool.lastResult(), tool.executions(), query(EventTypes.PERMISSION_DENIED));
  }

  /**
   * 同一注册表/同一权限集上真实守卫的判定结果（拒绝路径无副作用：判定先于执行）。管线把守卫的 {@link ToolResult} 吞在内部，这是取到 {@code code ==
   * PERMISSION_DENIED} 的唯一现场。
   */
  private static ToolResult guardVerdict(
      ToolRegistry registry, AgentPermissionSet permissions, String command) {
    ToolContext context =
        new ToolContext(
            permissions.grantedToken(), permissions, Map.of(), Map.of("command", command));
    return new ToolExecutionGuard().execute(registry, ShellTool.NAME, context);
  }

  /** {@code permission.denied} 的 reason 原文（断言"落在哪个分支"的证据，不能只看"被拒了"）。 */
  private static String reasonOf(ShellCall call) throws Exception {
    assertThat(call.deniedEvents()).as("拒绝必须经管线产出 permission.denied 事件").hasSize(1);
    return JSON.readTree(call.deniedEvents().get(0).payload()).path("reason").asText();
  }

  private List<Event> query(String type) {
    return store.query(new EventQuery("", type, "", -1, 100));
  }

  /** 一次 bash 调用的观测面：回合结果 + 工具侧结果（未执行时为 null）+ 真实执行次数 + 拒绝事件。 */
  private record ShellCall(
      TurnResult turn, ToolResult toolResult, int executions, List<Event> deniedEvents) {}

  /**
   * {@link ShellTool} 的记录包装：权限元数据与目录/描述全部委托（门禁看到的就是真实 ShellTool 的 {@link ToolSpec} 与
   * schema），只额外记下"结果"与"是否真的落到工具执行"——拒绝路径上工具必须一次都没被调到。
   */
  private static final class RecordingShellTool implements AgentTool {

    private final ShellTool delegate;
    private ToolResult lastResult;
    private int executions;

    private RecordingShellTool(ShellTool delegate) {
      this.delegate = delegate;
    }

    @Override
    public String name() {
      return delegate.name();
    }

    @Override
    public String description() {
      return delegate.description();
    }

    @Override
    public Map<String, Object> jsonSchema() {
      return delegate.jsonSchema();
    }

    @Override
    public ToolSpec spec() {
      return delegate.spec();
    }

    @Override
    public ToolResult execute(ToolContext context) {
      executions++;
      lastResult = delegate.execute(context);
      return lastResult;
    }

    private ToolResult lastResult() {
      return lastResult;
    }

    private int executions() {
      return executions;
    }
  }
}
