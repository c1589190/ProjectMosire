package io.mosire.agentlib.decision;

import java.util.Map;
import java.util.Objects;

/**
 * 提交口看到的一条调用（设计 §5.8 冻结形状）：{@link DecisionPackages#commit} 把<b>被批子集</b>还原成这个形状， 交给 {@link
 * DecisionCommitPort}。
 *
 * <p><b>为什么它与 {@link PackageItem} 分开</b>：{@code PackageItem} 是"提议者写的"（带摘要与说明， 面向人与事件面），{@code
 * ResolvedCall} 是"执行者在等的"（只有 index / 工具名 / 明文参数）。 分开之后，"被驳条目永不进 calls"在类型层面就看得见：被驳条目根本没有对应的 {@code
 * ResolvedCall} ——不用指望宿主"记得看那个被拒标志位"（红线 5 / B-2）。
 *
 * <p><b>顺序 = 包内 {@code items()} 顺序</b>（被批子集按该顺序过滤出来）， 使宿主回执里逐条 {@code outcomes} 可以按位置对回条目（见 {@link
 * DecisionCommitPort} 的契约）。
 *
 * @param index 条目在包内的身份（与 {@link PackageItem#index()} 逐字相等）
 * @param toolName 工具名
 * @param args 调用参数明文（顶层不可变快照；宿主提交口只读，禁止改动）
 */
public record ResolvedCall(int index, String toolName, Map<String, Object> args) {

  public ResolvedCall {
    if (index < 0) {
      throw new IllegalArgumentException("index 不得为负（与 PackageItem.index 同口径）：" + index);
    }
    Objects.requireNonNull(toolName, "toolName");
    if (toolName.isBlank()) {
      throw new IllegalArgumentException("toolName 不得为空白");
    }
    Objects.requireNonNull(args, "args");
    for (Map.Entry<String, Object> entry : args.entrySet()) {
      Objects.requireNonNull(entry.getKey(), "args 含 null 键");
      Objects.requireNonNull(entry.getValue(), "args." + entry.getKey() + " 的值为 null");
    }
    args = Map.copyOf(args);
  }
}
