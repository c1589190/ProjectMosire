package io.mosire.agentlib.compact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.context.ContextComposer;
import io.mosire.agentlib.context.ContextWindow;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.llm.ModelCapabilities;
import io.mosire.agentlib.llm.ToolDef;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 默认上下文压缩器的触发、摘要请求、严格失败与覆写点。
 *
 * <p>所有确定性夹具都用真实 {@link TokenEstimator}；只有阈值边界需要“恰好 200/199”的整数账，才让窗口正文长度直接决定估算值。
 */
class DefaultContextCompactorTest {

  private static final String PROMPT = "P";

  @Test
  void thresholdTriggersAtEqualityAndNotAtOneBelowUsingTheExactCapacityFormula() {
    CompactionPolicy policy =
        new CompactionPolicy(1000, 790, 100, 9, PROMPT); // trigger = 1000-790-1-9 = 200

    ScriptedSummarizer boundarySummarizer = ScriptedSummarizer.returning("short summary");
    DefaultContextCompactor boundary = new DefaultContextCompactor(boundarySummarizer, policy);
    ContextWindow boundaryWindow =
        new ContextWindow("boundary", List.of(LlmMessage.user("a".repeat(796))));

    CompactionOutcome boundaryOutcome =
        boundary.compactIfNeeded(boundaryWindow, List.of(), List.of(), new RecordingComposer());

    assertThat(boundaryOutcome.beforeTokens()).isEqualTo(200);
    assertThat(boundaryOutcome.triggerTokens()).isEqualTo(200);
    assertThat(boundaryOutcome.compacted()).isTrue();
    assertThat(boundaryOutcome.reason()).isEqualTo("threshold");
    assertThat(boundaryOutcome.afterTokens()).isLessThan(200);
    assertThat(boundarySummarizer.calls()).isEqualTo(1);

    ScriptedSummarizer belowSummarizer = ScriptedSummarizer.returning("must not be used");
    DefaultContextCompactor below = new DefaultContextCompactor(belowSummarizer, policy);
    ContextWindow belowWindow =
        new ContextWindow("below", List.of(LlmMessage.user("a".repeat(792))));

    CompactionOutcome belowOutcome =
        below.compactIfNeeded(belowWindow, List.of(), List.of(), new RecordingComposer());

    assertThat(belowOutcome.beforeTokens()).isEqualTo(199);
    assertThat(belowOutcome.triggerTokens()).isEqualTo(200);
    assertThat(belowOutcome.compacted()).isFalse();
    assertThat(belowOutcome.reason()).isEmpty();
    assertThat(belowOutcome.turnInput()).isEmpty();
    assertThat(belowSummarizer.calls()).isZero();
  }

  @Test
  void currentRequestEstimateIncludesToolsAndToolsCanMakeALowWindowTrigger() {
    CompactionPolicy policy = new CompactionPolicy(500, 200, 100, 0, PROMPT); // trigger = 299
    ContextWindow window = new ContextWindow("tools", List.of(LlmMessage.user("a".repeat(800))));
    List<LlmMessage> turnInput = List.of(LlmMessage.user("next"));

    RecordingComposer withoutToolsComposer = new RecordingComposer();
    ScriptedSummarizer withoutToolsSummarizer = ScriptedSummarizer.returning("unused");
    DefaultContextCompactor withoutTools =
        new DefaultContextCompactor(withoutToolsSummarizer, policy);
    CompactionOutcome withoutToolsOutcome =
        withoutTools.compactIfNeeded(window, turnInput, List.of(), withoutToolsComposer);

    assertThat(withoutToolsOutcome.compacted()).isFalse();
    assertThat(withoutToolsSummarizer.calls()).isZero();

    AgentTool largeTool =
        new TestTool(
            "large-tool", "t".repeat(600), Map.of("type", "object", "properties", Map.of()));
    RecordingComposer withToolsComposer = new RecordingComposer();
    ScriptedSummarizer withToolsSummarizer = ScriptedSummarizer.returning("tool summary");
    DefaultContextCompactor withTools = new DefaultContextCompactor(withToolsSummarizer, policy);

    CompactionOutcome withToolsOutcome =
        withTools.compactIfNeeded(window, turnInput, List.of(largeTool), withToolsComposer);

    assertThat(withoutToolsOutcome.beforeTokens()).isLessThan(299);
    assertThat(withToolsOutcome.beforeTokens()).isGreaterThanOrEqualTo(299);
    assertThat(withToolsComposer.requests.get(0).tools()).isNotEmpty();
    assertThat(withoutToolsComposer.requests.get(0).tools()).isEmpty();
    assertThat(withToolsOutcome.compacted()).isTrue();
    assertThat(withToolsSummarizer.calls()).isEqualTo(1);
    assertThat(withToolsSummarizer.requests().get(0).tools()).isEmpty();
  }

