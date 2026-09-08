package io.mosire.agentlib.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ToolSpec;
import org.junit.jupiter.api.Test;

class ToolExecutionGuardTest {

  private final ToolExecutionGuard guard = new ToolExecutionGuard();

  @Test
  void executesWhenPermitted() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(echoTool());
    ToolContext context =
        ToolContext.of(
            AccessToken.DEFAULT,
            AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build());
    ToolResult result = guard.execute(registry, "echo", context);
    assertThat(result.success()).isTrue();
  }

  @Test
  void deniesWhenNotInWhitelist() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(echoTool());
    ToolContext context =
        ToolContext.of(
            AccessToken.DEFAULT,
            AgentPermissionSet.builder(AccessToken.DEFAULT).allow("other").build());
    ToolResult result = guard.execute(registry, "echo", context);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo(ToolExecutionGuard.DENIED);
  }

  @Test
  void unknownToolGivesNotFound() {
    ToolRegistry registry = new ToolRegistry();
    ToolContext context =
        ToolContext.of(
            AccessToken.DEFAULT,
            AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build());
    ToolResult result = guard.execute(registry, "nope", context);
    assertThat(result.code()).isEqualTo("TOOL_NOT_FOUND");
  }

  @Test
  void declaresDestructiveRequiresPermission() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(
        new AgentTool() {
          @Override
          public String name() {
            return "rm";
          }

          @Override
          public ToolSpec spec() {
            return ToolSpec.level(AccessToken.GUEST, false, true);
          }

          @Override
          public ToolResult execute(ToolContext context) {
            return ToolResult.ok("deleted");
          }
        });
    ToolContext denied =
        ToolContext.of(
            AccessToken.DEFAULT,
            AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build());
    assertThat(guard.execute(registry, "rm", denied).code()).isEqualTo(ToolExecutionGuard.DENIED);
    ToolContext allowed =
        ToolContext.of(
            AccessToken.DEFAULT,
            AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allowAll()
                .destructiveAllowed(true)
                .build());
    assertThat(guard.execute(registry, "rm", allowed).success()).isTrue();
  }

  private static AgentTool echoTool() {
    return new AgentTool() {
      @Override
      public String name() {
        return "echo";
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok("echo: " + context.arguments().getOrDefault("text", ""));
      }
    };
  }
}
