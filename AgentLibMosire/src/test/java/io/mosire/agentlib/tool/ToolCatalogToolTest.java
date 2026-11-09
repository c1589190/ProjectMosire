package io.mosire.agentlib.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ToolSpec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A5/A6：{@code tool_catalog} 的只读、可见面过滤、来源/分组元数据与参数校验。 */
class ToolCatalogToolTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void fullViewReturnsToolAndSourceMetadata() throws Exception {
    ToolRegistry registry = registryWithMetadata();
    ToolCatalogTool catalog = new ToolCatalogTool(registry);

    ToolResult result = catalog.execute(context(AccessToken.DEFAULT, Map.of("view", "full")));

    assertThat(result.success()).isTrue();
    JsonNode root = MAPPER.readTree(result.message());
    assertThat(root.path("view").asText()).isEqualTo("full");
    assertThat(root.path("toolCount").asInt()).isEqualTo(2);

    JsonNode qqSource = source(root, "qqconnect");
    assertThat(qqSource).isNotNull();
    assertThat(qqSource.path("displayName").asText()).isEqualTo("QQ Connect");
    assertThat(qqSource.path("version").asText()).isEqualTo("0.3.1");
    assertThat(qqSource.path("toolCount").asInt()).isEqualTo(1);

    JsonNode qqTool = tool(root, "qq_send");
    assertThat(qqTool).isNotNull();
    assertThat(qqTool.path("title").asText()).isEqualTo("发送 QQ 消息");
    assertThat(qqTool.path("group").asText()).isEqualTo("qq");
    assertThat(qqTool.path("sourceId").asText()).isEqualTo("qqconnect");
    assertThat(qqTool.path("requiredLevel").asText()).isEqualTo("DEFAULT");
    assertThat(qqTool.path("sensitive").asBoolean()).isTrue();
    assertThat(tags(qqTool)).containsExactly("external", "write");

    JsonNode builtinTool = tool(root, "read_doc");
    assertThat(builtinTool).isNotNull();
    assertThat(builtinTool.path("sourceId").asText()).isEmpty();
    assertThat(source(root, "").path("displayName").asText()).isEqualTo("builtin");
  }

  @Test
  void summaryViewOmitsToolDetailsButKeepsCounts() throws Exception {
    ToolCatalogTool catalog = new ToolCatalogTool(registryWithMetadata());

    ToolResult result = catalog.execute(context(AccessToken.DEFAULT, Map.of()));

    assertThat(result.success()).isTrue();
    JsonNode root = MAPPER.readTree(result.message());
    assertThat(root.path("view").asText()).isEqualTo("summary");
    assertThat(root.path("toolCount").asInt()).isEqualTo(2);
    assertThat(root.has("tools")).isFalse();
    assertThat(root.path("groups").findValuesAsText("group")).contains("docs", "qq");
  }

  @Test
  void onlyToolsTheCallerCanInvokeAreListed() throws Exception {
    ToolCatalogTool catalog = new ToolCatalogTool(registryWithMetadata());

    ToolResult result = catalog.execute(context(AccessToken.GUEST, Map.of("view", "full")));

    assertThat(result.success()).isTrue();
    JsonNode root = MAPPER.readTree(result.message());
    assertThat(root.path("toolCount").asInt()).isEqualTo(1);
    assertThat(tool(root, "qq_send")).isNull();
    assertThat(tool(root, "read_doc")).isNotNull();
  }

  @Test
  void filtersBySourceAndLimitTruncates() throws Exception {
    ToolCatalogTool catalog = new ToolCatalogTool(registryWithMetadata());

    ToolResult bySource =
        catalog.execute(
            context(AccessToken.DEFAULT, Map.of("view", "full", "sourceId", "qqconnect")));
    JsonNode sourceRoot = MAPPER.readTree(bySource.message());
    assertThat(sourceRoot.path("toolCount").asInt()).isEqualTo(1);
    assertThat(tool(sourceRoot, "qq_send")).isNotNull();

    ToolResult limited =
        catalog.execute(context(AccessToken.DEFAULT, Map.of("view", "full", "limit", 1)));
    JsonNode limitedRoot = MAPPER.readTree(limited.message());
    assertThat(limitedRoot.path("tools")).hasSize(1);
    assertThat(limitedRoot.path("truncated").asBoolean()).isTrue();
  }

  @Test
  void invalidArgumentsReturnStableCode() {
    ToolCatalogTool catalog = new ToolCatalogTool(registryWithMetadata());

    ToolResult result = catalog.execute(context(AccessToken.DEFAULT, Map.of("view", "everything")));

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("INVALID_ARGUMENTS");
  }

  private static ToolRegistry registryWithMetadata() {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAll(
        "qqconnect",
        List.of(
            tool(
                "qq_send",
                "发送 QQ 消息",
                "qq",
                Set.of("write", "external"),
                ToolSpec.level(AccessToken.DEFAULT, true, false))));
    registry.register(tool("read_doc", "读文档", "docs", Set.of("read-only"), ToolSpec.DEFAULT));
    registry.registerSourceInfo(ToolSourceInfo.of("qqconnect", "QQ Connect", "0.3.1"));
    return registry;
  }

  private static ToolContext context(AccessToken token, Map<String, Object> args) {
    return new ToolContext(token, AgentPermissionSet.unrestricted(token), Map.of(), args);
  }

  private static JsonNode source(JsonNode root, String sourceId) {
    for (JsonNode source : root.path("sources")) {
      if (sourceId.equals(source.path("sourceId").asText())) {
        return source;
      }
    }
    return null;
  }

  private static JsonNode tool(JsonNode root, String name) {
    for (JsonNode tool : root.path("tools")) {
      if (name.equals(tool.path("name").asText())) {
        return tool;
      }
    }
    return null;
  }

  private static List<String> tags(JsonNode tool) {
    List<String> tags = new java.util.ArrayList<>();
    for (JsonNode tag : tool.path("tags")) {
      tags.add(tag.asText());
    }
    return tags;
  }

  private static AgentTool tool(
      String name, String title, String group, Set<String> tags, ToolSpec spec) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String title() {
        return title;
      }

      @Override
      public String group() {
        return group;
      }

      @Override
      public Set<String> tags() {
        return tags;
      }

      @Override
      public ToolSpec spec() {
        return spec;
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok(name);
      }
    };
  }
}
