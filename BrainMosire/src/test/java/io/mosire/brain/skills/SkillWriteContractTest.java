package io.mosire.brain.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 写路径的两条新纪律（2026-10-16，D1/D2）——判据来自设计文档 {@code 设计-Skill运行时能力.md §5} 与派单书，<b>不是</b>从实现反推：
 *
 * <ul>
 *   <li><b>D1 写后不抛</b>：写成功之后目录重扫失败<b>不改结论</b>——{@code create}/{@code edit} 仍返回成功，失败只降级为 {@link
 *       SkillAccess.WriteResult#disclosure()} 里一条<b>非空披露</b>；<b>不得</b>再抛 {@code
 *       SKILL_RELOAD_FAILED} （那会让模型重试并撞 {@code SKILL_EXISTS}，“写失败”与“已存在”两条互相矛盾的账会同时进事件库）；
 *   <li><b>D2 创建不覆盖</b>：{@code create} 撞名必须由<b>落盘那一步</b>判出（不覆盖、不发 {@code REPLACE_EXISTING}），
 *       而不是只靠写之前的 {@code Files.exists} 预检——两个 {@link SkillAccess} 实例并发建同名技能时，恰好一个成功、另一个 {@code
 *       SKILL_EXISTS}，盘上是赢家那份完整文档。
 * </ul>
 *
 * <p><b>写后重扫失败的注入缝</b>（两条，互相印证）：(1) 技能根<b>可写可进入但不可列举</b>（{@code --wx}）——写盘能成功、
 * 重扫必失败，走的是完整生产路径（{@code create} → 写 → 重扫）；(2) 根指向一个普通文件 + 包级可见的 {@link
 * SkillAccess#rescanDisclosure(String)} 直调（这是本次为测试放宽的唯一可见性，见报告）。每条注入都配一条<b>正控</b>： 同一条件下 {@code
 * refresh()} 确实抛 {@code SKILL_RELOAD_FAILED}——否则“注入没生效”会让负向用例变成幻影判别力。
 *
 * <p>判别性（变异自证见证据包 {@code .omo/evidence/r-角色与D2自证/}）：① 把 {@code rescanDisclosure} 改回“失败即抛” ⇒ 负向 D1
 * 用例当场红；② 把 {@code create} 的 mkdir 抢名换回“{@code Files.exists} 预检 + {@code createDirectories} +
 * 覆盖改名”（第一版修法）⇒ D2 并发用例当场红（2026-10-16 复验：200 轮里 189 轮两方都成功；跨进程版 {@link SkillCreateCrossProcessTest}
 * 同变异 45/60 轮）。D2 的“不覆盖”由内核 {@code mkdir(2)} 承担，不由改名承担。
 */
class SkillWriteContractTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** D2 并发轮数：每轮两个线程同时冲同一个新名字。 */
  private static final int RACE_ROUNDS = 200;

  /** 断言消息里最多逐条列出多少个异常轮（其余只报计数，避免报告被几百行噪声淹没）。 */
  private static final int MAX_REPORTED_ROUNDS = 10;

  @TempDir Path tempDir;

  private Path root;
  private SkillCatalog catalog;

  @BeforeEach
  void setUp() throws IOException {
    root = Files.createDirectories(tempDir.resolve("skills"));
    catalog = new SkillCatalog();
    catalog.scan(root);
  }

  // ---------- A：正向（写成功 ⇒ 披露为空 + 盘上有文件 + 立刻可见） ----------

  @Test
  void createAndEditSucceedWithEmptyDisclosureAndAreImmediatelyVisible() throws IOException {
    SkillAccess access = SkillTestSupport.unrestricted(catalog, root);

    SkillAccess.WriteResult created = access.create("alpha", "新技能", "正文甲", Set.of("Read"));
    assertThat(created.disclosure()).as("扫描正常时没有披露").isEmpty();
    assertThat(Files.readString(root.resolve("alpha").resolve(Skill.SKILL_FILE_NAME)))
        .contains("name: alpha")
        .contains("正文甲");
    assertThat(access.visibleSkills()).extracting(Skill::name).contains("alpha");
    assertThat(access.loadBody("alpha")).contains("正文甲");
    assertThat(catalog.lookup("alpha"))
        .as("写前解析的返回值就是写成功后目录里那份（D1 不再回查目录）")
        .contains(created.skill());

    SkillAccess.WriteResult edited = access.edit("alpha", "改过的用途", "正文乙", null);
    assertThat(edited.disclosure()).as("扫描正常时没有披露").isEmpty();
    assertThat(Files.readString(root.resolve("alpha").resolve(Skill.SKILL_FILE_NAME)))
        .contains("description: 改过的用途")
        .contains("正文乙")
        .doesNotContain("正文甲");
    assertThat(access.loadBody("alpha")).contains("正文乙");
    assertThat(catalog.lookup("alpha")).contains(edited.skill());
  }

  /**
   * edit 的<b>写后重扫调用点</b>接线证据（D1 的正常路径版）：只看 {@link SkillCatalog} 快照，不调 {@code loadBody}/{@code
   * search}（那两处入口自己会重扫，会把这条结论洗掉）。
   *
   * <p>★ 为什么没法用“写后重扫失败”端到端验 edit 那一侧：{@code edit} 写之前也要 {@code refresh()}（同一段扫描），
   * 任何让写后重扫失败的状态都会先让写前 refresh 失败 ⇒ 调用在落盘之前就以 {@code SKILL_RELOAD_FAILED} 死掉；
   * 失败只可能在两次扫描之间被注入（竞态），不是确定性用例能覆盖的缝（见报告的覆盖缺口一节）。共享的失败降级判定线 （{@code rescanDisclosure}）由 create
   * 两侧端到端覆盖；这条用例补上 edit 调用点的成功分支接线。
   */
  @Test
  void editRefreshesTheCatalogSnapshotAfterWriting() throws IOException {
    SkillAccess access = SkillTestSupport.unrestricted(catalog, root);
    access.create("alpha", "旧用途", "旧正文", null);
    assertThat(catalog.lookup("alpha").orElseThrow().description()).isEqualTo("旧用途");

    SkillAccess.WriteResult edited = access.edit("alpha", "新用途", "新正文", null);

    assertThat(edited.disclosure()).isEmpty();
    assertThat(catalog.lookup("alpha").orElseThrow().description())
        .as("edit 的写后重扫确实发生：快照从写前 refresh 的旧元数据更新到新元数据")
        .isEqualTo("新用途");
  }

  // ---------- B：负向 D1（写后扫描失败 ⇒ 成功 + 非空披露，不抛） ----------

  /**
   * 完整生产路径的注入：技能根 {@code --wx}（可写可进入、不可列举）⇒ {@code create} 的落盘成功，写后重扫必失败。
   *
   * <p>判据：<b>返回成功</b>（不抛 {@code SKILL_RELOAD_FAILED}）+ 非空披露 + 盘上文件完整（写没有被回滚、也没有被报成失败）。
   */
  @Test
  void createSucceedsWithDisclosureWhenPostWriteRescanFails() throws IOException {
    Path lockedRoot = Files.createDirectories(tempDir.resolve("locked-skills"));
    Set<PosixFilePermission> original = Files.getPosixFilePermissions(lockedRoot);
    Files.setPosixFilePermissions(
        lockedRoot, EnumSet.of(PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
    try {
      SkillAccess access = SkillTestSupport.unrestricted(new SkillCatalog(), lockedRoot);
      SkillException loud = catchThrowableOfType(access::refresh, SkillException.class);
      assertThat(loud.code())
          .as("正控：这个注入确实让扫描失败（否则下面的披露断言是幻影）")
          .isEqualTo(SkillException.SKILL_RELOAD_FAILED);

      SkillAccess.WriteResult result = access.create("ghostroot", "写后扫描失败", "正文丙", null);

      assertThat(result.skill().name()).isEqualTo("ghostroot");
      assertThat(result.disclosure()).as("写成功之后的扫描失败只降级为披露，不再抛 SKILL_RELOAD_FAILED").isPresent();
      assertThat(result.disclosure().orElseThrow()).contains("ghostroot");
      assertThat(Files.readString(lockedRoot.resolve("ghostroot").resolve(Skill.SKILL_FILE_NAME)))
          .as("盘上确实有完整文件——写没有被回滚")
          .contains("正文丙");
    } finally {
      Files.setPosixFilePermissions(lockedRoot, original);
    }
  }

  /** 同一条注入走工具面：模型实际拿到的结果必须是 {@code success=true} 且 JSON 里有 {@code disclosure} 字段。 */
  @Test
  void skillCreateToolReportsSuccessAndDisclosureWhenPostWriteRescanFails() throws IOException {
    Path lockedRoot = Files.createDirectories(tempDir.resolve("locked-skills-tool"));
    Set<PosixFilePermission> original = Files.getPosixFilePermissions(lockedRoot);
    Files.setPosixFilePermissions(
        lockedRoot, EnumSet.of(PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
    try {
      ToolRegistry registry = new ToolRegistry();
      for (AgentTool tool : SkillTools.all(new SkillCatalog(), lockedRoot)) {
        registry.register(tool);
      }

      ToolResult result =
          call(
              registry,
              SkillTools.SKILL_CREATE,
              Map.of("name", "ghostroot", "description", "写后扫描失败", "body", "正文丙"),
              SkillPermissions.withScopes(
                  AgentPermissionSet.system(),
                  ResourceScope.unlimited(),
                  ResourceScope.unlimited()));

      assertThat(result.success()).as(result.message()).isTrue();
      Map<String, Object> body = json(result.message());
      assertThat(body.get("name")).isEqualTo("ghostroot");
      assertThat(body.get("disclosure"))
          .as("写成功但重扫失败：结果里必须说清“写成了”")
          .isInstanceOf(String.class)
          .asString()
          .contains("ghostroot");
    } finally {
      Files.setPosixFilePermissions(lockedRoot, original);
    }
  }

  /**
   * 直调注入缝（派单书建议的那条）：根指向一个普通文件 ⇒ {@code scanTolerant} 抛 {@code IllegalArgumentException} ⇒ {@code
   * refresh()} 包成 {@code SkillException} ⇒ 写后路径必须把它降级为披露而不是异常。
   */
  @Test
  void rescanDisclosureIsBestEffortWhenTheSkillRootIsNotADirectory() throws IOException {
    Path notADirectory = tempDir.resolve("not-a-directory");
    Files.writeString(notADirectory, "普通文件", StandardCharsets.UTF_8);
    SkillAccess access = SkillTestSupport.unrestricted(new SkillCatalog(), notADirectory);

    SkillException loud = catchThrowableOfType(access::refresh, SkillException.class);
    assertThat(loud.code()).as("正控：非目录根确实让扫描失败").isEqualTo(SkillException.SKILL_RELOAD_FAILED);

    Optional<String> disclosure = access.rescanDisclosure("x");
    assertThat(disclosure).as("写后重扫失败 ⇒ 返回非空披露，而不是抛").isPresent();
    assertThat(disclosure.orElseThrow()).contains("x").contains("重扫失败");
  }

  // ---------- C：负向 D2（并发 create 同名 ⇒ 恰好一个赢，盘上是赢家那份） ----------

  /**
   * 两个 {@link SkillAccess} 实例（各自一份 {@link SkillCatalog} 快照，模拟父/子进程）<b>同时</b> {@code create}
   * 同一个新名字，跑 {@value #RACE_ROUNDS} 轮。每轮判三条：<b>恰好一个成功</b>；失败方是 {@code SKILL_EXISTS}；盘上文件是赢家那份
   * <b>可解析的完整文档</b>（不是半成品、也不是失败方的正文），且技能目录里没有残留临时文件。
   *
   * <p><b>现状（2026-10-16 复验，不是预期）：本用例是绿的。</b>{@code create} 先用 {@code Files.createDirectory}
   * 原子抢名，撞名必抛 {@code FileAlreadyExistsException} ⇒ 恰好一个赢家由内核保证；200 轮实测 0 异常（两方都成功 = 0）。曾经的写法（{@code
   * Files.exists} 预检 + {@code createDirectories} + 覆盖改名）不成立：{@code Files.move(..., ATOMIC_MOVE)}
   * 撞名在本平台<b>静默替换</b>，两方都可能通过预检。
   *
   * <p>判别性说明：把 mkdir 抢名换回上述旧写法，本用例当场红（实测 189/200 轮两方都返回成功，盘上只剩一方正文——工具回执说谎）； 跨进程版本 {@link
   * SkillCreateCrossProcessTest} 在同一变异下 45/60 轮红。
   */
  @Test
  void concurrentCreateOfTheSameNameYieldsExactlyOneWinner()
      throws IOException, InterruptedException {
    List<String> reported = new ArrayList<>();
    int anomalyRounds = 0;
    int doubleSuccessRounds = 0;
    int wrongContentRounds = 0;
    for (int round = 0; round < RACE_ROUNDS; round++) {
      String name = "race-" + round;
      CyclicBarrier start = new CyclicBarrier(2);
      AtomicReference<Object> left = new AtomicReference<>();
      AtomicReference<Object> right = new AtomicReference<>();
      Thread leftThread =
          Thread.ofPlatform()
              .name("skill-create-left-" + round)
              .start(
                  () ->
                      left.set(
                          attempt(
                              SkillTestSupport.unrestricted(new SkillCatalog(), root),
                              start,
                              name,
                              "LEFT")));
      Thread rightThread =
          Thread.ofPlatform()
              .name("skill-create-right-" + round)
              .start(
                  () ->
                      right.set(
                          attempt(
                              SkillTestSupport.unrestricted(new SkillCatalog(), root),
                              start,
                              name,
                              "RIGHT")));
      leftThread.join();
      rightThread.join();
      RoundCheck check = checkRound(round, name, left.get(), right.get());
      if (check.message() == null) {
        continue;
      }
      anomalyRounds++;
      if (check.doubleSuccess()) {
        doubleSuccessRounds++;
      }
      if (check.wrongContent()) {
        wrongContentRounds++;
      }
      if (reported.size() < MAX_REPORTED_ROUNDS) {
        reported.add(check.message());
      }
    }
    System.out.println(
        "[D2 竞态] 轮数="
            + RACE_ROUNDS
            + "，异常轮="
            + anomalyRounds
            + "（两方都成功="
            + doubleSuccessRounds
            + "，盘上内容≠赢家="
            + wrongContentRounds
            + "）");
    if (anomalyRounds > reported.size()) {
      reported.add("……另有 " + (anomalyRounds - reported.size()) + " 个异常轮未逐条列出");
    }
    assertThat(reported).as("并发 create 同名：恰好一个成功、另一个 SKILL_EXISTS、盘上是赢家那份完整文档").isEmpty();
  }

  /** 一轮的判定结果：{@code message == null} = 该轮正常。 */
  private record RoundCheck(boolean doubleSuccess, boolean wrongContent, String message) {}

  private RoundCheck checkRound(int round, String name, Object left, Object right) {
    Path directory = root.resolve(name);
    String onDisk = onDiskMarker(directory);
    List<String> problems = new ArrayList<>();
    long successes =
        (left instanceof SkillAccess.WriteResult ? 1 : 0)
            + (right instanceof SkillAccess.WriteResult ? 1 : 0);
    if (successes != 1) {
      problems.add("成功数=" + successes);
    }
    if (successes == 1) {
      Object loser = left instanceof SkillAccess.WriteResult ? right : left;
      if (!(loser instanceof SkillException failure)
          || !SkillException.SKILL_EXISTS.equals(failure.code())) {
        problems.add("失败方不是 SKILL_EXISTS：" + describe(loser));
      }
      String winner = left instanceof SkillAccess.WriteResult ? "LEFT" : "RIGHT";
      if (!onDisk.equals(winner)) {
        problems.add("盘上是 " + onDisk + " 的正文，赢家是 " + winner);
      }
    }
    List<String> entries = entriesOf(directory);
    if (!entries.equals(List.of(Skill.SKILL_FILE_NAME))) {
      problems.add("技能目录残留：" + entries);
    }
    if (problems.isEmpty()) {
      return new RoundCheck(false, false, null);
    }
    return new RoundCheck(
        successes > 1,
        problems.stream().anyMatch(problem -> problem.startsWith("盘上是")),
        "第 "
            + round
            + " 轮（"
            + name
            + "）：左="
            + describe(left)
            + "，右="
            + describe(right)
            + "，盘上="
            + onDisk
            + "；"
            + String.join("；", problems));
  }

  private static Object attempt(
      SkillAccess access, CyclicBarrier start, String name, String marker) {
    try {
      start.await();
      return access.create(name, "并发创建-" + marker, marker + "-BODY", null);
    } catch (Throwable failure) {
      return failure;
    }
  }

  /** 盘上正文的标记（{@code LEFT} / {@code RIGHT} / 读不出来的原因）——用来判“谁的字还在”。 */
  private String onDiskMarker(Path directory) {
    try {
      SkillLoader.Parsed parsed =
          SkillLoader.parse(
              Files.readString(directory.resolve(Skill.SKILL_FILE_NAME), StandardCharsets.UTF_8),
              directory);
      return switch (parsed.body()) {
        case "LEFT-BODY" -> "LEFT";
        case "RIGHT-BODY" -> "RIGHT";
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
      return failure.code();
    }
    return String.valueOf(outcome);
  }

  // ---------- 夹具 ----------

  private static ToolResult call(
      ToolRegistry registry,
      String tool,
      Map<String, Object> args,
      AgentPermissionSet permissions) {
    ToolContext context =
        new ToolContext(
            permissions.grantedToken(),
            permissions,
            Map.of(),
            args,
            io.mosire.agentlib.permission.AgentIdentity.UNKNOWN);
    return ToolCallAuthorizer.standard().execute(registry, tool, context);
  }

  private static Map<String, Object> json(String text) {
    try {
      return JSON.readValue(text, new TypeReference<Map<String, Object>>() {});
    } catch (IOException e) {
      throw new AssertionError("工具结果不是 JSON: " + text, e);
    }
  }
}
