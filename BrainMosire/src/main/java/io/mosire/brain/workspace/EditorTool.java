package io.mosire.brain.workspace;

import java.io.IOException;

/**
 * 编辑器能力占位——<b>三期实现，本阶段（P2-5）只留形状</b>。
 *
 * <p><strong>设计意图</strong>：把"改文件"从 shell 里拆出来。{@code bash} 什么都能干，但"改了哪个文件的哪一处"没有结构化语义——
 * 审计只能靠猜、破坏性等级的粒度只能是"整个 bash"；编辑器把动作收敛成可枚举的两种（精确替换 / 补丁），每次调用自带 diff 语义， 才谈得上按动作审计与追责。
 *
 * <p>三期落地之前，模型的改文件手段就是 {@code bash}（受权限门禁约束：sensitive + destructive，默认拒绝、需显式放行）；本接口的存在 不改变二期的任何行为。
 *
 * <p><strong>本文件是占位，不是可用能力</strong>：无实现、不接线、不被任何生产代码引用（P2-5 的验收句即"编译通过"）。与 {@link Workspace}
 * 同理，接口刻意 <b>不</b> extends {@code AgentTool}——那会提前冻结三期的入参 schema、权限三要素与结果渲染；
 * 方法签名与失败语义（找不到/多处命中/补丁不可应用如何表达）同样属<b>草案</b>，留待三期落定。
 */
public interface EditorTool {

  /**
   * 精确字符串替换：把 {@code oldText} 换成 {@code newText}。
   *
   * <p>刻意要求 {@code oldText} 在目标文件里<b>唯一命中</b>：找不到与找到多处都必须是失败，而不是"挑一个最像的"——静默改错地方比直接 报错糟糕得多。
   *
   * @param path 工作区内的相对路径
   * @param oldText 待替换的原文（须唯一命中）
   * @param newText 替换后的文本
   * @throws IOException 读取/写回失败
   */
  void edit(String path, String oldText, String newText) throws IOException;

  /**
   * 应用一段 unified diff 补丁：多处改动一次提交，让审计能按"一次编辑"记账。
   *
   * @param patch unified diff 文本
   * @throws IOException 读取/写回失败
   */
  void applyPatch(String patch) throws IOException;
}