  @Test
  void summaryRequestIsVisibleHistoryPlusPromptWithNoToolsAndTheSummaryTokenCap() {
    CompactionPolicy policy = new CompactionPolicy(100_000, 1000, 321, 0, PROMPT);
    ContextWindow window =
        new ContextWindow(
            "summary-request",
            List.of(
                LlmMessage.user("visible-one"),
                LlmMessage.assistant(List.of(new ContentPart.Text("visible-answer")))));
    List<LlmMessage> visibleBefore = window.messages();
    ScriptedSummarizer summarizer = ScriptedSummarizer.returning("summary-result");
    DefaultContextCompactor compactor = new DefaultContextCompactor(summarizer, policy);
    compactor.requestCompaction();

    CompactionOutcome outcome =
        compactor.compactIfNeeded(
            window, List.of(LlmMessage.user("next-question")), List.of(), new RecordingComposer());

    assertThat(outcome.compacted()).isTrue();
    LlmRequest request = summarizer.requests().get(0);
    List<LlmMessage> expectedMessages = new ArrayList<>(visibleBefore);
    expectedMessages.add(LlmMessage.user(PROMPT));
    assertThat(request.messages()).containsExactlyElementsOf(expectedMessages);
    assertThat(request.tools()).isEmpty();
    assertThat(request.sampling().maxTokens()).isEqualTo(321);
    assertThat(outcome.summarizerResponse()).isNotNull();
    assertThat(outcome.summarizerResponse().textPart()).contains("summary-result");
  }

  @Test
  void successfulCompactionLeavesOneSyntheticUserAndEmptyTurnInputWhileRawEndsWithMarkerAndUser() {
    CompactionPolicy policy = new CompactionPolicy(100_000, 1000, 100, 0, PROMPT);
    ContextWindow window =
        new ContextWindow("success", List.of(LlmMessage.user("old"), LlmMessage.user("older")));
    ScriptedSummarizer summarizer = ScriptedSummarizer.returning("SUMMARY");
    DefaultContextCompactor compactor = new DefaultContextCompactor(summarizer, policy);
    compactor.requestCompaction();

    CompactionOutcome outcome =
        compactor.compactIfNeeded(
            window, List.of(LlmMessage.user("CURRENT")), List.of(), new RecordingComposer());

    assertThat(outcome.compacted()).isTrue();
    assertThat(outcome.turnInput()).isEmpty();
    assertThat(outcome.droppedMessages()).isEqualTo(2);
    assertThat(outcome.summaryTokens()).isEqualTo(new TokenEstimator().estimateText("SUMMARY"));
    assertThat(window.messages()).hasSize(1);
    assertThat(window.messages().get(0).role()).isEqualTo(LlmMessage.ROLE_USER);
    String projectedText = textOf(window.messages().get(0));
    assertThat(projectedText).contains("SUMMARY").contains("CURRENT");
    assertThat(window.rawMessages().subList(window.rawSize() - 2, window.rawSize()))
        .containsExactly(
            new LlmMessage(ContextWindow.ROLE_COMPACT, List.of(new ContentPart.Text("SUMMARY"))),
            LlmMessage.user("CURRENT"));
    assertThat(window.unsavedTail()).hasSize(2);
  }

