package io.mosire.agentlib.approval;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.ConfigStore;
import java.time.Duration;
import java.util.Objects;

/**
 * 把配置里的 {@code approval.*} 读成一份 {@link ApprovalConfig}（三期 S4-B2，读法照 {@code LlmRouteLoader}）： {@code
 * approval.http} / {@code approval.timeoutSeconds} / {@code approval.httpPort}。
 *
 * <p><b>整项缺失 ⇒ 缺省值</b>（不是错误）：审批面在"没写过任何 approval 配置"的部署里照样要能起来（默认开 + 300 s）。 JSON {@code null}
 * 与缺失同口径（先例：{@code LlmRouteLoader} 的 {@code credentialsRef}）。
 *
 * <p><b>项存在但值非法 ⇒ 响亮失败</b>（{@link #E_APPROVAL_CONFIG}，消息点名是哪个键）：把 {@code approval.timeoutSeconds}
 * 写成 {@code -1} 或 {@code "300"} 时，静默回退缺省会得到一个"看起来配了、其实没生效"的部署——审批等待上限恰好是那种 不能靠猜的参数。负值不是"立刻拒"的写法（那是
 * {@code 0}），非数更不是。
 *
 * <p>本类<b>不</b>校验 {@code timeoutSeconds = 0} 之类的语义合法性之外的东西（{@code 0} 合法：零预算 ⇒ 立即拒，见 {@link
 * ApprovalConfig}）；也不碰任何通道实现——配置只描述"装配什么"，不描述"怎么问人"。
 */
public final class ApprovalConfigLoader {

  /** {@code approval.*} 项存在但值非法（类型/取值）。消息只含键名与（数值键的）原值，不含任何密钥。 */
  public static final String E_APPROVAL_CONFIG = "E_APPROVAL_CONFIG";

  /** 配置树里承载审批装配参数的段。 */
  private static final String SECTION = "approval";

  private static final String KEY_HTTP = "http";
  private static final String KEY_TIMEOUT_SECONDS = "timeoutSeconds";
  private static final String KEY_HTTP_PORT = "httpPort";

  private ApprovalConfigLoader() {}

  /**
   * 读一份审批配置。
   *
   * @param store 配置存储（本进程配置根上的实例，如 {@code new FileConfigStore(dataDir)}）
   * @throws ConfigException {@code E_APPROVAL_CONFIG}（项存在但类型/取值非法）
   */
  public static ApprovalConfig load(ConfigStore store) {
    Objects.requireNonNull(store, "store");
    return new ApprovalConfig(http(store), timeout(store), httpPort(store));
  }

  /** 开关：缺失 ⇒ 默认开；存在但非布尔 ⇒ 响亮。 */
  private static boolean http(ConfigStore store) {
    JsonNode value = valueOf(store, KEY_HTTP);
    if (value == null) {
      return ApprovalConfig.DEFAULT_HTTP;
    }
    if (!value.isBoolean()) {
      throw invalid(KEY_HTTP, "必须是布尔（true/false），实际类型 " + value.getNodeType(), value.asText());
    }
    return value.booleanValue();
  }

  /** 等待上限（秒）：缺失 ⇒ 300；存在但非整数/负数 ⇒ 响亮（0 合法 = 立刻拒）。 */
  private static Duration timeout(ConfigStore store) {
    JsonNode value = valueOf(store, KEY_TIMEOUT_SECONDS);
    if (value == null) {
      return Duration.ofSeconds(ApprovalConfig.DEFAULT_TIMEOUT_SECONDS);
    }
    if (!value.isIntegralNumber()) {
      throw invalid(KEY_TIMEOUT_SECONDS, "必须是整数秒（不接受的写法：小数/字符串/布尔）", value.asText());
    }
    long seconds = value.longValue();
    if (seconds < 0) {
      throw invalid(KEY_TIMEOUT_SECONDS, "不得为负（0 = 立刻拒，是合法的；负数不是）", value.asText());
    }
    return Duration.ofSeconds(seconds);
  }

  /** 端口：缺失 ⇒ 0（自动分配）；存在但非整数/越界 ⇒ 响亮。 */
  private static int httpPort(ConfigStore store) {
    JsonNode value = valueOf(store, KEY_HTTP_PORT);
    if (value == null) {
      return ApprovalConfig.DEFAULT_HTTP_PORT;
    }
    if (!value.isIntegralNumber()) {
      throw invalid(KEY_HTTP_PORT, "必须是整数端口（0 = 自动分配）", value.asText());
    }
    int port = value.intValue();
    if (port < 0 || port > 65535) {
      throw invalid(KEY_HTTP_PORT, "越界（合法范围 0..65535，0 = 自动分配）", value.asText());
    }
    return port;
  }

  /** 取键值：缺失与 JSON null 同口径（返回 null = 用缺省）。 */
  private static JsonNode valueOf(ConfigStore store, String key) {
    JsonNode value = store.get(SECTION, key).orElse(null);
    return value == null || value.isNull() ? null : value;
  }

  /** 非法值一律同一个错误码（"哪一项配错了"由消息点名，不靠码区分）——错误码是给调用方分支用的，不是给人读的。 */
  private static ConfigException invalid(String key, String reason, String rawValue) {
    return new ConfigException(
        E_APPROVAL_CONFIG,
        "审批配置不可用：" + SECTION + "." + key + " " + reason + "（实际值: " + rawValue + "）");
  }
}
