package io.mosire.agentlib.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.ConfigStore;
import io.mosire.agentlib.config.FileConfigStore;
import io.mosire.agentlib.permission.AccessToken;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A3（每条路由自己的超时与协议）+ A4（官方装配入口）的验收测试，外加一条<b>端到端</b>用例：从 {@code config.json} 出发， 经 {@link
 * LlmRouteAssembler} 拿到可用的 {@link LlmClient}，真的把请求打到本地假供应商上。
 *
 * <p>为什么端到端那条最重要：需求要的是一份"从配置加载 → 得到可用 LlmClient"的官方示例，而示例最容易撒的谎是 "编译得过但装配错了"（协议的 switch
 * 分支、密钥引用、baseUrl 拼接任一处错位都只在真发请求时才暴露）。所以这里不看 对象图，看<b>供应商那头收到了什么</b>。
 */
class RouteTransportAndAssemblyTest {

  @TempDir Path tempDir;

  private HttpServer server;
  private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
  private final AtomicReference<String> lastBody = new AtomicReference<>();

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  // ---------- 工具 ----------

  private ConfigStore store() {
    return new FileConfigStore(tempDir);
  }

  private void writeConfig(String json) throws IOException {
    Files.createDirectories(tempDir);
    Files.writeString(tempDir.resolve("config.json"), json, StandardCharsets.UTF_8);
  }

  /** 一条形态完整的具名路由（超时/协议/能力全给），baseUrl 指向本地假端点。 */
  private static String routeJson(String baseUrl, String extra) {
    return "{\"baseUrl\":\""
        + baseUrl
        + "\",\"model\":\"deepseek-chat\",\"credentialsRef\":\"keys.deepseek\""
        + extra
        + "}";
  }

  // ---------- A3：超时与协议 ----------

  @Test
  void 每条路由读自己的超时与协议() throws IOException {
    writeConfig(
        "{\"llm\":{\"routes\":{\"deepseek\":"
            + routeJson("http://127.0.0.1:9/v1", ",\"timeoutMs\":1234,\"connectTimeoutMs\":567")
            + "}}}");

    ModelRoute route = LlmRouteLoader.load(store(), "deepseek");

    assertThat(route.transport().readTimeout())
        .as("timeoutMs → 读超时")
        .isEqualTo(Duration.ofMillis(1234));
    assertThat(route.transport().connectTimeout())
        .as("connectTimeoutMs → 连接超时")
        .isEqualTo(Duration.ofMillis(567));
    assertThat(route.transport().protocol()).isEqualTo(LlmProtocol.OPENAI_COMPATIBLE);
  }

  @Test
  void 超时与协议缺席时取默认值() throws IOException {
    writeConfig(
        "{\"llm\":{\"routes\":{\"deepseek\":" + routeJson("http://127.0.0.1:9/v1", "") + "}}}");

    ModelRoute route = LlmRouteLoader.load(store(), "deepseek");

    assertThat(route.transport().readTimeout()).isEqualTo(LlmTransport.DEFAULT_READ_TIMEOUT);
    assertThat(route.transport().connectTimeout()).isEqualTo(LlmTransport.DEFAULT_CONNECT_TIMEOUT);
    assertThat(route.transport().protocol())
        .as("没写协议 = OpenAI 兼容（唯一已实现的方言）")
        .isEqualTo(LlmProtocol.OPENAI_COMPATIBLE);
  }

  @Test
  void 未知协议响亮失败且不回落默认() throws IOException {
    writeConfig(
        "{\"llm\":{\"routes\":{\"deepseek\":"
            + routeJson("http://127.0.0.1:9/v1", ",\"protocol\":\"anthropic\"")
            + "}}}");

    ConfigException unknown =
        catchThrowableOfType(() -> LlmRouteLoader.load(store(), "deepseek"), ConfigException.class);

    assertThat(unknown.code())
        .as("按 OpenAI 协议悄悄发出去 = 用错误的方言砸供应商")
        .isEqualTo(LlmRouteLoader.E_LLM_PROTOCOL_UNKNOWN);
  }