  @Test
  void manualRequestCompactsBelowThresholdAndReportsManualReason() {
    CompactionPolicy policy = new CompactionPolicy(100_000, 1000, 100, 0, PROMPT);
    ContextWindow window = new ContextWindow("manual", List.of(LlmMessage.user("tiny history")));
    ScriptedSummarizer summarizer = ScriptedSummarizer.returning("manual summary");
    DefaultContextCompactor compactor = new DefaultContextCompactor(summarizer, policy);

    compactor.requestCompaction();
    CompactionOutcome outcome =
        compactor.compactIfNeeded(
            window,
            List.of(LlmMessage.user("manual question")),
            List.of(),
            new RecordingComposer());

    assertThat(outcome.beforeTokens()).isLessThan(outcome.triggerTokens());
    assertThat(outcome.compacted()).isTrue();
    assertThat(outcome.reason()).isEqualTo("manual");
    assertThat(outcome.turnInput()).isEmpty();
    assertThat(summarizer.calls()).isEqualTo(1);
  }

  @Test
  void toolLoopWithoutANewUserUsesTheFixedContinuationPromptInOneSyntheticUser() {
    CompactionPolicy policy = new CompactionPolicy(100_000, 1000, 100, 0, PROMPT);
    ContextWindow window = new ContextWindow("continuation", List.of(LlmMessage.user("history")));
    DefaultContextCompactor compactor =
        new DefaultContextCompactor(ScriptedSummarizer.returning("loop summary"), policy);
    compactor.requestCompaction();

    CompactionOutcome outcome =
        compactor.compactIfNeeded(window, List.of(), List.of(), new RecordingComposer());

    assertThat(outcome.compacted()).isTrue();
    assertThat(window.messages()).hasSize(1);
    String text = textOf(window.messages().get(0));
    assertThat(text).contains("loop summary").contains(CompactionPrompts.CONTINUATION_PROMPT);
    assertThat(window.rawMessages().get(window.rawSize() - 1))
        .isEqualTo(LlmMessage.user(CompactionPrompts.CONTINUATION_PROMPT));
  }

  @Test
  void summarizerLlmExceptionBecomesCompactionExceptionWithoutChangingRawWindow() {
    assertStrictFailureWithoutRawChange(
        ScriptedSummarizer.failing(new LlmException("summary down")),
        new CompactionPolicy(100_000, 1000, 100, 0, PROMPT),
        "压缩摘要调用失败");
  }

  @Test
  void blankSummaryBecomesCompactionExceptionWithoutChangingRawWindow() {
    assertStrictFailureWithoutRawChange(
        ScriptedSummarizer.returning(LlmResponse.text("   ")),
        new CompactionPolicy(100_000, 1000, 100, 0, PROMPT),
        "没有非空正文");
  }

  @Test
  void summaryWithToolCallBecomesCompactionExceptionWithoutChangingRawWindow() {
    LlmResponse toolOnly =
        new LlmResponse(
            LlmMessage.assistant(
                List.of(new ContentPart.ToolCall("call-1", "echo", Map.of("x", 1)))),
            "summary-model",
            1,
            1);
    assertStrictFailureWithoutRawChange(
        ScriptedSummarizer.returning(toolOnly),
        new CompactionPolicy(100_000, 1000, 100, 0, PROMPT),
        "工具调用");
  }

  @Test
  void postCompactionStillAtTriggerLineBecomesCompactionExceptionWithoutChangingRawWindow() {
    assertStrictFailureWithoutRawChange(
        ScriptedSummarizer.returning(LlmResponse.text("长".repeat(200))),
        new CompactionPolicy(100, 10, 10, 0, PROMPT),
        "压缩后估算仍达到触发线");
  }

