package io.mosire.main.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.mosire.agentlib.agent.AgentConfig;
import io.mosire.agentlib.config.FileConfigStore;
import io.mosire.agentlib.decision.DecisionCommitPort;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventWrite;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.ConfigApiKeySource;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmRouteLoader;
import io.mosire.agentlib.llm.ModelRoute;
import io.mosire.agentlib.llm.OpenAICompatibleLlmClient;
import io.mosire.agentlib.mcp.McpSourceBridge;
import io.mosire.agentlib.mcp.McpToolSource;
import io.mosire.agentlib.mcp.PipeMcpClientTransport;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.PermissionChecker;
import io.mosire.agentlib.plugin.ToolSource;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.brain.context.BasicContextAssembler;
import io.mosire.brain.context.ContextPolicy;
import io.mosire.brain.context.ContextSources;
import io.mosire.brain.decision.CrossProcessDecisionSubmitter;
import io.mosire.brain.decision.DecisionHistoryTools;
import io.mosire.brain.decision.DecisionWindows;
import io.mosire.brain.decision.ReleaseMarkingCommitPort;
import io.mosire.brain.permission.AgentRoles;
import io.mosire.brain.runtime.AgentPipeline;
import io.mosire.brain.runtime.AgentRuntime;
import io.mosire.brain.runtime.EventTypes;
import io.mosire.brain.runtime.TurnResult;
import io.mosire.brain.skills.SkillAccess;
import io.mosire.brain.skills.SkillCatalog;
import io.mosire.brain.skills.SkillPermissions;
import io.mosire.brain.subagent.AgentTemplate;
import io.mosire.brain.subagent.AgentTemplateStore;
import io.mosire.docs.DocNamespaces;
import io.mosire.main.app.BootConfig;
import io.mosire.main.decision.LocalDecisionCommitPort;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 子 Agent 进程引导（{@code Main agent --id <id> --template <t> --goal <g> [--templates-dir <d>]
 * [--data-dir <d>] [--config-dir <d>] [--parent-link]}）——计划 §4.4 的"子体进程即 runtime + MCP client"。
 *
 * <p><strong>进程形态</strong>：
 *
 * <ul>
 *   <li>无网关（不进 HTTP/AdminREST/A2A——任务口径"子进程内无网关"）；
 *   <li>模板装载（{@code --templates-dir}，缺省 {@code configs/agents}）→ 模板权限集构造 runtime（子体身份不是继承 父级——红线 R7
 *       的进程面：自身权限即模板三要素，父侧按同一模板权限集为子体服务）→ 事件库落在 {@code --data-dir/events.db}（父装配层经 {@code
 *       --data-dir <root>/<instanceId>} 隔离）；
 *   <li>LLM 形态由 {@code --config-dir} 二分（R-A2-1）：<b>缺席</b> = 离线/测试形态，逐字不变地走模板引导脚本（{@code
 *       template.scriptedFakeLlm()}，W3 E2E 与 smoke.sh 的复现支点）；<b>在场</b> = 真模型形态，按 {@code
 *       <config-dir>/config.json} 的 {@code llm.*} 构造 {@link OpenAICompatibleLlmClient}（见 {@link
 *       #realLlm}）。 两种形态下模板的 {@code maxTurns}/{@code maxToolCallsPerTurn}/{@code
 *       timeBudgetSeconds}/{@code quotaMaxTokens} 硬顶一律生效（R-A2-8：硬顶属于运行时，与 LLM 形态无关）。
 * </ul>
 *
 * <p><strong>父链接（{@code --parent-link}）</strong>：stdin/stdout 是父进程建好的 MCP stdio 链路（标准换行帧、 一帧一
 * JSON-RPC），本进程以 <b>MCP client</b> 身份接管（{@link PipeMcpClientTransport}——官方 StdioClientTransport 只
 * spawn 不接管流），经 {@link McpSourceBridge}（按本进程权限集的<b>判定面</b>过滤：命中 = {@link
 * io.mosire.agentlib.permission.PermissionChecker#allows} 放行）把父级暴露面同步进本地 registry。
 *
 * <p><strong>即退与终局记录（§2.1/§2.2，2026-09-13）</strong>：跑完目标回合后<b>不再</b>阻塞等"关停信号"（那会让子体答完还空转 ≥171 s，
 * 父侧全程看到 {@code RUNNING}）——{@code chat()} 返回 ⇒ 先把自己的终局（{@code agent.lifecycle/action=finished} +
 * {@code stopReason}/{@code turns}/{@code toolCalls}）<b>落进自己的
 * events.db</b>，再退栈退出。父侧以<b>这条记录的有无</b>判终局 （见 {@code SubagentManager.onChildExited}）：有 ⇒ {@code
 * FINISHED} + 停因，没有 ⇒ 异常终局（{@code 未留下终局记录}）——所以 落库必须发生在 {@code AgentRuntime.close()}
 * <b>之前</b>，且写失败要响亮（stderr + 非零退出），不许静默吞。 链接形态的关停路径（父侧 kill）因此变成：父侧先关它的 MCP server（子体读到
 * EOF/管道关闭），再走三层进程关停；子体来不及落库是<b>已知的诚实边界</b> ——父侧按 {@code KILLED} 记，不假装它交代过停因。
 *
 * <p><b>"即退"还要求传输侧线程守护化</b>：删掉 {@code whenClosed().get()} 只解决了"业务上不再等"，进程能退出还需要 JVM 里没有非守护线程
 * ——进站读线程会永久阻塞在不可中断的 {@code System.in} 读上（{@code closeGracefully()} 的 dispose 也中断不了它），非守护化会把 子进程钉在
 * exit 前（实测 thread dump：{@code main} 已返回、{@code DestroyJavaVM} 在等那个线程）。守护化落在 {@code
 * PipeMcpClientTransport}（见其类注释）。
 *
 * <p><strong>stdout 纪律</strong>：本进程 stdout = MCP 帧通道。所有可见输出只准走 stderr（启动即把 {@code System.out}
 * 与协议流解绑——帧用早期捕获的原始流，日志/错误经 stderr；使用期间任何窗口进程 若把信息写 {@code System.out} 都会污染协议帧，代码评审红线）。
 *
 * <p><strong>退出码语义（如实二分，勿写成"失败一律非零"）</strong>：<b>引导期</b>装配失败 ⇒ stderr + <b>非零退出</b>，绝不把异常焐成零退出 ——缺
 * {@code llm.*}（{@code E_LLM_CONFIG_MISSING}）、缺密钥（{@code E_KEY_MISSING}）、密钥引用形态不支持（{@code
 * E_REF_FORM_UNSUPPORTED}），见 {@link #realLlm} 的取密钥预检。而 <b>chat 期</b>的 LLM 失败（端点不可达 / 401 / 超时）由
 * {@link io.mosire.brain.runtime.AgentPipeline}（{@code :289-293}）收敛成 {@link
 * io.mosire.brain.runtime.StopReason#LLM_ERROR} 的<b>正常回合</b>——本类照常打印"回合完成"并 <b>exit
 * 0</b>，退出码不区分"模型坏了"。 这是<b>既有语义</b>（与主 Agent 一致；脚本假 LLM 排空时同样走这条路），父侧只能从回合停因 {@code stop=LLM_ERROR}
 * 分辨；改退出码属另一件事。
 */
public final class SubagentProcessMain {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 子体侧决策历史工具的供给源 id（与父侧同名工具分源：便于按源整组卸载与审计）。 */
  private static final String DECISION_HISTORY_TOOL_SOURCE_ID = "decision-history-local";

  private SubagentProcessMain() {}

  /**
   * 运行一次子 Agent 进程：{@code chat()} 返回 ⇒ 落终局记录（§2.1）⇒ <b>即退</b>（§2.2，链接形态也<b>不再</b>等父侧关停信号）。
   *
   * @return 进程退出码：0 = 目标回合完成且终局记录已落库；1 = 引导/运行失败（含终局记录写入失败——父侧据此按"未留下终局记录"处理）
   */
  public static int execute(Options options) {
    Objects.requireNonNull(options, "options");
    // 帧通道 = 启动时的 stdout 原流；此后 System.out 一律重定向到 stderr——详见类注释 stdout 纪律
    OutputStream protocolOut = System.out;
    PrintStream diagnostics = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    System.setOut(diagnostics);

    McpToolSource source = null;
    McpSourceBridge bridge = null;
    PipeMcpClientTransport transport = null;
    AgentRuntime runtime = null;
    SqliteEventStore events = null;
    EventBus bus = null;
    try {
      // 模板库是只读快照（进程级无需释放），局部使用即可（无 finally 回收——避免死存储）
      AgentTemplateStore store = new AgentTemplateStore(options.templatesDir());
      store.load();
      AgentTemplate template =
          store
              .get(options.templateId())
              .orElseThrow(
                  () -> new IllegalArgumentException("子 Agent 模板不存在: " + options.templateId()));

      Files.createDirectories(options.dataDir());
      SqliteEventStore eventStore = SqliteEventStore.open(options.dataDir().resolve("events.db"));
      events = eventStore;
      EventBus eventBus = new EventBus();
      bus = eventBus;

      AgentConfig config = template.toAgentConfig(options.instanceId());
      // 技能两个面（本能力）：模板给基线，父侧 argv 传下来的是**收紧后的生效值**（父级物化过的），
      // 这里 decode 后与模板求交（withScopes 再把可编辑面夹进可见面）。argv 缺席 ⇒ none（fail-closed，不是“不限”）。
      //
      // 第 8 维（角色，设计 §4.5）：父侧算出的生效角色（调用者 ∩ 模板 ∩ 请求）经 argv 下发，这里 decode 后挂上——
      // 子体的角色**只**认这份下发值（模板的 roles 只是父侧求交的输入之一，不能在这里被重新放大回去）。
      // 旗标缺席 / 空白 ⇒ ⊥（fail-closed，不是"不限"）：老装配或漏传不会让子体白拿角色。
      AgentPermissionSet permissions =
          SkillPermissions.withScopes(
                  template.toPermissionSet(),
                  SkillPermissions.decode(options.skillVisible()),
                  SkillPermissions.decode(options.skillEditable()))
              .withRoles(AgentRoles.decode(options.roles()));
      // 文档两个面（R3b）：同一条缝、同一个编码口径（SkillPermissions 的 encode/decode 是"ResourceScope ↔ argv
      // 文本表"的唯一实现，两族范围复用而不是各写一份）。argv 缺席 ⇒ none（fail-closed）；可编辑面恒被夹进可见面。
      permissions =
          withDocsScopes(
              permissions,
              SkillPermissions.decode(options.docVisible()),
              SkillPermissions.decode(options.docEditable()));
      ToolRegistry registry = new ToolRegistry();
      // ★ 裁定 15 ④（2026-10-17 用户指令「这玩意他妈不是可配置项吗？」）：子体自己的"本轮包窗口"同样可配——
      //   读**它自己的**配置根（父侧经 --config-dir 传下来的那一个；缺席 = 离线/测试形态 ⇒ 缺省 30 分钟不变）。
      //   ★ 这一项不是"父侧替子体配"：跨进程结算的两个窗口在父侧（App 的 decisionWindows + 网关），子体这一份只决定
      //     "它本地那个包自己的 deadline"（它拿到回执就落 decided/committed，正常路径根本走不到到点）。两边同名同义。
      //   ★ 走 AgentPipeline 的进程级缺省而不是 toolConfig：子体这条装配路（AgentRuntime 的无 toolConfig 构造）
      //     没有工具配置通道，而 AgentRuntime 不在本单改动面内——理由写在 AgentPipeline#defaultDecisionDeadlineMillis。
      if (options.configDir() != null) {
        DecisionWindows childWindows =
            DecisionWindows.load(new FileConfigStore(options.configDir()));
        AgentPipeline.useDecisionDeadlineMillis(childWindows.deadlineMillis());
      }
      // ★ 用户裁定 15 ⑤（补 P2b 缺口 #5）：子体进程**本地**注册 decision_history —— 读**它自己的** events.db
      //   与**它自己的**实例 id。它因此能"下一轮看上一轮的整个包 + 按 index 看其中几项"（默认概览、full 才明文）。
      //   ★ 与父侧那一份的关系：同名工具、各自读各自的库（父侧那份 noExport = true，外发即越权）。
      //   ★ 注册在桥绑定**之前**：名字相同的工具在 registry 里只能有一个（重复注册会响亮抛错），
      //     而这里要的就是"子体看到的是自己那一份"——父侧那份本来也不外发，两者不会互相覆盖。
      registry.register(
          DECISION_HISTORY_TOOL_SOURCE_ID,
          DecisionHistoryTools.localTool(options.dataDir().resolve("events.db"), config.id()));
      if (options.parentLink()) {
        // 父侧建好的管道 → MCP client（握手/工具目录/调用全经此传输；父侧永远在同一对管道上服务）
        McpToolSource parentSource = new McpToolSource("parent");
        source = parentSource;
        transport = new PipeMcpClientTransport(McpJsonDefaults.getMapper(), System.in, protocolOut);
        parentSource.connect(transport);
        // 可见面过滤：父侧暴露面 ∩ 本进程的判定面（同一个 PermissionChecker.allows）——未命中的工具对子体不可见
        // （同步即授权；A-6：可见面 ⊆ 判定面）
        bridge = McpSourceBridge.bind(parentSource, registry, filterFor(permissions, parentSource));
      }

      // R-A2-1：--config-dir 缺席 ⇒ 逐字不变地走模板脚本假 LLM；在场 ⇒ 真模型（R-A2-2/R-A2-7：路由与密钥一律取自配置）
      LlmClient llm =
          options.configDir() == null
              ? template.scriptedFakeLlm()
              : realLlm(options.configDir(), options.templateId());
      // 技能目录预注入（每回合的目录档）：只在本进程拿到技能根时接线；扫描失败不拦启动（子体没有可用的目录档就退回
      // “不预注入”，stderr 留痕）——一个手改坏的技能不该让所有子 Agent 都拉不起来（技能**工具**走父侧链路，
      // 那时会按 SkillCatalog 的既有语义响亮报错，边界仍在）。
      BasicContextAssembler assembler = skillAssembler(options, permissions, diagnostics);
      // ★ P3b（设计 §5quinquies ①/⑤）：子体侧的<b>跨进程收尾缝</b>——回合收尾把本轮待裁决队列交回父侧结算，
      //   回执落进<b>自己的</b>包表与事件库。三件必须在<b>本进程</b>装配（Brain 侧拿不到子体的实例身份与权限集，
      //   主 Agent 的 App 也不造子体）：① 自己的包表；② 本机执行者（执行身份 = 本实例，红线 R7）；③ 提交口。
      //   ★ 只在 --parent-link 时接：没有父链接连那个工具都看不见（registry 里不会有它），缝对它是空的——
      //     于是 available() 判不在 ⇒ 不绑队列、Ask 走既有那一跳（与接线前逐字一致，这是回归安全网）。
      CrossProcessDecisionSubmitter decisionSubmitter =
          options.parentLink()
              ? decisionSubmitter(
                  registry, permissions, options.goal(), config, eventStore, eventBus)
              : null;
      AgentRuntime agent =
          decisionSubmitter == null
              ? assembler == null
                  ? new AgentRuntime(config, llm, registry, eventStore, eventBus, permissions)
                  : new AgentRuntime(
                      config,
                      llm,
                      registry,
                      new ToolExecutionGuard(),
                      assembler,
                      eventStore,
                      eventBus,
                      permissions)
              : assembler == null
                  ? new AgentRuntime(
                      config, llm, registry, eventStore, eventBus, permissions, decisionSubmitter)
                  : new AgentRuntime(
                      config,
                      llm,
                      registry,
                      new ToolExecutionGuard(),
                      assembler,
                      eventStore,
                      eventBus,
                      permissions,
                      decisionSubmitter);
      runtime = agent;
      TurnResult result = agent.chat(options.goal());
      diagnostics.println(
          String.format(
              "子 Agent 回合完成: id=%s stop=%s turns=%d toolCalls=%d",
              options.instanceId(), result.stopReason(), result.turns(), result.toolCalls()));

      // §2.1 终局记录（父侧终局判定的唯一判据）：chat() 返回 ⇒ 先落库，再退栈（finally 里的 runtime.close()）。
      // 写失败 = 协议义务没履行 ⇒ 响亮（stderr + 非零退出），绝不静默吞；父侧据此按"未留下终局记录"记异常终局。
      try {
        eventStore.append(
            EventWrite.of(
                EventTypes.AGENT_LIFECYCLE,
                options.instanceId(),
                terminalPayload(result),
                options.instanceId()));
      } catch (RuntimeException e) {
        diagnostics.println("子 Agent 终局记录写入失败（父侧将按未留下终局记录处理）: " + e.getMessage());
        return 1;
      }
      return 0;
    } catch (RuntimeException | java.io.IOException e) {
      diagnostics.println("子 Agent 引导/运行失败: " + e.getMessage());
      return 1;
    } finally {
      // 模板库是只读快照（进程级无需释放）；资源按创建反序回收
      closeQuietly(bridge);
      closeQuietly(source);
      if (transport != null) {
        // 优雅关停管道：释放内部调度线程（非守护线程残留会拖住 JVM 退出——父侧 kill 无需等宽限超时兜底）
        try {
          transport.closeGracefully().block();
        } catch (Exception e) {
          System.err.println("子 Agent 父链接关停失败: " + e.getMessage());
        }
      }
      closeQuietly(runtime);
      closeQuietly(bus);
      closeQuietly(events);
    }
  }

  /**
   * 子体侧的<b>跨进程收尾缝</b>（P3b，设计 §5quinquies ①/⑤）：包表 + 本机执行者 + 提交口三件，全部用<b>本进程</b>的身份与权限集建。
   *
   * <p><b>为什么这三件必须在子体侧 new（不能在 Brain 里给）</b>：
   *
   * <ol>
   *   <li><b>包表</b>（{@link DecisionPackages}）：不变量 1 说"那一轮只有<b>子体自己的包</b>是决策单元"——父侧只跑一个不落历史的临时包
   *       （{@code CrossProcessDecisionGateway}）。表若由父侧建，子体的历史就会变成父侧那份的影子；
   *   <li><b>执行者</b>（{@link LocalDecisionCommitPort}）：它执行被批子集时用的权限集/身份必须是<b>本实例的</b> （红线
   *       R7：子体不能沿父级权限执行）。"本实例的权限集"只有本进程手里这一份（父侧那份是同一个模板算出来的另一份拷贝）；
   *   <li><b>提交工具调用</b>：走本进程 registry 里那个由父侧经 MCP 桥同步进来的 {@code submit_decision_package}
   *       ——它是"父侧工具面"在本进程的代理，Brain 侧拿不到本进程的 registry 与判定面。
   * </ol>
   *
   * <p><b>身份（{@code proposerKey} 的唯一来源）</b>：实例 id 取 {@link AgentConfig#id()}——它由 {@code
   * template.toAgentConfig(options.instanceId())} 落成 {@code --id}，与父侧在 MCP 链接上绑的那个 id <b>逐字同源</b>
   * （{@code SubProcessExecutor#launch} 的 {@code AgentIdentity.subagent(instance.instanceId(),
   * …)}）；父侧还会拿它核实例注册表 （防冒充）。档位取子 Agent 缺省 {@link CommandMode#LIMITED}：argv
   * <b>不下发档位</b>（父侧绑在链接上），而父侧的判定 <b>从不信</b>子体自报的档位（它用自己那一份）——这里取缺省只是"偏保守的一侧"，不影响任何判定。
   *
   * <p><b>执行者用的工具配置是空表</b>：子体进程当前<b>没有</b>装配期 {@code toolConfig}（既有形态，本单不改）， 所以"执行时注入同一份配置"在这里 =
   * 两边都是空表（不是"提交口少注入了一份"）。这条是既有缺口，如实登记在 P3b 报告里。
   *
   * @param registry 本进程 registry（父侧工具面的代理在这一张里）
   * @param permissions 本进程生效权限集（模板 ∩ 父侧 argv 下发值）
   * @param goal 本实例目标（父侧给的任务；进身份，供本地判定面看清"我是谁"）
   * @param config 本实例配置（{@code id} = 实例 id）
   * @param eventStore 本进程事件库（{@code events.db}：五类包事件落这里）
   * @param eventBus 本进程事件总线（旁路通知；子进程的总线没有落库订阅，落库由缝自己 append）
   */
  private static CrossProcessDecisionSubmitter decisionSubmitter(
      ToolRegistry registry,
      AgentPermissionSet permissions,
      String goal,
      AgentConfig config,
      SqliteEventStore eventStore,
      EventBus eventBus) {
    AgentIdentity identity =
        AgentIdentity.subagent(config.id(), CommandMode.LIMITED, goal == null ? "" : goal, 0);
    DecisionPackages packages = new DecisionPackages();
    // 执行身份 = 本实例（红线 R7 + 设计 §5quinquies ③）：按包 id 现取，但每一包都取同一份本实例身份——
    // 父侧回传的 finalizedBy 只进记录面（"谁拍的板"），不参与"以谁的权限执行"。
    // ★ P3d（设计 §5sexies⑥）：被批条目的执行**只能再经一次 MCP**（工具是父侧代理）⇒ 外面套一层装饰器，给每条挂上
    //   {packageId, index} 放行标记；父侧据此认出"包 P 第 i 条、已批且尚未执行"⇒ 放行并标记已用（重复使用具名拒）。
    DecisionCommitPort commitPort =
        new ReleaseMarkingCommitPort(
            new LocalDecisionCommitPort(
                registry,
                // 纯判定链（不含审批闸）：裁决即授权，执行时不再问一次人——同 App 的装配口径
                new ToolExecutionGuard(),
                Map.of(),
                packageId ->
                    new LocalDecisionCommitPort.ExecutingIdentity(
                        permissions.grantedToken(), permissions, identity)));
    // 提交工具是 Ask-free 的（它做的事就是"替子体问"）：这条 authorizer 的 Ask 分支到不了它，
    // 用 standard()（= 纯判定链）即可——与管线那条入口同源，本进程不新建第二套判定。
    return new CrossProcessDecisionSubmitter(
        packages,
        java.util.Optional.of(commitPort),
        registry,
        ToolCallAuthorizer.standard(),
        Map.of(),
        permissions.grantedToken(),
        permissions,
        identity,
        eventStore,
        eventBus);
  }

  /**
   * 真模型装配（R-A2-2/R-A2-7）：<b>只</b>读 {@code <configRoot>/config.json} 的 {@code llm.*}——{@code
   * baseUrl}/模型名/密钥三者全部来自这条 {@link ModelRoute}，模板里的 {@code model}（{@code AgentConfig} 默认字面量 {@code
   * "fake"}）不参与。<b>只传路径</b>：父进程给的是配置根，密钥值由本进程自己读出（D23/R-A2-3）。
   *
   * <p><b>走哪条路由</b>（多 provider 并存，2026-09-14）：{@code agents.<templateId>.llm.route} → {@code
   * llm.route} → {@code "default"}（{@link LlmRouteLoader#routeName}）——按<b>模板</b>配（同一模板的实例走同一条），
   * <b>不新增任何传递面</b>（父侧照旧只给路径）。点到不存在的名字 ⇒ {@code E_LLM_ROUTE_UNKNOWN} 在引导期响亮，<b>绝不</b>回落到别的路由。
   *
   * <p><b>失败一律响亮（D24），绝不回退 {@code scriptedFakeLlm()}</b>：
   *
   * <ul>
   *   <li>缺 {@code llm.baseUrl}/{@code llm.model} ⇒ {@link LlmRouteLoader#load} 在引导期抛 {@code
   *       E_LLM_CONFIG_MISSING}；
   *   <li>缺密钥 / 引用形态不支持 ⇒ 由下面这次"取密钥预检"在引导期抛 {@link ConfigApiKeySource#E_KEY_MISSING}/{@code
   *       E_REF_FORM_UNSUPPORTED}——预检对<b>本进程实际选中的那条</b>路由做（多路由下"预检 A、实跑
   *       B"是会漏掉这条的唯一形态）。<b>为什么预检</b>：不预检的话，取密钥失败要等到首次 {@code chat} 才发生，而 {@code AgentPipeline} 会把
   *       {@code LlmException} 收敛成 {@code StopReason.LLM_ERROR} 的<b>正常回合</b>（退出码
   *       0）——父侧就再也分辨不出"子体压根没跑起来"。预检只多取一次值、不固化任何东西：{@link ConfigApiKeySource} 每次 {@code chat}
   *       仍现读配置，轮换/过期感知的 SPI 契约不变。
   * </ul>
   *
   * <p>异常消息不含密钥值（引用名也只经 {@link ConfigApiKeySource} 的白名单回显），可直接进子进程 stderr。
   */
  private static LlmClient realLlm(Path configRoot, String templateId) {
    FileConfigStore store = new FileConfigStore(configRoot);
    // 走哪条路由（多 provider 并存）：agents.<templateId>.llm.route → llm.route → "default"（按模板配，无需新增传递面）
    ModelRoute route = LlmRouteLoader.load(store, LlmRouteLoader.routeName(store, templateId));
    ConfigApiKeySource keys =
        new ConfigApiKeySource(store, route.credentialsRef(), AccessToken.SYSTEM);
    keys.apiKey(); // 预检：结果有意丢弃（只要"取得到"这一事实；密钥值不留在本方法里）
    return new OpenAICompatibleLlmClient(route, keys);
  }

  /**
   * 终局记录 payload（§2.1）：{@code action=finished} + 子体流水线自己的计数器（{@link TurnResult}）。
   *
   * <p>键名与父侧读侧（{@code SubagentManager.readChildTerminalRecord}）逐字对齐：父侧只认<b>带非空 {@code
   * stopReason}</b> 的 {@code action=finished} 事件——所以这里不写假值：{@code stopReason} 取枚举名，{@code
   * turns}/{@code toolCalls} 取自本篇回合结果。
   */
  private static String terminalPayload(TurnResult result) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("action", "finished");
    payload.put("stopReason", result.stopReason().name());
    payload.put("turns", result.turns());
    payload.put("toolCalls", result.toolCalls());
    try {
      return JSON.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      // 三个标量键写不出 JSON 属结构性缺陷，不该在这里兜：抛出去由外层按"引导/运行失败"收（+stderr、非零退出）
      throw new IllegalStateException("终局记录 payload 序列化失败", e);
    }
  }

  /**
   * 子体的技能目录预注入装配：{@code --skills-dir} 缺席 ⇒ {@code null}（不接线，行为与接线前逐字一致）。
   *
   * <p>可见面取的是本进程权限集（模板 ∩ 父侧 argv）里那个值——<b>不是</b>模板单独说的那份，否则父级收紧过的范围会在 预注入这一侧被放大回去。
   *
   * <p><b>目录档预算（D2）</b>：取 {@code --skills-listing-budget}；旗标缺席 ⇒ {@link
   * SkillCatalog#DEFAULT_LISTING_BUDGET}（与接线前逐字一致）。此前这里恒用 {@code new SkillCatalog()} ⇒ 父侧配的 {@code
   * skills.listingBudget} 在子体内失效（本项修复的正是这个缺口）。
   */
  private static BasicContextAssembler skillAssembler(
      Options options, AgentPermissionSet permissions, PrintStream diagnostics) {
    if (options.skillsDir() == null) {
      return null;
    }
    int listingBudget =
        options.skillsListingBudget() == null
            ? SkillCatalog.DEFAULT_LISTING_BUDGET
            : options.skillsListingBudget();
    SkillCatalog catalog = new SkillCatalog(listingBudget);
    try {
      // 宽容扫描：坏掉的单个技能只影响它自己（其余技能的目录档照常预注入），坏掉这件事 stderr 留痕
      SkillCatalog.ScanReport report = catalog.scanTolerant(options.skillsDir());
      if (!report.broken().isEmpty()) {
        diagnostics.println("技能目录里有坏技能（本次预注入跳过它们）: " + report.broken());
      }
    } catch (RuntimeException e) {
      diagnostics.println(
          "技能目录预注入已跳过（根目录不可扫描，技能工具仍可用）: " + options.skillsDir() + " → " + e.getMessage());
      return null;
    }
    SkillAccess access = SkillAccess.of(permissions, catalog, options.skillsDir());
    return new BasicContextAssembler(ContextPolicy.defaults(), ContextSources.skills(access));
  }

  /**
   * 子体的文档两个面落进权限集（R3b）：与 {@code SkillPermissions.withScopes} 同一形状——<b>可编辑面自动与可见面求交</b>， 于是 {@code
   * docs-edit ⊆ docs} 在数据上成立（"改得动但看不见"造不出来），单调性守卫也看得见这两个命名空间。
   *
   * <p><b>为什么在本模块自己写这一小段而不是再开一个常量类</b>：命名空间常量仍取自 {@code docs-mosire}（不抄字符串），这里只做两次 {@code
   * withNamespace}；技能面之所以有 {@code SkillPermissions} 那个类，是因为它还带着目录扫描过滤等一堆用法，文档面这一侧在子进程里 只用到"恢复两个面"。
   */
  private static AgentPermissionSet withDocsScopes(
      AgentPermissionSet permissions,
      io.mosire.agentlib.permission.ResourceScope visible,
      io.mosire.agentlib.permission.ResourceScope editable) {
    return permissions.withResourceScopes(
        permissions
            .resourceScopes()
            .withNamespace(DocNamespaces.DOCS, visible)
            .withNamespace(DocNamespaces.DOCS_EDIT, editable.narrowTo(visible)));
  }

  /**
   * 本进程权限集 → 桥同步过滤器（A-6：可见面与判定面<b>同源</b>）。
   *
   * <p>命中 = {@link PermissionChecker#allows} 放行 —— 判定式<b>只此一处</b>（判定面 {@code ToolCallAuthorizer}
   * 用的是同一个 oracle）。此前的白名单式过滤只看 allowed-tools 一维：只读、黑名单、桶、敏感/破坏各维在可见面上缺席，于是子体可能看见"一调就吃 {@code
   * PERMISSION_DENIED}"的工具（烧 token 烧回合，而且那正是设计 §4.8 点名的"第二处真相"）。
   *
   * <p>★ <b>子侧这一面只看得到"权限集驱动"的维</b>：经父侧桥进来的工具是 {@code McpToolAdapter}（{@code spec()} 恒 {@code
   * ToolSpec.DEFAULT}、{@code roles()} 恒空集）——外部 MCP 工具无法先知敏感性。真 spec/roles 只在父侧注册表里，故
   * "敏感/破坏/角色"这几维的收窄要靠<b>父侧外发面</b>兜住，本谓词只保证父侧外发面之内不再出现"看得见却判不过"的名字。
   *
   * <p>★ <b>不缓存快照</b>：谓词跨多次 {@code sync()} 复用（目录变更要能带进新工具），故每次求值现读 {@link
   * ToolSource#listTools()}——该快照是本地缓存（{@code McpToolSource} 在 connect 与 {@code tools/list_changed}
   * 时刷新）， 不是网络往返。
   */
  private static Predicate<String> filterFor(AgentPermissionSet permissions, ToolSource source) {
    return name ->
        source.listTools().stream()
            .filter(t -> t.name().equals(name))
            .findFirst()
            .map(t -> PermissionChecker.allows(permissions, name, t.spec(), t.roles()))
            .orElse(false);
  }

  private static void closeQuietly(AutoCloseable resource) {
    if (resource == null) {
      return;
    }
    try {
      resource.close();
    } catch (Exception e) {
      System.err.println("子 Agent 资源关闭失败: " + e.getMessage());
    }
  }

  /**
   * {@code agent} 子命令的进程级参数（Main 解析后的不可变快照）。
   *
   * @param configDir 父的配置根（{@code --config-dir}；null = 离线/测试形态 ⇒ 模板脚本假 LLM）。只放路径——密钥值由本进程自己从 {@code
   *     <configDir>/config.json} 读（D23）
   * @param skillsDir 技能根目录（{@code --skills-dir}；null = 本子体不做技能目录预注入）。技能<b>工具</b>由父侧 MCP 链路服务，
   *     这里只为“每回合的可见技能目录档”提供落点
   * @param skillVisible 可见面（{@code --skill-visible}，可重复；{@code *}/{@code none}/路径，见 {@link
   *     SkillPermissions#encode}）；空表 = none（fail-closed）
   * @param skillEditable 可编辑面（{@code --skill-editable}），语义同上
   * @param docVisible 文档可见面（{@code --doc-visible}，可重复；编码口径与技能面逐字相同）；空表 = none（fail-closed）
   * @param docEditable 文档可编辑面（{@code --doc-editable}）；空表 = none，且恒被夹进可见面
   * @param skillsListingBudget 技能目录档字符预算（{@code --skills-listing-budget}；D2）。<b>null = 旗标缺席</b> ⇒
   *     回落 {@link SkillCatalog#DEFAULT_LISTING_BUDGET}（与接线前逐字一致）；0 是合法取值（不披露目录档）；负数在构造期具名拒
   * @param roles 角色（{@code --roles}，逗号连接的升序角色名；第 8 维，设计 §4.5）。<b>null = 旗标缺席</b> ⇒ {@code
   *     RoleSet.none()}（fail-closed，不是"不限"）——老装配或漏传不会让子体白拿角色。父侧下发的就是算好的生效值 （{@code 调用者 ∩ 模板 ∩
   *     请求}），子进程侧不再与模板求交（重复求交只会把父级的收紧洗回去）
   */
  public record Options(
      String instanceId,
      String templateId,
      String goal,
      Path templatesDir,
      Path dataDir,
      boolean parentLink,
      Path configDir,
      Path skillsDir,
      List<String> skillVisible,
      List<String> skillEditable,
      List<String> docVisible,
      List<String> docEditable,
      Integer skillsListingBudget,
      String roles) {

    /** 13 参兼容构造（角色维引入之前）：{@code roles = null} = 旗标缺席 ⇒ 子体 ⊥（fail-closed，逐字回到本字段引入前的行为）。 */
    public Options(
        String instanceId,
        String templateId,
        String goal,
        Path templatesDir,
        Path dataDir,
        boolean parentLink,
        Path configDir,
        Path skillsDir,
        List<String> skillVisible,
        List<String> skillEditable,
        List<String> docVisible,
        List<String> docEditable,
        Integer skillsListingBudget) {
      this(
          instanceId,
          templateId,
          goal,
          templatesDir,
          dataDir,
          parentLink,
          configDir,
          skillsDir,
          skillVisible,
          skillEditable,
          docVisible,
          docEditable,
          skillsListingBudget,
          null);
    }

    /** 12 参兼容构造（D2 之前）：目录档预算<b>未下发</b>（{@code null} ⇒ 子进程按缺省 4096 回落，行为逐字不变）。 */
    public Options(
        String instanceId,
        String templateId,
        String goal,
        Path templatesDir,
        Path dataDir,
        boolean parentLink,
        Path configDir,
        Path skillsDir,
        List<String> skillVisible,
        List<String> skillEditable,
        List<String> docVisible,
        List<String> docEditable) {
      this(
          instanceId,
          templateId,
          goal,
          templatesDir,
          dataDir,
          parentLink,
          configDir,
          skillsDir,
          skillVisible,
          skillEditable,
          docVisible,
          docEditable,
          null);
    }

    /** 11 参兼容构造（文档面引入之前）：两个文档面都取空表 = none（fail-closed，逐字回到本字段引入前的行为）。 */
    public Options(
        String instanceId,
        String templateId,
        String goal,
        Path templatesDir,
        Path dataDir,
        boolean parentLink,
        Path configDir,
        Path skillsDir,
        List<String> skillVisible,
        List<String> skillEditable) {
      this(
          instanceId,
          templateId,
          goal,
          templatesDir,
          dataDir,
          parentLink,
          configDir,
          skillsDir,
          skillVisible,
          skillEditable,
          List.of(),
          List.of());
    }

    /** 7 参兼容构造（技能面引入之前）：不预注入、四个范围面都取 none。 */
    public Options(
        String instanceId,
        String templateId,
        String goal,
        Path templatesDir,
        Path dataDir,
        boolean parentLink,
        Path configDir) {
      this(
          instanceId,
          templateId,
          goal,
          templatesDir,
          dataDir,
          parentLink,
          configDir,
          null,
          List.of(),
          List.of());
    }

    public Options {
      if (instanceId == null || instanceId.isBlank()) {
        throw new IllegalArgumentException("--id 不能为空");
      }
      if (templateId == null || templateId.isBlank()) {
        throw new IllegalArgumentException("--template 不能为空");
      }
      if (goal == null || goal.isBlank()) {
        throw new IllegalArgumentException("--goal 不能为空");
      }
      templatesDir = Objects.requireNonNullElse(templatesDir, BootConfig.DEFAULT_TEMPLATES_DIR);
      dataDir = Objects.requireNonNullElse(dataDir, BootConfig.DEFAULT_DATA_DIR);
      // 不可变快照；null 与空表同口径（都读作 none —— 缺省方向朝收紧）
      skillVisible = skillVisible == null ? List.of() : List.copyOf(skillVisible);
      skillEditable = skillEditable == null ? List.of() : List.copyOf(skillEditable);
      docVisible = docVisible == null ? List.of() : List.copyOf(docVisible);
      docEditable = docEditable == null ? List.of() : List.copyOf(docEditable);
      if (skillsListingBudget != null && skillsListingBudget < 0) {
        // 与 SkillCatalog 同一口径（负预算 = 配置错误）：解析即拒，不夹成 0
        throw new IllegalArgumentException("--skills-listing-budget 不能为负: " + skillsListingBudget);
      }
    }
  }
}
