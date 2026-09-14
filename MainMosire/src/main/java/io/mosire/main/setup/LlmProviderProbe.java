package io.mosire.main.setup;

import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.ModelRoute;
import io.mosire.agentlib.llm.OpenAICompatibleLlmClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 引导探测（配置引导 D5）：用提交的 provider <b>真发一次</b> 1-token 补全——成功才允许写盘 + 热切换。
 * "配了但连不上"的部署是本仓反复踩过的失败形态，宁可在引导期响亮。
 *
 * <p>探测是真实计费调用（几个 token）；读超时取短值（20s），失败原样抛 {@link LlmException} （消息已由客户端净化，不含密钥）。
 */
public final class LlmProviderProbe {

  /** 探测读超时：给慢网关留余量，又不让引导者久等。 */
  static final Duration PROBE_READ_TIMEOUT = Duration.ofSeconds(20);

  private LlmProviderProbe() {}

  /**
   * 发一次最小补全。
   *
   * @param baseUrl 完整 OpenAI 兼容地址
   * @param model 模型名
   * @param apiKey 密钥；null/空白 = 匿名
   * @throws LlmException 网络/超时/非 2xx/协议违约——原样上抛（写盘不发生）
   */
  public static void ping(String baseUrl, String model, String apiKey) throws LlmException {
    ModelRoute route = new ModelRoute("probe", baseUrl, model, "");
    LlmClient client =
        new OpenAICompatibleLlmClient(
            route,
            source(apiKey),
            OpenAICompatibleLlmClient.DEFAULT_CONNECT_TIMEOUT,
            PROBE_READ_TIMEOUT);
    client.chat(LlmRequest.ofMessages(List.of(LlmMessage.user("ping"))));
  }

  private static OpenAICompatibleLlmClient.ApiKeySource source(String apiKey) {
    if (apiKey == null || apiKey.isBlank()) {
      return OpenAICompatibleLlmClient.ApiKeySource.none();
    }
    String value = apiKey;
    return () -> Optional.of(value);
  }
}
