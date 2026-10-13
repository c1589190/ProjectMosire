package io.mosire.docs;

import java.nio.file.Path;
import java.util.Optional;

/**
 * {@code FILE} 纪律下一次写操作的<b>结果描述</b>（创建 / 编辑之后的快照）。
 *
 * <p><b>{@link #virtualPath()} 是面向模型的那一份，{@link #physical()} 只进日志</b>（A7/G4）：物理布局对模型不可见，"落盘在哪个目录"
 * 是宿主的事。
 *
 * @param virtualPath 虚拟路径（{@code <挂载名>/<相对路径>}）
 * @param bytes 字节数（UTF-8）
 * @param lines 行数
 * @param physical 物理落点（仅日志用）
 */
public record DocFile(String virtualPath, long bytes, long lines, Optional<Path> physical) {

  public DocFile {
    virtualPath = virtualPath == null ? "" : virtualPath;
    physical = physical == null ? Optional.empty() : physical;
  }
}
