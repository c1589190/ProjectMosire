package io.mosire.agentlib.permission;

/**
 * <b>调用者的命令档位</b>（2026-09-13 用户裁决）：决定"哪些命令需要审批"，与 {@link
 * AccessToken}（工具<b>可用性</b>的等级标量）是<b>两个维度</b>。
 *
 * <p>两档语义（对经"命令闸"的工具生效，今天 = {@code bash}）：
 *
 * <ul>
 *   <li>{@link #FULL}（<b>完全权限</b>，主 Agent 缺省）：分类器判 <i>直放</i> 的命令<b>直接执行</b>；判 <i>需审批</i>
 *       的（系统敏感区）仍要核实确认；判 <i>硬拒</i> 的照旧硬拒（<b>两档都一样</b>——硬拒不可审批，档位改不了它）。
 *   <li>{@link #LIMITED}（<b>不完全权限</b>，子 Agent 缺省）：<b>所有</b>命令都要审批（连"直放"档也提升为审批）；硬拒照旧。 审批人 =
 *       沿派生链向上第一个 {@code FULL} 的 Agent（它真的跑一轮判断，见 {@code SuperiorJudgeGate}）， 链上没有 {@code FULL} ⇒
 *       到客户端/人。
 * </ul>
 *
 * <p><b>敏感区永远到人</b>：分类器判"需审批"的那一类（{@code systemctl}/包管理/写 {@code /etc}…）<b>不</b>由上级 Agent
 * 代批——上级判定只覆盖 {@code LIMITED} 提升上来的那些。这条是用户裁决，不是实现细节。
 *
 * <p><b>单调性</b>：档位随派生向下<b>只减不增</b>（{@link #covers}）——父 {@code FULL} 可以给子 {@code FULL} 或 {@code
 * LIMITED}；父 {@code LIMITED} 只能给子 {@code LIMITED}。与 {@code PermissionChecker.isSubset} 同口径。
 */
public enum CommandMode {

  /** 完全权限（主 Agent 缺省）：除系统敏感区外都给过。 */
  FULL(1),

  /** 不完全权限（子 Agent 缺省）：所有命令都要审批。 */
  LIMITED(0);

  private final int rank;

  CommandMode(int rank) {
    this.rank = rank;
  }

  /** 本档位是否<b>够得着</b>给下级授予 {@code child}（{@code FULL} 覆盖两档，{@code LIMITED} 只覆盖自己）。 */
  public boolean covers(CommandMode child) {
    return child != null && rank >= child.rank;
  }

  /** 配置/线缆上的取值名（小写；{@code full|limited}）。 */
  public String wireName() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }

  /**
   * 解析配置值（大小写不敏感，两端空白容忍）。<b>坏值返回 {@code null}</b>，由调用方决定"响亮失败"还是走缺省—— 本枚举不认识的值（如 {@code
   * "partial"}）绝不静默当成某一档。
   */
  public static CommandMode parse(String wire) {
    if (wire == null) {
      return null;
    }
    String normalized = wire.strip().toLowerCase(java.util.Locale.ROOT);
    for (CommandMode mode : values()) {
      if (mode.wireName().equals(normalized)) {
        return mode;
      }
    }
    return null;
  }
}
