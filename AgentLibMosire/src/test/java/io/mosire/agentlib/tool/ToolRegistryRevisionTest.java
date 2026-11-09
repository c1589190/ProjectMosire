package io.mosire.agentlib.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link ToolRegistry#revision()}：只在成功的增删时自增，失败/空操作不变。 */
class ToolRegistryRevisionTest {

  @Test
  void revisionAdvancesOnlyOnSuccessfulMutations() {
    ToolRegistry registry = new ToolRegistry();
    assertThat(registry.revision()).isZero();

    registry.register(tool("a"));
    assertThat(registry.revision()).isEqualTo(1);

    assertThatThrownBy(() -> registry.register(tool("a")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(registry.revision()).isEqualTo(1);

    assertThat(registry.unregister("missing")).isNull();
    assertThat(registry.revision()).isEqualTo(1);

    assertThat(registry.unregister("a")).isNotNull();
    assertThat(registry.revision()).isEqualTo(2);
  }

  @Test
  void batchAndSourceMutationsBumpRevisionOnce() {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAll(List.of());
    assertThat(registry.revision()).isZero();

    registry.registerAll(List.of(tool("a"), tool("b")));
    assertThat(registry.revision()).isEqualTo(1);

    assertThat(registry.unregisterAll(List.of("missing"))).isZero();
    assertThat(registry.revision()).isEqualTo(1);

    assertThat(registry.unregisterAll(List.of("a", "b"))).isEqualTo(2);
    assertThat(registry.revision()).isEqualTo(2);

    registry.register("source-x", tool("c"));
    assertThat(registry.revision()).isEqualTo(3);

    assertThatThrownBy(() -> registry.unregisterAllBySource(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(registry.revision()).isEqualTo(3);

    assertThat(registry.unregisterAllBySource("source-x")).isEqualTo(1);
    assertThat(registry.revision()).isEqualTo(4);
  }

  private static AgentTool tool(String name) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok("ok");
      }
    };
  }
}
