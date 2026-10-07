package io.mosire.brain.subagent;

/**
 * 技能范围越界：spawn 请求里的 {@code allowedSkills}/{@code editableSkills} 要了调用者自己都够不着的技能路径。
 *
 * <p><b>为什么与 {@link SubagentRejectedException} 分开</b>：工具层要把它落成独立错误码 {@code SKILL_SCOPE_DENIED}——
 * 模型看到 {@code PERMISSION_DENIED} 会以为“换条路再试”，看到 {@code DIR_NOT_ALLOWED} 会以为只是目录问题；
 * 只有点名“技能范围”它才会去改成要一个自己看得见的技能范围（或改用技能工具处理）。合成一个码会让模型去试别的提权路径。
 *
 * <p><b>为什么请求面是硬拒而不是静默求交</b>：与 {@link WorkingDirDeniedException} 同一条理由——模板那一份是<b>建议</b>，
 * 请求这一份是<b>要求</b>；静默求交会产出一个“看起来给了技能、实际一个都看不见”的子体，而原因（上级不允许）在启动那一刻没人知道。
 */
public final class SkillScopeDeniedException extends SubagentRejectedException {

  private static final long serialVersionUID = 1L;

  public SkillScopeDeniedException(String message) {
    super(message);
  }
}
