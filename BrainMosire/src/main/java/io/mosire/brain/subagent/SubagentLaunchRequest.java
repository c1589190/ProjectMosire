package io.mosire.brain.subagent;

import io.mosire.agentlib.permission.CommandMode;
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
 */
public record SubagentLaunchRequest(
    String templateId,
    String goal,
    Set<String> extraDenied,
    Integer maxTurnsCap,
    Long timeBudgetSecondsCap,
    Long quotaMaxTokensCap,
    CommandMode mode,
    List<String> allowedDirs,
    List<String> allowedTools) {

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
    // 不可变副本（内联 copyOf：让 SpotBugs 看得见来源）；null 保持 null——"没提这一嘴"与"显式空表"是两种取值
    allowedDirs = allowedDirs == null ? null : List.copyOf(allowedDirs);
    allowedTools = allowedTools == null ? null : List.copyOf(allowedTools);
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
