package io.mosire.brain.subagent;

/**
 * 工作目录越界（S5-B）：spawn 请求里的 {@code allowedDirs} 要了调用者自己都够不着的地方。
 *
 * <p><b>为什么与 {@link SubagentRejectedException} 分开</b>：工具层要把它落成独立错误码 {@code DIR_NOT_ALLOWED} （裁决
 * ⑥）——模型看到 {@code PERMISSION_DENIED} 会以为"换条路再试"（那是工具/身份面的事），看到 {@code DIR_NOT_ALLOWED}
 * 才知道"是我要的目录不在上级可达面里"，据以改成要一个够得着的目录继续干活。合成一个码会让模型去试别的提权路径。
 *
 * <p><b>为什么请求面是硬拒而不是静默求交</b>：模板那一份是<b>建议</b>（通用配置，被上级收紧是常态，求交即可），请求这一份是<b>要求</b>——
 * 调用者明确说"给子体这个目录"，如果它自己都没有，静默求交会产出一个"看起来配了、实际没生效"的子体，而真正的原因（上级不允许） 在启动那一刻没人知道。两个方向都错得安静，所以这里选择响亮。
 */
public final class WorkingDirDeniedException extends SubagentRejectedException {

  private static final long serialVersionUID = 1L;

  public WorkingDirDeniedException(String message) {
    super(message);
  }
}
