package io.mosire.agentlib.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mosire.agentlib.llm.OpenAICompatibleLlmClient.ApiKeySource;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link OpenAICompatibleLlmClient} 协议级用例：{@code jdk.httpserver} 假端点（绑 127.0.0.1 + 临时端口 0），全离线，
 * 不依赖任何真实供应商。
 *
 * <p>假端点**刻意分块 + flush**（块间停顿 20ms）——只发完整帧的用例测不出"跨读边界"与"arguments 跨 chunk 拼接" 这两类真 bug。判据见各用例注释。
 */
class OpenAICompatibleLlmClientTest {

  private static final String API_KEY = "not-a-real-credential-7f3a";
  private static final String ROUTE_MODEL = "route-model-fake";
  private static final String STREAM_MODEL = "streamed-model-fake";

  /** 响应脚本：每项 = 一块要写出的原始字节（块间 flush + 停顿）。 */
  private final List<byte[]> script = new CopyOnWriteArrayList<>();

  private final AtomicInteger writes = new AtomicInteger();
  private final List<String> requestBodies = new CopyOnWriteArrayList<>();
  private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
  private final AtomicReference<String> lastAccept = new AtomicReference<>();
  private volatile int status = 200;
  private volatile String errorBody = "";

  /** 脚本写完后的停顿（模拟"供应商不再吐数据"）；客户端必须靠自己的读超时脱身。 */
  private volatile long stallAfterScriptMillis;

  private HttpServer server;
  private ExecutorService executor;
  private String baseUrl;

  @BeforeEach
  void startFakeEndpoint() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    // 并发服务（R6）：HttpServer 缺省 executor 是"调用线程内串行执行"——本套用例存在"客户端正在读、服务端还没写完"
    // 的形态，单线程服务端会把两边一起卡死。虚拟线程每任务一个，量小、天然守护（不会拖住构建 JVM）。
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext("/chat/completions", this::handle);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopFakeEndpoint() {
    server.stop(0);
    executor.shutdownNow();
  }

  // ---------- SSE 解析（R5 必判点） ----------