  @Test
  void summarizeIsOverridableAndTheOverrideIsUsedInsteadOfTheInjectedClient() {
    CompactionPolicy policy = new CompactionPolicy(100_000, 1000, 100, 0, PROMPT);
    ContextWindow window = new ContextWindow("override", List.of(LlmMessage.user("old")));
    LlmClient neverCalled =
        request -> {
          throw new AssertionError("子类覆写 summarize 后不应调用注入的摘要客户端");
        };
    OverridingCompactor compactor = new OverridingCompactor(neverCalled, policy);
    compactor.requestCompaction();

    CompactionOutcome outcome =
        compactor.compactIfNeeded(
            window, List.of(LlmMessage.user("new")), List.of(), new RecordingComposer());

    assertThat(outcome.compacted()).isTrue();
    assertThat(compactor.visibleHistorySeen).containsExactly(LlmMessage.user("old"));
    assertThat(textOf(window.messages().get(0))).contains("OVERRIDE-SUMMARY").contains("new");
  }

  @Test
  void validatePostCompactionIsAnOverridableEnforcementPoint() {
    CompactionPolicy policy = new CompactionPolicy(100_000, 1000, 100, 0, PROMPT);
    ContextWindow window = new ContextWindow("post-override", List.of(LlmMessage.user("old")));
    PostRejectingCompactor compactor =
        new PostRejectingCompactor(ScriptedSummarizer.returning("summary"), policy);
    compactor.requestCompaction();
    List<LlmMessage> before = window.rawMessages();

    assertThatThrownBy(
            () ->
                compactor.compactIfNeeded(
                    window, List.of(LlmMessage.user("new")), List.of(), new RecordingComposer()))
        .isInstanceOf(CompactionException.class)
        .hasMessageContaining("CUSTOM-POST-REJECT");
    assertThat(compactor.postCheckCalled).isTrue();
    assertThat(window.rawMessages()).isEqualTo(before);
  }

  @Test
  void defaultPolicyUsesMinAgentLimitCapsSummaryAt4096AndHeadroomZero() {
    ModelCapabilities caps =
        new ModelCapabilities(true, false, false, false, false, 50_000, 20_000);

    CompactionPolicy agentCapped = DefaultContextCompactor.defaultPolicy(caps, 8_000);
    assertThat(agentCapped.maxContextTokens()).isEqualTo(50_000);
    assertThat(agentCapped.maxOutputTokens()).isEqualTo(8_000);
    assertThat(agentCapped.summaryMaxTokens()).isEqualTo(4_096);
    assertThat(agentCapped.headroomTokens()).isZero();
    assertThat(agentCapped.compactionPrompt()).isNotBlank();

    CompactionPolicy modelCapped = DefaultContextCompactor.defaultPolicy(caps, 0);
    assertThat(modelCapped.maxOutputTokens()).isEqualTo(20_000);
    assertThat(modelCapped.summaryMaxTokens()).isEqualTo(4_096);

    ModelCapabilities small =
        new ModelCapabilities(true, false, false, false, false, 50_000, 1_024);
    CompactionPolicy smallPolicy = DefaultContextCompactor.defaultPolicy(small, 0);
    assertThat(smallPolicy.maxOutputTokens()).isEqualTo(1_024);
    assertThat(smallPolicy.summaryMaxTokens()).isEqualTo(1_024);
  }

