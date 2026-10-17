package io.mosire.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.workspace.CapabilityCatalog;
import io.mosire.workspace.DefaultWorkspaceRegistry;
import io.mosire.workspace.Kind;
import io.mosire.workspace.WorkspaceCaller;
import io.mosire.workspace.WorkspaceException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Docs 侧的路径安全判据（审核 P0-2 的 Docs 半边）：{@code FILE} 的 read/create/edit 与 {@code LOG} 的挂载根都必须经工作区 {@code
 * resolveForIo}，并且磁盘实现自己也不跟随软链。
 *
 * <p><b>为什么这些用例必须走库 API 而不是只测工作区</b>：工作区侧的安全解析已经通过 {@code WorkspacePathSecurityTest}；本类要证明 Docs
 * 真的把它接上了——否则 {@code resolveForIo} 只是一条没人走的安全旁路，Docs 仍会顺着软链读写挂载外文件。
 *
 * <p><b>逐条对号</b>：外部软链文件 READ 拒；外部软链父目录 CREATE/EDIT 拒且外部零写入；内部软链默认拒； {@code FILE} list 拒软链目录；{@code
 * LOG} 软链日志文件在 read/append/query 三个口都拒。负向同时断言稳定码与“文案不含物理路径”。
 */
class DocsPathSecurityTest {

  @TempDir Path tempDir;

  private Path fileRoot;
  private Path logRoot;
  private Path outside;
  private WorkspaceCaller caller;
  private DefaultWorkspaceRegistry registry;
  private DocsEngine engine;

  @BeforeEach
  void setUp() throws IOException {
    fileRoot = Files.createDirectories(tempDir.resolve("workspace").resolve("main"));
    logRoot = Files.createDirectories(tempDir.resolve("journal").resolve("main"));
    outside = Files.createDirectories(tempDir.resolve("outside"));
    CapabilityCatalog catalog = DocsFixture.catalog();
    caller = DocsFixture.caller("main", tempDir);
    registry =
        DocsFixture.registryWith(
            catalog, DocsFixture.mount("docs", "main", "main", fileRoot, Kind.FILE), caller);
    registry.mount(DocsFixture.mount("journal", "main", "main", logRoot, Kind.LOG), caller);
    engine = new DocsEngine(registry);
  }

  /** 挂载内软链指向外部文件 ⇒ READ 拒，且外部内容不出现在异常里。 */
  @Test
  void fileReadThroughExternalSymlinkIsRejected() throws IOException {
    Path secret = outside.resolve("secret.md");
    Files.writeString(secret, "TOP-SECRET-CONTENT");
    Files.createSymbolicLink(fileRoot.resolve("leak.md"), secret);

    DocException denied =
        catchThrowableOfType(() -> engine.readFile("docs/leak.md", caller, ""), DocException.class);

    assertThat(denied.code()).isEqualTo(DocException.RESOURCE_DENIED);
    assertThat(denied.getMessage()).doesNotContain("TOP-SECRET-CONTENT");
    assertNoPhysicalPath(denied);
  }

  /** 挂载内软链父目录指向外部目录 ⇒ CREATE 拒，外部目录零新增。 */
  @Test
  void fileCreateThroughExternalSymlinkParentIsRejectedAndWritesNothingOutside()
      throws IOException {
    Path escape = fileRoot.resolve("escape");
    Files.createSymbolicLink(escape, outside);

    DocException denied =
        catchThrowableOfType(
            () -> engine.createFile("docs/escape/pwn.md", caller, "outside-write"),
            DocException.class);

    assertThat(denied.code()).isEqualTo(DocException.RESOURCE_DENIED);
    assertThat(childrenOf(outside)).as("外部目录零新增（含临时文件）").isEmpty();
    assertNoPhysicalPath(denied);
  }

  /** 挂载内软链父目录指向外部目录 ⇒ EDIT 拒，外部目标一个字节不动。 */
  @Test
  void fileEditThroughExternalSymlinkParentIsRejectedAndLeavesOutsideUntouched()
      throws IOException {
    Path victim = outside.resolve("victim.md");
    Files.writeString(victim, "original-outside\n");
    Files.createSymbolicLink(fileRoot.resolve("egress"), outside);

    DocException denied =
        catchThrowableOfType(
            () ->
                engine.editFile(
                    "docs/egress/victim.md",
                    caller,
                    List.of(EditOp.of(DocAddr.line(1), DocOpKind.REPLACE, "pwned"))),
            DocException.class);

    assertThat(denied.code()).isEqualTo(DocException.RESOURCE_DENIED);
    assertThat(Files.readString(victim)).isEqualTo("original-outside\n");
    assertThat(childrenOf(outside)).as("外部目录零新增（含临时文件）").containsExactly("victim.md");
    assertNoPhysicalPath(denied);
  }

  /** 指向挂载内另一个文件的软链：默认策略仍是拒（只有显式 RESOLVE_WITHIN_ROOT 的读路径才谈得上允许）。 */
  @Test
  void internalSymlinkIsRejectedByDefault() throws IOException {
    Path real = fileRoot.resolve("real.md");
    Files.writeString(real, "internal");
    Files.createSymbolicLink(fileRoot.resolve("alias.md"), real);
    Path realDir = Files.createDirectories(fileRoot.resolve("real-dir"));
    Files.createSymbolicLink(fileRoot.resolve("dir-alias"), realDir);

    DocException readDenied =
        catchThrowableOfType(
            () -> engine.readFile("docs/alias.md", caller, ""), DocException.class);
    DocException createDenied =
        catchThrowableOfType(
            () -> engine.createFile("docs/dir-alias/new.md", caller, "x"), DocException.class);

    assertThat(readDenied.code()).isEqualTo(DocException.RESOURCE_DENIED);
    assertThat(createDenied.code()).isEqualTo(DocException.RESOURCE_DENIED);
    assertThat(Files.exists(realDir.resolve("new.md"))).isFalse();
    assertNoPhysicalPath(readDenied);
    assertNoPhysicalPath(createDenied);
  }

