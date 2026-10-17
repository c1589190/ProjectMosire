package io.mosire.agentlib.decision;

import java.util.List;
import java.util.Optional;

/**
 * 决策包五工具的只读取数口。
 *
 * <p><b>它解决什么</b>：{@link DecisionPackageTools} 的写路径早已通过 {@link DecisionSubmissionPort}
 * 交给宿主，但读路径过去仍要求一个 concrete {@link DecisionPackages}
 * 实例。宿主的包状态若在自身持久化层（例如世界状态快照）里，五工具启动后拿不到它，只能看到一张空内存表。本接口把五个实际读能力抽出来，宿主可 用当前 head 的快照直接实现，不需要创建
 * {@link DecisionPackages}，也不需要 {@code restore}。
 *
 * <p><b>读什么</b>：
 *
 * <ul>
 *   <li>{@link #find(String)} 是历史入口：旧 tick、已终局、已过期的包都要能按 id 读到；未知 id ⇒ 空；
 *   <li>{@link #pending()} / {@link #all()} 是五工具列表用的<b>投影</b>：允许宿主按当前 tick 或领域状态裁剪，也可以排除 {@code
 *       MERGED}；它们<b>不</b>承诺返回世界全历史，避免 GM 列表混入旧包；
 *   <li>{@link #verdicts(String)} 返回该包已表态的条目，按包内条目顺序；未表态条目不出现；未知 id ⇒ 空表；
 *   <li>{@link #expiryReason(String)} 与既有 {@link DecisionPackages#expiryReason(String)} 同义；没有到点原因 ⇒
 *       空。
 * </ul>
 *
 * <p><b>★ 只读边界</b>：新宿主实现不得因读改写包状态、发事件或写审计；{@code full=true} 的明文读取审计仍由工具层的 {@link DecisionReadAudit}
 * 负责。端口不做身份/权限判断，也不暴露 {@code contentVersion} 或任何写能力。
 *
 * <p><b>兼容例外</b>：{@link DecisionPackages} 实现本接口时仍保留其既有的惰性过期读行为和逐条表态投影——那是 Brain
 * 内存适配器的旧兼容口径，不是新宿主实现应模仿的默认语义。新宿主（例如 Simos core）应直接返回 {@link DecisionPackageSnapshot#verdicts()}
 * 的纯累计结果。
 *
 * <p>所有返回值必须非 {@code null}；实现方对 {@code null} 列表的空值归一由工具层按失败处理，不静默当成“没有包”。
 */
public interface DecisionPackageReadPort {

  /**
   * 按 id 取包快照。
   *
   * <p>这是历史读取入口：实现不得只查当前 tick 或只查未终局包。未知 id ⇒ {@link Optional#empty()}。
   *
   * @param packageId 包 id（非 null）
   * @return 包快照；未知 id ⇒ 空
   */
  Optional<DecisionPackage> find(String packageId);

  /**
   * 五工具列表的“待裁决”投影，按实现自己的登记顺序返回。
   *
   * <p>允许宿主按当前 tick/领域状态裁剪；不需要返回世界全历史。未知或缺失 ⇒ 空表。
   *
   * @return 待裁决包投影；不得为 {@code null}
   */
  List<DecisionPackage> pending();

  /**
   * 五工具列表的完整读取投影（用于补充 {@link #pending()} 未覆盖的分段裁决/已批未执行包）。
   *
   * <p>允许宿主按当前 tick/领域状态裁剪；不需要返回世界全历史。未知或缺失 ⇒ 空表。
   *
   * @return 包列表投影；不得为 {@code null}
   */
  List<DecisionPackage> all();

  /**
   * 该包已表态的条目。
   *
   * <p>返回顶层 {@link DecisionItemVerdict}，与 {@link DecisionPackageSnapshot#verdicts()} 同形；未表态条目不出现。未知
   * id ⇒ 空表。
   *
   * @param packageId 包 id（非 null）
   * @return 已表态条目；不得为 {@code null}
   */
  List<DecisionItemVerdict> verdicts(String packageId);

  /**
   * 该包最近一次到点的原因。
   *
   * <p>未知 id、没有到点原因或实现没有这条信息 ⇒ {@link Optional#empty()}。
   *
   * @param packageId 包 id（非 null）
   * @return 到点原因；没有 ⇒ 空
   */
  Optional<String> expiryReason(String packageId);
}
