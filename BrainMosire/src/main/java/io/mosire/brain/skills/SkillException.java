package io.mosire.brain.skills;

/**
 * 技能操作的一次响亮失败：带<b>稳定的错误码</b> + 面向模型可读的消息。
 *
 * <p>为什么需要独立异常类型（而不是直接返回 {@code ToolResult.error}）：技能能力有<b>两个</b>调用面——工具面（Agent 经 {@code skill_*}
 * 工具调用）与装配面（上下文预注入按可见面过滤、子 Agent 物化范围）。失败语义要在两个面上一致，就不能把
 * “哪种失败”这件事埋在工具实现里；本异常是那段共享语义的载体，工具面在边界上转成错误码。
 *
 * <p><b>与 {@code ResourceDeniedException} 的分工</b>：<b>权限面</b>的拒绝走后者（它由工具调用唯一入口 {@code
 * ToolCallAuthorizer} 统一转成 {@code RESOURCE_DENIED}，且是既定 SPI 的唯一回传路径，不许被替代）；
 * 本异常只承载<b>权限之外的</b>失败（不存在、已存在、frontmatter 非法、读写失败、重扫失败）。两者都不许被吞。
 */
public class SkillException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 技能不存在<b>或调用者看不见</b>（两者刻意同码同消息：否则工具面会变成隐藏技能的“存在性探测器”）。 */
  public static final String SKILL_NOT_FOUND = "SKILL_NOT_FOUND";

  /** 创建时目标技能目录/文档已存在（不覆盖——覆盖已有技能走 {@code skill_edit}）。 */
  public static final String SKILL_EXISTS = "SKILL_EXISTS";

  /** 参数非法（技能名不合形态、description 为空/含换行、allowed-tools 元素含分隔符等）。 */
  public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";

  /** 写入内容过不了 frontmatter 校验（与读盘同一段解析，见 {@code SkillLoader#parse}）。 */
  public static final String SKILL_INVALID = "SKILL_INVALID";

  /** 读盘失败（文件被删/不可读）。 */
  public static final String SKILL_READ_FAILED = "SKILL_READ_FAILED";

  /** 落盘失败（目录建不出来、临时文件写不动、原子替换失败）。 */
  public static final String SKILL_WRITE_FAILED = "SKILL_WRITE_FAILED";

  /** 写入已成功但目录重扫失败——此刻盘上状态与快照不一致，必须响亮（不许“写成功、列表看不见”静默共存）。 */
  public static final String SKILL_RELOAD_FAILED = "SKILL_RELOAD_FAILED";

  private final String code;

  public SkillException(String code, String message) {
    this(code, message, null);
  }

  public SkillException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  /** 稳定错误码（工具面直接把它当 {@code ToolResult} 的 code 用）。 */
  public String code() {
    return code;
  }
}