  /** {@code FILE} list 也是文件系统读取：软链目录必须拒（走注册表已安全前置的 {@code list}）。 */
  @Test
  void fileListThroughSymlinkedDirectoryIsRejected() throws IOException {
    Path outsideDir = Files.createDirectories(outside.resolve("listing"));
    Files.writeString(outsideDir.resolve("leaked.md"), "outside");
    Files.createSymbolicLink(fileRoot.resolve("linked-dir"), outsideDir);

    AgentTool docList = DocsFixture.tool(registry, DocsTools.DOC_LIST);
    ToolContext context = DocsFixture.context("main", docList, tempDir);
    DocsAccess access = new DocsAccess(registry);

    WorkspaceException denied =
        catchThrowableOfType(
            () -> access.list(context, "docs/linked-dir"), WorkspaceException.class);

    assertThat(denied.code()).isEqualTo(WorkspaceException.RESOURCE_DENIED);
    assertNoPhysicalPath(denied);
  }

  /** {@code LOG}：软链日志文件在 read / append / query 三个口都拒，外部文件不被读也不被写。 */
  @Test
  void logSymlinkedLogFileIsRejectedOnReadAppendAndScan() throws IOException {
    Path externalLog = outside.resolve("notes.log");
    Files.writeString(externalLog, "{\"address\":\"notes\",\"status\":\"EFFECTIVE\"}\n");
    Files.createSymbolicLink(logRoot.resolve(DocLogs.fileName("notes")), externalLog);

    DocException readDenied =
        catchThrowableOfType(
            () -> engine.read("journal", caller, DocViewer.seesAll("main"), "notes"),
            DocException.class);
    DocException appendDenied =
        catchThrowableOfType(
            () ->
                engine.append(
                    "journal", caller, AppendRequest.of("notes", 1, "k", "must-not-land")),
            DocException.class);
    DocException queryDenied =
        catchThrowableOfType(
            () -> engine.query("journal", caller, QuerySpec.latest(1), DocViewer.seesAll("main")),
            DocException.class);

    assertThat(readDenied.code()).isEqualTo(DocException.RESOURCE_DENIED);
    assertThat(appendDenied.code()).isEqualTo(DocException.RESOURCE_DENIED);
    assertThat(queryDenied.code()).isEqualTo(DocException.RESOURCE_DENIED);
    assertThat(Files.readString(externalLog))
        .as("外部日志一个字节都不动")
        .isEqualTo("{\"address\":\"notes\",\"status\":\"EFFECTIVE\"}\n");
    assertNoPhysicalPath(readDenied);
    assertNoPhysicalPath(appendDenied);
    assertNoPhysicalPath(queryDenied);
  }

  /**
   * 纵深防御：即使调用方绕过 {@link DocsEngine} 直接拿 {@link DiskDocFiles}，它自己也必须逐段拒软链。
   *
   * <p>这条用例是磁盘实现的独立保险：引擎侧的 {@code resolveForIo} 与磁盘侧的 no-symlink 检查是两层防线，不能只测上面一层。
   */
  @Test
  void diskDocFilesItselfRejectsSymlinks() throws IOException {
    Path victim = outside.resolve("direct-victim.md");
    Files.writeString(victim, "outside");
    Files.createSymbolicLink(fileRoot.resolve("direct-link.md"), victim);
    Files.createSymbolicLink(fileRoot.resolve("direct-escape"), outside);
    DiskDocFiles store = new DiskDocFiles(fileRoot, relative -> "docs/" + relative);

    DocException readDenied =
        catchThrowableOfType(() -> store.read("direct-link.md", ""), DocException.class);
    DocException createDenied =
        catchThrowableOfType(() -> store.create("direct-escape/pwn.md", "x"), DocException.class);

    assertThat(readDenied.code()).isEqualTo(DocException.RESOURCE_DENIED);
    assertThat(createDenied.code()).isEqualTo(DocException.RESOURCE_DENIED);
    assertThat(childrenOf(outside)).as("直接调用磁盘实现也不得写到外部目录").containsExactly("direct-victim.md");
    assertNoPhysicalPath(readDenied);
    assertNoPhysicalPath(createDenied);
  }

  /** 负向文案纪律：异常消息不得含挂载根/外部目录的物理路径（物理落点只进日志）。 */
  private void assertNoPhysicalPath(Throwable error) {
    assertThat(error.getMessage())
        .doesNotContain(tempDir.toAbsolutePath().normalize().toString())
        .doesNotContain(outside.toAbsolutePath().normalize().toString())
        .doesNotContain(fileRoot.toAbsolutePath().normalize().toString())
        .doesNotContain(logRoot.toAbsolutePath().normalize().toString());
  }

  private static List<String> childrenOf(Path directory) throws IOException {
    try (var children = Files.list(directory)) {
      return children.map(path -> path.getFileName().toString()).sorted().toList();
    }
  }
}
