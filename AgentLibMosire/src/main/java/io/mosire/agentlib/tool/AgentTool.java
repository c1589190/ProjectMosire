package io.mosire.agentlib.tool;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import java.util.Map;
import java.util.Set;

/**
 * 工具唯一接口：一个 Agent 可执行动作的最小描述 + 执行器。
 *
 * <p>实现约定：
 *
 * <ul>
 *   <li>{@link #name()} 必须全局唯一（注册进 {@link ToolRegistry} 时重复即抛错）；
 *   <li>{@link #jsonSchema()} 为 JSON Schema（output 到 LLM 工具定义）；
 *   <li>{@link #spec()} 声明三要素（默认 {@link ToolSpec#DEFAULT}：guest 级、非敏感非破坏）——
 *       不声明不报错，但敏感工具必须显式声明，否则永远不会被放行；
 *   <li>实现自身不做<b>工具名级</b>权限判断（那是 {@link ToolExecutionGuard} 的边界职责）；<b>资源级</b>判定经注入的 {@link
 *       ToolContext#resources()}（唯一入口），工具不得自行放行——详见 {@link #resources()}。
 * </ul>
 */
public interface AgentTool {

  String name();

  /** 面向模型的自然语言描述；缺省空串（模型可读性差，工具应提供）。 */
  default String description() {
    return "";
  }

  /** 工具入参的 JSON Schema；缺省为无参数。 */
  default Map<String, Object> jsonSchema() {
    return Map.of("type", "object", "properties", Map.of());
  }

  default ToolSpec spec() {
    return ToolSpec.DEFAULT;
  }

  /**
   * 本工具能被哪些角色调用（§A 角色维的<b>声明侧</b>，设计 §4.2）。
   *
   * <p><b>空集 = 本维不表态 = 任意角色都能调</b>（= 本维引入前的行为）。为什么空集读作"不表态"而不是"谁都不能用"： 既有工具（bash / skill_* / doc_*
   * / workspace_*）一个都没声明角色，若空集读作"谁都不能用"，本维一引入就把全部既有 工具打死。所以本维的正确切法是：<b>不表态 ⇒ 不构成约束；非空 ⇒
   * 参与判定且只收窄</b>。
   *
   * <p>★ 与调用者侧的空集读法<b>相反</b>（{@link io.mosire.agentlib.permission.AgentPermissionSet#roles()} 的空集 =
   * ⊥ = "谁都不是"，fail-closed）——身份没有"回落"，资源面才有。两侧不共用一个 {@code any()}，语义表见 {@link
   * io.mosire.agentlib.permission.RoleSet}。
   *
   * <p>"不让任何角色用"这个语义<b>不提供</b>：那种工具根本不该注册（要停用走白名单/黑名单）。
   *
   * <p>角色名对 AgentLib 是<b>不透明标签</b>（宿主用枚举实现、以 {@code name()} 落配置与事件），通用层不认识 {@code GM}/{@code
   * DM}。声明里的空白名不匹配任何调用者（fail-closed），不是"退回不表态"。
   *
   * <p>返回值必须<b>非 null</b>（缺省 {@code Set.of()}）：判定点对 null 按"不表态"读（同 {@link #spec()} 的 null ⇒ {@code
   * ToolSpec.DEFAULT} 口径），那只是防呆，不是允许返回 null。
   */
  default Set<String> roles() {
    return Set.of();
  }

  /**
   * 本工具对这次调用的自报闸位；缺省 {@link ToolGate#ALLOW}（既有工具行为逐字不变）。
   *
   * <p>分类器是人的检查点的<b>分流器，不是安全边界</b>（见设计 §2.1）——实现须诚实，不得自称"完备"： 报 {@code ALLOW}
   * 的含义是"我判断这次调用不需要人看"，不是"我证明了它安全"。
   *
   * <p>本 SPI 是 S4 的<b>唯一</b>新增判定入口：工具报"要不要人看"，而"放不放行"仍归 {@link ToolCallAuthorizer}（硬拒 →（审批闸）→
   * 执行）。工具<b>不得</b>据此自行放行——{@code Block} 由入口转成 {@code COMMAND_BLOCKED} 且不进审批。
   */
  default io.mosire.agentlib.approval.ToolGate gate(ToolContext context) {
    return io.mosire.agentlib.approval.ToolGate.ALLOW;
  }

  /**
   * 本工具<b>声明的资源面</b>（S5-C 声明式 SPI）：命名空间 → 缺省策略。缺省 {@link ResourceManifest#NONE} （一个命名空间都不声明）。
   *
   * <p><b>声明是必须的</b>：判定点对"未声明的命名空间"一律拒（"没声明"读作"我不知道这类资源怎么判"，而不是"随便用"）。所以 用 {@link
   * ToolContext#resources()} 的工具必须在这里声明自己的命名空间，用不到的资源 SPI 的工具保持缺省、行为逐字不变。
   *
   * <p><b>谁判、谁调</b>：宿主（{@code ToolCallAuthorizer}）在<b>执行前</b>用"调用者权限集 × 本声明"建出判定者注入 {@link
   * ToolContext#resources()}；工具只在真正读写前<b>调</b> {@code require}——自行放行（不调、或自己判断）等于跳过唯一入口， 本 SPI
   * 的存在意义就是"工具不得自裁"。
   *
   * <p><b>声明的是"我拥有什么"而不是"我要什么"</b>：一次调用的可达面上限仍由调用者权限集决定（见 {@link
   * io.mosire.agentlib.permission.ResourcePolicy} 的"缺省不是封顶"）。
   */
  default ResourceManifest resources() {
    return ResourceManifest.NONE;
  }

  /**
   * 落 {@code tool.call} 事件时用的参数视图。<b>缺省原样返回</b>（既有工具行为逐字不变）。
   *
   * <p>需要脱敏的工具（如 bash）覆写它以只暴露摘要——事件库应能"对账"但不默认存明文。
   *
   * <p><b>为什么是工具自报而不是管线判断</b>：只有工具自己知道哪几个参数是"模型自造的任意字符串"（能变成任意副作用的只有命令文本， 而 cwd/timeout
   * 不是），管线按工具名硬编码会造出第二套知识；本 SPI 是这条知识的唯一落点。
   *
   * <p><b>调用点与顺序</b>：{@code AgentPipeline.executeToolCall} 在<b>调 authorizer 之前</b>落 {@code
   * tool.call} 事件——故脱敏必须发生在这个 emit 点，落在审批器里等于没落（事件早就写出去了）。
   *
   * <p><b>契约</b>：返回<b>非 null</b> 的 Map（调用点对 null 退回原始 args，见管线注释——那只是防呆，不是允许返回 null）；
   * 实现<b>不得</b>回传它声称要脱敏的内容。
   */
  default Map<String, Object> ledgerArgs(ToolContext context) {
    return context.arguments();
  }

  /**
   * 执行一次调用。实现必须把参数、IO 错误映射为 {@link ToolResult}（成功或带 code 的失败）， 不抛异常吞掉边界错误（意外异常可抛，由审计记录后转为工具层错误）。
   */
  ToolResult execute(ToolContext context);
}
