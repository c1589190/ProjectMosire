package io.mosire.docs;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.workspace.CapabilityCatalog;
import io.mosire.workspace.Kind;
import io.mosire.workspace.WorkspaceCaller;
import io.mosire.workspace.WorkspaceRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code doc_*} 工具面的判据表（设计 §5.6 / §5.9），全离线：纪律声明式（B9）、不可见 ≡ 不存在、范围外 ⇒ {@code
 * RESOURCE_DENIED}、资源面与破坏性标记、列目录不含物理路径。
 *
 * <ul>
 *   <li>{@link #toolSurfaceMatchesDesign()}（§5.6 表 + §5.9"FILE 删除在 v1 工具面不存在"）；
 *   <li>{@link #manifestDefaultsToDeny()}（§5.6：读 {@code docs}、写 {@code docs-edit}，缺省 DENY）；
 *   <li>{@link #disciplineIsDeclarativeThroughTools()}（B9：LOG 上建/改文件、FILE 上追加/作废都具名拒，且盘上不动）；
 *   <li>{@link #invisibleAndMissingShareTheSameAnswer()}（§5.9 负向：不可见 ≡ 不存在，不泄露存在性）；
 *   <li>{@link #outOfScopeIsResourceDeniedAtTheToolBoundary()}（§5.6/§5.9：范围外 ⇒
 *       RESOURCE_DENIED，由唯一入口转码）；
 *   <li>{@link #listCountsOnlyVisibleEntriesAndHidesPhysicalPaths()}（§5.6/§5.9：条数按可见性统计、输出只含虚拟路径）。
 * </ul>
 */
class DocsToolsDisciplineTest {

  @TempDir Path tempDir;

  private Path logDir;
  private Path fileDir;
  private AgentPermissionSet permissions;
  private WorkspaceRegistry registry;

  @BeforeEach
  void setUp() throws IOException {
    logDir = Files.createDirectories(tempDir.resolve("logs"));
    fileDir = Files.createDirectories(tempDir.resolve("files"));
    CapabilityCatalog catalog = DocsFixture.catalog();
    permissions = DocsFixture.permissions(tempDir);
    WorkspaceCaller main = new WorkspaceCaller("main", permissions);
    registry = DocsFixture.registry(catalog);
    registry.mount(DocsFixture.mount("logs", "main", "main", logDir, Kind.LOG), main);
    registry.mount(DocsFixture.mount("files", "main", "main", fileDir, Kind.FILE), main);
  }

  /** §5.6：七个工具、资源面、破坏性标记；§5.9：v1 工具面里没有"删文件"。 */
  @Test
  void toolSurfaceMatchesDesign() {
    List<AgentTool> tools = DocsTools.all(registry);

    assertThat(tools)
        .extracting(AgentTool::name)
        .containsExactlyInAnyOrderElementsOf(DocsTools.NAMES);
    assertThat(DocsTools.NAMES)
        .as("v1 不做删除：rm / doc_delete 都不在工具面里")
        .doesNotContain("doc_delete", "doc_remove", "doc_rm", "rm")
        .hasSize(7);
    for (AgentTool tool : tools) {
      boolean write =
          Set.of(DocsTools.DOC_APPEND, DocsTools.DOC_VOID, DocsTools.DOC_CREATE, DocsTools.DOC_EDIT)
              .contains(tool.name());
      assertThat(tool.resources().namespaces())
          .as("%s 的资源面", tool.name())
          .containsExactly(write ? DocNamespaces.DOCS_EDIT : DocNamespaces.DOCS);
      assertThat(tool.spec().destructive()).as("%s 的破坏性标记", tool.name()).isEqualTo(write);
    }
  }

  /** §5.6：两个面的缺省策略都是 {@link ResourcePolicy#DENY}（未表态 = 什么都看不见/改不了）。 */
  @Test
  void manifestDefaultsToDeny() {
    for (AgentTool tool : DocsTools.all(registry)) {
      String namespace = tool.resources().namespaces().iterator().next();
      assertThat(tool.resources().policyFor(namespace)).isEqualTo(ResourcePolicy.DENY);
    }
  }

  /** B9：纪律是声明式的——LOG 挂载上建/改文件、FILE 挂载上追加/作废，都具名拒且盘上不动。 */
  @Test
  void disciplineIsDeclarativeThroughTools() throws IOException {
    ToolResult createdOnLog =
        execute(DocsTools.DOC_CREATE, Map.of("mount", "logs/a.md", "text", "x"));
    assertThat(createdOnLog.success()).isFalse();
    assertThat(createdOnLog.code()).isEqualTo(DocException.DOC_INVALID);

    ToolResult editedOnLog =
        execute(
            DocsTools.DOC_EDIT,
            Map.of(
                "mount",
                "logs/a.md",
                "ops",
                List.of(Map.of("op", "replace", "line", 1, "text", "y"))));
    assertThat(editedOnLog.code()).isEqualTo(DocException.DOC_INVALID);
    assertThat(childrenOf(logDir)).as("LOG 挂载上不得产出文件").isEmpty();

    ToolResult appendedOnFile =
        execute(
            DocsTools.DOC_APPEND,
            Map.of("mount", "files", "address", "a", "tick", 1, "value", "v"));
    assertThat(appendedOnFile.success()).isFalse();
    assertThat(appendedOnFile.code()).isEqualTo(DocException.DOC_INVALID);

    ToolResult voidedOnFile = execute(DocsTools.DOC_VOID, Map.of("mount", "files", "id", "a#0"));
    assertThat(voidedOnFile.code()).isEqualTo(DocException.DOC_INVALID);
    assertThat(childrenOf(fileDir)).as("FILE 挂载上一个文件都没建出来").isEmpty();

    // 反面对照：各自的纪律上都能做（否则上面的"拒"可能只是工具坏了）
    assertThat(
            execute(
                    DocsTools.DOC_APPEND,
                    Map.of("mount", "logs", "address", "a", "tick", 1, "value", "v"))
                .success())
        .isTrue();
    assertThat(execute(DocsTools.DOC_CREATE, Map.of("mount", "files/a.md", "text", "x")).success())
        .isTrue();
  }

  /** §5.9 负向：不可见 ≡ 不存在——工具面给同一个回答、同一句文案。 */
  @Test
  void invisibleAndMissingShareTheSameAnswer() {
    execute(
        DocsTools.DOC_APPEND,
        Map.of("mount", "logs", "address", "secret", "tick", 1, "value", "v", "owner", "kid"));

    ToolResult invisible = execute(DocsTools.DOC_READ, Map.of("mount", "logs", "id", "secret#0"));
    ToolResult missing = execute(DocsTools.DOC_READ, Map.of("mount", "logs", "id", "secret#99"));

    assertThat(invisible.success()).isFalse();
    assertThat(invisible.code()).isEqualTo(DocException.DOC_NOT_FOUND).isEqualTo(missing.code());
    assertThat(invisible.message().replace("secret#0", "<id>"))
        .as("同一句回答：文案里不许出现'存在但你看不见'的暗示")
        .isEqualTo(missing.message().replace("secret#99", "<id>"));
  }

  /** §5.6/§5.9 负向：范围外 ⇒ {@code RESOURCE_DENIED}（由唯一入口转码），挂载不存在同码。 */
  @Test
  void outOfScopeIsResourceDeniedAtTheToolBoundary() {
    AgentPermissionSet silent =
        DocsFixture.permissions(tempDir)
            .withResourceScopes(ResourceScopeMap.of("fs", ResourceScope.ofDir(tempDir)));
    AgentTool read = tool(DocsTools.DOC_READ);
    ToolRegistry tools = new ToolRegistry();
    tools.register(read);

    ToolResult denied =
        ToolCallAuthorizer.standard()
            .execute(
                tools,
                DocsTools.DOC_READ,
                DocsFixture.context("main", silent, read, Map.of("mount", "logs", "address", "a")));

    assertThat(denied.success()).isFalse();
    assertThat(denied.code()).isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);

    ToolResult unknownMount =
        execute(DocsTools.DOC_READ, Map.of("mount", "nothing", "address", "a"));
    assertThat(unknownMount.code())
        .as("挂载不存在与无权同码（不泄露存在性）")
        .isEqualTo(DocException.RESOURCE_DENIED);
  }

  /**
   * §4.11 负向："<b>未授予 {@code docs} 能力的挂载上调用 {@code doc_*} ⇒ {@code RESOURCE_DENIED}</b>"。
   *
   * <p>能力是"在这个工作区里能干什么"的唯一决定点（A8）：挂载没声明 {@code docs} ⇒ 解析点就不放行， 工具面因此报同一个码，而不是"面内不存在"。
   */
  @Test
  void mountWithoutDocsCapabilityDeniesDocTools() throws IOException {
    Path bareDir = Files.createDirectories(tempDir.resolve("bare"));
    WorkspaceCaller main = new WorkspaceCaller("main", permissions);
    registry.mount(
        DocsFixture.mount(
            "bare",
            "main",
            "main",
            bareDir,
            Kind.LOG,
            Set.of(),
            io.mosire.workspace.Origin.ASSEMBLY),
        main);

    ToolResult read = execute(DocsTools.DOC_READ, Map.of("mount", "bare", "address", "a"));
    ToolResult append =
        execute(
            DocsTools.DOC_APPEND, Map.of("mount", "bare", "address", "a", "tick", 1, "value", "v"));

    assertThat(read.success()).isFalse();
    assertThat(read.code()).isEqualTo(DocException.RESOURCE_DENIED);
    assertThat(append.code()).isEqualTo(DocException.RESOURCE_DENIED);
    assertThat(childrenOf(bareDir)).as("被拒的调用不得在盘上留下任何东西").isEmpty();
  }

  /**
   * §5.6/§5.9：{@code doc_list} 的条数按可见性统计，输出只含虚拟路径（A7）。
   *
   * <p>观看者身份就是挂载索引键（{@link io.mosire.workspace.WorkspaceCaller} 一个键扮两个角色），因此要让"另一个观看者"
   * 真的能解析到这个挂载，就给它一条自己的挂载（同一物理目录、同一虚拟名，两个使用者切片）。
   */
  @Test
  void listCountsOnlyVisibleEntriesAndHidesPhysicalPaths() throws IOException {
    // 调用者是 main、使用者是 stranger 的挂载按持有者身份单独登记一份；
    // "无主文档对无标签者不可见"（§5.4）因此有一条只对 owner 可见的、一条谁都不见（除 GM）的。
    CapabilityCatalog catalog = DocsFixture.catalog();
    AgentPermissionSet mainPermissions = DocsFixture.permissions(tempDir);
    WorkspaceCaller main = new WorkspaceCaller("main", mainPermissions);
    registry =
        DocsFixture.registryWith(
            catalog,
            Map.of("main", "main", "stranger", "main/stranger"),
            DocsFixture.mount("logs", "main", "stranger", logDir, Kind.LOG),
            main);
    registry.mount(DocsFixture.mount("logs", "main", "main", logDir, Kind.LOG), main);
    registry.mount(DocsFixture.mount("files", "main", "main", fileDir, Kind.FILE), main);

    execute(
        DocsTools.DOC_APPEND,
        Map.of("mount", "logs", "address", "a", "tick", 1, "value", "v1", "owner", "main"));
    execute(
        DocsTools.DOC_APPEND,
        Map.of(
            "mount",
            "logs",
            "address",
            "a",
            "tick",
            2,
            "value",
            "v2",
            "tags",
            List.of("stranger")));
    execute(
        DocsTools.DOC_APPEND,
        Map.of("mount", "logs", "address", "a", "tick", 3, "value", "orphan"));

    AgentTool list = tool(DocsTools.DOC_LIST);
    ToolResult gm =
        list.execute(
            DocsFixture.context(
                AccessToken.SYSTEM,
                "main",
                AgentPermissionSet.system().withResourceScopes(mainPermissions.resourceScopes()),
                list,
                Map.of("mount", "logs")));
    assertThat(gm.success()).as(gm.message()).isTrue();
    assertThat(gm.message())
        .as("GM 看见全部三条")
        .contains("a（可见条目 3 条）")
        .doesNotContain(tempDir.toAbsolutePath().toString());

    ToolResult mainView = execute(DocsTools.DOC_LIST, Map.of("mount", "logs"));
    assertThat(mainView.message())
        .as("main 只看见 owner=main 的那一条；数出不可见条目就是泄露存在性")
        .contains("a（可见条目 1 条）");

    ToolResult strangerView =
        list.execute(
            DocsFixture.context("stranger", mainPermissions, list, Map.of("mount", "logs")));
    assertThat(strangerView.message()).as("stranger 只看见 tags 命中它的那一条").contains("a（可见条目 1 条）");

    execute(DocsTools.DOC_CREATE, Map.of("mount", "files/notes.md", "text", "x"));
    ToolResult files = execute(DocsTools.DOC_LIST, Map.of("mount", "files"));
    assertThat(files.message()).contains("files/notes.md").doesNotContain(fileDir.toString());
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  private ToolResult execute(String toolName, Map<String, Object> arguments) {
    AgentTool tool = tool(toolName);
    return tool.execute(DocsFixture.context("main", permissions, tool, arguments));
  }

  private AgentTool tool(String name) {
    return DocsFixture.tool(registry, name);
  }

  /** 目录下的文件名（升序；用 try-with-resources 关掉目录流）。 */
  private static List<String> childrenOf(Path directory) throws IOException {
    try (java.util.stream.Stream<Path> children = Files.list(directory)) {
      return children.map(path -> path.getFileName().toString()).sorted().toList();
    }
  }
}
