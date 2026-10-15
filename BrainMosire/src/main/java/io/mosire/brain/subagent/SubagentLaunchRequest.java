package io.mosire.brain.subagent;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.brain.permission.AgentRoles;
import java.util.List;
import java.util.Set;

/**
 * 一次 spawn 请求（调用方意图，不是最终配置——最终配置由模板∩收紧推导，见 {@link SubagentManager}）。
 *
 * <p>收紧语义：所有 cap 只能比模板更严（取 min；更松的 cap 被忽略而非报错——"拒绝收紧失败" 不算错误）；{@code extraDenied} 只增不减 denied
 * 名单；白名单永不放大；{@code allowedDirs} 同理（见下）。
 *
 * @param templateId 目标模板 id（必填）
 * @param goal 任务目标（必填；事件里只记长度不记全文，防上下文泄漏）
 * @param extraDenied 追加拒绝的工具名；null 视为空集
 * @param maxTurnsCap 轮次上限收紧；null = 不收紧
 * @param timeBudgetSecondsCap 时间预算（秒）收紧；null = 不收紧
 * @param quotaMaxTokensCap token 配额收紧；null = 不收紧
 * @param mode 想要的<b>命令档位</b>（S6）；{@code null} = 不给（落到子 Agent 的缺省 {@link CommandMode#LIMITED}）。
 *     <b>只是"想要"</b>：父级档位不够时 {@link SubagentManager#spawn} 直接拒（单调性，同权限收紧口径），不静默降级
 * @param allowedDirs 想要给子体的<b>工作目录</b>（S5-B；绝对或相对路径文本）。{@code null} = 不指定（不额外收窄， 父级与模板说多少就是多少）；空表 =
 *     <b>显式要求"哪里都不许"</b>（与 {@code allowedTools: []} 同形同义）。
 *     <b>与模板的分工</b>：模板那一份是<b>建议</b>（超出父级就静默求交），请求这一份是<b>要求</b>—— 超出父级可达面一律响亮拒（{@code
 *     DIR_NOT_ALLOWED}），不静默截断
 * @param allowedTools 想要给子体的<b>工具白名单</b>（D27 的 {@code A1}）。{@code null} = 不指定（子体的工具面由模板白名单说了算，
 *     逐字回到本字段引入前的行为）； 空表 = <b>显式"一个工具都不给"</b>（与 {@code allowedDirs: []}
 *     同形同义）。<b>与模板的分工</b>：模板那一份是<b>建议</b> （通用配置，超出调用者可达面就静默求交），请求这一份是<b>要求</b>——点名调用者自己没有的工具，{@link
 *     SubagentManager#spawn} 一律响亮拒（{@code
 *     SubagentRejectedException}，消息点名越界工具），不静默截断；求交结果为空是<b>合法</b>取值（= 子体一个工具都没有）， 不是错误
 * @param allowedSkills 想要给子体的<b>技能可见面</b>：条目是技能文件系统路径（技能文件夹或单个 {@code SKILL.md}）。{@code null} =
 *     不指定（不额外收窄：调用者可见面 ∩ 模板建议说了算）；空表 = <b>显式"一个技能都看不见"</b>（与 {@code allowedTools: []}
 *     同形同义）。<b>与模板的分工同 {@code allowedDirs}</b>：模板那一份是建议（超出调用者静默求交），请求这一份是<b>要求</b>——
 *     超出调用者可达面一律响亮拒（{@code SKILL_SCOPE_DENIED}），不静默截断
 * @param editableSkills 想要给子体的<b>技能可编辑面</b>（语义同 {@code allowedSkills}，判定对照调用者的可编辑面）。
 *     生效值恒被求交进最终可见面之内（"改得动但看不见"造不出来）
 * @param allowedDocs 想要给子体的<b>文档可见面</b>（R3b）：条目是文档面的物理路径前缀（{@code docs} 命名空间落格的就是挂载落点）。 {@code
 *     null} = 不指定（不额外收窄：调用者可见面 ∩ 模板建议说了算）；空表 = <b>显式"什么文档都看不见"</b>（与 {@code allowedTools: []}
 *     同形同义）。<b>与模板的分工同 {@code allowedDirs}</b>：模板那一份是建议（超出调用者静默求交），请求这一份是
 *     <b>要求</b>——超出调用者可达面一律响亮拒（{@code DOC_SCOPE_DENIED}），不静默截断
 * @param editableDocs 想要给子体的<b>文档可编辑面</b>（语义同 {@code allowedDocs}，判定对照调用者的可编辑面）。
 *     生效值恒被求交进最终可见面之内（{@code docs-edit ⊆ docs}）
 * @param roles 想要给子体的<b>角色</b>（设计 §4.5 的第 8 维）。{@code null} = 不指定（子体的角色由调用者角色 ∩ 模板基线说了算，
 *     逐字回到本字段引入前的行为）；空表 = <b>显式"一个角色都不给"</b>（与 {@code allowedTools: []} 同形同义）。<b>与模板的分工同 {@code
 *     allowedDirs}</b>：模板那一份是<b>建议</b>（超出调用者就静默求交），请求这一份是<b>要求</b>——点名调用者自己没有的角色， {@link
 *     SubagentManager#spawn} 一律响亮拒（{@code SubagentRejectedException}），不静默截断
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "allowedSkills/editableSkills/allowedDocs/editableDocs/roles 在规范构造里已 List.copyOf 成不可变快照；五者允许为 null"
            + "（null = 不指定，与空表是两种取值），那个 null 分支让 SpotBugs 的字段不可变性推断失效——记录型数据的防御拷贝，"
            + "不是暴露内部表示")
public record SubagentLaunchRequest(
    String templateId,
    String goal,
    Set<String> extraDenied,
    Integer maxTurnsCap,
    Long timeBudgetSecondsCap,
    Long quotaMaxTokensCap,
    CommandMode mode,
    List<String> allowedDirs,
    List<String> allowedTools,
    List<String> allowedSkills,
    List<String> editableSkills,
    List<String> allowedDocs,
    List<String> editableDocs,
    List<String> roles) {

  public SubagentLaunchRequest {
    if (templateId == null || templateId.isBlank()) {
      throw new IllegalArgumentException("templateId 不能为空");
    }
    if (goal == null || goal.isBlank()) {
      throw new IllegalArgumentException("goal 不能为空");
    }
    extraDenied = extraDenied == null ? Set.of() : Set.copyOf(extraDenied);
    if (allowedDirs != null) {
      for (String dir : allowedDirs) {
        if (dir == null || dir.isBlank()) {
          throw new IllegalArgumentException("allowedDirs 的元素必须是非空白路径文本: " + allowedDirs);
        }
      }
    }
    if (allowedTools != null) {
      for (String tool : allowedTools) {
        if (tool == null || tool.isBlank()) {
          throw new IllegalArgumentException("allowedTools 的元素必须是非空白工具名: " + allowedTools);
        }
      }
    }
    allowedSkills = checkSkillPaths("allowedSkills", allowedSkills);
    editableSkills = checkSkillPaths("editableSkills", editableSkills);
    // 文档范围（R3b）与技能范围同一口径同一个校验函数：元素非空白、null 保持 null（"没提这一嘴"与"显式空表"是两种取值）
    allowedDocs = checkSkillPaths("allowedDocs", allowedDocs);
    editableDocs = checkSkillPaths("editableDocs", editableDocs);
    // 第 8 维（角色）：元素非空白，且必须能无损落到 argv 文本线上（含分隔符的名字在构造期就响亮失败——
    // 静默切分会让子体拿到调用者没有的角色，撞红线 1；校验走 AgentRoles 的同一实现，本类不另写一套口径）
    if (roles != null) {
      for (String role : roles) {
        if (role == null || role.isBlank()) {
          throw new IllegalArgumentException("roles 的元素必须是非空白角色名: " + roles);
        }
      }
      roles = List.copyOf(roles);
      if (!roles.isEmpty()) {
        AgentRoles.requireWireSafe(RoleSet.of(roles.toArray(String[]::new)));
      }
    }
    // 不可变副本（内联 copyOf：让 SpotBugs 看得见来源）；null 保持 null——"没提这一嘴"与"显式空表"是两种取值
    allowedDirs = allowedDirs == null ? null : List.copyOf(allowedDirs);
    allowedTools = allowedTools == null ? null : List.copyOf(allowedTools);
  }

  /**
   * 范围条目的构造期校验（技能与文档四个字段共用；与 {@code allowedDirs}/{@code allowedTools} 同口径：元素必须非空白、null 保持 null）。
   *
   * <p>只校验“形态”，不在这里判越界——越界是 {@link SubagentManager#spawn} 的事（它才有调用者可达面这份真值）。
   */
  private static List<String> checkSkillPaths(String field, List<String> paths) {
    if (paths == null) {
      return null;
    }
    for (String path : paths) {
      if (path == null || path.isBlank()) {
        throw new IllegalArgumentException(field + " 的元素必须是非空白路径文本: " + paths);
      }
    }
    return List.copyOf(paths);
  }

  /**
   * 13 参兼容构造（角色维引入之前）：{@code roles = null} = 不指定（逐字回到本字段引入前的行为）。
   *
   * <p>与 11/9 参两条同理：请求面是模型/调用方每任务现造的对象，保留旧签名可以让既有构造点（编排工具、夹具）零改动。
   */
  public SubagentLaunchRequest(
      String templateId,
      String goal,
      Set<String> extraDenied,
      Integer maxTurnsCap,
      Long timeBudgetSecondsCap,
      Long quotaMaxTokensCap,
      CommandMode mode,
      List<String> allowedDirs,
      List<String> allowedTools,
      List<String> allowedSkills,
      List<String> editableSkills,
      List<String> allowedDocs,
      List<String> editableDocs) {
    this(
        templateId,
        goal,
        extraDenied,
        maxTurnsCap,
        timeBudgetSecondsCap,
        quotaMaxTokensCap,
        mode,
        allowedDirs,
        allowedTools,
        allowedSkills,
        editableSkills,
        allowedDocs,
        editableDocs,
        null);
  }

  /**
   * 11 参兼容构造（文档范围字段引入之前）：两个文档范围取 {@code null} = 不指定（逐字回到本字段引入前的行为）。
   *
   * <p>与 9 参那条同理：请求面是模型/调用方每任务现造的对象，保留旧签名可以让既有构造点（编排工具、夹具）零改动。
   */
  public SubagentLaunchRequest(
      String templateId,
      String goal,
      Set<String> extraDenied,
      Integer maxTurnsCap,
      Long timeBudgetSecondsCap,
      Long quotaMaxTokensCap,
      CommandMode mode,
      List<String> allowedDirs,
      List<String> allowedTools,
      List<String> allowedSkills,
      List<String> editableSkills) {
    this(
        templateId,
        goal,
        extraDenied,
        maxTurnsCap,
        timeBudgetSecondsCap,
        quotaMaxTokensCap,
        mode,
        allowedDirs,
        allowedTools,
        allowedSkills,
        editableSkills,
        null,
        null);
  }

  /**
   * 9 参兼容构造（技能范围字段引入之前）：两个技能范围取 {@code null} = 不指定（逐字回到本字段引入前的行为）。
   *
   * <p>请求面是模型/调用方每任务现造的对象，保留旧签名可以让既有构造点（编排工具、夹具）零改动。
   */
  public SubagentLaunchRequest(
      String templateId,
      String goal,
      Set<String> extraDenied,
      Integer maxTurnsCap,
      Long timeBudgetSecondsCap,
      Long quotaMaxTokensCap,
      CommandMode mode,
      List<String> allowedDirs,
      List<String> allowedTools) {
    this(
        templateId,
        goal,
        extraDenied,
        maxTurnsCap,
        timeBudgetSecondsCap,
        quotaMaxTokensCap,
        mode,
        allowedDirs,
        allowedTools,
        null,
        null);
  }

  /** 6 参兼容构造（S6 之前）：档位未指定、工作目录不指定。 */
  public SubagentLaunchRequest(
      String templateId,
      String goal,
      Set<String> extraDenied,
      Integer maxTurnsCap,
      Long timeBudgetSecondsCap,
      Long quotaMaxTokensCap) {
    this(
        templateId,
        goal,
        extraDenied,
        maxTurnsCap,
        timeBudgetSecondsCap,
        quotaMaxTokensCap,
        null,
        null);
  }

  /** 7 参兼容构造（S5-B 之前）：工作目录不指定。 */
  public SubagentLaunchRequest(
      String templateId,
      String goal,
      Set<String> extraDenied,
      Integer maxTurnsCap,
      Long timeBudgetSecondsCap,
      Long quotaMaxTokensCap,
      CommandMode mode) {
    this(
        templateId,
        goal,
        extraDenied,
        maxTurnsCap,
        timeBudgetSecondsCap,
        quotaMaxTokensCap,
        mode,
        null);
  }

  /** 8 参兼容构造（D27 之前）：工具白名单不指定（子体的工具面由模板说了算）。 */
  public SubagentLaunchRequest(
      String templateId,
      String goal,
      Set<String> extraDenied,
      Integer maxTurnsCap,
      Long timeBudgetSecondsCap,
      Long quotaMaxTokensCap,
      CommandMode mode,
      List<String> allowedDirs) {
    this(
        templateId,
        goal,
        extraDenied,
        maxTurnsCap,
        timeBudgetSecondsCap,
        quotaMaxTokensCap,
        mode,
        allowedDirs,
        null);
  }
}
