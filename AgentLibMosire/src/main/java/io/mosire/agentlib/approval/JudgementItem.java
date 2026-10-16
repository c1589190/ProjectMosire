package io.mosire.agentlib.approval;

import java.util.Map;
import java.util.Objects;

/**
 * 上级判定提示里的<b>一条明文明细</b>：工具名 + 人读摘要 + 参数明文。
 *
 * <p><b>为什么需要它</b>：{@link ApprovalRequest} 只带 {@code summary}/{@code digest}——对单条命令够用（摘要里就是那条命令），
 * 对<b>决策包</b>不够：包的 {@code summary} 只有"几条、哪些工具"，上级（一个 LLM）看得到"要改"，看不到"改成什么"。 用户 2026-10-16
 * 对这件事的原话是「肯定展示全部内容啊」。逐条明细因此由装配面（决策包桥）另路提供， 不塞进 {@code ApprovalRequest} 的载荷——那条载荷是有意脱敏的（同 {@link
 * ApprovalEventTypes#REQUESTED} 的口径）。
 *
 * <p><b>★ 密钥纪律（本类为什么会带 {@code args} 明文）</b>：{@code args} 是<b>明文</b>，只准进两处：① 上级判定的 <b>提示</b>（内存态、一次
 * HTTP 调用，同 {@code ApprovalRequest.summary} 的档位）；② 决策人的回环 HTTP 详情面。它 <b>不得</b>进事件库、{@code
 * ApprovalRequest}、日志与异常消息——持有者（{@link SuperiorJudgeDetailRegistry}）的生命周期就是这句话的执行： 登记 → 判定读完 →
 * <b>立刻释放</b>。
 *
 * <p><b>不做深拷贝</b>：{@code args} 只做顶层不可变快照（同 {@code PackageItem} 的口径）——它是从包内条目原样转过来的，
 * 深拷贝在这里不增加任何安全性，只会多一份可能分叉的副本。
 *
 * @param toolName 工具名（公开面：它本来就进事件载荷）
 * @param summary 人读摘要（由建包方在条目登记那一刻从 {@code args} 派生；唯一实现点是 {@link
 *     io.mosire.agentlib.decision.DecisionItemDigest#itemSummary(String, java.util.Map)}）
 * @param args 参数<b>明文</b>（只进提示与决策面；顶层不可变快照，键/值不得为 null）
 */
public record JudgementItem(String toolName, String summary, Map<String, Object> args) {

  public JudgementItem {
    Objects.requireNonNull(toolName, "toolName");
    if (toolName.isBlank()) {
      throw new IllegalArgumentException("toolName 不得为空白（它决定这条调用走哪条闸链）");
    }
    summary = summary == null ? "" : summary;
    Objects.requireNonNull(args, "args");
    for (Map.Entry<String, Object> entry : args.entrySet()) {
      Objects.requireNonNull(entry.getKey(), "args 含 null 键");
      Objects.requireNonNull(entry.getValue(), "args." + entry.getKey() + " 的值为 null");
    }
    args = Map.copyOf(args);
  }
}
