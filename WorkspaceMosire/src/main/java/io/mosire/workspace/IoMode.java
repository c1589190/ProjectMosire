package io.mosire.workspace;

/**
 * 文件 IO 解析模式（{@link WorkspaceRegistry#resolveForIo} 的入参）。
 *
 * <p>它与 {@link SymlinkPolicy} 一起把“词法映射”和“安全 IO”分开：
 *
 * <ul>
 *   <li>{@link #READ}：目标必须存在，按策略处理软链；
 *   <li>{@link #WRITE_EXISTING}：目标必须存在，写路径永远拒绝软链；
 *   <li>{@link #CREATE}：目标可以不存在，写路径永远拒绝软链。
 * </ul>
 */
public enum IoMode {
  READ,
  WRITE_EXISTING,
  CREATE
}
