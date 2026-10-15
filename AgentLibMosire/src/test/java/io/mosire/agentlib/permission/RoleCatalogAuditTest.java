package io.mosire.agentlib.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.permission.RoleCatalogAudit.Report;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 装订期角色目录审计的行为契约（设计 §4.6；不变量 A-4 / A-5）。
 *
 * <p><b>为什么这三条必须在装订期判</b>：角色名对 AgentLib 是不透明标签，判定侧只做"调用者角色 ∩ 工具声明角色 ≠ ∅"—— ①工具写了一个拼错/未登记的角色名 ⇒
 * 该工具<b>永远不可达</b>但进程照常起、日志照常干净（"漂移无症状"）；③宿主另有一份手抄的角色→工具目录 ⇒ 两份真相各写一遍， 改了一处另一处不跟着动。两者都必须在
 * 进程开始对外服务<b>之前</b>变成一条可见的结论。
 *
 * <p><b>判别性（变异自证）</b>：删掉 {@code run()} 里"未登记角色名"的那段检查 ⇒ {@link
 * #unknownRoleNameDeclaredByAToolFailsAndNamesBothTheToolAndTheRole} 转红；把 ③ 的"两样都给了才做"改成"缺一份也报不一致"
 * ⇒ {@link #onlyOneSideGivenSkipsTheReconciliationInsteadOfPassingIt} 转红。
 */
class RoleCatalogAuditTest {

  // ────────────────────────── A-4：未知角色名 ⇒ 失败 ──────────────────────────

  /**
   * A-4（正）：工具声明了未登记角色 ⇒ {@code hasFailures()} 为真，且明细<b>点名工具 + 角色</b>（"哪个工具的哪个名字拼错了"必须一眼可读——
   * 只报一个计数等于让人回去翻注册表）。
   */
  @Test
  void unknownRoleNameDeclaredByAToolFailsAndNamesBothTheToolAndTheRole() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("gm_only_tool", Set.of("gm")));
    registry.register(tool("typo_tool", Set.of("gm", "gm_typo")));

    Report report = RoleCatalogAudit.of(registry, Set.of("gm")).run();

    assertThat(report.hasFailures()).as("未登记角色名 ⇒ 失败（不是报告）").isTrue();
    assertThat(report.unknownRoleFindings()).hasSize(1);
    assertThat(report.failures()).hasSize(1);
    assertThat(report.unknownRoleFindings().get(0))
        .as("明细必须同时点名工具与角色")
        .contains("typo_tool")
        .contains("gm_typo");
    assertThat(report.summary()).contains("失败=1").contains("目录对账=未提供");
  }

  /** A-4（正·边界）：注册表里全是已登记角色（或无角色声明的既有工具）⇒ 不失败。 */
  @Test
  void knownRoleNamesAndSilentToolsProduceNoFailure() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("gm_only_tool", Set.of("gm")));
    registry.register(tool("dm_only_tool", Set.of("dm")));
    registry.register(tool("legacy_tool", Set.of()));

    Report report = RoleCatalogAudit.of(registry, Set.of("gm", "dm")).run();

    assertThat(report.hasFailures()).isFalse();
    assertThat(report.toolCount()).isEqualTo(3);
    assertThat(report.roleDeclaringToolCount()).as("空集 = 本维不表态，不计入声明了角色的工具数（A-2）").isEqualTo(2);
    assertThat(report.knownRoleCount()).isEqualTo(2);
  }

  /** A-4（负·反向对照）：把同一个工具的角色名从"未登记"换成"已登记"⇒ 同一条断言反向成立。没有这条，"失败"可能只是"这个注册表怎么都失败"。 */
  @Test
  void theSameShapeWithARegisteredNameStopsFailing() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("gated_tool", Set.of("gm")));

    assertThat(RoleCatalogAudit.of(registry, Set.of("gm")).run().hasFailures()).isFalse();
    assertThat(RoleCatalogAudit.of(registry, Set.of("dm")).run().hasFailures())
        .as("换成不覆盖它的已知集合 ⇒ 失败（同一条判定式，不是注册表的性质）")
        .isTrue();
  }

  /** 声明里的空白名与拼错的角色同类：匹配不了任何调用者 ⇒ 永远不可达 ⇒ 失败，且明细显式写 {@code <空白>}。 */
  @Test
  void blankDeclaredRoleIsAlsoAFailureAndIsRenderedExplicitly() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("blank_role_tool", Set.of("  ")));

    Report report = RoleCatalogAudit.of(registry, Set.of("gm")).run();

    assertThat(report.hasFailures()).isTrue();
    assertThat(report.unknownRoleFindings().get(0)).contains("blank_role_tool").contains("<空白>");
  }

  /** {@code null} 角色集按"不表态"读（防呆口径，同 {@code AgentTool#roles} 的 null ⇒ 空集）。 */
  @Test
  void nullRoleSetReadsAsSilentAndNeverFails() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("null_roles_tool", null));

    Report report = RoleCatalogAudit.of(registry, Set.of()).run();

    assertThat(report.hasFailures()).isFalse();
    assertThat(report.roleDeclaringToolCount()).isZero();
  }

  // ────────────────────────── ② 报告（不失败） ──────────────────────────

  /** ②：已知角色底下没有任何工具 ⇒ 报告；报告<b>不是</b>失败（可能是"这个部署还没配角色门工具"的正常态）。 */
  @Test
  void knownRoleWithoutAnyToolIsReportedButNotFailed() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("legacy_tool", Set.of()));

    Report report = RoleCatalogAudit.of(registry, Set.of("gm")).run();

    assertThat(report.hasFailures()).isFalse();
    assertThat(report.uncoveredRoleFindings()).hasSize(1);
    assertThat(report.uncoveredRoleFindings().get(0)).contains("gm").contains("没有任何工具");
    assertThat(report.failures()).isEmpty();
    assertThat(report.summary()).contains("报告=1").contains("失败=0");
  }

  /** ② 的另一半：工具声明的角色<b>全都</b>不在已知集合内 ⇒ 报告（与 ① 同时出现时各自成条，不互相吞）。 */
  @Test
  void toolWhoseEveryDeclaredRoleIsUnknownIsReportedAsUnreachable() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("orphan_tool", Set.of("ghost")));

    Report report = RoleCatalogAudit.of(registry, Set.of("gm")).run();

    assertThat(report.hasFailures()).isTrue(); // ① 未知角色名
    assertThat(report.uncoveredRoleFindings())
        .anySatisfy(finding -> assertThat(finding).contains("orphan_tool").contains("ghost"));
  }

  /** 宿主给的 {@code knownRoles} 含空白名 ⇒ 构造期响亮失败（宿主 bug，静默收下会让"未登记"判定失真）。 */
  @Test
  void blankKnownRoleIsRejectedAtConstruction() {
    assertThatThrownBy(() -> RoleCatalogAudit.of(new ToolRegistry(), List.of("gm", " ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("空白角色名");
  }

  // ────────────────────────── A-5：派生 ⇔ 手抄对账 ──────────────────────────

  /**
   * A-5（正）：两样都给、且一致 ⇒ 不失败且 {@code catalogChecked()==true}（"对账做过并通过"与"没提供这一层"必须可区分——它们的 {@code
   * hasFailures()} 都是 false）。
   */
  @Test
  void matchingDerivedAndHandwrittenCatalogsPassAndReportChecked() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("gm_tool", Set.of("gm")));
    registry.register(tool("dm_tool", Set.of("dm")));

    RoleCatalogAudit audit =
        RoleCatalogAudit.of(registry, Set.of("gm", "dm"))
            .withDerivation(r -> Map.of("gm", Set.of("gm_tool"), "dm", Set.of("dm_tool")))
            .withHandwrittenCatalog(Map.of("gm", Set.of("gm_tool"), "dm", Set.of("dm_tool")));

    Report report = audit.run();

    assertThat(report.catalogChecked()).isTrue();
    assertThat(report.hasFailures()).isFalse();
    assertThat(report.catalogDiffFindings()).isEmpty();
    assertThat(report.summary()).contains("目录对账=已做");
  }

  /**
   * A-5（负·关键）：派生清单与手抄清单<b>不符</b> ⇒ 失败，明细点出"手抄缺哪些 / 多哪些"。
   *
   * <p>判别性：这条正是"隔壁 #3"的形状——手抄 16 条、派生 17 条，改了一处另一处不跟着动；把 ③ 整段删掉 ⇒ 本用例转红。
   */
  @Test
  void mismatchingHandwrittenCatalogFailsAndNamesTheMissingAndExtraTools() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("gm_tool", Set.of("gm")));
    registry.register(tool("gm_tool_two", Set.of("gm")));

    Report report =
        RoleCatalogAudit.of(registry, Set.of("gm"))
            .withDerivation(r -> Map.of("gm", Set.of("gm_tool", "gm_tool_two")))
            .withHandwrittenCatalog(Map.of("gm", Set.of("gm_tool"), "dm", Set.of("dm_tool")))
            .run();

    assertThat(report.catalogChecked()).isTrue();
    assertThat(report.hasFailures()).isTrue();
    assertThat(report.catalogDiffFindings()).hasSizeGreaterThanOrEqualTo(2);
    assertThat(report.catalogDiffFindings())
        .anySatisfy(f -> assertThat(f).contains("gm").contains("gm_tool_two"))
        .anySatisfy(f -> assertThat(f).contains("dm"));
    assertThat(report.failures()).isNotEmpty();
  }

  /**
   * A-5（正·可区分）：只给一份（派生或手抄）⇒ 跳过对账：{@code catalogChecked()==false} 且<b>不失败</b>。
   *
   * <p>"跳过"不是"空跑通过"——调用方据此回答"本部署有没有提供这一层"，而 {@code hasFailures()} 回答"对账过没过"。
   */
  @Test
  void onlyOneSideGivenSkipsTheReconciliationInsteadOfPassingIt() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("gm_tool", Set.of("gm")));

    Report derivedOnly =
        RoleCatalogAudit.of(registry, Set.of("gm"))
            .withDerivation(r -> Map.of("gm", Set.of("gm_tool")))
            .run();
    Report handwrittenOnly =
        RoleCatalogAudit.of(registry, Set.of("gm"))
            .withHandwrittenCatalog(Map.of("gm", Set.of("totally_other")))
            .run();
    Report neither = RoleCatalogAudit.of(registry, Set.of("gm")).run();

    assertThat(derivedOnly.catalogChecked()).isFalse();
    assertThat(derivedOnly.hasFailures()).isFalse();
    assertThat(handwrittenOnly.catalogChecked()).as("手抄清单单独在场也不对账（没有对比物）").isFalse();
    assertThat(handwrittenOnly.hasFailures()).as("跳过 ≠ 不一致：单边在场不许把'没对账'报成'对账不符'").isFalse();
    assertThat(neither.catalogChecked()).isFalse();
    assertThat(neither.summary()).contains("目录对账=未提供");
  }

  /** 手抄清单含空白角色名 ⇒ 注入期响亮失败（与 knownRoles 同一口径）。 */
  @Test
  void blankRoleInTheHandwrittenCatalogIsRejectedAtInjection() {
    RoleCatalogAudit audit = RoleCatalogAudit.of(new ToolRegistry(), Set.of("gm"));

    assertThatThrownBy(() -> audit.withHandwrittenCatalog(Map.of(" ", Set.of("t"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("空白角色名");
  }

  /**
   * 未注入派生函数时（缺省派生 = 注册表自身的"角色 → 声明了它的工具"）：{@code catalogChecked()} 仍为 false、空白声明名仍是失败。
   *
   * <p>★ 如实记：缺省派生只喂检查 ② 的角色全集；对任何"角色来自工具声明"的注册表，它算出的角色底下都<b>必然</b>有那个工具， 所以它在 ②
   * 上<b>没有</b>可观察差别（见证据包的"没验证"一节）。这里只钉住"缺省派生不改变结论方向"。
   */
  @Test
  void defaultDerivationKeepsTheVerdictAndStaysUnchecked() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("blank_role_tool", Set.of(" ")));
    registry.register(tool("gm_tool", Set.of(" gm ", "dm")));

    Report report = RoleCatalogAudit.of(registry, Set.of("gm", "dm")).run();

    assertThat(report.catalogChecked()).isFalse();
    assertThat(report.hasFailures()).as("空白声明名始终是失败（缺省派生也不吞掉它）").isTrue();
    assertThat(report.unknownRoleFindings()).hasSize(1);
    assertThat(report.unknownRoleFindings().get(0)).contains("blank_role_tool");
  }

  /** {@code run()} 每次现读注册表快照（可重复调用、不可变报告）：注册表变更后结论跟着变——"装订期一次"是流程事实，不是本类的缓存语义。 */
  @Test
  void runReadsAFreshRegistrySnapshotEveryTime() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("gm_tool", Set.of("gm")));
    RoleCatalogAudit audit = RoleCatalogAudit.of(registry, Set.of("gm"));

    assertThat(audit.run().hasFailures()).isFalse();
    registry.register(tool("typo_tool", Set.of("gm_typo")));
    assertThat(audit.run().hasFailures()).as("新增的坏声明必须在下一次 run 里被看见").isTrue();
    registry.unregister("typo_tool");
    assertThat(audit.run().hasFailures()).isFalse();
  }

  // ────────────────────────── 夹具 ──────────────────────────

  /** 合成工具：只承载"名字 + 角色声明"，执行面带最小实现（本审计只读 name/roles）。 */
  private static AgentTool tool(String name, Set<String> roles) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Set<String> roles() {
        return roles;
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok(name);
      }
    };
  }
}
