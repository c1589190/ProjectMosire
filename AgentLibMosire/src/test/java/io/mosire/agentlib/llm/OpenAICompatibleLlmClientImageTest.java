package io.mosire.agentlib.llm;

import static io.mosire.agentlib.llm.FakeChatEndpoint.contentFrame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 图片分片（{@link ContentPart.Image}）的线格式与失败面——用假端点<b>捕获原始请求体</b>后断言。
 *
 * <p>本文件盯住四件事（每件都有判别力）：
 *
 * <ol>
 *   <li><b>带图消息的 {@code content} 是数组</b>：{@code [{"type":"text"…},{"type":"image_url"…}]}，且
 *       data-URI 里的 base64 <b>逐字节等于</b>资产字节——把 mime 与字节写反、或漏 base64，本用例必红。
 *   <li><b>纯文本消息仍然是字符串 {@code content}</b>：支持图片不得改动既有请求形状（对照组的判别力在"形状"本身）。
 *   <li><b>解析不到就响亮</b>：{@link ToolAssetResolver#none()} 或资产不存在 ⇒ {@link LlmException}（{@code
 *       Kind.CONFIG}），并且<b>一个请求都没发出去</b>——静默丢图是本类最不能接受的失败（见 {@link ContentPart.Image} 类注）。
 *   <li><b>两类角色/类型错配也响亮</b>：tool 角色带图、资产媒体类型与分片声明不一致 ⇒ 同样不发请求。
 * </ol>
 */
class OpenAICompatibleLlmClientImageTest {

  /** 1×1 的合法 PNG（字节本身不重要；重要的是"进了 data-URI 的就是这份字节"）。 */
  private static final byte[] PNG_BYTES =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

  private static final String ROUTE_MODEL = "image-route-model-fake";
  private static final String ASSET_ID = "asset-1";

  private FakeChatEndpoint endpoint;

  @BeforeEach
  void startFakeEndpoint() throws IOException {
    endpoint = FakeChatEndpoint.start();
    endpoint.sayJson(contentFrame("ok")).done();
  }

  @AfterEach
  void stopFakeEndpoint() {
    endpoint.close();
  }

  /** 认 {@code asset-1} 的解析器（字节 = {@link #PNG_BYTES}，媒体类型 image/png）。 */
  private static ToolAssetResolver resolverWithPng() {
    return assetId ->
        ASSET_ID.equals(assetId)
            ? Optional.of(new ToolAsset("image/png", PNG_BYTES))
            : Optional.empty();
  }

  private OpenAICompatibleLlmClient client(ToolAssetResolver resolver) {
    return new OpenAICompatibleLlmClient(
        ModelRoute.of("test", endpoint.baseUrl(), ROUTE_MODEL, "keys.fake"),
        OpenAICompatibleLlmClient.ApiKeySource.none(),
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        resolver);
  }

  private static LlmMessage userWithImage() {
    return new LlmMessage(
        LlmMessage.ROLE_USER,
        List.of(new ContentPart.Text("看这张图"), new ContentPart.Image("image/png", ASSET_ID)));
  }

  @Test
  void sendsTheImageAsADataUriInsideAContentArray() throws IOException {
    client(resolverWithPng()).chat(new LlmRequest(List.of(userWithImage())));

    JsonNode content = endpoint.requestJson(0).path("messages").path(0).path("content");
    assertThat(content.isArray()).as("带图消息的 content 必须是数组（多模态形态）").isTrue();
    assertThat(content).hasSize(2);
    assertThat(content.get(0).path("type").asText()).isEqualTo("text");
    assertThat(content.get(0).path("text").asText()).isEqualTo("看这张图");
    assertThat(content.get(1).path("type").asText()).isEqualTo("image_url");
    // ★ 判别点：data-URI 里的 base64 必须逐字节等于资产字节；mime 必须来自资产
    String url = content.get(1).path("image_url").path("url").asText();
    assertThat(url)
        .isEqualTo("data:image/png;base64," + Base64.getEncoder().encodeToString(PNG_BYTES));
  }

  @Test
  void textOnlyMessagesKeepThePlainStringContentForm() throws IOException {
    client(ToolAssetResolver.none()).chat(new LlmRequest(List.of(LlmMessage.user("只有文字"))));

    JsonNode content = endpoint.requestJson(0).path("messages").path(0).path("content");
    assertThat(content.isTextual()).as("纯文本消息的 content 必须仍是字符串（不改既有请求形状）").isTrue();
    assertThat(content.asText()).isEqualTo("只有文字");
  }

  @Test
  void unresolvableAssetFailsLoudlyAndSendsNothing() {
    assertThatThrownBy(
            () -> client(ToolAssetResolver.none()).chat(new LlmRequest(List.of(userWithImage()))))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining(ASSET_ID)
        .extracting(e -> ((LlmException) e).kind())
        .isEqualTo(LlmException.Kind.CONFIG);

    assertThat(endpoint.requestCount()).as("解析失败时一个请求都不该发出去").isZero();
  }

  @Test
  void toolRoleMessagesMayNotCarryImages() {
    LlmMessage toolWithImage =
        new LlmMessage(
            LlmMessage.ROLE_TOOL,
            List.of(
                new ContentPart.ToolResult("call-1", "some_tool", "结果", null),
                new ContentPart.Image("image/png", ASSET_ID)));

    assertThatThrownBy(() -> client(resolverWithPng()).chat(new LlmRequest(List.of(toolWithImage))))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("tool");
    assertThat(endpoint.requestCount()).as("角色不合法时一个请求都不该发出去").isZero();
  }

  @Test
  void mediaTypeMismatchBetweenPartAndAssetFailsLoudly() {
    ToolAssetResolver jpegResolver = assetId -> Optional.of(new ToolAsset("image/jpeg", PNG_BYTES));

    assertThatThrownBy(() -> client(jpegResolver).chat(new LlmRequest(List.of(userWithImage()))))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("image/jpeg");
    assertThat(endpoint.requestCount()).isZero();
  }
}
