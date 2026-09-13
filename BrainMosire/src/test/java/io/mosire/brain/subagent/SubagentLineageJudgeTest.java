package io.mosire.brain.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventWrite;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.runtime.EventTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D27 血缘判定（{@link ContextAccessJudge}）的判别性契约：<b>全部用真 {@link SubagentManager} 造实例</b>（构造路径要真—— 血缘
 * path 由 manager 在派发那一刻拼，手搓 record 证明不了这一点）。
 *
 * <p><b>探针的判别力前提</b>（设计 §七.3）：树里的模板<b>明确授了</b> {@code kill_sub_agent}/{@code
 * list_sub_agents}/{@code read_agent_context}，探针上下文直接取<b>实例自己的权限集</b>——于是"工具不在白名单/级别不够"不可能成为混淆项，
 * 拒绝只可能来自 subtree 那一条（这类"判定没被触达却读成通过"是本仓已登记的缺陷形态）。
 *
 * <p>全离线：{@link InProcessExecutor} 门闩阻塞（子体停在 RUNNING，kill/状态断言才有观测面）；不起真进程、不花 token。
 */
class SubagentLineageJudgeTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String READ = SubagentOrchestrationTools.READ_AGENT_CONTEXT;
  private static final String KILL = SubagentOrchestrationTools.KILL_SUB_AGENT;
  private static final String LIST = SubagentOrchestrationTools.LIST_SUB_AGENTS;

  @TempDir Path tempDir;

  private Path templateDir;
  private AgentTemplateStore templates;
  private SqliteEventStore store;
  private EventBus bus;
  private SubagentManager manager;
  private ToolRegistry registry;

  /** 门闩：子体停在 RUNNING（用完后在 tearDown 统一放行）。 */
  private final CountDownLatch release = new CountDownLatch(1);

  @BeforeEach
  void setUp() throws Exception {
    templateDir = Files.createDirectories(tempDir.resolve("templates"));
    // 模板授了三个被判定守着的工具（设计 §七.3）：拒绝不许来自"模板没给"这一维
    writeTemplate("reader", List.of(READ, KILL, LIST, "echo"));
    templates = new AgentTemplateStore(templateDir);
    templates.load();
    store = SqliteEventStore.open(tempDir.resolve("events.db"));
    bus = new EventBus();
    manager =
        new SubagentManager(
            templates,
            new InProcessExecutor((instanceId, config) -> release.await()),
            store,
            bus,
            AgentConfig.builder(AgentIdentity.MAIN_ID).build(),
            AgentPermissionSet.system(),
            0,
            () -> CommandMode.FULL,
            SubagentLimits.defaults());
    registry = new ToolRegistry();
    registry.registerAll(SubagentOrchestrationTools.of(manager));
  }

  @AfterEach
  void tearDown() {
    release.countDown();
    manager.close();
    bus.close();
    store.close();
  }

  // ---- §七.1 按段匹配：a1 读不到 a10 ----

  /**
   * <b>按段前缀匹配的判别性反例</b>（设计 §七.1）：{@code a1} 的调用者读不到"看着像 a1 分支"的目标。
   *
   * <p>{@code a10} 的实例化：拿 a1 的<b>整 id</b> 当模板名（合法模板 id：小写 + 短横线，长度 ≤64），再让 main 派一个——它的 path 以 a1 的
   * path 为<b>字符串</b>前缀，但下一个字符是 {@code -} 而不是 {@code /}。判定若写成 {@code startsWith(path(c))}（少了 {@code
   * + "/"}），这次读取会被放行 ⇒ 本用例转红。
   *
   * <p>反向对照（同一条码路、只换目标）：a1 读自己真正的子体 ⇒ 过判定并读到内容——否则本用例可能只是"怎么都拒"，判别力为零。
   */
  @Test
  void stringPrefixLookalikeBranchIsNotInTheSubtree() throws Exception {
    SubagentInstance a1 = spawnAsMain("reader", "a1 分支");
    String lookalikeTemplateId = a1.instanceId();
    writeTemplate(lookalikeTemplateId, List.of(READ));
    templates.reload();
    SubagentInstance lookalike = spawnAsMain(lookalikeTemplateId, "看着像 a1 的兄弟");

    // 前提成立才叫"判别性反例"：字符串前缀成立、按段前缀不成立
    assertThat(lookalike.parentInstanceId())
        .as("它是 main 派的，不是 a1 派的")
        .isEqualTo(AgentIdentity.MAIN_ID);
    assertThat(lookalike.lineagePath()).startsWith(a1.lineagePath() + "-");
    assertThat(lookalike.lineagePath()).doesNotStartWith(a1.lineagePath() + "/");

    ToolResult denied =
        call(READ, probe(a1, Map.of("target", lookalike.instanceId(), "view", "results")));
    assertThat(denied.success()).isFalse();
    assertThat(denied.code()).isEqualTo(ContextAccessJudge.SUBTREE_DENIED);
    assertThat(denied.message()).isEqualTo(ContextAccessJudge.OUT_OF_SCOPE_REASON);

    // 反向对照：a1 读自己的子体 ⇒ 判定放行（库真存在，读到内容）
    SubagentInstance a11 = spawnAs(a1, "reader", "a1-1");
    writeChildDb(a11.instanceId(), "a1-1 干完了");
    ToolResult allowed =
        call(READ, probe(a1, Map.of("target", a11.instanceId(), "view", "results")));
    assertThat(allowed.success()).as(allowed.message()).isTrue();
    assertThat(allowed.message()).contains("a1-1 干完了");
  }

  // ---- §七.2 拒因可分（两个混淆项分开验，不混成一条） ----

  /**
   * 同血脉 + <b>无能力位</b> ⇒ 拒因是能力位（{@code DENIED}），不是 subtree。
   *
   * <p>判别性：把能力位那一维删掉（只判血缘），本次调用会被放行（目标就在自己的子树里）⇒ 第一条断言转红。
   */
  @Test
  void ownSubtreeWithoutCapabilityBitIsDeniedForTheCapabilityReason() {
    SubagentInstance a1 = spawnAsMain("reader", "a1 分支");
    SubagentInstance a11 = spawnAs(a1, "reader", "a1-1");

    AgentPermissionSet withoutRead =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow("echo", LIST).build();
    ToolResult denied =
        call(
            READ,
            probeWith(a1, withoutRead, Map.of("target", a11.instanceId(), "view", "results")));

    assertThat(denied.success()).isFalse();
    assertThat(denied.code()).isEqualTo("PERMISSION_DENIED");
    assertThat(denied.message()).contains("未授予").contains(READ);
    // 与另一条拒因分开：这里不是血缘结论
    assertThat(denied.message()).doesNotContain("血缘");
    assertThat(denied.message()).isNotEqualTo(ContextAccessJudge.OUT_OF_SCOPE_REASON);
  }

  /**
   * 有能力位 + <b>不同血脉</b> ⇒ 拒因是 {@code SUBTREE_DENIED}，<b>不是</b>"工具不存在"/"级别不足"。
   *
   * <p>判别性：把 subtree 那一维删掉（只判能力位），探针（模板明确授了 {@code read_agent_context}）会被放行 ⇒ 第一条断言转红。
   * 顺带锁"两个面分开"：模型面只说"这一类被拒"——不含目标 path/id（细节在日志里）。
   */
  @Test
  void otherBranchWithCapabilityBitIsDeniedBySubtree() {
    SubagentInstance a1 = spawnAsMain("reader", "a1 分支");
    SubagentInstance a2 = spawnAsMain("reader", "a2 分支");
    SubagentInstance a11 = spawnAs(a1, "reader", "a1-1");

    // 探针 = a2 自己的权限集（含 read_agent_context）+ a2 的身份：能力位、级别都不是混淆项
    assertThat(a2.permissions().isToolAllowed(READ)).as("探针前提：模板真的授了读能力位").isTrue();
    ToolResult denied =
        call(READ, probe(a2, Map.of("target", a11.instanceId(), "view", "results")));

    assertThat(denied.success()).isFalse();
    assertThat(denied.code()).isEqualTo(ContextAccessJudge.SUBTREE_DENIED);
    assertThat(denied.message()).isEqualTo(ContextAccessJudge.OUT_OF_SCOPE_REASON);
    assertThat(denied.message()).doesNotContain("未授予").doesNotContain("级别不足");
    // 两个面分开：模型面不递 path / id / 别支的存在性（细节走日志）
    assertThat(denied.message())
        .doesNotContain(a11.lineagePath())
        .doesNotContain(a11.instanceId())
        .doesNotContain(a1.instanceId())
        .doesNotContain("/");
  }

  // ---- §七.4 self 的身份收紧 + 规则表的两头（未知目标 / 空 target） ----

  /**
   * {@code target=self} 只对 <b>main 或本 manager 已知实例</b>放行（设计 §3.3#4）：否则外部面（{@code external-mcp}）能借
   * self 直读主 Agent 自己的事件库——那条路过去由 SYSTEM 级别 + {@code noExport} 结构挡住，两者都已卸掉。
   *
   * <p>判别性：把 self 分支写成"一律放行"，后两条断言转红；写成"一律拒"，前两条转红。
   */
  @Test
  void selfIsOnlyAllowedForMainOrKnownInstancesOfThisManager() {
    SubagentInstance a1 = spawnAsMain("reader", "a1 分支");

    assertThat(
            ContextAccessJudge.judge(
                    probe(a1, Map.of("target", ContextAccessJudge.SELF)), manager, "self", READ)
                .allowed())
        .as("已知实例读自己 ⇒ 放行")
        .isTrue();
    assertThat(
            ContextAccessJudge.judge(
                    mainContext(Map.of("target", ContextAccessJudge.SELF)), manager, "self", READ)
                .allowed())
        .as("main 读自己 ⇒ 放行")
        .isTrue();

    for (AgentIdentity identity :
        List.of(
            AgentIdentity.external(),
            AgentIdentity.subagent("ghost-0", CommandMode.FULL, "谁也不是", 1),
            AgentIdentity.UNKNOWN)) {
      ToolContext context =
          new ToolContext(
              AccessToken.DEFAULT,
              probePermissions(),
              readConfig(),
              Map.of("target", ContextAccessJudge.SELF, "view", "results"),
              identity);
      ToolResult denied = call(READ, context);
      assertThat(denied.success()).as("identity=%s", identity.instanceId()).isFalse();
      assertThat(denied.code())
          .as("identity=%s", identity.instanceId())
          .isEqualTo(ContextAccessJudge.SUBTREE_DENIED);
      assertThat(denied.message()).isEqualTo(ContextAccessJudge.OUT_OF_SCOPE_REASON);
    }
  }

  /**
   * 规则表的两头按既有码逐字保留：{@code target} 空 ⇒ {@code INVALID_ARGUMENTS}；目标不是本进程已知实例 ⇒ {@code
   * UNKNOWN_INSTANCE}（"不存在"是事实，不是权限结论——对 main 也一样）。
   */
  @Test
  void blankTargetAndUnknownTargetKeepTheirOwnCodes() {
    SubagentInstance a1 = spawnAsMain("reader", "a1 分支");

    ToolResult blank = call(READ, probe(a1, Map.of("target", "   ", "view", "results")));
    assertThat(blank.success()).isFalse();
    assertThat(blank.code()).isEqualTo("INVALID_ARGUMENTS");

    ToolResult unknown = call(KILL, probe(a1, Map.of("instanceId", "ghost-0000")));
    assertThat(unknown.success()).isFalse();
    assertThat(unknown.code()).isEqualTo(ContextAccessJudge.UNKNOWN_INSTANCE);

    ToolResult unknownForMain = call(KILL, mainContext(Map.of("instanceId", "ghost-0000")));
    assertThat(unknownForMain.code()).isEqualTo(ContextAccessJudge.UNKNOWN_INSTANCE);
  }

  // ---- 规则表：解析不出的身份一律 fail-closed（含空 path） ----

  /**
   * 调用者 path 解析不出（不在 manager 里 / 父本身未落账 ⇒ 空串）⇒ <b>一切目标都拒</b>，包括 {@code self}。
   *
   * <p>这条是"父解析不出就记空串、照常派发"（{@link SubagentManager#lineagePathOf}）的兜底面：派发不拒，但判定面 fail-closed ——空
   * path 的子体读不到别人，也读不到"自己"（它没有可证明的身份）。
   *
   * <p>判别性：把空 path 当成"同血脉"（或让 {@code inSubtree} 对空串返回 true），后两条断言转红。
   */
  @Test
  void unresolvableCallerPathFailsClosedForEveryTarget() {
    SubagentInstance a1 = spawnAsMain("reader", "a1 分支");
    SubagentInstance orphan = spawnOrphan();

    assertThat(orphan.lineagePath()).as("父解析不出 ⇒ 记空串，但不拒派发").isEmpty();
    assertThat(orphan.parentInstanceId()).isEqualTo("ghost-parent");
    assertThat(orphan.permissions().isToolAllowed(READ)).as("探针前提：能力位在").isTrue();

    ToolResult denied =
        call(READ, probe(orphan, Map.of("target", a1.instanceId(), "view", "results")));
    assertThat(denied.success()).isFalse();
    assertThat(denied.code()).isEqualTo(ContextAccessJudge.SUBTREE_DENIED);

    ToolResult selfDenied =
        call(READ, probe(orphan, Map.of("target", ContextAccessJudge.SELF, "view", "results")));
    assertThat(selfDenied.success()).as("空 path 连'读自己'都不成立").isFalse();
    assertThat(selfDenied.code()).isEqualTo(ContextAccessJudge.SUBTREE_DENIED);
    assertThat(selfDenied.message()).isEqualTo(ContextAccessJudge.OUT_OF_SCOPE_REASON);
  }

  /**
   * <b>规则⑤</b>（目标 path 空 + 调用者可解析 ⇒ {@code SUBTREE_DENIED}）的判别性用例：<b>main 也动不了空 path 的实例</b>。
   *
   * <p>与上一条互补：那条验"空 path 当调用者"（规则④），这条验"空 path 当目标"。空 path 只可能由解析不出的父派生（{@link
   * SubagentManager#lineagePathOf}）⇒ 规则④/⑤ 之间<b>没有任何工具面</b>够得着它——manager 里 {@link #spawnOrphan()}
   * 的注释与本用例一起 钉住"此前 javadoc 说的'父照样能 kill'不成立"。拒因必须<b>不是</b> {@code UNKNOWN_INSTANCE}（它确实在 manager
   * 里）也不是能力位 {@code DENIED}（main 的探针权限集全授）。
   *
   * <p>唯一出口是关停面：{@code manager.kill(id)} 直呼 {@code terminate}（不经判定、不看 path）——用例末端验它仍能把实例收掉
   * （"读写面够不着" 不变成实例泄漏）。判别性（本轮变异靶）：把规则⑤删掉 ⇒ 空 path 落进 {@code inSubtree} 的空串检查、仍拒（同码同文案）⇒
   * <b>变异存活</b>， 如实记"规则⑤在行为上被⑦覆盖"；把 {@code inSubtree} 改成"任一侧空 = true"（放开 fail-closed）⇒ 本用例三条拒绝断言转红。
   */
  @Test
  void emptyTargetPathIsDeniedForEveryToolFaceEvenForMain() throws Exception {
    SubagentInstance orphan = spawnOrphan();
    assertThat(orphan.lineagePath()).as("前提：它是空 path 实例").isEmpty();
    assertThat(orphan.permissions().isToolAllowed(KILL)).as("探针前提：能力位在（拒因不许来自这一维）").isTrue();

    ToolResult killDenied = call(KILL, mainContext(Map.of("instanceId", orphan.instanceId())));
    assertThat(killDenied.success()).isFalse();
    assertThat(killDenied.code())
        .as("不是'不存在'（它在 manager 里）也不是能力位——是血缘那条")
        .isEqualTo(ContextAccessJudge.SUBTREE_DENIED)
        .isNotEqualTo(ContextAccessJudge.UNKNOWN_INSTANCE)
        .isNotEqualTo("PERMISSION_DENIED");
    assertThat(killDenied.message()).isEqualTo(ContextAccessJudge.OUT_OF_SCOPE_REASON);
    assertThat(manager.get(orphan.instanceId()).orElseThrow().status())
        .as("拒了就不许动到它")
        .isEqualTo(SubagentStatus.RUNNING);

    ToolResult readDenied =
        call(READ, mainContext(Map.of("target", orphan.instanceId(), "view", "results")));
    assertThat(readDenied.code()).isEqualTo(ContextAccessJudge.SUBTREE_DENIED);

    // 规则顺序（"先判身份再判能力位"）也钉住：能力位缺 + 目标 path 空 ⇒ 仍报血缘那条（⑤ 在 ⑥ 之前），
    // 不是"未授予 <工具>"——删掉⑤会让本次拒绝改用⑥的能力位码（可观测差异），这是⑤不冗余的那一半
    AgentPermissionSet withoutKill =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow("echo", READ, LIST).build();
    ToolResult killDeniedWithoutBit =
        call(
            KILL,
            new ToolContext(
                withoutKill.grantedToken(),
                withoutKill,
                readConfig(),
                Map.of("instanceId", orphan.instanceId()),
                AgentIdentity.main(CommandMode.FULL)));
    assertThat(killDeniedWithoutBit.code())
        .as("身份/血缘类先判：能力位缺也不改这条拒因")
        .isEqualTo(ContextAccessJudge.SUBTREE_DENIED)
        .isNotEqualTo("PERMISSION_DENIED");
    assertThat(killDeniedWithoutBit.message())
        .isEqualTo(ContextAccessJudge.OUT_OF_SCOPE_REASON)
        .doesNotContain("未授予");

    // list 是同一件事的范围形态：空 path 不在任何 subtree 里 ⇒ main 的列表里也看不到它
    List<String> seenByMain = ids(call(LIST, mainContext(Map.of())));
    assertThat(seenByMain).doesNotContain(orphan.instanceId());

    // 唯一出口 = 关停面（编排入口直呼 terminate，不经判定）：实例不会因为"工具面够不着"而泄漏
    assertThat(manager.kill(orphan.instanceId())).as("关停面仍然收得掉").isTrue();
    awaitStatus(orphan.instanceId(), SubagentStatus.KILLED);
  }

  // ---- kill / list 走同一条判定（V2/V3 的判据面） ----

  /**
   * {@code kill}/{@code list} 的拒因必须是"{@code a1-1 ∉ subtree(a2)}"，<b>不许</b>是"工具不存在"（V2）：兄弟分支 kill
   * 被血缘拒， 自己的子体放行，跳级处置（main 直接动孙代）也放行（V3）；{@code list} 只列自己的子树（别支的存在不外泄）。
   *
   * <p>判别性：把工具内判定换回 {@code caller == SYSTEM} 自查，a1 这条"自己的子体"会被拒 ⇒ 转红；把 list 的过滤去掉，a2 会看到 a1/a1-1 ⇒
   * 转红。
   */
  @Test
  void killAndListFollowTheSameSubtreeRule() throws Exception {
    SubagentInstance a1 = spawnAsMain("reader", "a1 分支");
    SubagentInstance a2 = spawnAsMain("reader", "a2 分支");
    SubagentInstance a11 = spawnAs(a1, "reader", "a1-1");

    ToolResult denied = call(KILL, probe(a2, Map.of("instanceId", a11.instanceId())));
    assertThat(denied.success()).isFalse();
    assertThat(denied.code()).isEqualTo(ContextAccessJudge.SUBTREE_DENIED);
    assertThat(denied.message()).isEqualTo(ContextAccessJudge.OUT_OF_SCOPE_REASON);
    assertThat(manager.get(a11.instanceId()).orElseThrow().status())
        .as("拒了就不许动到它")
        .isEqualTo(SubagentStatus.RUNNING);

    ToolResult killed = call(KILL, probe(a1, Map.of("instanceId", a11.instanceId())));
    assertThat(killed.success()).as(killed.message()).isTrue();
    awaitStatus(a11.instanceId(), SubagentStatus.KILLED);

    // 跳级处置：main 直接 kill a2 的子体（subtree 的直接结果，不是特例）
    SubagentInstance a21 = spawnAs(a2, "reader", "a2-1");
    ToolResult jump = call(KILL, mainContext(Map.of("instanceId", a21.instanceId())));
    assertThat(jump.success()).as(jump.message()).isTrue();

    List<String> seenByA1 = ids(call(LIST, probe(a1, Map.of())));
    assertThat(seenByA1)
        .as("a1 的 list = 自己 + 自己的子孙")
        .containsExactlyInAnyOrder(a1.instanceId(), a11.instanceId());
    List<String> seenByA2 = ids(call(LIST, probe(a2, Map.of())));
    assertThat(seenByA2)
        .as("a2 看不到 a1 与 a1-1 的存在")
        .containsExactlyInAnyOrder(a2.instanceId(), a21.instanceId());
    List<String> seenByMain = ids(call(LIST, mainContext(Map.of())));
    assertThat(seenByMain)
        .as("main 列全部")
        .containsExactlyInAnyOrder(
            a1.instanceId(), a2.instanceId(), a11.instanceId(), a21.instanceId());
  }

  // ---- fixtures ----

  private SubagentInstance spawnAsMain(String templateId, String goal) {
    return manager.spawn(
        new SubagentLaunchRequest(templateId, goal, null, null, null, null),
        AgentIdentity.main(CommandMode.FULL),
        AgentPermissionSet.system());
  }

  private SubagentInstance spawnAs(SubagentInstance caller, String templateId, String goal) {
    return manager.spawn(
        new SubagentLaunchRequest(templateId, goal, null, null, null, null),
        AgentIdentity.subagent(caller.instanceId(), caller.mode(), goal, caller.depth()),
        caller.permissions());
  }

  /**
   * 父身份解析不出的子体：{@code lineagePath=""}（{@link SubagentManager#lineagePathOf} 的"父 path 空 ⇒ 子 path
   * 空"）——这是 空 path 实例的<b>唯一</b>产生方式（父既不是 main，也不在本 manager 的实例表里）。派发不拒（设计选择），但判定面对它 fail-closed。
   */
  private SubagentInstance spawnOrphan() {
    return manager.spawn(
        new SubagentLaunchRequest("reader", "父身份不明", null, null, null, null),
        AgentIdentity.subagent("ghost-parent", CommandMode.FULL, "野爹", 1),
        AgentPermissionSet.system());
  }

  private ToolResult call(String toolName, ToolContext context) {
    AgentTool tool = registry.find(toolName).orElseThrow();
    return tool.execute(context);
  }

  /** 探针上下文：身份 = 该实例，权限集 = 该实例的<b>真实</b>权限集（宿主建链时绑的就是这两样）。 */
  private ToolContext probe(SubagentInstance caller, Map<String, Object> args) {
    return probeWith(caller, caller.permissions(), args);
  }

  private ToolContext probeWith(
      SubagentInstance caller, AgentPermissionSet permissions, Map<String, Object> args) {
    return new ToolContext(
        permissions.grantedToken(),
        permissions,
        readConfig(),
        args,
        AgentIdentity.subagent(caller.instanceId(), caller.mode(), "探针", caller.depth()));
  }

  private ToolContext mainContext(Map<String, Object> args) {
    return new ToolContext(
        AccessToken.SYSTEM,
        probePermissions(),
        readConfig(),
        args,
        AgentIdentity.main(CommandMode.FULL));
  }

  /** 一份"判定面全授"的权限集（探针用，避免"工具没给"成为混淆项）。 */
  private static AgentPermissionSet probePermissions() {
    return AgentPermissionSet.builder(AccessToken.DEFAULT)
        .allow(READ, KILL, LIST, "echo")
        .destructiveAllowed(true)
        .build();
  }

  private Map<String, Object> readConfig() {
    return Map.of(
        AgentContextReader.CONFIG_SUBAGENTS_ROOT, subagentsRoot().toString(),
        AgentContextReader.CONFIG_SELF_EVENTS_DB, tempDir.resolve("self-events.db").toString(),
        AgentContextReader.CONFIG_SELF_AGENT_ID, AgentIdentity.MAIN_ID);
  }

  private Path subagentsRoot() {
    return tempDir.resolve("subagents");
  }

  private void writeChildDb(String instanceId, String output) throws Exception {
    Path dir = Files.createDirectories(subagentsRoot().resolve(instanceId));
    try (SqliteEventStore child = SqliteEventStore.open(dir.resolve("events.db"))) {
      child.append(
          EventWrite.of(
              EventTypes.CONVERSATION_TURN,
              instanceId,
              "{\"input\":\"干活\",\"output\":\"" + output + "\"}",
              instanceId));
    }
  }

  private void writeTemplate(String id, List<String> allowedTools) throws Exception {
    Files.writeString(
        templateDir.resolve(id + ".json"),
        """
        {
          "id": "%s",
          "description": "血缘判定探针模板",
          "systemPrompt": "你是血缘判定探针",
          "model": "fake",
          "token": "DEFAULT",
          "allowedTools": %s,
          "deniedTools": [],
          "destructiveAllowed": true,
          "sensitiveAllowed": false,
          "readOnly": false,
          "maxTurns": 5,
          "maxToolCallsPerTurn": 5,
          "timeBudgetSeconds": 60,
          "quotaMaxTokens": 0
        }
        """
            .formatted(id, JSON.writeValueAsString(allowedTools)));
  }

  private List<String> ids(ToolResult listResult) {
    assertThat(listResult.success()).as(listResult.message()).isTrue();
    try {
      List<Map<String, Object>> rows = JSON.readValue(listResult.message(), List.class);
      return rows.stream().map(row -> String.valueOf(row.get("instanceId"))).toList();
    } catch (Exception e) {
      throw new AssertionError("list 结果无法解析: " + listResult.message(), e);
    }
  }

  private void awaitStatus(String instanceId, SubagentStatus expected) throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      SubagentInstance current = manager.get(instanceId).orElse(null);
      if (current != null && current.status() == expected) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError(
        "等待状态超时: "
            + instanceId
            + "，实际 "
            + manager.get(instanceId).map(SubagentInstance::status).orElse(null));
  }
}
