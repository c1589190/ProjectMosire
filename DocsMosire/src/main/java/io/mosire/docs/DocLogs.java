package io.mosire.docs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code LOG} 纪律的存储实现（设计 §5.3）：<b>每地址一个 NDJSON 追加日志</b> + 排他文件锁。
 *
 * <p><b>为什么是"每地址一个文件"</b>：地址是调用方给的不透明字符串，没有可靠的共同前缀能做分片键；一地址一文件让"同地址并发追加排队、跨地址并发无冲突"成为结构性结论
 * （锁的粒度就是冲突的粒度）。
 *
 * <p><b>文件名只是索引，不是身份</b>（B5）：文件名 = 地址的<b>安全编码</b>（{@link #encode}）；读回时以<b>记录内的 {@code address}</b>
 * 为准，并且要求它与文件名编码出的地址一致——不一致就是坏数据（进 {@code broken} 披露），不"以文件名为准"把记录悄悄改写成另一个地址。
 *
 * <p><b>序号 = 行序</b>（从 0 起）：{@code id = <地址>#<序号>}。撞 id（同序号被不同载荷占用）具名拒；<b>同地址 + 同载荷 ⇒ 同 id</b>（可重放，
 * 返回已存在的那条，不重复落盘）。
 *
 * <p><b>原子性与互斥</b>：追加 = 排他文件锁下的"读行序 → 定序号 → 写一行 + {@code force}"，整个 read-modify-write 在<b>同一个
 * 被锁住的通道</b>里完成（读若另开描述符就只保护了半段，实测会出现重复序号，见 {@link #withFileLock}）。
 *
 * <p><b>坏行不静默</b>：解析不出来的行进 {@link #broken()} 披露并<b>继续</b>读；写侧永不产出坏行（{@link Json#encode} 是确定性的）。
 *
 * <p><b>诚实边界</b>：文件锁只在同主机、支持锁的文件系统上成立（设计 §9.3）；NFS/多机不在承诺内。若底层文件系统不支持锁，追加退化为"JVM 内互斥"
 * ——跨进程撕裂的风险由此<b>不被覆盖</b>，这一点写在代码里而不是含糊带过。读取路径（{@code read}/{@code query}/{@code addresses}）走
 * {@link Files#readAllBytes}：它不参与写者的互斥，理论上可能读到"另一个进程刚写了一半"的内容；那一条会作为坏行进 {@code broken}（不静默），
 * 下一次读即可自愈。
 */
final class DocLogs implements DocStore {

  private static final Logger LOG = LoggerFactory.getLogger(DocLogs.class);

  /** 日志文件后缀（{@code LOG} 纪律的存储形态；同目录里的其它文件不属于本纪律）。 */
  static final String LOG_SUFFIX = ".log";

  /** 文件名里 {@code %} 转义用的前缀（地址是调用方给的任意字符串，必须编码成安全的文件名）。 */
  private static final char ESCAPE = '%';

  /** "行号未知"的取值（坏文件名/读失败这类"整份文件都不可用"的披露用它）。 */
  private static final int NOTHING = 0;

  private final Path baseDir;
  private final Object lock = new Object();

  /**
   * <b>JVM 内按日志文件的锁</b>：{@code FileChannel.lock()} 是<b>整 JVM 级</b>的（同一文件在同一 JVM 内重复获取会抛 {@code
   * OverlappingFileLockException}），而本类每次操作都现建实例 ⇒ 线程之间必须先在 JVM 内排好队，再去拿文件锁。 键是物理文件路径（{@link
   * Path#toAbsolutePath()} + 归一），所以"不同地址"仍然并行、"同一地址"串行。
   */
  private static final java.util.concurrent.ConcurrentHashMap<String, Object> JVM_LOCKS =
      new java.util.concurrent.ConcurrentHashMap<>();

  private final List<BrokenDoc> broken = new ArrayList<>();

  private DocLogs(Path baseDir) {
    this.baseDir = baseDir;
  }

  /**
   * 落盘：{@code baseDir} 是该挂载解析出的物理目录（由 {@link DocsEngine} 经工作区解析点给出，本类不再做映射）。
   *
   * <p><b>每次操作现建实例</b>：坏数据清单是"实例内累计"的诊断快照，跨调用累计需要长期持有实例。现建让"写完立刻对同一挂载内的后续读可见" （设计 §5.3
   * 的重扫语义）成为结构性结论——没有缓存可以陈旧。
   */
  static DocStore onDisk(Path baseDir) {
    return new DocLogs(java.util.Objects.requireNonNull(baseDir, "baseDir"));
  }

  @Override
  public DocEntry append(AppendRequest req) {
    synchronized (lock) {
      Path file = logFile(baseDir, req.address());
      // ★ 排他锁必须包住"读行序 → 定序号 → 追加"这一整段，而且读也必须走**同一个被锁住的通道**。
      //   实测（本机 ext4，2026-10-13）：用 Files.readAllLines 在锁内另开描述符读，读到的是旧内容 ——
      //   两个进程于是在各自锁内都看到"0 行"、都算出序号 0，盘上出现重复序号。改成"锁内的读也走同一通道"后
      //   实测序号唯一、等待时间可见（见 withFileLock 的注释）。
      return withFileLock(file, (channel, locked) -> appendLocked(file, channel, req));
    }
  }

  /** 已持文件锁时的追加（序号由本次读到的行序决定，因此必须与读在同一临界区、同一通道里）。 */
  private DocEntry appendLocked(Path file, FileChannel channel, AppendRequest req) {
    List<DocEntry> entries = parseLines(readAllBytes(channel, file), file);
    for (DocEntry existing : entries) {
      if (existing.address().equals(req.address())
          && existing.payloadSignature().equals(req.payloadSignature())) {
        // 同地址 + 同载荷 ⇒ 同 id（可重放）：不重复落盘，返回已存在的那条
        return existing;
      }
    }
    long ordinal = entries.stream().filter(e -> e.address().equals(req.address())).count();
    DocEntry candidate = req.toEntry(ordinal);
    DocEntryId id = candidate.id();
    for (DocEntry existing : entries) {
      if (existing.id().equals(id)
          && !existing.payloadSignature().equals(candidate.payloadSignature())) {
        // 撞 id：同一序号被不同载荷占了（日志被外部改写，或另一个写入者绕过了锁）——写入侧具名拒
        throw DocException.exists(
            "条目 id 已被不同载荷占用（同名不同义会让重放语义失效）: " + id + "，该地址日志行数=" + entries.size());
      }
    }
    byte[] line = (Json.encode(record(candidate)) + "\n").getBytes(StandardCharsets.UTF_8);
    writeAtEnd(channel, file, line);
    return candidate;
  }

  /**
   * 在该日志文件上取排他锁，把一段操作放在锁内执行（跨进程互斥的唯一写法）。
   *
   * <p><b>三层各管一件事</b>：
   *
   * <ol>
   *   <li>{@code synchronized (jvmLock(file))}：{@code FileChannel.lock()} 是<b>整 JVM 级</b>的（同一文件在同一
   *       JVM 内重复获取会抛 {@link java.nio.channels.OverlappingFileLockException}），而本类每次操作都现建实例 ⇒
   *       线程之间要先在 JVM 内排队；
   *   <li>{@link FileChannel#lock()}：跨<b>进程</b>的互斥（同主机、支持锁的文件系统；设计 §9.3 的边界）；
   *   <li>{@code channel} 本身：<b>锁内的一切读写都必须走这个通道</b>。★ 实测教训：在锁内用 {@code Files.readAllLines}
   *       另开描述符读，读到的是旧内容——锁保护的是"这一段逻辑"，不是"这个路径"。用别的描述符读等于把 read-modify-write 拆成两半，重复序号就是这么来的。
   * </ol>
   *
   * <p><b>锁不可用</b>（文件系统不支持）时记 WARN 并继续：JVM 内仍然正确（第 1 层在），跨进程的保证<b>不成立</b>—— 这条边界写在代码里，不假装锁总是存在。
   *
   * <p><b>通道以 READ + WRITE 打开（不用 APPEND）</b>：{@code APPEND} 会让所有写都落到文件末尾、并让位置的语义变得难以推敲； 这里显式 {@code
   * position(size())} 再写，读到哪儿、写到哪儿都看得见。
   */
  private DocEntry withFileLock(Path file, LockedAction action) {
    synchronized (jvmLock(file)) {
      ensureDirectory(file.getParent());
      try (FileChannel channel =
          FileChannel.open(
              file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
        FileLock fileLock = null;
        try {
          fileLock = channel.lock();
        } catch (IOException
            | UnsupportedOperationException
            | java.nio.channels.OverlappingFileLockException e) {
          LOG.warn(
              "docs.log 拿不到排他文件锁，退化为 JVM 内互斥（跨进程不撕裂的保证在此挂载上不成立）: {}, 原因={}",
              fileNameOf(file),
              e.toString());
        }
        try {
          return action.run(channel, fileLock != null);
        } finally {
          if (fileLock != null) {
            try {
              fileLock.release();
            } catch (IOException e) {
              LOG.debug("docs.log 文件锁释放失败: {}", fileNameOf(file), e);
            }
          }
        }
      } catch (IOException e) {
        throw DocException.writeFailed("日志打不开（这一行没有落盘）: " + fileNameOf(file), e);
      }
    }
  }

  /** 锁内执行的一段操作（拿到的是<b>被锁住的那个通道</b>，读写都该走它）。 */
  @FunctionalInterface
  private interface LockedAction {
    DocEntry run(FileChannel channel, boolean locked);
  }

  /** JVM 内按文件路径的锁对象（同地址串行、不同地址并行）。 */
  private static Object jvmLock(Path file) {
    return JVM_LOCKS.computeIfAbsent(
        file.toAbsolutePath().normalize().toString(), key -> new Object());
  }

  @Override
  public DocEntry voidEntry(DocEntryId id) {
    synchronized (lock) {
      DocEntry target =
          read(id.address()).stream()
              .filter(entry -> entry.ordinal() == id.ordinal())
              .findFirst()
              .orElseThrow(() -> DocException.notFound("面内不存在这个条目（不存在与不可见在这里是同一个回答）: " + id));
      if (target.status() == DocStatus.VOIDED) {
        // 幂等：再作废一次不造出第二堆作废痕迹（"已作废"就是事实本身）
        return target;
      }
      // 作废 = 追加一条同载荷、状态为 VOIDED 的新记录（原记录一个字都不动）
      return append(AppendRequest.voided(target));
    }
  }

  @Override
  public Optional<DocEntry> find(DocEntryId id) {
    synchronized (lock) {
      return read(id.address()).stream()
          .filter(entry -> entry.ordinal() == id.ordinal())
          .findFirst();
    }
  }

  @Override
  public List<DocEntry> read(String address) {
    synchronized (lock) {
      String key = DocAddress.require(address);
      Path file = logFile(baseDir, key);
      if (!Files.exists(file)) {
        // 这个地址还没有日志：是"空"，不是"坏"——返回空表（设计 §5.5 的"空结果不抛"）
        return List.of();
      }
      // 读也走同一把 JVM 内锁：否则"读"可能落在"另一个进程刚 truncate/写到一半"的窗口里（跨进程的脏读）
      synchronized (jvmLock(file)) {
        return List.copyOf(readFile(file));
      }
    }
  }

  @Override
  public List<DocEntry> query(QuerySpec spec, DocViewer viewer) {
    synchronized (lock) {
      List<DocEntry> all = new ArrayList<>();
      for (Path file : scan(baseDir)) {
        all.addAll(readFile(file));
      }
      // 先按可见性过滤（不可见的条目在结果里不存在），再过滤时刻，再排序，最后截断（"先排后截"）
      List<DocEntry> matched = new ArrayList<>();
      for (DocEntry entry : all) {
        if (DocVisibility.canSee(viewer, entry) && spec.matches(entry.tick())) {
          matched.add(entry);
        }
      }
      // tick 降序；同 tick 按 (地址, 序号数值) 升序——序号按数值不按字符串（#10 vs #2）
      matched.sort(
          (left, right) -> {
            int byTick = Long.compare(right.tick(), left.tick());
            return byTick != 0 ? byTick : left.id().compareTo(right.id());
          });
      return List.copyOf(matched.subList(0, Math.min(spec.limit(), matched.size())));
    }
  }

  @Override
  public List<String> addresses() {
    synchronized (lock) {
      java.util.TreeSet<String> known = new java.util.TreeSet<>();
      for (Path file : scan(baseDir)) {
        // 文件名只是索引（B5）：这里按解码出的地址归集，记录内的地址在读取时逐条核对
        String address = canonicalAddress(file);
        if (address != null) {
          known.add(address);
        }
      }
      return List.copyOf(known);
    }
  }

  @Override
  public List<BrokenDoc> broken() {
    synchronized (lock) {
      return List.copyOf(broken);
    }
  }

  // ── 内部：写入 ────────────────────────────────────────────────────────────────────────────────

  /** 从<b>锁住的通道</b>读全文（{@code position(0)} 起，按 {@code size()} 循环读满）。 */
  private static byte[] readAllBytes(FileChannel channel, Path file) {
    try {
      int size = (int) Math.min(channel.size(), Integer.MAX_VALUE);
      ByteBuffer buffer = ByteBuffer.allocate(size);
      channel.position(0);
      while (buffer.hasRemaining()) {
        int read = channel.read(buffer);
        if (read < 0) {
          break;
        }
      }
      return java.util.Arrays.copyOf(buffer.array(), buffer.position());
    } catch (IOException e) {
      throw DocException.readFailed("日志读不出来（不把它当成空日志）: " + fileNameOf(file), e);
    }
  }

  /** 往<b>锁住的通道</b>末尾写一行（显式定位到末尾：不用 {@code APPEND} 打开，位置的语义才看得见）。 */
  private static void writeAtEnd(FileChannel channel, Path file, byte[] line) {
    try {
      channel.position(channel.size());
      ByteBuffer buffer = ByteBuffer.wrap(line);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    } catch (IOException e) {
      throw DocException.writeFailed("日志追加失败（这一行没有落盘）: " + fileNameOf(file), e);
    }
  }

  /** 读一个地址的日志文件（坏行进 {@code broken} 并继续；写路径上的读见 {@link #parseLines}）。 */
  private List<DocEntry> readFile(Path file) {
    if (!Files.exists(file)) {
      return List.of();
    }
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(file);
    } catch (IOException e) {
      noteBroken(fileNameOf(file), NOTHING, "日志读不出来（不把它当成空日志）", file);
      return List.of();
    }
    return parseLines(bytes, file);
  }

  /**
   * 字节 → 条目列表（坏行/坏记录<b>披露并跳过</b>，不静默、也不让整份日志读不出来）。
   *
   * <p><b>身份以记录内的 {@code address} 为准</b>（B5）：文件名编码出的地址是索引，两者不一致就披露并跳过——不"以文件名为准"把记录
   * 悄悄改写成另一个地址，也不认那种文件（见 {@link #canonicalAddress}）。
   */
  private List<DocEntry> parseLines(byte[] bytes, Path file) {
    String address = canonicalAddress(file);
    if (address == null) {
      return List.of();
    }
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
              .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (java.nio.charset.CharacterCodingException e) {
      noteBroken(fileNameOf(file), NOTHING, "日志不是合法 UTF-8（不猜内容、不做有损替换）", file);
      return List.of();
    }
    List<DocEntry> entries = new ArrayList<>();
    String[] lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      if (line.isBlank()) {
        // 末尾换行会产生一个空尾巴：那不是坏行，只是"文件以换行结束"
        if (i == lines.length - 1) {
          continue;
        }
        noteBroken(fileNameOf(file), i + 1L, "空行（不是合法记录，已跳过并继续读）", file);
        continue;
      }
      DocEntry entry;
      try {
        entry = parseRecord(line);
      } catch (RuntimeException e) {
        noteBroken(fileNameOf(file), i + 1L, "坏行：不是合法的 NDJSON 记录（已跳过并继续读）", file);
        continue;
      }
      if (!entry.address().equals(address)) {
        // 文件名只是索引、记录内的地址才是身份（B5）：两者不一致 ⇒ 披露并跳过
        noteBroken(fileNameOf(file), i + 1L, "记录内地址与文件名编码出的地址不一致（身份以记录内为准）", file);
        continue;
      }
      entries.add(entry);
    }
    return entries;
  }

  /**
   * 文件名 → 规范地址；不是本实现写出来的文件名（编码不回去、解不出合法地址）⇒ {@code null} 并披露。
   *
   * <p><b>为什么要求"回编码一致"</b>：编码是单射，认一个非规范文件名会让两个不同地址共用同一份日志——那正是 B5 要避免的 "把文件名当身份"。
   */
  private String canonicalAddress(Path file) {
    String encoded = stripSuffix(file.getFileName().toString());
    try {
      String address = DocAddress.require(decode(encoded));
      if (!encode(address).equals(encoded)) {
        throw new IllegalArgumentException("文件名不是规范编码（回编码不一致）");
      }
      return address;
    } catch (RuntimeException e) {
      noteBroken(fileNameOf(file), NOTHING, "文件名不是合法的地址编码（按坏数据披露，不猜它的地址）", file);
      return null;
    }
  }

  /** 扫描挂载目录下的全部日志文件（一层，不递归；{@code .log} 后缀是唯一识别口径）。 */
  private List<Path> scan(Path dir) {
    List<Path> files = new ArrayList<>();
    if (!Files.isDirectory(dir)) {
      return files;
    }
    try (Stream<Path> children = Files.list(dir)) {
      for (Path child : children.toList()) {
        if (Files.isRegularFile(child) && child.getFileName().toString().endsWith(LOG_SUFFIX)) {
          files.add(child);
        }
      }
    } catch (IOException e) {
      throw DocException.reloadFailed("日志目录读不出来（fail-closed）: " + dir.getFileName(), e);
    }
    files.sort(null);
    return files;
  }

  // ── 内部：编解码 ─────────────────────────────────────────────────────────────────────────────

  /** 记录的 JSON 形态（字段名写死；读回按同一张表解——改表就是改存储格式，必须同步改 {@link #parseRecord}）。 */
  private static Map<String, Object> record(DocEntry entry) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("address", entry.address());
    map.put("tick", entry.tick());
    map.put("ordinal", entry.ordinal());
    map.put("tags", new ArrayList<>(entry.tags()));
    map.put("affiliations", new ArrayList<>(entry.affiliations()));
    map.put("owner", entry.owner().orElse(null));
    map.put("key", entry.key());
    map.put("value", entry.value());
    map.put("note", entry.note().orElse(null));
    map.put("status", entry.status().name());
    map.put("fields", entry.fields());
    map.put("at", entry.at().orElse(null));
    return map;
  }

  private static DocEntry parseRecord(String line) {
    Map<String, Object> map = Json.object(Json.decode(line));
    String status = Json.string(map, "status");
    DocStatus parsed;
    try {
      parsed = DocStatus.valueOf(status.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("状态取值非法: " + status, e);
    }
    Map<String, Object> fields = new LinkedHashMap<>();
    if (Json.field(map, "fields", null) instanceof Map<?, ?> nested) {
      for (Map.Entry<?, ?> field : nested.entrySet()) {
        fields.put(String.valueOf(field.getKey()), Json.normalize(field.getValue()));
      }
    }
    return new DocEntry(
        Json.string(map, "address"),
        Json.number(map, "tick", -1),
        Json.number(map, "ordinal", -1),
        new LinkedHashSet<>(Json.strings(map, "tags")),
        new LinkedHashSet<>(Json.strings(map, "affiliations")),
        optional(Json.field(map, "owner", null)),
        Json.string(map, "key"),
        Json.field(map, "value", ""),
        optional(Json.field(map, "note", null)),
        parsed,
        fields,
        optional(Json.field(map, "at", null)));
  }

  private static Optional<String> optional(Object value) {
    return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
  }

  /** 地址 → 日志文件名（安全编码；文件名只是索引，身份在记录内）。 */
  static String fileName(String address) {
    return encode(address) + LOG_SUFFIX;
  }

  /** 挂载目录 + 地址 → 日志文件路径。 */
  static Path logFile(Path baseDir, String address) {
    return baseDir.resolve(fileName(address));
  }

  private static String stripSuffix(String name) {
    return name.endsWith(LOG_SUFFIX)
        ? name.substring(0, name.length() - LOG_SUFFIX.length())
        : name;
  }

  /**
   * 地址 → 文件名安全编码（可逆、确定性、无路径分隔符、无前导点）。
   *
   * <p>保留 {@code [A-Za-z0-9._-]}，其余字节（按 UTF-8）写成 {@code %XX}。{@code %} 自己也编码，因此编码是单射；编码结果不含 {@code
   * /} 与 {@code \}，也就不会跨出挂载目录。
   */
  static String encode(String text) {
    StringBuilder out = new StringBuilder();
    for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
      int value = b & 0xFF;
      char c = (char) value;
      if (value < 0x80 && (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-')) {
        out.append(c);
      } else {
        out.append(ESCAPE).append(String.format("%02X", value));
      }
    }
    return out.toString();
  }

  /** 文件名编码 → 地址（严格：任何不合法形态一律抛，由调用方按坏数据披露）。 */
  static String decode(String encoded) {
    byte[] bytes = new byte[encoded.length()];
    int length = 0;
    for (int i = 0; i < encoded.length(); i++) {
      char c = encoded.charAt(i);
      if (c != ESCAPE) {
        if (c > 0x7F) {
          throw new IllegalArgumentException("文件名编码里出现非 ASCII 字符: " + c);
        }
        bytes[length++] = (byte) c;
        continue;
      }
      if (i + 2 >= encoded.length()) {
        throw new IllegalArgumentException("文件名编码的 % 转义不完整");
      }
      int high = Character.digit(encoded.charAt(i + 1), 16);
      int low = Character.digit(encoded.charAt(i + 2), 16);
      if (high < 0 || low < 0) {
        throw new IllegalArgumentException("文件名编码的 % 转义不是十六进制");
      }
      bytes[length++] = (byte) ((high << 4) | low);
      i += 2;
    }
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      return decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString();
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException("文件名编码不是合法 UTF-8", e);
    }
  }

  // ── 内部：通用 ───────────────────────────────────────────────────────────────────────────────

  private void noteBroken(String where, long line, String reason, Path physical) {
    broken.add(new BrokenDoc(where, line, reason, Optional.of(physical.toString())));
  }

  private static void ensureDirectory(Path dir) {
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw DocException.writeFailed("日志目录建不出来: " + dir.getFileName(), e);
    }
  }

  /** 面向模型的渲染：只出文件名，不出宿主的目录结构（A7）。 */
  private static String fileNameOf(Path path) {
    Path name = path.getFileName();
    return name == null ? "<logs>" : name.toString();
  }
}
