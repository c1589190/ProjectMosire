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

  @Test
  void registerWithSourceThenUnregisterAllBySourceLeavesOtherSources() {
    ToolRegistry registry = new ToolRegistry();
    registry.register("srcA", fakeTool("a1"));
    registry.register("srcA", fakeTool("a2"));
    registry.register("srcB", fakeTool("b1"));
    // 无源注册（内建路径）：不得被按源移除波及
    registry.register(fakeTool("builtin"));

    assertThat(registry.unregisterAllBySource("srcA")).isEqualTo(2);
    assertThat(registry.list()).extracting(AgentTool::name).containsExactly("b1", "builtin");
    assertThat(registry.find("a1")).isEmpty();
    assertThat(registry.find("a2")).isEmpty();
    // 重复按源移除：幂等，返回 0
    assertThat(registry.unregisterAllBySource("srcA")).isZero();
    assertThat(registry.size()).isEqualTo(2);
  }

  @Test
  void unregisterAllBySourceBroadcastsOncePerBatch() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    registry.register("srcA", fakeTool("a1"));
    registry.register("srcA", fakeTool("a2"));
    List<Integer> sizesAfterChange = new CopyOnWriteArrayList<>();
    AutoCloseable subscription = registry.onChange(() -> sizesAfterChange.add(registry.size()));

    assertThat(registry.unregisterAllBySource("srcA")).isEqualTo(2);
    subscription.close();
    // 整源移除只广播一次，且取消订阅后不再广播
    registry.register("srcB", fakeTool("b1"));
    assertThat(sizesAfterChange).containsExactly(0);
  }

  @Test
  void sourceBookkeepingFollowsEveryRemovalPath() {
    ToolRegistry registry = new ToolRegistry();
    // 单个 unregister 也要清掉来源归属：同名工具换源再注册后，按新源可整组移除
    registry.register("srcA", fakeTool("t"));
    registry.unregister("t");
    registry.register("srcB", fakeTool("t"));
    assertThat(registry.unregisterAllBySource("srcB")).isEqualTo(1);

    // 批量 unregisterAll(Collection) 同样清掉来源归属
    registry.register("srcA", fakeTool("t"));
    assertThat(registry.unregisterAll(List.of("t"))).isEqualTo(1);
    assertThat(registry.unregisterAllBySource("srcA")).isZero();
    assertThat(registry.size()).isZero();
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