  @Test
  void 协议名接受常见写法() throws IOException {
    writeConfig(
        "{\"llm\":{\"routes\":{\"deepseek\":"
            + routeJson("http://127.0.0.1:9/v1", ",\"protocol\":\"openai-compatible\"")
            + "}}}");

    assertThat(LlmRouteLoader.load(store(), "deepseek").transport().protocol())
        .isEqualTo(LlmProtocol.OPENAI_COMPATIBLE);
  }

  @Test
  void 超时必须是正整数毫秒() throws IOException {
    writeConfig(
        "{\"llm\":{\"routes\":{\"deepseek\":"
            + routeJson("http://127.0.0.1:9/v1", ",\"timeoutMs\":0")
            + "}}}");

    ConfigException rejected =
        catchThrowableOfType(() -> LlmRouteLoader.load(store(), "deepseek"), ConfigException.class);

    assertThat(rejected.code()).isEqualTo(LlmRouteLoader.E_LLM_CONFIG_MISSING);
  }

  // ---------- A3：baseUrl 语义（端点当根用要当场拒绝） ----------

  @Test
  void 把端点当_baseUrl_用会被当场拒绝且不回显它() {
    String leaky = "http://leaky-host.invalid/v1/chat/completions";
    ModelRoute route = new ModelRoute("r", leaky, "m", "");

    IllegalArgumentException rejected =
        catchThrowableOfType(
            () -> new OpenAICompatibleLlmClient(route, () -> Optional.empty()),
            IllegalArgumentException.class);

    assertThat(rejected).isNotNull();
    assertThat(String.valueOf(rejected.getMessage()))
        .as("/v1 该由调用方写进 baseUrl；错误消息本身不得回显疑似带凭据的 URL")
        .doesNotContain("leaky-host");
  }

  @Test
  void baseUrl_不含_v1_也照样能装配() throws IOException {
    // "/v1" 是否写进 baseUrl 是调用方的事：这里 baseUrl 只到根，端点由客户端补 /chat/completions
    String baseUrl = startServer();
    writeConfig("{\"llm\":{\"routes\":{\"deepseek\":" + routeJson(baseUrl, "") + "}}}");

    LlmClient llm = LlmRouteAssembler.client(store(), "deepseek", AccessToken.SYSTEM);

    assertThat(llm.model()).isEqualTo("deepseek-chat");
  }

  // ---------- A4：能力描述 ----------

  @Test
  void 能力描述按配置读出() throws IOException {
    writeConfig(
        "{\"llm\":{\"routes\":{\"deepseek\":"
            + routeJson(
                "http://127.0.0.1:9/v1",
                ",\"capabilities\":{\"toolCalling\":true,\"parallelToolCalls\":false,"
                    + "\"reasoning\":true,\"promptCaching\":false,\"maxContext\":65536,"
                    + "\"maxOutput\":8192}")
            + "}}}");

    ModelCapabilities caps = LlmRouteLoader.capabilities(store(), "deepseek");

    assertThat(caps.toolCalling()).isTrue();
    assertThat(caps.parallelToolCalls()).isFalse();
    assertThat(caps.reasoning()).as("deepseek-flash 这类推理模型必须如实声明").isTrue();
    assertThat(caps.maxContext()).isEqualTo(65536);
    assertThat(caps.maxOutput()).isEqualTo(8192);
  }

  @Test
  void 能力描述缺席时保守全关() throws IOException {
    writeConfig(
        "{\"llm\":{\"routes\":{\"deepseek\":" + routeJson("http://127.0.0.1:9/v1", "") + "}}}");

    assertThat(LlmRouteLoader.capabilities(store(), "deepseek"))
        .as("没写能力 = 一切能力视为不存在，宁可保守拒绝也不乐观假设")
        .isEqualTo(ModelCapabilities.defaults());
  }

  /**
   * 视觉能力（图片输入）：声明 {@code vision:true} 要读到 true——调用方的能力门控（发图 vs 回落字符图）就靠它。
   *
   * <p>判别性：把 {@code capabilitiesAt} 里的 {@code boolAt(..., "vision")} 换成常量 {@code false}，本用例必红；
   * 反过来漏改 {@link ModelCapabilities} 的组件顺序（vision 与别的布尔位串位）也会红——单独立一条就是为了让这个位有一个"自己的名字"。
   */
  @Test
  void 视觉能力按配置读出() throws IOException {
    writeConfig(
        "{\"llm\":{\"routes\":{\"deepseek\":"
            + routeJson("http://127.0.0.1:9/v1", ",\"capabilities\":{\"vision\":true}")
            + "}}}");

    assertThat(LlmRouteLoader.capabilities(store(), "deepseek").vision()).isTrue();
  }

