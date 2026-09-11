package io.mosire.agentlib.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 构造期 {@code baseUrl} 非法时的凭据安全门禁（三期 S1-A1 F3）。
 *
 * <p>背景：{@code Main run} 的非 demo 路径会直接构造真客户端（S1-A1 R6），且构造期异常原样打到 stderr（{@code Main.main} 捕获后
 * {@code printStackTrace}）；而 {@code baseUrl} 允许内嵌凭据（{@code https://user:token@host/v1}）。JDK {@code
 * URI.create} 的原生消息会<b>回显整条输入串</b>，于是畸形 baseUrl 会把凭据送到 stderr。这里钉住"消息与整条异常链都不含 baseUrl 原文"，
 * 同时钉住"仍然响亮"（不许退化成无信息的静默失败）。
 */
class OpenAICompatibleLlmClientBaseUrlSafetyTest {

  private static final String CREDENTIAL = "sk-live-SECRET123";

  /** 路径含空格的合法 authority——{@code URI.create} 必抛 IAE，且原生消息回显整条串（含 userinfo 里的凭据）。 */
  private static final String MALFORMED_WITH_CREDENTIAL =
      "https://user:" + CREDENTIAL + "@vault.local/v1 bad path";

  private static final String MALFORMED_PLAIN = "https://api.example.test/v1 bad path";

  /** 语义零变化：畸形 baseUrl 仍在构造期响亮抛 {@code IllegalArgumentException}，并给出可读分类。 */
  @Test
  void malformedBaseUrlStaysLoud() {
    IllegalArgumentException failure =
        catchThrowableOfType(
            IllegalArgumentException.class,
            () -> new OpenAICompatibleLlmClient(route(MALFORMED_PLAIN)));

    assertThat(failure).isNotNull();
    assertThat(failure.getMessage()).contains("baseUrl 非法");
  }

  /** 内嵌凭据的畸形 baseUrl：凭据值、主机名、路径片段都不得出现在消息里（防部分回显/部分掩码）。 */
  @Test
  void malformedBaseUrlNeverEchoesTheCredentialOrTheUrl() {
    IllegalArgumentException failure =
        catchThrowableOfType(
            IllegalArgumentException.class,
            () -> new OpenAICompatibleLlmClient(route(MALFORMED_WITH_CREDENTIAL)));

    assertThat(failure).isNotNull();
    List<String> messages = allMessages(failure);
    assertThat(messages).noneMatch(message -> message.contains(CREDENTIAL));
    assertThat(messages).noneMatch(message -> message.contains("vault.local"));
    assertThat(messages).noneMatch(message -> message.contains("bad path"));
  }

  /**
   * 原生 IAE 的 {@code getMessage} 带着整条 URL：既不能当 cause、也不能挂 suppressed（{@code printStackTrace}
   * 会把两者都打出来）。
   */
  @Test
  void malformedBaseUrlLeavesNoCauseNorSuppressedToWalkInto() {
    IllegalArgumentException failure =
        catchThrowableOfType(
            IllegalArgumentException.class,
            () -> new OpenAICompatibleLlmClient(route(MALFORMED_WITH_CREDENTIAL)));

    assertThat(failure).isNotNull();
    assertThat(failure.getCause()).isNull();
    assertThat(failure.getSuppressed()).isEmpty();
    assertThat(failure.getMessage()).contains("baseUrl 非法");
  }

  /** 合法 baseUrl（含内嵌凭据）不受影响：构造正常、路由可读。 */
  @Test
  void wellFormedBaseUrlIsUnaffected() {
    ModelRoute route =
        ModelRoute.of("default", "https://user:" + CREDENTIAL + "@vault.local/v1", "m", "");

    OpenAICompatibleLlmClient client = new OpenAICompatibleLlmClient(route);

    assertThat(client.route().baseUrl()).contains("vault.local");
  }

  private static ModelRoute route(String baseUrl) {
    return ModelRoute.of("default", baseUrl, "model-one", "");
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
