package io.mosire.agentlib.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A2：{@code registerAll(sourceId, tools)} 的原子性、单次通知与按源归属。 */
class ToolRegistrySourceBatchTest {

  @Test
  void registersWholeBatchUnderSourceWithOneNotification() {
    ToolRegistry registry = new ToolRegistry();
    AtomicInteger changes = new AtomicInteger();
    registry.onChange(changes::incrementAndGet);

    registry.registerAll("qqconnect", List.of(tool("a"), tool("b")));

    assertThat(registry.listBySource("qqconnect"))
        .extracting(AgentTool::name)
        .containsExactly("a", "b");
    assertThat(registry.sourceIds()).containsExactly("qqconnect");
    assertThat(changes).hasValue(1);
    assertThat(registry.revision()).isEqualTo(1);
  }

  @Test
  void blankSourceIdIsRejectedWithoutWriting() {
    ToolRegistry registry = new ToolRegistry();

    assertThatThrownBy(() -> registry.registerAll(" ", List.of(tool("a"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sourceId");
    assertThat(registry.size()).isZero();
    assertThat(registry.revision()).isZero();
  }

  @Test
  void failedBatchLeavesNoPartialRegistration() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("existing"));

    assertThatThrownBy(
            () -> registry.registerAll("source-x", List.of(tool("new"), tool("existing"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("工具名重复");

    assertThat(registry.list()).extracting(AgentTool::name).containsExactly("existing");
    assertThat(registry.listBySource("source-x")).isEmpty();
    assertThat(registry.revision()).isEqualTo(1);
  }

  @Test
  void emptyBatchIsNoOp() {
    ToolRegistry registry = new ToolRegistry();
    AtomicInteger changes = new AtomicInteger();
    registry.onChange(changes::incrementAndGet);

    registry.registerAll("source-x", List.of());

    assertThat(registry.size()).isZero();
    assertThat(changes).hasValue(0);
    assertThat(registry.revision()).isZero();
  }

  @Test
  void unregisterAllBySourceRemovesOnlyThatSource() {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAll("source-a", List.of(tool("a1"), tool("a2")));
    registry.registerAll("source-b", List.of(tool("b1")));

    assertThat(registry.unregisterAllBySource("source-a")).isEqualTo(2);

    assertThat(registry.list()).extracting(AgentTool::name).containsExactly("b1");
    assertThat(registry.sourceIds()).containsExactly("source-b");
  }

  private static AgentTool tool(String name) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok(name);
      }
    };
  }
}
