package io.mosire.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.workspace.DefaultWorkspaceRegistry;
import io.mosire.workspace.Kind;
import io.mosire.workspace.WorkspaceCaller;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 坏数据披露的观看者收口判据表（设计 §9.16「坏数据披露未按观看者收口」★ R6 已修；§5.3 / §5.4 / §5.5），全离线。
 *
 * <p><b>收口的是什么</b>：{@code broken} 披露只列调用者<b>看得见</b>的地址的坏数据——与 {@code list} 的地址摘要同一判据（{@link
 * DocVisibility#canSee}），因此"不可见 ≡ 不存在"在披露口上同样成立：不可见地址在披露里<b>一个字都不出现</b>（含 {@link BrokenDoc#where()}
 * 里的编码文件名）。收口前的披露会把整个挂载的坏数据（含不可见地址的编码文件名）念给模型听，等于一个"这里有哪些地址" 的存在性探测器（R4b 发现）。
 *
 * <p><b>形状是加重载，不是改签名</b>：{@code broken(caller)}（引擎层）与 {@code api.broken(caller)} 仍是<b>全量诊断快照</b>
 * ——逐字不变，只该给"不把结果读给模型听"的进程内调用点用；面向观看者/模型的一律走 {@code broken(caller, viewer)}。
 *
 * <p><b>逐条对号</b>：
 *
 * <ul>
 *   <li>{@link #toolFaceDisclosesOnlyVisibleAddresses()}（工具面 {@code
 *       DocsAccess.broken(ToolContext)}：只披露看得见的地址）；
 *   <li>{@link #viewerScopedSignatureDisclosesOnlyVisibleAddresses()}（收口形态：只含可见地址，API 与引擎逐字一致）；
 *   <li>{@link #fullSnapshotSignatureStillCarriesTheInvisibleAddress()}（对照 +
 *       旧签名回归：全量快照确实含有不可见地址，证明数据真在、 判据有判别力，且旧签名行为逐字不变）；
 *   <li>{@link #gmViewerStillSeesEverything()}（GM 观看者照常披露——不是"一律不披露"）；
 *   <li>{@link #unreadableAddressIsNotDisclosedFailClosed()}（整份日志读不出来 ⇒ 判不出可见性 ⇒ 不披露，fail-closed）。
 * </ul>
 */
class DocsBrokenDisclosureTest {

  /** 调用者（owner）自己的地址：看得见。 */
  private static final String VISIBLE = "open-log";

  /** 别人的地址：对调用者不可见（B2/§5.5：与不存在同一个回答）。 */
  private static final String SECRET = "机密-账目";

  /** 后面会被 chmod 成读不出来的那个可见地址（fail-closed 用）。 */
  private static final String LOCKED = "locked-log";

  /** 不可见地址编码文件名的独立指纹（"机" 的 UTF-8 首字节 + 后两字节的百分号编码）。 */
  private static final String SECRET_ENCODED_FINGERPRINT = "%E6%9C%BA";

  @TempDir Path tempDir;

  private Path logRoot;
  private DefaultWorkspaceRegistry registry;
  private AgentPermissionSet permissions;
  private WorkspaceCaller caller;
  private DocsApi api;

  @BeforeEach
  void setUp() throws IOException {
    logRoot = Files.createDirectories(tempDir.resolve("journal"));
    permissions = DocsFixture.permissions(logRoot);
    caller = new WorkspaceCaller("bob", permissions);
    registry =
        DocsFixture.registryWith(
            DocsFixture.catalog(),
            DocsFixture.mount("journal", "bob", "bob", logRoot, Kind.LOG),
            caller);
    api = new DocsApi(registry);
    api.append("journal", caller, owned(VISIBLE, "bob"));
    api.append("journal", caller, owned(SECRET, "alice"));
    api.append("journal", caller, owned(LOCKED, "bob"));
    // 每个地址都造一处坏数据：往日志尾部追加一行不是合法 NDJSON 的记录（坏行在此披露）
    for (String address : List.of(VISIBLE, SECRET, LOCKED)) {
      Files.writeString(DocLogs.logFile(logRoot, address), "{这不是合法记录\n", StandardOpenOption.APPEND);
    }
  }

  /** §9.16 主判据：工具面（{@code doc_list} 的坏数据后缀）只披露调用者看得见的地址。 */
  @Test
  void toolFaceDisclosesOnlyVisibleAddresses() {
    ToolResult listed = docListAsBob();

    assertThat(listed.success()).as(listed.message()).isTrue();
    assertThat(listed.message())
        .as("看得见的地址的坏数据必须照常披露（收口不是「索性不披露」）")
        .contains(VISIBLE)
        .contains("坏数据");
    assertThat(listed.message())
        .as("不可见地址的编码文件名一个字都不出现")
        .doesNotContain(logFileName(SECRET))
        .doesNotContain(SECRET_ENCODED_FINGERPRINT)
        .doesNotContain(SECRET);
    assertThat(listed.message())
        .as("A7：披露与列表都不含物理落点")
        .doesNotContain(logRoot.toString())
        .doesNotContain(tempDir.toString());
  }

  /** §9.16：收口形态（{@code broken(caller, viewer)}）只含可见地址；API 层与引擎层逐字一致。 */
  @Test
  void viewerScopedSignatureDisclosesOnlyVisibleAddresses() {
    DocViewer bob = DocViewer.of("bob", Set.of());

    List<BrokenDoc> scoped = api.broken(caller, bob);

    assertThat(whereOf(scoped))
        .as("可见地址（owner=bob）的坏数据在")
        .contains(logFileName(VISIBLE))
        .as("不可见地址（owner=alice）的编码文件名不在")
        .doesNotContain(logFileName(SECRET));
    assertThat(scoped.stream().map(BrokenDoc::where).toList())
        .as("独立指纹再钉一次（不依赖 DocLogs 的编码实现）")
        .noneMatch(where -> where.contains(SECRET_ENCODED_FINGERPRINT) || where.contains(SECRET));
    assertThat(api.broken(caller, bob)).as("API 层只是引擎层的转发（同一份判据）").isEqualTo(scoped);
    assertThat(api.engine().broken(caller, bob)).isEqualTo(scoped);
  }

  /** §9.16 对照 + 旧签名回归：不带观看者的全量诊断快照<b>确实含有</b>不可见地址的坏数据（证明数据真在、上面两条判据有判别力）， 并且两个旧签名的行为逐字不变。 */
  @Test
  void fullSnapshotSignatureStillCarriesTheInvisibleAddress() {
    List<BrokenDoc> engineView = api.engine().broken(caller);
    List<BrokenDoc> apiView = api.broken(caller);

    assertThat(whereOf(engineView))
        .as("全量快照包含三个地址的坏数据（含调用者看不见的那个）")
        .contains(logFileName(VISIBLE), logFileName(LOCKED), logFileName(SECRET));
    assertThat(
            whereOf(engineView).stream()
                .anyMatch(where -> where.contains(SECRET_ENCODED_FINGERPRINT)))
        .as("不可见地址的编码文件名在旧签名里可见（判据不是恒真）")
        .isTrue();
    assertThat(apiView).as("旧签名逐字不变：DocsApi.broken(caller) 仍是引擎的全量快照").isEqualTo(engineView);
  }

  /** §9.16：GM 观看者（U3 无条件可见）照常拿到含不可见地址的披露——收口是"按观看者过滤"而不是"一律不披露"。 */
  @Test
  void gmViewerStillSeesEverything() {
    DocViewer gm = DocViewer.seesAll("admin");

    assertThat(whereOf(api.broken(caller, gm)))
        .as("GM 的收口形态仍然披露不可见地址")
        .contains(logFileName(SECRET));

    AgentTool docList = DocsFixture.tool(registry, DocsTools.DOC_LIST);
    ToolResult gmList =
        docList.execute(
            DocsFixture.context(
                AccessToken.SYSTEM, "bob", permissions, docList, Map.of("mount", "journal")));

    assertThat(gmList.success()).as(gmList.message()).isTrue();
    assertThat(gmList.message())
        .as("工具面的 GM 短路位（AccessToken.SYSTEM）与被过滤的调用者是同一条缝")
        .contains(logFileName(SECRET));
  }

  /**
   * §9.16 / §9.18：整份日志读不出来 ⇒ 该地址一条条目都读不到 ⇒ 判不出可见性 ⇒ 它的坏数据不披露（fail-closed）；<b>同一次调用里其余可见地址照常披露</b>。
   */
  @Test
  void unreadableAddressIsNotDisclosedFailClosed() throws IOException {
    DocViewer bob = DocViewer.of("bob", Set.of());
    assertThat(whereOf(api.broken(caller, bob)))
        .as("前置：可读时这个地址的坏数据是会被披露的（否则下面的绿是恒真）")
        .contains(logFileName(LOCKED));

    Path lockedFile = DocLogs.logFile(logRoot, LOCKED);
    assertThat(lockedFile.toFile().setReadable(false, false)).isTrue();
    assertThat(lockedFile.toFile().setWritable(false, false)).isTrue();
    try {
      boolean unreadable;
      try {
        Files.readAllBytes(lockedFile);
        unreadable = false;
      } catch (IOException e) {
        unreadable = true;
      }
      assumeTrue(unreadable, "当前用户仍能读被 chmod 掉权限的文件（root？）⇒ 本用例的 fail-closed 前置不成立，跳过而不是假绿");

      List<BrokenDoc> after = api.broken(caller, bob);

      assertThat(whereOf(after))
          .as("读不出来的地址 ⇒ 不披露（判不出可见性就不出现）")
          .doesNotContain(logFileName(LOCKED));
      assertThat(whereOf(after)).as("同一次调用里可见地址照常披露").contains(logFileName(VISIBLE));
    } finally {
      lockedFile.toFile().setReadable(true, false);
      lockedFile.toFile().setWritable(true, false);
    }
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  private ToolResult docListAsBob() {
    AgentTool docList = DocsFixture.tool(registry, DocsTools.DOC_LIST);
    return docList.execute(
        DocsFixture.context("bob", permissions, docList, Map.of("mount", "journal")));
  }

  /** 带 owner 的追加请求（owner 是可见性轴 3：单决策人私有）。 */
  private static AppendRequest owned(String address, String owner) {
    return new AppendRequest(
        address,
        1L,
        Set.of(),
        Set.of(),
        Optional.of(owner),
        "k",
        "v",
        Optional.empty(),
        Map.of(),
        Optional.empty());
  }

  /** 该地址的日志文件名（{@code LOG} 披露的 {@code where} 就是它——编码后的文件名，含 {@code .log}）。 */
  private String logFileName(String address) {
    return DocLogs.logFile(logRoot, address).getFileName().toString();
  }

  private static List<String> whereOf(List<BrokenDoc> broken) {
    return broken.stream().map(BrokenDoc::where).toList();
  }
}
