package io.mosire.agentlib.decision;

import java.util.Objects;

/**
 * 一次裁决回填的结果（决策面 HTTP 的回执面；设计 §5.10 的"回执里给<b>实际生效</b>的裁决"）。
 *
 * <p><b>为什么"没有回执"也要是一个正经结果</b>：裁决被记下（{@code DECIDED}）与"被批子集落了盘"是两件事。提交口没装配、 或提交口抛了异常（{@code
 * COMMIT_FAILED}）时，包<b>保持 {@code DECIDED}</b>——这时既不能报成落定，也不能把整次裁决报成失败 （人做的那次裁决确实生效了）。所以 {@code
 * receipt == null} 必须带一句诚实的 {@link #commitNote()}， 而不是让调用方看到一片空白自己去猜。
 *
 * <p><b>密钥纪律</b>：{@code commitNote} 只写"为什么没有回执"（id / 拒码 / 一行理由），<b>不得</b>抄参数明文——它是给人读的 回执面，而明文只在包内
 * {@code args} 与决策面的详情响应里。
 *
 * @param pkg 回填后的包快照（{@code state != PROPOSED}；裁决就在 {@link DecisionPackage#decision()} 里）
 * @param receipt 提交回执（{@code null} = 这次没有落定，见 {@link #commitNote()}）
 * @param commitNote 提交侧的诚实说明（{@code receipt == null} 时不得为空白）；不含明文
 */
public record DecisionSubmission(DecisionPackage pkg, CommitReceipt receipt, String commitNote) {

  public DecisionSubmission {
    Objects.requireNonNull(pkg, "pkg");
    if (pkg.state() == PackageState.PROPOSED) {
      throw new IllegalArgumentException(
          "回填后的包不可能是 PROPOSED（那是「还没人裁」）：id=" + pkg.id() + " state=" + pkg.state());
    }
    if (pkg.decision() == null) {
      throw new IllegalArgumentException("回填后的包必须带裁决（谁批的、批了哪些都要能对账）：id=" + pkg.id());
    }
    commitNote = commitNote == null ? "" : commitNote;
    if (receipt == null && commitNote.isBlank()) {
      throw new IllegalArgumentException("没有回执时必须说明原因（commitNote）：不许把「没落定」留成一片空白 id=" + pkg.id());
    }
  }
}
