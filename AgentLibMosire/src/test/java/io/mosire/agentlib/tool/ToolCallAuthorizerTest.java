package io.mosire.agentlib.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ToolSpec;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * {@link ToolCallAuthorizer}：工具调用<b>唯一入口</b>的三段顺序（硬拒 →（审批闸）→ 执行）。
 *
 * <p>判别性重点（每条都"变异即转红"）：
 *
 * <ol>
 *   <li>硬拒<b>不进工具体</b>——若被拒的调用仍跑了副作用，{@link #permissionDenialNeverReachesToolBody} 转红；
 *   <li><b>MCP 路径同判</b>——见同包外的 {@code io.mosire.agentlib.mcp.AgentToMcpServerAuthorityTest}（走真实 MCP
 *       握手 + {@code tools/call}）：把 {@code AgentToMcpServer.handleCall} 还原成裸 {@code
 *       tool.execute(context)} 该用例转红。 这正是 S4 要修的洞：MCP 路径过去连 {@link ToolExecutionGuard}
 *       都不经，工具名级白名单与"敏感/破坏需显式放行"在这条路上从不生效。
 * </ol>
 */
class ToolCallAuthorizerTest {

  /** 调用者权限集：白名单通配（不设限）——让"被挡住"这件事只能来自身份级别/放行位，不会与白名单混淆。 */
  private static AgentPermissionSet unrestricted(
      AccessToken token, boolean sensitive, boolean destructive) {
    return AgentPermissionSet.builder(token)
        .allowAll()
        .sensitiveAllowed(sensitive)
        .destructiveAllowed(destructive)
        .build();
  }

  private static ToolRegistry registryWith(AgentTool... tools) {
    ToolRegistry registry = new ToolRegistry();
    for (AgentTool tool : tools) {
      registry.register(tool);
    }
    return registry;
  }

  /** SYSTEM 级工具：DEFAULT 调用者够不着（模拟"系统级编排工具"的 spec）。 */
  private static AgentTool systemTool(String name, AtomicBoolean bodyRan) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public ToolSpec spec() {
        return ToolSpec.level(AccessToken.SYSTEM, false, true);
      }

      @Override
      public ToolResult execute(ToolContext context) {
        bodyRan.set(true);
        return ToolResult.ok(name + ":executed");
      }
    };
  }

  /** DEFAULT 级但敏感的工具：身份够、放行位不够（模拟"写文件/发消息"类工具）。 */
  private static AgentTool sensitiveTool(String name, AtomicBoolean bodyRan) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, false);
      }

      @Override
      public ToolResult execute(ToolContext context) {
        bodyRan.set(true);
        return ToolResult.ok(name + ":executed");
      }
    };
  }

  @Test
  void toolNotFoundIsTerminal() {
    ToolResult result =
        ToolCallAuthorizer.standard()
            .execute(
                registryWith(),
                "nonexistent",
                ToolContext.of(AccessToken.SYSTEM, AgentPermissionSet.system()));
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("TOOL_NOT_FOUND");
  }

  @Test
  void permissionDenialNeverReachesToolBody() {
    AtomicBoolean bodyRan = new AtomicBoolean(false);
    ToolRegistry registry = registryWith(systemTool("spawn_sub_agent", bodyRan));
    // 调用者：DEFAULT 身份 + 白名单通配 + 敏感/破坏双放行 ⇒ 唯一挡得住它的就是"身份级别"
    ToolResult result =
        ToolCallAuthorizer.standard()
            .execute(
                registry,
                "spawn_sub_agent",
                ToolContext.of(AccessToken.DEFAULT, unrestricted(AccessToken.DEFAULT, true, true)));
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo(ToolExecutionGuard.DENIED);
    // 拒绝理由须指名"要求 SYSTEM / 调用者为 DEFAULT"（判定读的是工具自己的 spec，不是别处的常量）
    assertThat(result.message()).contains("SYSTEM").contains("DEFAULT");
    // ★ 判别性：被拒的调用绝不能碰工具体（副作用为零）
    assertThat(bodyRan).isFalse();
  }

  @Test
  void allowedCallExecutesBody() {
    AtomicBoolean bodyRan = new AtomicBoolean(false);
    ToolRegistry registry = registryWith(systemTool("spawn_sub_agent", bodyRan));
    ToolResult result =
        ToolCallAuthorizer.standard()
            .execute(
                registry,
                "spawn_sub_agent",
                ToolContext.of(AccessToken.SYSTEM, AgentPermissionSet.system()));
    assertThat(result.success()).isTrue();
    assertThat(result.message()).isEqualTo("spawn_sub_agent:executed");
    assertThat(bodyRan).isTrue();
  }
}
