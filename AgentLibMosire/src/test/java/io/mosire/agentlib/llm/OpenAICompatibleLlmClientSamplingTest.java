package io.mosire.agentlib.llm;

import static io.mosire.agentlib.llm.FakeChatEndpoint.contentFrame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * <b>A2 验收</b>：{@code temperature} / {@code maxTokens} / {@code extraBody} 真的进了请求体——用假端点<b>捕获原始请求体
 * JSON</b> 后断言（这是"在供应商侧可观测"的唯一证据面；只测 getter 等于什么都没证明）。
 *
 * <p>本文件的两条重点是需求点名的边界：
 *
 * <ul>
 *   <li>{@code temperature = 0} <b>是合法值</b>，绝不能被当成"未设置"而省略——判决场景（要可复现）全靠它。
 *   <li>{@code extraBody} 里出现协议保留键（{@code model}/{@code messages}/{@code temperature}…）→
 *       <b>构造期</b>响亮 拒绝，不静默忽略（静默接受会让用户以为"我在 extraBody 里改了 model"，实际发出去的还是路由里那个）。
 * </ul>
 */
class OpenAICompatibleLlmClientSamplingTest {

  private static final String ROUTE_MODEL = "sampling-route-model-fake";

  private FakeChatEndpoint endpoint;

  @BeforeEach
  void startFakeEndpoint() throws IOException {
    endpoint = FakeChatEndpoint.start();
    endpoint.sayJson(contentFrame("ok")).done(); // 每个用例只关心请求体，响应给一条最简的合法 SSE
  }

  @AfterEach
  void stopFakeEndpoint() {
    endpoint.close();
  }

  // ---------- 请求体形状（供应商侧可观测） ----------

  /**
   * A2 核心边界 pin：{@code temperature = 0} 必须以 {@code "temperature":0} 出现在请求体里。
   *
   * <p>判别性：省略（<b>未设置</b>）与 {@code 0}（<b>贪心解码，要可复现</b>）是两种完全不同的诉求，用 0 当哨兵会把两者混成 一个——把 {@code
   * applySampling} 里的判空改成 {@code temperature != 0} 之类，本用例必红。
   */
  @Test
  void sendsTemperatureZeroInsteadOfOmittingIt() throws IOException {
    endpoint.client(ROUTE_MODEL).chat(oneUserTurn().withSampling(Sampling.atTemperature(0)));

    JsonNode body = endpoint.requestJson(0);
    assertThat(body.has("temperature")).as("temperature=0 必须发出去，而不是被当成\"未设置\"").isTrue();
    assertThat(body.get("temperature").isNull()).isFalse();
    assertThat(body.get("temperature").doubleValue()).isEqualTo(0.0d);
  }

  /** 反向对照：没设采样参数时请求体里<b>不得</b>出现 {@code temperature}/{@code max_tokens}——"未设置"就是"用供应商默认值"。 */
  @Test
  void omitsTemperatureAndMaxTokensWhenSamplingNotSet() throws IOException {
    endpoint.client(ROUTE_MODEL).chat(oneUserTurn());

    JsonNode body = endpoint.requestJson(0);
    assertThat(body.has("temperature")).isFalse();
    assertThat(body.has("max_tokens")).isFalse();
    // 协议键照旧（否则"没有 temperature"可能只是因为请求体整个是空的——那这条断言就空转了）
    assertThat(body.get("model").asText()).isEqualTo(ROUTE_MODEL);
    assertThat(body.get("stream").asBoolean()).isTrue();
  }

  /** A2：{@code maxTokens} 以协议的 {@code max_tokens}（snake_case）平铺在请求体顶层，值原样。 */
  @Test
  void sendsMaxTokensAsSnakeCaseTopLevelField() throws IOException {
    endpoint.client(ROUTE_MODEL).chat(oneUserTurn().withMaxTokens(1234));

    JsonNode body = endpoint.requestJson(0);
    assertThat(body.has("max_tokens")).isTrue();
    assertThat(body.get("max_tokens").intValue()).isEqualTo(1234);
  }

  /**
   * A2：{@code extraBody} 的键值<b>原样</b>透传到请求体顶层——包括实现完全不认识的自定义键（Ollama/vLLM/网关的私有字段 {@code
   * top_k}/{@code thinking} 等）。这是"私有扩展"这条通路唯一的证据面。
   */
  @Test
  void passesExtraBodyKeysThroughVerbatim() throws IOException {
    Map<String, Object> extraBody = new LinkedHashMap<>();
    extraBody.put("top_k", 40);
    extraBody.put("thinking", "enabled");
    extraBody.put("x-vendor-flag", true);
    extraBody.put("nested", Map.of("keep", 7));

    endpoint
        .client(ROUTE_MODEL)
        .chat(oneUserTurn().withSampling(Sampling.withExtraBody(extraBody)));

    JsonNode body = endpoint.requestJson(0);
    assertThat(body.get("top_k").intValue()).isEqualTo(40);
    assertThat(body.get("thinking").asText()).isEqualTo("enabled");
    assertThat(body.get("x-vendor-flag").asBoolean()).isTrue();
    assertThat(body.path("nested").path("keep").asInt()).isEqualTo(7);
    // 透传不覆盖协议键：model/stream 仍是本库写下的值
    assertThat(body.get("model").asText()).isEqualTo(ROUTE_MODEL);
    assertThat(body.get("stream").asBoolean()).isTrue();
  }

