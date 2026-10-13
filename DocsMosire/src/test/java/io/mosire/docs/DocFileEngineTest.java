package io.mosire.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.mosire.workspace.CapabilityCatalog;
import io.mosire.workspace.Kind;
import io.mosire.workspace.WorkspaceCaller;
import io.mosire.workspace.WorkspaceRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code FILE} 纪律（自实现 IO）的判据表（设计 §5.2 / §5.3 / §5.5 / §5.9），全离线，走库 API（{@link DocsEngine}）。
 *
 * <p><b>逐条对号</b>：
 *
 * <ul>
 *   <li>正向：{@link #readHonoursLinesRange()}、{@link #createIsAtomicAndCollisionIsRejected()}、 {@link
 *       #editWithUniqueAnchorHitsTheRightLine()}、{@link #allFourOpKindsApply()}、 {@link
 *       #editIsVisibleToSubsequentReads()}；
 *   <li>负向：{@link #anchorMissIsRejectedAndDiskUntouched()}、{@link
 *       #anchorNotUniqueIsRejectedAndDiskUntouched()}、 {@link
 *       #multiOpFailureLeavesDiskByteIdentical()}、{@link #expectMismatchIsRejected()}、 {@link
 *       #lineRangeOutOfBoundsIsRejected()}、{@link #emptyOpListIsRejected()}、 {@link
 *       #invalidLinesSpecIsRejected()}、{@link #badEncodingIsDisclosedNotSilent()}、 {@link
 *       #disciplineMismatchIsRejected()}、{@link #virtualPathEscapeIsRejectedAtTheVirtualLayer()}、
 *       {@link #missingFileIsNotFound()}.
 * </ul>
 */
class DocFileEngineTest {

  @TempDir Path tempDir;

  private Path dir;
  private WorkspaceCaller caller;
  private WorkspaceRegistry registry;
  private DocsEngine engine;

  @BeforeEach
  void setUp() throws IOException {
    dir = Files.createDirectories(tempDir.resolve("workspace").resolve("main"));
    CapabilityCatalog catalog = DocsFixture.catalog();
    caller = DocsFixture.caller("main", tempDir);
    registry =
        DocsFixture.registryWith(
            catalog, DocsFixture.mount("docs", "main", "main", dir, Kind.FILE), caller);
    engine = new DocsEngine(registry);
  }

  // ────────────────────────────── 正向 ──────────────────────────────

  /** §5.9 正向：{@code lines="10,40"} 只回该区间（缺省全文；上界越界截到总行数）。 */
  @Test
  void readHonoursLinesRange() {
    engine.createFile("docs/notes.md", caller, numbered(100));

    DocReadResult full = engine.readFile("docs/notes.md", caller, "");
    assertThat(full.totalLines()).isEqualTo(100);
    assertThat(full.content().lines().count()).isEqualTo(100);

    DocReadResult window = engine.readFile("docs/notes.md", caller, "10,40");
    assertThat(window.fromLine()).isEqualTo(10);
    assertThat(window.toLine()).isEqualTo(40);
    assertThat(window.content()).isEqualTo(numberedRange(10, 40));
    assertThat(window.content()).doesNotContain("line-9\n").doesNotContain("line-41\n");

    DocReadResult clipped = engine.readFile("docs/notes.md", caller, "90,1000");
    assertThat(clipped.toLine()).as("上界越界截到总行数（不是报错）").isEqualTo(100);
    assertThat(clipped.content()).isEqualTo(numberedRange(90, 100));
  }

  /** §5.9 正向：{@code doc_create} 原子建文件、内容一次落定；撞名拒且原文件一个字节不动。 */
  @Test
  void createIsAtomicAndCollisionIsRejected() throws IOException {
    DocFile created = engine.createFile("docs/sub/notes.md", caller, "第一行\n第二行");
    Path physical = dir.resolve("sub").resolve("notes.md");

    assertThat(created.virtualPath()).isEqualTo("docs/sub/notes.md");
    assertThat(created.lines()).isEqualTo(2);
    assertThat(Files.readString(physical)).as("末尾换行归一").isEqualTo("第一行\n第二行\n");

    DocException exists =
        catchThrowableOfType(
            () -> engine.createFile("docs/sub/notes.md", caller, "别的内容"), DocException.class);
    assertThat(exists.code()).isEqualTo(DocException.DOC_EXISTS);
    assertThat(Files.readString(physical)).as("撞名不静默覆盖").isEqualTo("第一行\n第二行\n");
    assertThat(childrenOf(dir.resolve("sub"))).as("不留临时文件").containsExactly("notes.md");
  }

  /** §5.9 正向：唯一精确锚点改对位置。 */
  @Test
  void editWithUniqueAnchorHitsTheRightLine() {
    engine.createFile("docs/a.md", caller, "alpha\nbeta\ngamma");

    engine.editFile(
        "docs/a.md", caller, List.of(EditOp.of(DocAddr.anchor("beta"), DocOpKind.REPLACE, "BETA")));

    assertThat(engine.readFile("docs/a.md", caller, "").content())
        .isEqualTo("alpha\nBETA\ngamma\n");
  }

  /** §5.5 正向：四种动作各施一次（REPLACE / INSERT_BEFORE / INSERT_AFTER / DELETE）。 */
  @Test
  void allFourOpKindsApply() {
    engine.createFile("docs/a.md", caller, "one\ntwo\nthree");

    engine.editFile(
        "docs/a.md",
        caller,
        List.of(
            EditOp.of(DocAddr.line(1), DocOpKind.REPLACE, "ONE"),
            EditOp.of(DocAddr.line(2), DocOpKind.DELETE, ""),
            EditOp.of(DocAddr.anchor("three"), DocOpKind.INSERT_BEFORE, "before-three"),
            EditOp.of(DocAddr.anchor("three"), DocOpKind.INSERT_AFTER, "after-three")));

    assertThat(engine.readFile("docs/a.md", caller, "").content())
        .isEqualTo("ONE\nbefore-three\nthree\nafter-three\n");
  }

  /** §5.3 正向：写完立刻对同一挂载内的后续读可见（没有缓存可以陈旧）。 */
  @Test
  void editIsVisibleToSubsequentReads() {
    engine.createFile("docs/a.md", caller, "old");

    engine.editFile(
        "docs/a.md", caller, List.of(EditOp.of(DocAddr.line(1), DocOpKind.REPLACE, "new")));

    assertThat(engine.readFile("docs/a.md", caller, "").content()).isEqualTo("new\n");
  }

  // ────────────────────────────── 负向 ──────────────────────────────

  /** §5.9 负向（B10）：锚点不命中 ⇒ 拒且盘上不动。 */
  @Test
  void anchorMissIsRejectedAndDiskUntouched() throws IOException {
    engine.createFile("docs/a.md", caller, "alpha\nbeta");
    String before = Files.readString(dir.resolve("a.md"));

    DocException denied =
        catchThrowableOfType(
            () ->
                engine.editFile(
                    "docs/a.md",
                    caller,
                    List.of(EditOp.of(DocAddr.anchor("nope"), DocOpKind.REPLACE, "x"))),
            DocException.class);

    assertThat(denied.code()).isEqualTo(DocException.INVALID_ARGUMENTS);
    assertThat(Files.readString(dir.resolve("a.md"))).isEqualTo(before);
  }

  /** §5.9 负向（B10）：锚点不唯一（两行同文本）⇒ 拒且盘上不动。 */
  @Test
  void anchorNotUniqueIsRejectedAndDiskUntouched() throws IOException {
    engine.createFile("docs/a.md", caller, "dup\nmid\ndup");
    String before = Files.readString(dir.resolve("a.md"));

    DocException denied =
        catchThrowableOfType(
            () ->
                engine.editFile(
                    "docs/a.md",
                    caller,
                    List.of(EditOp.of(DocAddr.anchor("dup"), DocOpKind.REPLACE, "x"))),
            DocException.class);

    assertThat(denied.code()).isEqualTo(DocException.INVALID_ARGUMENTS);
    assertThat(denied.getMessage()).contains("不唯一");
    assertThat(Files.readString(dir.resolve("a.md"))).isEqualTo(before);
  }

  /** §5.9 负向（B8/B10）：多项编辑<b>全成或全不成</b>——第一条成功、第二条失败時盘上仍是原内容。 */
  @Test
  void multiOpFailureLeavesDiskByteIdentical() throws IOException {
    engine.createFile("docs/a.md", caller, "alpha\nbeta\ngamma");
    byte[] before = Files.readAllBytes(dir.resolve("a.md"));

    DocException denied =
        catchThrowableOfType(
            () ->
                engine.editFile(
                    "docs/a.md",
                    caller,
                    List.of(
                        EditOp.of(DocAddr.line(1), DocOpKind.REPLACE, "ALPHA"),
                        EditOp.of(DocAddr.anchor("missing"), DocOpKind.REPLACE, "x"))),
            DocException.class);

    assertThat(denied.code()).isEqualTo(DocException.INVALID_ARGUMENTS);
    assertThat(Files.readAllBytes(dir.resolve("a.md"))).isEqualTo(before);
  }

  /** §5.5 负向：{@code expect} 前置断言不符 ⇒ 拒且盘上不动。 */
  @Test
  void expectMismatchIsRejected() throws IOException {
    engine.createFile("docs/a.md", caller, "alpha\nbeta");
    String before = Files.readString(dir.resolve("a.md"));

    DocException denied =
        catchThrowableOfType(
            () ->
                engine.editFile(
                    "docs/a.md",
                    caller,
                    List.of(EditOp.of(DocAddr.line(1), DocOpKind.REPLACE, "x", "别的原文"))),
            DocException.class);

    assertThat(denied.code()).isEqualTo(DocException.INVALID_ARGUMENTS);
    assertThat(denied.getMessage()).contains("expect");
    assertThat(Files.readString(dir.resolve("a.md"))).isEqualTo(before);
  }

  /** §5.5 负向：行号越界 ⇒ 拒且盘上不动。 */
  @Test
  void lineRangeOutOfBoundsIsRejected() throws IOException {
    engine.createFile("docs/a.md", caller, "alpha\nbeta");
    String before = Files.readString(dir.resolve("a.md"));

    DocException denied =
        catchThrowableOfType(
            () ->
                engine.editFile(
                    "docs/a.md",
                    caller,
                    List.of(EditOp.of(DocAddr.line(9), DocOpKind.REPLACE, "x"))),
            DocException.class);

    assertThat(denied.code()).isEqualTo(DocException.INVALID_ARGUMENTS);
    assertThat(denied.getMessage()).contains("越界");
    assertThat(Files.readString(dir.resolve("a.md"))).isEqualTo(before);
  }

  /** §5.5 负向：空操作列表是调用方的笔误，不是"不变"。 */
  @Test
  void emptyOpListIsRejected() {
    engine.createFile("docs/a.md", caller, "alpha");

    assertThatThrownBy(() -> engine.editFile("docs/a.md", caller, List.of()))
        .isInstanceOf(DocException.class)
        .extracting(e -> ((DocException) e).code())
        .isEqualTo(DocException.INVALID_ARGUMENTS);
  }

  /** §5.5 负向：{@code lines} 形态非法（非"起,止"/行号 <1/起点>终点/起点越界）逐格具名拒。 */
  @Test
  void invalidLinesSpecIsRejected() {
    engine.createFile("docs/a.md", caller, "one\ntwo\nthree");

    for (String spec : List.of("10", "a,b", "0,2", "3,1", "9,10", "10,", ",-1")) {
      assertThatThrownBy(() -> engine.readFile("docs/a.md", caller, spec))
          .as("lines='%s'", spec)
          .isInstanceOf(DocException.class)
          .extracting(e -> ((DocException) e).code())
          .isEqualTo(DocException.INVALID_ARGUMENTS);
    }
  }

  /** §5.3/§5.9 负向：坏文件不静默（非法 UTF-8 ⇒ {@code DOC_READ_FAILED} + {@code broken} 披露）。 */
  @Test
  void badEncodingIsDisclosedNotSilent() throws IOException {
    engine.createFile("docs/a.md", caller, "ok");
    Path physical = dir.resolve("a.md");
    Files.write(physical, new byte[] {(byte) 0xC3, (byte) 0x28});

    DocException failed =
        catchThrowableOfType(() -> engine.readFile("docs/a.md", caller, ""), DocException.class);

    assertThat(failed.code()).isEqualTo(DocException.DOC_READ_FAILED);
    DiskDocFiles store = new DiskDocFiles(dir, relative -> "docs/" + relative);
    assertThatThrownBy(() -> store.read("a.md", "")).isInstanceOf(DocException.class);
    assertThat(store.broken()).as("坏文件必须披露，不许静默").isNotEmpty();
  }

  /**
   * §5.9 正向："子 Agent 在自己的工作区里建 / 改 / 读成功"（§5.7 的 N9 主场景）。
   *
   * <p>挂载由父创建（{@code holder=父}、{@code user=子}），落点是父工作区内的真子目录；子体用自己的身份与 {@code grant} 落格后的面去做三件事。
   */
  @Test
  void subagentCanCreateEditAndReadInItsOwnWorkspace() throws IOException {
    Path childDir = Files.createDirectories(tempDir.resolve("workspaces").resolve("child"));
    CapabilityCatalog catalog = DocsFixture.catalog();
    WorkspaceCaller parent = DocsFixture.caller("main", tempDir);
    WorkspaceRegistry registry =
        DocsFixture.registryWith(
            catalog,
            Map.of("main", "main", "child", "main/child"),
            DocsFixture.mount("workspace", "main", "child", childDir, Kind.FILE),
            parent);
    WorkspaceCaller child =
        new WorkspaceCaller("child", registry.grant("child", DocsFixture.permissions(tempDir)));
    DocsEngine childEngine = new DocsEngine(registry);

    childEngine.createFile("workspace/notes.md", child, "第一行\n第二行");
    childEngine.editFile(
        "workspace/notes.md",
        child,
        List.of(EditOp.of(DocAddr.anchor("第二行"), DocOpKind.REPLACE, "改过了")));
    DocReadResult read = childEngine.readFile("workspace/notes.md", child, "");

    assertThat(read.content()).isEqualTo("第一行\n改过了\n");
    assertThat(read.path()).as("面向模型只出虚拟路径").isEqualTo("workspace/notes.md");
    assertThat(Files.readString(childDir.resolve("notes.md"))).isEqualTo("第一行\n改过了\n");
  }

  /** §5.9 负向（B9）：{@code FILE} 的操作打在 LOG / SCRATCH / REFERENCE 挂载上一律具名拒。 */
  @Test
  void disciplineMismatchIsRejected() throws IOException {
    Path logDir = Files.createDirectories(tempDir.resolve("logs"));
    Path scratchDir = Files.createDirectories(tempDir.resolve("scratch"));
    Path refDir = Files.createDirectories(tempDir.resolve("ref"));
    CapabilityCatalog catalog = DocsFixture.catalog();
    WorkspaceCaller main = DocsFixture.caller("main", tempDir);
    WorkspaceRegistry multi =
        DocsFixture.registryWith(
            catalog, DocsFixture.mount("logs", "main", "main", logDir, Kind.LOG), main);
    multi.mount(DocsFixture.mount("scratch", "main", "main", scratchDir, Kind.SCRATCH), main);
    multi.mount(DocsFixture.mount("ref", "main", "main", refDir, Kind.REFERENCE), main);
    DocsEngine multiEngine = new DocsEngine(multi);

    for (String mount : List.of("logs", "scratch", "ref")) {
      assertThatThrownBy(() -> multiEngine.readFile(mount + "/a.md", main, ""))
          .as("%s 上读文件", mount)
          .isInstanceOf(DocException.class)
          .extracting(e -> ((DocException) e).code())
          .isEqualTo(DocException.DOC_INVALID);
      assertThatThrownBy(() -> multiEngine.createFile(mount + "/a.md", main, "x"))
          .as("%s 上建文件", mount)
          .isInstanceOf(DocException.class)
          .extracting(e -> ((DocException) e).code())
          .isEqualTo(DocException.DOC_INVALID);
    }
  }

  /** §4.2/§5.9 负向：越界路径在虚拟层就被拒（不留到 IO 层）。 */
  @Test
  void virtualPathEscapeIsRejectedAtTheVirtualLayer() {
    for (String virtualPath :
        List.of("docs/../x", "docs//x", "/docs/x", "docs/x/", "docs/./x", "doc s/x")) {
      assertThatThrownBy(() -> engine.readFile(virtualPath, caller, ""))
          .as("虚拟路径 '%s'", virtualPath)
          .isInstanceOf(DocException.class)
          .extracting(e -> ((DocException) e).code())
          .isEqualTo(DocException.INVALID_ARGUMENTS);
    }
  }

  /** §5.9 负向：面内不存在 ⇒ {@code DOC_NOT_FOUND}（与"不可见"同回答，见 DocsToolsDisciplineTest）。 */
  @Test
  void missingFileIsNotFound() {
    DocException missing =
        catchThrowableOfType(() -> engine.readFile("docs/nope.md", caller, ""), DocException.class);

    assertThat(missing.code()).isEqualTo(DocException.DOC_NOT_FOUND);
    assertThat(missing.getMessage()).doesNotContain(tempDir.toAbsolutePath().toString());
  }

  private static String numbered(int count) {
    return numberedRange(1, count);
  }

  /** 目录下的文件名（升序；用 try-with-resources 关掉目录流）。 */
  private static List<String> childrenOf(Path directory) throws IOException {
    try (java.util.stream.Stream<Path> children = Files.list(directory)) {
      return children.map(path -> path.getFileName().toString()).sorted().toList();
    }
  }

  private static String numberedRange(int from, int to) {
    StringBuilder text = new StringBuilder();
    for (int i = from; i <= to; i++) {
      text.append("line-").append(i).append('\n');
    }
    return text.toString();
  }
}
