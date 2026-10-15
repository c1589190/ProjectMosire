package io.mosire.agentlib.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 角色代数（设计 {@code 设计-角色与统一决策包.md} §4.3.1）的判据——逐条按设计语义写，不是按实现反推。
 *
 * <p>本类只判 {@link RoleSet} 自身的代数与归一：{@code of()} 的响亮失败、去空白/去重/字典序稳定、{@code none()} = ⊥、 {@code
 * narrowTo} = meet、{@code covers} = 子集序、{@code intersects} = 判定（空白项不匹配任何人）、不可变快照、 {@code summary}
 * 升序。判定面的顺序与真入口接线在 {@code PermissionCheckerTest} / {@code ToolExecutionGuardTest}。
 *
 * <p>★ 两侧空集读法相反（类的核心）：本类是<b>类型</b>，只表达 ⊥ 与"一组具体角色"；声明侧的"空集 = ⊤ = 不表态" 由判定点（{@code
 * PermissionChecker} 的 {@code toolRoles.isEmpty()} 分支 / {@code AgentTool.roles()} 的缺省）承担。
 * 所以这里不断言"空集 = 谁都能过"这种在声明侧才成立的读法，只断言 ⊥ 的代数行为。
 */
class RoleSetTest {

  // ---------- of()：响亮失败 ----------

