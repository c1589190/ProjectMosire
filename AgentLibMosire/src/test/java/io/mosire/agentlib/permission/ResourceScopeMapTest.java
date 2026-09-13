package io.mosire.agentlib.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link ResourceScopeMap} 的层语义（S5-B）：<b>未声明 = 本层不表态</b>（不是"全拒"）、继承物化、逐命名空间求交与涵盖。
 *
 * <p>判别性重点：把"未声明"退化成"全拒"或"不限"任一方向，下面第一组用例必红。
 */
class ResourceScopeMapTest {

  @Test
  void undeclaredNamespaceIsSilentNotDeniedAndNotUnrestricted() {
    ResourceScopeMap onlyFs =
        ResourceScopeMap.of(ResourceScopeMap.FS, ResourceScope.of("/srv/work"));
    assertThat(onlyFs.namespaces()).containsExactly(ResourceScopeMap.FS);
    assertThat(onlyFs.declaredScope("plugin:foo")).isNull(); // 本层不表态：回落到工具 manifest 默认
    assertThat(onlyFs.scopeOrUnrestricted("plugin:foo").unrestricted()).isTrue();
    // 显式"哪里都不许"是另一回事：它表态了，且值为空集
    ResourceScopeMap denied = onlyFs.withNamespace("plugin:foo", ResourceScope.none());
    assertThat(denied.declaredScope("plugin:foo").allows("tenant/9")).isFalse();
    assertThat(denied.scopeOrUnrestricted("plugin:foo").unrestricted()).isFalse();
  }

  @Test
  void emptyMapIsTheDefaultAndMeansNoOpinion() {
    assertThat(ResourceScopeMap.empty().namespaces()).isEmpty();
    assertThat(ResourceScopeMap.empty().declaredScope(ResourceScopeMap.FS)).isNull();
    assertThat(ResourceScopeMap.empty().summary()).isEqualTo("*");
  }

  @Test
  void withNamespaceReplacesOnlyThatEntry() {
    ResourceScopeMap before =
        ResourceScopeMap.of(
            Map.of(
                ResourceScopeMap.FS,
                ResourceScope.of("/a"),
                "plugin:x",
                ResourceScope.of("tenant/1")));
    ResourceScopeMap after = before.withNamespace(ResourceScopeMap.FS, ResourceScope.of("/a/b"));
    assertThat(after.declaredScope(ResourceScopeMap.FS).prefixList()).containsExactly("/a/b");
    assertThat(after.declaredScope("plugin:x").prefixList()).containsExactly("tenant/1"); // 逐字保留
    assertThat(before.declaredScope(ResourceScopeMap.FS).prefixList())
        .containsExactly("/a"); // 原值不变
  }

  @Test
  void narrowToAppliesPerNamespaceAndKeepsSilence() {
    ResourceScopeMap mine =
        ResourceScopeMap.of(ResourceScopeMap.FS, ResourceScope.of("/srv", "/opt"));
    ResourceScopeMap theirs =
        ResourceScopeMap.of(
            Map.of(
                ResourceScopeMap.FS,
                ResourceScope.of("/srv/work"),
                "plugin:x",
                ResourceScope.of("tenant/1")));
    ResourceScopeMap both = mine.narrowTo(theirs);
    // fs：两边都表态 ⇒ 求交；plugin:x：只有对方表态 ⇒ 对方说了算（"不表态"不是"不限地否定"）
    assertThat(both.declaredScope(ResourceScopeMap.FS).prefixList()).containsExactly("/srv/work");
    assertThat(both.declaredScope("plugin:x").prefixList()).containsExactly("tenant/1");
    assertThat(theirs.narrowTo(mine)).isEqualTo(both); // 对称
  }

  @Test
  void narrowToProducesExplicitEmptyWhenNothingOverlaps() {
    ResourceScopeMap both =
        ResourceScopeMap.of(ResourceScopeMap.FS, ResourceScope.of("/srv"))
            .narrowTo(ResourceScopeMap.of(ResourceScopeMap.FS, ResourceScope.of("/data")));
    // 交集为空也要显式留成条目（"全拒"要看得见，不能变成"这一项不存在"）
    assertThat(both.namespaces()).containsExactly(ResourceScopeMap.FS);
    assertThat(both.declaredScope(ResourceScopeMap.FS).allows("/srv")).isFalse();
  }

  @Test
  void coversRequiresChildToBeAtLeastAsNarrowOnDeclaredNamespaces() {
    ResourceScopeMap parent = ResourceScopeMap.of(ResourceScopeMap.FS, ResourceScope.of("/srv"));
    assertThat(
            parent.covers(ResourceScopeMap.of(ResourceScopeMap.FS, ResourceScope.of("/srv/work"))))
        .isTrue();
    assertThat(parent.covers(ResourceScopeMap.of(ResourceScopeMap.FS, ResourceScope.of("/data"))))
        .isFalse();
    assertThat(parent.covers(ResourceScopeMap.of(ResourceScopeMap.FS, ResourceScope.none())))
        .isTrue();
    // 子级不表态 ⇒ 运行期落到下一层默认，无从证明"不比父级宽" ⇒ 保守判否（fail-closed）
    assertThat(parent.covers(ResourceScopeMap.empty())).isFalse();
    // 子级表态为"不限" ⇒ 明显更宽
    assertThat(parent.covers(ResourceScopeMap.of(ResourceScopeMap.FS, ResourceScope.unlimited())))
        .isFalse();
  }

  @Test
  void coversIgnoresNamespacesTheParentHasNoOpinionAbout() {
    ResourceScopeMap parent = ResourceScopeMap.empty();
    // 父级什么都没说 ⇒ 子级怎么表态都不构成越权（表成更窄是收缩、表成不限是"与我无关"）
    assertThat(parent.covers(ResourceScopeMap.of(ResourceScopeMap.FS, ResourceScope.unlimited())))
        .isTrue();
    assertThat(parent.covers(ResourceScopeMap.empty())).isTrue();
  }

  @Test
  void constructorRejectsBlankNamespaceAndNullScope() {
    assertThatThrownBy(() -> ResourceScopeMap.of("  ", ResourceScope.none()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("命名空间名");
    // HashMap 允许 null 值（Map.of 会先自己炸——那样测的就不是本类了）
    java.util.Map<String, ResourceScope> withNull = new java.util.HashMap<>();
    withNull.put(ResourceScopeMap.FS, null);
    assertThatThrownBy(() -> ResourceScopeMap.of(withNull))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining(ResourceScopeMap.FS);
  }

  @Test
  void summaryListsNamespaceRoots() {
    ResourceScopeMap map =
        ResourceScopeMap.of(Map.of(ResourceScopeMap.FS, ResourceScope.of("/srv/work")));
    assertThat(map.summary()).isEqualTo("fs=/srv/work");
  }
}
