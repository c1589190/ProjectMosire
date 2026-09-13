package io.mosire.main.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.runtime.EventTypes;
import io.mosire.brain.subagent.SubagentInstance;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * W3b 真实子进程 E2E（本任务唯一的真实子进程 E2E——Task 6 验收口径）。
 *
 * <p>场景（离线、仅回环管道）：主 Agent（FakeLlm 编排脚本）经 {@code spawn_sub_agent} 派生<b>真实</b>子 Agent 子进程 （{@code
 * Main agent --id ... --parent-link}，父侧接管其 stdin/stdout 为 MCP stdio 链接）：
 *
 * <ol>
 *   <li>reader（只读模板 {@code [echo]}）：子体经 MCP 回环调用父级 echo 工具（tool.call/tool.result 落在子体事件库）；
 *   <li>grand（模板 {@code [echo, spawn_sub_agent]}，放行 destructive）：子体经 MCP 回环<b>成功</b>派生孙代——S5-A 起
 *       {@code spawn_sub_agent} 是 DEFAULT 级（此前 SYSTEM 级，子体必然被 {@code permission.denied} 挡下），
 *       开放的安全性由 manager 侧三道<b>锚到调用者</b>的闸承担；
 *   <li>孙代（同 grand 模板，真实子进程）：{@code depth=2}、{@code parentInstanceId=grand 实例}——身份穿透<b>两个</b> MCP
 *       跳的证据；它再派时 {@code depth=3 > 子深度上限 2}，被<b>深度闸</b>拒绝（拒绝类事件落在父级事件库、 子体侧 tool.result
 *       ok=false），父侧不留半个实例——"子体能派"与"派不出跑飞"同时钉住；
 *   <li>三个子体跑完脚本后<b>即退</b>（§2.2：不再阻塞等父侧关停信号），退栈前把自己的终局记录落进<b>自己的</b> events.db （§2.1）； 父侧不 kill
 *       任何一个，生命周期链自己走到 {@code finished} 且带终局字段（§2.3 规则 1）。
 * </ol>
 *
 * <p>断言面：父级事件库 = 主 Agent 的 spawn 工具调用/结果 + 三个子 Agent 的完整生命周期链（configured→…→finished，含
 * stopReason/turns/toolCalls/exitCode）+ 孙代越界的 {@code decision:DEPTH_LIMIT}；子级事件库（{@code
 * <dataDir>/subagents/<instanceId>/events.db}） = 各自的 tool.call/tool.result + 自己的终局记录。
 *
 * <p><b>判别性（设计 §四.1/§四.2，本轮变异靶）</b>：
 *
 * <ul>
 *   <li>变异回"链接形态保留 {@code whenClosed} 阻塞" ⇒ 子体答完不退栈、终局记录不落库、父侧永远停在 {@code RUNNING} ⇒ {@link
 *       #realChildSubprocessCallsParentToolsViaMcpAndFinishesOnItsOwn} 在 await 生命周期链上超时转红；
 *   <li>把子体落库挪到退栈之后（或删掉）⇒ 同一条 await（父侧判据 = 子库终局事件）转红；
 *   <li>把终局记录写成缺 {@code stopReason} 的老形态 ⇒ 子库记录断言与父侧 {@code FINISHED} 链双双转红。
 * </ul>
 *
 * <p>第二个用例把<b>异常终局</b>（§四.3 的第二个混淆项、§四.6 的退出码）钉在真进程上：子体引导期就死（配置根缺 {@code llm.*}） ⇒ 子库没有终局记录 ⇒ 父侧
 * {@code FAILED} + {@code reason=未留下终局记录} + <b>真实退出码 1</b>（不是编出来的 0）， 且模型面（{@code
 * list_sub_agents}/{@code wait_sub_agent}）能区分"跑完了"与"没留下记录"。
 */
class W3SubagentE2ETest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  @Test
  void realChildSubprocessCallsParentToolsViaMcpAndFinishesOnItsOwn() throws Exception {
    Path templates = tempDir.resolve("templates");
    Files.createDirectories(templates);
    writeTemplate(
        templates,
        "reader",
        List.of("echo"),
        List.of(step("echo", Map.of("text", "你好，子 Agent 报到")), step(null, "问候完成")));
    // S5-A：grand 放行 destructive（否则 spawn_sub_agent 连工具体都进不去）；脚本<b>自递归</b>——子体派一个同模板的
    // 孙代，孙代再派时撞深度闸（depth 3 > 子深度上限 2）。一条真链同时验"子体能派""跨度由身份定""越界被拒"三件事。
    writeTemplate(
        templates,
        "grand",
        List.of("echo", "spawn_sub_agent"),
        List.of(
            step("spawn_sub_agent", Map.of("templateId", "grand", "goal", "再派一层（应被深度闸拒绝）")),
            step("echo", Map.of("text", "派完孙代后仍可服务")),
            step(null, "完成")),
        true);

    FakeLlmClient mainLlm =
        FakeLlmClient.with(
            LlmResponse.toolCall(
                "m-1", "spawn_sub_agent", Map.of("templateId", "reader", "goal", "问候主流程")),
            LlmResponse.text("已派遣问候子 Agent"),
            LlmResponse.toolCall(
                "m-2", "spawn_sub_agent", Map.of("templateId", "grand", "goal", "派发自递归子体")),
            LlmResponse.text("已了解"));
    Path dataDir = tempDir.resolve("data");
    BootConfig config =
        new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0, templates);
    App app = App.start(config, mainLlm, List.of(echoTool()));
    try (app) {
      // W3b 接线验收：主 Agent 工具面含内置编排工具（含 B 块新增的等取口 wait_sub_agent）
      assertThat(app.runtime().registry().find("spawn_sub_agent")).isPresent();
      assertThat(app.runtime().registry().find("kill_sub_agent")).isPresent();
      assertThat(app.runtime().registry().find("list_sub_agents")).isPresent();
      assertThat(app.runtime().registry().find("wait_sub_agent")).isPresent();

      // 回合 1：主 Agent（脚本）spawn reader —— 真实子进程
      app.runtime().chat("请派一个问候子 Agent");
      SubagentInstance reader = awaitChild(app, i -> "reader".equals(i.templateId()));
      Path readerEvents = childEvents(dataDir, reader.instanceId());
      // 子体经 MCP 回环调用父级 echo：tool.call + tool.result（成功）落在子体事件库
      awaitEvent(readerEvents, EventTypes.TOOL_RESULT, "tool", "echo", "ok", true);
      // 往返原文钉住：result.message 是父侧 echo 工具的真实返回（模板脚本传入文本 → "echo:" 前缀）
      awaitEventContaining(
          readerEvents, EventTypes.TOOL_RESULT, "tool", "echo", "message", "echo:你好，子 Agent 报到");
      assertThat(eventsOf(readerEvents, EventTypes.TOOL_CALL))
          .extracting(e -> payload(e, "tool"))
          .contains("echo");
      assertThat(eventsOf(readerEvents, EventTypes.PERMISSION_DENIED)).isEmpty();

      // 回合 2：主 Agent（脚本）spawn grand —— 真实子进程
      app.runtime().chat("请派一个自递归探测子 Agent");
      SubagentInstance grand = awaitChild(app, i -> "grand".equals(i.templateId()));
      Path grandEvents = childEvents(dataDir, grand.instanceId());
      // 子体<b>成功</b>派孙代：父侧工具调用入口按 spec（DEFAULT 级）放行，manager 以调用者身份（grand，depth 1）派生
      awaitEvent(grandEvents, EventTypes.TOOL_RESULT, "tool", "spawn_sub_agent", "ok", true);
      // 派完仍可服务（S5-A 降级没有把这条链接弄坏）
      awaitEvent(grandEvents, EventTypes.TOOL_RESULT, "tool", "echo", "ok", true);

      // 孙代：真实子进程。depth=2（= maxDepth-1）+ parentInstanceId=grand ⇒ 身份穿透两个 MCP 跳的证据
      // （旧实现按 manager 的构造期 parentDepth 算，这一层会得到 depth=1 且可以无限繁殖下去）
      SubagentInstance grandchild =
          awaitChild(
              app,
              i ->
                  "grand".equals(i.templateId())
                      && grand.instanceId().equals(i.parentInstanceId()));
      assertThat(grandchild.instanceId()).isNotEqualTo(grand.instanceId());
      assertThat(grandchild.depth()).isEqualTo(2);
      assertThat(grandchild.parentInstanceId()).isEqualTo(grand.instanceId());

      // 孙代再派（depth 3 > 子深度上限 2）→ 深度闸拒绝：子体侧 tool.result ok=false、
      // 父侧 decision:DEPTH_LIMIT（agent=孙代 id ——"谁被拒了"，不是主 Agent）
      Path grandchildEvents = childEvents(dataDir, grandchild.instanceId());
      awaitEvent(grandchildEvents, EventTypes.TOOL_RESULT, "tool", "spawn_sub_agent", "ok", false);
      awaitDecision(dataDir, "DEPTH_LIMIT", grandchild.instanceId());

      // 父侧实例账：只有三个（reader / grand / 孙代），被拒的那次不留半个实例
      assertThat(app.subagents()).hasSize(3);

      // §2.2 即退 + §2.3 规则 1：主 Agent <b>一次 kill 都没发</b>，三个子体跑完脚本自行退出；父侧按"子库终局事件的有无"判终局
      // ——链自己走到 finished 且带 stopReason/turns/toolCalls/exitCode。
      // 判别性：变异回"链接形态保留 whenClosed 阻塞" ⇒ 子体永不退栈 ⇒ 本 await 超时转红（本轮变异靶 §四.1）。
      for (SubagentInstance child : List.of(reader, grand, grandchild)) {
        awaitLifecycleActions(
            dataDir, child.instanceId(), List.of("configured", "spawning", "running", "finished"));

        // 父侧终局事件（§2.4 的持久面）：停因来自子体自己落的那条记录，退出码来自真句柄（不是编的 0）
        Map<String, Object> terminal = terminalEventPayload(dataDir, child.instanceId());
        assertThat(terminal)
            .as("父侧终局事件 id=%s", child.instanceId())
            .containsEntry("action", "finished")
            .containsEntry("stopReason", "FINISHED")
            .doesNotContainKey("reason");
        assertThat(terminal).containsKeys("turns", "toolCalls", "exitCode");
        assertThat((Integer) terminal.get("turns")).isPositive();
        assertThat((Integer) terminal.get("toolCalls")).isPositive();
        assertThat((Integer) terminal.get("exitCode"))
            .as("即退路径的退出码 = 真句柄给的 0（子进程已退出，不是编出来的）")
            .isZero();

        // 子体自己库里的终局记录（§2.1）——父侧那条 FINISHED 的判据本身；变异挪到退栈后/删掉 ⇒ 上面 await 与这里双双转红
        Map<String, Object> ownRecord =
            awaitChildTerminalRecord(childEvents(dataDir, child.instanceId()), child.instanceId());
        assertThat(ownRecord)
            .as("子库终局记录 id=%s", child.instanceId())
            .containsEntry("action", "finished")
            .containsEntry("stopReason", "FINISHED");
        assertThat(ownRecord).containsKeys("turns", "toolCalls");
      }

      // §2.6：对已经是终态的子体再 kill ⇒ 如实回"已是终态"，不谎报"已终止"（真进程路径同口径）
      ToolResult reKill = kill(app, reader.instanceId());
      assertThat(reKill.success()).isTrue();
      assertThat(reKill.message()).contains("已是终态").contains("FINISHED");

      // §2.5 + C 块：等取口对已终态实例立即返回终局字段（模型面能看到"为什么停"）
      Map<String, Object> waited = jsonBody(waitFor(app, reader.instanceId(), 0));
      assertThat(waited)
          .containsEntry("status", "FINISHED")
          .containsEntry("stopReason", "FINISHED");
      assertThat(waited).containsKeys("turns", "toolCalls", "exitCode", "waitedMillis");

      // 主 Agent 父级事件库：spawn 工具调用/结果（脚本驱动、成功）+ 越权类事件为零
      // （孙代的深度拒绝发的是 decision:DEPTH_LIMIT，不是 permission.denied）
      try (SqliteEventStore mainStore = SqliteEventStore.open(dataDir.resolve("events.db"))) {
        List<Event> spawnCalls =
            mainStore.query(new EventQuery("main", EventTypes.TOOL_CALL, "", -1, 100));
        assertThat(spawnCalls)
            .extracting(e -> payload(e, "tool"))
            .contains("spawn_sub_agent", "spawn_sub_agent");
        List<Event> spawnResults =
            mainStore.query(new EventQuery("main", EventTypes.TOOL_RESULT, "", -1, 100));
        assertThat(spawnResults)
            .filteredOn(e -> "spawn_sub_agent".equals(payload(e, "tool")))
            .extracting(e -> payload(e, "ok"))
            .allMatch(Boolean.TRUE::equals);
        assertThat(
                mainStore.query(new EventQuery("main", EventTypes.PERMISSION_DENIED, "", -1, 100)))
            .isEmpty();
      }
    }
  }

  /**
   * §2.3 规则 2 的真进程形态（§四.3 第二个混淆项 + §四.6）：子体<b>引导期就死</b>（配置根里没有 {@code llm.*} ⇒ {@code
   * E_LLM_CONFIG_MISSING}）⇒ 它一条终局记录都没留下 ⇒ 父侧记 {@code FAILED} + {@code reason=未留下终局记录} + <b>真实退出码
   * 1</b>，且模型面（{@code list_sub_agents}/{@code wait_sub_agent}）能区分"跑完了"与"没留下记录"。
   *
   * <p><b>为什么这就是 §四.3 的判别点</b>：同一条链上，"正常跑完"（上一个用例）父侧是 {@code FINISHED} + {@code stopReason}，这里父侧是
   * {@code FAILED} 且<b>没有</b> {@code stopReason}——两个混淆项（跑完了 / 没留下记录）分开验，模型可见面 如实分叉。若父侧退回"RUNNING
   * 退出一律记 FINISHED"（旧口径），本用例转红。
   *
   * <p><b>§四.6 的判别点</b>：断言 {@code exitCode == 1}——把 {@code exitCodeOf} 写成"拿不到补 0"/恒 0（看着无害的改法）⇒
   * 本用例转红；也能抓到"读子库记录时把退出码写成常量"的改法。
   */
  @Test
  void childThatDiesBeforeLeavingATerminalRecordIsFailedAndVisibleOnTheModelFace()
      throws Exception {
    Path templates = tempDir.resolve("templates");
    Files.createDirectories(templates);
    writeTemplate(templates, "broken", List.of(), List.of());
    Path dataDir = tempDir.resolve("data");
    // 配置根：只有密钥、没有 llm.*——生产真模型形态（装配层把配置根交给子体）下，子体引导期即失败：
    // stderr 一行 + 非零退出，chat 从未开始 ⇒ 子库终局记录必然缺席（§2.3 规则 2 的真实触发形态）
    Path configRoot = tempDir.resolve("config-root");
    Files.createDirectories(configRoot);
    Files.writeString(
        configRoot.resolve("config.json"), "{\"keys\":{\"local\":\"sk-sentinel-not-a-real-key\"}}");
    BootConfig config =
        new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0, templates);
    FakeLlmClient mainLlm =
        FakeLlmClient.with(
            LlmResponse.toolCall(
                "m-1", "spawn_sub_agent", Map.of("templateId", "broken", "goal", "注定失败")),
            LlmResponse.text("已派遣"));
    App app = App.start(config, mainLlm, List.of(), configRoot);
    try (app) {
      app.runtime().chat("派一个必崩的子 Agent");
      SubagentInstance child = awaitChild(app, i -> "broken".equals(i.templateId()));

      // 规则 2：RUNNING 退出 + 子库无终局事件 ⇒ FAILED（不是 FINISHED——那正是旧口径的谎言）
      awaitLifecycleActions(
          dataDir, child.instanceId(), List.of("configured", "spawning", "running", "failed"));
      Map<String, Object> terminal = terminalEventPayload(dataDir, child.instanceId());
      assertThat(terminal).containsEntry("action", "failed").containsEntry("reason", "未留下终局记录");
      assertThat(terminal)
          .as("没留下终局记录 ⇒ 不编 stopReason（'未知'不许伪装成'正常完成'）")
          .doesNotContainKey("stopReason")
          .doesNotContainKey("turns")
          .doesNotContainKey("toolCalls");
      assertThat(terminal).containsEntry("exitCode", 1);

      // 子库确实没有终局记录（父侧的 FAILED 不是读库失败造成的假象——库在，记录不在）
      assertThat(Files.isRegularFile(childEvents(dataDir, child.instanceId())))
          .as("子体已建库（引导期在模板装载之后建库）")
          .isTrue();
      assertThat(childTerminalRecord(childEvents(dataDir, child.instanceId()), child.instanceId()))
          .isNull();

      // C 块模型面：等取口如实回 FAILED + 无 stopReason（模型据此区分"跑完了"与"没留下记录"）
      Map<String, Object> waited = jsonBody(waitFor(app, child.instanceId(), 0));
      assertThat(waited)
          .containsEntry("status", "FAILED")
          .containsEntry("exitCode", 1)
          .doesNotContainKey("stopReason");
      // list 行同口径（异常终局在模型面可见——C 块的落点）
      Map<String, Object> row =
          listRows(app).stream()
              .filter(r -> child.instanceId().equals(r.get("instanceId")))
              .findFirst()
              .orElseThrow(() -> new AssertionError("list 里应有该子体: " + child.instanceId()));
      assertThat(row)
          .containsEntry("status", "FAILED")
          .containsEntry("exitCode", 1)
          .doesNotContainKey("stopReason");
    }
  }

  private AgentTool echoTool() {
    return new AgentTool() {
      @Override
      public String name() {
        return "echo";
      }

      @Override
      public String description() {
        return "原样返回输入文本";
      }

      @Override
      public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string")));
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok("echo:" + context.arguments().getOrDefault("text", ""));
      }
    };
  }

  private ToolResult kill(App app, String instanceId) {
    return callTool(app, "kill_sub_agent", Map.of("instanceId", instanceId));
  }

  private ToolResult waitFor(App app, String instanceId, long timeoutMs) {
    return callTool(
        app, "wait_sub_agent", Map.of("instanceId", instanceId, "timeoutMs", timeoutMs));
  }

  /**
   * 经主 Agent 的 guard 出口调用编排工具（与主 Agent 管线执行工具相同的路径）。
   *
   * <p>身份必须与生产同源（{@code AgentPipeline} 装配的 main 身份；本夹具无 {@code CommandModeHolder} ⇒ {@code
   * FULL}）：血缘判定按 identity 查表，4 参构造的 {@code UNKNOWN} 解析不出 callerPath ⇒ kill 一律 {@code
   * SUBTREE_DENIED}。
   */
  private ToolResult callTool(App app, String tool, Map<String, Object> args) {
    return new ToolExecutionGuard()
        .execute(
            app.runtime().registry(),
            tool,
            new ToolContext(
                AccessToken.SYSTEM,
                AgentPermissionSet.system(),
                Map.of(),
                args,
                AgentIdentity.main(CommandMode.FULL)));
  }

  // ---- helpers ----

  private void writeTemplate(
      Path dir, String id, List<String> allowedTools, List<Map<String, Object>> script)
      throws Exception {
    writeTemplate(dir, id, allowedTools, script, false);
  }

  private void writeTemplate(
      Path dir,
      String id,
      List<String> allowedTools,
      List<Map<String, Object>> script,
      boolean destructiveAllowed)
      throws Exception {
    Map<String, Object> template =
        Map.ofEntries(
            Map.entry("id", id),
            Map.entry("description", "W3 E2E 模板 " + id),
            Map.entry("systemPrompt", "你是测试子 Agent（模板 " + id + "）。"),
            Map.entry("model", "fake"),
            Map.entry("token", "DEFAULT"),
            Map.entry("allowedTools", allowedTools),
            Map.entry("deniedTools", List.of()),
            Map.entry("destructiveAllowed", destructiveAllowed),
            Map.entry("sensitiveAllowed", false),
            Map.entry("readOnly", false),
            Map.entry("maxTurns", 10),
            Map.entry("maxToolCallsPerTurn", 5),
            Map.entry("timeBudgetSeconds", 120),
            Map.entry("quotaMaxTokens", 0),
            Map.entry("script", script));
    Files.writeString(dir.resolve(id + ".json"), JSON.writeValueAsString(template));
  }

  private static Map<String, Object> step(String toolCall, String text) {
    return toolCall == null
        ? Map.of("text", text)
        : Map.of("toolCall", toolCall, "args", Map.of("text", "忽略"), "text", "");
  }

  private static Map<String, Object> step(String toolCall, Map<String, Object> args) {
    return Map.of("toolCall", toolCall, "args", args, "text", "");
  }

  /** 轮询 manager 直到出现满足条件的实例（管理器因 spawn 完成即返回，读取时应已就绪）。 */
  private SubagentInstance awaitChild(App app, Predicate<SubagentInstance> match) throws Exception {
    SubagentInstance[] found = new SubagentInstance[1];
    await(
        () -> {
          found[0] = app.subagents().stream().filter(match).findFirst().orElse(null);
          return found[0] != null;
        },
        30_000,
        "等待子 Agent 出现");
    return found[0];
  }

  /** 轮询父级事件库里的 {@code decision} 事件（agent = 被拒的调用者）。 */
  private void awaitDecision(Path dataDir, String decision, String agentId) throws Exception {
    await(
        () -> {
          try (SqliteEventStore store = SqliteEventStore.open(dataDir.resolve("events.db"))) {
            return store.query(new EventQuery(agentId, EventTypes.DECISION, "", -1, 100)).stream()
                .anyMatch(e -> decision.equals(payload(e, "decision")));
          }
        },
        30_000,
        "等待 decision:" + decision + " agent=" + agentId);
  }

  /** 轮询某类型事件满足谓词（子体进程已落地后才出现——读取方与子体并发访问 WAL 事件库）。 */
  private void awaitEvent(Path db, String type, Map<String, Object> expected) throws Exception {
    await(
        () -> {
          List<Event> events = eventsOf(db, type);
          for (Event event : events) {
            boolean matches = true;
            for (Map.Entry<String, Object> entry : expected.entrySet()) {
              if (entry.getValue() == null) {
                if (payload(event, entry.getKey()) == null) {
                  matches = false;
                  break;
                }
              } else if (!entry.getValue().equals(payload(event, entry.getKey()))) {
                matches = false;
                break;
              }
            }
            if (matches) {
              return true;
            }
          }
          return false;
        },
        45_000,
        "等待事件 " + type + " " + expected);
  }

  private void awaitEvent(
      Path db, String type, String key, String value, String key2, Object value2) throws Exception {
    // Map.of/Map.entry 拒绝 null 值——约定 value2==null = 仅要求 key2 字段存在且值非空（匹配循环对 null 期望值走存在性分支）
    Map<String, Object> expected = new HashMap<>();
    expected.put(key, value);
    if (key2 != null) {
      expected.put(key2, value2);
    }
    awaitEvent(db, type, expected);
  }

  /** 轮询事件（type + key/value 精确匹配、key2 子串匹配——钉住拒绝方/往返原文的断言变体）。 */
  private void awaitEventContaining(
      Path db, String type, String key, Object value, String key2, String contained)
      throws Exception {
    await(
        () -> {
          for (Event event : eventsOf(db, type)) {
            Object actual2 = payload(event, key2);
            if (value.equals(payload(event, key))
                && actual2 != null
                && String.valueOf(actual2).contains(contained)) {
              return true;
            }
          }
          return false;
        },
        45_000,
        "等待事件 " + type + " " + key + "=" + value + " 且 " + key2 + " 含 [" + contained + "]");
  }

  /** 轮询某子 Agent 的生命周期动作序列（父级事件库）；超时消息带上<b>实际链</b>（失败要一眼看出差在哪条）。 */
  private void awaitLifecycleActions(Path dataDir, String childId, List<String> expected)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(30_000);
    List<String> actual = List.of();
    while (System.nanoTime() < deadline) {
      try (SqliteEventStore store = SqliteEventStore.open(dataDir.resolve("events.db"))) {
        actual = lifecycleActions(store, childId);
        if (actual.equals(expected)) {
          return;
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "超时（30000ms）: 等待生命周期链 " + childId + " = " + expected + "，实际 = " + actual);
  }

  /**
   * 轮询子体<b>自己</b>库里的终局记录（§2.1）——父侧判定的唯一判据本身；子进程刚退出，轮询容忍并发写。
   *
   * @return 记录 payload（{@code action=finished} 且带非空 {@code stopReason}）；超时未落库 ⇒ 断言失败
   */
  private Map<String, Object> awaitChildTerminalRecord(Path db, String instanceId)
      throws Exception {
    @SuppressWarnings("unchecked")
    Map<String, Object>[] found = new Map[1];
    await(
        () -> {
          found[0] = childTerminalRecord(db, instanceId);
          return found[0] != null;
        },
        30_000,
        "等待子库终局记录 " + instanceId);
    return found[0];
  }

  /** 子库里的终局记录（没有 ⇒ null）；口径与父侧读侧逐字一致：{@code action=finished} 且 {@code stopReason} 非空。 */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> childTerminalRecord(Path db, String instanceId) {
    if (!Files.isRegularFile(db)) {
      return null;
    }
    try (SqliteEventStore store = SqliteEventStore.open(db)) {
      for (Event event :
          store.query(
              new EventQuery(instanceId, EventTypes.AGENT_LIFECYCLE, instanceId, -1, 100))) {
        Map<String, Object> payload = JSON.readValue(event.payload(), Map.class);
        if ("finished".equals(payload.get("action"))
            && payload.get("stopReason") != null
            && !String.valueOf(payload.get("stopReason")).isBlank()) {
          return payload;
        }
      }
      return null;
    } catch (Exception e) {
      return null; // 库刚建/正被写：视为"尚未落库"，轮询会再来（与父侧读侧同口径）
    }
  }

  /** 父库里该子 Agent 的<b>最新</b>一条生命周期事件 payload（用例在 await 收束后调用 = 终局事件）。 */
  private static Map<String, Object> terminalEventPayload(Path dataDir, String childId) {
    try (SqliteEventStore store = SqliteEventStore.open(dataDir.resolve("events.db"))) {
      List<Event> events =
          store.query(new EventQuery(childId, EventTypes.AGENT_LIFECYCLE, childId, -1, 100));
      assertThat(events).as("生命周期事件非空: %s", childId).isNotEmpty();
      return payloadMap(events.get(0)); // 倒序（seq 降序）：第一条 = 最新
    } catch (Exception e) {
      throw new AssertionError("读父级终局事件失败: " + childId, e);
    }
  }

  /** {@code list_sub_agents} 的行（主 Agent 身份）——C 块的模型可见面。 */
  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> listRows(App app) {
    ToolResult listed = callTool(app, "list_sub_agents", Map.of());
    assertThat(listed.success()).as("list 应成功: %s", listed.message()).isTrue();
    try {
      return JSON.readValue(listed.message(), List.class);
    } catch (Exception e) {
      throw new AssertionError("list 返回体无法解析: " + listed.message(), e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> jsonBody(ToolResult result) {
    assertThat(result.success()).as("工具调用应成功: %s", result.message()).isTrue();
    try {
      return JSON.readValue(result.message(), Map.class);
    } catch (Exception e) {
      throw new AssertionError("工具返回体无法解析: " + result.message(), e);
    }
  }

  private static List<String> lifecycleActions(SqliteEventStore store, String childId) {
    List<Event> events =
        store.query(new EventQuery(childId, EventTypes.AGENT_LIFECYCLE, childId, -1, 100));
    List<String> actions = new ArrayList<>();
    for (int i = events.size() - 1; i >= 0; i--) {
      actions.add(String.valueOf(payload(events.get(i), "action")));
    }
    return actions;
  }

  private static List<Event> eventsOf(Path db, String type) {
    try (SqliteEventStore store = SqliteEventStore.open(db)) {
      return store.query(new EventQuery("", type, "", -1, 100));
    }
  }

  private static Path childEvents(Path dataDir, String instanceId) {
    return dataDir.resolve("subagents").resolve(instanceId).resolve("events.db");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> payloadMap(Event event) {
    try {
      return JSON.readValue(event.payload(), Map.class);
    } catch (Exception e) {
      throw new AssertionError("事件 payload 无法解析: " + event.payload(), e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Object payload(Event event, String key) {
    try {
      return JSON.readValue(event.payload(), Map.class).get(key);
    } catch (Exception e) {
      throw new AssertionError("事件 payload 无法解析: " + event.payload(), e);
    }
  }

  private static void await(BooleanSupplier condition, long timeoutMillis, String what)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("超时（" + timeoutMillis + "ms）: " + what);
  }
}
