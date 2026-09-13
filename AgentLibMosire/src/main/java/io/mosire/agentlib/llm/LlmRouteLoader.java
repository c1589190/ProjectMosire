package io.mosire.agentlib.llm;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.ConfigStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 把配置读成 {@link ModelRoute}：<b>扁平形态</b> {@code llm.baseUrl} / {@code llm.model} / {@code
 * llm.credentialsRef}（= 名为 {@value #DEFAULT_ROUTE_NAME} 的路由），或<b>具名路由表</b> {@code
 * llm.routes.<name>.{baseUrl,model,credentialsRef}}（多 provider 并存，2026-09-14）。
 *
 * <p><b>缺必填 → 响亮</b>（D24）：{@code baseUrl} 与 {@code model} 缺失（或不是非空文本）抛 {@link
 * #E_LLM_CONFIG_MISSING}，消息点名缺的是<b>哪个键</b>（{@code llm.baseUrl} / {@code
 * llm.routes.glm.model}）——两类缺配置必须可分辨， 否则"配错了哪一项"只能靠猜。
 *
 * <p><b>{@code credentialsRef} 允许缺失</b>（→ 空串 = 有意的匿名，本地部署如 Ollama）；但<b>存在却不是文本</b>时响亮失败——
 * 把"配错了"当成"匿名部署"是本项目最坏的失败形态。取值只做归一化（去首尾空白），不做形态校验：形态判定归 {@link ConfigApiKeySource}（那里才知道哪些形态受支持）。
 *
 * <p><b>点名了一条不存在的路由 → 响亮且可分辨</b>：{@link #E_LLM_ROUTE_UNKNOWN}（消息带请求的名字 + 可用名字），
 * <b>绝不</b>静默回落到另一条路由——"名字写错"与"配置没写"是两种修法，运维要能一眼分开。
 *
 * <p>本类<b>不</b>校验 {@code baseUrl} 是否是合法 URL：URL 解析失败由 {@link OpenAICompatibleLlmClient} 在构造期响亮抛出（
 * {@code URI.create}），在此重复一遍只会得到两条不一致的失败文案。
 *
 * <p><b>路由表是全局的，按 agent 只能选名字</b>（{@link #routeName}）：端点与密钥形态属运维面，不属 agent 自选面。
 */
public final class LlmRouteLoader {

  /** {@code llm.baseUrl} / {@code llm.model}（或非文本的 {@code llm.credentialsRef}）不可用。 */
  public static final String E_LLM_CONFIG_MISSING = "E_LLM_CONFIG_MISSING";

  /** 点名的路由不在 {@code llm.routes} 表里（或名字非法、或没有路由表却点了非 default 的名）。 */
  public static final String E_LLM_ROUTE_UNKNOWN = "E_LLM_ROUTE_UNKNOWN";

  /** 配置树里承载 LLM 路由的段。 */
  private static final String SECTION = "llm";

  private static final String KEY_BASE_URL = "baseUrl";
  private static final String KEY_MODEL = "model";
  private static final String KEY_CREDENTIALS_REF = "credentialsRef";

  /** 选哪条路由（可选，默认 {@value #DEFAULT_ROUTE_NAME}）；也用于按 agent 覆盖（见 {@link #routeName}）。 */
  private static final String KEY_ROUTE = "route";

  /** 具名路由表。 */
  private static final String KEY_ROUTES = "routes";

  /**
   * 默认路由名：扁平形态 {@code llm.*} 就是它（老配置一字不改照跑），也是 {@code llm.route} 缺席时的取值。
   *
   * <p>取固定 {@code "default"} 而非从 {@code model}/URL 派生：①它必须在配置改写之间保持稳定（日志/事件里作为路由标识用）；②
   * 派生值会把"模型名"与"路由名"混为一谈；③"default" 是"唯一隐含条目"的惯例叫法。
   */
  private static final String DEFAULT_ROUTE_NAME = "default";

  /**
   * 路由名/agent id 的合法字符集：名字是往配置树里<b>寻址的段</b>（{@code llm.routes.<name>.*}），带点的名字会指到别的节点上， 故与 {@code
   * ConfigAuth.AGENT_ID} 同族收紧。
   */
  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

  private LlmRouteLoader() {}

  /**
   * 读默认路由（= {@link #load(ConfigStore, String) load(store, "default")}）。保留此单参形态：既有调用点与用例的语义逐字不变。
   *
   * @param store 配置存储（本进程配置根上的实例，如 {@code new FileConfigStore(dataDir)}）
   * @return 由配置构造的路由
   * @throws ConfigException {@code E_LLM_CONFIG_MISSING} / {@code E_LLM_ROUTE_UNKNOWN}——消息不含任何配置值
   */
  public static ModelRoute load(ConfigStore store) {
    return load(store, DEFAULT_ROUTE_NAME);
  }

  /**
   * 按名读一条路由。
   *
   * <p>解析顺序：①{@code llm.routes.<name>} 命中即用它；②否则若 {@code name} 是 {@value #DEFAULT_ROUTE_NAME} 且扁平
   * {@code llm.baseUrl}/{@code llm.model} 在场，用扁平形态（兼容层）；③否则 {@link #E_LLM_ROUTE_UNKNOWN}。
   *
   * @param store 配置存储（本进程配置根上的实例）
   * @param routeName 路由名（{@code null}/空白 → 默认路由）
   * @return 该名字对应的路由，{@code name} = {@code routeName}
   * @throws ConfigException {@code E_LLM_CONFIG_MISSING}（必填键缺失/非文本、{@code llm.routes} 非对象、或 {@code
   *     credentialsRef} 非文本）或 {@code E_LLM_ROUTE_UNKNOWN}（名字非法/不在表里/没有路由表却点了非默认名）——消息不含任何配置值
   */
  public static ModelRoute load(ConfigStore store, String routeName) {
    Objects.requireNonNull(store, "store");
    String name = normalizeName(routeName);
    Optional<JsonNode> named = namedRouteNode(store, name);
    if (named.isPresent()) {
      JsonNode node = named.get();
      if (!node.isObject()) {
        throw new ConfigException(
            E_LLM_CONFIG_MISSING,
            "LLM 路由配置不可用："
                + SECTION
                + "."
                + KEY_ROUTES
                + "."
                + name
                + " 必须是对象（含 baseUrl/model/credentialsRef）");
      }
      String prefix = SECTION + "." + KEY_ROUTES + "." + name;
      return new ModelRoute(
          name,
          required(store, prefix, KEY_BASE_URL),
          required(store, prefix, KEY_MODEL),
          credentialsRef(store, prefix));
    }
    return flatOrDefault(store, name);
  }

  /**
   * 解析某 agent 该走哪条路由：①{@code agents.<ownerId>.llm.route} → ②{@code llm.route} → ③{@value
   * #DEFAULT_ROUTE_NAME}。
   *
   * <p><b>回退必须显式写</b>：{@code FileConfigStore} 是"整键命中即返回"、<b>不做跨前缀回退</b>， 即 {@code
   * store.get("agents.bob", "llm.route")} 落空时<b>不会</b>自动落到全局 {@code llm.route}。
   *
   * <p>覆盖只到"选名字"这一层：路由表（端点/模型/密钥引用）是全局的——见类 Javadoc。
   *
   * @param store 配置存储
   * @param ownerId 主 Agent 传其 id（{@code main}）；子 Agent 传 {@code templateId}（稳定、可按模板配）
   * @return 路由名（绝不会是空白）
   * @throws ConfigException {@code E_LLM_CONFIG_MISSING}（{@code route} 存在但非非空文本）
   */
  public static String routeName(ConfigStore store, String ownerId) {
    Objects.requireNonNull(store, "store");
    // 寻址 = agents.<id>.llm.route：agent 分支拿的是 key（"llm.route"）去 agents/<id>.json 里下钻
    // （FileConfigStore 的 agent 分支用 key、全局分支用 fullKey）——所以前缀是 "agents.<id>"、键带 "llm."；
    // 写成 (prefix="agents.bob", key="route") 会去读 bob.json 的顶层 "route"，键不同且不报错
    Optional<String> perAgent = textAt(store, "agents." + ownerId, SECTION + "." + KEY_ROUTE);
    if (perAgent.isPresent()) {
      return perAgent.get();
    }
    return textAt(store, SECTION, KEY_ROUTE).orElse(DEFAULT_ROUTE_NAME);
  }

  /** 已登记的路由名（升序；含扁平形态隐含的 {@value #DEFAULT_ROUTE_NAME}，若它在场）。错误消息用。 */
  public static List<String> availableNames(ConfigStore store) {
    Objects.requireNonNull(store, "store");
    List<String> names = new ArrayList<>();
    Optional<JsonNode> routes = routesNode(store);
    if (routes.isPresent() && routes.get().isObject()) {
      routes.get().fieldNames().forEachRemaining(names::add);
    }
    if (!names.contains(DEFAULT_ROUTE_NAME) && !flatAbsent(store)) {
      names.add(DEFAULT_ROUTE_NAME);
    }
    Collections.sort(names);
    return names;
  }

  /** 具名路由节点（{@code llm.routes.<name>}）；无路由表/表里没这个名字 → 空。名字非法已由调用方拦下。 */
  private static Optional<JsonNode> namedRouteNode(ConfigStore store, String name) {
    Optional<JsonNode> routes = routesNode(store);
    if (routes.isEmpty()) {
      return Optional.empty();
    }
    JsonNode node = routes.get();
    if (!node.isObject()) {
      throw new ConfigException(
          E_LLM_CONFIG_MISSING, "LLM 路由配置不可用：" + SECTION + "." + KEY_ROUTES + " 必须是对象（路由名 → 路由）");
    }
    JsonNode named = node.get(name);
    return named == null ? Optional.empty() : Optional.of(named);
  }

  /** {@code llm.routes} 节点；缺席 → 空。 */
  private static Optional<JsonNode> routesNode(ConfigStore store) {
    JsonNode node = store.get(SECTION, KEY_ROUTES).orElse(null);
    return node == null || node.isNull() ? Optional.empty() : Optional.of(node);
  }

  /** 扁平形态：仅当点名的是默认路由、且扁平键在场时可用；否则响亮。 */
  private static ModelRoute flatOrDefault(ConfigStore store, String name) {
    if (!DEFAULT_ROUTE_NAME.equals(name)) {
      throw unknownRoute(store, name);
    }
    // 有路由表却没点名、表里也没有 default ⇒ "路由不存在"（列可用名最有用）；没有路由表 ⇒ 走下面的 required()，
    // 抛 E_LLM_CONFIG_MISSING——"LLM 配置整个没写"的既有码被脚本与进程级用例逐字断言，不许改口径
    if (flatAbsent(store) && hasRoutesTable(store)) {
      throw unknownRoute(store, name);
    }
    return new ModelRoute(
        name,
        required(store, SECTION, KEY_BASE_URL),
        required(store, SECTION, KEY_MODEL),
        credentialsRef(store, SECTION));
  }

  /** 是否有<b>非空</b>路由表（决定"默认路由取不到"该报哪个码：路由不存在 vs 配置整个没写）。 */
  private static boolean hasRoutesTable(ConfigStore store) {
    return routesNode(store).map(node -> node.isObject() && !node.isEmpty()).orElse(false);
  }

  /** 扁平形态是否完全缺席（两个必填键一个都没有）——只有"一个都没有"才叫没配扁平形态；半配要走响亮报错。 */
  private static boolean flatAbsent(ConfigStore store) {
    return store.get(SECTION, KEY_BASE_URL).isEmpty() && store.get(SECTION, KEY_MODEL).isEmpty();
  }

  /** 未知路由：响亮 + 可分辨（带请求名与可用名，便于一眼看出是拼错还是没配）。 */
  private static ConfigException unknownRoute(ConfigStore store, String name) {
    List<String> available = availableNames(store);
    String hint =
        available.isEmpty() ? "（当前没有任何已配置的路由）" : "（可用：" + String.join(", ", available) + "）";
    return new ConfigException(
        E_LLM_ROUTE_UNKNOWN,
        "LLM 路由不可用：点名了不存在的路由 \""
            + name
            + "\" "
            + hint
            + "——请检查配置根的 config.json 的 "
            + SECTION
            + "."
            + KEY_ROUTE
            + " 与 "
            + SECTION
            + "."
            + KEY_ROUTES
            + "（本项绝不回退到别的路由）");
  }

  /** 路由名归一化 + 形态校验；空白 → 默认。名字非法 ⇒ 未知路由（它不可能存在）。 */
  private static String normalizeName(String routeName) {
    if (routeName == null || routeName.isBlank()) {
      return DEFAULT_ROUTE_NAME;
    }
    String name = routeName.strip();
    if (!SAFE_NAME.matcher(name).matches()) {
      throw new ConfigException(
          E_LLM_ROUTE_UNKNOWN, "LLM 路由名非法：只能由字母/数字/下划线/连字符组成，且不以连字符开头（它是配置树里的寻址段）");
    }
    return name;
  }

  /** 读一个可选的非空文本键：缺席/null → 空；存在但非文本或空白 → 响亮。 */
  private static Optional<String> textAt(ConfigStore store, String prefix, String key) {
    JsonNode value = store.get(prefix, key).orElse(null);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new ConfigException(
          E_LLM_CONFIG_MISSING, "LLM 路由配置不可用：" + prefix + "." + key + " 必须是非空文本");
    }
    return Optional.of(value.asText().strip());
  }

  /** 必填文本键：缺失/非文本/空白一律响亮，消息带键名（两类缺配置可分辨）。 */
  private static String required(ConfigStore store, String prefix, String key) {
    JsonNode value = store.get(prefix, key).orElse(null);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new ConfigException(
          E_LLM_CONFIG_MISSING,
          "LLM 路由配置不可用：" + prefix + "." + key + " 缺失或不是非空文本（请检查配置根的 config.json）");
    }
    return value.asText();
  }

  /** 可选引用：缺失/JSON null = 匿名（空串）；存在但非文本 = 响亮（不许把配错当成匿名）。 */
  private static String credentialsRef(ConfigStore store, String prefix) {
    JsonNode value = store.get(prefix, KEY_CREDENTIALS_REF).orElse(null);
    if (value == null || value.isNull()) {
      return "";
    }
    if (!value.isTextual()) {
      throw new ConfigException(
          E_LLM_CONFIG_MISSING,
          "LLM 路由配置不可用：" + prefix + "." + KEY_CREDENTIALS_REF + " 必须是文本（整项缺失才表示匿名调用）");
    }
    return value.asText().strip();
  }
}
