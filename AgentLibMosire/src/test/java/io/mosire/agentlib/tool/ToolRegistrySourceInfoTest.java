package io.mosire.agentlib.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A6：供给源元数据的登记/查询/排序/保留语义。 */
class ToolRegistrySourceInfoTest {

  @Test
  void registersQueriesAndSortsSourceInfos() {
    ToolRegistry registry = new ToolRegistry();
    registry.registerSourceInfo(
        new ToolSourceInfo("qqconnect", "QQ Connect", "0.3.1", "QQ 工具", Map.of("vendor", "bma3")));
    registry.registerSourceInfo(ToolSourceInfo.of("inbox", "Inbox", "1.0.0"));

    ToolSourceInfo qq = registry.sourceInfo("qqconnect").orElseThrow();
    assertThat(qq.displayName()).isEqualTo("QQ Connect");
    assertThat(qq.version()).isEqualTo("0.3.1");
    assertThat(registry.sourceInfos())
        .extracting(ToolSourceInfo::sourceId)
        .containsExactly("inbox", "qqconnect");
    assertThat(registry.sourceInfo("missing")).isEmpty();
    assertThat(registry.sourceInfo(" ")).isEmpty();
  }

  @Test
  void metadataSurvivesSourceToolRemovalUntilExplicitlyRemoved() {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAll("qqconnect", List.of(tool("qq_send")));
    registry.registerSourceInfo(ToolSourceInfo.of("qqconnect", "QQ Connect", "0.3.1"));

    registry.unregisterAllBySource("qqconnect");

    assertThat(registry.sourceIds()).isEmpty();
    assertThat(registry.sourceInfo("qqconnect")).isPresent();
    assertThat(registry.unregisterSourceInfo("qqconnect")).isTrue();
    assertThat(registry.sourceInfo("qqconnect")).isEmpty();
  }

  @Test
  void sourceOfMapsToolBackToItsRegisteredSource() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("builtin_tool"));
    registry.registerAll("qqconnect", List.of(tool("qq_send")));

    assertThat(registry.sourceOf("qq_send")).contains("qqconnect");
    assertThat(registry.sourceOf("builtin_tool")).isEmpty();
    assertThat(registry.sourceOf("missing")).isEmpty();
    assertThat(registry.sourceOf(" ")).isEmpty();
  }

  @Test
  void blankDisplayNameFallsBackToSourceId() {
    ToolSourceInfo info = new ToolSourceInfo("qqconnect", " ", null, null, null);

    assertThat(info.displayName()).isEqualTo("qqconnect");
    assertThat(info.version()).isEmpty();
    assertThat(info.description()).isEmpty();
    assertThat(info.attributes()).isEmpty();
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
