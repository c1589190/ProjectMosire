package io.mosire.brain.skills;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D2 的<b>跨进程</b>判别力：两个真 JVM（Surefire fork + 它拉起的子进程）在屏障文件对齐后， <b>同时</b> {@code create}
 * 同名技能，逐轮判“恰好一个赢家、失败方 SKILL_EXISTS、盘上是赢家那份完整文档、目录无残留”。
 *
 * <p><b>为什么必须真进程</b>：D2 的事故形状（ATOMIC_MOVE 撞名静默替换）本就是进程级现象；同 JVM 的两个线程只能证明线程安全。
 * 判定与技能名抢名都是进程间共享同一文件系统，所以这条用例是 {@code
 * SkillWriteContractTest#concurrentCreateOfTheSameNameYieldsExactlyOneWinner} 的进程边界版本。
 *
 * <p><b>判别力（变异自证见证据包）</b>：把 {@code SkillAccess.create} 的 mkdir 抢名换回“{@code Files.exists} 预检 +
 * {@code createDirectories} + 覆盖改名”，本用例与同 JVM 那条同时红（实测两方都成功 = 大量轮）。
 *
 * <p><b>为什么父进程自己也算一方</b>：跨进程对齐用“屏障文件 + 自旋”而不是 {@code CyclicBarrier}（那只在进程内）； 父进程写 go 文件后立即建，子进程自旋到
 * go 文件出现后立即建，两侧进入 {@code create} 的间隔在微秒级。
 */
class SkillCreateCrossProcessTest {

  /** 轮数：每轮换一个新名字，父子各冲一次。 */
  private static final int ROUNDS = 60;

  /** 每个屏障/结果文件的等待上限（整条用例的自然上限 = ROUNDS × 该值，但正常一轮几毫秒）。 */
  private static final long WAIT_MILLIS = 30_000;

  /** 断言消息里最多逐条列出多少轮问题（其余只报计数）。 */
  private static final int MAX_REPORTED_ROUNDS = 10;

  @TempDir Path tempDir;

  @Test
  void twoJvmsRacingForTheSameNameYieldExactlyOneWinner() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("skills"));
    Path barrier = Files.createDirectories(tempDir.resolve("barrier"));
    Path childLog = tempDir.resolve("child.log");
    String prefix = "xproc-";
    AgentPermissionSet permissions =
        SkillPermissions.withScopes(
            AgentPermissionSet.system(), ResourceScope.unlimited(), ResourceScope.unlimited());

    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    Process child =
        new ProcessBuilder(
                java.toString(),
                "-Xmx64m",
                "-cp",
                classpath,
                SkillCreateChildMain.class.getName(),
                root.toString(),
                barrier.toString(),
                String.valueOf(ROUNDS),
                prefix)
            .redirectErrorStream(true)
            .redirectOutput(childLog.toFile())
            .start();

    List<String> problems = new ArrayList<>();
    int anomalyRounds = 0;
    try {
      for (int round = 0; round < ROUNDS; round++) {
        String name = prefix + round;
        Files.writeString(barrier.resolve("parent-ready-" + round), "1");
        awaitFile(barrier.resolve("child-ready-" + round), "子进程就绪", child, childLog);
        Files.writeString(barrier.resolve("go-" + round), "1");
        Object parentOutcome = attempt(permissions, root, name, "PARENT");
        awaitFile(barrier.resolve("child-outcome-" + round), "子进程结果", child, childLog);
        String childOutcome =
            Files.readString(barrier.resolve("child-outcome-" + round), StandardCharsets.UTF_8)
                .trim();
        String message = checkRound(round, name, parentOutcome, childOutcome);
        if (message != null) {
          anomalyRounds++;
          if (problems.size() < MAX_REPORTED_ROUNDS) {
            problems.add(message);
          }
        }
      }
    } finally {
      if (child.isAlive()) {
        child.destroy();
      }
      if (!child.waitFor(10, TimeUnit.SECONDS)) {
        child.destroyForcibly();
      }
    }
    if (anomalyRounds > problems.size()) {
      problems.add("……另有 " + (anomalyRounds - problems.size()) + " 个异常轮未逐条列出");
    }
    assertThat(problems)
        .as(
            "跨进程并发 create 同名：恰好一个成功、另一个 SKILL_EXISTS、盘上是赢家那份完整文档（异常轮=%s/%s）%n子进程日志:%n%s",
            anomalyRounds, ROUNDS, childLogText(childLog))
        .isEmpty();
  }

  /** 一轮的判定：{@code null} = 正常。父/子两侧的“成功”只可能是 {@link SkillAccess.WriteResult}。 */
  private String checkRound(int round, String name, Object parentOutcome, String childOutcome) {
    Path directory = root(name);
    String onDisk = onDiskMarker(directory);
    boolean parentWon = parentOutcome instanceof SkillAccess.WriteResult;
    boolean childWon = "OK".equals(childOutcome);
    List<String> roundProblems = new ArrayList<>();
    long successes = (parentWon ? 1 : 0) + (childWon ? 1 : 0);
    if (successes != 1) {
      roundProblems.add("成功数=" + successes);
    } else {
      String loser = parentWon ? childOutcome : describe(parentOutcome);
      if (!("FAIL:" + SkillException.SKILL_EXISTS).equals(loser)) {
        roundProblems.add("失败方不是 SKILL_EXISTS：" + loser);
      }
      String winner = parentWon ? "PARENT" : "CHILD";
      if (!onDisk.equals(winner)) {
        roundProblems.add("盘上是 " + onDisk + " 的正文，赢家是 " + winner);
      }
    }
    List<String> entries = entriesOf(directory);
    if (!entries.equals(List.of(Skill.SKILL_FILE_NAME))) {
      roundProblems.add("技能目录残留：" + entries);
    }
    if (roundProblems.isEmpty()) {
      return null;
    }
    return "第 "
        + round
        + " 轮（"
        + name
        + "）：父="
        + describe(parentOutcome)
        + "，子="
        + childOutcome
        + "，盘上="
        + onDisk
        + "；"
        + String.join("；", roundProblems);
  }

  private Object attempt(
      AgentPermissionSet permissions, Path skillsRoot, String name, String marker) {
    try {
      SkillAccess access = SkillAccess.of(permissions, new SkillCatalog(), skillsRoot);
      return access.create(name, "跨进程-" + name, marker + "-BODY", null);
    } catch (Throwable failure) {
      return failure;
    }
  }

  private Path root(String name) {
    return tempDir.resolve("skills").resolve(name);
  }

  /** 盘上正文的标记（PARENT / CHILD / 读不出来的原因）。 */
  private String onDiskMarker(Path directory) {
    try {
      SkillLoader.Parsed parsed =
          SkillLoader.parse(
              Files.readString(directory.resolve(Skill.SKILL_FILE_NAME), StandardCharsets.UTF_8),
              directory);
      return switch (parsed.body()) {
        case "PARENT-BODY" -> "PARENT";
        case "CHILD-BODY" -> "CHILD";
        default -> "未知正文(" + parsed.body() + ")";
      };
    } catch (IOException | RuntimeException e) {
      return "不可读(" + e + ")";
    }
  }

  private static List<String> entriesOf(Path directory) {
    try (var children = Files.list(directory)) {
      return children.map(child -> child.getFileName().toString()).sorted().toList();
    } catch (IOException e) {
      return List.of("目录不可列举: " + e);
    }
  }

  private static String describe(Object outcome) {
    if (outcome instanceof SkillAccess.WriteResult result) {
      return "成功(" + result.skill().name() + ")";
    }
    if (outcome instanceof SkillException failure) {
      return "FAIL:" + failure.code();
    }
    return String.valueOf(outcome);
  }

  private static void awaitFile(Path file, String what, Process child, Path childLog)
      throws IOException, InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
    while (!Files.exists(file)) {
      if (!child.isAlive()) {
        throw new AssertionError(
            "子进程提前退出（等待 "
                + what
                + " "
                + file.getFileName()
                + "）%n子进程日志:%n"
                + childLogText(childLog));
      }
      if (System.nanoTime() > deadline) {
        throw new AssertionError(
            "等待 " + what + " 超时: " + file.getFileName() + "%n子进程日志:%n" + childLogText(childLog));
      }
      Thread.sleep(1);
    }
  }

  private static String childLogText(Path childLog) {
    try {
      return Files.readString(childLog, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "（读不到子进程日志: " + e + "）";
    }
  }
}
