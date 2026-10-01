package io.mosire.agentlib.llm;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.ConfigStore;
import java.time.Duration;
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
 * <p><b>一条路由条目的完整键表</b>（具名形态；扁平形态把同样的键直接挂在 {@code llm} 下）：
 *
 * <pre>{@code
 * "llm": { "routes": { "<name>": {
 *     "baseUrl":         "https://api.deepseek.com/v1",  // 必填；API 根，不含 /chat/completions
 *     "model":           "deepseek-chat",                // 必填
 *     "credentialsRef":  "keys.deepseek",                // 选填；见 ConfigApiKeySource
 *     "protocol":        "openai-compatible",            // 选填；见 LlmProtocol（A3②）
 *     "timeoutMs":       120000,                         // 选填；SSE 流空闲超时（A3①；只要还在输出就不按总时长切）
 *     "connectTimeoutMs": 10000,                         // 选填；建连超时
 *     "capabilities":    { "toolCalling": true, "maxContext": 65536,
 *                          "echoReasoningContent": true } // 选填；见 ModelCapabilities
 * } } }
 * }</pre>
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
 * <p>本类<b>不</b>校验 {@code baseUrl} 是否是合法 URL、也不查它的形态：URL 解析失败与"把补全端点当根写进来"两种情况都由 {@link
 * OpenAICompatibleLlmClient} 在构造期响亮抛出（{@code URI.create} / 端点形态守卫），在此重复一遍只会得到两条不一致的失败文案。 {@code
 * baseUrl} 的语义（API 根 → 客户端自行拼 {@code /chat/completions}，{@code /v1} 要谁写）见 {@link ModelRoute} 的
 * Javadoc。
 *
 * <p><b>路由表是全局的，按 agent 只能选名字</b>（{@link #routeName}）：端点与密钥形态属运维面，不属 agent 自选面。
 */
public final class LlmRouteLoader {

  /** {@code llm.baseUrl} / {@code llm.model}（或非文本的 {@code llm.credentialsRef}）不可用。 */
  public static final String E_LLM_CONFIG_MISSING = "E_LLM_CONFIG_MISSING";

  /** 点名的路由不在 {@code llm.routes} 表里（或名字非法、或没有路由表却点了非 default 的名）。 */
  public static final String E_LLM_ROUTE_UNKNOWN = "E_LLM_ROUTE_UNKNOWN";

  /** {@code protocol} 写了一个本库不实现的方言——修法是"换一个受支持的方言"，与"少了必填项"的修法不同，故单列一码。 */
  public static final String E_LLM_PROTOCOL_UNKNOWN = "E_LLM_PROTOCOL_UNKNOWN";

  /** 配置树里承载 LLM 路由的段。 */
  private static final String SECTION = "llm";

  private static final String KEY_BASE_URL = "baseUrl";
  private static final String KEY_MODEL = "model";
  private static final String KEY_CREDENTIALS_REF = "credentialsRef";

  /**
   * SSE 流空闲超时（毫秒）——A3① + 2026-10-01：超时是 provider 的属性，故住在路由条目里。
   *
   * <p>语义是“连续多久没有新数据才判死”，不是“一次调用总时长上限”：reasoning 模型持续输出时不应被切。
   */
  private static final String KEY_TIMEOUT_MS = "timeoutMs";

  /** 建连超时（毫秒）。 */
  private static final String KEY_CONNECT_TIMEOUT_MS = "connectTimeoutMs";

  /** 协议/方言（A3②）。 */
  private static final String KEY_PROTOCOL = "protocol";

  /** 模型能力描述（{@link ModelCapabilities}，供 {@link ModelProvider} 登记用）。 */
  private static final String KEY_CAPABILITIES = "capabilities";

  /**
   * 思考模式回传位（A6 修复版）的配置键：住在 {@code capabilities} 块里。
   *
   * <p>它的语义是"发送侧是否对每条 assistant 消息恒发 {@code reasoning_content}"——放在能力块里是因为它是路由/模型能力的一部分
   * （不是采样参数、也不是某条消息的属性），并让 {@code simos.llm.providers} 的掩码视图天然能显示它。
   */
  private static final String KEY_ECHO_REASONING_CONTENT = "echoReasoningContent";

  /** 选哪条路由（可选，默认 {@value #DEFAULT_ROUTE_NAME}）；也用于按 agent 覆盖（见 {@link #routeName}）。 */
  private static final String KEY_ROUTE = "route";

  /** 具名路由表。 */
  private static final String KEY_ROUTES = "routes";

  /**
   * 默认路由名：扁平形态 {@code llm.*} 就是它（老配置一字不改照跑），也是 {@code llm.route} 缺席时的取值。
   *
   * <p>取固定 {@code "default"} 而非从 {@code model}/URL 派生：①它必须在配置改写之间保持稳定（作为路由标识：错误消息按名报， {@link
   * ModelRoute#name()} 供调用方落日志）；②派生值会把"模型名"与"路由名"混为一谈；③"default" 是"唯一隐含条目"的惯例叫法。
   *
   * <p><b>如实登记</b>：本轮<b>不</b>把路由名写进事件库（{@code llm.call} 的 payload 仍只有服务端自报的 {@code
   * model}）——两条路由共用同一个模型名时，事件库分不出走的是哪条。设计文档 §四的开口项，不是"已支持"。
   */
  private static final String DEFAULT_ROUTE_NAME = "default";

  /**
   * 路由名/agent id 的合法字符集：名字是往配置树里<b>寻址的段</b>（{@code llm.routes.<name>.*}），带点的名字会指到别的节点上， 故与 {@code
   * ConfigAuth.AGENT_ID} 同族收紧。
   */
  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

  private LlmRouteLoader() {}

  /**
   * 路由名合法性（{@link #SAFE_NAME} 的公开判别面）：配置引导（CLI 向导 / HTTP setup 端点）在<b>写入前</b>用它拦下 非法名字——写侧校验与
   * {@link #load} 读侧的 {@code normalizeName} 同源，两处不各造一份正则。
   *
   * @param routeName 待检名字（null = 非法）
   * @return true = 可作为 {@code llm.routes.<name>} 的名字段写入
   */
  public static boolean isValidRouteName(String routeName) {
    return routeName != null && SAFE_NAME.matcher(routeName).matches();
  }

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
          credentialsRef(store, prefix),
          transport(store, prefix));
    }
    return flatOrDefault(store, name);
  }

  /**
   * 读一条路由的<b>能力描述</b>（{@link ModelCapabilities}）：{@code llm.routes.<name>.capabilities.*} （扁平形态则是
   * {@code llm.capabilities.*}）。
   *
   * <p>缺席 = {@link ModelCapabilities#defaults()}（保守全关），与 {@code credentialsRef} 同一口径；<b>存在但形态不对</b>
   * 一律响亮——把"配错了"读成"没配"会让路由悄悄按保守能力跑，"模型明明支持工具调用却不用"这类现象极难归因。
   *
   * <p>为什么归 {@link LlmRouteLoader} 而不是装配器：能力与 baseUrl/model 同住一个配置节点，读法（含错误码与消息口径）必须与 {@link #load}
   * 同源——两处各读一遍同一个节点，迟早会读成两种口径。
   */
  public static ModelCapabilities capabilities(ConfigStore store, String routeName) {
    Objects.requireNonNull(store, "store");
    String name = normalizeName(routeName);
    Optional<JsonNode> named = namedRouteNode(store, name);
    // 与 load 同一条解析路径：具名条目住在 llm.routes.<name>，扁平形态住在 llm
    String prefix = named.isPresent() ? SECTION + "." + KEY_ROUTES + "." + name : SECTION;
    return capabilitiesAt(store, prefix);
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
    // ownerId 为 null 会读 agents/null.json（"以为没配覆盖、其实查了另一个文件"）——这里是调用方编程错误，不是配置错误
    Objects.requireNonNull(ownerId, "ownerId");
    // 寻址 = agents.<id>.llm.route：agent 分支拿的是 key（"llm.route"）去 agents/<id>.json 里下钻
    // （FileConfigStore 的 agent 分支用 key、全局分支用 fullKey）——所以前缀是 "agents.<id>"、键带 "llm."；
    // 写成 (prefix="agents.bob", key="route") 会去读 bob.json 的顶层 "route"，键不同且不报错
    Optional<String> perAgent = textAt(store, "agents." + ownerId, SECTION + "." + KEY_ROUTE);
    if (perAgent.isPresent()) {
      return perAgent.get();
    }
    return textAt(store, SECTION, KEY_ROUTE).orElse(DEFAULT_ROUTE_NAME);
  }

  /**
   * 已登记的路由名（升序；含扁平形态隐含的 {@value #DEFAULT_ROUTE_NAME}，若它在场）。
   *
   * <p><b>这是"配置里有哪些 provider"的权威枚举入口</b>（A4 ②）：{@link LlmRouteAssembler#provider} 与 错误消息都走它。
   * 它<b>只列名字、不校验内容</b>——配置页"先看到全部（含写坏的那条）、再逐条报错"要的正是这个语义：一条路由 写坏了，用户还得先看见它，才谈得上去修它。
   *
   * <p>与 {@link LlmRouteAssembler#provider} 的分工是刻意的：<b>枚举要给全，装配要全对</b>（装配遇到坏路由一律 响亮失败，而不是让它悄悄缺席）。
   */
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
        credentialsRef(store, SECTION),
        transport(store, SECTION));
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
          E_LLM_ROUTE_UNKNOWN, "LLM 路由名非法：只能以字母/数字开头，其后可含字母/数字/下划线/连字符（它是配置树里的寻址段）");
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

  /**
   * 接法（A3①② + A6 修复版）：协议 + 两个超时 + 思考模式回传位。
   *
   * <p>四个键<b>都可缺席</b>，缺席即用 {@link LlmTransport} 的默认值——"没配超时/没配思考模式"是常态，不是错误；{@code timeoutMs}
   * 配的是<b>流空闲</b>上限，不是总时长。反之，<b>写了但不合法一律 响亮</b>（见 {@link #protocol} / {@link #millis} / {@link
   * #echoReasoningContentAt}）：把"配错了"读成"没配"，会让用户以为自己的约束生效了。
   */
  private static LlmTransport transport(ConfigStore store, String prefix) {
    return new LlmTransport(
        protocol(store, prefix),
        millis(store, prefix, KEY_CONNECT_TIMEOUT_MS, LlmTransport.DEFAULT_CONNECT_TIMEOUT),
        millis(store, prefix, KEY_TIMEOUT_MS, LlmTransport.DEFAULT_READ_TIMEOUT),
        echoReasoningContentAt(store, prefix));
  }

  /**
   * 协议/方言（A3②）：缺席 = {@link LlmProtocol#OPENAI_COMPATIBLE}（今天唯一的实现，也是既有配置的事实取值）； 写了但不认识 = {@link
   * #E_LLM_PROTOCOL_UNKNOWN}，<b>不回落默认</b>。
   *
   * <p><b>消息不回显该键的原文</b>：配置值原则上不外显（{@link ConfigException} 的消息契约）——GUI 里挨着的就是密钥输入框，
   * 贴错字段并非假想；方言名本身不敏感，但"能不能回显"按值分类要靠人来判，不如一律不回显。
   */
  private static LlmProtocol protocol(ConfigStore store, String prefix) {
    JsonNode value = store.get(prefix, KEY_PROTOCOL).orElse(null);
    if (value == null || value.isNull()) {
      return LlmProtocol.OPENAI_COMPATIBLE;
    }
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new ConfigException(
          E_LLM_CONFIG_MISSING, "LLM 路由配置不可用：" + prefix + "." + KEY_PROTOCOL + " 必须是非空文本");
    }
    try {
      return LlmProtocol.of(value.asText());
    } catch (IllegalArgumentException unknown) {
      throw new ConfigException(
          E_LLM_PROTOCOL_UNKNOWN,
          "LLM 路由配置不可用："
              + prefix
              + "."
              + KEY_PROTOCOL
              + " 是未知的协议/方言（当前只实现 "
              + LlmProtocol.OPENAI_COMPATIBLE
              + "；原文不外显）");
    }
  }

  /**
   * 毫秒超时键：缺席 = 默认值；存在但不是正整数 → 响亮（<b>不回落默认</b>——"配错了"不能看起来像"没配"）。
   *
   * <p>零与负数在这里被拒：{@code 0} 在 {@link Duration} 里是合法值、在 {@code HttpRequest#timeout} 里却是"立刻超时"，
   * 放行只会得到一个"每次都超时"的配置。
   */
  private static Duration millis(ConfigStore store, String prefix, String key, Duration fallback) {
    JsonNode value = store.get(prefix, key).orElse(null);
    if (value == null || value.isNull()) {
      return fallback;
    }
    if (!value.isNumber() || !value.canConvertToLong() || value.asLong() <= 0) {
      throw new ConfigException(
          E_LLM_CONFIG_MISSING, "LLM 路由配置不可用：" + prefix + "." + key + " 必须是正整数毫秒数（缺席才表示用默认超时）");
    }
    return Duration.ofMillis(value.asLong());
  }

  /**
   * 能力描述：整块缺席 = {@link ModelCapabilities#defaults()}（保守全关）；出现任何形态不对的键 → 响亮。
   *
   * <p>键名<b>就是</b> {@link ModelCapabilities} 的组件名（{@code toolCalling}/{@code
   * parallelToolCalls}/{@code reasoning}/{@code promptCaching}/{@code vision}/{@code
   * maxContext}/{@code maxOutput}/{@code echoReasoningContent}）——不另立一套命名，读代码的人不必做一次翻译。
   *
   * <p>{@code echoReasoningContent} 与 {@link #transport(ConfigStore, String)} 读的是同一个配置键：能力页展示的 bit
   * 与发送侧真正生效的 bit 必须同源，否则会出现"页面说开着、线上仍缺键"的静默失效。
   */
  private static ModelCapabilities capabilitiesAt(ConfigStore store, String prefix) {
    JsonNode node = capabilitiesNode(store, prefix);
    if (node == null) {
      return ModelCapabilities.defaults();
    }
    String caps = prefix + "." + KEY_CAPABILITIES;
    return new ModelCapabilities(
        boolAt(store, caps, "toolCalling"),
        boolAt(store, caps, "parallelToolCalls"),
        boolAt(store, caps, "reasoning"),
        boolAt(store, caps, "promptCaching"),
        boolAt(store, caps, "vision"),
        countAt(store, caps, "maxContext"),
        countAt(store, caps, "maxOutput"),
        boolAt(store, caps, KEY_ECHO_REASONING_CONTENT));
  }

  /**
   * 思考模式回传位：{@code capabilities} 整块缺席 → {@code false}；块内该键缺席 → {@code false}；存在但非布尔 → 响亮。
   *
   * <p>它比"模型支持推理"（{@link ModelCapabilities#reasoning()}）更窄：并非所有支持推理的供应商都要求回传空 {@code
   * reasoning_content}；给不认该字段的普通 OpenAI 兼容端点塞未知键是另一种 400。
   */
  private static boolean echoReasoningContentAt(ConfigStore store, String prefix) {
    if (capabilitiesNode(store, prefix) == null) {
      return false;
    }
    return boolAt(store, prefix + "." + KEY_CAPABILITIES, KEY_ECHO_REASONING_CONTENT);
  }

  /**
   * 取 {@code capabilities} 块：缺席/null → {@code null}；存在但不是对象 → {@link #E_LLM_CONFIG_MISSING} 响亮。
   *
   * <p>提取成方法是为了让 {@link #transport(ConfigStore, String)} 与 {@link #capabilitiesAt(ConfigStore,
   * String)} 对"整块形态不对"给出同一句文案——两处各写一份对象校验，迟早会变成两种口径。
   */
  private static JsonNode capabilitiesNode(ConfigStore store, String prefix) {
    JsonNode node = store.get(prefix, KEY_CAPABILITIES).orElse(null);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isObject()) {
      throw new ConfigException(
          E_LLM_CONFIG_MISSING,
          "LLM 路由配置不可用：" + prefix + "." + KEY_CAPABILITIES + " 必须是对象（能力名 → 布尔/数值）");
    }
    return node;
  }

  /** 能力布尔项：缺席 = false（= {@link ModelCapabilities#defaults()} 的口径）；存在但非布尔 → 响亮。 */
  private static boolean boolAt(ConfigStore store, String prefix, String key) {
    JsonNode value = store.get(prefix, key).orElse(null);
    if (value == null || value.isNull()) {
      return false;
    }
    if (!value.isBoolean()) {
      throw new ConfigException(
          E_LLM_CONFIG_MISSING, "LLM 路由配置不可用：" + prefix + "." + key + " 必须是布尔值 true/false");
    }
    return value.asBoolean();
  }

  /** 能力数值项（{@code maxContext}/{@code maxOutput}）：缺席 = 0（未知）；存在但不是非负整数 → 响亮。 */
  private static int countAt(ConfigStore store, String prefix, String key) {
    JsonNode value = store.get(prefix, key).orElse(null);
    if (value == null || value.isNull()) {
      return 0;
    }
    if (!value.isIntegralNumber() || value.asLong() < 0 || value.asLong() > Integer.MAX_VALUE) {
      throw new ConfigException(
          E_LLM_CONFIG_MISSING, "LLM 路由配置不可用：" + prefix + "." + key + " 必须是非负整数（0 = 未知）");
    }
    return value.asInt();
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
