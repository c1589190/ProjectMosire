package io.mosire.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 可见性判据的唯一一处（设计 §5.4，不变量 B2/B3）——逐格判一遍，每格都能说清"哪一行被改坏时它会转红"。
 *
 * <p>对号：§5.9 负向"身份解不出<b>不许</b>按归属放行"、"无主文档对无标签者不可见"；§5.9 正向"GM 看见全部"、 "可见者看见自己的 + tags/affiliations
 * 命中的"。
 */
class DocVisibilityTest {

  @Test
  void seesAllShortCircuitsBeforeEveryAxis() {
    DocEntry invisibleToOthers = entry(Set.of(), Set.of("other-team"), "someone-else");

    assertThat(DocVisibility.canSee(DocViewer.seesAll("main"), invisibleToOthers)).isTrue();
    assertThat(DocVisibility.canSee(DocViewer.seesAll(""), invisibleToOthers)).isTrue();
  }

  @Test
  void ownerSeesHisOwnEntry() {
    assertThat(
            DocVisibility.canSee(DocViewer.of("kid", Set.of()), entry(Set.of(), Set.of(), "kid")))
        .isTrue();
    assertThat(
            DocVisibility.canSee(DocViewer.of("other", Set.of()), entry(Set.of(), Set.of(), "kid")))
        .isFalse();
  }

  @Test
  void tagsGrantVisibility() {
    assertThat(
            DocVisibility.canSee(
                DocViewer.of("kid", Set.of()), entry(Set.of("kid"), Set.of(), null)))
        .isTrue();
    assertThat(
            DocVisibility.canSee(
                DocViewer.of("kid", Set.of()), entry(Set.of("other"), Set.of(), null)))
        .isFalse();
  }

  @Test
  void affiliationsGrantVisibilityOnIntersection() {
    assertThat(
            DocVisibility.canSee(
                DocViewer.of("kid", Set.of("team-a")),
                entry(Set.of(), Set.of("team-a", "team-b"), null)))
        .isTrue();
  }

  /**
   * §5.4 的 ★：身份解不出 ⇒ 跳过 affiliations 支——"不知道你是谁"不许读成"按归属放行"（fail-closed 的那一半）。
   *
   * <p><b>★ 停手上报的偏差（R4 发现，未改生产代码）</b>：设计 §5.4 与 {@link DocVisibility} 的类 javadoc 写的是 "身份解不出 ⇒ 跳过
   * affiliations 支，<b>只走 tags</b>"，但实现把 tags 支也用 {@code viewer.resolvable()} 挡掉了（{@code
   * DocVisibility.java:57} 的注释"actorId 解不出时这一支也不成立"与类 javadoc 自相矛盾）。后果：把文档显式 tag 成字面量 {@code
   * "unknown"} 时，身份为 {@code unknown} 的观看者读不到它。方向是<b>更严</b>（不是泄露），
   * 因此这里把<b>实际行为</b>钉住并标注偏差，等控制方裁定哪一侧是权威口径。
   */
  @Test
  void unresolvableIdentitySkipsAffiliations() {
    DocEntry affiliated = entry(Set.of(), Set.of("team-a"), null);

    assertThat(DocVisibility.canSee(DocViewer.of("", Set.of("team-a")), affiliated)).isFalse();
    assertThat(DocVisibility.canSee(DocViewer.of("unknown", Set.of("team-a")), affiliated))
        .isFalse();
    assertThat(DocVisibility.canSee(DocViewer.of("  ", Set.of("team-a")), affiliated)).isFalse();
    assertThat(
            DocVisibility.canSee(
                DocViewer.of("unknown", Set.of("team-a")),
                entry(Set.of("unknown"), Set.of(), null)))
        .as("实际行为：身份解不出时 tags 支同样不成立（比设计更严；见本用例 javadoc 的偏差说明）")
        .isFalse();
    assertThat(
            DocVisibility.canSee(
                DocViewer.of("kid", Set.of()), entry(Set.of("kid"), Set.of(), null)))
        .as("身份可解出时 tags 支照常成立")
        .isTrue();
  }

  /** §5.4：无主 ≠ 大家都能看（fail-closed）。 */
  @Test
  void ownerlessEntryIsInvisibleToUntagged() {
    DocEntry orphan = entry(Set.of(), Set.of("team-a"), null);

    assertThat(DocVisibility.canSee(DocViewer.of("stranger", Set.of("team-b")), orphan)).isFalse();
    assertThat(DocVisibility.canSee(DocViewer.of("stranger", Set.of()), orphan)).isFalse();
    assertThat(DocVisibility.canSee(DocViewer.of("team-a", Set.of("team-a")), orphan)).isTrue();
  }

  /** §5.5："取不到"与"不可见"返回同一个回答——{@code visible} 只有一个出口。 */
  @Test
  void visibleMatchesCanSee() {
    DocEntry secret = entry(Set.of(), Set.of(), "kid");

    assertThat(DocVisibility.visible(DocViewer.of("kid", Set.of()), secret)).isPresent();
    assertThat(DocVisibility.visible(DocViewer.of("stranger", Set.of()), secret)).isEmpty();
    assertThat(DocVisibility.canSee(DocViewer.of("stranger", Set.of()), secret)).isFalse();
  }

  /** 空 tags/affiliations 的边界：空集不与空 actorId 撞上。 */
  @Test
  void emptyAxesDoNotCollideWithEmptyIdentity() {
    DocEntry blank = entry(Set.of(), Set.of(), null);

    assertThat(DocVisibility.canSee(DocViewer.of("", Set.of()), blank)).isFalse();
    assertThat(DocVisibility.canSee(DocViewer.of("unknown", Set.of()), blank)).isFalse();
  }

  private static DocEntry entry(Set<String> tags, Set<String> affiliations, String owner) {
    return new DocEntry(
        "a",
        1,
        0,
        tags,
        affiliations,
        Optional.ofNullable(owner),
        "k",
        "v",
        Optional.empty(),
        DocStatus.EFFECTIVE,
        Map.of(),
        Optional.empty());
  }
}
