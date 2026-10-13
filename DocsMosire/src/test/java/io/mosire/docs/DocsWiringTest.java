package io.mosire.docs;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.tool.AgentTool;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 装配冒烟（判据 §4.11 正向"带 {@code docs} 能力的挂载授予后，子体能在自己的 {@code /docs} 里 {@code doc_append} 并读回"、§4.11
 * 正向"{@code grant} 后子体权限集里确有该前缀"）。
 *
 * <p><b>它同时是夹具的自证</b>：{@link DocsFixture} 的装配走到"能用"这一档，后续用例的装配红点才有判别力。
 */
class DocsWiringTest {

  @TempDir Path tempDir;

  /** §4.11 正向：装配期挂载 → grant → 子体（自己的挂载切片）能 append 并读回。 */
  @Test
  void grantedMountLetsCallerAppendAndReadBack() throws IOException {
    Path dir = Files.createDirectories(tempDir.resolve("workspaces").resolve("child"));
    CapabilityCatalog catalog = DocsFixture.catalog();
    WorkspaceCaller parent = DocsFixture.caller("main", dir);
    WorkspaceRegistry registry =
        DocsFixture.registryWith(
            catalog,
            Map.of("main", "main", "child", "main/child"),
            DocsFixture.mount("docs", "main", "child", dir, Kind.LOG),
            parent);

    // ── grant：把挂载的物理前缀并进子体的面（能力落格，§4.8） ──
    AgentPermissionSet childFace = DocsFixture.permissions(dir);
    AgentPermissionSet landed = registry.grant("child", childFace);
    assertThat(
            landed
                .resourceScopes()
                .declaredScope(DocNamespaces.DOCS)
                .covers(ResourceScope.ofDir(dir)))
        .as("grant 后子体权限集里确有该前缀")
        .isTrue();
    assertThat(
            landed
                .resourceScopes()
                .declaredScope(DocNamespaces.DOCS_EDIT)
                .covers(ResourceScope.ofDir(dir)))
        .isTrue();

    // ── 子体在自己的 /docs 里 append 并读回 ──
    WorkspaceCaller child = new WorkspaceCaller("child", landed);
    DocsEngine engine = new DocsEngine(registry);
    DocEntry appended = engine.append("docs/notes", child, AppendRequest.of("notes", 7, "k", "v1"));
    assertThat(appended.ordinal()).isZero();
    assertThat(appended.id().toString()).isEqualTo("notes#0");

    List<DocEntry> read = engine.read("docs/notes", child, DocViewer.seesAll("child"), "notes");
    assertThat(read).extracting(DocEntry::value).containsExactly("v1");
  }

  /** §4.11 正向：装配期挂载后 {@code resolve} 能取到物理落点、{@code list} 投影出虚拟条目。 */
  @Test
  void resolveAndListWorkOnAssemblyMount() throws IOException {
    Path dir = Files.createDirectories(tempDir.resolve("docs-root"));
    Files.createDirectories(dir.resolve("a"));
    CapabilityCatalog catalog = DocsFixture.catalog();
    WorkspaceCaller caller = DocsFixture.caller("main", dir);
    WorkspaceRegistry registry =
        DocsFixture.registryWith(
            catalog, DocsFixture.mount("docs", "main", "main", dir, Kind.FILE), caller);

    assertThat(registry.resolve("docs/a", caller)).isEqualTo(dir.resolve("a"));
    assertThat(registry.list("docs", caller)).containsExactly("docs/a");
  }

  /** 工具面自证：七个 {@code doc_*} 工具都能被造出来、资源面与破坏性标记与设计 §5.6 一致。 */
  @Test
  void docsToolsExposeDesignSurface() throws IOException {
    Path dir = Files.createDirectories(tempDir.resolve("docs-root"));
    CapabilityCatalog catalog = DocsFixture.catalog();
    WorkspaceCaller caller = DocsFixture.caller("main", dir);
    WorkspaceRegistry registry =
        DocsFixture.registryWith(
            catalog, DocsFixture.mount("docs", "main", "main", dir, Kind.LOG), caller);

    List<AgentTool> tools = DocsTools.all(registry);
    assertThat(tools)
        .extracting(AgentTool::name)
        .containsExactlyInAnyOrderElementsOf(DocsTools.NAMES);
    for (AgentTool tool : tools) {
      boolean write =
          tool.name().equals(DocsTools.DOC_APPEND)
              || tool.name().equals(DocsTools.DOC_VOID)
              || tool.name().equals(DocsTools.DOC_CREATE)
              || tool.name().equals(DocsTools.DOC_EDIT);
      assertThat(tool.resources().namespaces())
          .as("%s 的资源面", tool.name())
          .containsExactly(write ? DocNamespaces.DOCS_EDIT : DocNamespaces.DOCS);
      assertThat(tool.spec().destructive()).as("%s 的破坏性标记", tool.name()).isEqualTo(write);
    }
  }

  /** 工具面自证：{@code doc_append} 经工具执行成功并回显虚拟地址（不泄露物理落点）。 */
  @Test
  void appendThroughToolSucceedsAndHidesPhysicalPath() throws IOException {
    Path dir = Files.createDirectories(tempDir.resolve("docs-root"));
    CapabilityCatalog catalog = DocsFixture.catalog();
    Path root = tempDir.toAbsolutePath().normalize();
    WorkspaceCaller caller = DocsFixture.caller("main", root);
    WorkspaceRegistry registry =
        DocsFixture.registryWith(
            catalog, DocsFixture.mount("docs", "main", "main", dir, Kind.LOG), caller);
    AgentTool append = DocsFixture.tool(registry, DocsTools.DOC_APPEND);

    ToolResult result =
        append.execute(
            DocsFixture.context(
                "main",
                DocsFixture.permissions(root),
                append,
                Map.of(
                    "mount", "docs",
                    "address", "world/1",
                    "tick", 3,
                    "key", "k",
                    "value", "hello")));

    assertThat(result.success()).as(result.message()).isTrue();
    assertThat(result.message()).contains("world/1#0");
    assertThat(result.message()).doesNotContain(tempDir.toAbsolutePath().toString());
  }
}