  @Test
  void ofFailsLoudlyOnEmptyAllBlankOrAnyBlankName() {
    assertThatThrownBy(RoleSet::of)
        .as("roles=[] 与“忘了写角色名”在配置面上同形，静默收成 ⊥ 就是漂移无症状")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RoleSet.of((String[]) null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RoleSet.of("")).as("空串名").isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RoleSet.of("   "))
        .as("全空白名")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RoleSet.of((String) null))
        .as("null 名")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RoleSet.of("gm", " "))
        .as("任一名为空白 ⇒ 整次失败（不是悄悄丢掉那一项）")
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------- of()：归一（去空白 / 去重）+ summary 显式升序 ----------

  @Test
  void ofTrimsDeduplicatesAndSummaryIsSorted() {
    RoleSet set = RoleSet.of(" a ", "b", "a");
    assertThat(set.roles()).as("去空白 + 去重（迭代序不保证，见类注释）").containsExactlyInAnyOrder("a", "b");
    assertThat(set.summary()).isEqualTo("a,b");
    assertThat(set).as("集合语义：与声明顺序无关").isEqualTo(RoleSet.of("b", "a"));
    assertThat(set.hashCode()).isEqualTo(RoleSet.of("b", "a").hashCode());
    assertThat(RoleSet.of("b", "a").summary()).as("summary 升序，与声明顺序无关").isEqualTo("a,b");
    assertThat(RoleSet.of(" gm ").roles()).containsExactly("gm");
    assertThat(RoleSet.of("gm").summary()).isEqualTo("gm");
  }

  @Test
  void roleNamesAreCaseSensitiveOpaqueLabels() {
    assertThat(RoleSet.of("GM").summary()).isEqualTo("GM");
    assertThat(RoleSet.of("GM")).isNotEqualTo(RoleSet.of("gm"));
    assertThat(RoleSet.of("A", "b").summary()).as("字典序按字符序").isEqualTo("A,b");
  }

  // ---------- none() = ⊥ ----------

  @Test
  void noneIsBottomAndSummarizesAsEmptyString() {
    assertThat(RoleSet.none().roles()).isEmpty();
    assertThat(RoleSet.none().summary()).as("⊥ 落事件写空串").isEmpty();
    assertThat(RoleSet.none()).isEqualTo(new RoleSet(Set.of()));
    assertThat(new RoleSet(null)).as("null 集合按 ⊥ 读").isEqualTo(RoleSet.none());
  }

  // ---------- narrowTo = meet ----------

  @Test
  void narrowToIsTheMeetWithBottomAbsorbing() {
    RoleSet gm = RoleSet.of("gm");
    RoleSet dm = RoleSet.of("dm");
    RoleSet both = RoleSet.of("gm", "dm");

    assertThat(gm.narrowTo(dm)).as("不相交 ⇒ ⊥").isEqualTo(RoleSet.none());
    assertThat(gm.narrowTo(RoleSet.none())).as("⊥ 吸收").isEqualTo(RoleSet.none());
    assertThat(RoleSet.none().narrowTo(gm)).as("⊥ 吸收（另一侧）").isEqualTo(RoleSet.none());
    assertThat(both.narrowTo(gm)).as("交集").isEqualTo(gm);
    assertThat(gm.narrowTo(both)).as("交换律").isEqualTo(gm);
    assertThat(gm.narrowTo(gm)).as("幂等").isEqualTo(gm);
    assertThat(both.narrowTo(RoleSet.of("dm", "gm"))).isEqualTo(both);
  }

  // ---------- covers = 子集序 ----------

  @Test
  void coversIsTheSupersetOrderWithBottomCoveringOnlyBottom() {
    RoleSet bottom = RoleSet.none();
    RoleSet gm = RoleSet.of("gm");
    RoleSet both = RoleSet.of("gm", "dm");

    assertThat(bottom.covers(bottom)).isTrue();
    assertThat(bottom.covers(gm)).as("★ ⊥ 只涵盖 ⊥（A-1 的代数根）").isFalse();
    assertThat(both.covers(gm)).as("超集涵盖子集").isTrue();
    assertThat(gm.covers(both)).as("子集不涵盖超集").isFalse();
    assertThat(gm.covers(bottom)).as("任何集合都涵盖 ⊥（⊥ 是最小身份）").isTrue();
    assertThat(gm.covers(gm)).as("自反").isTrue();
    assertThat(both.covers(RoleSet.of("gm", "dm"))).isTrue();
  }

  // ---------- intersects = 判定 ----------

  @Test
  void intersectsMatchesDeclaredNamesAfterTrimAndNeverMatchesBlanks() {
    RoleSet gm = RoleSet.of("gm");

    assertThat(gm.intersects(List.of("gm"))).isTrue();
    assertThat(gm.intersects(List.of(" gm "))).as("声明侧去空白后同口径比对").isTrue();
    assertThat(gm.intersects(List.of("dm", "gm"))).isTrue();
    assertThat(gm.intersects(List.of("GM"))).as("大小写敏感（不透明标签）").isFalse();
    assertThat(gm.intersects(List.of("dm"))).isFalse();
    assertThat(gm.intersects(List.of(" "))).as("★ 空白项不匹配任何人 ⇒ 拒（fail-closed），不是“退回不表态”").isFalse();
    assertThat(gm.intersects(List.of("dm", "  "))).isFalse();
    assertThat(gm.intersects(List.of())).as("声明空集 = 本维不表态，判定点先短路").isFalse();
    assertThat(gm.intersects(null)).as("null 声明按空集读").isFalse();
    assertThat(RoleSet.none().intersects(List.of("gm"))).as("⊥ 恒 false（A-3）").isFalse();
    assertThat(RoleSet.none().intersects(List.of())).isFalse();
  }

  @Test
  void intersectsSkipsNullItemsWithoutFailing() {
    List<String> withNull = new ArrayList<>();
    withNull.add(null);
    withNull.add("gm");
    assertThat(RoleSet.of("gm").intersects(withNull)).as("null 项跳过，不抛").isTrue();
    assertThat(RoleSet.of("gm").intersects(Collections.singletonList(null))).isFalse();
    assertThat(RoleSet.of("gm").intersects(List.of("dm", "gm"))).isTrue();
  }

  // ---------- 不可变 ----------

  @Test
  void rolesAreAnImmutableSnapshot() {
    Set<String> source = new HashSet<>();
    source.add("gm");
    RoleSet set = new RoleSet(source);
    source.add("dm");
    assertThat(set.roles()).as("构造时快照：外部改动不影响已建值").containsExactly("gm");

    assertThatThrownBy(() -> set.roles().add("dm"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> set.roles().remove("gm"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> set.roles().clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void ofTakesASnapshotOfTheVarargsArray() {
    String[] names = {"gm", "dm"};
    RoleSet set = RoleSet.of(names);
    names[0] = "hacked";
    assertThat(set.summary()).as("声明数组事后被改不影响已建值").isEqualTo("dm,gm");
  }
}
