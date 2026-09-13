package io.mosire.agentlib.approval;

import java.util.Objects;

/**
 * 工具对<b>一次调用</b>的自报闸位（S4 的工具内权限 SPI）：{@code AgentTool.gate(context)} 返回它。
 *
 * <p><b>诚实边界（写进契约，不许自称完备）</b>：报出 {@code Ask} 的那个分类器是"人的检查点的<b>分流器</b>， 不是安全边界"（设计
 * §2.1）——静态分类可被变量、别名、{@code eval}、写脚本二次执行绕过。真正的边界是人（审批）、 权限面（工具名级）与将来的沙箱。实现须诚实：报 {@code ALLOW}
 * 就是"我判断这次调用不需要人看"，不是"我已经证明了它安全"。
 *
 * <p><b>硬拒不进审批</b>：{@link Block} 的定义就是"连问都不该问"（防"审批把硬拒洗白"）——它不产生 {@code
 * approval.requested}，通道里根本看不到它。
 */
public sealed interface ToolGate {

  /** 直放（缺省；既有工具行为逐字不变）。 */
  ToolGate ALLOW = new Allow();

  record Allow() implements ToolGate {}

  /**
   * 需审批。{@code classKey} = remember 的键（<b>不是</b>工具名、也<b>不是</b>整条命令），如 {@code
   * bash:ask:systemctl}；会话级放行按 {@code (调用者, classKey)} 归类。{@code summary} 是给人看的一行说明 （只进提示面，不进事件）。
   *
   * <p>{@code kind} = <b>为什么问</b>（见 {@link AskKind}）：决定"谁有资格批"——系统敏感区只能到人， 档位提升可交上级 Agent
   * 判定。工具<b>必须</b>如实报（报 {@code SENSITIVE} 就是"这类只能人批"，不是"我判断更严"）。
   */
  record Ask(String classKey, String summary, AskKind kind) implements ToolGate {

    public Ask {
      Objects.requireNonNull(classKey, "classKey");
      if (classKey.isBlank()) {
        throw new IllegalArgumentException("classKey 不得为空白（它是会话级 remember 的键）");
      }
      summary = summary == null ? "" : summary;
      kind = kind == null ? AskKind.SENSITIVE : kind;
    }

    /** 2 参兼容构造：理由缺省 = {@link AskKind#SENSITIVE}（见该枚举的"缺省取 SENSITIVE"）。 */
    public Ask(String classKey, String summary) {
      this(classKey, summary, AskKind.SENSITIVE);
    }
  }

  /** 硬拒，<b>不进审批</b>。{@code reason} 会原样进 {@code ToolResult.message}（不得含密钥/完整命令）。 */
  record Block(String classKey, String reason) implements ToolGate {

    public Block {
      Objects.requireNonNull(classKey, "classKey");
      Objects.requireNonNull(reason, "reason");
      if (classKey.isBlank()) {
        throw new IllegalArgumentException("classKey 不得为空白");
      }
    }
  }
}