  /**
   * A6 修复版的思考模式回传位：{@code capabilities.echoReasoningContent=true} 必须同时进入能力描述与 {@link
   * LlmTransport}（发送侧真正用的那个值）。
   *
   * <p>只改一边的失效形态很隐蔽：页面显示"已开"但线上仍缺键，或反过来在线级塞了不认该字段的供应商。
   */
  @Test
  void 思考模式回传位按配置读出并进入路由接法() throws IOException {
    writeConfig(
        "{\"llm\":{\"routes\":{\"deepseek\":"
            + routeJson(
                "http://127.0.0.1:9/v1",
                ",\"capabilities\":{\"reasoning\":true,\"echoReasoningContent\":true}")
            + "}}}");

    ModelRoute route = LlmRouteLoader.load(store(), "deepseek");
    ModelCapabilities caps = LlmRouteLoader.capabilities(store(), "deepseek");

    assertThat(route.transport().echoReasoningContent()).as("LlmTransport 是发送侧权威值").isTrue();
    assertThat(caps.echoReasoningContent()).as("能力描述与接法必须同源，配置页看到的 bit 就是线上生效的 bit").isTrue();
  }

  /** 回传位缺席 = false；存在但非布尔 = 响亮，不回落默认（与其它能力位同一口径）。 */
  @Test
  void 思考模式回传位缺席保守关且非布尔响亮() throws IOException {
    writeConfig(
        "{\"llm\":{\"routes\":{\"deepseek\":" + routeJson("http://127.0.0.1:9/v1", "") + "}}}");
    assertThat(LlmRouteLoader.load(store(), "deepseek").transport().echoReasoningContent())
        .isFalse();

    writeConfig(
        "{\"llm\":{\"routes\":{\"deepseek\":"
            + routeJson(
                "http://127.0.0.1:9/v1", ",\"capabilities\":{\"echoReasoningContent\":\"yes\"}")
            + "}}}");

    ConfigException rejected =
        catchThrowableOfType(() -> LlmRouteLoader.load(store(), "deepseek"), ConfigException.class);
    assertThat(rejected).isNotNull();
    assertThat(rejected.code()).isEqualTo(LlmRouteLoader.E_LLM_CONFIG_MISSING);
  }

  @Test
  void 能力描述形态不对要响亮() throws IOException {
    writeConfig(
        "{\"llm\":{\"routes\":{\"deepseek\":"
            + routeJson("http://127.0.0.1:9/v1", ",\"capabilities\":\"yes\"")
            + "}}}");

    ConfigException rejected =
        catchThrowableOfType(
            () -> LlmRouteLoader.capabilities(store(), "deepseek"), ConfigException.class);

    assertThat(rejected.code())
        .as("把「配错了」读成「没配」会让路由悄悄按保守能力跑，极难归因")
        .isEqualTo(LlmRouteLoader.E_LLM_CONFIG_MISSING);
  }

  // ---------- A4：装配器 ----------

  @Test
  void 官方示例_从配置到可用客户端_端到端() throws Exception {
    String baseUrl = startServer();
    writeConfig(
        "{\"llm\":{\"routes\":{\"deepseek\":"
            + routeJson(baseUrl, "")
            + "}},\"keys\":{\"deepseek\":\"sk-test-not-a-real-key\"}}");

    ConfigStore store = store();
    LlmClient llm = LlmRouteAssembler.client(store, "deepseek", AccessToken.SYSTEM);
    String answer = llm.text(new LlmRequest(List.of(LlmMessage.user("在吗"))).withTemperature(0));

    assertThat(answer).isEqualTo("你好");
    assertThat(lastAuthorization.get())
        .as("密钥从 keys.* 取出并带上")
        .isEqualTo("Bearer sk-test-not-a-real-key");
    assertThat(lastBody.get())
        .as("temperature=0 必须真的出现在请求体里（0 是合法值，不能被当成「未设置」省略）")
        .contains("\"temperature\":0")
        .contains("\"model\":\"deepseek-chat\"");
  }

