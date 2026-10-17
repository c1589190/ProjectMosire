package io.mosire.workspace;

/**
 * 安全 IO 解析时的软链策略。
 *
 * <p>{@link #REJECT} 是默认值：路径中任何一段是软链都拒。{@link #RESOLVE_WITHIN_ROOT} 只对 {@link IoMode#READ}
 * 开放：允许读软链，但真实目标必须仍在挂载的真实根内。写路径（WRITE_EXISTING / CREATE）永远拒绝软链， 不提供“可写软链”的选项。
 */
public enum SymlinkPolicy {
  REJECT,
  RESOLVE_WITHIN_ROOT
}
