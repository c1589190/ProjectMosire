package io.mosire.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.workspace.CapabilityCatalog;
import io.mosire.workspace.Kind;
import io.mosire.workspace.WorkspaceCaller;
import io.mosire.workspace.WorkspaceRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code LOG} 纪律的判据表（设计 §5.2 / §5.3 / §5.5 / §5.9 全量），全离线，走库 API（{@link DocsEngine}）。
 *
 * <p><b>逐条对号</b>：
 *
 * <ul>
 *   <li>正向：{@link #appendThenReadBackByIdAndAddress()}、{@link #querySortsThenTruncates()}、 {@link
 *       #sameTickOrdersByOrdinalNumerically()}、{@link #emptyResultDoesNotThrow()}、 {@link
 *       #fieldsAndValueRoundTripOpaque()}、{@link #gmSeesEverythingOthersSeeOnlyTheirOwn()}、 {@link
 *       #voidAppendsVoidedTrailAndIsIdempotent()}、{@link #samePayloadReplaysToSameId()}；
 *   <li>负向：{@link #tickAndRangeAreMutuallyExclusive()}、{@link #limitMustBePositive()}、 {@link
 *       #invalidAddressIsRejectedAtWriteSide()}、{@link #collidingIdIsRejectedLoudly()}、 {@link
 *       #outOfScopeIsResourceDenied()}、{@link #unknownMountIsSameAnswerAsDenied()}、 {@link
 *       #invisibleIsSameAnswerAsMissing()}、{@link #brokenLineIsDisclosedNotSilent()}、 {@link
 *       #recordAddressMismatchIsSkippedAndDisclosed()}。
 * </ul>
 */
class DocLogsEngineTest {

  @TempDir Path tempDir;

  private Path dir;
  private CapabilityCatalog catalog;
  private WorkspaceCaller caller;
  private WorkspaceRegistry registry;
  private DocsEngine engine;

  @BeforeEach
  void setUp() throws IOException {
    dir = Files.createDirectories(tempDir.resolve("workspace").resolve("main"));
    catalog = DocsFixture.catalog();
    caller = DocsFixture.caller("main", tempDir);
    registry =
        DocsFixture.registryWith(
            catalog, DocsFixture.mount("docs", "main", "main", dir, Kind.LOG), caller);
    engine = new DocsEngine(registry);
  }

  // ────────────────────────────── 正向 ──────────────────────────────

  /** §5.9 正向：追加一条 ⇒ 按 id / 地址读回。 */
  @Test
  void appendThenReadBackByIdAndAddress() {
    DocEntry appended = engine.append("docs", caller, AppendRequest.of("notes", 3, "k", "v"));
    assertThat(appended.id().toString()).isEqualTo("notes#0");
    assertThat(appended.status()).isEqualTo(DocStatus.EFFECTIVE);
    assertThat(appended.ordinal()).isZero();

    assertThat(engine.find("docs", caller, DocViewer.seesAll("main"), "notes#0"))
        .get()
        .extracting(DocEntry::value)
        .isEqualTo("v");
    assertThat(engine.read("docs", caller, DocViewer.seesAll("main"), "notes"))
        .extracting(DocEntry::value)
        .containsExactly("v");
    assertThat(engine.list("docs", caller, DocViewer.seesAll("main")))
        .singleElement()
        .satisfies(summary -> assertThat(summary.address()).isEqualTo("notes"));
  }

  /** §5.5：先排后截——tick 降序取最新 limit 条（先截断再排序会得到另一批）。 */
  @Test
  void querySortsThenTruncates() {
    for (int i = 0; i < 12; i++) {
      engine.append("docs", caller, AppendRequest.of("notes", i, "k" + i, i));
    }

    List<DocEntry> latest =
        engine.query("docs", caller, QuerySpec.latest(5), DocViewer.seesAll("main"));

    assertThat(latest).extracting(DocEntry::tick).containsExactly(11L, 10L, 9L, 8L, 7L);
    assertThat(engine.query("docs", caller, QuerySpec.atTick(4, 10), DocViewer.seesAll("main")))
        .extracting(DocEntry::value)
        .as("数值经 NDJSON 往返后统一为 Long（JSON 只有一种 number）")
        .containsExactly(4L);
    assertThat(engine.query("docs", caller, QuerySpec.range(2L, 5L, 10), DocViewer.seesAll("main")))
        .extracting(DocEntry::tick)
        .containsExactly(5L, 4L, 3L, 2L);
  }

  /** §5.5：同 tick 按 (地址, 序号数值) 升序——{@code #2} vs {@code #10} 必须是数值序。 */
  @Test
  void sameTickOrdersByOrdinalNumerically() {
    for (int i = 0; i < 12; i++) {
      engine.append("docs", caller, AppendRequest.of("notes", 7, "k" + i, i));
    }

    List<DocEntry> entries = engine.read("docs", caller, DocViewer.seesAll("main"), "notes");

    assertThat(entries).hasSize(12);
    assertThat(entries).extracting(DocEntry::ordinal).containsExactlyElementsOf(ordinals(12));
    assertThat(engine.query("docs", caller, QuerySpec.atTick(7, 12), DocViewer.seesAll("main")))
        .extracting(entry -> entry.id().toString())
        .startsWith("notes#0", "notes#1", "notes#2", "notes#3")
        .contains("notes#10", "notes#11");
  }

  /** §5.5：空结果不抛（不存在的地址、不命中的 tick 都返回空表）。 */
  @Test
  void emptyResultDoesNotThrow() {
    assertThat(engine.read("docs", caller, DocViewer.seesAll("main"), "nothing")).isEmpty();
    assertThat(engine.find("docs", caller, DocViewer.seesAll("main"), "nothing#0")).isEmpty();
    assertThat(engine.query("docs", caller, QuerySpec.atTick(9, 5), DocViewer.seesAll("main")))
        .isEmpty();
  }

  /**
   * §5.4/§5.9 正向：{@code fields} 与 {@code value} 原样进原样出（不解析、不改写）。
   *
   * <p><b>诚实边界</b>：数值标量经 NDJSON 往返后统一成 {@code Long}（JSON 只有一种 number）——{@code Integer 7} 读回是 {@code
   * 7L}。这是 JSON 的语义，不是"改写内容"（字符串/嵌套结构/键序都逐字保留）。
   */
  @Test
  void fieldsAndValueRoundTripOpaque() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("worldid", "w-1");
    fields.put("branch", Map.of("branch", "main", "revision", 7L));
    String value = "第一行\n第二行\t\"引号\" ${不解析} \\ 反斜杠";
    engine.append(
        "docs",
        caller,
        new AppendRequest(
            "world/1",
            5,
            Set.of("tag-a"),
            Set.of("aff-1"),
            Optional.of("main"),
            "k",
            value,
            Optional.of("备注"),
            fields,
            Optional.of("at-1")));

    DocEntry read = engine.read("docs", caller, DocViewer.seesAll("main"), "world/1").get(0);

    assertThat(read.value()).isEqualTo(value);
    assertThat(read.fields()).containsExactlyInAnyOrderEntriesOf(fields);
    assertThat(read.note()).contains("备注");
    assertThat(read.at()).contains("at-1");
  }

  /** §5.4：GM 无条件看见全部；普通观看者只看见 owner/tags/affiliations 命中的。 */
  @Test
  void gmSeesEverythingOthersSeeOnlyTheirOwn() {
    engine.append(
        "docs",
        caller,
        new AppendRequest(
            "a",
            1,
            Set.of(),
            Set.of(),
            Optional.of("kid"),
            "k",
            "owner-only",
            Optional.empty(),
            Map.of(),
            Optional.empty()));
    engine.append(
        "docs",
        caller,
        new AppendRequest(
            "a",
            2,
            Set.of("kid"),
            Set.of(),
            Optional.empty(),
            "k",
            "tagged",
            Optional.empty(),
            Map.of(),
            Optional.empty()));
    engine.append(
        "docs",
        caller,
        new AppendRequest(
            "a",
            3,
            Set.of(),
            Set.of("team"),
            Optional.empty(),
            "k",
            "affiliated",
            Optional.empty(),
            Map.of(),
            Optional.empty()));
    engine.append("docs", caller, AppendRequest.of("a", 4, "k", "orphan"));

    assertThat(engine.read("docs", caller, DocViewer.seesAll("main"), "a")).hasSize(4);
    List<DocEntry> kid = engine.read("docs", caller, DocViewer.of("kid", Set.of("team")), "a");
    assertThat(kid)
        .extracting(DocEntry::value)
        .containsExactly("owner-only", "tagged", "affiliated");
    assertThat(engine.list("docs", caller, DocViewer.of("kid", Set.of("team"))))
        .as("条数按可见性统计（数出不可见的条目等于泄露存在性）")
        .singleElement()
        .satisfies(summary -> assertThat(summary.visibleEntries()).isEqualTo(3));
  }

  /** §5.5：作废 = 追加一条 {@code VOIDED}（原记录不动）；重复作废幂等。 */
  @Test
  void voidAppendsVoidedTrailAndIsIdempotent() {
    engine.append("docs", caller, AppendRequest.of("a", 1, "k", "v"));

    DocEntry voided = engine.voidEntry("docs", caller, "a#0");

    assertThat(voided.status()).isEqualTo(DocStatus.VOIDED);
    List<DocEntry> all = engine.read("docs", caller, DocViewer.seesAll("main"), "a");
    assertThat(all).hasSize(2);
    assertThat(all.get(0).status()).as("原记录不动").isEqualTo(DocStatus.EFFECTIVE);
    assertThat(all.get(1).status()).isEqualTo(DocStatus.VOIDED);

    engine.voidEntry("docs", caller, "a#0");
    assertThat(engine.read("docs", caller, DocViewer.seesAll("main"), "a")).hasSize(2);
  }

  /** §5.2/§5.9 正向：同地址 + 同载荷 ⇒ 同 id（可重放），不重复落盘。 */
  @Test
  void samePayloadReplaysToSameId() {
    DocEntry first = engine.append("docs", caller, AppendRequest.of("a", 1, "k", "v"));
    DocEntry replay = engine.append("docs", caller, AppendRequest.of("a", 1, "k", "v"));

    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(engine.read("docs", caller, DocViewer.seesAll("main"), "a")).hasSize(1);
  }

  // ────────────────────────────── 负向 ──────────────────────────────

  /** §5.5：{@code tick} 精确 与 闭区间 互斥、{@code limit} 必填 > 0——构造期具名拒。 */
  @Test
  void tickAndRangeAreMutuallyExclusive() {
    DocException both =
        catchThrowableOfType(
            () -> new QuerySpec(Optional.of(1L), Optional.of(2L), Optional.empty(), 5),
            DocException.class);
    assertThat(both.code()).isEqualTo(DocException.INVALID_ARGUMENTS);

    DocException reversed =
        catchThrowableOfType(() -> QuerySpec.range(9L, 2L, 5), DocException.class);
    assertThat(reversed.code()).isEqualTo(DocException.INVALID_ARGUMENTS);
  }

  @Test
  void limitMustBePositive() {
    assertThatThrownBy(() -> QuerySpec.latest(0))
        .isInstanceOf(DocException.class)
        .extracting(e -> ((DocException) e).code())
        .isEqualTo(DocException.INVALID_ARGUMENTS);
    assertThatThrownBy(() -> QuerySpec.latest(-1)).isInstanceOf(DocException.class);
  }

  /** §5.2/B6：非法地址在<b>写入侧</b>就拒（不留到读侧表现为"文档静默消失"）。 */
  @Test
  void invalidAddressIsRejectedAtWriteSide() {
    List<String> bad = List.of("", "  ", "has space", "has#hash", "double//slash", "x".repeat(201));
    for (String address : bad) {
      assertThatThrownBy(() -> DocAddress.require(address))
          .as("地址 '%s'", address)
          .isInstanceOf(DocException.class)
          .extracting(e -> ((DocException) e).code())
          .isEqualTo(DocException.INVALID_ARGUMENTS);
    }
    assertThatThrownBy(
            () -> engine.append("docs", caller, AppendRequest.of("has#hash", 1, "k", "v")))
        .isInstanceOf(DocException.class)
        .extracting(e -> ((DocException) e).code())
        .isEqualTo(DocException.INVALID_ARGUMENTS);
  }

  /**
   * §5.2/§5.9 负向：撞 id 具名拒（{@code DOC_EXISTS}）。
   *
   * <p>造法：先落三行（序号 0/1/2），再手工删掉中间那行——文件里剩 0 与 2，条数=2 ⇒ 下一次追加算出的序号正是 2，与已有记录撞 id 且载荷不同 ⇒ 写入侧具名拒。
   */
  @Test
  void collidingIdIsRejectedLoudly() throws IOException {
    for (int i = 0; i < 3; i++) {
      engine.append("docs", caller, AppendRequest.of("a", i, "k", "v" + i));
    }
    Path file = DocLogs.logFile(dir, "a");
    List<String> lines = new ArrayList<>(Files.readAllLines(file));
    assertThat(lines).hasSize(3);
    lines.remove(1);
    Files.write(file, lines);

    DocException denied =
        catchThrowableOfType(
            () -> engine.append("docs", caller, AppendRequest.of("a", 9, "k", "new")),
            DocException.class);

    assertThat(denied.code()).isEqualTo(DocException.DOC_EXISTS);
  }

  /** §5.6/§5.9 负向：范围外（未表态）⇒ {@code RESOURCE_DENIED}——不是"面内不存在"。 */
  @Test
  void outOfScopeIsResourceDenied() {
    WorkspaceCaller silent =
        DocsFixture.callerDeclaring("main", "fs", ResourceScope.ofDir(tempDir));

    DocException denied =
        catchThrowableOfType(
            () -> engine.append("docs", silent, AppendRequest.of("a", 1, "k", "v")),
            DocException.class);
    assertThat(denied.code()).isEqualTo(DocException.RESOURCE_DENIED);

    DocException readDenied =
        catchThrowableOfType(
            () -> engine.read("docs", silent, DocViewer.seesAll("main"), "a"), DocException.class);
    assertThat(readDenied.code()).isEqualTo(DocException.RESOURCE_DENIED);
  }

  /** §4.5/§5.9 负向：挂载名不存在与"无权"返回同一个回答（不泄露存在性）。 */
  @Test
  void unknownMountIsSameAnswerAsDenied() {
    DocException unknown =
        catchThrowableOfType(
            () -> engine.read("nothing", caller, DocViewer.seesAll("main"), "a"),
            DocException.class);
    WorkspaceCaller silent =
        DocsFixture.callerDeclaring("main", "fs", ResourceScope.ofDir(tempDir));
    DocException denied =
        catchThrowableOfType(
            () -> engine.read("docs", silent, DocViewer.seesAll("main"), "a"), DocException.class);

    assertThat(unknown.code()).isEqualTo(DocException.RESOURCE_DENIED).isEqualTo(denied.code());
  }

  /** §5.9 负向：不可见 ≡ 不存在——同一回答，不给"这条存在但你看不到"的任何提示。 */
  @Test
  void invisibleIsSameAnswerAsMissing() {
    engine.append(
        "docs",
        caller,
        new AppendRequest(
            "a",
            1,
            Set.of(),
            Set.of(),
            Optional.of("kid"),
            "k",
            "secret",
            Optional.empty(),
            Map.of(),
            Optional.empty()));
    DocViewer stranger = DocViewer.of("stranger", Set.of());

    assertThat(engine.find("docs", caller, stranger, "a#0")).isEmpty();
    assertThat(engine.find("docs", caller, stranger, "a#99")).isEmpty();
    assertThat(engine.read("docs", caller, stranger, "a")).isEmpty();
    assertThat(engine.list("docs", caller, stranger)).isEmpty();
    assertThat(engine.find("docs", caller, DocViewer.seesAll("main"), "a#0")).isPresent();
  }

  /**
   * §5.9 负向：坏行不静默（进 {@code broken} 披露并继续读）。
   *
   * <p>钉在存储层（{@link DocLogs} 是包私有类）：同一实例读一次 ⇒ 合法条目照常返回、坏行被披露。
   */
  @Test
  void brokenLineIsDisclosedNotSilent() throws IOException {
    engine.append("docs", caller, AppendRequest.of("a", 1, "k", "v1"));
    engine.append("docs", caller, AppendRequest.of("a", 2, "k", "v2"));
    Files.writeString(DocLogs.logFile(dir, "a"), "{oops\n", StandardOpenOption.APPEND);

    DocStore store = DocLogs.onDisk(dir);
    List<DocEntry> entries = store.read("a");

    assertThat(entries).extracting(DocEntry::value).containsExactly("v1", "v2");
    assertThat(store.broken()).as("坏行必须披露，不许静默跳过").isNotEmpty();
    assertThat(store.broken().get(0).reason()).contains("坏行");
  }

  /** §5.3/B5 负向：记录内 address 与文件名编码不一致 ⇒ 该行披露并跳过（身份以记录内为准）。 */
  @Test
  void recordAddressMismatchIsSkippedAndDisclosed() throws IOException {
    engine.append("docs", caller, AppendRequest.of("a", 1, "k", "good"));
    Path file = DocLogs.logFile(dir, "a");
    String good = Files.readString(file);
    Files.writeString(
        file,
        good.replace("\"address\":\"a\"", "\"address\":\"other\""),
        StandardOpenOption.APPEND);

    DocStore store = DocLogs.onDisk(dir);

    assertThat(store.read("a")).extracting(DocEntry::value).containsExactly("good");
    assertThat(store.broken()).isNotEmpty();
  }

  private static List<Long> ordinals(int count) {
    List<Long> ordinals = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      ordinals.add((long) i);
    }
    return ordinals;
  }
}
