package io.mosire.agentlib.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.llm.ToolAsset;
import io.mosire.agentlib.llm.ToolAssetResolver;
import io.mosire.agentlib.tool.ToolResult;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 工具结果里的<b>图片资产</b>在 MCP 面出成 {@link McpSchema.ImageContent}——外部 agent（含 GM 面的大模型）据此"看图"。
 *
 * <p>本文件钉四条边界：① 图片资产 ⇒ 文本块<b>之后</b>追加一个 image 块，base64 与 mime 逐字节正确（顺带把 SDK 构造参数的
 * 顺序钉死——data/mimeType 写反必红）；② 解析不到的 id ⇒ 记 WARN、跳过，<b>文本结果照旧</b>（不把"图暂不可用"升级成"工具失败"）； ③ 非图片资产（溢出的文本
 * docId）不出图片块；④ 失败结果只出文本（错误面不夹带图片）。
 */
class AgentToMcpServerImageContentTest {

  private static final byte[] PNG_BYTES =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

  private static ToolAssetResolver pngResolver() {
    return assetId ->
        "asset-1".equals(assetId)
            ? Optional.of(new ToolAsset("image/png", PNG_BYTES))
            : Optional.empty();
  }

  /** 文本永远在，图片是附加块；base64/mime 与资产一致。 */
  @Test
  void imageAssetBecomesAnImageContentAfterTheText() {
    McpSchema.CallToolResult result =
        AgentToMcpServer.toCallToolResult(
            ToolResult.ok("已生成视图（400×300）", List.of("asset-1")), pngResolver());

    assertThat(result.isError()).isFalse();
    assertThat(result.content()).hasSize(2);
    assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
    assertThat(((McpSchema.TextContent) result.content().get(0)).text())
        .as("文本块必须仍然带着工具消息")
        .contains("已生成视图");

    McpSchema.Content second = result.content().get(1);
    assertThat(second).isInstanceOf(McpSchema.ImageContent.class);
    McpSchema.ImageContent image = (McpSchema.ImageContent) second;
    assertThat(image.mimeType()).as("mime 与资产一致（写反成 data 的位置必红）").isEqualTo("image/png");
    assertThat(image.data())
        .as("base64 必须逐字节等于资产字节")
        .isEqualTo(Base64.getEncoder().encodeToString(PNG_BYTES));
  }

  /** 解析不到 ⇒ 只出文本，调用不算失败（工件过期是常态）。 */
  @Test
  void unresolvableAssetIsSkippedAndTextSurvives() {
    McpSchema.CallToolResult result =
        AgentToMcpServer.toCallToolResult(
            ToolResult.ok("文本仍在", List.of("asset-过期了")), pngResolver());

    assertThat(result.isError()).isFalse();
    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
  }

  /** 非图片资产（文本 docId 溢出机制）不出图片块。 */
  @Test
  void nonImageAssetsDoNotBecomeImageContent() {
    ToolAssetResolver textAssetResolver =
        assetId -> Optional.of(new ToolAsset("text/plain", "hello".getBytes()));

    McpSchema.CallToolResult result =
        AgentToMcpServer.toCallToolResult(
            ToolResult.ok("长文档已暂存", List.of("doc-1")), textAssetResolver);

    assertThat(result.content()).hasSize(1);
  }

  /** 失败结果只出文本——错误面不夹带图片。 */
  @Test
  void errorResultsNeverCarryImages() {
    ToolResult failure = new ToolResult("BAD_REQUEST", "参数不对", List.of("asset-1"));

    McpSchema.CallToolResult result = AgentToMcpServer.toCallToolResult(failure, pngResolver());

    assertThat(result.isError()).isTrue();
    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
  }

  /** 缺省重载（不传 resolver）= 不认任何资产：文本照旧、无图片块。 */
  @Test
  void defaultOverloadWithoutResolverKeepsTextOnly() {
    McpSchema.CallToolResult result =
        AgentToMcpServer.toCallToolResult(ToolResult.ok("文本仍在", List.of("asset-1")));

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
  }
}
