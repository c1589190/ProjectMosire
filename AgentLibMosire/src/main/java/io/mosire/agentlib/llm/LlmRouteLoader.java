package io.mosire.agentlib.llm;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.ConfigStore;
import java.util.Objects;

/**
 * 把配置里的 {@code llm.*} 读成唯一一条 {@link ModelRoute}（三期 S1-A1）：{@code llm.baseUrl} / {@code llm.model} /
 * {@code llm.credentialsRef} → {@code ModelRoute}。
 *
 * <p><b>缺必填 → 响亮</b>（D24）：{@code baseUrl} 与 {@code model} 缺失（或不是非空文本）抛 {@link
 * #E_LLM_CONFIG_MISSING}，消息点名缺的是<b>哪个键</b>（{@code llm.baseUrl} / {@code llm.model}）——两类缺配置必须可分辨，
 * 否则"配错了哪一项"只能靠猜。
 *
 * <p><b>{@code credentialsRef} 允许缺失</b>（→ 空串 = 有意的匿名，本地部署如 Ollama）；但<b>存在却不是文本</b>时响亮失败——
 * 把"配错了"当成"匿名部署"是本项目最坏的失败形态。取值只做归一化（去首尾空白），不做形态校验：形态判定归 {@link ConfigApiKeySource}（那里才知道哪些形态受支持）。
 *
 * <p>本类<b>不</b>校验 {@code baseUrl} 是否是合法 URL：URL 解析失败由 {@link OpenAICompatibleLlmClient} 在构造期响亮抛出（
 * {@code URI.create}），在此重复一遍只会得到两条不一致的失败文案。
 */
public final class LlmRouteLoader {

  /** {@code llm.baseUrl} / {@code llm.model}（或非文本的 {@code llm.credentialsRef}）不可用。 */
  public static final String E_LLM_CONFIG_MISSING = "E_LLM_CONFIG_MISSING";

  /** 配置树里承载 LLM 路由的段。 */
  private static final String SECTION = "llm";

  private static final String KEY_BASE_URL = "baseUrl";
  private static final String KEY_MODEL = "model";
  private static final String KEY_CREDENTIALS_REF = "credentialsRef";

  /**
   * 路由名：配置里<b>没有</b>对应键（{@code llm.name} 不存在，本加载器也只解析唯一一条路由，没有按名选择的余地）。
   *
   * <p>取固定 {@code "default"} 而非从 {@code model}/URL 派生：①它必须在配置改写之间保持稳定（日志/事件里作为路由标识用）；②
   * 派生值会把"模型名"与"路由名"混为一谈，等到真出现多路由（按名选择）时两者语义不同；③"default" 是"唯一隐含条目"的惯例叫法。
   */
  private static final String DEFAULT_ROUTE_NAME = "default";

  private LlmRouteLoader() {}

  /**
   * 读一条路由。
   *
   * @param store 配置存储（本进程配置根上的实例，如 {@code new FileConfigStore(dataDir)}）
   * @return 由 {@code llm.*} 构造的路由，{@code name} = {@code "default"}
   * @throws ConfigException {@code E_LLM_CONFIG_MISSING}（必填键缺失/非文本，或 {@code credentialsRef}
   *     非文本）——消息不含任何配置值
   */
  public static ModelRoute load(ConfigStore store) {
    Objects.requireNonNull(store, "store");
    String baseUrl = required(store, KEY_BASE_URL);
    String model = required(store, KEY_MODEL);
    return new ModelRoute(DEFAULT_ROUTE_NAME, baseUrl, model, credentialsRef(store));
  }

  /** 必填文本键：缺失/非文本/空白一律响亮，消息带键名（两类缺配置可分辨）。 */
  private static String required(ConfigStore store, String key) {
    JsonNode value = store.get(SECTION, key).orElse(null);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new ConfigException(
          E_LLM_CONFIG_MISSING,
          "LLM 路由配置不可用：" + SECTION + "." + key + " 缺失或不是非空文本（请检查配置根的 config.json）");
    }
    return value.asText();
  }

  /** 可选引用：缺失/JSON null = 匿名（空串）；存在但非文本 = 响亮（不许把配错当成匿名）。 */
  private static String credentialsRef(ConfigStore store) {
    JsonNode value = store.get(SECTION, KEY_CREDENTIALS_REF).orElse(null);
    if (value == null || value.isNull()) {
      return "";
    }
    if (!value.isTextual()) {
      throw new ConfigException(
          E_LLM_CONFIG_MISSING,
          "LLM 路由配置不可用：" + SECTION + "." + KEY_CREDENTIALS_REF + " 必须是文本（整项缺失才表示匿名调用）");
    }
    return value.asText().strip();
  }
}
