package io.mosire.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * {@link Resolver} 的工厂：<b>v1 只交付 {@link #direct()}</b>（设计 §4.4 的 N8 推迟项——多物理根覆盖与分片不在这里）。
 *
 * <p><b>为什么只给一个实现还要一个工厂类</b>：扩展点必须"只有一个入口能造 resolver"，否则调用点会各自 {@code new} 一个自己的匿名实现，G1
 * 的兜底也就变成了唯一防线。要加 overlay/sharded 时，实现加在这里、签名不动，调用方零改动。
 */
public final class Resolvers {

  private static final Resolver DIRECT = new DirectResolver();

  private Resolvers() {
    throw new AssertionError("No instances");
  }

  /**
   * 一对一映射（v1 唯一实现）：虚拟相对路径就是物理相对路径。
   *
   * <p>返回的是<b>无状态单例</b>：它不持有任何调用者数据，可以安全共享。
   */
  public static Resolver direct() {
    return DIRECT;
  }

  /** 一对一：{@code dir.resolve(relativePath).normalize()}；越界与非法拼法一律抛（fail-closed）。 */
  private static final class DirectResolver implements Resolver {

    @Override
    public Path resolve(Path dir, String relativePath) {
      Path base = baseDir(dir);
      String relative = relativePath == null ? "" : relativePath.strip();
      if (relative.isEmpty()) {
        return base;
      }
      requireRelative(relative);
      try {
        return base.resolve(relative).normalize();
      } catch (InvalidPathException e) {
        // 含 NUL 等无法成为路径的字符：这不是"没有这个文件"，而是输入非法 ⇒ 不猜
        throw new WorkspaceException(
            WorkspaceException.INVALID_ARGUMENTS, "挂载内的相对路径不是合法路径（含无法成为路径的字符）");
      }
    }

    @Override
    public List<String> list(Path dir, String relativePrefix) {
      Path base = baseDir(dir);
      String prefix = relativePrefix == null ? "" : relativePrefix.strip();
      if (!prefix.isEmpty()) {
        requireRelative(prefix);
      }
      Path target;
      try {
        target = prefix.isEmpty() ? base : base.resolve(prefix).normalize();
      } catch (InvalidPathException e) {
        throw new WorkspaceException(
            WorkspaceException.INVALID_ARGUMENTS, "挂载内的相对前缀不是合法路径（含无法成为路径的字符）");
      }
      if (!Files.isDirectory(target)) {
        // 目录不在盘上/不是目录：fail-closed（空表会与"空目录"长得一样，分不清是"没有"还是"读不到"）
        throw new WorkspaceException(
            WorkspaceException.WORKSPACE_NOT_FOUND,
            "挂载内没有可列出的目录: " + (prefix.isEmpty() ? "<挂载根>" : prefix));
      }
      List<String> entries = new ArrayList<>();
      try (Stream<Path> children = Files.list(target)) {
        for (Path child : children.toList()) {
          Path name = child.getFileName();
          if (name == null) {
            // Files.list 的子项必有名字；这里不解引用可能为 null 的返回值——真出现也只跳过这一条，不改变其它条目
            continue;
          }
          entries.add(join(prefix, name.toString()));
        }
      } catch (IOException e) {
        throw new WorkspaceException(
            WorkspaceException.WORKSPACE_NOT_FOUND,
            "挂载内的目录读不出来（fail-closed）: " + (prefix.isEmpty() ? "<挂载根>" : prefix),
            e);
      }
      // 排序：投影结果不该随文件系统返回顺序抖动（模型与用例都更容易对账）
      entries.sort(null);
      return List.copyOf(entries);
    }

    /** 物理根：必须绝对路径（相对根会让"落在挂载内"变成"落在进程 cwd 里"，那是另一个地方）。 */
    private static Path baseDir(Path dir) {
      Objects.requireNonNull(dir, "dir");
      if (!dir.isAbsolute()) {
        throw new WorkspaceException(WorkspaceException.INVALID_ARGUMENTS, "挂载的物理根必须是绝对路径");
      }
      return dir.normalize();
    }

    /** 相对拼法的守卫（G3）：不得以 {@code /} 开头、不得含空段与 {@code .}/{@code ..} 段。 */
    private static void requireRelative(String relative) {
      if (relative.startsWith("/")) {
        throw new WorkspaceException(
            WorkspaceException.INVALID_ARGUMENTS, "挂载内的相对路径不得以 '/' 开头: " + relative);
      }
      for (String segment : relative.split("/", -1)) {
        if (segment.isEmpty()) {
          throw new WorkspaceException(
              WorkspaceException.INVALID_ARGUMENTS, "挂载内的相对路径不得含空段（重复 '/'）: " + relative);
        }
        if (".".equals(segment) || "..".equals(segment)) {
          throw new WorkspaceException(
              WorkspaceException.INVALID_ARGUMENTS, "挂载内的相对路径不得含 '.' 或 '..' 段: " + relative);
        }
      }
    }

    /** 前缀 + 子项名 → 相对 {@code dir} 的路径（POSIX 分隔符）。 */
    private static String join(String prefix, String name) {
      return prefix.isEmpty() ? name : prefix + "/" + name;
    }
  }
}
