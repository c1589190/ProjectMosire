package io.mosire.agentlib.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

  @Test
  void registersAndListsSorted() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(fakeTool("b_tool"));
    registry.register(fakeTool("a_tool"));
    assertThat(registry.list()).extracting(AgentTool::name).containsExactly("a_tool", "b_tool");
    assertThat(registry.find("b_tool")).isPresent();
    assertThat(registry.find("missing")).isEmpty();
  }

  @Test
  void rejectsDuplicateNames() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(fakeTool("dup"));
    assertThatThrownBy(() -> registry.register(fakeTool("dup")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dup");
  }

  @Test
  void unregisterRemovesToolAndUnregisterAllRemovesBatch() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(fakeTool("a"));
    registry.register(fakeTool("b"));
    assertThat(registry.unregister("a")).isNotNull();
    assertThat(registry.find("a")).isEmpty();
    assertThat(registry.unregister("missing")).isNull();
    assertThat(registry.unregisterAll(List.of("missing"))).isZero();
    assertThat(registry.unregisterAll(List.of("b"))).isEqualTo(1);
    assertThat(registry.size()).isZero();
  }

  @Test
  void onChangeNotifiesSnapshotsOnEveryMutation() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    List<String> snapshots = new CopyOnWriteArrayList<>();
    AutoCloseable subscription =
        registry.onChange(
            () ->
                snapshots.add(
                    registry.list().stream().map(AgentTool::name).reduce("", (a, b) -> a + b)));
    registry.register(fakeTool("a"));
    registry.register(fakeTool("b"));
    registry.unregister("a");
    registry.unregisterAll(List.of("b"));
    subscription.close();
    // 取消订阅后不再广播
    registry.register(fakeTool("ignored"));
    assertThat(snapshots).containsExactly("a", "ab", "b", "");
  }

  @Test
  void snapshotIsImmutable() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(fakeTool("tool"));
    List<AgentTool> snapshot = registry.list();
    assertThatThrownBy(() -> snapshot.add(fakeTool("another")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static AgentTool fakeTool(String name) {
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
