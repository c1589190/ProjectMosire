package io.mosire.agentlib.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@link ToolRegistry} 的只读枚举增量（P2-7 / T19 R4：{@code sourceIds()} 与 {@code
 * listBySource(String)}）的判别性用例。
 *
 * <p>与既有 {@code ToolRegistryTest} 分开成文件：既有用例一字不改（P2-7 的硬性约束），新增能力独立成篇更便于逐条核对。
 */
class ToolRegistrySourceEnumerationTest {

  @Test
  void sourceIdsListsRegisteredSourcesSortedAndExcludesTheUnattributedSentinel() {
    ToolRegistry registry = new ToolRegistry();
    registry.register("srcB", fakeTool("b1"));
    registry.register("srcA", fakeTool("a1"));
    registry.register("srcA", fakeTool("a2"));
    // 无源注册（McpSourceBridge 的 registerAll 路径）落哨兵值：它不是"某个源"，不得出现在源清单里
    registry.register(fakeTool("no_source_tool"));

    assertThat(registry.sourceIds()).containsExactly("srcA", "srcB");

    // 两层分工的行为：整组摘除后该源自动离开清单（"已发现但无工具在册"的源只能由供给源自己的清单回答）
    assertThat(registry.unregisterAllBySource("srcA")).isEqualTo(2);
    assertThat(registry.sourceIds()).containsExactly("srcB");
  }

  @Test
  void sourceIdsIsAnImmutableSnapshot() {
    ToolRegistry registry = new ToolRegistry();
    registry.register("srcA", fakeTool("a1"));
    var snapshot = registry.sourceIds();
    assertThatThrownBy(() -> snapshot.add("srcB"))
        .isInstanceOf(UnsupportedOperationException.class);

    registry.register("srcB", fakeTool("b1"));
    assertThat(snapshot).as("快照不随后续注册变化").containsExactly("srcA");
  }

  @Test
  void listBySourceReturnsOnlyThatSourceSortedByName() {
    ToolRegistry registry = new ToolRegistry();
    registry.register("srcA", fakeTool("a2"));
    registry.register("srcA", fakeTool("a1"));
    registry.register("srcB", fakeTool("b1"));
    registry.register(fakeTool("no_source_tool"));

    assertThat(registry.listBySource("srcA"))
        .extracting(AgentTool::name)
        .containsExactly("a1", "a2");
    assertThat(registry.listBySource("srcB")).extracting(AgentTool::name).containsExactly("b1");
    assertThat(registry.listBySource("srcC")).as("未知源返回空表而非抛错").isEmpty();
  }

  @Test
  void listBySourceRejectsBlankIdBecauseItWouldMeanTheUnattributedSentinel() {
    ToolRegistry registry = new ToolRegistry();
    registry.register("srcA", fakeTool("a1"));

    assertThatThrownBy(() -> registry.listBySource(""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registry.listBySource("  "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registry.listBySource(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void listBySourceFollowsEveryRemovalPath() {
    ToolRegistry registry = new ToolRegistry();
    registry.register("srcA", fakeTool("t1"));
    registry.register("srcA", fakeTool("t2"));

    assertThat(registry.unregister("t1")).isNotNull();
    assertThat(registry.listBySource("srcA")).extracting(AgentTool::name).containsExactly("t2");

    // 同名工具换源再注册：归属随之转移（与 unregisterAllBySource 的既有口径一致）
    registry.unregister("t2");
    registry.register("srcB", fakeTool("t2"));
    assertThat(registry.listBySource("srcA")).isEmpty();
    assertThat(registry.listBySource("srcB")).extracting(AgentTool::name).containsExactly("t2");
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
