package io.mosire.workspace;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 喂 bash 侧的<b>物理落点</b>清单条目（设计 §4.3、§4.8）。
 *
 * <p><b>虚拟层不下发给插件</b>：清单里是物理路径 + 命名空间 + 纪律——执行侧只需要回答"这条路径对这次调用可见吗、能不能写"， 它不必理解虚拟路径空间（那是本模块的事）。
 *
 * <p><b>命名空间是条目的一部分，不是工作区类型的枚举</b>：R1 收编后 {@code SandboxMask} 已命名空间无关（清单条目自带 {@code namespace}，判定读
 * {@code declaredScope(条目.namespace)}）；本模块按<b>能力落格的命名空间</b>产出条目，因此"能力 换了落格命名空间"不需要改执行侧一行。
 *
 * <p><b>G7（逐条目求与）由执行侧承担</b>：同一物理路径被多条条目声明（多个命名空间）时，只要有一条判成不可见就<b>遮蔽</b> ——{@code SandboxMask.fence}
 * 已按路径求与。本模块因此<b>不去重同一路径的不同命名空间条目</b>：那正是 G7 要看见的信息。
 *
 * @param namespace 该条目所属命名空间（调用者权限集里 {@code resourceScopes} 的键）
 * @param path 物理落点（宿主绝对路径）
 * @param kind 该挂载的纪律——执行侧据此决定只读 bind 还是可写（{@link Kind} 的表）
 */
public record MaskEntry(String namespace, Path path, Kind kind) {

  public MaskEntry {
    namespace = namespace == null ? "" : namespace.strip();
    if (namespace.isEmpty()) {
      throw new IllegalArgumentException("遮蔽面条目的命名空间不能为空");
    }
    Objects.requireNonNull(path, "path");
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException("遮蔽面条目的路径必须是绝对路径: " + path);
    }
    Objects.requireNonNull(kind, "kind");
  }
}
