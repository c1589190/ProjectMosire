package io.mosire.agentlib.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.FileConfigStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LlmRouteLoader} 的闭环测试（三期 S1-A1 R2）：{@code llm.*} → {@link ModelRoute}。
 *
 * <p><b>判别性约定</b>：
 *
 * <ul>
 *   <li>缺 {@code baseUrl} 与缺 {@code model} 的失败必须<b>可分辨</b>（消息点名各自的键——抓"同一条通用文案"的实现）；
 *   <li>{@code credentialsRef} 缺失<b>不报错</b>（抓"把可选当必填"的实现），但存在却不是文本时<b>响亮</b>（抓"把配错当匿名"的实现）；
 *   <li>三个字段必须真的来自配置（改写配置 → 结果随之改变，抓硬编码实现）；
 *   <li>失败消息不回流任何配置值（含凭据样串的 URL 型 baseUrl 也不例外）。
 * </ul>
 *
 * <p>全程离线：只用 {@code @TempDir} 上的 {@link FileConfigStore}，不触任何网络。
 */
class LlmRouteLoaderTest {

  /** 含凭据的 URL（凭据安全断言用的大小写敏感标记串）。 */
  private static final String CREDENTIAL_URL = "https://user:sk-live-SECRET123@vault.local/v1";

  @TempDir Path tempDir;

  // ---------- 正向：三字段来自配置 ----------

  /** R2 判别性：字段确实来自配置（不是硬编码/默认值）；{@code name} 取稳定的固定值。 */
  @Test
  void loadsRouteFieldsFromConfigAndFollowsConfigChanges() throws IOException {
    writeConfig(config(CREDENTIAL_URL, "model-one", "keys.one"));
    ModelRoute first = LlmRouteLoader.load(store());
    assertThat(first.baseUrl()).isEqualTo(CREDENTIAL_URL);
    assertThat(first.model()).isEqualTo("model-one");
    assertThat(first.credentialsRef()).isEqualTo("keys.one");
    assertThat(first.name()).as("配置里没有路由名这一项：固定 'default'").isEqualTo("default");

    writeConfig(config("https://api.two.example.test/v1", "model-two", "keys.two"));
    ModelRoute second = LlmRouteLoader.load(store());
    assertThat(List.of(second.baseUrl(), second.model(), second.credentialsRef()))
        .as("改写配置必须改变结果（抓硬编码）")
        .containsExactly("https://api.two.example.test/v1", "model-two", "keys.two");
  }

  // ---------- 必填缺失：响亮且可分辨 ----------

  /** R2 判别性：缺 {@code baseUrl} 与缺 {@code model} 的文案必须互相可分辨，且共用同一非敏感码。 */
  @Test
  void missingBaseUrlAndMissingModelAreDistinguishable() throws IOException {
    writeConfig("{\"llm\":{\"model\":\"model-one\"}}");
    ConfigException noBaseUrl =
        catchThrowableOfType(ConfigException.class, () -> LlmRouteLoader.load(store()));

    writeConfig("{\"llm\":{\"baseUrl\":\"https://api.example.test/v1\"}}");
    ConfigException noModel =
        catchThrowableOfType(ConfigException.class, () -> LlmRouteLoader.load(store()));

    assertThat(noBaseUrl).isNotNull();
    assertThat(noModel).isNotNull();
    assertThat(noBaseUrl.code()).isEqualTo(LlmRouteLoader.E_LLM_CONFIG_MISSING);
    assertThat(noModel.code()).isEqualTo(LlmRouteLoader.E_LLM_CONFIG_MISSING);

    assertThat(noBaseUrl.getMessage()).contains("llm.baseUrl").doesNotContain("llm.model");
    assertThat(noModel.getMessage()).contains("llm.model").doesNotContain("llm.baseUrl");
  }

  /** R2：值存在但不可用（空白 / 非文本）以及整段缺失，一律响亮——不许当成"用了默认值"。 */
  @Test
  void unusableRequiredValuesAreLoud() throws IOException {
    List<String> unusable =
        List.of(
            "{}",
            "{\"llm\":{}}",
            "{\"llm\":{\"baseUrl\":\"   \",\"model\":\"model-one\"}}",
            "{\"llm\":{\"baseUrl\":12,\"model\":\"model-one\"}}",
            "{\"llm\":{\"baseUrl\":\"https://api.example.test/v1\",\"model\":null}}");

    for (String json : unusable) {
      writeConfig(json);
      ConfigException failure =
          catchThrowableOfType(ConfigException.class, () -> LlmRouteLoader.load(store()));
      assertThat(failure).as("config=%s", json).isNotNull();
      assertThat(failure.code())
          .as("config=%s", json)
          .isEqualTo(LlmRouteLoader.E_LLM_CONFIG_MISSING);
    }
  }

