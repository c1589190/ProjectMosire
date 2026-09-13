package io.mosire.agentlib.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ResourceScope} 的取值语义（S5-B）：<b>段边界包含</b>、两种空（不限 vs 哪里都不许）、两道集运算（求交/涵盖）。
 *
 * <p>判别性重点（这几条一旦回退成"字符串前缀"或"空集退化"，用例必须红）：
 *
 * <ul>
 *   <li>{@code /srv/work} 不含 {@code /srv/work-evil}（字符串前缀会含，段边界不会）；
 *   <li>"不限"与"空集"在 {@code allows} 上取相反值（谁退化成对方都会被抓）；
 *   <li>{@code narrowTo} 互不相交 ⇒ 空集，{@code covers} 对"更宽/更窄/不相交"三种关系分别判对。
 * </ul>
 */
class ResourceScopeTest {

  @Test
  void segmentBoundaryIsNotStringPrefix() {
    ResourceScope work = ResourceScope.of("/srv/work");
    assertThat(work.allows("/srv/work")).isTrue();
    assertThat(work.allows("/srv/work/")).isTrue();
    assertThat(work.allows("/srv/work/sub/file.txt")).isTrue();
    // 这三个是<b>另一个目录</b>：字符串 startsWith 会放进去，段边界不会
    assertThat(work.allows("/srv/work-evil")).isFalse();
    assertThat(work.allows("/srv/work2")).isFalse();
    assertThat(work.allows("/srv")).isFalse();
    assertThat(work.allows("/srv/workshop")).isFalse();
  }

  @Test
  void rootPrefixCoversEverything() {
    ResourceScope root = ResourceScope.of("/");
    assertThat(root.allows("/etc/shadow")).isTrue();
    assertThat(root.allows("/")).isTrue();
  }

  @Test
  void multiplePrefixesUnionAndNormalize() {
    ResourceScope scope = ResourceScope.of("/srv//work/", "/data");
    assertThat(scope.prefixList()).containsExactlyInAnyOrder("/srv/work", "/data");
    assertThat(scope.allows("/srv/work/a")).isTrue();
    assertThat(scope.allows("/data/b")).isTrue();
    assertThat(scope.allows("/data2")).isFalse();
  }

  @Test
  void unrestrictedAndNoneAreTwoDifferentValues() {
    ResourceScope any = ResourceScope.unlimited();
    ResourceScope nothing = ResourceScope.none();
    assertThat(any.allows("/anything/at/all")).isTrue();
    assertThat(nothing.allows("/anything/at/all")).isFalse();
    assertThat(nothing.allows("/")).isFalse();
    // 空集在集运算里是"吸收元"：哪里都不许 ∩ 不限 = 哪里都不许
    assertThat(nothing.narrowTo(any)).isEqualTo(nothing);
    assertThat(any.narrowTo(nothing)).isEqualTo(nothing);
  }

