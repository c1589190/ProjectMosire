package io.mosire.docs;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code FILE} 纪律的存储实现（设计 §5.3/§5.5）：<b>普通文件</b>（虚拟路径 → 物理文件，一对一）+ <b>自实现 IO</b>（N13）。
 *
 * <p><b>原子写的唯一做法</b>：同目录临时文件 → {@code ATOMIC_MOVE}。{@code Files.move} 在<b>同一文件系统内</b>是原子的（POSIX
 * {@code rename}），因此盘上永远只存在"旧的完整内容"或"新的完整内容"，不会出现写一半的文档。临时文件放<b>同目录</b>：跨设备 {@code rename} 会失败，
 * 而失败在"写文档"这条路上等于不可用——放同目录让原子性成为可能，而不是靠运气。
 *
 * <p><b>创建不覆盖</b>（{@code DOC_EXISTS}）：撞名具名拒，不静默覆盖（B6）。实现顺序写死为"先落定临时文件 → 目标已存在则删临时文件并拒 → 否则 {@code
 * ATOMIC_MOVE}"——先写后判让"判与写之间被抢先创建"的窗口最小，且窗口内的结果仍是"拒"，不是"覆盖"。
 *
 * <p><b>多项编辑全成或全不成</b>（B8/B10）：全部操作在内存里施加于同一份行视图，任何一条失败（锚点不命中/不唯一、{@code expect} 不符、行号越界）
 * 都整体抛，且<b>盘上不动一个字</b>——写盘只发生在所有操作都成功之后。
 *
 * <p><b>坏文件不静默</b>：读不出来的文件进 {@link #broken()} 披露；{@link #read} 对"面内不存在"（{@code
 * DOC_NOT_FOUND}）与"读失败"（ {@code DOC_READ_FAILED}）分码——这不是存在性泄露，因为"能否访问这个挂载"已经过了工作区解析点 + 资源面两道准入。
 *
 * <p><b>面向模型的输入输出只有相对路径与虚拟路径</b>（A7）：本类接收挂载内<b>相对路径</b>（映射由 {@link DocsEngine} 交给工作区解析点完成），
 * 对外渲染走注入的 {@link #labelOf}（相对路径 → 虚拟路径）。
 *
 * <p><b>软链与 TOCTOU 的诚实边界</b>：基目录是工作区 {@code resolveForIo} 给出的安全真实根；本类在每次读写前逐段 {@code
 * isSymbolicLink} 检查，读/建/替换都用 {@code NOFOLLOW_LINKS} 或 {@code CREATE_NEW}，临时文件与目标同目录并走 {@code
 * ATOMIC_MOVE}。未实现 {@code SecureDirectoryStream} 的目录 fd 相对操作，因此“逐段检查”与“真正 open/rename”
 * 之间仍有一个竞争窗口；这里不静默退回“跟随软链的普通 IO”，窗口如实记录在这里。
 */
final class DiskDocFiles implements DocFileStore {

  private static final Logger LOG = LoggerFactory.getLogger(DiskDocFiles.class);

  private final Path baseDir;
  private final Function<String, String> labelOf;
  private final Object lock = new Object();
  private final List<BrokenDoc> broken = new ArrayList<>();
  private final AtomicLong temporaryCounter = new AtomicLong();

  DiskDocFiles(Path baseDir, Function<String, String> labelOf) {
    // 基目录必须是绝对、归一的安全真实根：相对根会把落点变成“进程 cwd 下的某个地方”。
    this.baseDir =
        java.util.Objects.requireNonNull(baseDir, "baseDir").toAbsolutePath().normalize();
    this.labelOf = java.util.Objects.requireNonNull(labelOf, "labelOf");
  }

  @Override
  public DocReadResult read(String relativePath, String lines) {
    synchronized (lock) {
      String label = labelOf.apply(relativePath);
      Path file = resolve(relativePath, label);
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        throw DocException.notFound("面内不存在这个文档（不存在与不可见在这里是同一个回答）: " + label);
      }
      byte[] bytes;
      try {
        bytes = readNoFollow(file, label);
      } catch (IOException e) {
        noteBroken(label, 0, "文件读不出来", file);
        throw DocException.readFailed("文档读不出来（没有把它当成空文件）: " + label, e);
      }
      String text = decode(bytes, label, file);
      List<String> all = splitLines(text);
      long total = all.size();
      LineRange range = LineRange.parse(lines, total);
      List<String> selected =
          range.to() < range.from()
              ? List.of()
              : all.subList((int) range.from() - 1, (int) range.to());
      String content = String.join("\n", selected);
      if (!selected.isEmpty()) {
        content = content + "\n";
      }
      return new DocReadResult(
          label, content, range.from(), range.to(), total, bytes.length, List.copyOf(broken));
    }
  }

  /**
   * 原子创建（撞名拒）。
   *
   * <p>THROWS_METHOD_THROWS_RUNTIMEEXCEPTION 抑制：本契约的失败一律是 {@link DocException}（{@code
   * RuntimeException} 子类 + 稳定错误码，见其类 javadoc），{@code catch (RuntimeException e)} 只为"删掉临时文件后原样重抛"
   * ——清理不是吞异常；本方法没有更窄的异常类型可声明。
   */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification =
          "创建失败=DocException（RuntimeException 子类，稳定错误码）原样上抛是本契约；catch 只为删临时文件后重抛，"
              + "无更窄的异常类型可用（与 App.start / BashSandbox 同款先例）。")
  @Override
  public DocFile create(String relativePath, String text) {
    synchronized (lock) {
      String label = labelOf.apply(relativePath);
      Path file = resolve(relativePath, label);
      if (file.equals(baseDir)) {
        // 落点就是挂载根：它不是“一个文档文件”，且临时文件会落到挂载根之外（等于在范围外写）
        throw DocException.invalid("文档落点不能是挂载根（要创建文件请给出挂载内的相对路径）: " + label);
      }
      Path parent = file.getParent();
      if (parent == null) {
        // 落点没有父目录 = 挂载根解析成了"根/空路径"：装配/输入已坏，不能继续。
        // 此刻盘上还没有任何新东西（临时文件尚未落定），文案只出虚拟路径、不出物理落点
        throw DocException.writeFailed("文档落点没有父目录（挂载根解析异常）: " + label, null);
      }
      ensureDirectory(parent, label);
      String content = normalize(text);
      byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
      Path temporary = temporaryPath(file);
      try {
        writeNewFile(temporary, bytes, label);
      } catch (RuntimeException e) {
        // 临时文件可能已经以 CREATE_NEW 落定：失败时删掉，别在盘上留半成品
        deleteQuietly(temporary);
        throw e;
      }
      try {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
          // 撞名拒（B6）：先落定临时文件再判，窗口最小；判否只删临时文件，目标一个字节都不动
          throw DocException.exists("文档已存在（创建不覆盖；要改内容请用 doc_edit）: " + label);
        }
        requireSafeTarget(file, label);
        atomicMove(temporary, file, label);
      } catch (RuntimeException e) {
        deleteQuietly(temporary);
        throw e;
      }
      LOG.debug("docs.file.create 虚拟路径={} 物理落点={}", label, file);
      return new DocFile(label, bytes.length, countLines(content), Optional.of(file));
    }
  }

  /**
   * 行寻址编辑（多项全成或全不成；失败时盘上不动一个字）。
   *
   * <p>THROWS_METHOD_THROWS_RUNTIMEEXCEPTION 抑制：同 {@link #create}——失败一律是 {@link
   * DocException}（{@code RuntimeException} 子类 + 稳定错误码），{@code catch (RuntimeException e)}
   * 只为清理临时文件后原样重抛。
   */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification =
          "编辑失败=DocException（RuntimeException 子类，稳定错误码）原样上抛是本契约；catch 只为删临时文件后重抛，"
              + "无更窄的异常类型可用（同 create）。")
  @Override
  public DocFile edit(String relativePath, List<EditOp> ops) {
    if (ops == null || ops.isEmpty()) {
      throw DocException.invalid("doc_edit 至少需要一个操作（空操作列表是调用方的笔误，不是\"不变\"）");
    }
    synchronized (lock) {
      String label = labelOf.apply(relativePath);
      Path file = resolve(relativePath, label);
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        throw DocException.notFound("面内不存在这个文档（不存在与不可见在这里是同一个回答）: " + label);
      }
      byte[] originalBytes;
      try {
        originalBytes = readNoFollow(file, label);
      } catch (IOException e) {
        throw DocException.readFailed("文档读不出来（编辑前必须先读到全文，否则无法保证全成或全不成）: " + label, e);
      }
      String original = decode(originalBytes, label, file);
      // 全部操作先在内存里施加（任何一条失败 ⇒ 整体抛，盘上不动一个字）
      List<String> working = new ArrayList<>(splitLines(original));
      for (EditOp op : ops) {
        apply(working, op, label);
      }
      String updated = String.join("\n", working);
      if (!working.isEmpty()) {
        updated = updated + "\n";
      }
      byte[] bytes = updated.getBytes(StandardCharsets.UTF_8);
      Path temporary = temporaryPath(file);
      try {
        writeNewFile(temporary, bytes, label);
      } catch (RuntimeException e) {
        deleteQuietly(temporary);
        throw e;
      }
      try {
        requireSafeTarget(file, label);
        atomicMove(temporary, file, label);
      } catch (RuntimeException e) {
        deleteQuietly(temporary);
        throw e;
      }
      LOG.debug("docs.file.edit 虚拟路径={} 物理落点={} 操作数={}", label, file, ops.size());
      return new DocFile(label, bytes.length, working.size(), Optional.of(file));
    }
  }

  /** 已观察到的坏数据（读失败等）。 */
  List<BrokenDoc> broken() {
    synchronized (lock) {
      return List.copyOf(broken);
    }
  }

  // ── 内部：编辑语义 ───────────────────────────────────────────────────────────────────────────

  /**
   * 在行视图上施加一条操作（<b>纯内存</b>：调用方负责"全部成功才写盘"）。
   *
   * <p>寻址规则：行号/行区间按 1 起闭区间；锚点要求整行逐字相等且<b>唯一</b>命中（0 处或多处都拒）。
   */
  private static void apply(List<String> lines, EditOp op, String label) {
    DocAddr addr = op.addr();
    long total = lines.size();
    int from;
    int to;
    String addressed;
    if (addr.byAnchor()) {
      List<Integer> hits = new ArrayList<>();
      for (int i = 0; i < lines.size(); i++) {
        if (lines.get(i).equals(addr.anchor())) {
          hits.add(i);
        }
      }
      if (hits.isEmpty()) {
        throw DocException.invalid("锚点不命中（一行都没匹配上，盘上未改动）: " + label + " " + addr.label());
      }
      if (hits.size() > 1) {
        throw DocException.invalid(
            "锚点不唯一（命中 " + hits.size() + " 处，盘上未改动；请改用行区间）: " + label + " " + addr.label());
      }
      from = hits.get(0);
      to = from;
      addressed = lines.get(from);
    } else {
      if (addr.from() > total || addr.to() > total) {
        throw DocException.invalid(
            "行号越界（文件共 " + total + " 行，盘上未改动）: " + label + " " + addr.label());
      }
      from = addr.from() - 1;
      to = addr.to() - 1;
      addressed = String.join("\n", lines.subList(from, to + 1));
    }
    // 前置断言（expect）：把"我以为这段还是老样子"变成可检验的前置条件
    op.expect()
        .ifPresent(
            expected -> {
              if (!expected.equals(addressed)) {
                throw DocException.invalid(
                    "expect 不符（寻址到的原文与断言不一致，盘上未改动）: " + label + " " + addr.label());
              }
            });
    switch (op.kind()) {
      case REPLACE -> {
        lines.subList(from, to + 1).clear();
        lines.addAll(from, insertLines(op.text()));
      }
      case DELETE -> lines.subList(from, to + 1).clear();
      case INSERT_AFTER -> lines.addAll(to + 1, insertLines(op.text()));
      case INSERT_BEFORE -> lines.addAll(from, insertLines(op.text()));
    }
  }

  /** 插入文本 → 行列表（末尾换行不产生"多一个空行"：{@code "a\n"} 是插入一行 {@code a}）。 */
  private static List<String> insertLines(String text) {
    if (text.isEmpty()) {
      // 插入空文本 = 插入一个空行（与"把这一行改成空行"是两件事：前者行数 +1）
      return new ArrayList<>(List.of(""));
    }
    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
    List<String> parts = new ArrayList<>(List.of(normalized.split("\n", -1)));
    if (!parts.isEmpty() && parts.get(parts.size() - 1).isEmpty()) {
      parts.remove(parts.size() - 1);
    }
    return parts;
  }

  // ── 内部：IO ────────────────────────────────────────────────────────────────────────────────

  /**
   * 挂载内相对路径 → 物理落点（挂载根已是工作区解析点给出的安全真实根；本类不做虚拟路径映射）。
   *
   * <p><b>逐段软链拒绝</b>：{@code resolveForIo} 已经判过一次，但“检查”和“真正打开”之间有竞争窗口；这里在打开前按当前文件系统 状态再逐段查一遍，读/写都以
   * {@code NOFOLLOW_LINKS} 打开。不跟随软链是本类的硬边界，不是调用方的可选策略。
   */
  private Path resolve(String relativePath, String label) {
    requireSafeBaseDir(label);
    String relative = relativePath == null ? "" : relativePath.strip();
    if (relative.isEmpty()) {
      return baseDir;
    }
    Path current = baseDir;
    for (String segment : relative.split("/", -1)) {
      if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
        // 引擎已经校验过拼法；这里再拒一次是纵深防御（绝不允许相对路径逃出挂载根）
        throw DocException.denied("挂载内相对路径不是合法形态（fail-closed）: " + label);
      }
      Path next = current.resolve(segment);
      if (Files.isSymbolicLink(next)) {
        throw DocException.denied("路径中包含软链（默认拒绝，不跟随）: " + label);
      }
      current = next;
    }
    return current;
  }

  private void requireSafeBaseDir(String label) {
    if (Files.isSymbolicLink(baseDir)) {
      throw DocException.denied("挂载根是软链（默认拒绝，不跟随）: " + label);
    }
  }

  /**
   * 以 {@code CREATE_NEW + NOFOLLOW_LINKS} 写临时文件（绝不覆盖、绝不跟随软链）。
   *
   * <p>临时文件与目标同目录：同文件系统的 {@code rename} 才可能是原子的；跨设备会失败，而失败在这里等于文档不可用。
   */
  private static void writeNewFile(Path temporary, byte[] bytes, String label) {
    try (FileChannel channel =
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    } catch (UnsupportedOperationException e) {
      throw DocException.writeFailed("文件系统不支持 NOFOLLOW_LINKS（拒绝退回跟随软链的普通写）: " + label, e);
    } catch (IOException e) {
      throw DocException.writeFailed("临时文件写不出来（盘上未新增任何东西）: " + label, e);
    }
  }

  /** 读：{@code NOFOLLOW_LINKS} 打开（与逐段检查构成两层防线）。 */
  private static byte[] readNoFollow(Path file, String label) throws IOException {
    try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      return input.readAllBytes();
    } catch (UnsupportedOperationException e) {
      throw DocException.readFailed("文件系统不支持 NOFOLLOW_LINKS（拒绝退回跟随软链的普通读）: " + label, e);
    }
  }

  /**
   * 原子替换前的复核：真实父目录与（若存在的）目标真实路径都必须仍在安全真实根内，且目标不是软链。
   *
   * <p>这是“检查—替换”之间竞争窗口的最小加固；真正无窗口的做法是 fd 相对操作（{@code SecureDirectoryStream}），本实现 未使用它，TOCTOU
   * 残余窗口如实记录在类 javadoc。
   */
  private void requireSafeTarget(Path target, String label) {
    Path parent = target.getParent();
    if (parent == null) {
      throw DocException.writeFailed("文档落点没有父目录（fail-closed）: " + label, null);
    }
    try {
      if (Files.isSymbolicLink(baseDir)) {
        throw DocException.denied("挂载根是软链（默认拒绝，不跟随）: " + label);
      }
      Path realBase = baseDir.toRealPath();
      Path realParent = parent.toRealPath();
      if (!realParent.startsWith(realBase)) {
        throw DocException.denied("文档落点的真实父目录落在挂载根之外（软链逃逸，fail-closed）: " + label);
      }
      if (Files.isSymbolicLink(target)) {
        throw DocException.denied("文档落点是软链（默认拒绝，不跟随）: " + label);
      }
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        Path realTarget = target.toRealPath();
        if (!realTarget.startsWith(realBase)) {
          throw DocException.denied("文档落点的真实路径落在挂载根之外（软链逃逸，fail-closed）: " + label);
        }
      }
    } catch (IOException e) {
      throw DocException.writeFailed("原子替换前无法复核文档落点（fail-closed，盘上未改动）: " + label, e);
    }
  }

  private static void atomicMove(Path temporary, Path target, String label) {
    try {
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      // 同目录 rename 在 POSIX 上原子；这里也不静默降级成"就地重写"——那会让盘上出现半成品（B8）
      throw DocException.writeFailed("文件系统不支持原子替换（不允许就地重写，否则盘上会出现半成品）: " + label, e);
    } catch (IOException e) {
      throw DocException.writeFailed("原子替换失败（盘上仍是原内容）: " + label, e);
    }
  }

  /**
   * 安全地逐级建目录：从最近已存在的父目录开始，每一级先查软链再 {@code createDirectory}。
   *
   * <p>不用 {@link Files#createDirectories}：它会跟随“已存在的软链目录”，于是挂载内的软链父目录会把文件写到挂载外。
   */
  private static void ensureDirectory(Path dir, String label) {
    Deque<Path> missing = new ArrayDeque<>();
    Path cursor = dir;
    while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
      missing.push(cursor);
      cursor = cursor.getParent();
    }
    if (cursor == null) {
      throw DocException.writeFailed("目录没有可用的已存在父目录（fail-closed）: " + label, null);
    }
    if (Files.isSymbolicLink(cursor)) {
      throw DocException.denied("目录路径包含软链（默认拒绝，不跟随）: " + label);
    }
    if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
      throw DocException.writeFailed("目录落点不是目录（fail-closed）: " + label, null);
    }
    Path current = cursor;
    for (Path target : missing) {
      Path name = target.getFileName();
      if (name == null) {
        throw DocException.writeFailed("目录项没有文件名（fail-closed）: " + label, null);
      }
      Path next = current.resolve(name);
      if (Files.isSymbolicLink(next)) {
        throw DocException.denied("目录路径包含软链（默认拒绝，不跟随）: " + label);
      }
      try {
        Files.createDirectory(next);
      } catch (FileAlreadyExistsException e) {
        if (Files.isSymbolicLink(next) || !Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS)) {
          throw DocException.denied("目录在创建时被替换成软链或非目录（fail-closed）: " + label);
        }
      } catch (IOException e) {
        throw DocException.writeFailed("目录建不出来: " + label, e);
      }
      current = next;
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // 临时文件删不掉不影响正确性（它不参与读）；这里也不掩盖真正的失败原因
    }
  }

  /** 临时文件路径：同目录、不可猜、不撞（{@code <名字>.tmp-<pid>-<计数>}）。 */
  private Path temporaryPath(Path target) {
    return target.resolveSibling(
        target.getFileName()
            + ".tmp-"
            + ProcessHandle.current().pid()
            + "-"
            + temporaryCounter.incrementAndGet());
  }

  private String decode(byte[] bytes, String label, Path file) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      noteBroken(label, 0, "不是合法 UTF-8（不猜内容、不做有损替换）", file);
      throw DocException.readFailed("文档不是合法 UTF-8（不猜内容）: " + label, e);
    }
  }

  /** 文本 → 行列表（一次行尾规范化：{@code \r\n} 与 {@code \r} 都算一个换行）。 */
  private static List<String> splitLines(String text) {
    if (text.isEmpty()) {
      return new ArrayList<>();
    }
    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
    List<String> parts = new ArrayList<>(List.of(normalized.split("\n", -1)));
    if (!parts.isEmpty() && parts.get(parts.size() - 1).isEmpty()) {
      // 末尾换行是"最后一行的结束符"，不是"多出一个空行"
      parts.remove(parts.size() - 1);
    }
    return parts;
  }

  private static String normalize(String text) {
    String value = text == null ? "" : text;
    String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
    return normalized.isEmpty() || normalized.endsWith("\n") ? normalized : normalized + "\n";
  }

  private static long countLines(String text) {
    return text.isEmpty() ? 0 : splitLines(text).size();
  }

  private void noteBroken(String where, long line, String reason, Path physical) {
    broken.add(new BrokenDoc(where, line, reason, Optional.of(physical.toString())));
  }

  /**
   * {@code lines="10,40"} 的解析（设计 §5.5）：省略 ⇒ 全文；否则必须是 {@code 起,止} 闭区间（1 起，起 ≤ 止）。
   *
   * @param from 起始行号（1 起）
   * @param to 结束行号（1 起，闭区间；空文件时 {@code from=1,to=0}）
   */
  record LineRange(long from, long to) {

    static LineRange parse(String lines, long total) {
      String text = lines == null ? "" : lines.strip();
      if (text.isEmpty()) {
        return new LineRange(1, total);
      }
      String[] parts = text.split(",", -1);
      if (parts.length != 2) {
        throw DocException.invalid("lines 形态非法（应为 \"起,止\"，如 \"10,40\"）: '" + text + "'");
      }
      long from = parseLine(parts[0], text);
      long to = parseLine(parts[1], text);
      if (from > to) {
        throw DocException.invalid("lines 起点大于终点（写反了）: '" + text + "'");
      }
      if (from > total) {
        // 越界不是"空结果"：静默返回空会让人以为文件真的没有这一段
        throw DocException.invalid("lines 起点越界（文件共 " + total + " 行）: '" + text + "'");
      }
      return new LineRange(from, Math.min(to, total));
    }

    private static long parseLine(String text, String whole) {
      try {
        long value = Long.parseLong(text.strip());
        if (value < 1) {
          throw DocException.invalid("lines 的行号从 1 起: '" + whole + "'");
        }
        return value;
      } catch (NumberFormatException e) {
        throw DocException.invalid("lines 的行号不是整数: '" + whole + "'");
      }
    }
  }
}