  @Test
  void 未落盘的路由也能装配_给测试连接用() {
    // 配置页的「测试连接」探的是用户刚敲的候选配置——它此刻不在 ConfigStore 里，走不了 client(store, ...)
    ModelRoute candidate = new ModelRoute("probe", "http://127.0.0.1:9/v1", "candidate-model", "");

    LlmClient probe = LlmRouteAssembler.client(candidate, () -> Optional.of("sk-candidate"));

    assertThat(probe.model()).as("探测路径与真实装配共用同一个协议开关").isEqualTo("candidate-model");
  }

  @Test
  void 身份不足以取本进程密钥时装配即失败() throws IOException {
    writeConfig(
        "{\"llm\":{\"routes\":{\"deepseek\":" + routeJson("http://127.0.0.1:9/v1", "") + "}}}");

    ConfigException rejected =
        catchThrowableOfType(
            () -> LlmRouteAssembler.client(store(), "deepseek", AccessToken.GUEST),
            ConfigException.class);

    assertThat(rejected.code()).isEqualTo("E_TOKEN_TOO_LOW");
  }

  @Test
  void 装配器登记全部路由及其能力() throws IOException {
    writeConfig(
        "{\"llm\":{\"routes\":{"
            + "\"a\":"
            + routeJson("http://127.0.0.1:9/v1", ",\"capabilities\":{\"toolCalling\":true}")
            + ","
            + "\"b\":"
            + routeJson("http://127.0.0.1:9/v1", "")
            + "}}}");

    ModelProvider provider = LlmRouteAssembler.provider(store());

    assertThat(provider.size()).isEqualTo(2);
    assertThat(provider.resolve("a")).isPresent();
    assertThat(provider.capabilities("a").toolCalling()).isTrue();
    assertThat(provider.capabilities("b").toolCalling()).as("b 没声明能力 ⇒ 保守全关，而不是继承 a 的").isFalse();
  }

  @Test
  void 有一条坏路由时整次装配响亮失败() throws IOException {
    writeConfig(
        "{\"llm\":{\"routes\":{"
            + "\"good\":"
            + routeJson("http://127.0.0.1:9/v1", "")
            + ","
            + "\"broken\":{\"baseUrl\":\"http://127.0.0.1:9/v1\",\"credentialsRef\":\"\"}"
            + "}}}");

    ConfigException rejected =
        catchThrowableOfType(() -> LlmRouteAssembler.provider(store()), ConfigException.class);

    assertThat(rejected.code())
        .as("装配是全有或全无：坏路由不许悄悄缺席（枚举要给全请用 availableNames）")
        .isEqualTo(LlmRouteLoader.E_LLM_CONFIG_MISSING);
  }

  @Test
  void 没有任何路由时返回空表而不是抛错() throws IOException {
    writeConfig("{\"runtime\":{\"logLevel\":\"info\"}}");

    ModelProvider provider = LlmRouteAssembler.provider(store());

    assertThat(provider.size()).isZero();
    assertThat(LlmRouteLoader.availableNames(store())).isEmpty();
  }

  @Test
  void 可用名枚举是权威入口_坏路由也必须看得见() throws IOException {
    writeConfig(
        "{\"llm\":{\"routes\":{"
            + "\"good\":"
            + routeJson("http://127.0.0.1:9/v1", "")
            + ","
            + "\"broken\":{\"baseUrl\":\"http://127.0.0.1:9/v1\"}"
            + "}}}");

    assertThat(LlmRouteLoader.availableNames(store()))
        .as("配置页要先看到全部（含写坏的那条）才谈得上修——枚举只列名字、不校验内容")
        .containsExactly("broken", "good");
  }

  // ---------- 假供应商 ----------

  /** 起一个 OpenAI 兼容的流式假端点，返回 baseUrl（含 {@code /v1}——由调用方写进配置，正是 A3 的语义）。 */
  private String startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/v1/chat/completions", this::handleChatCompletions);
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  private void handleChatCompletions(HttpExchange exchange) throws IOException {
    lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    byte[] body =
        ("""
        data: {"choices":[{"delta":{"content":"你好"},"finish_reason":null}]}

        data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2}}

        data: [DONE]

        """)
            .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0); // 0 = chunked：流式端点必须分块写
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
}
