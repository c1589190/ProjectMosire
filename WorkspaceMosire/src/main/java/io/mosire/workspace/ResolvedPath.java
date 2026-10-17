package io.mosire.workspace;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 一次安全 IO 解析的结果（{@link WorkspaceRegistry#resolveForIo}）。
 *
 * <p>{@code physicalPath} 是按挂载 + 相对路径拼出的目标路径；{@code realPath} 是经过真实路径检查后的路径。对 {@link IoMode#CREATE}
 * 且目标尚不存在的情形，{@code realPath} 是“最近存在父目录的真实路径 + 尾段”，因此它可能尚不存在， 但仍必须落在挂载的真实根内。
 *
 * <p>本记录只在进程内流转；物理路径不得进入面向模型的错误消息、事件载荷或工具结果。
 *
 * @param mount 解析归位到的挂载
 * @param relativePath 挂载内相对路径（空串 = 挂载根）
 * @param physicalPath 依挂载拼出的目标路径（绝对、归一）
 * @param realPath 真实路径检查结果（绝对、归一；CREATE 时可能尚不存在）
 * @param exists 目标当前是否存在
 */
public record ResolvedPath(
    Mount mount, String relativePath, Path physicalPath, Path realPath, boolean exists) {

  public ResolvedPath {
    Objects.requireNonNull(mount, "mount");
    relativePath = relativePath == null ? "" : relativePath;
    Objects.requireNonNull(physicalPath, "physicalPath");
    Objects.requireNonNull(realPath, "realPath");
    if (!physicalPath.isAbsolute() || !physicalPath.normalize().equals(physicalPath)) {
      throw new IllegalArgumentException("physicalPath 必须是绝对、已归一路径");
    }
    if (!realPath.isAbsolute() || !realPath.normalize().equals(realPath)) {
      throw new IllegalArgumentException("realPath 必须是绝对、已归一路径");
    }
  }
}