  @Test
  void defaultPolicyNamesTheMissingCapabilityKey() {
    ModelCapabilities missingContext =
        new ModelCapabilities(true, false, false, false, false, 0, 8_192);
    ModelCapabilities missingOutput =
        new ModelCapabilities(true, false, false, false, false, 131_072, 0);

    assertThatThrownBy(() -> DefaultContextCompactor.defaultPolicy(missingContext, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capabilities.maxContext");
    assertThatThrownBy(() -> DefaultContextCompactor.defaultPolicy(missingOutput, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capabilities.maxOutput");
  }

  private static void assertStrictFailureWithoutRawChange(
      ScriptedSummarizer summarizer, CompactionPolicy policy, String expectedMessage) {
    ContextWindow window =
        new ContextWindow("strict", List.of(LlmMessage.user("old"), LlmMessage.user("older")));
    List<LlmMessage> rawBefore = window.rawMessages();
    DefaultContextCompactor compactor = new DefaultContextCompactor(summarizer, policy);
    compactor.requestCompaction();

    CompactionException failure =
        (CompactionException)
            org.assertj.core.api.Assertions.catchThrowableOfType(
                CompactionException.class,
                () ->
                    compactor.compactIfNeeded(
                        window,
                        List.of(LlmMessage.user("current")),
                        List.of(),
                        new RecordingComposer()));

    assertThat(failure).isNotNull();
    assertThat(failure.getMessage()).contains(expectedMessage);
    assertThat(window.rawMessages()).isEqualTo(rawBefore);
    assertThat(window.messages()).isEqualTo(rawBefore);
  }

  private static String textOf(LlmMessage message) {
    return message.content().stream()
        .filter(ContentPart.Text.class::isInstance)
        .map(ContentPart.Text.class::cast)
        .map(ContentPart.Text::text)
        .reduce("", String::concat);
  }

  private static final class OverridingCompactor extends DefaultContextCompactor {

    private List<LlmMessage> visibleHistorySeen;

    private OverridingCompactor(LlmClient summarizer, CompactionPolicy policy) {
      super(summarizer, policy);
    }

    @Override
    protected String summarize(List<LlmMessage> visibleHistory) {
      visibleHistorySeen = List.copyOf(visibleHistory);
      return "OVERRIDE-SUMMARY";
    }
  }

  private static final class PostRejectingCompactor extends DefaultContextCompactor {

    private boolean postCheckCalled;

    private PostRejectingCompactor(LlmClient summarizer, CompactionPolicy policy) {
      super(summarizer, policy);
    }

    @Override
    protected void validatePostCompaction(LlmRequest postRequest) {
      postCheckCalled = true;
      throw new CompactionException("CUSTOM-POST-REJECT");
    }
  }

  private static final class RecordingComposer implements ContextComposer {

    private final List<LlmRequest> requests = new ArrayList<>();

    @Override
    public LlmRequest compose(
        List<LlmMessage> windowMessages, List<LlmMessage> turnInput, List<AgentTool> tools) {
      List<LlmMessage> messages = new ArrayList<>(windowMessages.size() + turnInput.size());
      messages.addAll(windowMessages);
      messages.addAll(turnInput);
      List<ToolDef> toolDefs =
          tools.stream()
              .map(tool -> new ToolDef(tool.name(), tool.description(), tool.jsonSchema()))
              .toList();
      LlmRequest request = new LlmRequest(messages, toolDefs);
      requests.add(request);
      return request;
    }
  }

  private static final class ScriptedSummarizer implements LlmClient {

    private final Deque<Object> script = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();

    static ScriptedSummarizer returning(String text) {
      return returning(LlmResponse.text(text));
    }

    static ScriptedSummarizer returning(LlmResponse response) {
      ScriptedSummarizer summarizer = new ScriptedSummarizer();
      summarizer.script.addLast(response);
      return summarizer;
    }

    static ScriptedSummarizer failing(LlmException failure) {
      ScriptedSummarizer summarizer = new ScriptedSummarizer();
      summarizer.script.addLast(failure);
      return summarizer;
    }

    int calls() {
      return requests.size();
    }

    List<LlmRequest> requests() {
      return requests;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      requests.add(request);
      Object next = script.pollFirst();
      if (next == null) {
        throw new AssertionError("摘要器脚本耗空");
      }
      if (next instanceof LlmException failure) {
        throw failure;
      }
      return (LlmResponse) next;
    }
  }

  private record TestTool(String name, String description, Map<String, Object> jsonSchema)
      implements AgentTool {

    @Override
    public ToolResult execute(ToolContext context) {
      throw new AssertionError("压缩器的 summary 请求不应带可执行工具");
    }
  }
}
