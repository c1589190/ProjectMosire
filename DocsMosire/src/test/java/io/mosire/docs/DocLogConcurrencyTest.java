package io.mosire.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>{@code LOG} 并发的回归钉</b>（设计 §5.3 的实测教训，§5.9 负向"同地址并发追加不撕裂"）。
 *
 * <p>被钉住的实现事实（{@link DocLogs#withFileLock}）："读行序 → 定序号 → 追加"整段必须在<b>同一个被锁住的通道</b>里完成。
 * 最初实现把读放在锁外（或锁内另开描述符读），跨进程真跑（R3a 探针：4 JVM × 40 次追加）<b>实测出现重复序号</b>； 改成"锁内的读写同走一个通道"后 160 行拿到 160
 * 个唯一且连续的序号。
 *
 * <p>因此这里两条钉：
 *
 * <ul>
 *   <li>{@link #sameAddressConcurrentAppendsInOneJvm()}：JVM 内多线程（{@code FileChannel.lock()} 是整 JVM
 *       级的 ⇒ 线程间必须先走 JVM 内排队，去掉那一层就会重复序号）；
 *   <li>{@link #sameAddressConcurrentAppendsAcrossProcesses()}：<b>跨进程</b>（这条才对"锁内另开描述符读/读在锁外"有判别力
 *       ——R3a 的探针就是这个形态）。子进程入口见 {@link ChildAppender}。
 * </ul>
 */
class DocLogConcurrencyTest {

  private static final String ADDRESS = "world/1";

  @TempDir Path tempDir;

  /** JVM 内 8 线程 × 25 次：序号必须唯一且连续。 */
  @Test
  void sameAddressConcurrentAppendsInOneJvm() throws Exception {
    Path dir = Files.createDirectories(tempDir.resolve("logs"));
    int threads = 8;
    int perThread = 25;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int t = 0; t < threads; t++) {
        final int id = t;
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  for (int i = 0; i < perThread; i++) {
                    // 每次载荷必须不同：同地址 + 同载荷是"重放"（返回已存在的那条），不会追加
                    DocLogs.onDisk(dir)
                        .append(AppendRequest.of(ADDRESS, i, "k", "t" + id + "-" + i));
                  }
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    List<DocEntry> entries = DocLogs.onDisk(dir).read(ADDRESS);
    assertThat(entries).hasSize(threads * perThread);
    assertThat(entries)
        .extracting(DocEntry::ordinal)
        .containsExactlyElementsOf(ordinals(threads * perThread));
  }

  /** 跨进程 4 JVM × 40 次：序号必须唯一且连续（R3a 探针的形态）。 */
  @Test
  void sameAddressConcurrentAppendsAcrossProcesses() throws Exception {
    Path dir = Files.createDirectories(tempDir.resolve("shared"));
    Path go = tempDir.resolve("go");
    int processes = 4;
    int perProcess = 40;
    String classpath = System.getProperty("java.class.path");
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    List<Process> children = new ArrayList<>();
    List<Path> logs = new ArrayList<>();
    for (int p = 0; p < processes; p++) {
      Path log = tempDir.resolve("child-" + p + ".log");
      logs.add(log);
      children.add(
          new ProcessBuilder(
                  java,
                  "-Xmx96m",
                  "-cp",
                  classpath,
                  ChildAppender.class.getName(),
                  dir.toString(),
                  ADDRESS,
                  String.valueOf(perProcess),
                  go.toString(),
                  "p" + p)
              .redirectErrorStream(true)
              .redirectOutput(log.toFile())
              .start());
    }
    // 四个子进程都起来了再放行（否则先跑的会独自跑完，"并发"就没了）
    Files.writeString(go, "go");
    for (int p = 0; p < processes; p++) {
      Process child = children.get(p);
      boolean exited = child.waitFor(60, TimeUnit.SECONDS);
      assertThat(exited).as("子进程 %d 超时未结束", p).isTrue();
      assertThat(child.exitValue()).as("子进程 %d 失败：%s", p, readQuietly(logs.get(p))).isZero();
    }

    List<DocEntry> entries = DocLogs.onDisk(dir).read(ADDRESS);
    assertThat(entries).hasSize(processes * perProcess);
    assertThat(entries)
        .extracting(DocEntry::ordinal)
        .containsExactlyElementsOf(ordinals(processes * perProcess));
  }

  /** 子进程入口：同地址追加 {@code count} 条（载荷各不相同），等"放行标记"出现后开跑。 */
  public static final class ChildAppender {

    private ChildAppender() {
      throw new AssertionError("No instances");
    }

    /** {@code args = <日志目录> <地址> <条数> <放行标记> <进程标签>}。 */
    public static void main(String[] args) throws IOException, InterruptedException {
      Path dir = Path.of(args[0]);
      String address = args[1];
      int count = Integer.parseInt(args[2]);
      Path go = Path.of(args[3]);
      String tag = args[4];
      while (!Files.exists(go)) {
        Thread.sleep(5);
      }
      for (int i = 0; i < count; i++) {
        DocLogs.onDisk(dir).append(AppendRequest.of(address, i, "k", tag + "-" + i));
      }
    }
  }

  private static String readQuietly(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      return "<读不出子进程输出: " + e + ">";
    }
  }

  private static List<Long> ordinals(int count) {
    List<Long> ordinals = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      ordinals.add((long) i);
    }
    return ordinals;
  }
}