  /** 透传与强类型字段可共用：三者同时设上，互不覆盖。 */
  @Test
  void sendsTemperatureMaxTokensAndExtraBodyTogether() throws IOException {
    Map<String, Object> extraBody = Map.of("top_k", 5);
    Sampling sampling = new Sampling(0.0d, 512, extraBody);

    endpoint.client(ROUTE_MODEL).chat(oneUserTurn().withSampling(sampling));

    JsonNode body = endpoint.requestJson(0);
    assertThat(body.get("temperature").doubleValue()).isEqualTo(0.0d);
    assertThat(body.get("max_tokens").intValue()).isEqualTo(512);
    assertThat(body.get("top_k").intValue()).isEqualTo(5);
  }

  // ---------- extraBody 的保留键（构造期响亮拒绝） ----------

  /**
   * A2：{@link Sampling#RESERVED_KEYS} 里的每个键出现在 {@code extraBody} 里 → <b>构造期</b>即抛 {@link
   * IllegalArgumentException}（响亮，不静默忽略）。
   *
   * <p>用例直接以 {@code Sampling.RESERVED_KEYS} 为数据源（不是另抄一份常量）：保留键集合扩了、而校验循环被删掉的情况会 在这里立刻变红。
   */
  @ParameterizedTest(name = "保留键 {0} 经 extraBody 透传必须被拒")
  @MethodSource("reservedKeys")
  void rejectsReservedKeysInExtraBodyAtConstructionTime(String reservedKey) {
    Map<String, Object> sneaky = Map.of(reservedKey, "hijack");

    assertThatThrownBy(() -> Sampling.withExtraBody(sneaky))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("extraBody")
        .hasMessageContaining(reservedKey);
  }

  /** 保留键集合本身也是对外契约：这几条协议关键键必须在列（集合被悄悄缩小 = 用户又能经 extraBody 改协议了）。 */
  @Test
  void reservedKeySetCoversTheProtocolCriticalKeys() {
    assertThat(Sampling.RESERVED_KEYS)
        .contains(
            "model", "messages", "stream", "stream_options", "tools", "temperature", "max_tokens");
  }

  /** 反面对照：非保留键照常接受（否则"保留键被拒"可能只是因为校验把一切都拒了）。 */
  @Test
  void acceptsExtraBodyKeysThatAreNotReserved() {
    Sampling sampling = Sampling.withExtraBody(Map.of("top_k", 40, "thinking", true));

    assertThat(sampling.extraBody()).containsEntry("top_k", 40).containsEntry("thinking", true);
    assertThat(sampling.isEmpty()).isFalse();
  }

  // ---------- Sampling 自身的取值边界 ----------

  /**
   * 0 与"未设置"在 {@link Sampling} 内部也必须分得开：{@code atTemperature(0)} 的 {@code isEmpty()} 是 {@code
   * false} ——若它被算成"整组没设"，请求侧就会把整份采样参数省掉（与 {@link #sendsTemperatureZeroInsteadOfOmittingIt} 是同一 反面）。
   */
  @Test
  void zeroTemperatureIsAValueNotAnAbsence() {
    assertThat(Sampling.atTemperature(0).isEmpty()).isFalse();
    assertThat(Sampling.atTemperature(0).temperature()).isEqualTo(0.0d);
    assertThat(Sampling.DEFAULT.isEmpty()).isTrue();
    // 不传 sampling 的请求按 DEFAULT 补齐（null 与"什么都不设"同义）
    assertThat(LlmRequest.ofMessages(List.of(LlmMessage.user("在吗"))).sampling())
        .isEqualTo(Sampling.DEFAULT);
  }

  /**
   * 取值校验只拦"明显错"的：负数/NaN/无穷温度、非正 {@code maxTokens} 一律在构造期拒绝。
   *
   * <p>与 0 的边界配套：这组断言同时钉住"校验不是 {@code <= 0}"这类会把合法 0 一起误杀的写法（那种写法下 {@link
   * #zeroTemperatureIsAValueNotAnAbsence} 必红，本用例则保证真正的非法值仍被拦住）。
   */
  @Test
  void rejectsNonPositiveOrNonFiniteValues() {
    assertThatThrownBy(() -> Sampling.atTemperature(-0.1d))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("temperature");
    assertThatThrownBy(() -> Sampling.atTemperature(Double.NaN))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Sampling.atTemperature(Double.POSITIVE_INFINITY))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> Sampling.cappedAt(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxTokens");
    assertThatThrownBy(() -> Sampling.cappedAt(-5)).isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * {@code withTemperature}/{@code withMaxTokens} 只改自己那一项：另一项与 {@code extraBody} 原样保留（重载最容易丢的就是它们）。
   */
  @Test
  void requestWithersPreserveTheRestOfTheSampling() {
    LlmRequest request =
        LlmRequest.ofMessages(List.of(LlmMessage.user("在吗")))
            .withSampling(new Sampling(0.7d, 256, Map.of("top_k", 5)));

    Sampling afterTemperature = request.withTemperature(0).sampling();
    assertThat(afterTemperature.temperature()).isEqualTo(0.0d);
    assertThat(afterTemperature.maxTokens()).isEqualTo(256);
    assertThat(afterTemperature.extraBody()).containsEntry("top_k", 5);

    Sampling afterMaxTokens = request.withMaxTokens(64).sampling();
    assertThat(afterMaxTokens.temperature()).isEqualTo(0.7d);
    assertThat(afterMaxTokens.maxTokens()).isEqualTo(64);
    assertThat(afterMaxTokens.extraBody()).containsEntry("top_k", 5);
  }

  // ---------- 辅助 ----------

  private static Stream<String> reservedKeys() {
    return Sampling.RESERVED_KEYS.stream();
  }

  private static LlmRequest oneUserTurn() {
    return LlmRequest.ofMessages(List.of(LlmMessage.user("在吗")));
  }
}
