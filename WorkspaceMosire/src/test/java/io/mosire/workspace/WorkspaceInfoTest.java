package io.mosire.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code workspace_info}（能力自述工具）的判据表（<b>设计 §5.11</b>，R8b 责任区），全离线。
 *
 * <p><b>为什么这些用例必须走唯一入口</b>：设计 §5.11 的正向第 ① 条是"<b>面空（未表态）</b>的调用者也能调成功"——那是 资源前置闸的行为，直接 {@code
 * tool.execute(...)} 会绕开它。因此放行类用例一律经 {@link ToolCallAuthorizer#standard()}， 与生产同一条路。
 *
 * <p><b>逐条对号</b>（§5.11 判据段）：
 *
 * <ul>
 *   <li>正 ① + ②：{@link #emptyFaceCallerStillSeesItsOwnMountNames()}（面空也成功；输出里有真挂载名，模型无须事先知道任何名字）；
 *   <li>正 ③：{@link #zeroMountCallerGetsClearAnswerNotAnError()}（零挂载得到"你还没有工作区 + 找谁开"的清晰回答，不是报错）；
 *   <li>负 ①：{@link #identitySwitchShowsOnlyOwnMounts()}（换身份只看到自己的挂载）；
 *   <li>负 ②：{@link #answerNeverContainsPhysicalPaths()}（物理路径不出现，且同一判定式在带物理落点的文本上会命中）；
 *   <li>负 ③ + §5.11 资源声明段：{@link #noManifestMeansPreGateStaysSilent()} 与 {@link
 *       #infoBodyDoesNotTouchResourceSpi()}（空声明面 ⇒ 前置闸不说话；body 里没有资源 SPI 调用）。
 * </ul>
 */
class WorkspaceInfoTest {

  private static final String MAIN = "main";
  private static final String OTHER = "other";

  /** 被测源码（{@code surefire} 的工作目录就是模块根；仓库根再跑时补一层模块名）。 */
  private static final String WORKSPACE_TOOLS_SOURCE =
      "src/main/java/io/mosire/workspace/WorkspaceTools.java";

  private static final String WORKSPACE_INFO_SOURCE =
      "src/main/java/io/mosire/workspace/WorkspaceInfo.java";

  @TempDir Path tempDir;

  private CapabilityCatalog catalog;
  private DefaultWorkspaceRegistry registry;
  private AgentPermissionSet fullFace;

  @BeforeEach
  void setUp() {
    catalog = WorkspaceFixture.catalog();
    registry = DefaultWorkspaceRegistry.create(catalog);
    fullFace = WorkspaceFixture.fullFaces(tempDir);
  }

  /**
   * §5.11 正向 ① + ②：调用者<b>一个面都没表态</b>（前置闸最容易误伤的那一类）， 而且它的挂载是装配期挂的 ⇒ 调 {@code workspace_info}
   * 必须成功，输出里必须直接给出<b>真挂载名</b>。
   */
  @Test
  void emptyFaceCallerStillSeesItsOwnMountNames() throws IOException {
    Path workspaceDir = Files.createDirectories(tempDir.resolve("workspace"));
    Path journalDir = Files.createDirectories(tempDir.resolve("journal"));
    mountAssembly("workspace", MAIN, workspaceDir, Kind.FILE, Set.of(WorkspaceFixture.DOCS));
    mountAssembly("journal", MAIN, journalDir, Kind.LOG, Set.of(WorkspaceFixture.DOCS));
    AgentPermissionSet emptyFace = emptyFace();

    ToolResult result = call(WorkspaceTools.WORKSPACE_INFO, emptyFace, MAIN, Map.of());

    assertThat(result.success())
        .as("面空也必须能调（不是错误）: code=%s message=%s", result.code(), result.message())
        .isTrue();
    assertThat(result.message())
        .as("模型不需要事先知道任何名字：两个真挂载名都在输出里")
        .contains("虚拟名=workspace")
        .contains("虚拟名=journal")
        .contains("纪律=FILE")
        .contains("纪律=LOG");
    assertThat(result.message())
        .as("路径示例也用真名字（同一段文案在零挂载时用 <挂载名> 占位）")
        .contains("workspace/notes/hello.md");
  }

  /**
   * §5.11 正向 ③：<b>零挂载</b>的调用者得到"你还没有工作区 + 让上级给你挂"的清晰回答——不是异常，也不是空串。
   *
   * <p>后半段是同一判据的负向面：<b>身份解不出</b>（{@link AgentIdentity#UNKNOWN}）时，即使表里有别人（{@code main}）的挂载，
   * 也<b>不许</b>拿那一片冒充"你的"——必须回答零挂载，且那些名字一个字都不出现。
   */
  @Test
  void zeroMountCallerGetsClearAnswerNotAnError() throws IOException {
    Path workspaceDir = Files.createDirectories(tempDir.resolve("workspace"));
    mountAssembly("workspace", MAIN, workspaceDir, Kind.FILE, Set.of(WorkspaceFixture.DOCS));

    ToolResult newcomer = call(WorkspaceTools.WORKSPACE_INFO, emptyFace(), "newbie", Map.of());

    assertThat(newcomer.success()).as("零挂载不是错误: %s", newcomer.message()).isTrue();
    assertThat(newcomer.message())
        .as("要说清三件事：你没有地、现在能干什么、找谁要")
        .contains("【你的挂载】0 个")
        .contains("你还没有工作区")
        .contains("上级")
        .contains("父 Agent");
    assertThat(newcomer.message())
        .as("零挂载时不许编一个名字出来（那正是「拿工具名当挂载名猜」的事故成因）")
        .contains("<挂载名>/notes/hello.md")
        .doesNotContain("workspace/notes/hello.md");

    ToolResult unknown =
        call(WorkspaceTools.WORKSPACE_INFO, emptyFace(), AgentIdentity.UNKNOWN, Map.of());

    assertThat(unknown.success()).as("身份解不出也照样是清楚回答: %s", unknown.message()).isTrue();
    assertThat(unknown.message())
        .as("解不出身份 ⇒ 按零挂载回答，不拿别人的切片冒充「你的」")
        .contains("【你的挂载】0 个")
        .doesNotContain("虚拟名=workspace");
  }

  /**
   * §5.11 负向 ①：<b>换身份 ⇒ 只看到自己的挂载</b>。
   *
   * <p>两个方向都断：{@code main} 看不到 {@code other} 的挂载，{@code other} 也看不到 {@code main} 的——
   * 单向断言可能只是"恰好没打印"，双向才是"按身份切片"。
   */
  @Test
  void identitySwitchShowsOnlyOwnMounts() throws IOException {
    Path mineDir = Files.createDirectories(tempDir.resolve("mine"));
    Path otherDir = Files.createDirectories(tempDir.resolve("others"));
    mountAssembly("mine", MAIN, mineDir, Kind.FILE, Set.of(WorkspaceFixture.DOCS));
    mountAssembly("bobs-ws", OTHER, otherDir, Kind.FILE, Set.of(WorkspaceFixture.DOCS));

    ToolResult asMain = call(WorkspaceTools.WORKSPACE_INFO, fullFace, MAIN, Map.of());
    ToolResult asOther = call(WorkspaceTools.WORKSPACE_INFO, fullFace, OTHER, Map.of());

    assertThat(asMain.success()).as(asMain.message()).isTrue();
    assertThat(asMain.message())
        .as("main 只该看到 mine")
        .contains("虚拟名=mine")
        .doesNotContain("bobs-ws");
    assertThat(asOther.success()).as(asOther.message()).isTrue();
    assertThat(asOther.message())
        .as("other 只该看到 bobs-ws")
        .contains("虚拟名=bobs-ws")
        .doesNotContain("虚拟名=mine");
  }

  /**
   * §5.11 负向 ②：输出里<b>不含任何物理路径</b>（A7/G4）。
   *
   * <p><b>正样本自证</b>：同一条判定式（{@link #mentionsPhysicalPath}）在"带物理落点的文本"上必须命中—— 否则"不命中"可能只是判定式写错了（本仓
   * §四.5 的纪律）。
   */
  @Test
  void answerNeverContainsPhysicalPaths() throws IOException {
    Path workspaceDir = Files.createDirectories(tempDir.resolve("workspace"));
    Path journalDir = Files.createDirectories(tempDir.resolve("journal"));
    mountAssembly("workspace", MAIN, workspaceDir, Kind.FILE, Set.of(WorkspaceFixture.DOCS));
    mountAssembly("journal", MAIN, journalDir, Kind.LOG, Set.of(WorkspaceFixture.DOCS));

    String text = call(WorkspaceTools.WORKSPACE_INFO, fullFace, MAIN, Map.of()).message();

    assertThat(text).as("对照前置：输出非空、且真的在讲这两个挂载").contains("虚拟名=workspace");
    assertThat(mentionsPhysicalPath(text, workspaceDir))
        .as("输出里不得出现挂载的物理落点: %s", workspaceDir)
        .isFalse();
    assertThat(mentionsPhysicalPath(text, journalDir)).as("输出里不得出现挂载的物理落点").isFalse();
    assertThat(mentionsPhysicalPath(text, tempDir)).as("连数据的物理根也不得出现").isFalse();

    // 正样本自证：同一判定式在带物理落点的文本上会命中（两处方向都证：挂载落点 / 数据根）
    assertThat(mentionsPhysicalPath("落点=" + workspaceDir, workspaceDir))
        .as("同一 pattern 在带物理落点的文本上必须命中——否则上面的「不命中」没有判别力")
        .isTrue();
    assertThat(mentionsPhysicalPath("根=" + tempDir.resolve("workspace"), tempDir)).isTrue();
  }

  /**
   * §5.11 资源声明段：{@code workspace_info} 声明 {@code ResourceManifest.NONE} ⇒ 资源前置闸对它<b>不说话</b>；
   * 三个管理工具仍声明 {@code workspaces}/DENY ⇒ 同一个空面调用者在它们那里当场被拒。
   *
   * <p>后半段是判定力的自证：前置闸不是"对谁都沉默"（否则前半段是恒真的假判据）。
   */
  @Test
  void noManifestMeansPreGateStaysSilent() {
    AgentPermissionSet emptyFace = emptyFace();
    AgentTool info = tool(registry, WorkspaceTools.WORKSPACE_INFO);
    AgentTool mount = tool(registry, WorkspaceTools.MOUNT_WORKSPACE);

    assertThat(ResourceAuthorizer.of(emptyFace, info.resources()).denial())
        .as("空声明面 ⇒ 前置闸无事可做（§5.11 的裁定就是为这一条）")
        .isEmpty();
    assertThat(ResourceAuthorizer.of(emptyFace, mount.resources()).denial())
        .as("自证：同一个空面调用者在管理工具上确实会被前置闸拒")
        .isPresent();
  }

  /**
   * §5.11 负向 ③（结构性）：{@code workspace_info} 的 body 里<b>没有</b> {@code resources()} 调用——
   * 不声明面却去调它，等于期望一个必然拒绝的判定者放行。
   *
   * <p><b>为什么按方法正文取</b>：{@code mount_workspace} 与 {@code workspace_info} 住在同一个文件里， 整文件 grep 会把前者的
   * {@code require} 算到后者头上。{@code WorkspaceInfo}（渲染层）另按整文件断。
   *
   * <p><b>正样本自证</b>：同一条判定式在 {@code mount} 的方法正文上必须命中 {@code resources().require(}。
   */
  @Test
  void infoBodyDoesNotTouchResourceSpi() throws IOException {
    String tools = source(WORKSPACE_TOOLS_SOURCE);

    String infoBody = methodBody(tools, "static AgentTool info(");
    String mountBody = methodBody(tools, "static AgentTool mount(");

    assertThat(infoBody)
        .as("workspace_info 的 body 不得出现资源 SPI")
        .doesNotContain("resources()")
        .doesNotContain(".require(");
    assertThat(mountBody)
        .as("正样本自证：同一 pattern 在 mount_workspace 上会命中")
        .contains("resources()")
        .contains(".require(");
    assertThat(source(WORKSPACE_INFO_SOURCE))
        .as("渲染层也只读挂载表：不碰资源 SPI、不碰物理落点")
        .doesNotContain("resources()")
        .doesNotContain(".require(");
  }

  /**
   * §5.11 的<b>发现渠道</b>：nudge 必须写在工具描述里（描述是模型发现一个工具的唯一入口）—— "刚被 {@code WORKSPACE_NOT_FOUND} 拒了 ⇒
   * 调我"，错误码引生产常量而不是手抄字面量。
   */
  @Test
  void infoDescriptionCarriesTheNudge() {
    String description = tool(registry, WorkspaceTools.WORKSPACE_INFO).description();

    assertThat(description)
        .as("描述里点出「被 WORKSPACE_NOT_FOUND 拒了就先调我」")
        .contains(WorkspaceException.WORKSPACE_NOT_FOUND)
        .contains("无参数");
    assertThat(description).as("并承诺「一个挂载都没有的人调它也会得到清楚回答（不是错误）」——该承诺由零挂载用例兑现").contains("不是错误");
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /** 面空（未表态）：{@code allowAll} + 一个资源命名空间都不表态——最需要自救渠道的那一类调用者。 */
  private static AgentPermissionSet emptyFace() {
    return AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build();
  }

  /** 装配期挂载（宿主代码走注册表 API 的绝对 {@code dir} 形态）。 */
  private void mountAssembly(
      String name, String user, Path dir, Kind kind, Set<String> capabilities) {
    registry.mount(
        new Mount(
            new MountName(name),
            user,
            user,
            dir,
            Resolvers.direct(),
            kind,
            capabilities,
            Origin.ASSEMBLY,
            Optional.empty()),
        new WorkspaceCaller(user, fullFace));
  }

  private static AgentTool tool(WorkspaceRegistry registry, String name) {
    for (AgentTool tool : WorkspaceTools.all(registry)) {
      if (tool.name().equals(name)) {
        return tool;
      }
    }
    throw new AssertionError("WorkspaceTools 里没有这个工具名: " + name);
  }

  /** 经<b>唯一入口</b>调用（与生产同路：资源前置闸 → 工具 body）。 */
  private ToolResult call(
      String toolName, AgentPermissionSet perms, String instanceId, Map<String, Object> arguments) {
    return call(toolName, perms, new AgentIdentity(instanceId, CommandMode.FULL, "", 0), arguments);
  }

  /** 同上，但身份整个给定（用于 {@link AgentIdentity#UNKNOWN} 这种"解不出身份"的形态）。 */
  private ToolResult call(
      String toolName,
      AgentPermissionSet perms,
      AgentIdentity identity,
      Map<String, Object> arguments) {
    AgentTool target = tool(registry, toolName);
    ToolRegistry tools = new ToolRegistry();
    tools.register(target);
    ToolContext context =
        new ToolContext(
            AccessToken.DEFAULT,
            perms,
            Map.of(),
            arguments,
            identity,
            ResourceAuthorizer.of(perms, target.resources()));
    return ToolCallAuthorizer.standard().execute(tools, toolName, context);
  }

  /**
   * "这段文本提到这个物理目录了吗"——<b>唯一判定式</b>，正负两个方向都走它（正样本自证才有意义）。
   *
   * <p>用绝对归一形态比对：工具输出里若回显物理落点，最可能是 {@code mount.dir()} 的原样字符串。
   */
  private static boolean mentionsPhysicalPath(String text, Path dir) {
    return text.contains(dir.toAbsolutePath().normalize().toString());
  }

  /** 读被测源文件（找不到就红——不许静默跳过结构断言）。 */
  private static String source(String relative) throws IOException {
    Path direct = Path.of(relative);
    Path path = Files.isRegularFile(direct) ? direct : Path.of("WorkspaceMosire").resolve(relative);
    assertThat(Files.isRegularFile(path))
        .as("被测源文件必须存在（路径写错就是用例失效，不是跳过）: %s", path.toAbsolutePath())
        .isTrue();
    return Files.readString(path, StandardCharsets.UTF_8);
  }

  /**
   * 取一个方法的正文（从签名到顶格两空格的右花括号）——结构性断言的取样单位。
   *
   * <p>不按花括号配平：这些方法体里有形如 {@code "[a-z0-9]{0,63}"} 的 schema 描述串；按缩进取界更稳 （{@code google-java-format}
   * 下顶层方法的结束花括号恒为两空格缩进）。
   *
   * <p><b>先归一换行</b>：本机 {@code core.autocrlf=true}，工作树里的 Java 文件是 CRLF（HEAD 里的 blob 是 LF）—— 不归一的话
   * {@code "\n }\n"} 恒不命中，这条结构断言会红在换行上而不是红在被测事实上。
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