  @Test
  void unrestrictedCannotCarryPrefixes() {
    assertThatThrownBy(() -> new ResourceScope(true, java.util.Set.of("/srv")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能带前缀");
    assertThatThrownBy(() -> ResourceScope.of())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("至少一个前缀");
  }

  @Test
  void relativeSegmentsAreRejectedInPrefixes() {
    assertThatThrownBy(() -> ResourceScope.of("/srv/../etc"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'..'");
    assertThatThrownBy(() -> ResourceScope.of(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能为空");
  }

  @Test
  void narrowToKeepsTheNarrowerOfNestedPrefixes() {
    ResourceScope wide = ResourceScope.of("/srv", "/var");
    ResourceScope narrow = ResourceScope.of("/srv/work", "/var");
    ResourceScope intersection = wide.narrowTo(narrow);
    // 嵌套对留窄的（/srv ∩ /srv/work = /srv/work）；完全相同的条目当然保留（/var）
    assertThat(intersection.prefixList()).containsExactlyInAnyOrder("/srv/work", "/var");
    assertThat(intersection.allows("/srv/work/a")).isTrue();
    assertThat(intersection.allows("/var/log")).isTrue();
    assertThat(intersection.allows("/srv/other")).isFalse(); // 只有 /srv/work 那一段是全体的
    assertThat(intersection.allows("/opt")).isFalse();
    // 对称
    assertThat(narrow.narrowTo(wide)).isEqualTo(intersection);
  }

  @Test
  void narrowToDropsEntriesThatOnlyOneSideDeclares() {
    // 只在一侧出现的条目 = 另一侧没有表态"允许它" ⇒ 交集里不许留（留了就是"求交"变"求并"）
    ResourceScope intersection =
        ResourceScope.of("/srv", "/opt").narrowTo(ResourceScope.of("/srv/work"));
    assertThat(intersection.prefixList()).containsExactly("/srv/work");
  }

  @Test
  void narrowToOfDisjointSetsIsExplicitlyEmpty() {
    ResourceScope intersection = ResourceScope.of("/srv").narrowTo(ResourceScope.of("/data"));
    assertThat(intersection.unrestricted()).isFalse();
    assertThat(intersection.prefixList()).isEmpty();
    assertThat(intersection.allows("/srv")).isFalse(); // 空集 = 哪里都不许（不是"不限"）
    assertThat(intersection.allows("/data")).isFalse();
  }

  @Test
  void unrestrictedIsTheNeutralElementOfNarrowing() {
    ResourceScope any = ResourceScope.unlimited();
    ResourceScope work = ResourceScope.of("/srv/work");
    assertThat(any.narrowTo(work)).isEqualTo(work);
    assertThat(work.narrowTo(any)).isEqualTo(work);
  }

  @Test
  void coversIsMonotonicUnderNarrowingAndNotUnderStringPrefix() {
    ResourceScope wide = ResourceScope.of("/srv");
    ResourceScope narrow = ResourceScope.of("/srv/work");
    assertThat(wide.covers(narrow)).isTrue();
    assertThat(narrow.covers(wide)).isFalse();
    assertThat(wide.covers(ResourceScope.unlimited())).isFalse();
    assertThat(ResourceScope.unlimited().covers(wide)).isTrue();
    assertThat(wide.covers(wide)).isTrue();
    // 段边界在单调性上的同一件事：/srv/work 不涵盖 /srv/work-evil
    assertThat(narrow.covers(ResourceScope.of("/srv/work-evil"))).isFalse();
    // 涵盖只看前缀集合（"哪里都不许"被任何受限集涵盖）
    assertThat(wide.covers(ResourceScope.none())).isTrue();
    assertThat(ResourceScope.none().covers(wide)).isFalse();
  }

  @Test
  void ofDirsResolvesRealPathOfExistingRoots(@TempDir Path tempDir) throws Exception {
    Path real = Files.createDirectories(tempDir.resolve("real"));
    Path link = tempDir.resolve("link");
    Files.createSymbolicLink(link, real);
    ResourceScope viaLink = ResourceScope.ofDir(link);
    // 根本身是软链 ⇒ 取 realpath（否则"围栏围的是链接名，写进去的是真目录"的假安全）
    assertThat(viaLink.dirList()).containsExactly(real.toRealPath());
    assertThat(viaLink.allowsDir(real.resolve("a/b"))).isTrue();
  }

  @Test
  void ofDirsAcceptsRootsThatDoNotExistYet(@TempDir Path tempDir) {
    Path missing = tempDir.resolve("future/work");
    ResourceScope scope = ResourceScope.ofDirs(java.util.List.of(missing));
    assertThat(scope.allowsDir(missing.resolve("x"))).isTrue();
    assertThat(scope.dirList()).containsExactly(missing.toAbsolutePath().normalize());
    // 不存在的根拿不到 realpath 那一层保证（只做词法），这是<b>已知</b>的诚实分级，不是这里能补的洞
    assertThat(scope.allows(missing.toAbsolutePath().normalize().toString())).isTrue();
  }

  @Test
  void allowsDirNormalizesCandidateWithoutResolvingSymlinks(@TempDir Path tempDir) {
    Path root = tempDir.resolve("work");
    ResourceScope scope = ResourceScope.ofDir(tempDir);
    assertThat(scope.allowsDir(root.resolve("a/../b"))).isTrue(); // 词法归一后仍在根内
    assertThat(scope.allowsDir(tempDir.resolve("..").resolve("elsewhere"))).isFalse(); // 归一后落到根外
  }

  @Test
  void summaryShowsUnlimitedAsStarElseRoots() {
    assertThat(ResourceScope.unlimited().summary()).isEqualTo("*");
    assertThat(ResourceScope.none().summary()).isEmpty();
    assertThat(ResourceScope.of("/srv/work").summary()).isEqualTo("/srv/work");
  }
}
