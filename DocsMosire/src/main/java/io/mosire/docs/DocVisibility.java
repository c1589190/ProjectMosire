package io.mosire.docs;

import java.util.Objects;
import java.util.Optional;

/**
 * {@code LOG} 条目的可见性判据——<b>本仓唯一一处</b>（设计 §5.4，不变量 B2/B3）。
 *
 * <p><b>为什么必须只有一处</b>：GUI、工具面、库 API 三种消费者都要回答"这条能不能给他看"。判据若各写一份，迟早出现"GUI 看得见、工具看不见"
 * 这类分叉，而这类分叉不会报错，只会静默泄密。因此这里只提供一个静态方法，任何调用点都调它。
 *
 * <p><b>判据（顺序即优先级，不许随手调换）</b>：
 *
 * <pre>
 * canSee(viewer, entry):
 *   viewer.seesAll == true                                   → true   ← GM 无条件（在判据之前短路，不落 tags）
 *   viewer.actorId != null && entry.owner == viewer.actorId   → true   ← owner-only
 *   entry.tags.contains(viewer.actorId)                       → true   ← 显式指派
 *   viewer.affiliations ∩ entry.affiliations ≠ ∅              → true   ← 按归属
 *   其余                                                      → false  ← fail-closed
 * </pre>
 *
 * <p><b>两条容易写错的细节</b>：
 *
 * <ul>
 *   <li><b>身份解不出 ⇒ 跳过 affiliations 支</b>（只走 tags）："不知道你是谁"不许读成"按归属放行"（设计 §5.4 的 ★）；
 *   <li><b>无主 ≠ 大家都能看</b>：{@code owner} 为空只是"没有 owner 命中"，仍要过 tags/affiliations 两支。
 * </ul>
 *
 * <p><b>{@code FILE} 纪律没有条目级可见性</b>（设计 §5.4 末段）：文件能不能被看见 = 挂载 + 资源面（{@code docs} 前缀）决定， 不在文件内部再搞一套
 * tags/affiliations（避免两处判）。
 */
public final class DocVisibility {

  private DocVisibility() {
    throw new AssertionError("No instances");
  }

  /**
   * 观看者能否看见这条条目（B3：fail-closed）。
   *
   * @param viewer 观看者（非 null）
   * @param entry 条目（非 null）
   */
  public static boolean canSee(DocViewer viewer, DocEntry entry) {
    Objects.requireNonNull(viewer, "viewer");
    Objects.requireNonNull(entry, "entry");
    // ① GM 短路在判据之前：不落 tags、不进三支（U3）
    if (viewer.seesAll()) {
      return true;
    }
    // ② owner-only：单决策人私有
    if (entry.owner().isPresent() && entry.owner().get().equals(viewer.actorId())) {
      return true;
    }
    // ③ 显式指派（tags 里就是身份键；actorId 解不出时这一支也不成立——空串不该与"空 tag"撞上）
    if (viewer.resolvable() && entry.tags().contains(viewer.actorId())) {
      return true;
    }
    // ④ 按归属：身份解不出就<b>跳过</b>这一支（fail-closed 的一侧）
    if (!viewer.resolvable()) {
      return false;
    }
    for (String affiliation : entry.affiliations()) {
      if (viewer.affiliations().contains(affiliation)) {
        return true;
      }
    }
    return false;
  }

  /**
   * "取得到"的唯一合成写法（B2 + 设计 §5.5 的"取不到与不可见返回同一个回答"）：不存在 ⇒ 空；可见性判否 ⇒ <b>也</b>空。
   *
   * <p><b>为什么把它写成一个方法而不是让调用方自己 {@code filter}</b>：存在性与可见性一旦分开写，某个调用点顺手 {@code return
   * entry}（忘了判可见）就会让工具面变成"文档存在性探测器"（不泄露存在性是设计 §5.5 的硬要求）。库 API 与工具面都走这一个入口。
   */
  public static Optional<DocEntry> visible(DocViewer viewer, DocEntry entry) {
    return canSee(viewer, entry) ? Optional.of(entry) : Optional.empty();
  }
}
