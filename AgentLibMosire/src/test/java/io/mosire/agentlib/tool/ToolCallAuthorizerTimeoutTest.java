package io.mosire.agentlib.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceDeniedException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A3：工具执行硬超时（默认 10 分钟、可配置、超时返回 TIMEOUT、异常口径不变）。 */
class ToolCallAuthorizerTimeoutTest {

  @Test
  void slowToolReturnsTimeoutAndDoesNotBlockCaller() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(
        tool(
            "slow",
            context -> {
              try {
                Thread.sleep(5_000L);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.ok("interrupted");
              }
              return ToolResult.ok("late");
            }));

    long started = System.nanoTime();
    ToolResult result =
        ToolCallAuthorizer.standard()
            .execute(
                registry, "slow", context(Map.of(ToolCallAuthorizer.CONFIG_TOOL_TIMEOUT_MS, 50L)));
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo(ToolCallAuthorizer.TIMEOUT);
    assertThat(result.message()).contains("超时未完成");
    assertThat(elapsedMillis).isLessThan(2_000L);
  }

  @Test
  void fastToolIsUnaffected() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("fast", context -> ToolResult.ok("ok")));

    ToolResult result =
        ToolCallAuthorizer.standard()
            .execute(
                registry,
                "fast",
                context(Map.of(ToolCallAuthorizer.CONFIG_TOOL_TIMEOUT_MS, 5_000L)));

    assertThat(result.success()).isTrue();
    assertThat(result.message()).isEqualTo("ok");
  }

  @Test
  void resourceDeniedStillMapsToStableCodeThroughWorker() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(
        tool(
            "denied",
            context -> {
              throw new ResourceDeniedException("资源不可达");
            }));

    ToolResult result =
        ToolCallAuthorizer.standard()
            .execute(
                registry,
                "denied",
                context(Map.of(ToolCallAuthorizer.CONFIG_TOOL_TIMEOUT_MS, 5_000L)));

    assertThat(result.code()).isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
    assertThat(result.message()).isEqualTo("资源不可达");
  }

  @Test
  void runtimeExceptionStillPropagates() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(
        tool(
            "boom",
            context -> {
              throw new IllegalStateException("工具内部 bug");
            }));

    assertThatThrownBy(
            () ->
                ToolCallAuthorizer.standard()
                    .execute(
                        registry,
                        "boom",
                        context(Map.of(ToolCallAuthorizer.CONFIG_TOOL_TIMEOUT_MS, 5_000L))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("工具内部 bug");
  }

  @Test
  void timeoutConfigDefaultsAndFallbacks() {
    assertThat(ToolCallAuthorizer.timeoutMillis(context(Map.of())))
        .isEqualTo(ToolCallAuthorizer.DEFAULT_TOOL_TIMEOUT_MS);
    assertThat(
            ToolCallAuthorizer.timeoutMillis(
                context(Map.of(ToolCallAuthorizer.CONFIG_TOOL_TIMEOUT_MS, 1234))))
        .isEqualTo(1234L);
    assertThat(
            ToolCallAuthorizer.timeoutMillis(
                context(Map.of(ToolCallAuthorizer.CONFIG_TOOL_TIMEOUT_MS, "1500"))))
        .isEqualTo(1500L);
    assertThat(
            ToolCallAuthorizer.timeoutMillis(
                context(Map.of(ToolCallAuthorizer.CONFIG_TOOL_TIMEOUT_MS, 0))))
        .isEqualTo(ToolCallAuthorizer.DEFAULT_TOOL_TIMEOUT_MS);
    assertThat(
            ToolCallAuthorizer.timeoutMillis(
                context(Map.of(ToolCallAuthorizer.CONFIG_TOOL_TIMEOUT_MS, -1))))
        .isEqualTo(ToolCallAuthorizer.DEFAULT_TOOL_TIMEOUT_MS);
    assertThat(
            ToolCallAuthorizer.timeoutMillis(
                context(Map.of(ToolCallAuthorizer.CONFIG_TOOL_TIMEOUT_MS, "abc"))))
        .isEqualTo(ToolCallAuthorizer.DEFAULT_TOOL_TIMEOUT_MS);
  }

  private static ToolContext context(Map<String, Object> config) {
    return new ToolContext(AccessToken.SYSTEM, AgentPermissionSet.system(), config, Map.of());
  }

  private static AgentTool tool(
      String name, java.util.function.Function<ToolContext, ToolResult> body) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return body.apply(context);
      }
    };
  }
}