  // ---------- 可选：credentialsRef ----------

  /** R2 判别性：缺失（或显式 null）= 匿名，<b>不</b>报错（抓"把可选当必填"的实现）。 */
  @Test
  void missingCredentialsRefIsAnonymousNotAnError() throws IOException {
    writeConfig("{\"llm\":{\"baseUrl\":\"https://api.example.test/v1\",\"model\":\"model-one\"}}");
    assertThat(LlmRouteLoader.load(store()).credentialsRef()).isEmpty();

    writeConfig(
        "{\"llm\":{\"baseUrl\":\"https://api.example.test/v1\",\"model\":\"model-one\","
            + "\"credentialsRef\":null}}");
    assertThat(LlmRouteLoader.load(store()).credentialsRef()).isEmpty();

    writeConfig(
        "{\"llm\":{\"baseUrl\":\"https://api.example.test/v1\",\"model\":\"model-one\","
            + "\"credentialsRef\":\"  \"}}");
    assertThat(LlmRouteLoader.load(store()).credentialsRef()).isEmpty();
  }

  /**
   * R2 判别性：{@code credentialsRef} <b>存在但不是文本</b>时响亮（抓"把配错当匿名"的实现）。
   *
   * <p>与"缺失 = 匿名"刻意分开：缺失是用户明说了"不要密钥"，非文本是用户以为配了密钥——后者静默变匿名后， 故障会以"鉴权失败/匿名被拒"的形态在很远的地方爆出来。
   */
  @Test
  void nonTextualCredentialsRefIsLoudNotAnonymous() throws IOException {
    writeConfig(
        "{\"llm\":{\"baseUrl\":\"https://api.example.test/v1\",\"model\":\"model-one\","
            + "\"credentialsRef\":{\"env\":\"MY_API_KEY\"}}}");
    ConfigException failure =
        catchThrowableOfType(ConfigException.class, () -> LlmRouteLoader.load(store()));

    assertThat(failure).isNotNull();
    assertThat(failure.code()).isEqualTo(LlmRouteLoader.E_LLM_CONFIG_MISSING);
    assertThat(allMessages(failure)).noneMatch(message -> message.contains("MY_API_KEY"));
  }

  // ---------- 凭据安全 ----------

  /** 失败消息不回流任何配置值：配置里放含凭据的 URL（作为 baseUrl/credentialsRef）也不能出现在异常链里。 */
  @Test
  void messagesNeverEchoConfiguredValues() throws IOException {
    writeConfig(
        "{\"llm\":{\"baseUrl\":\""
            + CREDENTIAL_URL
            + "\",\"credentialsRef\":\""
            + CREDENTIAL_URL
            + "\"}}");
    ConfigException noModel =
        catchThrowableOfType(ConfigException.class, () -> LlmRouteLoader.load(store()));

    writeConfig(
        "{\"llm\":{\"baseUrl\":\""
            + CREDENTIAL_URL
            + "\",\"model\":\"model-one\","
            + "\"credentialsRef\":[1,2]}}");
    ConfigException badRef =
        catchThrowableOfType(ConfigException.class, () -> LlmRouteLoader.load(store()));

    for (ConfigException failure : List.of(noModel, badRef)) {
      assertThat(failure).isNotNull();
      assertThat(allMessages(failure)).noneMatch(message -> message.contains("sk-live-SECRET123"));
    }
  }

  // ---------- 工具 ----------

  private FileConfigStore store() {
    return new FileConfigStore(tempDir);
  }

  private void writeConfig(String json) throws IOException {
    Files.write(tempDir.resolve("config.json"), json.getBytes(StandardCharsets.UTF_8));
  }

  private static String config(String baseUrl, String model, String credentialsRef) {
    return "{\"llm\":{\"baseUrl\":\""
        + baseUrl
        + "\",\"model\":\""
        + model
        + "\",\"credentialsRef\":\""
        + credentialsRef
        + "\"}}";
  }

  /** 异常链全部 message（含逐层 cause）——凭据安全断言用。 */
  private static List<String> allMessages(Throwable throwable) {
    List<String> messages = new ArrayList<>();
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      messages.add(String.valueOf(current.getMessage()));
    }
    return messages;
  }
}
