package io.mosire.workspace;

/**
 * 血缘子树判定（按段前缀，含自身）——M2/M5/M9 的"自己或后代"口径。
 *
 * <p><b>口径与 {@code ContextAccessJudge.inSubtree} 逐字同源</b>（设计 §4.10 M2 明写"照 {@code
 * ContextAccessJudge} 的 subtree 判法"），但本模块<b>不得依赖 Brain</b>，故照口径自己实现一份：
 *
 * <pre>
 * inSubtree(c, t) ⟺ t.equals(c) ∨ t.startsWith(c + "/")
 * </pre>
 *
 * <p><b>{@code + "/"} 不许省</b>：{@code startsWith(c)} 会让兄弟分支 {@code a10} 落进 {@code a1} 的子树——血缘判定直接
 * 崩坏。<b>任一侧为空 ⇒ false</b>（fail-closed：没有路径就不认亲）。身份解析不出时（缺省映射下身份键不是血缘路径）， 子树判定的结果就是"只有自己"，这正是保守的一侧。
 */
public final class Lineage {

  private Lineage() {
    throw new AssertionError("No instances");
  }

  /**
   * {@code candidateKey} 是否落在 {@code ancestorKey} 的子树内（<b>含自身</b>）。
   *
   * @param ancestorKey 参照身份键 / 血缘路径（如 {@code main/a1}）
   * @param candidateKey 待判身份键 / 血缘路径（如 {@code main/a1/b1}）
   */
  public static boolean inSubtree(String ancestorKey, String candidateKey) {
    if (ancestorKey == null || candidateKey == null) {
      return false;
    }
    String ancestor = ancestorKey.strip();
    String candidate = candidateKey.strip();
    if (ancestor.isEmpty() || candidate.isEmpty()) {
      return false;
    }
    return candidate.equals(ancestor) || candidate.startsWith(ancestor + "/");
  }
}
