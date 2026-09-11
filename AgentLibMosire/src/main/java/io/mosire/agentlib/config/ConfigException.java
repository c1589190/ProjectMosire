package io.mosire.agentlib.config;

/**
 * 配置存储的失败信号：每条都带<b>非敏感错误码</b>（{@code E_…}），供上层（工具、事件库）在不接触 cause 的情况下分类归因——T18 复审 Minor
 * 3：把失败统一成固定文案会让事件库里所有同类失败不可分辨； 错误码是分类的权威，不要解析 message 文本。
 *
 * <p><b>消息安全契约（硬性，源自 T18 R2 与评审 Minor 3 的升级条件）</b>：本异常的 {@code message} 只允许出现错误码、身份级别名、文件路径、schema
 * 关键字名等<b>结构性</b>信息；<b>绝不</b>拼接配置值 （尤其凭据值或内嵌凭据的 URL）。该契约有判别性用例（{@code ConfigStoreTest}，凭据样串 + 触发失败
 * → 异常链所有 message 均不含该串）。新增抛出点时必须遵守。
 */
public final class ConfigException extends RuntimeException {

  /** 越权写入被拒（前缀授权拒绝，含不合规键的 deny-by-default）。 */
  public static final String E_PREFIX_DENIED = "E_PREFIX_DENIED";

  /** schema 校验未通过（写入前校验，或写后读回校验——后者已回滚）。 */
  public static final String E_SCHEMA_INVALID = "E_SCHEMA_INVALID";

  /** schema 不可用（文件缺失/不可读/本身不是合法 JSON Schema）。 */
  public static final String E_SCHEMA_UNAVAILABLE = "E_SCHEMA_UNAVAILABLE";

  /** 既有配置文件损坏（不是合法 JSON，或顶层不是 JSON 对象）。 */
  public static final String E_CONFIG_CORRUPT = "E_CONFIG_CORRUPT";

  /** 文件 I/O 失败（读写/备份/回滚/原子移动不支持等）。 */
  public static final String E_CONFIG_IO = "E_CONFIG_IO";

  private final String code;

  public ConfigException(String code, String message) {
    super(code + ": " + message);
    this.code = code;
  }

  public ConfigException(String code, String message, Throwable cause) {
    super(code + ": " + message, cause);
    this.code = code;
  }

  /** 非敏感错误码（{@code E_…} 常量之一）。 */
  public String code() {
    return code;
  }
}
