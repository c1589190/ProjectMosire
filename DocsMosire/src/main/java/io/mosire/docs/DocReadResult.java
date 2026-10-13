package io.mosire.docs;

import java.util.List;
import java.util.Optional;

/**
 * 一次 {@code FILE} 读取的结果（设计 §5.5：{@code doc_read(mount, path, lines?)}，{@code lines} 缺省全文、给了就只回该区间）。
 *
 * <p><b>为什么不是裸 {@code String}</b>：设计 §5.3 要求坏数据进 {@code broken} 披露——裸字符串没有地方放它，于是"文件里有一段读不出来"
 * 只能靠抛异常（毁掉"继续读"）或静默（毁掉"不静默"）。这里把它显式带出来。
 *
 * <p><b>{@link #path()} 是虚拟路径</b>（A7/G4）：物理落点只进日志。
 *
 * @param path 虚拟路径（{@code <挂载名>/<相对路径>}）
 * @param content 读到的文本（按 {@code lines} 区间裁剪后；换行符规范化成 {@code \n}）
 * @param fromLine 本段内容在文件里的起始行号（1 起；整文件读时为 1）
 * @param toLine 本段内容在文件里的结束行号（1 起；空文件为 0）
 * @param totalLines 文件总行数
 * @param bytes 文件字节数
 * @param broken 本次读取中不静默披露的坏数据（可为空表）
 */
public record DocReadResult(
    String path,
    String content,
    long fromLine,
    long toLine,
    long totalLines,
    long bytes,
    List<BrokenDoc> broken) {

  public DocReadResult {
    path = path == null ? "" : path;
    content = content == null ? "" : content;
    broken = broken == null ? List.of() : List.copyOf(broken);
  }

  /** 内容区间（供工具面渲染 {@code lines="10,40"} 的回应）。 */
  public Optional<String> rangeLabel() {
    if (totalLines == 0) {
      return Optional.of("空文件");
    }
    if (fromLine == 1 && toLine == totalLines) {
      return Optional.empty();
    }
    return Optional.of(fromLine + "," + toLine);
  }
}
