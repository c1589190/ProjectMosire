package io.mosire.docs;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.workspace.CapabilityCatalog;
import io.mosire.workspace.Kind;
import io.mosire.workspace.WorkspaceCaller;
import io.mosire.workspace.WorkspaceException;
import io.mosire.workspace.WorkspaceRegistry;
import io.mosire.workspace.WorkspaceTools;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code doc_guide}（能力自述工具）的判据表（<b>设计 §5.11</b>，R8b 责任区），全离线。
 *
 * <p><b>逐条对号</b>（§5.11 判据段 + "不漂移"段）：
 *
 * <ul>
 *   <li>正 ① + ③：{@link #guideWorksForEmptyFaceAndZeroMounts()}（面空/零挂载也调得到，"先让上级挂一块"是回答不是错误）；
 *   <li>正 ②：{@link #guideNamesRealMountsWithoutPriorKnowledge()}（输出里有真挂载名与真路径示例；可读/可写如实按调用者面算）；
 *   <li>正 ④（不漂移）：{@link #guideListingEqualsFamilyAndNames()}（<b>三重全等</b>：清单 == 家族工具对象 == {@link
 *       DocsTools#NAMES}，且每行取自工具自己的 {@code name()}/{@code description()}/schema）；
 *   <li>负 ①：{@link #guideShowsOnlyCallersOwnMounts()}（换身份只看得到自己的挂载）；
 *   <li>负 ②：{@link #guideNeverEmitsPhysicalPaths()}（物理路径不出现 + 同一判定式的正样本自证）；
 *   <li>负 ③（结构性）：{@link #guideBodyDoesNotCallResourceSpi()}（body 里没有 {@code
 *       resources().require(...)}， 配 {@code mount_workspace} 的正样本自证）；
 *   <li>R8 实现事实"判定判据只有一份"：{@link #guideCarriesNoSecondAdmissionJudgment()}（结构断言 + 正样本自证）。
 * </ul>
 */
class DocGuideTest {

  private static final String MAIN = "main";
  private static final String OTHER = "other";

  /** 被测源码（{@code surefire} 的工作目录就是模块根；仓库根再跑时补一层模块名）。 */
  private static final String DOC_GUIDE_SOURCE = "src/main/java/io/mosire/docs/DocGuide.java";

  private static final String DOCS_ENGINE_SOURCE = "src/main/java/io/mosire/docs/DocsEngine.java";

  private static final String DOCS_TOOLS_SOURCE = "src/main/java/io/mosire/docs/DocsTools.java";

  /** 跨模块的正样本来源：{@code mount_workspace} 的 body 里确实调了资源 SPI。 */
  private static final String WORKSPACE_TOOLS_SOURCE =
      "src/main/java/io/mosire/workspace/WorkspaceTools.java";

  @TempDir Path tempDir;

  private Path logDir;
  private Path fileDir;
  private AgentPermissionSet fullFace;
  private WorkspaceRegistry registry;

  @BeforeEach
  void setUp() throws IOException {
    logDir = Files.createDirectories(tempDir.resolve("logs"));
    fileDir = Files.createDirectories(tempDir.resolve("files"));
    CapabilityCatalog catalog = DocsFixture.catalog();
    fullFace = DocsFixture.permissions(tempDir);
    WorkspaceCaller main = new WorkspaceCaller(MAIN, fullFace);
    registry = DocsFixture.registry(catalog);
    registry.mount(DocsFixture.mount("logs", MAIN, MAIN, logDir, Kind.LOG), main);
    registry.mount(DocsFixture.mount("files", MAIN, MAIN, fileDir, Kind.FILE), main);
  }

  /**
   * §5.11 "不漂移"（<b>本设计的硬性质</b>）：{@code doc_guide} 列出的工具名集合 == 家族工具对象的名字集合 == {@link
   * DocsTools#NAMES}——<b>三重全等</b>，含 {@code doc_guide} 自己（所以不需要任何减法）。
   *
   * <p>每行还必须取自工具对象自己：名字 + {@code description()} + schema 必需参数都是现读，不是抄一段可能过期的文案。
   */
  @Test
  void guideListingEqualsFamilyAndNames() {
    List<AgentTool> family = DocsTools.all(registry);

    ToolResult result = guide(Map.of(), MAIN, fullFace);

    assertThat(result.success()).as(result.message()).isTrue();
    List<String> listed = listedToolNames(result.message());
    assertThat(listed)
        .as("清单 == 家族工具对象的名字集合")
        .containsExactlyInAnyOrderElementsOf(family.stream().map(AgentTool::name).toList())
        .as("清单 == DocsTools.NAMES（第三重全等）")
        .containsExactlyInAnyOrderElementsOf(DocsTools.NAMES)
        .as("§5.11 的形状：七个文档工具 + 自述工具自己")
        .hasSize(8)
        .contains(DocsTools.DOC_GUIDE);
    assertThat(result.message()).as("条数也从家族现读（不是写死的「7 个」）").contains("【工具】" + family.size() + " 个");
    for (AgentTool tool : family) {
      assertThat(result.message())
          .as("清单每行现读工具对象自己的 name()/description(): %s", tool.name())
          .contains("- " + tool.name() + "：" + flat(tool.description()));
    }
    assertThat(result.message())
        .as("必需参数也是现读 schema（§5.11 的 required 表在清单里如实呈现）")
        .contains("（schema 必需参数：无）")
        .contains("（schema 必需参数：mount）")
        .contains("（schema 必需参数：mount, limit）");
  }

  /**
   * §5.11 正向 ① + ③：调用者<b>面空</b>且<b>零挂载</b>——最需要这条自救渠道的人。经唯一入口（资源前置闸）调， 必须成功，并给出"你名下还没有挂载 + 先让上级挂一块
   * + 挂载名清单问 workspace_info"的清晰回答。
   */
  @Test
  void guideWorksForEmptyFaceAndZeroMounts() {
    AgentTool guideTool = DocsFixture.tool(registry, DocsTools.DOC_GUIDE);
    AgentPermissionSet emptyFace = emptyFace();
    ToolRegistry tools = new ToolRegistry();
    tools.register(guideTool);

    ToolResult result =
        ToolCallAuthorizer.standard()
            .execute(
                tools,
                DocsTools.DOC_GUIDE,
                DocsFixture.context("newbie", emptyFace, guideTool, Map.of()));

    assertThat(result.success())
        .as("空声明面 + 零挂载也必须调得到（不是错误）: code=%s message=%s", result.code(), result.message())
        .isTrue();
    assertThat(result.message())
        .as("零挂载要说清「你没有地、找谁开、下一步问谁」")
        .contains("【在你的挂载上】0 个")
        .contains("上级")
        .contains(WorkspaceTools.WORKSPACE_INFO)
        .contains(DocException.RESOURCE_DENIED);
  }

  /**
   * §5.11 正向 ②：输出里有<b>真挂载名</b>与用真名字示范的路径形态——模型不需要事先知道任何名字。
   *
   * <p>同时钉住"可读/可写是<b>按调用者自己的面</b>算的"：同一批挂载，面空 ⇒ 两个都 ❌（并说明原因）；完整面 ⇒ 两个都 ✅。 只断一半会让"可读/可写"退化成恒定的装饰文字。
   */
  @Test
  void guideNamesRealMountsWithoutPriorKnowledge() {
    ToolResult emptyFaceView = guide(Map.of(), MAIN, emptyFace());

    assertThat(emptyFaceView.success()).as(emptyFaceView.message()).isTrue();
    assertThat(emptyFaceView.message())
        .as("真挂载名 + 真名字示范的路径形态")
        .contains("- logs：可读")
        .contains("- files：可读")
        .contains("files/notes/hello.md")
        .contains("【路径形态】");
    assertThat(emptyFaceView.message())
        .as("面空 ⇒ 如实报「都不可读/不可写」，并说明是未表态")
        .contains("可读 ❌ 可写 ❌")
        .contains("未表态 = 什么都看不见、也改不了");

    ToolResult fullFaceView = guide(Map.of(), MAIN, fullFace);

    assertThat(fullFaceView.message())
        .as("完整面 ⇒ 两个挂载都可读可写（判定来自 DocsEngine.faceCovers，不是常量）")
        .contains("- files：可读 ✅ 可写 ✅")
        .contains("- logs：可读 ✅ 可写 ✅");
  }

  /** §5.11 负向 ①：<b>换身份 ⇒ 只看到自己的挂载</b>（两个方向都断，避免"恰好没打印"）。 */
  @Test
  void guideShowsOnlyCallersOwnMounts() throws IOException {
    Path otherDir = Files.createDirectories(tempDir.resolve("others"));
    registry.mount(
        DocsFixture.mount("bobs-docs", OTHER, OTHER, otherDir, Kind.LOG),
        new WorkspaceCaller(OTHER, fullFace));

    ToolResult asMain = guide(Map.of(), MAIN, fullFace);
    ToolResult asOther = guide(Map.of(), OTHER, fullFace);

    assertThat(asMain.success()).as(asMain.message()).isTrue();
    assertThat(asMain.message())
        .as("main 只该看到自己的两个挂载")
        .contains("- logs：")
        .contains("- files：")
        .doesNotContain("bobs-docs");
    assertThat(asOther.success()).as(asOther.message()).isTrue();
    assertThat(asOther.message())
        .as("other 只该看到 bobs-docs")
        .contains("- bobs-docs：")
        .doesNotContain("- logs：")
        .doesNotContain("- files：");
  }

  /**
   * §5.11 负向 ②：输出里<b>不含任何物理路径</b>（A7/G4）。
   *
   * <p><b>正样本自证</b>：同一条判定式在"带物理落点的文本"上必须命中——否则"不命中"可能只是判定式写错了。
   */
  @Test
  void guideNeverEmitsPhysicalPaths() {
    String text = guide(Map.of(), MAIN, fullFace).message();

    assertThat(text).as("对照前置：输出确实在讲这几个挂载").contains("- files：");
    assertThat(mentionsPhysicalPath(text, fileDir)).as("不得出现挂载物理落点: %s", fileDir).isFalse();
    assertThat(mentionsPhysicalPath(text, logDir)).as("不得出现挂载物理落点: %s", logDir).isFalse();
    assertThat(mentionsPhysicalPath(text, tempDir)).as("连数据的物理根也不得出现").isFalse();

    assertThat(mentionsPhysicalPath("落点=" + fileDir, fileDir))
        .as("同一 pattern 在带物理落点的文本上必须命中——否则上面的「不命中」没有判别力")
        .isTrue();
    assertThat(mentionsPhysicalPath("根=" + tempDir.resolve("files"), tempDir)).isTrue();
  }

  /**
   * R8 实现事实"<b>判定判据只有一份</b>"（结构断言）：{@code DocGuide} 只做参数绑定，自己<b>不</b>写 {@code allows}/{@code
   * covers}/{@code declared}/命名空间判定；它调的是 {@link DocsEngine#faceCovers} 那一份唯一实现。
   *
   * <p><b>正样本自证</b>：同一批 token 在唯一判据的归属地 {@link DocsEngine} 上必须命中—— 这条自证同时说明"0 命中"不是工具写错。
   */
  @Test
  void guideCarriesNoSecondAdmissionJudgment() throws IOException {
    String guideSource = source(DOC_GUIDE_SOURCE, repoPath(DOC_GUIDE_SOURCE));
    String engineSource = source(DOCS_ENGINE_SOURCE, repoPath(DOCS_ENGINE_SOURCE));

    for (String token :
        List.of("WorkspacePermissions", "ResourceScope", ".allows(", ".covers(", ".declared(")) {
      assertThat(guideSource)
          .as("DocGuide 不得自带判定写法（唯一判据在 DocsEngine.faceCovers）: %s", token)
          .doesNotContain(token);
      assertThat(engineSource).as("正样本自证：同一 token 在唯一判据的归属地会命中: %s", token).contains(token);
    }
    assertThat(guideSource).as("可读/可写一律调引擎那一份实现（不是复制一份到自己家里）").contains("DocsEngine.faceCovers(");
  }

  /**
   * §5.11 负向 ③（结构性）：自述工具的 body 里<b>没有</b> {@code resources().require(...)}——不声明面却去调它，
   * 等于期望一个必然拒绝的判定者放行。
   *
   * <p>{@code doc_guide} 的 body 在 {@link DocsTools} 的 {@code guide(...)} 里，所以<b>按方法正文取</b>（整文件里还有工具
   * 骨架自己的 {@code resources()} 访问器，整文件 grep 会误报）；渲染层 {@link DocGuide} 另按整文件断。
   *
   * <p><b>正样本自证</b>：同一对 token 在 {@code WorkspaceTools.mount_workspace} 的 body 上必须命中 （{@code
   * context.resources().require(} 在源码里分两行写，故按两个 token 分别断）。
   */
  @Test
  void guideBodyDoesNotCallResourceSpi() throws IOException {
    String guideBody =
        methodBody(
            source(DOCS_TOOLS_SOURCE, repoPath(DOCS_TOOLS_SOURCE)), "static AgentTool guide(");

    assertThat(guideBody)
        .as("doc_guide 的 body 不得出现资源 SPI（NONE 的工具本来也用不了该 SPI）")
        .doesNotContain("resources()")
        .doesNotContain(".require(");
    assertThat(source(DOC_GUIDE_SOURCE, repoPath(DOC_GUIDE_SOURCE)))
        .as("渲染层也不碰资源 SPI")
        .doesNotContain("resources()")
        .doesNotContain(".require(");

    String mountBody =
        methodBody(
            source(WORKSPACE_TOOLS_SOURCE, repoPath(WORKSPACE_TOOLS_SOURCE), siblingRepoPath()),
            "static AgentTool mount(");
    assertThat(mountBody)
        .as("正样本自证：同一对 token 在 mount_workspace 的 body 上会命中")
        .contains("resources()")
        .contains(".require(");
  }

  /**
   * §5.11 的<b>发现渠道</b>：nudge 必须写在工具描述里（"不知道有哪些挂载 / 不确定 {@code doc_*} 怎么用 / 刚被 {@code
   * WORKSPACE_NOT_FOUND}·{@code DOC_NOT_FOUND} 拒了 ⇒ 先调我"）——描述是模型发现一个工具的唯一入口，
   * 隔壁因此零改动。三个名字全引生产常量，不手抄字面量。
   */
  @Test
  void guideDescriptionCarriesTheNudge() {
    String description = DocsFixture.tool(registry, DocsTools.DOC_GUIDE).description();

    assertThat(description)
        .as("两个错误码 + 指回 workspace_info 的名字（全部引生产常量）")
        .contains(WorkspaceException.WORKSPACE_NOT_FOUND)
        .contains(DocException.DOC_NOT_FOUND)
        .contains(WorkspaceTools.WORKSPACE_INFO);
    assertThat(description).as("并承诺「一个挂载都没有的人调它也会得到清楚回答（不是错误）」").contains("不是错误");
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /** 面空（未表态）：{@code allowAll} + 一个资源命名空间都不表态。 */
  private static AgentPermissionSet emptyFace() {
    return AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build();
  }

  /** 直接调 {@code doc_guide}（工具内不读盘、不判准入，直接 execute 与生产同语义）。 */
  private ToolResult guide(
      Map<String, Object> arguments, String instanceId, AgentPermissionSet perms) {
    AgentTool tool = DocsFixture.tool(registry, DocsTools.DOC_GUIDE);
    return tool.execute(DocsFixture.context(instanceId, perms, tool, arguments));
  }

  private static String flat(String description) {
    return description == null ? "" : description.replace('\n', ' ').replace('\r', ' ').strip();
  }

  /** 从【工具】段解析清单里的名字（每行形如 {@code - 名字：说明}）。 */
  private static List<String> listedToolNames(String message) {
    int from = message.indexOf("【工具】");
    int to = message.indexOf("【两种纪律】");
    assertThat(from).as("输出里必须有【工具】段").isNotNegative();
    assertThat(to).as("输出里必须有【两种纪律】段（工具段的结束标记）").isGreaterThan(from);
    List<String> names = new ArrayList<>();
    for (String line : message.substring(from, to).split("\n")) {
      if (!line.startsWith("- ")) {
        continue;
      }
      int colon = line.indexOf('：');
      assertThat(colon).as("清单每行形如「- 名字：说明」: %s", line).isGreaterThan(2);
      names.add(line.substring(2, colon));
    }
    return names;
  }

  /** "这段文本提到这个物理目录了吗"——唯一判定式，正负两向都走它。 */
  private static boolean mentionsPhysicalPath(String text, Path dir) {
    return text.contains(dir.toAbsolutePath().normalize().toString());
  }

  /**
   * 读被测源文件：按候选路径依次找（{@code surefire} 的工作目录是模块根；从仓库根手跑时是仓库根；跨模块的正样本要往上走一层）， 一个都找不到就红——不许静默跳过结构断言。
   */
  private static String source(String... candidates) throws IOException {
    for (String candidate : candidates) {
      Path path = Path.of(candidate);
      if (Files.isRegularFile(path)) {
        return Files.readString(path, StandardCharsets.UTF_8);
      }
    }
    throw new AssertionError("被测源文件一个都没找到（路径写错就是用例失效，不是跳过）: " + List.of(candidates));
  }

  /** 仓库根相对路径（工作目录 = 模块根时用不到，工作目录 = 仓库根时用它）。 */
  private static String repoPath(String moduleRelative) {
    return "DocsMosire/" + moduleRelative;
  }

  /** 兄弟模块相对路径（工作目录 = 模块根时跨模块取 {@code WorkspaceMosire} 的源码）。 */
  private static String siblingRepoPath() {
    return "../WorkspaceMosire/" + WORKSPACE_TOOLS_SOURCE;
  }

  /**
   * 取一个方法的正文（签名 → 顶格两空格的右花括号）。{@code docs-mosire} 的源码在本机是 CRLF （{@code
   * core.autocrlf=true}），先归一换行再匹配。
   */
  private static String methodBody(String src, String signature) {
    String normalized = src.replace("\r\n", "\n");
    int at = normalized.indexOf(signature);
    assertThat(at).as("源码里找不到这个签名（写法变了就该改这条用例）: %s", signature).isNotNegative();
    int end = normalized.indexOf("\n  }\n", at);
    assertThat(end).as("找不到方法正文的结束行: %s", signature).isNotNegative();
    return normalized.substring(at, end);
  }
}