  /**
   * R5② 帧跨读边界：把整段 SSE 按**字节**切开成三块，切点刻意落在多字节字符"好"的 UTF-8 字节中间、以及第二帧 {@code data:} 前缀的中间——只有"跨读缓冲 +
   * 增量解码"的实现才能得到完整文本；按单个 read 的字节直接 {@code new String(...)} 或按"一次 read = 一整行"解析的实现会得到乱码/碎片。
   */
  @Test
  void streamsTextSpanningReadBoundaries() {
    String sse =
        "data: {\"model\":\""
            + STREAM_MODEL
            + "\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"}}]}\n\n"
            + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"世界\"}}]}\n\n"
            + "data: [DONE]\n\n";
    byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
    int midCharacter = indexOf(bytes, "好".getBytes(StandardCharsets.UTF_8)) + 1;
    int secondFrame = sse.indexOf("data:", sse.indexOf("data:") + 1);
    script.addAll(splitAt(bytes, midCharacter, secondFrame + 2)); // 第二刀落在第二帧 "data:" 前缀的中间（da|ta:）

    LlmResponse response = client(ApiKeySource.none(), Duration.ofSeconds(5)).chat(oneUserTurn());

    assertThat(response.textPart()).contains("你好世界");
    // 模型名取自流（路由配置里是另一个值）——证明读的是响应而不是把配置回显
    assertThat(response.model()).isEqualTo(STREAM_MODEL);
    assertThat(writes.get()).isGreaterThan(1); // 刺激确实分了多块（用例本身不是一次性整包）
  }

  /**
   * R5③ 工具参数跨 chunk 拼接（本类最经典的错）：{@code arguments} 被切成三段，<b>每一段单独都不是合法 JSON</b> ——只取首块 /
   * 只取末块的实现必然抛错或拿到离谱结果；断言按<b>完整值</b>比对，只断言"有 toolCalls"是空转。
   */
  @Test
  void concatenatesToolCallArgumentsAcrossChunks() {
    script.add(sseFrame(chunkWithToolCall("{\"te")));
    script.add(sseFrame(chunkWithToolCall("xt\":\"wuxia-")));
    script.add(sseFrame(chunkWithToolCall("bridge-42\"}")));
    script.add(DONE_FRAME);

    LlmResponse response = client(ApiKeySource.none(), Duration.ofSeconds(5)).chat(oneUserTurn());

    List<ContentPart.ToolCall> calls = toolCallsOf(response);
    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).id()).isEqualTo("call_1");
    assertThat(calls.get(0).name()).isEqualTo("echo");
    assertThat(calls.get(0).arguments())
        .containsExactlyInAnyOrderEntriesOf(Map.of("text", "wuxia-bridge-42"));
  }

  /** R5③ 多工具调用：两个调用的分片**交错**到达，必须按 {@code index} 各归各桶（"追加到最后一个桶"的实现会拼成垃圾）。 */
  @Test
  void keepsInterleavedToolCallsInSeparateBuckets() {
    script.add(sseFrame(toolCallFragment(0, "call_0", "echo", "{\"a\":\"fir")));
    script.add(sseFrame(toolCallFragment(1, "call_1", "echo", "{\"b\":\"sec")));
    script.add(sseFrame(toolCallFragment(0, null, null, "st\"}")));
    script.add(sseFrame(toolCallFragment(1, null, null, "ond\"}")));
    script.add(DONE_FRAME);

    LlmResponse response = client(ApiKeySource.none(), Duration.ofSeconds(5)).chat(oneUserTurn());

    List<ContentPart.ToolCall> calls = toolCallsOf(response);
    assertThat(calls).hasSize(2);
    assertThat(calls.get(0).id()).isEqualTo("call_0");
    assertThat(calls.get(0).arguments()).containsExactlyInAnyOrderEntriesOf(Map.of("a", "first"));
    assertThat(calls.get(1).id()).isEqualTo("call_1");
    assertThat(calls.get(1).arguments()).containsExactlyInAnyOrderEntriesOf(Map.of("b", "second"));
  }

  /** R5④ usage 落在末尾 chunk：token 与缓存字段落到 {@link LlmResponse} 对应字段；未上报的缓存写保持"未知"。 */
  @Test
  void mapsUsageAndCacheTokensFromFinalChunk() {
    script.add(sseFrame("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"记账\"}}]}"));
    script.add(
        sseFrame(
            "{\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":45,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":90},\"cache_creation_input_tokens\":30}}"));
    script.add(DONE_FRAME);

    LlmResponse response = client(ApiKeySource.none(), Duration.ofSeconds(5)).chat(oneUserTurn());

    assertThat(response.inputTokens()).isEqualTo(120);
    assertThat(response.outputTokens()).isEqualTo(45);
    assertThat(response.cacheReadTokens()).isEqualTo(90);
    assertThat(response.cacheWriteTokens()).isEqualTo(30);
  }

  /** 供应商未上报的字段保持 {@link LlmResponse#UNKNOWN_TOKENS}（-1）——不编造 0（备用缓存键：prompt_cache_hit_tokens）。 */
  @Test
  void keepsUnreportedTokenFieldsUnknown() {
    script.add(
        sseFrame(
            "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"}}],"
                + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,\"prompt_cache_hit_tokens\":5}}"));
    script.add(DONE_FRAME);

    LlmResponse response = client(ApiKeySource.none(), Duration.ofSeconds(5)).chat(oneUserTurn());

    assertThat(response.inputTokens()).isEqualTo(7);
    assertThat(response.outputTokens()).isEqualTo(3);
    assertThat(response.cacheReadTokens()).isEqualTo(5);
    assertThat(response.cacheWriteTokens()).isEqualTo(LlmResponse.UNKNOWN_TOKENS);
  }

  /**
   * F1 pin（R5④ 的对称面）：请求体<b>必须显式索要</b> usage（{@code stream_options:{"include_usage":true}}）。
   *
   * <p>为什么这是必判点：OpenAI 规范下 {@code stream:true} 时 usage <b>默认不下发</b>；不发该键 → 四个 token 字段恒 -1 → {@link
   * LlmQuota#record} 把负值夹成 0 → 本地配额账本<b>永远不超限</b>，"配额超限 → QUOTA"这条验收路径在真实 OpenAI 上不可达 （DeepSeek
   * 原生默认带 usage，故缺键在默认供应商上"看起来正常"）。
   *
   * <p>判别性：假端点<b>刻意不发 usage 帧</b>，断言分两半——①请求体确实含该键（把该键从请求体移除，本用例必红，已实测）； ②缺 usage 时四个 token 字段保持
   * {@link LlmResponse#UNKNOWN_TOKENS}（<b>不编造 0</b>：若实现改成缺省填 0，前半段仍绿、这里必红）。
   */
  @Test
  void requestsUsageInStreamOptionsAndKeepsTokensUnknownWhenVendorOmitsUsage() {
    script.add(sseFrame("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"无 usage 的流\"}}]}"));
    script.add(DONE_FRAME);

    LlmResponse response = client(ApiKeySource.none(), Duration.ofSeconds(5)).chat(oneUserTurn());

    assertThat(requestBodies.get(0)).contains("\"stream_options\":{\"include_usage\":true}");
    assertThat(response.inputTokens()).isEqualTo(LlmResponse.UNKNOWN_TOKENS);
    assertThat(response.outputTokens()).isEqualTo(LlmResponse.UNKNOWN_TOKENS);
    assertThat(response.cacheReadTokens()).isEqualTo(LlmResponse.UNKNOWN_TOKENS);
    assertThat(response.cacheWriteTokens()).isEqualTo(LlmResponse.UNKNOWN_TOKENS);
    assertThat(response.textPart()).contains("无 usage 的流");
  }

  /**
   * R5⑤ {@code [DONE]} 后不再读、不抛：终止符之后跟了非法 JSON 帧——继续读的实现会在这里抛 LlmException， 本用例通过即证明"见到 [DONE] 就收工"。
   */
  @Test
  void stopsReadingAtDoneAndIgnoresTrailingGarbage() {
    script.add(sseFrame("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"收工\"}}]}"));
    script.add(DONE_FRAME);
    script.add(rawBytes("data: {这不是合法 JSON，且在 [DONE] 之后}\n\n"));

    LlmResponse response = client(ApiKeySource.none(), Duration.ofSeconds(5)).chat(oneUserTurn());

    assertThat(response.textPart()).contains("收工");
  }

  /** 流在 {@code [DONE]} 之前 EOF = 截断：响亮失败，不交回半截响应（静默数据丢失是本项目最坏的失败模式）。 */
  @Test
  void failsLoudlyWhenStreamEndsWithoutDone() {
    script.add(sseFrame("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"半截\"}}]}"));

    assertThatThrownBy(() -> client(ApiKeySource.none(), Duration.ofSeconds(5)).chat(oneUserTurn()))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("截断");
  }

  /**
   * 200 但响应体不是 SSE（例如代理返回的普通 JSON）：loud 失败，而不是返回一个空内容的"成功"；且文案必须与 {@link
   * #reportsEmptyCompletionDistinctlyFromNotSse()} 的"空完成"分开（两类故障排查方向不同）。
   */
  @Test
  void failsLoudlyWhenBodyIsNotSse() {
    assertThatThrownBy(() -> client(ApiKeySource.none(), Duration.ofSeconds(5)).chat(oneUserTurn()))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("data 帧")
        .hasMessageContaining("不是 OpenAI 兼容")
        .hasMessageNotContaining("空完成");
  }

  /**
   * F2 pin（失败必须可辨）：<b>是 SSE 但供应商什么也没产出</b>（有 Content-Type、有终止符 {@code [DONE]}、零内容帧）与 <b>根本不是
   * SSE</b>（零 data 帧且无终止符）是两类故障——前者是"供应商/网关把内容丢了"，后者是"接错端点/响应不是流式"， 文案混在一起会把排查方向带偏。
   *
   * <p>返回语义选择：<b>响亮报"空完成"</b>而不是交回空 {@link LlmResponse}。理由：①按 OpenAI 协议，正常完成至少会有 {@code
   * finish_reason} 帧（现在还会带 usage），零 data 帧意味着内容整个丢了，交回空响应就是静默数据丢失；②零 data 帧同时意味着零
   * usage，静默返回会让配额账本"看起来一切正常"（与 F1 同一类静默失效）。选择与理由已写进 {@code Accumulator#toResponse} 的 Javadoc。
   */
  @Test
  void reportsEmptyCompletionDistinctlyFromNotSse() {
    script.add(DONE_FRAME); // 供应商对空补全的退化形态：只有终止符

    assertThatThrownBy(() -> client(ApiKeySource.none(), Duration.ofSeconds(5)).chat(oneUserTurn()))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("空完成")
        .hasMessageNotContaining("不是 OpenAI 兼容");
  }

  /** 流中携带供应商错误对象（部分供应商如此报错）→ LlmException，带非敏感摘要。 */
  @Test
  void mapsInStreamProviderErrorToLlmException() {
    script.add(
        sseFrame("{\"error\":{\"message\":\"upstream overloaded\",\"type\":\"server_error\"}}"));

    assertThatThrownBy(() -> client(ApiKeySource.none(), Duration.ofSeconds(5)).chat(oneUserTurn()))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("upstream overloaded");
  }

  // ---------- R1 超时边界 ----------

  /**
   * R1 超时边界（不重试的代价必须由超时兜底）：供应商发完半帧就不再吐数据，客户端必须在 {@code readTimeout} 内抛 {@link
   * LlmException}——绝不无限挂起。5 秒停顿 vs 300ms 读超时 + 3 秒上限断言，判别"真超时"与"碰巧返回"。
   */
  @Test
  void timesOutWithLlmExceptionInsteadOfHangingWhenProviderStalls() {
    script.add(rawBytes("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"半"));
    stallAfterScriptMillis = 5_000;
    OpenAICompatibleLlmClient client = client(ApiKeySource.none(), Duration.ofMillis(300));

    long startedNanos = System.nanoTime();
    assertThatThrownBy(() -> client.chat(oneUserTurn()))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("超时");
    long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L;
    assertThat(elapsedMillis).isLessThan(3_000L);
  }

  /**
   * 传输层失败（连接被拒）也必须归成 {@link LlmException}：契约要求"失败抛 LlmException"——裸 {@code IOException}/ {@code
   * UncheckedIOException} 逃到 {@link LlmClient} 调用方是违约。端口取自"刚被释放的临时端口"，全离线、快速失败。
   */
  @Test
  void mapsConnectionFailureToLlmException() throws IOException {
    int releasedPort;
    try (ServerSocket released = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      releasedPort = released.getLocalPort();
    }
    ModelRoute route =
        ModelRoute.of("test", "http://127.0.0.1:" + releasedPort, ROUTE_MODEL, "keys.fake");

    OpenAICompatibleLlmClient client =
        new OpenAICompatibleLlmClient(
            route, ApiKeySource.none(), Duration.ofSeconds(2), Duration.ofSeconds(2));

    // 消息文案允许"连接失败/超时"两种（取决于本机对拒绝连接的处理），断言的是类型与来源归类
    assertThatThrownBy(() -> client.chat(oneUserTurn()))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("LLM ");
  }

  /** 连接/读取超时必须为正——否则"设了超时"是假的。 */
  @Test
  void rejectsNonPositiveTimeouts() {
    ModelRoute route = ModelRoute.of("test", baseUrl, ROUTE_MODEL, "keys.fake");
    assertThatThrownBy(
            () ->
                new OpenAICompatibleLlmClient(
                    route, ApiKeySource.none(), Duration.ZERO, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("connectTimeout");
    assertThatThrownBy(
            () ->
                new OpenAICompatibleLlmClient(
                    route, ApiKeySource.none(), Duration.ofSeconds(1), Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("readTimeout");
  }

  // ---------- R4 供应商错误 vs 本地配额 ----------

  /**
   * R4 pin：供应商 429 一律 {@link LlmException}——<b>绝不</b> {@link QuotaExceededException}（后者是"本地账本超限"， 由
   * {@link LlmQuota#record} 抛、由 AgentPipeline 只在 {@code quota.record} 周围捕获；从这里抛出管线捕获不到）。 消息带 HTTP
   * 状态 + 非敏感摘要。
   */
  @Test
  void mapsProviderRateLimitToLlmExceptionNotQuotaExceeded() {
    status = 429;
    errorBody =
        "{\"error\":{\"message\":\"Rate limit reached for model\",\"type\":\"rate_limit_error\"}}";

    assertThatThrownBy(() -> client(ApiKeySource.none(), Duration.ofSeconds(5)).chat(oneUserTurn()))
        .isInstanceOf(LlmException.class)
        .isNotInstanceOf(QuotaExceededException.class)
        .hasMessageContaining("429")
        .hasMessageContaining("Rate limit reached for model");
  }

  /** R4 续：非 2xx 一族（含鉴权失败）都归 LlmException，且消息里只出现非敏感描述。 */
  @Test
  void mapsUnauthorizedToLlmExceptionWithNonSensitiveMessage() {
    status = 401;
    errorBody = "{\"error\":{\"message\":\"invalid api key\"}}";

    assertThatThrownBy(
            () -> client(() -> Optional.of(API_KEY), Duration.ofSeconds(5)).chat(oneUserTurn()))
        .isInstanceOf(LlmException.class)
        .isNotInstanceOf(QuotaExceededException.class)
        .hasMessageContaining("401")
        .hasMessageContaining("本消息不含密钥值")
        .hasMessageNotContaining(API_KEY);
  }

  // ---------- R2 密钥纪律 ----------

  /**
   * R2 pin：密钥经 {@link OpenAICompatibleLlmClient.ApiKeySource} 注入并真的发出去（先证 Authorization 头到位，
   * 再证它不外泄——否则"消息里没有密钥"是空转）；供应商把密钥回显进错误体时，异常消息里必须已被抹成 {@code ***}。
   */
  @Test
  void neverLeaksApiKeyIntoExceptionMessage() {
    status = 400;
    errorBody =
        "{\"error\":{\"message\":\"bad request: Authorization: Bearer " + API_KEY + " rejected\"}}";

    assertThatThrownBy(
            () -> client(() -> Optional.of(API_KEY), Duration.ofSeconds(5)).chat(oneUserTurn()))
        .isInstanceOf(LlmException.class)
        .hasMessageNotContaining(API_KEY)
        .hasMessageContaining("***");

    assertThat(lastAuthorization.get()).isEqualTo("Bearer " + API_KEY);
  }

  /**
   * F3 pin：取密钥 SPI 抛异常时，其异常消息<b>绝不</b>进本类的异常消息——后者会经 {@code AgentPipeline} 的 {@code
   * emitDecision("LLM_ERROR", reason = e.getMessage())} 落进<b>持久事件库并展示给用户</b>（不只是瞬时日志），而 SPI 的
   * 消息里可能内嵌凭据值（本类无法净化它根本没拿到的值）。
   *
   * <p>判别性：金丝雀串同时以"裸文本"与"URL userinfo"（{@code https://user:CANARY@vault.local/v1}）两种形态出现在 SPI
   * 的异常消息里——前者钉"整段拼接"（仅靠 URL 正则救不了它），后者钉"只抹 URL"这一半。同时断言 cause 仍保留原异常与其消息 （诊断力不丢，只是不进那条会被持久化的消息）。
   */
  @Test
  void keepsKeySourceFailureMessageOutOfExceptionMessage() {
    String canary = "credential-canary-8f21";
    ApiKeySource exploding =
        () -> {
          throw new IllegalStateException(
              "keystore read failed for "
                  + canary
                  + " at https://user:"
                  + canary
                  + "@vault.local/v1");
        };

    LlmException failure =
        catchThrowableOfType(
            LlmException.class, () -> client(exploding, Duration.ofSeconds(5)).chat(oneUserTurn()));

    assertThat(failure).hasMessageContaining("取密钥失败").hasMessageNotContaining(canary);
    // 诊断力：原始异常（含其消息）留在 cause 里，只是不进"会被持久化并展示"的那条消息
    assertThat(failure.getCause())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(canary);
  }

  /**
   * R2：缺省 {@link OpenAICompatibleLlmClient.ApiKeySource#none()} 不带 {@code
   * Authorization}（匿名/本地部署可用）。
   */
  @Test
  void omitsAuthorizationHeaderWithoutKey() {
    script.add(sseFrame("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"匿名可用\"}}]}"));
    script.add(DONE_FRAME);

    LlmResponse response = client(ApiKeySource.none(), Duration.ofSeconds(5)).chat(oneUserTurn());

    assertThat(response.textPart()).contains("匿名可用");
    assertThat(lastAuthorization.get()).isNull();
  }

  // ---------- 请求侧形状 ----------

  /**
   * 请求体形状（协议契约的下行方向）：model/stream:true/stream_options/messages/tools + 工具结果与工具调用回填。 {@code
   * stream_options.include_usage} 的取舍理由见 {@link
   * #requestsUsageInStreamOptionsAndKeepsTokensUnknownWhenVendorOmitsUsage()}。
   */
  @Test
  void sendsOpenAiCompatibleRequestBody() {
    script.add(sseFrame("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"}}]}"));
    script.add(DONE_FRAME);
    LlmRequest request =
        new LlmRequest(
            List.of(
                LlmMessage.system("你是测试 Agent"),
                LlmMessage.user("跑一下 echo"),
                LlmMessage.assistant(
                    List.of(new ContentPart.ToolCall("call_9", "echo", Map.of("text", "x")))),
                LlmMessage.tool(new ContentPart.ToolResult("call_9", "echo", "结果", null))),
            List.of(
                ToolDef.of(
                    "echo",
                    "回显",
                    Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("text", Map.of("type", "string"))))));

    client(ApiKeySource.none(), Duration.ofSeconds(5)).chat(request);

    String body = requestBodies.get(0);
    assertThat(body)
        .contains("\"model\":\"" + ROUTE_MODEL + "\"")
        .contains("\"stream\":true")
        .contains("\"stream_options\":{\"include_usage\":true}")
        .contains("\"role\":\"system\"")
        .contains("\"role\":\"user\"")
        .contains("\"tool_calls\":[{\"id\":\"call_9\",\"type\":\"function\"")
        .contains("\"name\":\"echo\"")
        .contains("\"arguments\":\"{\\\"text\\\":\\\"x\\\"}\"")
        .contains("\"role\":\"tool\",\"tool_call_id\":\"call_9\",\"content\":\"结果\"")
        .contains("\"type\":\"function\",\"function\":{\"name\":\"echo\",\"description\":\"回显\"");
    assertThat(lastAccept.get()).isEqualTo("text/event-stream");
  }

  // ---------- 假端点 ----------

  private void handle(HttpExchange exchange) throws IOException {
    try {
      lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      lastAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
      requestBodies.add(
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      if (status != 200) {
        byte[] body = errorBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
        return;
      }
      if (script.isEmpty()) {
        // 空脚本 = 200 但不是 SSE（模拟代理/网关返回普通 JSON）
        byte[] body = "{\"object\":\"chat.completion\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
        return;
      }
      exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0); // 0 = chunked：分块写、逐块 flush
      OutputStream out = exchange.getResponseBody();
      for (byte[] chunk : script) {
        out.write(chunk);
        out.flush();
        writes.incrementAndGet();
        sleepQuietly(20); // 块间停顿：逼出"一次 read 拿不到完整帧"的真实形态
      }
      if (stallAfterScriptMillis > 0) {
        sleepQuietly(stallAfterScriptMillis);
      }
      exchange.close();
    } catch (IOException clientHungUp) {
      // 客户端按 [DONE] 提前停读、或读超时后取消连接，都是预期路径（服务端写失败而已）
    }
  }

  /**
   * 观测名 = 路由模型名：{@code agent.lifecycle} 与自检据此判"这个进程在跟谁说话"。报错名字会让"以为在用真模型 /
   * 以为在用假模型"两个方向同时失真——2026-09-12 U6 实跑抓到子体明明在调 deepseek-flash，生命周期事件却报 {@code fake}。
   */
  @Test
  void modelReportsTheRouteModel() {
    assertThat(client(ApiKeySource.none(), Duration.ofSeconds(1)).model()).isEqualTo(ROUTE_MODEL);
  }

  private OpenAICompatibleLlmClient client(
      OpenAICompatibleLlmClient.ApiKeySource apiKeySource, Duration readTimeout) {
    ModelRoute route = ModelRoute.of("test", baseUrl, ROUTE_MODEL, "keys.fake");
    return new OpenAICompatibleLlmClient(route, apiKeySource, Duration.ofSeconds(2), readTimeout);
  }

  private static LlmRequest oneUserTurn() {
    return LlmRequest.ofMessages(List.of(LlmMessage.user("在吗")));
  }

  private static List<ContentPart.ToolCall> toolCallsOf(LlmResponse response) {
    return response.assistantMessage().content().stream()
        .filter(ContentPart.ToolCall.class::isInstance)
        .map(ContentPart.ToolCall.class::cast)
        .toList();
  }

  // ---------- SSE 造帧工具 ----------

  private static final byte[] DONE_FRAME = rawBytes("data: [DONE]\n\n");

  private static byte[] sseFrame(String json) {
    return rawBytes("data: " + json + "\n\n");
  }

  /** 把参数分片转义成 JSON 字符串内容（分片自身含 {@code "}，直接嵌入会造出非法帧）。 */
  private static String jsonString(String raw) {
    return raw.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static byte[] rawBytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static String chunkWithToolCall(String argumentsFragment) {
    return "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
        + "\"type\":\"function\",\"function\":{\"name\":\"echo\",\"arguments\":\""
        + jsonString(argumentsFragment)
        + "\"}}]}}]}";
  }

  private static String toolCallFragment(
      int index, String id, String name, String argumentsFragment) {
    StringBuilder json =
        new StringBuilder("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{");
    json.append("\"index\":").append(index);
    if (id != null) {
      json.append(",\"id\":\"").append(id).append('"');
    }
    json.append(",\"type\":\"function\",\"function\":{");
    if (name != null) {
      json.append("\"name\":\"").append(name).append("\",");
    }
    return json.append("\"arguments\":\"")
        .append(jsonString(argumentsFragment))
        .append("\"}}]}}]}")
        .toString();
  }

  /** 把字节串按下标切块（下标可落在多字节字符/行前缀中间——刻意为之）。 */
  private static List<byte[]> splitAt(byte[] data, int... cutPoints) {
    int[] ordered = cutPoints.clone();
    Arrays.sort(ordered);
    List<byte[]> chunks = new ArrayList<>();
    int from = 0;
    for (int cut : ordered) {
      chunks.add(Arrays.copyOfRange(data, from, cut));
      from = cut;
    }
    chunks.add(Arrays.copyOfRange(data, from, data.length));
    return chunks;
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
