package io.mosire.agentlib.tool;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import java.util.Map;
import java.util.Objects;

/**
 * 一次工具调用的上下文：调用者身份 + 权限集 + 本次调用参数 + 配置注入点。
 *
 * <p>严格执行：工具所需的配置/凭据与“调用者想传的参数”是两回事——{@link #arguments} 来自 LLM 填入的 JSON 参数；工具自身配置经 {@link #config}
 * 由 Agent 运行时注入，工具实现只能读取、不能反向索取 调用者数据。
 *
 * <p><b>调用者身份有两层</b>（S6 起）：{@link #caller} 是<b>权限等级桶</b>（工具可用性判定用），{@link #identity} 是<b>实例级身份 +
 * 命令档位</b>（"谁在问、按哪一档办"用，见 {@link AgentIdentity}）。两者不可互相替代：桶只有三个取值， 答不出"这个子 Agent 与那个子 Agent 不是同一个"。
 *
 * <p><b>兼容口径</b>：{@link #identity} 缺省 = {@link AgentIdentity#UNKNOWN}（{@code FULL} + 中立
 * id）——恰好等于"没有档位这个概念"时的 行为，故<b>既有的 4 参调用点语义逐字不变</b>（4 参构造保留）。真实身份由宿主在装配处显式绑定：主 Agent 在运行时装配、子 Agent
 * 在父侧为它建的 MCP 链接上、外部面用 {@code external-mcp}（模型与 MCP 客户端都给不出、改不了）。
 *
 * <p><b>资源判定者（S5-C）</b>：{@link #resources} 是工具在真正读写前调 {@code require} 的那个判定者，由宿主在<b>工具调用唯一入口</b>
 * （{@code ToolCallAuthorizer}）注入——它拿调用者权限集与工具声明的资源面当场建出来。工具<b>只调不判</b>：自行放行等于跳过唯一入口。 缺省 {@link
 * ResourceAuthorizer#denying()}（fail-closed）：手工拼的上下文里 {@code require} 一律拒，直到宿主注入。
 */
public record ToolContext(
    AccessToken caller,
    AgentPermissionSet permissions,
    Map<String, Object> config,
    Map<String, Object> arguments,
    AgentIdentity identity,
    ResourceAuthorizer resources) {

  public ToolContext {
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(permissions, "permissions");
    config = config == null ? Map.of() : Map.copyOf(config);
    arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    identity = identity == null ? AgentIdentity.UNKNOWN : identity;
    // 缺省 = 无判定者（fail-closed；不是"缺省放行"）——不用资源 SPI 的工具行为逐字不变（它从不调 require）
    resources = resources == null ? ResourceAuthorizer.denying() : resources;
  }

  /** 5 参兼容构造：资源判定者取 {@link ResourceAuthorizer#denying()}（见类 javadoc）。 */
  public ToolContext(
      AccessToken caller,
      AgentPermissionSet permissions,
      Map<String, Object> config,
      Map<String, Object> arguments,
      AgentIdentity identity) {
    this(caller, permissions, config, arguments, identity, null);
  }

  /** 4 参兼容构造：身份取 {@link AgentIdentity#UNKNOWN}（= 本功能引入前的行为，见类 javadoc）。 */
  public ToolContext(
      AccessToken caller,
      AgentPermissionSet permissions,
      Map<String, Object> config,
      Map<String, Object> arguments) {
    this(caller, permissions, config, arguments, AgentIdentity.UNKNOWN);
  }

  public static ToolContext of(AccessToken caller, AgentPermissionSet permissions) {
    return new ToolContext(caller, permissions, Map.of(), Map.of());
  }

  /** 带身份的装配（宿主侧用：主 Agent、子 Agent 链接、外部面）。 */
  public static ToolContext of(
      AccessToken caller, AgentPermissionSet permissions, AgentIdentity identity) {
    return new ToolContext(caller, permissions, Map.of(), Map.of(), identity);
  }

  /** 换身份（保持 caller/permissions/config/arguments/resources 逐字不变）——MCP 面按"链接上绑定的身份"建每次调用的上下文时用。 */
  public ToolContext withIdentity(AgentIdentity newIdentity) {
    return new ToolContext(caller, permissions, config, arguments, newIdentity, resources);
  }

  /**
   * 换资源判定者（其余分量逐字不变）——<b>宿主侧注入点</b>：唯一入口用"调用者权限集 × 工具声明的资源面"建出判定者后， 在这里换掉缺省的 {@link
   * ResourceAuthorizer#denying()}。工具不得调它（换 = 自己给自己发许可）。
   */
  public ToolContext withResources(ResourceAuthorizer newResources) {
    return new ToolContext(caller, permissions, config, arguments, identity, newResources);
  }
}
