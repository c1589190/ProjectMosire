package io.mosire.agentlib.decision;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 决策包的完整运行态快照（U1，上游契约 {@code docs/upstream/agentlib-decision-contract.md} §3.3）。
 *
 * <p><b>它是宿主持久化的最小单位</b>：{@link DecisionPackage} 只承载"包长什么样"（状态 + 整包裁决），
 * 而恢复一个可继续运转的包还需要三样东西——逐条表态的累计 {@link #verdicts()}、提交回执 {@link #receipt()}、到点原因 {@link
 * #expiryReason()}。本记录把四者放在一起，宿主怎么序列化由宿主决定（核心不绑 codec）。
 *
 * <p><b>{@code contentVersion} 从 1 起</b>：任何成功的内容/裁决变更（append / replace / remove / itemDecided / 整包
 * decide）都递增；{@link DecisionPackages} 的宿主 id 入口用它做乐观并发（旧入口不要求版本，内部按当前版本执行）。 版本比较是"看旧包、批新包"的防线：不匹配 ⇒
 * {@code PACKAGE_VERSION_STALE}、零状态变更。
 *
 * <p><b>不可变与一致性</b>：列表在构造期 {@code List.copyOf} 冻结；{@code verdicts} 里每个 index 必须真实存在于 {@code
 * pkg.items()}（否则恢复出来的包会带一条指向不存在条目的表态，属于结构损坏，必须在构造期响亮拒）。
 *
 * @param pkg 包本体（不可变值）
 * @param contentVersion 内容版本（从 1 起；成功的内容/裁决变更递增）
 * @param verdicts 逐条表态的累计（不可变；允许为空 = 还没有任何逐条表态）
 * @param receipt 提交回执；{@code null} = 尚未提交
 * @param expiryReason 到点原因；{@code null} = 不是过期终局（与 {@link DecisionPackages#expiryReason(String)}
 *     同语义）
 */
public record DecisionPackageSnapshot(
    DecisionPackage pkg,
    long contentVersion,
    List<DecisionItemVerdict> verdicts,
    CommitReceipt receipt,
    String expiryReason) {

  public DecisionPackageSnapshot {
    Objects.requireNonNull(pkg, "pkg");
    if (contentVersion < 1L) {
      throw new IllegalArgumentException("contentVersion 从 1 起：内容版本不得小于 1，实际=" + contentVersion);
    }
    Objects.requireNonNull(verdicts, "verdicts");
    for (int i = 0; i < verdicts.size(); i++) {
      Objects.requireNonNull(verdicts.get(i), "verdicts[" + i + "]");
    }
    verdicts = List.copyOf(verdicts);
    Set<Integer> knownIndexes = new HashSet<>();
    for (PackageItem item : pkg.items()) {
      knownIndexes.add(item.index());
    }
    Set<Integer> seenIndexes = new HashSet<>();
    for (DecisionItemVerdict verdict : verdicts) {
      if (!knownIndexes.contains(verdict.index())) {
        throw new IllegalArgumentException(
            "verdicts 引用了包内不存在的条目 index="
                + verdict.index()
                + "（包 "
                + pkg.id()
                + " 共 "
                + pkg.items().size()
                + " 条）——恢复出来的快照不能带指向不存在条目的表态");
      }
      if (!seenIndexes.add(verdict.index())) {
        throw new IllegalArgumentException(
            "verdicts 同一 index 出现多次：index=" + verdict.index() + "（一条只能表一次态）");
      }
    }
    expiryReason = expiryReason == null || expiryReason.isBlank() ? null : expiryReason;
  }

  /** 该快照是否已落定（{@code receipt != null} = 宿主已回执；重复提交应拿回同一份）。 */
  public boolean settled() {
    return receipt != null;
  }
}
