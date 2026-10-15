package io.mosire.agentlib.decision;

import java.util.Map;
import java.util.Objects;

/**
 * 包内的一条待决调用（设计 §5.8 冻结形状）。
 *
 * <p><b>为什么同时带 {@code args} 与 {@code argsDigest}</b>：两者面向<b>不同的人</b>—— {@code args}
 * 是提交口落盘时要用的<b>明文</b>（只有执行侧需要它），{@code argsDigest} 是<b>脱敏摘要</b> （给人看的提示面、事件面、日志只允许出现它）。设计 §5.5
 * 把"事件载荷不含明文参数/命令"写成硬约束， 而"明文与摘要共用同一个字段"正是这类泄漏的标准成因，所以两个字段都在、各走各的面。
 *
 * <p><b>{@code args} 只准进内存态与提交口</b>：它可能含密钥（命令、令牌），因此不得进日志、事件与异常消息。 本类<b>不做</b>脱敏（脱敏发生在宿主算 {@code
 * argsDigest} 的那一步），只负责把明文原样交给 {@link DecisionCommitPort}。
 *
 * <p><b>{@code index} 是条目在包内的身份</b>：{@link Decision#approvedIndexes()} 用它表达"批哪些"， {@link
 * ResolvedCall#index()} 用它把被批子集对回本条目。取值口径由宿主定（本仓建议 0 起）， 本类只要求非负；"同一包内唯一"由 {@link
 * DecisionPackages#propose} 在收包时校验 （index 重复会让"批第 2 条"有歧义，属于必须在发编号前拦下的形状错误）。
 *
 * <p><b>不可变性与 null 口径</b>：{@code args} 做<b>浅层</b>不可变快照（{@code Map.copyOf}）， 顶层不允许 null 键/值——与
 * {@code ToolDef}/{@code ToolCall} 同一口径（先逐项点名再 copy：{@code Map.copyOf} 撞上 null 只抛无消息的
 * NPE，调用方无从知道坏的是哪条参数）。嵌套层里的 null 值合法、照常通过； 嵌套的容器本身不做深拷贝——提议之后调用方不得再改它们的结构。
 *
 * @param index 条目在包内的身份（非负；同一包内唯一）
 * @param toolName 工具名（宿主自报，不是模型给的字符串）
 * @param args 调用参数明文（只进内存态与提交口；顶层不可变快照，不含 null 键/值）
 * @param argsDigest 脱敏摘要（提示面/事件面只带它，不带明文）
 * @param summary 给人看的一行说明（提示面专用，不进事件面）
 */
public record PackageItem(
    int index, String toolName, Map<String, Object> args, String argsDigest, String summary) {

  public PackageItem {
    if (index < 0) {
      throw new IllegalArgumentException("index 不得为负：它是条目在包内的身份（" + index + "）");
    }
    Objects.requireNonNull(toolName, "toolName");
    if (toolName.isBlank()) {
      throw new IllegalArgumentException("toolName 不得为空白（它决定这条调用走哪条闸链）");
    }
    Objects.requireNonNull(args, "args");
    for (Map.Entry<String, Object> entry : args.entrySet()) {
      Objects.requireNonNull(entry.getKey(), "args 含 null 键");
      Objects.requireNonNull(entry.getValue(), "args." + entry.getKey() + " 的值为 null");
    }
    args = Map.copyOf(args);
    argsDigest = argsDigest == null ? "" : argsDigest;
    summary = summary == null ? "" : summary;
  }
}
