package io.mosire.agentlib.tool;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import java.util.Map;
import java.util.Objects;

/**
 * 一次工具调用的上下文：调用者身份 + 权限集 + 本次调用参数 + 配置注入点。
 *
 * <p>严格执行：工具所需的配置/凭据与“调用者想传的参数”是两回事——{@link #arguments} 来自 LLM 填入的 JSON 参数；工具自身配置经 {@link #config}
 * 由 Agent 运行时注入，工具实现只能读取、不能反向索取 调用者数据。
 */
public record ToolContext(
    AccessToken caller,
    AgentPermissionSet permissions,
    Map<String, Object> config,
    Map<String, Object> arguments) {

  public ToolContext {
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(permissions, "permissions");
    config = config == null ? Map.of() : Map.copyOf(config);
    arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
  }

  public static ToolContext of(AccessToken caller, AgentPermissionSet permissions) {
    return new ToolContext(caller, permissions, Map.of(), Map.of());
  }
}
