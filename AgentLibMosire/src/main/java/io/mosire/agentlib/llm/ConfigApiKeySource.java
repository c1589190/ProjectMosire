package io.mosire.agentlib.llm;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.ConfigStore;
import io.mosire.agentlib.permission.AccessToken;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 从 {@link ConfigStore} 的 {@code keys.*} 取密钥的 SPI 生产实现（三期 S1-A1）：把"本进程自己的 LLM 密钥从哪来"接到配置存储上。
 *
 * <p><b>支持的引用形态——有意收窄</b>：{@link ModelRoute} 的 Javadoc 写 {@code credentialsRef} 可以是「环境变量名或 {@code
 * keys.*} 配置键」，<b>本实现有意只支持 {@code keys.<name>}</b>，其余形态一律响亮拒绝（{@link
 * #E_REF_FORM_UNSUPPORTED}）：读环境变量取密钥是 T18 R2 明令禁止的第二条密钥通路。收窄发生在<b>本实现</b>，故 {@code ModelRoute}
 * 的措辞<b>不</b>因此修改（它描述的是记录本身的取值域， 不是每个实现的保证）。
 *
 * <p><b>绝不静默返回空</b>（D24）：引用空白 = <b>有意的匿名</b>调用（本地部署如 Ollama，正常路径，不是错误）；引用是 {@code keys.<name>}
 * 但配置里没有可用值 = 响亮抛（{@link #E_KEY_MISSING}）；引用是别的形态 = 响亮抛（{@link
 * #E_REF_FORM_UNSUPPORTED}）。让"配错了"看起来像"匿名部署"是本类最要避免的失败。
 *
 * <p><b>token 参数不是摆设</b>：构造时要求 {@code token.atLeast(AccessToken.SYSTEM)}，不满足 → 构造即失败（{@link
 * #E_TOKEN_TOO_LOW}），<b>此时一次配置都不读</b>。这里的 {@link AccessToken} 是<b>进程运行时身份</b>——"取本进程自己的 LLM
 * 密钥"只对该身份开放；它与"agent 的工具权限"（某个 agent 能调哪些工具、能否读某前缀）是<b>两回事</b>。读侧分档的完整规则待 T21a 裁决， 届时再决定是否放宽本检查。
 *
 * <p><b>每次 {@link #apiKey()} 现读</b>：不缓存、不在字段里长期持有密钥值——SPI 契约要求每次 {@code chat} 重新取一次，以便实现轮换/过期感知。
 *
 * <p><b>凭据安全</b>：不读环境变量/系统属性；密钥值不进日志、不进异常消息、不进 URL；引用名只在保守白名单字符集内才回显（见 {@link
 * #safeName}），因为引用名本身可能是"含凭据的 URL"。异常消息的契约见 {@link ConfigException}。
 */
public final class ConfigApiKeySource implements OpenAICompatibleLlmClient.ApiKeySource {

  /** 进程运行时身份低于 {@link AccessToken#SYSTEM}：取本进程 LLM 密钥不在该身份的可及范围内。 */
  public static final String E_TOKEN_TOO_LOW = "E_TOKEN_TOO_LOW";

  /** {@code keys.<name>} 形态合法，但配置里没有可取用的值（不存在 / null / 容器 / 空文本）。 */
  public static final String E_KEY_MISSING = "E_KEY_MISSING";

  /** {@code credentialsRef} 既非空白（匿名）也非 {@code keys.<name>}（典型如环境变量名）。 */
  public static final String E_REF_FORM_UNSUPPORTED = "E_REF_FORM_UNSUPPORTED";

  /** 唯一受支持的引用前缀。 */
  private static final String KEYS_PREFIX = "keys.";

  /** 配置里承载密钥的顶层段（{@code store.get("keys", "<name>")}）。 */
  private static final String KEYS_SECTION = "keys";

  /** 可安全回显的引用名（保守白名单：不含点、冒号、斜杠等可拼出 URL/路径的字符）。 */
  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  /** 不可安全回显时的占位（宁可少给诊断信息，也不把疑似凭据的串写进会被持久化的事件流）。 */
  private static final String REDACTED_NAME = "<不可显示的引用名>";

  private final ConfigStore store;

  /** 已归一化（null → 空串，去首尾空白）：空 = 匿名。 */
  private final String credentialsRef;

  /**
   * @param store 配置存储（只经 {@link ConfigStore#get} 读 {@code keys.*}；本类不写配置、无权限参数——读侧分档待 T21a）
   * @param credentialsRef 密钥引用；null/空白 = 有意的匿名调用
   * @param token 调用方进程运行时身份，必须至少 {@link AccessToken#SYSTEM}
   * @throws ConfigException {@code E_TOKEN_TOO_LOW}（身份不足；此时未读任何配置）
   */
  public ConfigApiKeySource(ConfigStore store, String credentialsRef, AccessToken token) {
    // 身份检查必须最先发生：不满足时连配置存储都不许碰（否则"被忽略的 token"会伪装成已鉴权）
    requireSystem(token);
    this.store = Objects.requireNonNull(store, "store");
    this.credentialsRef = credentialsRef == null ? "" : credentialsRef.strip();
  }

  /**
   * 取当前密钥：每次调用都重读配置。
   *
   * @return 密钥；空 = 无密钥（匿名调用，请求不带 {@code Authorization}）
   * @throws ConfigException {@code E_KEY_MISSING}（{@code keys.<name>} 无可用值）或 {@code
   *     E_REF_FORM_UNSUPPORTED}（其它形态）——消息不含密钥值与原始引用串
   */
  @Override
  public Optional<String> apiKey() {
    if (credentialsRef.isEmpty()) {
      // 有意的匿名：ModelRoute 允许 credentialsRef 为空（本地部署），此处**不是**失败
      return Optional.empty();
    }
    if (!credentialsRef.startsWith(KEYS_PREFIX)) {
      // 绝不静默返回空：否则"把环境变量名写进配置"会被静默当成"匿名部署"，是最坏的失败形态
      throw new ConfigException(
          E_REF_FORM_UNSUPPORTED,
          "credentialsRef 形态不受支持：本实现只支持 "
              + KEYS_PREFIX
              + "<name> 形态（环境变量名等其它形态一律拒绝——读环境变量取密钥是被禁止的第二条密钥通路，见类 Javadoc）");
    }
    String name = credentialsRef.substring(KEYS_PREFIX.length());
    JsonNode value = store.get(KEYS_SECTION, name).orElse(null);
    if (value == null || value.isNull() || value.isContainerNode() || value.asText().isBlank()) {
      throw new ConfigException(
          E_KEY_MISSING,
          "配置里没有可取用的密钥："
              + KEYS_PREFIX
              + safeName(name)
              + "（不存在 / 为 null / 是容器 / 文本为空——一律响亮失败，绝不当成匿名部署）");
    }
    return Optional.of(value.asText());
  }

  /** 身份校验（构造期一次性，不把 token 存成字段：取密钥不需要二次判身份，也不留"还有一层校验"的错觉）。 */
  private static void requireSystem(AccessToken token) {
    if (token == null || !token.atLeast(AccessToken.SYSTEM)) {
      throw new ConfigException(
          E_TOKEN_TOO_LOW,
          "进程运行时身份不足：取本进程 LLM 密钥要求至少 "
              + AccessToken.SYSTEM
              + "，实际为 "
              + (token == null ? "（缺失）" : token)
              + "（身份维度与 agent 的工具权限是两回事，见类 Javadoc）");
    }
  }

  /**
   * 引用名的安全回显：只在保守白名单字符集内原样给出，否则用占位符。
   *
   * <p>理由：{@code credentialsRef} 是调用方给的任意字符串，可能本身就是"含凭据的 URL"（如 {@code
   * keys.https://user:token@host}）；异常消息会经 SPI 的 cause 链被上层记录甚至持久化，回显未净化的原串等于泄漏凭据（{@link
   * OpenAICompatibleLlmClient.ApiKeySource} 的硬性契约）。
   */
  private static String safeName(String name) {
    return SAFE_NAME.matcher(name).matches() ? name : REDACTED_NAME;
  }
}
