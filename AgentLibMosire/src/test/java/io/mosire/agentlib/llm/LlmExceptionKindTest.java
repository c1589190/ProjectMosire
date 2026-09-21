package io.mosire.agentlib.llm;

import static io.mosire.agentlib.llm.FakeChatEndpoint.contentFrame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.mosire.agentlib.llm.LlmException.Kind;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * <b>B3 验收</b>：调用方（如 simos 的判定器降级路径）要能按<b>分类</b>决定"重试 / 降级 / 响亮炸"，而不是 {@code catch
 * (RuntimeException)} 一刀切。本文件钉两件事：
 *
 * <ol>
 *   <li>{@link Kind#retryable()} / {@link Kind#degradable()} 的<b>全真值表</b>——两个判据刻意分开：前者是时间维度
 *       （退避后再来一次值不值得），后者是降级面维度（拿不到结果时能否走保守默认值继续跑）。
 *   <li>用假端点造<b>真实 HTTP 响应</b>，验证状态码 → {@link Kind} 的映射（429/401/403/4xx/5xx，以及 3xx 那条"baseUrl
 *       配错了"的专门归类）——分类只在抛出点定，调用方不该去解析 message 文本。
 * </ol>
 */
class LlmExceptionKindTest {

  private static final String ROUTE_MODEL = "kind-route-model-fake";
  private static final String API_KEY = "not-a-real-credential-7f3a";

  private FakeChatEndpoint endpoint;

  @BeforeEach
  void startFakeEndpoint() throws IOException {
    endpoint = FakeChatEndpoint.start();
  }

  @AfterEach
  void stopFakeEndpoint() {
    endpoint.close();
  }

  // ---------- 真值表 ----------

  /**
   * B3 全真值表（逐格钉）。要点：{@link Kind#AUTH} 是"不可重试<b>但</b>可降级"的典型（密钥错了重试一万次也一样，但"这条路由今天用
   * 不了"可以由调用方走保守默认值兜住）；{@link Kind#CONFIG}/{@link Kind#CANCELLED}/{@link Kind#INTERNAL} 是"该炸"
   * 的一类——把某一格顺手改成"也能降级"（静默降级是本项目最坏的失败模式）必须让本用例变红。
   */
  @ParameterizedTest(name = "{0}: retryable={1}, degradable={2}")
  @CsvSource({
    "TRANSPORT, true, true",
    "TIMEOUT, true, true",
    "RATE_LIMIT, true, true",
    "PROVIDER_ERROR, true, true",
    "AUTH, false, true",
    "REQUEST_REJECTED, false, true",
    "PROTOCOL, false, true",
    "CONFIG, false, false",
    "NO_TEXT, false, true",
    "CANCELLED, false, false",
    "INTERNAL, false, false"
  })
  void kindTruthTable(Kind kind, boolean retryable, boolean degradable) {
    assertThat(kind.retryable()).isEqualTo(retryable);
    assertThat(kind.degradable()).isEqualTo(degradable);

    // 异常上的便捷方法必须与 kind() 等价（两处各写一份判据最容易漂移，调用方拿到的会是哪一份全凭运气）
    LlmException failure = new LlmException("测试用失败", kind);
    assertThat(failure.kind()).isEqualTo(kind);
    assertThat(failure.retryable()).isEqualTo(retryable);
    assertThat(failure.degradable()).isEqualTo(degradable);
  }

  /** 真值表必须覆盖全部 Kind：新增分类却忘了定"能不能重试/降级"时，本用例提醒补表（改这里 = 明确承认新增了一类失败）。 */
  @Test
  void kindTruthTableCoversEveryKind() {
    assertThat(Kind.values()).hasSize(11);
  }

  /** 未分类失败一律收口到 {@link Kind#INTERNAL}（保守判"该炸"）：不确定的失败不许悄悄重试，也不许悄悄降级。 */
  @Test
  void unclassifiedAndNullKindFallBackToInternal() {
    assertThat(new LlmException("裸消息").kind()).isEqualTo(Kind.INTERNAL);
    assertThat(new LlmException("带 cause", new IllegalStateException("boom")).kind())
        .isEqualTo(Kind.INTERNAL);
    assertThat(new LlmException("显式 null", (Kind) null).kind()).isEqualTo(Kind.INTERNAL);
  }

  // ---------- 状态码 → Kind（端到端） ----------

  /**
   * B3 端到端：真 HTTP 响应（假端点）→ 异常分类。分类只看状态码类别、不求解析供应商错误体（各家错误体结构不一，从自由文本里猜 分类等于造一个没人维护的映射表）。
   *
   * <p>{@code 302 → CONFIG} 也在表里：本客户端<b>不</b>跟随重定向（避免把 Authorization 带到另一个源），因此 3xx 的 成因是"baseUrl
   * 配错了"，不是"供应商在抖"——不该被归进可重试的一类。
   */
  @ParameterizedTest(name = "HTTP {0} → {1}")
  @CsvSource({
    "429, RATE_LIMIT",
    "401, AUTH",
    "403, AUTH",
    "400, REQUEST_REJECTED",
    "404, REQUEST_REJECTED",
    "503, PROVIDER_ERROR",
    "500, PROVIDER_ERROR",
    "302, CONFIG"
  })
  void mapsHttpStatusToKindEndToEnd(int httpStatus, Kind expected) {
    endpoint.failWith(
        httpStatus, "{\"error\":{\"message\":\"fake-upstream-status-" + httpStatus + "\"}}");

    LlmException failure =
        catchThrowableOfType(
            LlmException.class, () -> endpoint.client(ROUTE_MODEL).chat(oneUserTurn()));

    assertThat(failure.kind()).isEqualTo(expected);
    assertThat(failure.getMessage()).contains(String.valueOf(httpStatus));
    // 供应商侧失败绝不与"本地账本超限"混同（后者是 QuotaExceededException 的语义）
    assertThat(failure).isNotInstanceOf(QuotaExceededException.class);
  }

  /**
   * B3 端到端 pin（429）：限流是"可重试 + 可降级"，消息带 HTTP 状态与非敏感摘要。
   *
   * <p>同时钉住它<b>不是</b> {@link QuotaExceededException}：后者是"本地账本超限"的硬顶（防跑飞），而配额子系统只在 {@code
   * quota.record} 周围捕获它——把供应商限流归成它，管线根本捕获不到，只会变成误导性的错误分类。
   */
  @Test
  void rateLimitEndToEndIsRetryableAndDegradableButNeverALocalQuota() {
    endpoint.failWith(
        429,
        "{\"error\":{\"message\":\"Rate limit reached for model\",\"type\":\"rate_limit_error\"}}");

    LlmException failure =
        catchThrowableOfType(
            LlmException.class, () -> endpoint.client(ROUTE_MODEL).chat(oneUserTurn()));

    assertThat(failure.kind()).isEqualTo(Kind.RATE_LIMIT);
    assertThat(failure.retryable()).isTrue();
    assertThat(failure.degradable()).isTrue();
    assertThat(failure).isNotInstanceOf(QuotaExceededException.class);
    assertThat(failure.getMessage()).contains("429").contains("Rate limit reached for model");
  }

  /**
   * B3 端到端 pin（401）：鉴权失败 {@code retryable=false}（重试无用）、{@code degradable=true}（可走保守默认值继续跑，
   * <b>前提是失败被记录</b>，否则就是静默降级）。消息只含非敏感描述：密钥值绝不外泄。
   */
  @Test
  void authFailureEndToEndIsNotRetryableButDegradableAndLeaksNoKey() {
    endpoint.failWith(401, "{\"error\":{\"message\":\"invalid api key\"}}");

    LlmException failure =
        catchThrowableOfType(
            LlmException.class,
            () ->
                endpoint
                    .client(ROUTE_MODEL, () -> Optional.of(API_KEY), Duration.ofSeconds(5))
                    .chat(oneUserTurn()));

    assertThat(failure.kind()).isEqualTo(Kind.AUTH);
    assertThat(failure.retryable()).isFalse();
    assertThat(failure.degradable()).isTrue();
    assertThat(failure.getMessage()).contains("401").contains("本消息不含密钥值");
    assertThat(failure.getMessage()).doesNotContain(API_KEY);
  }

  /**
   * B3 端到端（非 HTTP 来源）：流在 {@code data: [DONE]} 之前被截断 → {@link Kind#PROTOCOL}（供应商协议违约），
   * 不是"网络抖动"也不是"供应商 5xx"——排查方向完全不同。
   */
  @Test
  void truncatedStreamIsProtocolKind() {
    // 有内容帧、但始终没等到 data: [DONE] 就 EOF（脚本到此为止）——供应商把流掐了
    endpoint.sayJson(contentFrame("半截"));

    LlmException failure =
        catchThrowableOfType(
            LlmException.class, () -> endpoint.client(ROUTE_MODEL).chat(oneUserTurn()));

    assertThat(failure.kind()).isEqualTo(Kind.PROTOCOL);
    assertThat(failure.retryable()).isFalse();
    assertThat(failure.degradable()).isTrue();
    assertThat(failure.getMessage()).contains("截断");
  }

  // ---------- 辅助 ----------

  private static LlmRequest oneUserTurn() {
    return LlmRequest.ofMessages(List.of(LlmMessage.user("在吗")));
  }
}
