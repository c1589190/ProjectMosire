package io.mosire.brain.subagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.agent.AgentConfig;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.brain.permission.AgentRoles;
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
 * @param allowedSkills 该模板子体的<b>技能可见面建议</b>：条目是技能文件系统路径——一个<b>技能文件夹</b>（子树里的技能都可见）或一个 <b>单个 {@code
 *     SKILL.md}</b>。null = 不限（不额外收窄）；空表 = <b>显式"一个技能都看不见"</b>（与 {@code allowedTools: []}
 *     同形同义）。同样<b>是建议不是要求</b>：最终生效值 = 调用者可见面 ∩ 这一份 ∩ 请求那一份，超出调用者的条目静默求交
 * @param editableSkills 该模板子体的<b>技能可编辑面建议</b>（语义同 {@code allowedSkills}）。生效值恒被求交进可见面之内——
 *     "改得动但看不见"在结构上造不出来（见 {@code SkillPermissions}）
 * @param allowedDocs 该模板子体的<b>文档可见面建议</b>（R3b）：条目是文档面的物理路径前缀——{@code docs}/{@code docs-edit} 两个
 *     命名空间落格的就是挂载的物理落点（见 {@code DocNamespaces}）。null = 不限（不额外收窄）；空表 = <b>显式"什么文档都看不见"</b> （与 {@code
 *     allowedTools: []} 同形同义）。同样<b>是建议不是要求</b>：最终生效值 = 调用者可见面 ∩ 这一份 ∩ 请求那一份
 * @param editableDocs 该模板子体的<b>文档可编辑面建议</b>（语义同 {@code allowedDocs}）。生效值恒被求交进可见面之内——
 *     "改得动但看不见"在结构上造不出来（{@code docs-edit ⊆ docs}）
 * @param roles 该模板子体的<b>角色基线</b>（设计 §4.5，第 8 维）：null = <b>不额外收窄</b>（调用者是什么角色，子体就接什么）；空表 =
 *     <b>显式"谁都不是"</b>（与 {@code allowedTools: []} 同形同义）。<b>是基线不是要求</b>——最终生效值 = 调用者角色 ∩ 这一份 ∩ 请求那一份，
 *     超出调用者的条目<b>静默求交</b>（模板是通用配置，被上级收紧是常态），与请求面 {@code roles} 的响亮拒绝口径不同。角色名不得含逗号 （argv 文本线的分隔符，见
 *     {@code AgentRoles#requireWireSafe}），装载期即响亮失败
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "allowedSkills/editableSkills/allowedDocs/editableDocs/roles 在规范构造里已 List.copyOf 成不可变快照，但都允许为"
            + " null（null = 不限/不额外收窄，与空表是两种取值），那个 null 分支让 SpotBugs 的字段不可变性推断失效——这是记录型数据的防御拷贝，"
            + "不是暴露内部表示")
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
    List<String> allowedWorkingDirs,
    List<String> allowedSkills,
    List<String> editableSkills,
    List<String> allowedDocs,
    List<String> editableDocs,
    List<String> roles) {

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
    // 技能范围与工作目录同一口径：null（不限）与 []（哪里都不许）是两种取值，不归一成一个；坏路径装载期响亮失败
    allowedSkills = normalizedPaths(id, "allowedSkills", allowedSkills);
    editableSkills = normalizedPaths(id, "editableSkills", editableSkills);
    // 文档范围（R3b）与技能范围同一口径：同一个归一函数，理由同上——"坏路径在装载期失败"这条保证不许缺角
    allowedDocs = normalizedPaths(id, "allowedDocs", allowedDocs);
    editableDocs = normalizedPaths(id, "editableDocs", editableDocs);
    // 角色基线（第 8 维）与上一条同一口径：null（不额外收窄）与 []（谁都不是）是两种取值，不归一成一个
    roles = normalizedRoles(id, roles);
  }

  /**
   * 角色基线的归一（{@code null} 保持 null = 不额外收窄；空表 = 谁都不是；元素非空白，且必须能无损落到 argv 文本线上）。
   *
   * <p>与 {@code allowedSkills}/{@code allowedDocs} 同一口径：装载期就把坏值挡住——"拼错的角色 = 永远不可达的工具"（设计 §4.6）。
   * 含分隔符的名字在这里（装载期）就响亮失败，理由见 {@link AgentRoles#requireWireSafe}；校验走 {@code AgentRoles}
   * 的同一实现，本类不另写一套口径。
   */
  private static List<String> normalizedRoles(String id, List<String> roles) {
    if (roles == null) {
      return null;
    }
    for (String role : roles) {
      if (role == null || role.isBlank()) {
        throw new IllegalArgumentException("模板 " + id + " 的 roles 元素必须是角色名文本: " + roles);
      }
    }
    List<String> copy = List.copyOf(roles);
    if (!copy.isEmpty()) {
      AgentRoles.requireWireSafe(RoleSet.of(copy.toArray(String[]::new)));
    }
    return copy;
  }

  /**
   * 技能范围条目的归一（null 保持 null；元素非空白；非空的表在装载期跑一遍路径归一，坏路径响亮失败）。
   *
   * <p>与 {@code allowedWorkingDirs} 的校验逐字同形——两处若各写一套，"坏路径在装载期失败"这条保证会缺一角。
   */
  private static List<String> normalizedPaths(String id, String field, List<String> paths) {
    if (paths == null) {
      return null;
    }
    for (String path : paths) {
      if (path == null || path.isBlank()) {
        throw new IllegalArgumentException("模板 " + id + " 的 " + field + " 元素必须是非空白路径文本: " + paths);
      }
    }
    List<String> copy = List.copyOf(paths);
    if (!copy.isEmpty()) {
      ResourceScope.ofDirs(copy.stream().map(Path::of).toList());
    }
    return copy;
  }

  /**
   * 模板的 {@code skills} 作用域建议（可见面）：{@code null} ⇒ 不限；空表 ⇒ 一个都看不见；有值 ⇒ 路径前缀集 （技能文件夹或单个 {@code
   * SKILL.md}）。
   */
  public ResourceScope skillVisibleScope() {
    return skillScopeOf(allowedSkills);
  }

  /** 模板的 {@code skills-edit} 作用域建议（可编辑面）；生效值由 {@code SubagentManager} 求交进可见面。 */
  public ResourceScope skillEditScope() {
    return skillScopeOf(editableSkills);
  }

  private static ResourceScope skillScopeOf(List<String> paths) {
    if (paths == null) {
      return ResourceScope.unlimited();
    }
    if (paths.isEmpty()) {
      return ResourceScope.none();
    }
    return ResourceScope.ofDirs(paths.stream().map(Path::of).toList());
  }

  /**
   * 模板的 {@code docs} 作用域建议（文档可见面，R3b）：{@code null} ⇒ 不限；空表 ⇒ 什么都看不见；有值 ⇒ 物理路径前缀集 （挂载的落点，见 {@code
   * DocNamespaces}）。
   */
  public ResourceScope docVisibleScope() {
    return docScopeOf(allowedDocs);
  }

  /** 模板的 {@code docs-edit} 作用域建议（文档可编辑面）；生效值由 {@code SubagentManager} 求交进可见面。 */
  public ResourceScope docEditScope() {
    return docScopeOf(editableDocs);
  }

  /** 文档范围与技能范围共用同一个"null = 不限 / 空表 = 没有 / 有值 = 路径前缀集"读法（口径只有一处）。 */
  private static ResourceScope docScopeOf(List<String> paths) {
    return skillScopeOf(paths);
  }

  /**
   * 模板的角色基线（设计 §4.5 的第 8 维）：{@code null} ⇒ <b>不额外收窄</b>（调用者是什么角色，子体就接什么）；{@link RoleSet#none()} ⇒
   * 显式"谁都不是"（空表）；其余 ⇒ 明确集合。
   *
   * <p><b>为什么返回可空的 {@link RoleSet} 而不是一种取值</b>：模板的"没写"（不表态）与"写了空表"（谁都不是）是两种语义，而 {@link RoleSet} 只有
   * ⊥ 与"一组具体角色"两种取值（它刻意不提供 {@code any()}——那是声明侧的读法）。spawn 侧据此决定这一份基线<b>参不参与</b>求交。
   */
  public RoleSet declaredRoles() {
    if (roles == null) {
      return null;
    }
    return roles.isEmpty() ? RoleSet.none() : RoleSet.of(roles.toArray(String[]::new));
  }

  /**
   * 18 参兼容构造（文档范围字段引入之前）：两个文档范围取 {@code null} = 不限（模板不额外收窄，逐字回到本字段引入前的行为）。
   *
   * <p>与 16 参那条同理：模板是部署者写的数据（JSON 走 {@link JsonCreator} 的规范构造），代码侧的既有构造点不该为了两个"没提这一嘴" 的字段全量改签名。
   */
  public AgentTemplate(
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
      List<String> allowedWorkingDirs,
      List<String> allowedSkills,
      List<String> editableSkills) {
    this(
        id,
        description,
        systemPrompt,
        model,
        token,
        allowedTools,
        deniedTools,
        destructiveAllowed,
        sensitiveAllowed,
        readOnly,
        maxTurns,
        maxToolCallsPerTurn,
        timeBudgetSeconds,
        quotaMaxTokens,
        script,
        allowedWorkingDirs,
        allowedSkills,
        editableSkills,
        null,
        null);
  }

  /**
   * 20 参兼容构造（角色维引入之前）：{@code roles = null} = 不额外收窄（模板不额外收窄角色，逐字回到本字段引入前的行为）。
   *
   * <p>与 18/16 参两条同理：模板是部署者写的数据（JSON 走 {@link JsonCreator} 的规范构造），代码侧的既有构造点不该为了一个"没提这一嘴"的字段全量改签名。
   */
  public AgentTemplate(
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
      List<String> allowedWorkingDirs,
      List<String> allowedSkills,
      List<String> editableSkills,
      List<String> allowedDocs,
      List<String> editableDocs) {
    this(
        id,
        description,
        systemPrompt,
        model,
        token,
        allowedTools,
        deniedTools,
        destructiveAllowed,
        sensitiveAllowed,
        readOnly,
        maxTurns,
        maxToolCallsPerTurn,
        timeBudgetSeconds,
        quotaMaxTokens,
        script,
        allowedWorkingDirs,
        allowedSkills,
        editableSkills,
        allowedDocs,
        editableDocs,
        null);
  }

  /**
   * 16 参兼容构造（技能范围字段引入之前）：两个技能范围取 {@code null} = 不限（模板不额外收窄，逐字回到本字段引入前的行为）。
   *
   * <p>为什么保留：模板是部署者写的数据（JSON 走 {@link JsonCreator} 的规范构造），而代码侧的既有构造点（夹具、脚本模板）
   * 不该为了一个“没提这一嘴”的字段全量改签名。
   */
  public AgentTemplate(
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
    this(
        id,
        description,
        systemPrompt,
        model,
        token,
        allowedTools,
        deniedTools,
        destructiveAllowed,
        sensitiveAllowed,
        readOnly,
        maxTurns,
        maxToolCallsPerTurn,
        timeBudgetSeconds,
        quotaMaxTokens,
        script,
        allowedWorkingDirs,
        null,
        null);
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

  /**
   * 映射为权限集（白名单含 {@code "*"} 时整体放通为 allowAll，与 AgentPermissionSet 的通配约定一致）。
   *
   * <p><b>第 8 维（角色）刻意不在本方法里物化</b>：模板的角色是<b>基线</b>，而"没写"（不额外收窄）在 {@link AgentPermissionSet}
   * 里表达不出来（那一维只有 ⊥ 与"一组具体角色"两种取值，没有"不表态"）。若这里把 null 读成 ⊥，一个父级有角色的部署会凭空把子体的角色清空； 若读成"照抄模板"，则 {@code
   * toPermissionSet()} 单独看又是一个比调用者宽的集合——两种都不行。生效值只能由 spawn 链求交后显式挂上 （{@link SubagentManager} 的
   * {@code 调用者 ∩ 模板 ∩ 请求}），子进程侧则由父侧 argv 下发的生效值挂上（见 {@code SubagentProcessMain}）。
   */
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
