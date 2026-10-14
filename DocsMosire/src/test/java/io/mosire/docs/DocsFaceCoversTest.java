package io.mosire.docs;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.workspace.CapabilityCatalog;
import io.mosire.workspace.Kind;
import io.mosire.workspace.WorkspaceCaller;
import io.mosire.workspace.WorkspaceRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 判定判据<b>只有一份</b>：{@link DocsEngine#faceCovers} 与引擎准入结论的一致性（设计 §5.11 "R8 实现事实"段 + §5.6/§5.9）， 全离线。
 *
 * <p><b>两段式为什么必须两段都判</b>：{@code faceCovers(permissions, namespace, 落点, 挂载根)} 先看"这个具体落点在不在可达面内"
 * （{@link ResourceScope#allows}），再看"前缀集合整体盖不盖得住挂载根"（{@link ResourceScope#covers}）。把两个参数合成一个
 * （都用落点）会在"<b>半个挂载</b>"（面 = 根/sub、根 = 根）上<b>放宽准入</b>——本类的 {@link
 * #halfMountIsRejectedEvenWhenTheOtherFaceCoversIt()} 就是那条判据。
 *
 * <p><b>为什么每条 engine 层用例都先断言 {@code registry.resolve} 已放行</b>：只有前置解析点放行了，红/绿才落在 {@code faceCovers}
 * 上；否则用例可能红在注册表的准入（M1/M3）上，看着像在守引擎判据，其实换掉引擎也不变红（幻影判别力）。
 */
class DocsFaceCoversTest {

  private static final String MAIN = "main";

  @TempDir Path tempDir;

  /** 挂载根（{@code files}）。 */
  private Path mountRoot;

  /** 挂载根下的子目录（"半个挂载"里的那个 {@code sub}）。 */
  private Path subRoot;

  /** 与本挂载无关的另一个目录（"面在别处"的负向样本）。 */
  private Path elsewhere;

  private WorkspaceRegistry registry;
  private AgentTool readTool;
  private AgentTool createTool;

  @BeforeEach
  void setUp() throws IOException {
    mountRoot = Files.createDirectories(tempDir.resolve("files"));
    subRoot = Files.createDirectories(mountRoot.resolve("sub"));
    elsewhere = Files.createDirectories(tempDir.resolve("elsewhere"));
    CapabilityCatalog catalog = DocsFixture.catalog();
    WorkspaceCaller mounting = new WorkspaceCaller(MAIN, DocsFixture.permissions(tempDir));
    registry = DocsFixture.registry(catalog);
    registry.mount(DocsFixture.mount("files", MAIN, MAIN, mountRoot, Kind.FILE), mounting);
    readTool = DocsFixture.tool(registry, DocsTools.DOC_READ);
    createTool = DocsFixture.tool(registry, DocsTools.DOC_CREATE);
  }

  /** §5.6/§5.11 正向：整段覆盖（数据根 / 恰好挂载根）⇒ {@code faceCovers} 真，引擎放行到存储层（文件不存在 ⇒ DOC_NOT_FOUND）。 */
  @Test
  void faceCoversMatchesEngineAdmissionOnCoveredLandings() {
    assertReadFace("面=数据根（整段覆盖）", faceOf(scope(tempDir), scope(tempDir)), true);
    assertReadFace("面=恰好挂载根", faceOf(scope(mountRoot), scope(mountRoot)), true);
  }

  /**
   * §5.9 fail-closed 负向：未表态 / 显式 {@code none()} / 面在别处 ⇒ {@code faceCovers} 假，引擎给 {@code
   * RESOURCE_DENIED}（不是"面内不存在"）。
   */
  @Test
  void faceCoversMatchesEngineAdmissionOnDeniedLandings() {
    assertReadFace("面未表态", emptyFace(), false);
    assertReadFace("面显式 none()", faceOf(ResourceScope.none(), ResourceScope.none()), false);
    assertReadFace("面在别的目录上", faceOf(scope(elsewhere), scope(elsewhere)), false);
  }

  /**
   * ★ 设计 §5.11 "R8 实现事实"里的那条：<b>半个挂载</b>——面 = 根/sub、根 = 根 ⇒ 拒。
   *
   * <p>读面只盖住 {@code 根/sub}、写面盖住整根：唯一解析点按"任一面覆盖挂载根"放行（M1/M3 那一层过了）， 于是这次拒绝<b>只能</b>来自 {@code
   * faceCovers} 的第二段。写面盖住整根、读面只盖半个时同理。
   */
  @Test
  void halfMountIsRejectedEvenWhenTheOtherFaceCoversIt() {
    AgentPermissionSet writeCoversRoot = faceOf(scope(subRoot), scope(mountRoot));
    assertReadFace("半个挂载：docs=根/sub、docs-edit=根", writeCoversRoot, false);
    assertThat(registry.resolve("files/sub/x.md", new WorkspaceCaller(MAIN, writeCoversRoot)))
        .as("前置：唯一解析点已放行（所以这条红在 faceCovers 的第二段上，不是红在注册表准入上）")
        .isEqualTo(subRoot.resolve("x.md"));

    AgentPermissionSet readCoversRoot = faceOf(scope(mountRoot), scope(subRoot));
    assertCreateFace("半个挂载：docs=根、docs-edit=根/sub", readCoversRoot, "files/sub/w-half.md", false);
    assertThat(registry.resolve("files/sub/w-half.md", new WorkspaceCaller(MAIN, readCoversRoot)))
        .as("前置：唯一解析点已放行（M1/M3 看的是挂载根，任一面盖住即可）")
        .isEqualTo(subRoot.resolve("w-half.md"));
    assertThat(Files.notExists(subRoot.resolve("w-half.md"))).as("被拒的创建不得在盘上留下半个文件").isTrue();
  }

  /**
   * §5.6 两个面是<b>两个命名空间</b>：同一对 (落点, 挂载根) 上，读面与写面的结论可以相反——读被拒而写放行，以及反过来。
   *
   * <p>这条钉住"读走 docs、写走 docs-edit"没有串号（串号会让"只读参考文档"这种装配失去意义）。
   */
  @Test
  void writeFaceIsJudgedSeparatelyFromReadFace() {
    // 读面只盖半个、写面盖整根 ⇒ 读拒、写放行
    AgentPermissionSet readNarrow = faceOf(scope(subRoot), scope(mountRoot));
    assertReadFace("读面窄 / 写面宽：读", readNarrow, false);
    assertCreateFace("读面窄 / 写面宽：写", readNarrow, "files/sub/w-write-ok.md", true);

    // 读面盖整根、写面只盖半个 ⇒ 读放行、写拒
    AgentPermissionSet writeNarrow = faceOf(scope(tempDir), scope(subRoot));
    assertReadFace("读面宽 / 写面窄：读", writeNarrow, true);
    assertCreateFace("读面宽 / 写面窄：写", writeNarrow, "files/sub/w-write-denied.md", false);
    assertThat(
            registry.resolve("files/sub/w-write-denied.md", new WorkspaceCaller(MAIN, writeNarrow)))
        .as("前置：解析点已放行（写被拒是引擎按 docs-edit 判的，不是解析点拒的）")
        .isEqualTo(subRoot.resolve("w-write-denied.md"));
    assertThat(Files.notExists(subRoot.resolve("w-write-denied.md")))
        .as("被拒的创建不得在盘上留下半个文件")
        .isTrue();
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /**
   * 读面一致性：{@code faceCovers(docs, 落点, 挂载根)} 的结论必须与引擎实际准入结论一致—— {@code true} ⇒ 放行到存储层（文件不存在 ⇒ {@code
   * DOC_NOT_FOUND}）；{@code false} ⇒ {@code RESOURCE_DENIED}。
   */
  private void assertReadFace(String what, AgentPermissionSet face, boolean expectedAdmitted) {
    Path physical = subRoot.resolve("x.md");
    boolean faceCovers = DocsEngine.faceCovers(face, DocNamespaces.DOCS, physical, mountRoot);
    ToolResult result =
        readTool.execute(
            DocsFixture.context(MAIN, face, readTool, Map.of("mount", "files/sub/x.md")));

    assertThat(faceCovers)
        .as("%s：faceCovers(过 docs 面, 落点=%s, 挂载根=%s)", what, physical, mountRoot)
        .isEqualTo(expectedAdmitted);
    assertThat(result.code())
        .as("%s：引擎准入结论必须与 faceCovers 同结论（faceCovers=%s）", what, faceCovers)
        .isEqualTo(expectedAdmitted ? DocException.DOC_NOT_FOUND : DocException.RESOURCE_DENIED);
  }

  /**
   * 写面一致性：同上，但看 {@code docs-edit} 面与 {@code doc_create}（写工具）。
   *
   * <p>放行时用<b>独立文件名</b>断言真的建出来了——只断"没报 RESOURCE_DENIED"会把"别的失败"也算成放行。
   */
  private void assertCreateFace(
      String what, AgentPermissionSet face, String virtualPath, boolean expectedAdmitted) {
    Path physical = mountRoot.resolve(virtualPath.substring("files/".length()));
    boolean faceCovers = DocsEngine.faceCovers(face, DocNamespaces.DOCS_EDIT, physical, mountRoot);
    ToolResult result =
        createTool.execute(
            DocsFixture.context(MAIN, face, createTool, Map.of("mount", virtualPath, "text", "x")));

    assertThat(faceCovers)
        .as("%s：faceCovers(过 docs-edit 面, 落点=%s, 挂载根=%s)", what, physical, mountRoot)
        .isEqualTo(expectedAdmitted);
    if (expectedAdmitted) {
      assertThat(result.success()).as("%s：放行就该真的建成: %s", what, result.message()).isTrue();
      assertThat(Files.exists(physical)).as("%s：盘上确有这个文件", what).isTrue();
    } else {
      assertThat(result.code())
          .as("%s：引擎给 RESOURCE_DENIED（不是「面内不存在」）", what)
          .isEqualTo(DocException.RESOURCE_DENIED);
    }
  }

  /** 面：只表态给定的命名空间（{@code null} = 不表态）。 */
  private static AgentPermissionSet faceOf(ResourceScope docs, ResourceScope docsEdit) {
    Map<String, ResourceScope> scopes = new LinkedHashMap<>();
    if (docs != null) {
      scopes.put(DocNamespaces.DOCS, docs);
    }
    if (docsEdit != null) {
      scopes.put(DocNamespaces.DOCS_EDIT, docsEdit);
    }
    return AgentPermissionSet.builder(AccessToken.DEFAULT)
        .allowAll()
        .resourceScopes(new ResourceScopeMap(scopes))
        .build();
  }

  /** 面空（未表态）：一个资源命名空间都不表态。 */
  private static AgentPermissionSet emptyFace() {
    return AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build();
  }

  private static ResourceScope scope(Path dir) {
    return ResourceScope.ofDir(dir);
  }
}
