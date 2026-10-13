package io.mosire.brain.subagent;

/**
 * 文档范围越界：spawn 请求里的 {@code allowedDocs}/{@code editableDocs} 要了调用者自己都够不着的文档路径。
 *
 * <p><b>为什么与 {@link SubagentRejectedException} 分开</b>：与 {@link SkillScopeDeniedException}
 * 同一条理由——工具层要把它落成 独立错误码 {@code DOC_SCOPE_DENIED}。模型看到 {@code PERMISSION_DENIED}
 * 会去换模板/换身份接着撞，看到点名"文档范围"才会把要求改成 一个自己够得着的范围；合成一个码等于把"这次要的东西不成立"伪装成"你不该用这个工具"。
 *
 * <p><b>为什么请求面是硬拒而不是静默求交</b>：模板那一份是<b>建议</b>（部署者写的通用配置，被上级收紧是常态），请求这一份是<b>要求</b>
 * （调用者当刻点名要的东西）；静默求交会产出一个"看起来给了文档面、实际什么都写不了"的子体，而真正的原因（上级不允许）在启动那一刻 没人知道——与 {@code
 * allowedDirs}/{@code allowedSkills} 的既有口径逐字同形。
 */
public final class DocScopeDeniedException extends SubagentRejectedException {

  private static final long serialVersionUID = 1L;

  public DocScopeDeniedException(String message) {
    super(message);
  }
}
