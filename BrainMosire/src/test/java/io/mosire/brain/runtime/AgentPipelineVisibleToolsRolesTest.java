package io.mosire.brain.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.agent.AgentConfig;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.llm.ToolDef;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.context.BasicContextAssembler;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★ 可见面 = 判定面（设计 §4.8「一次计算，两条面消费」；不变量 A-3 / A-6 / A-7）——<b>管线一侧</b>。
 *
 * <p><b>这一条防的是什么</b>：角色若只加进判定面，模型会<b>看得见</b>一个一调就吃 {@code PERMISSION_DENIED} 的工具——烧 token 烧回合，
 * 而且正是"第二处真相"的形状（可见面与判定面各算各的）。所以管线组请求时必须对 {@code registry.list()} 施加<b>同一个</b> oracle （{@code
 * PermissionChecker.allows}）。
 *
 * <p><b>怎么观测"模型看得见什么"</b>：{@link LlmRequest#tools()} 就是发给模型的那份工具表——本类用记录型 LLM 客户端截下它，断言的是
 * <b>模型真的拿到了什么</b>，不是管线内部的某个中间量。
 *
 * <p><b>判别性（变异自证）</b>：把 {@code AgentPipeline.visibleTools()} 的过滤去掉（改回 {@code registry.list()}）⇒
 * {@link #roleGatedToolOnTheExplicitWhitelistIsStillInvisibleAndUncallable} 与 {@link
 * #destructiveToolOnTheWhitelistIsInvisibleAndUncallable} 双双转红（工具重新出现在 tools() 里）。
 */
class AgentPipelineVisibleToolsRolesTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private SqliteEventStore store;
  private EventBus bus;

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
   * ★ A-7 主判据（负向）：工具<b>被显式写进白名单</b>（{@code isToolAllowed == true}）、但角色不相交 ⇒ 它<b>不出现在</b>模型请求的
   * {@code tools()} 里；模型就算硬调它，也吃 {@code PERMISSION_DENIED} 且工具体一次都没跑。
   *
   * <p>"基础面 ∩ 自定义面 = 生效面"：自定义面只能<b>在基础面之内</b>收窄，不能把角色够不着的工具加回来。
   */
  @Test
  void roleGatedToolOnTheExplicitWhitelistIsStillInvisibleAndUncallable() {
    AtomicInteger gmRuns = new AtomicInteger();
    ToolRegistry registry = new ToolRegistry();
    registry.register(synthetic("gm_tool", Set.of("gm"), ToolSpec.DEFAULT, gmRuns));
    registry.register(synthetic("plain_tool", Set.of(), ToolSpec.DEFAULT, new AtomicInteger()));
    AgentPermissionSet permissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allow("gm_tool", "plain_tool")
            .roles(RoleSet.of("dm"))
            .build();
    assertThat(permissions.isToolAllowed("gm_tool"))
        .as("前提：白名单<b>确实</b>放行了它（否则这条用例退化成'没在白名单里'）")
        .isTrue();

    RecordingLlm llm = new RecordingLlm();
    llm.enqueue(LlmResponse.text("我先看看有什么工具"));
    AgentPipeline pipeline = pipeline(registry, llm, permissions);

    pipeline.run("你能做什么");

    assertThat(llm.toolNamesOfFirstRequest())
        .as("角色不相交 ⇒ 不得出现在模型看得见的工具表里（A-6：可见面 ⊆ 判定面）")
        .containsExactly("plain_tool");
    assertThat(llm.toolNamesOfFirstRequest()).doesNotContain("gm_tool");

    // 模型硬调：判定面必须是终局拒绝，且工具体不许跑
    RecordingLlm second = new RecordingLlm();
    second.enqueue(LlmResponse.toolCall("c-1", "gm_tool", Map.of()));
    second.enqueue(LlmResponse.text("好，我不调了"));
    AgentPipeline calling = pipeline(registry, second, permissions);
    TurnResult result = calling.run("调用 gm_tool");

    assertThat(gmRuns).as("被拒的工具体绝不许执行").hasValue(0);
    assertThat(result.stopReason()).isEqualTo(StopReason.FINISHED);
    List<Event> denied = query(EventTypes.PERMISSION_DENIED);
    assertThat(denied).hasSize(1);
    Map<String, Object> payload = payloadOf(denied.get(0));
    assertThat(payload).containsEntry("tool", "gm_tool");
    assertThat(payload.get("reason").toString()).contains("角色不相交");
    assertThat(toolResults()).anySatisfy(r -> assertThat(r).containsEntry("ok", false));
  }

  /**
   * A-3/A-6 的同形负向（第二个来源：工具的 {@code destructive} 三要素）：白名单放行、{@code destructiveAllowed=false} ⇒
   * 同样不可见、不可调。角色的兄弟维共用一个 oracle——可见面不是"角色专用过滤"，而是"判定面本身"。
   */
  @Test
  void destructiveToolOnTheWhitelistIsInvisibleAndUncallable() {
    AtomicInteger dangerRuns = new AtomicInteger();
    ToolRegistry registry = new ToolRegistry();
    registry.register(
        synthetic(
            "danger_tool", Set.of(), new ToolSpec(AccessToken.GUEST, false, true), dangerRuns));
    registry.register(synthetic("plain_tool", Set.of(), ToolSpec.DEFAULT, new AtomicInteger()));
    AgentPermissionSet permissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow("danger_tool", "plain_tool").build();

    RecordingLlm llm = new RecordingLlm();
    llm.enqueue(LlmResponse.text("看工具"));
    pipeline(registry, llm, permissions).run("有什么工具");

    assertThat(llm.toolNamesOfFirstRequest()).containsExactly("plain_tool");

    RecordingLlm forcing = new RecordingLlm();
    forcing.enqueue(LlmResponse.toolCall("c-9", "danger_tool", Map.of()));
    forcing.enqueue(LlmResponse.text("放弃"));
    pipeline(registry, forcing, permissions).run("直接调 danger_tool");

    assertThat(dangerRuns).hasValue(0);
    assertThat(payloadOf(query(EventTypes.PERMISSION_DENIED).get(0)).get("reason").toString())
        .contains("破坏性");
  }

  /**
   * A-2 回归钉（正向）：<b>没声明角色</b>的工具在<b>任何</b>调用者下行为逐字不变——调用者 ⊥ 时它照样可见、照样可调。
   *
   * <p>（判定式本身的逐字不变由 RA1 的 {@code PermissionCheckerTest} 钉着；这里钉的是"整条链路"：可见面过滤没有把无角色工具误杀。）
   */
  @Test
  void toolsWithoutRoleDeclarationsStayVisibleAndCallableEvenForABottomCaller() {
    AtomicInteger runs = new AtomicInteger();
    ToolRegistry registry = new ToolRegistry();
    registry.register(synthetic("plain_tool", Set.of(), ToolSpec.DEFAULT, runs));
    AgentPermissionSet bottomCaller =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow("plain_tool").build();
    assertThat(bottomCaller.roles()).isEqualTo(RoleSet.none());

    RecordingLlm llm = new RecordingLlm();
    llm.enqueue(LlmResponse.toolCall("c-1", "plain_tool", Map.of()));
    llm.enqueue(LlmResponse.text("调完了"));
    pipeline(registry, llm, bottomCaller).run("调 plain_tool");

    assertThat(llm.toolNamesOfFirstRequest()).containsExactly("plain_tool");
    assertThat(runs).as("无角色声明的工具对 ⊥ 调用者照常可调（空集 = 本维不表态）").hasValue(1);
    assertThat(query(EventTypes.PERMISSION_DENIED)).isEmpty();
  }

  /** 调用者角色与工具声明角色<b>相交</b> ⇒ 可见且可调（正向对照：上一条的"不可见"不是"角色门工具永远不可见"）。 */
  @Test
  void aCallerSharingTheDeclaredRoleSeesAndCallsTheTool() {
    AtomicInteger runs = new AtomicInteger();
    ToolRegistry registry = new ToolRegistry();
    registry.register(synthetic("gm_tool", Set.of("gm"), ToolSpec.DEFAULT, runs));
    AgentPermissionSet permissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allow("gm_tool")
            .roles(RoleSet.of("gm", "dm"))
            .build();

    RecordingLlm llm = new RecordingLlm();
    llm.enqueue(LlmResponse.toolCall("c-1", "gm_tool", Map.of()));
    llm.enqueue(LlmResponse.text("调完了"));
    pipeline(registry, llm, permissions).run("调 gm_tool");

    assertThat(llm.toolNamesOfFirstRequest()).containsExactly("gm_tool");
    assertThat(runs).hasValue(1);
    assertThat(query(EventTypes.PERMISSION_DENIED)).isEmpty();
  }

  /** 只读 / 桶不足等<b>其他维</b>同样在可见面生效（同一 oracle 的自然结果，一条端到端样本防止"只给角色接线"）。 */
  @Test
  void otherDecisionDimensionsAlsoNarrowTheVisibleFace() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(
        synthetic(
            "needs_system", Set.of(), ToolSpec.level(AccessToken.SYSTEM), new AtomicInteger()));
    registry.register(synthetic("plain_tool", Set.of(), ToolSpec.DEFAULT, new AtomicInteger()));
    AgentPermissionSet permissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow("needs_system", "plain_tool").build();

    RecordingLlm llm = new RecordingLlm();
    llm.enqueue(LlmResponse.text("看工具"));
    pipeline(registry, llm, permissions).run("有什么工具");

    assertThat(llm.toolNamesOfFirstRequest()).containsExactly("plain_tool");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private AgentPipeline pipeline(
      ToolRegistry registry, LlmClient llm, AgentPermissionSet permissions) {
    return new AgentPipeline(
        AgentConfig.builder("main").maxTurns(4).build(),
        llm,
        registry,
        new ToolExecutionGuard(),
        new BasicContextAssembler(),
        store,
        bus,
        permissions.grantedToken(),
        permissions);
  }

  /** 合成工具：名字 + 角色声明 + 三要素 + 执行计数（"被拒的工具体一次都没跑"的直接判据）。 */
  private static AgentTool synthetic(
      String name, Set<String> roles, ToolSpec spec, AtomicInteger runs) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return "合成工具 " + name;
      }

      @Override
      public Set<String> roles() {
        return roles;
      }

      @Override
      public ToolSpec spec() {
        return spec;
      }

      @Override
      public ToolResult execute(ToolContext context) {
        runs.incrementAndGet();
        return ToolResult.ok(name + ":ok");
      }
    };
  }

  private List<Event> query(String type) {
    return new ArrayList<>(store.query(new EventQuery("", type, "", -1, 200)));
  }

  private List<Map<String, Object>> toolResults() {
    List<Map<String, Object>> results = new ArrayList<>();
    for (Event event : query(EventTypes.TOOL_RESULT)) {
      results.add(payloadOf(event));
    }
    return results;
  }

  private static Map<String, Object> payloadOf(Event e) {
    try {
      return JSON.readValue(e.payload(), new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (Exception ex) {
      throw new AssertionError("事件 payload 解析失败: " + e.payload(), ex);
    }
  }

  /** 记录型 LLM：把第一次请求的 {@code tools()} 名字截下来，响应脚本交给内层 {@code FakeLlmClient} 语义。 */
  private static final class RecordingLlm implements LlmClient {

    private final Deque<LlmResponse> script = new ArrayDeque<>();
    private List<String> firstRequestToolNames = List.of();

    void enqueue(LlmResponse response) {
      script.addLast(response);
    }

    List<String> toolNamesOfFirstRequest() {
      return firstRequestToolNames;
    }

    @Override
    public String model() {
      return "recording-fake";
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      if (firstRequestToolNames.isEmpty()) {
        firstRequestToolNames = request.tools().stream().map(ToolDef::name).toList();
      }
      LlmResponse response = script.pollFirst();
      if (response == null) {
        throw new LlmException("RecordingLlm 脚本已耗空（疑似循环跑飞）");
      }
      return response;
    }
  }
}
