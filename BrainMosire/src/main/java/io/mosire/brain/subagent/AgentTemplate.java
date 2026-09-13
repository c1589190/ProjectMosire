package io.mosire.brain.subagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.brain.runtime.AgentConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 一个子 Agent 模板（{@code configs/agents/<id>.json} 的内存态，数据不是代码——计划 D10）。
 *
 * <p>为什么模板自带权限三要素而不只配 prompt：子 Agent 的权限边界必须在 spawn 前就能静态审出 （红线 1
 * 权限单调性），模板是审计的最小单元；运行期任何收紧只能在此基础上做交集。
 *
 * <p><strong>script（W3b 新增）</strong>：子 Agent 离线假 LLM 的引导脚本——数据不是代码， 模板要素。脚本驱动 W3 E2E
 * 中真实子进程的确定性行为（先在哪个回合调哪个工具、何时说结束语）， 让"真实子进程 + 父级暴露工具经 MCP 回环调用"的事件链可完整复现；空脚本 = 无引导（假 LLM 回一句通用话）。
 *
 * <p>规范构造内完成全部校验：非法模板（id 格式、非正硬顶、负配额）在装载期即失败，不流入运行时。
 *
 * @param id 模板 id，须匹配 {@code [a-z0-9][a-z0-9-]{0,63}}（同时是文件名 stem 与子进程标识素材）
 * @param description 面向人的说明（null → 空串）
 * @param systemPrompt 子 Agent 系统提示词（必填非空）
 * @param model 模型名（null → {@link AgentConfig} 默认模型）
 * @param token 身份级别（null → {@link AccessToken#DEFAULT}）
 * @param allowedTools 工具白名单（null → 空集=deny-default；含 {@code "*"} = 不做白名单限制）
 * @param deniedTools 显式拒绝名单（null → 空集）
 * @param destructiveAllowed 是否放行破坏性工具
 * @param sensitiveAllowed 是否放行敏感工具
 * @param readOnly 是否只读 Agent（只读时不得执行任何工具）
 * @param maxTurns 轮次硬顶（&gt;0）
 * @param maxToolCallsPerTurn 单回合工具调用硬顶（&gt;0）
 * @param timeBudgetSeconds 时间预算秒数（&gt;0）
 * @param quotaMaxTokens token 配额（&gt;=0；0 = 不限）
 * @param script 引导脚本（null → 空脚本）；每步恰好是工具调用或文本之一
 * @param allowedWorkingDirs 该模板子体的<b>工作目录建议</b>（S5-B）：null = 不限（不额外收窄，父级说多少就是多少）； 空表 =
 *     <b>显式"哪里都不许"</b>（与 {@code allowedTools: []} 同形同义）。<b>是建议不是要求</b>—— 最终生效值 = 父级可达面 ∩ 这一份 ∩
 *     请求那一份；超出父级的条目<b>静默求交</b>（模板是通用配置，被上级收紧是常态）， 与请求面 {@code allowedDirs} 的响亮拒绝口径不同
 */
public record AgentTemplate(
    String id,
    String description,
    String systemPrompt,
    String model,
    AccessToken token,
    Set<String> allowedTools,
    Set<String> deniedTools,
    boolean destructiveAllowed,
    boolean sensitiveAllowed,
    boolean readOnly,
    int maxTurns,
    int maxToolCallsPerTurn,
    long timeBudgetSeconds,
    long quotaMaxTokens,
    List<ScriptStep> script,
    List<String> allowedWorkingDirs) {

  /** 模板 id 形态约束（小写、短横线、≤64 字符——可直接充当文件名/进程标识）。 */
  private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

  /** Jackson 反序列化也走同一规范构造（紧凑形），校验不分叉。 */
  @JsonCreator
  public AgentTemplate {
    if (id == null || !ID_PATTERN.matcher(id).matches()) {
      throw new IllegalArgumentException("模板 id 必须匹配 [a-z0-9][a-z0-9-]{0,63}: " + id);
    }
    if (systemPrompt == null || systemPrompt.isBlank()) {
      throw new IllegalArgumentException("模板 " + id + " 的 systemPrompt 不能为空");
    }
    if (maxTurns <= 0) {
      throw new IllegalArgumentException("模板 " + id + " 的 maxTurns 必须为正: " + maxTurns);
    }
    if (maxToolCallsPerTurn <= 0) {
      throw new IllegalArgumentException(
          "模板 " + id + " 的 maxToolCallsPerTurn 必须为正: " + maxToolCallsPerTurn);
    }
    if (timeBudgetSeconds <= 0) {
      throw new IllegalArgumentException(
          "模板 " + id + " 的 timeBudgetSeconds 必须为正: " + timeBudgetSeconds);
    }
    if (quotaMaxTokens < 0) {
      throw new IllegalArgumentException("模板 " + id + " 的 quotaMaxTokens 不能为负: " + quotaMaxTokens);
    }
    // 宽松项就地归一（而非校验失败）：可选字段缺失是合法模板形态，缺省值都取"更收缩"方向
    description = description == null ? "" : description;
    model = model == null ? "fake" : model;
    token = token == null ? AccessToken.DEFAULT : token;
    // 内联 Set.copyOf（同 AgentPermissionSet 的做法）：让 SpotBugs 看得见不可变来源
    allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
    deniedTools = deniedTools == null ? Set.of() : Set.copyOf(deniedTools);
    script = script == null ? List.of() : List.copyOf(script);
    // allowedWorkingDirs 刻意<b>不</b>归一成空表：null（不限）与 []（哪里都不许）是两种取值，混成一个会静默放大权限。
    // 元素形态在这里不判（路径归一归 ResourceScope.ofDirs 管，坏值在装载期由它响亮失败）。
    if (allowedWorkingDirs != null) {
      for (String dir : allowedWorkingDirs) {
        if (dir == null || dir.isBlank()) {
          throw new IllegalArgumentException(
              "模板 " + id + " 的 allowedWorkingDirs 元素必须是非空白路径文本: " + allowedWorkingDirs);
        }
      }
    }
    // 不可变副本（内联 copyOf：让 SpotBugs 看得见来源，同 allowedTools/deniedTools）
    allowedWorkingDirs = allowedWorkingDirs == null ? null : List.copyOf(allowedWorkingDirs);
    if (allowedWorkingDirs != null && !allowedWorkingDirs.isEmpty()) {
      // 装载期就把路径归一跑一遍：坏路径（含 NUL 等无法成路径的形态）在这里响亮失败，不流到 spawn 期才炸。
      // 归一结果不保留（每次 spawn 现算，根目录的 realpath 可能随环境变化——判定与运行同刻同源）
      ResourceScope.ofDirs(allowedWorkingDirs.stream().map(Path::of).toList());
    }
  }

  /**
   * 模板的 {@code fs} 作用域建议（{@code fs} 命名空间）：{@code null} ⇒ 不限；空表 ⇒ 哪里都不许；有值 ⇒ 目录集（绝对化 + 词法归一，已存在的根取
   * realpath——同 {@link ResourceScope#ofDirs}）。
   *
   * @throws IllegalArgumentException 路径根本不成路径（如含 NUL）的情形（模板装载期响亮失败）；{@code ..} 段属于 "路径字面"问题，按
   *     {@link Path} 的语义词法解析，不在此拒绝
   */
  public ResourceScope workingDirScope() {
    if (allowedWorkingDirs == null) {
      return ResourceScope.unlimited();
    }
    if (allowedWorkingDirs.isEmpty()) {
      return ResourceScope.none();
    }
    return ResourceScope.ofDirs(allowedWorkingDirs.stream().map(Path::of).toList());
  }

  /**
   * 映射为运行时 {@link AgentConfig}（quota 0 语义两侧行同：{@link AgentConfig#NO_QUOTA} 不限）。
   *
   * <p>id 直接用模板 id——本方法面向"模板即 Agent"的直用场景；spawn 组装时 Manager 会以实例 id 重建（见 SubagentManager），不经过本方法。
   */
  public AgentConfig toAgentConfig() {
    return toAgentConfig(id);
  }

  /** 映射为运行时 {@link AgentConfig} 并以实例 id 为运行 id（W3b：子 Agent 的事件/上下文 id 必须是实例 id， 模板本身只是源数据）。 */
  public AgentConfig toAgentConfig(String instanceId) {
    if (instanceId == null || instanceId.isBlank()) {
      throw new IllegalArgumentException("instanceId 不能为空");
    }
    return AgentConfig.builder(instanceId)
        .description(description)
        .systemPrompt(systemPrompt)
        .model(model)
        .allowedTools(allowedTools)
        .deniedTools(deniedTools)
        .maxTurns(maxTurns)
        .maxToolCallsPerTurn(maxToolCallsPerTurn)
        .timeBudget(Duration.ofSeconds(timeBudgetSeconds))
        .quotaMaxTokens(quotaMaxTokens)
        .build();
  }

  /**
   * 脚本形假 LLM（W3b 子 Agent 进程用）：按 {@code script} 逐条播放——工具调用步产出单工具调用响应、 文本步产出纯文本响应；空脚本回一句温和默认话；脚本耗尽后抛
   * {@link LlmException}（同 FakeLlmClient 的"耗尽即暴露"约定，防止跑飞循环静默复用）。每次返回全新实例（各子 Agent 独立播放）。
   */
  public LlmClient scriptedFakeLlm() {
    return new ScriptedFakeLlm(script);
  }

  /**
   * 一步引导脚本。
   *
   * @param toolCall 工具调用步骤的工具名（与 {@code text} 恰好一个非空；参数默认空表）
   * @param args 工具调用参数（null → 空表）
   * @param text 文本回复步骤的内容（与 {@code toolCall} 恰好一个非空）
   */
  public record ScriptStep(String toolCall, Map<String, Object> args, String text) {

    /** 任何非法组合（都空 / 都非空 / 空白名）在构造期即失败——模板装载期暴露歧义，绝不流入运行时。 */
    @JsonCreator
    public ScriptStep {
      boolean hasCall = toolCall != null && !toolCall.isBlank();
      boolean hasText = text != null && !text.isBlank();
      if (hasCall == hasText) {
        throw new IllegalArgumentException("ScriptStep 必须恰好是工具调用或文本之一（toolCall 与 text 二选一）");
      }
      args = args == null ? Map.of() : Map.copyOf(args);
    }
  }

  /** 脚本播放器（内部实现；每模板实例一个，按步推进，耗尽抛 LlmException）。 */
  private static final class ScriptedFakeLlm implements LlmClient {

    private final List<ScriptStep> steps;
    private final AtomicInteger cursor = new AtomicInteger();

    ScriptedFakeLlm(List<ScriptStep> steps) {
      this.steps = steps;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      if (steps.isEmpty()) {
        int index = cursor.getAndIncrement();
        if (index > 0) {
          throw new LlmException("模板引导脚本已耗空（第 " + (index + 1) + " 次调用）——疑似循环跑飞，请检查 script");
        }
        return LlmResponse.text("子 Agent 无引导脚本，请向我描述任务。");
      }
      int index = cursor.getAndIncrement();
      if (index >= steps.size()) {
        throw new LlmException("模板引导脚本已耗空（第 " + (index + 1) + " 次调用）——疑似循环跑飞，请检查 script");
      }
      ScriptStep step = steps.get(index);
      if (step.toolCall() != null && !step.toolCall().isBlank()) {
        return LlmResponse.toolCall("script-" + (index + 1), step.toolCall(), step.args());
      }
      return LlmResponse.text(step.text());
    }
  }

  /** 映射为权限集（白名单含 {@code "*"} 时整体放通为 allowAll，与 AgentPermissionSet 的通配约定一致）。 */
  public AgentPermissionSet toPermissionSet() {
    AgentPermissionSet.Builder builder = AgentPermissionSet.builder(token);
    if (allowedTools.contains(AgentPermissionSet.ALL_TOOLS)) {
      builder.allowAll();
    } else {
      builder.allow(allowedTools.toArray(String[]::new));
    }
    builder.deny(deniedTools.toArray(String[]::new));
    return builder
        .destructiveAllowed(destructiveAllowed)
        .sensitiveAllowed(sensitiveAllowed)
        .readOnly(readOnly)
        .build();
  }
}
