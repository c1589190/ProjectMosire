package io.mosire.agentlib.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 真实 LLM 调用实现：OpenAI 兼容 {@code POST {baseUrl}/chat/completions}（JDK {@link HttpClient} + 手写 SSE 解析，
 * 不引第三方 HTTP/SSE 依赖）。
 *
 * <p><b>为什么本类不重试</b>（计划原文写"超时重试"，此处以 {@link LlmClient#chat} 的冻结契约为准——契约明写"不吞错、不重试（策略在调用方）"）：
 *
 * <ol>
 *   <li>重试是调用方策略：{@link LlmClient#chat} 的契约把"一次 {@code chat} = 一次请求"定死，Brain 主循环与 {@link
 *       FakeLlmClient} 都按此设计；实现里偷偷重试会改变所有调用点的耗时与失败语义。
 *   <li>会破坏 D18 的每次调用记账：{@code AgentPipeline} 每次 {@code chat} 落一条 {@code llm.call}（ input/output
 *       token + latency）——内部重试会让"一次调用"对应多次供应商请求，token 账目与延迟全部失真。
 *   <li>会向调用方隐藏失败与耗时：失败被重试掩盖后，上层再也看不见"供应商在抖"这件事。
 * </ol>
 *
 * <p>重试策略（指数退避/按状态码分类/上限）归调用方或后续任务，本类只保证"一次调用 = 一次请求"。
 *
 * <p><b>超时边界</b>：构造期设连接超时；每次调用以 {@code readTimeout} 为整个交换（连接 + 响应头 + 流式读取）的硬上限， 到点抛 {@link
 * LlmException}——<b>绝不无限挂起</b>。JDK 的 {@link HttpRequest#timeout} 只覆盖到"响应头到达"，流式响应体 （{@code [DONE]}
 * 之前的阻塞读）没有超时保护，故实际交换跑在虚拟线程上、调用线程做有界等待（虚拟线程天生守护， 超时后即使读线程仍卡在 socket 上也不会拖住 JVM 退出）。
 *
 * <p><b>供应商错误 vs 本地配额（不得混为一谈）</b>：供应商 429/限流等非 2xx 一律抛 {@link LlmException}（消息带 HTTP 状态 +
 * 非敏感摘要），<b>绝不</b>抛 {@link QuotaExceededException}、<b>绝不</b>映射成 Brain 的 {@code
 * StopReason.QUOTA}——后者语义是"<b>本地账本</b>超限"（由 {@link LlmQuota#record} 抛、由 {@code AgentPipeline} 只在
 * {@code quota.record(...)} 周围捕获并终止）；从 LLM 调用处抛 {@link QuotaExceededException} 管线<b>捕获不到</b>，
 * 只会变成误导性的错误分类。
 *
 * <p><b>密钥</b>：不走 {@link ModelRoute#credentialsRef} 自解析（那是 ConfigStore 的职责，尚无此实现），而是构造注入一个 {@link
 * ApiKeySource}（缺省 {@link ApiKeySource#none()} 返回空 = 匿名，本地部署如 Ollama 可用）。硬约束：本类<b>不</b>读环境变量
 * 或系统属性取密钥（那是绕过 ConfigStore 的第二条路）；密钥值<b>不进日志、不进异常消息</b>（异常消息里只出现"鉴权失败/未配置密钥" 之类的非敏感描述，且违规回显的密钥会被抹成
 * {@code ***}）。密钥每次调用现取，不在字段里长期持有。
 *
 * <p><b>SSE 解析契约</b>（手解，{@code stream:true} 恒开）：
 *
 * <ul>
 *   <li>帧按行拆分并<b>跨读边界缓冲</b>（一次 {@code read} 拿不到完整 {@code data:} 行是常态）；UTF-8 增量解码，多字节字符被拆开也不会撕裂。
 *   <li>{@code tool_calls} 的 {@code function.arguments} 是<b>逐块增量</b>到达的：必须跨 chunk 拼接后再反序列化成 {@link
 *       ContentPart.ToolCall#arguments()}；{@code id}/{@code name} 按 OpenAI 语义在首帧一次性给出，取首个非空值。
 *   <li>多个 tool call 按 {@code index} 分桶（允许不同调用的分片交错到达），结果按 {@code index} 升序交给 {@link LlmResponse}。
 *   <li>{@code usage} 通常在末尾 chunk，落到 {@link LlmResponse} 的 input/output/缓存 token 字段；供应商未上报的字段保持
 *       {@link LlmResponse#UNKNOWN_TOKENS}（-1），不编造 0。
 *   <li><b>{@code usage} 与配额账本</b>：请求体<b>必须</b>显式带 {@code
 *       stream_options:{"include_usage":true}}——OpenAI 规范下 {@code stream:true} 时 {@code usage}
 *       <b>默认不下发</b>；不发该键，四个 token 字段恒为 {@link LlmResponse#UNKNOWN_TOKENS}（-1），而 {@link
 *       LlmQuota#record} 把负值夹成 0 → <b>本地配额账本静默失效</b> （"配额超限 → {@code StopReason.QUOTA}"这条验收路径在真实
 *       OpenAI 上不可达）。DeepSeek 等供应商原生默认带 usage， 不发也"看起来正常"——这正是必须显式发的原因。该键对忽略未知字段的供应商无副作用。
 *   <li>{@code data: [DONE]} 为终止符：见到即停止读取并返回（其后内容一概不再解析、不再报错）。
 *   <li>流在 {@code [DONE]} 之前 EOF = 响应被截断 → {@link LlmException}（宁可响亮失败，也不静默交回半截响应）。
 * </ul>
 *
 * <p>本类实例线程安全（字段不可变，{@link HttpClient} 可并发使用），一个实例对应一条 {@link ModelRoute}；路由由调用方经 {@link
 * ModelProvider} 解析后传入。
 */
public final class OpenAICompatibleLlmClient implements LlmClient {

  /** 默认连接超时（与供应商建连的上限）。 */
  public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

  /** 默认读取超时：一次 {@code chat} 从发起到拿到完整响应的硬上限（含流式读取）。 */
  public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(60);

  private static final Logger LOG = LoggerFactory.getLogger(OpenAICompatibleLlmClient.class);

  /** OpenAI 兼容补全端点：{@code {baseUrl}/chat/completions}。 */
  private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

  /** SSE 数据行前缀。 */
  private static final String DATA_PREFIX = "data:";

  /** 流终止符。 */
  private static final String DONE = "[DONE]";

  /** 错误摘要的最大字符数（异常消息/日志里只许出现有界的非敏感片段）。 */
  private static final int MAX_EXCERPT_CHARS = 200;

  /** 错误响应体的最大读取字节数（有界读取，防供应商甩一个超大 body）。 */
  private static final int MAX_ERROR_BODY_BYTES = 8 * 1024;

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final TypeReference<Map<String, Object>> ARGUMENTS_TYPE = new TypeReference<>() {};

  private final ModelRoute route;
  private final ApiKeySource apiKeySource;
  private final HttpClient http;
  private final Duration readTimeout;
  private final URI chatCompletionsUri;

  /**
   * 取密钥 SPI：把"密钥从哪来"留给宿主（真实实现归 ConfigStore / {@code keys.*} 接线，见 T20/P2-7）。
   *
   * <p>约定：返回 {@link Optional#empty()} = 无密钥（匿名调用，本地部署常见）；实现抛异常视为取密钥失败，按调用失败处理。
   * 实现不应记录/回显密钥值（本类也不会）。每次 {@link #chat} 调用都会重新取一次，便于调用方实现轮换/过期感知。
   *
   * <p><b>对实现的硬性契约：不得在异常消息或 URL 中内嵌密钥/凭据值。</b> 取密钥失败时本类只会给出固定的非敏感文案并保留异常为
   * cause——客户端<b>无法</b>净化它根本没拿到的值（例如解析器把 {@code https://user:token@vault/…} 原样写进异常消息，本类 无从得知其中的
   * token 是凭据）。需要上报失败细节时请把凭据抹成占位符再写进消息。
   */
  public interface ApiKeySource {

    /**
     * 取当前应使用的密钥。
     *
     * @return 密钥；空 = 无密钥（请求不带 {@code Authorization}）
     */
    Optional<String> apiKey();

    /** 缺省实现：不提供密钥（离线单测与匿名部署无需任何密钥基础设施）。 */
    static ApiKeySource none() {
      return Optional::empty;
    }
  }

  /** 缺省组装：匿名、默认超时。 */
  public OpenAICompatibleLlmClient(ModelRoute route) {
    this(route, ApiKeySource.none());
  }

  /** 注入取密钥 SPI（默认超时）。 */
  public OpenAICompatibleLlmClient(ModelRoute route, ApiKeySource apiKeySource) {
    this(route, apiKeySource, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
  }

  /**
   * 全参组装。
   *
   * @param route 模型路由（{@code baseUrl} 是 API 根，如 {@code https://api.deepseek.com/v1}；本类在其后拼 {@code
   *     /chat/completions}）
   * @param apiKeySource 取密钥 SPI（见 {@link ApiKeySource}）
   * @param connectTimeout 建连超时（必须为正）
   * @param readTimeout 单次调用的整体读取超时（必须为正）
   */
  public OpenAICompatibleLlmClient(
      ModelRoute route, ApiKeySource apiKeySource, Duration connectTimeout, Duration readTimeout) {
    this.route = Objects.requireNonNull(route, "route");
    this.apiKeySource = Objects.requireNonNull(apiKeySource, "apiKeySource");
    positive(connectTimeout, "connectTimeout");
    this.readTimeout = positive(readTimeout, "readTimeout");
    this.chatCompletionsUri =
        URI.create(stripTrailingSlashes(route.baseUrl()) + CHAT_COMPLETIONS_PATH);
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            // 不跟随重定向：避免把 Authorization 头带到另一个源（宁可让调用方看见真实的重定向响应）
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /** 本实例绑定的路由（只读）。 */
  public ModelRoute route() {
    return route;
  }

  /**
   * 发起一次补全（一次调用 = 一次请求，不重试）。
   *
   * @param request 消息序列 + 工具目录
   * @return 助手消息（文本与工具调用拼装完毕）+ token 记账
   * @throws LlmException 网络失败/超时/非 2xx/协议违约（SSE 无法解析、arguments 非法 JSON、流被截断）——不吞错
   */
  @Override
  public LlmResponse chat(LlmRequest request) throws LlmException {
    Objects.requireNonNull(request, "request");
    // 超时边界：整个交换跑在虚拟线程上，调用线程以 readTimeout 为界等待。见类 Javadoc"超时边界"。
    CompletableFuture<LlmResponse> outcome = new CompletableFuture<>();
    // 让超时路径能关掉底层响应流（尽量解开仍卡在 read 上的读线程；关不掉也不影响调用方已按时返回）
    AtomicReference<InputStream> bodyRef = new AtomicReference<>();
    Thread.ofVirtual()
        .name("mosire-llm-sse")
        .start(
            () -> {
              try {
                outcome.complete(exchange(request, bodyRef));
              } catch (Throwable failure) {
                // 连 Error 也要定案：异常路径若漏过，调用方只能白等到超时，真正的原因被掩盖
                outcome.completeExceptionally(failure);
              }
            });
    try {
      return outcome.get(readTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException timeout) {
      outcome.cancel(true);
      LlmException failure =
          new LlmException(
              "LLM 调用超时：" + readTimeout.toMillis() + "ms 内未拿到完整响应（连接/响应头/SSE 流，供应商未在期限内完成）",
              timeout);
      closeQuietly(bodyRef.get(), failure);
      throw failure;
    } catch (InterruptedException interrupted) {
      outcome.cancel(true);
      Thread.currentThread().interrupt();
      LlmException failure = new LlmException("LLM 调用被中断", interrupted);
      closeQuietly(bodyRef.get(), failure);
      throw failure;
    } catch (ExecutionException wrapped) {
      throw asLlmException(wrapped.getCause());
    }
  }

  /** 真正的交换（跑在虚拟线程上；本方法内的任何失败都由 {@link #chat} 归类成 {@link LlmException}）。 */
  private LlmResponse exchange(LlmRequest request, AtomicReference<InputStream> bodyRef)
      throws IOException, InterruptedException {
    String apiKey = apiKeyOf();
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(chatCompletionsUri)
            .timeout(readTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody(request)));
    if (!apiKey.isEmpty()) {
      builder.header("Authorization", "Bearer " + apiKey);
    }
    HttpResponse<InputStream> response =
        http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw providerFailure(response, apiKey);
    }
    InputStream body = response.body();
    bodyRef.set(body);
    try {
      return parseSseStream(
          body,
          route.model(),
          response.headers().firstValue("Content-Type").orElse("(未知)"),
          apiKey);
    } finally {
      closeQuietly(body, null);
    }
  }

  /**
   * 取当前密钥（每次调用现取；空串 = 匿名）。
   *
   * <p><b>取密钥失败时绝不把 SPI 异常的消息原文拼进本类的消息</b>：本类的消息会经 {@code AgentPipeline} 的 {@code
   * emitDecision("LLM_ERROR", reason = e.getMessage())} 落进<b>持久事件库并展示给用户</b>（不只是瞬时日志），而密钥解析
   * 实现抛出的异常消息里可能内嵌凭据值——客户端<b>无法</b>净化它根本没拿到的值（只能靠约定，见 {@link ApiKeySource}）。故这里用固定 的非敏感文案，原始异常经
   * {@code cause} 保留（诊断力不丢：栈与类型都还在）。
   */
  private String apiKeyOf() {
    try {
      return apiKeySource.apiKey().map(String::trim).filter(key -> !key.isEmpty()).orElse("");
    } catch (RuntimeException keyFetchFailure) {
      throw new LlmException("取密钥失败（详见 cause：宿主侧密钥解析异常，消息不外显，以免泄漏凭据）", keyFetchFailure);
    }
  }

  /** 把任意失败归类成契约允许的 {@link LlmException}（不吞错、不重试、不泄漏敏感值）。 */
  private static LlmException asLlmException(Throwable cause) {
    if (cause instanceof Error error) {
      throw error;
    }
    if (cause instanceof LlmException llm) {
      return llm;
    }
    if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
      return new LlmException("LLM 请求超时（连接或响应头未在期限内到达）", cause);
    }
    if (cause instanceof InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new LlmException("LLM 调用被中断", interrupted);
    }
    String detail = cause == null ? "未知原因" : cause.getClass().getSimpleName();
    return new LlmException("LLM 调用失败（" + detail + safeDetail(cause) + "）", cause);
  }

  /** 附带的诊断文本：抹掉 URL（{@code baseUrl} 里可能内嵌凭据/查询串）后折叠截断——密钥绝不进异常消息。 */
  private static String safeDetail(Throwable cause) {
    if (cause == null || cause.getMessage() == null || cause.getMessage().isBlank()) {
      return "";
    }
    return ": " + oneLine(cause.getMessage().replaceAll("https?://\\S+", "<url>"));
  }

  /**
   * 供应商非 2xx：消息带 HTTP 状态 + 非敏感摘要。
   *
   * <p>429/限流在这里是 {@link LlmException}，<b>不是</b> {@link QuotaExceededException}（见类 Javadoc）。
   */
  private static LlmException providerFailure(HttpResponse<InputStream> response, String apiKey) {
    int status = response.statusCode();
    String prefix = "LLM 供应商返回 HTTP " + status + statusHint(status);
    LlmException plain = new LlmException(prefix);
    String excerpt;
    try (InputStream body = response.body()) {
      String raw = new String(body.readNBytes(MAX_ERROR_BODY_BYTES), StandardCharsets.UTF_8);
      excerpt = excerptOf(raw, apiKey, plain);
    } catch (IOException unreadable) {
      // 错误体读不到不影响分类（状态码已是主信息）；不吞：挂到 suppressed 供排查
      plain.addSuppressed(unreadable);
      excerpt = "";
    }
    return excerpt.isEmpty() ? plain : new LlmException(prefix + ": " + excerpt, plain);
  }

  /** 状态码的非敏感人话提示（只描述类别，不含任何请求/密钥内容）。 */
  private static String statusHint(int status) {
    if (status == 429) {
      return "（供应商限流/配额）";
    }
    if (status == 401 || status == 403) {
      return "（鉴权失败：密钥未配置或不匹配；本消息不含密钥值）";
    }
    if (status == 404) {
      return "（端点或模型不存在）";
    }
    if (status >= 500) {
      return "（供应商内部错误）";
    }
    return "";
  }

  /**
   * 从错误体里取一小段非敏感摘要：优先 {@code error.message}，退化到顶层 {@code message}。
   *
   * <p>不整段回显响应体（错误体可能回显请求内容）；解析失败挂在 {@code sink} 的 suppressed 上而不是静默丢弃；若供应商把密钥回显 进了错误消息，一律抹成 {@code
   * ***}。
   */
  private static String excerptOf(String rawBody, String apiKey, LlmException sink) {
    if (rawBody.isBlank()) {
      return "";
    }
    JsonNode node;
    try {
      node = JSON.readTree(rawBody);
    } catch (IOException malformed) {
      sink.addSuppressed(malformed);
      return "";
    }
    JsonNode error = node.path("error");
    String message =
        error.isObject() ? error.path("message").asText("") : node.path("message").asText("");
    String excerpt = oneLine(message);
    if (!apiKey.isEmpty()) {
      excerpt = excerpt.replace(apiKey, "***");
    }
    return excerpt;
  }

  /** 折叠换行并按上限截断（异常消息只放有界文本）。 */
  private static String oneLine(String text) {
    String collapsed = text.replaceAll("\\s+", " ").trim();
    return collapsed.length() <= MAX_EXCERPT_CHARS
        ? collapsed
        : collapsed.substring(0, MAX_EXCERPT_CHARS) + "…";
  }

  /**
   * 请求体：{@code {model, stream:true, stream_options:{include_usage:true}, messages[], tools[]}}。
   *
   * <p>{@code stream_options.include_usage} <b>不是可选项</b>：OpenAI 规范下 {@code stream:true} 时 usage
   * 默认不下发，不发该键 则 token 字段恒为 -1 → {@link LlmQuota} 的本地账本永远不超限（理由详见类 Javadoc"usage 与配额账本"）。供应商若忽略未知
   * 字段，该键无副作用。
   */
  private byte[] requestBody(LlmRequest request) throws IOException {
    ObjectNode root = JSON.createObjectNode();
    root.put("model", route.model());
    root.put("stream", true);
    // 显式索要 usage：见方法 Javadoc。放在 stream 之后、messages 之前（无协议要求，只为可读）
    root.putObject("stream_options").put("include_usage", true);
    ArrayNode messages = root.putArray("messages");
    for (LlmMessage message : request.messages()) {
      appendMessage(messages, message);
    }
    if (!request.tools().isEmpty()) {
      ArrayNode tools = root.putArray("tools");
      for (ToolDef tool : request.tools()) {
        ObjectNode entry = tools.addObject();
        entry.put("type", "function");
        ObjectNode function = entry.putObject("function");
        function.put("name", tool.name());
        function.put("description", tool.description());
        function.set("parameters", JSON.valueToTree(tool.jsonSchema()));
      }
    }
    return JSON.writeValueAsBytes(root);
  }

  /**
   * 把一条 {@link LlmMessage} 追加到 OpenAI 兼容的 messages 数组。
   *
   * <p>工具结果按 OpenAI 协议是<b>独立消息</b>（{@code role:tool} + {@code tool_call_id}），故一条消息里的多个 {@link
   * ContentPart.ToolResult} 会展开成多条；其余角色只产出一条消息（文本分片拼成一个字符串）。
   */
  private static void appendMessage(ArrayNode messages, LlmMessage message) {
    List<ContentPart.ToolResult> results = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    List<ContentPart.ToolCall> calls = new ArrayList<>();
    for (ContentPart part : message.content()) {
      if (part instanceof ContentPart.Text textPart) {
        text.append(textPart.text());
      } else if (part instanceof ContentPart.ToolResult result) {
        results.add(result);
      } else if (part instanceof ContentPart.ToolCall call) {
        calls.add(call);
      }
    }
    if (!results.isEmpty()) {
      for (ContentPart.ToolResult result : results) {
        ObjectNode tool = messages.addObject();
        tool.put("role", LlmMessage.ROLE_TOOL);
        tool.put("tool_call_id", result.toolCallId());
        // ContentPart.ToolResult 保证 content/error 恰好一个非空：失败回填也走 content（OpenAI 无错误位）
        tool.put("content", result.isError() ? result.error() : result.content());
      }
      return;
    }
    ObjectNode node = messages.addObject();
    node.put("role", message.role());
    if (!text.isEmpty()) {
      node.put("content", text.toString());
    } else if (!calls.isEmpty()) {
      node.putNull("content");
    } else {
      node.put("content", "");
    }
    if (!calls.isEmpty()) {
      ArrayNode toolCalls = node.putArray("tool_calls");
      for (ContentPart.ToolCall call : calls) {
        ObjectNode entry = toolCalls.addObject();
        entry.put("id", call.id());
        entry.put("type", "function");
        ObjectNode function = entry.putObject("function");
        function.put("name", call.name());
        function.put("arguments", JSON.valueToTree(call.arguments()).toString());
      }
    }
  }

  /**
   * 手解 SSE 流（起承转合见类 Javadoc"SSE 解析契约"）。
   *
   * @param body 响应体（流式；本方法只读、不关）
   * @param fallbackModel 流里没带 {@code model} 时的兜底（路由配置值）
   * @param contentType 仅用于"不是 SSE"时的错误描述
   * @param apiKey 仅用于把误回显的密钥抹掉
   */
  private static LlmResponse parseSseStream(
      InputStream body, String fallbackModel, String contentType, String apiKey)
      throws IOException {
    Accumulator accumulator = new Accumulator(fallbackModel, apiKey);
    // 行缓冲式读取：帧跨读边界切开、UTF-8 多字节字符被拆开都由 BufferedReader/InputStreamReader 兜住
    BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
    String line;
    while ((line = reader.readLine()) != null) {
      if (accumulator.acceptLine(line)) {
        return accumulator.toResponse(contentType);
      }
    }
    throw accumulator.truncatedStream(contentType);
  }

  /** SSE 累加器：把逐行到达的 {@code data:} 帧拼装成一次 {@link LlmResponse}。 */
  private static final class Accumulator {

    private final String fallbackModel;
    private final String apiKey;
    private final StringBuilder text = new StringBuilder();

    /** 按 OpenAI 的 {@code index} 分桶——不同工具调用的分片允许交错到达。 */
    private final Map<Integer, ToolCallBuffer> calls = new TreeMap<>();

    private String model;
    private int dataChunks;
    private long inputTokens = LlmResponse.UNKNOWN_TOKENS;
    private long outputTokens = LlmResponse.UNKNOWN_TOKENS;
    private long cacheReadTokens = LlmResponse.UNKNOWN_TOKENS;
    private long cacheWriteTokens = LlmResponse.UNKNOWN_TOKENS;

    Accumulator(String fallbackModel, String apiKey) {
      this.fallbackModel = fallbackModel;
      this.apiKey = apiKey;
    }

    /** 吃一行；返回 true = 已见 {@code data: [DONE]}（调用方应停止读取）。 */
    boolean acceptLine(String line) {
      String trimmed = line.strip();
      if (!trimmed.startsWith(DATA_PREFIX)) {
        // 空行分隔符、SSE 注释（: ping）与 event:/id: 等字段一律忽略——OpenAI 的负载全在 data: 行
        return false;
      }
      String payload = trimmed.substring(DATA_PREFIX.length()).strip();
      if (payload.isEmpty()) {
        return false;
      }
      if (DONE.equals(payload)) {
        return true;
      }
      dataChunks++;
      applyChunk(parseFrame(payload));
      return false;
    }

    private JsonNode parseFrame(String payload) {
      try {
        return JSON.readTree(payload);
      } catch (IOException malformed) {
        throw new LlmException(
            "SSE data 帧不是合法 JSON（供应商协议违约）: " + scrub(oneLine(payload)), malformed);
      }
    }

    private void applyChunk(JsonNode chunk) {
      JsonNode error = chunk.path("error");
      if (error.isObject() || error.isTextual()) {
        String detail =
            oneLine(
                error.isObject() ? error.path("message").asText(error.toString()) : error.asText());
        throw new LlmException("LLM 供应商在流中返回错误: " + scrub(detail));
      }
      if (model == null && chunk.path("model").isTextual()) {
        model = chunk.path("model").asText();
      }
      JsonNode usage = chunk.path("usage");
      if (usage.isObject()) {
        readUsage(usage);
      }
      for (JsonNode choice : chunk.path("choices")) {
        applyDelta(choice.path("delta"));
      }
    }

    /**
     * usage 在末尾 chunk（请求侧已显式带 {@code stream_options.include_usage}，见 {@link
     * #requestBody}）；缺字段保持"未知"。
     */
    private void readUsage(JsonNode usage) {
      inputTokens = longOr(usage.get("prompt_tokens"), inputTokens);
      outputTokens = longOr(usage.get("completion_tokens"), outputTokens);
      JsonNode details = usage.path("prompt_tokens_details");
      cacheReadTokens =
          longOr(
              details.get("cached_tokens"),
              longOr(usage.get("prompt_cache_hit_tokens"), cacheReadTokens));
      cacheWriteTokens = longOr(usage.get("cache_creation_input_tokens"), cacheWriteTokens);
    }

    /**
     * 吃一个 delta：文本逐块追加；tool_calls 的分片按 index 归桶，{@code arguments} 跨 chunk 拼接。
     *
     * <p>{@code reasoning_content}（思维链）与 {@code role} 有意忽略——前者不属回答内容，后者由请求侧决定。
     */
    private void applyDelta(JsonNode delta) {
      JsonNode content = delta.path("content");
      if (content.isTextual()) {
        text.append(content.asText());
      }
      for (JsonNode call : delta.path("tool_calls")) {
        int index = call.path("index").asInt(0);
        ToolCallBuffer buffer = calls.computeIfAbsent(index, key -> new ToolCallBuffer(index));
        buffer.acceptId(call.path("id"));
        JsonNode function = call.path("function");
        buffer.acceptName(function.path("name"));
        JsonNode arguments = function.path("arguments");
        if (arguments.isTextual()) {
          buffer.arguments().append(arguments.asText());
        }
      }
    }

    /**
     * 拼装结果。
     *
     * <p><b>只有终止符、没有任何 data 帧</b>（供应商对空补全的退化形态）在这里响亮报"空完成"，与 {@link #notSseStream} 的"不是
     * SSE"刻意分成两类文案——"供应商什么也没产出"与"接错了端点/不是流式响应"是两种完全不同的 故障，混为一谈会让排查方向跑偏。为什么当失败而不是交回空响应：①按 OpenAI
     * 协议，正常完成至少会有 {@code finish_reason} 帧（现在还会带 usage），零 data
     * 帧意味着供应商/网关把内容整个丢了，交回空响应就是静默数据丢失（本项目最坏的失败模式）； ②零 data 帧同时意味着零
     * usage，静默返回会让配额账本"看起来一切正常"（与请求侧必须显式索要 usage 是同一类静默失效问题）。
     */
    LlmResponse toResponse(String contentType) {
      if (dataChunks == 0) {
        throw new LlmException(
            "SSE 流是空完成：只见到终止符 data: [DONE]、没有任何内容帧（供应商未产出任何内容与 usage，Content-Type="
                + contentType
                + "）——不予采信空响应（见本方法 Javadoc）");
      }
      List<ContentPart> parts = new ArrayList<>();
      if (!text.isEmpty()) {
        parts.add(new ContentPart.Text(text.toString()));
      }
      for (ToolCallBuffer buffer : calls.values()) {
        parts.add(buffer.toToolCall());
      }
      return new LlmResponse(
          LlmMessage.assistant(parts),
          model == null ? fallbackModel : model,
          inputTokens,
          outputTokens,
          cacheReadTokens,
          cacheWriteTokens);
    }

    /** 没等到 {@code [DONE]} 就 EOF：流被截断，响亮失败而不是交回半截响应。 */
    LlmException truncatedStream(String contentType) {
      if (dataChunks == 0) {
        return notSseStream(contentType);
      }
      return new LlmException(
          "SSE 流在 data: [DONE] 之前结束（响应被截断：已收到 " + dataChunks + " 个 data 帧）——不予采信半截响应");
    }

    /**
     * 一个 data 帧都没有、也没等到终止符：更像是"接错了端点/响应根本不是 SSE"（例如网关返回普通 JSON，或空响应体）。
     *
     * <p>与"空完成"（{@link #toResponse}：是 SSE、有终止符，但供应商没产出内容）刻意使用不同文案——两类故障的排查方向不同。
     */
    private LlmException notSseStream(String contentType) {
      return new LlmException(
          "SSE 响应没有任何 data 帧，也没见到终止符 data: [DONE]（不是 OpenAI 兼容的流式响应？Content-Type="
              + contentType
              + "）");
    }

    /** 误回显的密钥一律抹掉（本类只在此处对供应商文本做净化）。 */
    private String scrub(String excerpt) {
      return apiKey.isEmpty() ? excerpt : excerpt.replace(apiKey, "***");
    }
  }

  /** 一个工具调用的增量缓冲：{@code arguments} 逐块拼接，{@code id}/{@code name} 取首个非空值。 */
  private static final class ToolCallBuffer {

    private final int index;
    private final StringBuilder arguments = new StringBuilder();
    private String id;
    private String name;

    ToolCallBuffer(int index) {
      this.index = index;
    }

    StringBuilder arguments() {
      return arguments;
    }

    /** OpenAI 的 id 在首帧一次性给出（重复拼接会得到重复值），故取首个非空。 */
    void acceptId(JsonNode value) {
      if (id == null && value.isTextual() && !value.asText().isBlank()) {
        id = value.asText();
      }
    }

    /** name 同上（少数实现在首帧给出完整函数名）。 */
    void acceptName(JsonNode value) {
      if (name == null && value.isTextual() && !value.asText().isBlank()) {
        name = value.asText();
      }
    }

    ContentPart.ToolCall toToolCall() {
      if (id == null || name == null) {
        throw new LlmException(
            "SSE 的 tool_calls 缺少 id 或 name（index=" + index + "）：供应商协议违约，无法构造工具调用");
      }
      return new ContentPart.ToolCall(id, name, argumentsOf(name));
    }

    /** 拼接结果必须是 JSON 对象；本方法就是"只取首块/末块"那类静默截断的哨兵。 */
    private Map<String, Object> argumentsOf(String toolName) {
      String json = arguments.length() == 0 ? "{}" : arguments.toString();
      JsonNode node;
      try {
        node = JSON.readTree(json);
      } catch (IOException malformed) {
        throw new LlmException(
            "tool_calls 的 arguments 不是合法 JSON（tool=" + toolName + "，多为分片拼接不完整）: " + oneLine(json),
            malformed);
      }
      if (!node.isObject()) {
        throw new LlmException("tool_calls 的 arguments 必须是 JSON 对象（tool=" + toolName + "）");
      }
      if (containsNull(node)) {
        // ContentPart.ToolCall 的不可变快照（Map.copyOf）不接受 null 值——这里抢先响亮报错，不让裸 NPE 逃出去
        throw new LlmException(
            "tool_calls 的 arguments 含 JSON null（ContentPart.ToolCall 不接受 null 值，tool="
                + toolName
                + "）");
      }
      return JSON.convertValue(node, ARGUMENTS_TYPE);
    }

    private static boolean containsNull(JsonNode node) {
      if (node.isNull()) {
        return true;
      }
      for (JsonNode child : node) {
        if (containsNull(child)) {
          return true;
        }
      }
      return false;
    }
  }

  private static long longOr(JsonNode node, long fallback) {
    return node != null && node.isNumber() ? node.asLong() : fallback;
  }

  /** 关流：失败不影响已定案的结果（超时/中断路径把失败挂 suppressed，正常路径只记 debug）——关流是收尾，不是结果。 */
  private static void closeQuietly(InputStream body, LlmException sink) {
    if (body == null) {
      return;
    }
    try {
      body.close();
    } catch (IOException closeFailure) {
      if (sink != null) {
        sink.addSuppressed(closeFailure);
      } else {
        LOG.debug("关闭 LLM 响应流失败（忽略）", closeFailure);
      }
    }
  }

  private static Duration positive(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " 必须为正: " + value);
    }
    return value;
  }

  private static String stripTrailingSlashes(String baseUrl) {
    String trimmed = baseUrl.strip();
    int end = trimmed.length();
    while (end > 0 && trimmed.charAt(end - 1) == '/') {
      end--;
    }
    return trimmed.substring(0, end);
  }
}
