package io.mosire.main;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.FileConfigStore;
import io.mosire.agentlib.llm.ConfigApiKeySource;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmRouteLoader;
import io.mosire.agentlib.llm.ModelRoute;
import io.mosire.agentlib.llm.OpenAICompatibleLlmClient;
import io.mosire.agentlib.permission.AccessToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Main#realLlm(Path)}（三期 S1-A1 R6）的装配测试：真客户端成型、缺配置/缺密钥响亮失败、绝不退化回假 LLM。
 *
 * <p><b>全程离线</b>：断言的是"装配成了什么 / 怎么失败"，不是"网络通不通"（真连通性归 S1-B 探索测试，D22）。 即便是 {@code chat}
 * 那条用例也不发请求：{@code OpenAICompatibleLlmClient} 取密钥先于任何 HTTP 动作（{@code exchange} 的第一句是 {@code
 * apiKeyOf()}），密钥取不到时在建连之前就失败。
 */
class MainRealLlmTest {

  private static final String CREDENTIAL = "sk-test-placeholder";

  @TempDir Path tempDir;

  // ---------- ⑥ 真客户端成型 ----------

  /** ⑥：有 {@code llm.*} 与密钥 → 不抛，且返回的是真客户端（不是 FakeLlmClient 之类的退化物）。 */
  @Test
  void realLlmBuildsRealClientFromConfig() throws IOException {
    writeConfig(
        "{\"keys\":{\"deepseek\":\""
            + CREDENTIAL
            + "\"},"
            + "\"llm\":{\"baseUrl\":\"https://api.example.test/v1\",\"model\":\"model-one\","
            + "\"credentialsRef\":\"keys.deepseek\"}}");

    LlmClient client = Main.realLlm(tempDir);

    assertThat(client).isInstanceOf(OpenAICompatibleLlmClient.class);
    assertThat(((OpenAICompatibleLlmClient) client).route().credentialsRef())
        .isEqualTo("keys.deepseek");
  }

  // ---------- ⑦ 缺配置响亮 ----------

  /** ⑦：空数据目录（无 {@code config.json}）→ 装配期响亮抛（非 demo 的默认路径不会退回假 LLM）。 */
  @Test
  void realLlmFailsLoudlyWhenConfigIsMissing() {
    ConfigException failure =
        catchThrowableOfType(ConfigException.class, () -> Main.realLlm(tempDir));

    assertThat(failure).isNotNull();
    assertThat(failure.code()).isEqualTo(LlmRouteLoader.E_LLM_CONFIG_MISSING);
    assertThat(failure.getMessage()).contains(LlmRouteLoader.E_LLM_CONFIG_MISSING);
    assertThat(allMessages(failure)).noneMatch(message -> message.contains("sk-"));
  }

  // ---------- ⑧ 缺密钥响亮（构造期 vs 取密钥期） ----------

  /**
   * ⑧：{@code llm.*} 齐全但 {@code keys.deepseek} 缺失 → 装配<b>不</b>抛，取密钥时抛（{@code E_KEY_MISSING}）。
   *
   * <p>为什么这样选（简报 ⑧ 给了二选一）：本断言语义<b>更准确</b>——{@link ConfigApiKeySource} 构造期不读配置，取密钥发生在 {@code
   * apiKey()}，这正是 SPI 契约"每次 {@code chat} 重新取一次（便于轮换/过期感知）"的落地方式；把密钥在装配期固化反而会破坏该契约。 "响亮"由 {@link
   * #realClientFailsChatInsteadOfFallingBackToFakeReplies()} 在调用面坐实（chat 抛 {@code LlmException}，且
   * cause 链上带着 {@code E_KEY_MISSING}）。
   */
  @Test
  void missingKeyIsLoudAtKeyFetchTimeNotAtAssemblyTime() throws IOException {
    writeConfig(
        "{\"llm\":{\"baseUrl\":\"https://api.example.test/v1\",\"model\":\"model-one\","
            + "\"credentialsRef\":\"keys.deepseek\"}}");

    // 装配不抛：密钥不在构造期固化（否则轮换/过期感知失效）
    assertThatCode(() -> Main.realLlm(tempDir)).doesNotThrowAnyException();

    // 装配路径上的取密钥点：同一配置根、同一 ref（与 realLlm 内部逐句等价）
    FileConfigStore store = new FileConfigStore(tempDir);
    ModelRoute route = LlmRouteLoader.load(store);
    ConfigApiKeySource source =
        new ConfigApiKeySource(store, route.credentialsRef(), AccessToken.SYSTEM);
    ConfigException failure = catchThrowableOfType(ConfigException.class, source::apiKey);

    assertThat(failure).isNotNull();
    assertThat(failure.code()).isEqualTo(ConfigApiKeySource.E_KEY_MISSING);
    assertThat(allMessages(failure)).noneMatch(message -> message.contains("sk-"));
  }

  /**
   * 调用面证据：缺密钥时真客户端的 {@code chat} 抛 {@link LlmException}——<b>不是</b>返回假回复、也不是静默匿名调用。
   *
   * <p>本用例零 I/O：密钥取不到发生在建连之前。这也正是"绝不静默退化回 {@code FakeLlmClient}"在调用面的判别性证据。
   */
  @Test
  void realClientFailsChatInsteadOfFallingBackToFakeReplies() throws IOException {
    writeConfig(
        "{\"llm\":{\"baseUrl\":\"https://api.example.test/v1\",\"model\":\"model-one\","
            + "\"credentialsRef\":\"keys.deepseek\"}}");
    LlmClient client = Main.realLlm(tempDir);

    LlmException failure =
        catchThrowableOfType(
            LlmException.class,
            () -> client.chat(LlmRequest.ofMessages(List.of(LlmMessage.user("ping")))));

    assertThat(failure).isNotNull();
    assertThat(failure.getMessage()).as("客户端对取密钥失败只给固定非敏感文案").contains("取密钥失败");
    assertThat(allMessages(failure)).noneMatch(message -> message.contains("sk-"));
    assertThat(allMessages(failure)).anyMatch(message -> message.contains("E_KEY_MISSING"));
  }

  // ---------- 工具 ----------

  private void writeConfig(String json) throws IOException {
    Files.write(tempDir.resolve("config.json"), json.getBytes(StandardCharsets.UTF_8));
  }

  /** 异常链全部 message（含逐层 cause）——凭据安全与错误码可见性断言用。 */
  private static List<String> allMessages(Throwable throwable) {
    List<String> messages = new ArrayList<>();
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      messages.add(String.valueOf(current.getMessage()));
    }
    return messages;
  }
}
