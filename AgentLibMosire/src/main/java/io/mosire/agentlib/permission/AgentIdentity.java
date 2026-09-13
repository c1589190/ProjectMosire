package io.mosire.agentlib.permission;

import java.util.Map;
import java.util.Objects;

/**
 * 一次工具调用的<b>调用者身份</b>：实例级 id + 命令档位 + 派生目标（goal）。
 *
 * <p><b>为什么需要它</b>：{@link AccessToken} 只有三个<b>桶</b>（{@code GUEST/DEFAULT/SYSTEM}），"主 Agent 与全体子
 * Agent 同为 {@code DEFAULT}"（{@code ContextAccessJudge} 的类注释早就记了这笔账）⇒ 审批面答不出"这是谁在问、它被派去干什么"， 而"按档位分流
 * + 沿派生链向上找第一个完全权限的 Agent"两件事都必须按<b>实例</b>办。
 *
 * <p><b>不可自报</b>：身份由<b>宿主</b>在建链/装配时绑定（主 Agent 在运行时装配处、子 Agent 在父侧为它建的 MCP 链接上），模型给不出也改不了——与 {@code
 * ApprovalRequest} 的"全部字段由宿主填"同一条红线。
 *
 * <p><b>缺省值选 {@link #UNKNOWN}</b>（不是 {@code main}）：{@code FULL} 档 + 中立 id，语义 = "没穿透身份的调用者，按本功能引入前的
 * 行为办"（{@code FULL} 恰好等于"没有档位这个概念"时的行为）。把缺省写成 {@code main} 会让"没设身份"的调用者<b>继承主 Agent 的身份</b>
 * ——那正是"审批按 (调用者, classKey) 归类"最怕的串号。
 *
 * @param instanceId 实例级身份（主 Agent 为 {@code main}；子 Agent 为 {@code <模板id>-<hex>}；外部 MCP 面为 {@code
 *     external-mcp}）。同一次会话/同一个 Agent 内稳定，<b>不得</b>含命令明文或密钥
 * @param mode 命令档位（见 {@link CommandMode}）
 * @param goal 该 Agent 的派生目标（子 Agent = 父给它的任务；主 Agent/外部面 = 空串）。上级判定要用它回答"这条命令与它的任务相符吗"，
 *     <b>只进内存态提示面与判定提示</b>，不进事件库
 */
public record AgentIdentity(String instanceId, CommandMode mode, String goal) {

  /**
   * 主 Agent 的实例 id（{@code App} 装配处显式绑定；与 {@code ToolContext} 的 {@code CONFIG_SELF_AGENT_ID} 同值）。
   */
  public static final String MAIN_ID = "main";

  /** 外部 MCP 暴露面的实例 id（对方不是本进程派生的 Agent，不受本进程的档位机制支配）。 */
  public static final String EXTERNAL_ID = "external-mcp";

  /** 未知身份：{@code FULL} + 中立 id（见类 javadoc 的"缺省值选 UNKNOWN"）。 */
  public static final AgentIdentity UNKNOWN = new AgentIdentity("unknown", CommandMode.FULL, "");

  public AgentIdentity {
    Objects.requireNonNull(instanceId, "instanceId");
    Objects.requireNonNull(mode, "mode");
    goal = goal == null ? "" : goal;
    if (instanceId.isBlank()) {
      // 空白 id 会让"按实例归类"退化成事实上的同一桶，构造期拒比事后追责便宜
      throw new IllegalArgumentException("instanceId 不得为空白");
    }
  }

  /** 主 Agent 身份（{@code main} + 给定档位）。 */
  public static AgentIdentity main(CommandMode mode) {
    return new AgentIdentity(MAIN_ID, mode, "");
  }

  /** 子 Agent 身份（实例 id + 档位 + 目标）。 */
  public static AgentIdentity subagent(String instanceId, CommandMode mode, String goal) {
    return new AgentIdentity(instanceId, mode, goal);
  }

  /**
   * 外部 MCP 暴露面的身份（{@code external-mcp} + {@link CommandMode#FULL}）。
   *
   * <p>档位取 {@code FULL} 是<b>照旧</b>：外部客户端不是本进程派生的 Agent，本功能引入前它在命令闸上就是"完全权限"
   * （只有系统敏感区要人批）。<b>但身份必须显式</b>——判定闸据 {@code instanceId} 认得出"这不是我派的下级"， 于是不会去替外部客户端的命令背书（见 {@code
   * SuperiorJudgeGate}）。
   */
  public static AgentIdentity external() {
    return new AgentIdentity(EXTERNAL_ID, CommandMode.FULL, "");
  }

  /** 同档位换 id（测试/装配便利）。 */
  public AgentIdentity withMode(CommandMode newMode) {
    return new AgentIdentity(instanceId, newMode, goal);
  }

  /**
   * 主 Agent 身份（档位从<b>运行时缝</b>现读）：缝里有 {@link CommandModeHolder} 就取它的当前档位，没有按 {@link
   * CommandMode#FULL}—— 后者恰好等于"本功能引入前"的行为，也让不经过装配层的调用点（大量测试）逐字不变。
   *
   * <p><b>现读不缓存</b>：HTTP 断点改档要立刻对<b>下一次</b>调用生效，把档位取到字段里就变成"重启才生效"。
   *
   * @param config {@code ToolContext.config} 那个缝（可为 null）
   */
  public static AgentIdentity mainFrom(Map<String, Object> config) {
    Object holder = config == null ? null : config.get(CommandModeHolder.CONFIG_KEY);
    CommandMode mode = holder instanceof CommandModeHolder live ? live.get() : CommandMode.FULL;
    return main(mode);
  }

  /** 是不是"没穿透身份"的缺省件（判定/审计要能把它与真身份分开——它不享受实例级会话放行）。 */
  public boolean isUnknown() {
    return "unknown".equals(instanceId);
  }
}
