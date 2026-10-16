package io.mosire.brain.decision;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.decision.DecisionPackage;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.PackageItem;
import io.mosire.agentlib.decision.ProposeRequest;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 逐级预裁定的判据（设计 §5ter 裁定 12 + §5quater 的实现形状）：三条能力判据、从提出者往上的上溯顺序、 "驳/不表态不终结（继续上报）"、{@link
 * AskKind#SENSITIVE} 强制到人。
 *
 * <p><b>为什么这些用例必须存在</b>：级联的写法有好几种都"看起来对"，但其中两种是错的——① 把下级的驳回当终结（用户 V18 明确要求"允许上一级 Agent 修改下一级 Agent
 * 做的裁定"）；② 把 SENSITIVE 也交给 Agent 预裁定（2026-09-13 老红线，裁定 12 只叠加、不取代）。 两条错法各有用例钉着（见 {@link
 * #lowerLevelRejectionDoesNotTerminateEscalation()} 与 {@link
 * #sensitiveItemIsForcedToHumanEvenWhenTheChainIsCapable()}）。
 *
 * <p><b>变异自证</b>：把 {@code DecisionCascade.walk} 里 {@code Verdict.REJECT} 那一支改成"把条目记成已解决" （=
 * 下级驳回即终结） ⇒ 本类的两条驳回用例当场红（实测见 P2b 报告）。
 */
class DecisionCascadeTest {

  private static final String NAMESPACE = "docs";
  private static final long DEADLINE = System.currentTimeMillis() + 60_000L;

  /** 有权执行 docs 面读写的级（完全权限档）。 */
  private static DecisionCascade.Level capableLevel(String instanceId, String parent) {
    AgentPermissionSet permissions =
        AgentPermissionSet.unrestricted(AccessToken.SYSTEM)
            .withResourceScopes(
                io.mosire.agentlib.permission.ResourceScopeMap.of(
                    NAMESPACE, io.mosire.agentlib.permission.ResourceScope.unlimited()));
    return new DecisionCascade.Level(instanceId, permissions, CommandMode.FULL, parent);
  }

  /**
   * 这一级的能力被资源面卡住（够不着工具声明的命名空间）。
   *
   * <p>★ 必须是<b>显式</b>的 {@link io.mosire.agentlib.permission.ResourceScope#none()}：资源面的口径是"未表态 =
   * 取工具缺省策略" （不是"全拒"），不表态的权限集在 READ_ONLY 声明面前照样够得着——那正是本仓 S5-C 的语义，别在这里写反。
   */
  private static DecisionCascade.Level resourceBlindLevel(String instanceId, String parent) {
    AgentPermissionSet permissions =
        AgentPermissionSet.system()
            .withResourceScopes(
                io.mosire.agentlib.permission.ResourceScopeMap.of(
                    NAMESPACE, io.mosire.agentlib.permission.ResourceScope.none()));
    return new DecisionCascade.Level(instanceId, permissions, CommandMode.FULL, parent);
  }

  /** 这一级的能力被档位卡住（不完全权限档不谈"替别人拍板"）。 */
  private static DecisionCascade.Level limitedLevel(String instanceId, String parent) {
    AgentPermissionSet permissions =
        AgentPermissionSet.unrestricted(AccessToken.SYSTEM)
            .withResourceScopes(
                io.mosire.agentlib.permission.ResourceScopeMap.of(
                    NAMESPACE, io.mosire.agentlib.permission.ResourceScope.unlimited()));
    return new DecisionCascade.Level(instanceId, permissions, CommandMode.LIMITED, parent);
  }

  /** 声明了 docs 面（READ_ONLY）的工具：没有资源判定者/够不着 ⇒ 能力判据第 2 条判否。 */
  private static AgentTool docsTool(String name) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public ResourceManifest resources() {
        return ResourceManifest.of(NAMESPACE, ResourcePolicy.READ_ONLY);
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok("ran");
      }

      @Override
      public ToolGate gate(ToolContext context) {
        // 工具自报"要问"（就是它会进包的原因）；报什么 kind 由用例经 askKinds 决定（真实来源 = 分类器）
        return new ToolGate.Ask("docs:ask", "写文档");
      }
    };
  }

  /** 建一个包（走真实状态机：级联必须能接在 propose 的真包上）。 */
  private static DecisionPackage pkg(String proposerKey, int itemCount) {
    DecisionPackages packages = new DecisionPackages();
    List<PackageItem> items = new java.util.ArrayList<>();
    for (int i = 0; i < itemCount; i++) {
      items.add(new PackageItem(i, "write_doc", Map.of("path", "a.md"), "digest-" + i, "写文档"));
    }
    return packages.propose(
        new ProposeRequest(proposerKey, AccessToken.SYSTEM, List.copyOf(items), DEADLINE));
  }

  /** 链：只认列出来的那几级（找不到 ⇒ 上溯到此为止）。 */
  private static DecisionCascade.Chain chain(DecisionCascade.Level... levels) {
    Map<String, DecisionCascade.Level> byId = new HashMap<>();
    for (DecisionCascade.Level level : levels) {
      byId.put(level.instanceId(), level);
    }
    return id -> Optional.ofNullable(byId.get(id));
  }

  private static DecisionCascade cascade(
      DecisionCascade.Chain chain, DecisionCascade.LevelJudgement judgement) {
    return new DecisionCascade(chain, name -> Optional.of(docsTool(name)), judgement);
  }

  @Test
  void capableProposerApprovesAndNothingReachesTheHuman() {
    DecisionCascade cascade =
        cascade(chain(capableLevel("main", "")), DecisionCascade.CAPABILITY_APPROVES);
    DecisionPackage p = pkg("main", 2);

    DecisionCascade.DecisionTrail trail =
        cascade.run(p, Map.of(0, AskKind.MODE_LIMITED, 1, AskKind.MODE_LIMITED));

    assertThat(trail.escalatedToHuman()).as("有能力的级在提出者那一层就批掉了").isEmpty();
    assertThat(trail.finalApproved()).containsExactlyInAnyOrder(0, 1);
    assertThat(trail.finalizedBy()).isEqualTo("main");
    assertThat(trail.levels()).hasSize(1);
    assertThat(trail.levels().get(0).deciderId()).isEqualTo("main");
    assertThat(trail.levels().get(0).approvedIndexes()).containsExactlyInAnyOrder(0, 1);
    assertThat(cascade.finalizedBy(p.id())).contains("main");
    assertThat(cascade.preApprovedOf(p.id())).contains(Set.of(0, 1));
  }

  @Test
  void sensitiveItemIsForcedToHumanEvenWhenTheChainIsCapable() {
    DecisionCascade cascade =
        cascade(chain(capableLevel("main", "")), DecisionCascade.CAPABILITY_APPROVES);
    DecisionPackage p = pkg("main", 2);

    DecisionCascade.DecisionTrail trail =
        cascade.run(
            p,
            Map.of(
                0, AskKind.MODE_LIMITED,
                1, AskKind.SENSITIVE));

    assertThat(trail.escalatedToHuman()).as("SENSITIVE 强制留在到人那一侧").containsExactly(1);
    assertThat(trail.finalApproved()).as("只有档位提升那条被预裁定批准").containsExactly(0);
    assertThat(trail.finalizedBy()).as("还有人要裁 ⇒ 还没终局").isNull();
    assertThat(trail.levels()).hasSize(1);
    assertThat(trail.levels().get(0).approvedIndexes()).containsExactly(0);
    assertThat(trail.levels().get(0).rejectedIndexes()).isEmpty();
  }

  @Test
  void missingAskKindIsTreatedAsSensitive() {
    DecisionCascade cascade =
        cascade(chain(capableLevel("main", "")), DecisionCascade.CAPABILITY_APPROVES);
    DecisionPackage p = pkg("main", 1);

    DecisionCascade.DecisionTrail trail = cascade.run(p, null);

    assertThat(trail.escalatedToHuman())
        .as("没有'为什么问'的料 ⇒ 按 SENSITIVE（永远到人），fail-closed")
        .containsExactly(0);
    assertThat(trail.finalApproved()).isEmpty();
  }

  @Test
  void incapableLowerLevelEscalatesAndTheUpperLevelApproves() {
    // 提出者 sub-1 是 LIMITED 档（能力判据第 3 条判否）⇒ 继续上报；父 main 有能力 ⇒ 它批
    DecisionCascade cascade =
        cascade(
            chain(limitedLevel("sub-1", "main"), capableLevel("main", "")),
            DecisionCascade.CAPABILITY_APPROVES);
    DecisionPackage p = pkg("sub-1", 1);

    DecisionCascade.DecisionTrail trail = cascade.run(p, Map.of(0, AskKind.MODE_LIMITED));

    assertThat(trail.escalatedToHuman()).isEmpty();
    assertThat(trail.finalApproved()).containsExactly(0);
    assertThat(trail.finalizedBy()).as("终局裁决者 = 最后拍板那一级").isEqualTo("main");
    assertThat(trail.levels()).hasSize(1);
    assertThat(trail.levels().get(0).deciderId()).as("没能力的那一级不记裁决（没能力 ≠ 驳回）").isEqualTo("main");
  }

  @Test
  void resourceBlindLevelIsNotCapable() {
    // 权限面过得去但资源面够不着（没有 docs 面）⇒ 第 2 条判据判否 ⇒ 上报
    DecisionCascade cascade =
        cascade(
            chain(resourceBlindLevel("sub-1", ""), capableLevel("main", "")),
            DecisionCascade.CAPABILITY_APPROVES);
    DecisionPackage p = pkg("sub-1", 1);

    DecisionCascade.DecisionTrail trail = cascade.run(p, Map.of(0, AskKind.MODE_LIMITED));

    assertThat(trail.escalatedToHuman()).as("链上没人有能力 ⇒ 到人").containsExactly(0);
    assertThat(trail.finalizedBy()).isNull();
  }

  /** ★ 变异自证点（下级驳回即终结 ⇒ 本用例当场红）。 */
  @Test
  void lowerLevelRejectionDoesNotTerminateEscalation() {
    // sub-1 有能力但驳回；父 main 有能力且批准 ⇒ 条目最终由 main 批准（"允许上一级修改下一级"）
    DecisionCascade.LevelJudgement subRejects =
        (level, items) ->
            level.instanceId().equals("sub-1")
                ? DecisionCascade.Verdict.REJECT
                : DecisionCascade.Verdict.APPROVE;
    DecisionCascade cascade =
        cascade(chain(capableLevel("sub-1", "main"), capableLevel("main", "")), subRejects);
    DecisionPackage p = pkg("sub-1", 1);

    DecisionCascade.DecisionTrail trail = cascade.run(p, Map.of(0, AskKind.MODE_LIMITED));

    assertThat(trail.finalApproved()).as("下级驳回不是终结：上一级把它改判成批准").containsExactly(0);
    assertThat(trail.escalatedToHuman()).isEmpty();
    assertThat(trail.finalizedBy()).isEqualTo("main");
    assertThat(trail.levels()).hasSize(2);
    assertThat(trail.levels().get(0).deciderId()).isEqualTo("sub-1");
    assertThat(trail.levels().get(0).rejectedIndexes()).containsExactly(0);
    assertThat(trail.levels().get(1).deciderId()).isEqualTo("main");
    assertThat(trail.levels().get(1).approvedIndexes()).containsExactly(0);
  }

  /** ★ 变异自证点（下级驳回即终结 ⇒ 本用例当场红）。 */
  @Test
  void rejectionAtEveryLevelStillReachesTheHuman() {
    // sub-1 有能力但驳回；父 main 没能力（LIMITED）⇒ 到顶仍被驳 ⇒ 到人（人面对的是这条）
    DecisionCascade.LevelJudgement subRejects = (level, items) -> DecisionCascade.Verdict.REJECT;
    DecisionCascade cascade =
        cascade(chain(capableLevel("sub-1", "main"), limitedLevel("main", "")), subRejects);
    DecisionPackage p = pkg("sub-1", 1);

    DecisionCascade.DecisionTrail trail = cascade.run(p, Map.of(0, AskKind.MODE_LIMITED));

    assertThat(trail.escalatedToHuman()).as("到顶仍没人批 ⇒ 这一条到人（不是'下级驳了就没了'）").containsExactly(0);
    assertThat(trail.finalApproved()).isEmpty();
    assertThat(trail.finalizedBy()).isNull();
  }

  @Test
  void abstainAlsoEscalates() {
    DecisionCascade cascade =
        cascade(chain(capableLevel("main", "")), (level, items) -> DecisionCascade.Verdict.ABSTAIN);
    DecisionPackage p = pkg("main", 1);

    DecisionCascade.DecisionTrail trail = cascade.run(p, Map.of(0, AskKind.MODE_LIMITED));

    assertThat(trail.escalatedToHuman()).containsExactly(0);
    assertThat(trail.levels()).as("不表态不记裁决").isEmpty();
  }

  @Test
  void unknownProposerKeyEscalatesEverything() {
    DecisionCascade cascade =
        cascade(chain(capableLevel("main", "")), DecisionCascade.CAPABILITY_APPROVES);
    DecisionPackage p = pkg("sub-9", 1);

    DecisionCascade.DecisionTrail trail = cascade.run(p, Map.of(0, AskKind.MODE_LIMITED));

    assertThat(trail.escalatedToHuman()).as("派生链上认不出提出者 ⇒ 不猜能力，整条到人").containsExactly(0);
  }

  @Test
  void humanCompletionRecordsTheHumanLevelAndOnlyCountsEscalatedItems() {
    DecisionCascade cascade =
        cascade(
            chain(capableLevel("sub-1", "main"), limitedLevel("main", "")),
            DecisionCascade.CAPABILITY_APPROVES);
    DecisionPackage p = pkg("sub-1", 2);
    // sub-1 有能力 ⇒ 预批准 0；main 是 LIMITED（没能力）+ 1 号是 SENSITIVE ⇒ 上报到人
    DecisionCascade.DecisionTrail before =
        cascade.run(
            p,
            Map.of(
                0, AskKind.MODE_LIMITED,
                1, AskKind.SENSITIVE));
    assertThat(before.escalatedToHuman()).containsExactly(1);
    assertThat(before.finalApproved()).containsExactly(0);

    DecisionCascade.DecisionTrail after =
        cascade.complete(p.id(), Set.of(0, 1), "人批了").orElseThrow();

    assertThat(after.finalApproved())
        .as("人不能替下级已经批掉的条目改口，但他的批准要并进去")
        .containsExactlyInAnyOrder(0, 1);
    assertThat(after.finalizedBy()).isEqualTo(DecisionCascade.HUMAN_DECIDER);
    assertThat(after.levels()).hasSize(2);
    assertThat(after.levels().get(1).deciderId()).isEqualTo(DecisionCascade.HUMAN_DECIDER);
    assertThat(after.levels().get(1).approvedIndexes()).as("只认上报集合里的 index").containsExactly(1);
  }

  @Test
  void completionIsIdempotent() {
    DecisionCascade cascade =
        cascade(chain(capableLevel("sub-1", "")), DecisionCascade.CAPABILITY_APPROVES);
    DecisionPackage p = pkg("sub-1", 1);
    cascade.run(p, Map.of(0, AskKind.SENSITIVE));

    DecisionCascade.DecisionTrail first =
        cascade.complete(p.id(), Set.of(), "驳回", DecisionCascade.HUMAN_DECIDER).orElseThrow();
    DecisionCascade.DecisionTrail second =
        cascade.complete(p.id(), Set.of(0), "改主意", DecisionCascade.HUMAN_DECIDER).orElseThrow();

    assertThat(second.finalApproved()).as("首次裁决永不被覆盖").isEqualTo(first.finalApproved());
    assertThat(second.finalizedBy()).isEqualTo(DecisionCascade.HUMAN_DECIDER);
  }

  @Test
  void loopInTheChainCannotSpinForever() {
    // 链上出现环（数据损坏：a 的父是 b、b 的父是 a）：visited 守卫必须让它停下来，而不是死循环
    DecisionCascade cascade =
        cascade(
            chain(capableLevel("a", "b"), capableLevel("b", "a")),
            DecisionCascade.CAPABILITY_APPROVES);

    DecisionCascade.DecisionTrail trail = cascade.run(pkg("a", 1), Map.of(0, AskKind.MODE_LIMITED));

    assertThat(trail.finalApproved()).containsExactly(0);
    assertThat(trail.finalizedBy()).isEqualTo("a");
  }
}
