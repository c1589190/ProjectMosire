package io.mosire.workspace;

import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.tool.ToolContext;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@code workspace_info} 的内容渲染（设计 §5.11）：回答"工作区是什么、我有哪些挂载、纪律怎么用"。
 *
 * <p><b>为什么文案单独一个文件</b>：工具骨架（schema / spec / manifest / 异常转码）是判定相关代码，面向模型的文案会随用户反馈反复润色。
 * 分开之后"改措辞"碰不到骨架，读的人也不必在两件事之间来回跳。
 *
 * <p><b>只输出调用者自己的东西</b>（设计 §5.11 判据的负向一半）：挂载清单取 {@link WorkspaceRegistry#mounts(String)}——它按 {@code
 * user} 索引，正是唯一解析点用的同一张切片，因此"别人的挂载"在结构上就取不到。身份解不出（空 / {@link AgentIdentity#UNKNOWN} 的中立 id）⇒
 * 按"零挂载"回答，不拿一个不属于任何人的切片冒充"你的"。
 *
 * <p><b>绝不输出物理落点</b>（A7/G4）：本类只读 {@link Mount#name()} / {@link Mount#kind()} / {@link
 * Mount#capabilities()} / {@link Mount#origin()}，<b>从不碰</b> {@link Mount#dir()}——"能不能当 {@code
 * parent}"只取决于 "这个挂载在不在你的切片里 + 它有没有能力"，与它落在盘上哪里无关。
 *
 * <p><b>不声明资源面 ⇒ 不碰资源 SPI</b>（设计 §5.11 资源声明段）：本类不调判定者的任何方法。最需要这条帮助的调用者恰好是"面空/未表态"
 * 的那一类人，前置闸要是把它拒了，这个工具就没有存在意义。
 */
final class WorkspaceInfo {

  /**
   * 没有任何挂载时的回答（判据要求"清晰回答，不是报错"）：说清"你没有地" + "找谁要"。
   *
   * <p>文案里不出现任何具体名字——模型此刻本来就一个名字都不知道，这正是要解决的问题。
   */
  private static final String NO_MOUNT_ANSWER =
      "【你的挂载】0 个：你还没有工作区，现在没有任何一块地可以读写。\n"
          + "【没有工作区？】让上级给你挂一块：子 Agent 找父 Agent（父为子挂载）；主 Agent 与决策人由宿主在装配期挂。"
          + "挂载一到，你手上那些按工作区工作的工具立刻可用。\n";

  private WorkspaceInfo() {
    throw new AssertionError("No instances");
  }

  /** 渲染整段回答（三段：你是什么 / 你的挂载 / 怎么用；零挂载时第三段换成"没有工作区？"）。 */
  static String render(WorkspaceRegistry registry, ToolContext context) {
    Objects.requireNonNull(registry, "registry");
    String caller = callerKey(context);
    List<Mount> mounts = caller.isEmpty() ? List.of() : registry.mounts(caller);
    StringBuilder text = new StringBuilder();
    text.append("【你是什么】工作区 = 你能读写的一块「地」：地址是虚拟路径，形如 <挂载名>/<相对路径>（例：")
        .append(exampleOf(mounts))
        .append("）。你只见虚拟路径——物理目录对你不可见，也不会出现在任何工具输出里。\n");
    if (mounts.isEmpty()) {
      return text.append(NO_MOUNT_ANSWER).toString();
    }
    text.append("【你的挂载】").append(mounts.size()).append(" 个（只有你自己的：换一个身份来看，这些名字不会出现）：\n");
    for (Mount mount : mounts) {
      text.append("- 虚拟名=")
          .append(mount.name().value())
          .append(" 纪律=")
          .append(mount.kind())
          .append(" 能力=")
          .append(WorkspaceTools.renderCapabilities(mount.capabilities()))
          .append(" 来源=")
          .append(mount.origin())
          .append(' ')
          .append(parentHint(mount))
          .append('\n');
    }
    text.append("（每个条目的虚拟名就是虚拟路径的第一段；")
        .append(WorkspaceTools.MOUNT_WORKSPACE)
        .append(" 的 parent 只接受这些名字——不接受物理路径。以某个名字作 parent 新建的工作区算它的派生：")
        .append("parent 被卸载时，派生出来的那些一并失效。）\n");
    text.append("【怎么用】纪律写在挂载上，决定这块地能怎么动：\n")
        .append("- FILE：整文件——按虚拟路径读写整个文件。\n")
        .append("- LOG：追加型——只能追加与作废（条目不可变），永远不就地改。\n");
    appendOtherDisciplines(text, mounts);
    text.append("某个挂载上具体能做什么，由它声明的能力 id 决定（能力 id 是使用它的那族工具自己的定义）。\n");
    return text.toString();
  }

  /**
   * 调用者身份键（<b>与工作区其余工具、以及下游工具族同一口径</b>）：{@code context.identity().instanceId()}。
   *
   * <p>身份由宿主绑定，模型给不出也改不了。解不出（空串，或 {@link AgentIdentity#UNKNOWN} 那个中立 id）⇒ 返回空串 ⇒ 调用点按"零挂载"
   * 回答：宁可说"你没有工作区"，也不拿一个不属于任何人的切片冒充"你的挂载"。
   */
  static String callerKey(ToolContext context) {
    AgentIdentity identity = context == null ? null : context.identity();
    if (identity == null) {
      return "";
    }
    String id = identity.instanceId() == null ? "" : identity.instanceId().strip();
    return id.isEmpty() || AgentIdentity.UNKNOWN.instanceId().equals(id) ? "" : id;
  }

  /**
   * 能否作 {@code parent}（口径照 {@code mount_workspace} 的落点形态）。
   *
   * <p>切片里的挂载名都可用；唯一的例外是"没有声明任何能力"的挂载——解析它必被 A8 具名拒（"能力是唯一的决定点"，一个能力都不给等于谁都不放行），
   * 这里如实标否，免得模型拿到一个注定失败的名字。
   */
  private static String parentHint(Mount mount) {
    return mount.capabilities().isEmpty() ? "可作 parent=否（该挂载没有声明任何能力，解析必被拒）" : "可作 parent=是";
  }

  /** 只有调用者真的拥有该纪律的挂载时才解释它（避免把用不上的词汇塞给模型）。 */
  private static void appendOtherDisciplines(StringBuilder text, List<Mount> mounts) {
    Set<Kind> kinds = EnumSet.noneOf(Kind.class);
    for (Mount mount : mounts) {
      kinds.add(mount.kind());
    }
    if (kinds.contains(Kind.SCRATCH)) {
      text.append("- SCRATCH：通用暂存区。\n");
    }
    if (kinds.contains(Kind.REFERENCE)) {
      text.append("- REFERENCE：只读引用（按只读对待，不写）。\n");
    }
  }

  /**
   * 路径形态示例：优先用调用者自己的一个 FILE 挂载名（示范"第一段就是挂载名"，不给任何可能被误当真的假名字）。
   *
   * <p>零挂载时用 {@code <挂载名>} 占位——此刻还没有任何真名字可用，编一个出来正好会重演"拿工具名当挂载名猜"的事故。
   */
  private static String exampleOf(List<Mount> mounts) {
    for (Mount mount : mounts) {
      if (mount.kind() == Kind.FILE) {
        return mount.name().value() + "/notes/hello.md";
      }
    }
    return mounts.isEmpty()
        ? "<挂载名>/notes/hello.md"
        : mounts.get(0).name().value() + "/notes/hello.md";
  }
}
