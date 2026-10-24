package io.mosire.agentlib.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.compact.CompactionException;
import io.mosire.agentlib.compact.CompactionOutcome;
import io.mosire.agentlib.compact.CompactionPolicy;
import io.mosire.agentlib.compact.ContextCompactor;
import io.mosire.agentlib.compact.DefaultContextCompactor;
import io.mosire.agentlib.context.ContextComposer;
import io.mosire.agentlib.context.ContextWindow;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.tool.AgentTool;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

/** AgentLoop 的可选压缩钩子：成功、压缩失败、以及压缩成功后主 LLM 失败的三条路径。 */
class AgentLoopCompactionTest {

  private static final LlmMessage HEAD = LlmMessage.system("HEAD");

  @Test
  void compactionSuccessSendsProjectedHistoryOnceCallsOnCompactedOnceAndDoesNotRewriteTurnInput() {
    ContextWindow window =
        new ContextWindow(
            "success",
            List.of(
                LlmMessage.user("OLD-USER"),
                LlmMessage.assistant(List.of(new ContentPart.Text("OLD-ANSWER")))));
    RecordingComposer composer = new RecordingComposer();
    ScriptedLlm llm = ScriptedLlm.answering("done");
    RecordingListener listener = new RecordingListener();
    LlmMessage current = LlmMessage.user("CURRENT-USER");
    ProjectingCompactor compactor = new ProjectingCompactor("LOOP-SUMMARY");

    TurnResult result =
        AgentLoop.builder()
            .window(window)
            .composer(composer)
            .llm(llm)
            .dispatcher((call, state) -> {})
            .listener(listener)
            .contextCompactor(compactor)
            .build()
            .runTurn(List.of(current), List.of());

    assertThat(result.stopReason()).isEqualTo(StopReason.FINISHED);
    assertThat(llm.requests).hasSize(1);
    List<LlmMessage> sent = llm.requests.get(0).messages();
    assertThat(sent).hasSize(2); // head + 一条合成 user；不是 head + raw 旧历史 + 当前 user
    assertThat(sent.get(1).role()).isEqualTo(LlmMessage.ROLE_USER);
    String projected = textOf(sent.get(1));
    assertThat(projected).contains("LOOP-SUMMARY").contains("CURRENT-USER");
    assertThat(sent).noneMatch(message -> ContextWindow.ROLE_COMPACT.equals(message.role()));
    assertThat(sent).noneMatch(message -> textOf(message).contains("OLD-USER"));

    assertThat(listener.compacted).hasSize(1);
    assertThat(listener.compacted.get(0).compacted()).isTrue();
    assertThat(listener.compacted.get(0).turnInput()).isEmpty();
    assertThat(listener.errors).isEmpty();
    assertThat(listener.terminalCallbacks()).isEqualTo(1);

    List<LlmMessage> raw = window.rawMessages();
    assertThat(raw)
        .containsExactly(
            LlmMessage.user("OLD-USER"),
            LlmMessage.assistant(List.of(new ContentPart.Text("OLD-ANSWER"))),
            new LlmMessage(
                ContextWindow.ROLE_COMPACT, List.of(new ContentPart.Text("LOOP-SUMMARY"))),
            current,
            LlmResponse.text("done").assistantMessage());
    assertThat(raw.stream().filter(message -> message.equals(current)).count()).isEqualTo(1);
  }

  @Test
  void realDefaultCompactorReturnsEmptyTurnInputAndPreventsADuplicateUserInTheLoop() {
    ContextWindow window = new ContextWindow("real-default", List.of(LlmMessage.user("OLD-USER")));
    RecordingComposer composer = new RecordingComposer();
    ScriptedLlm mainLlm = ScriptedLlm.answering("main answer");
    ScriptedLlm summarizer = ScriptedLlm.answering("REAL-DEFAULT-SUMMARY");
    DefaultContextCompactor compactor =
        new DefaultContextCompactor(summarizer, new CompactionPolicy(100_000, 1000, 100, 0, "P"));
    compactor.requestCompaction();
    RecordingListener listener = new RecordingListener();
    LlmMessage current = LlmMessage.user("CURRENT-USER");

    TurnResult result =
        AgentLoop.builder()
            .window(window)
            .composer(composer)
            .llm(mainLlm)
            .dispatcher((call, state) -> {})
            .listener(listener)
            .contextCompactor(compactor)
            .build()
            .runTurn(List.of(current), List.of());

    assertThat(result.stopReason()).isEqualTo(StopReason.FINISHED);
    assertThat(mainLlm.requests).hasSize(1);
    assertThat(mainLlm.requests.get(0).messages())
        .filteredOn(message -> LlmMessage.ROLE_USER.equals(message.role()))
        .hasSize(1);
    assertThat(textOf(mainLlm.requests.get(0).messages().get(1)))
        .contains("REAL-DEFAULT-SUMMARY")
        .contains("CURRENT-USER");
    assertThat(window.rawMessages().stream().filter(current::equals).count()).isEqualTo(1);
    assertThat(listener.compacted).hasSize(1);
    assertThat(listener.compacted.get(0).turnInput()).isEmpty();
  }

  @Test
  void compactionExceptionGoesToOnLlmErrorWithoutCallingTheMainLlmAndPreservesOriginalRoundInput() {
    ContextWindow window = new ContextWindow("failure", List.of(LlmMessage.user("OLD-USER")));
    List<LlmMessage> rawBefore = window.rawMessages();
    RecordingComposer composer = new RecordingComposer();
    ScriptedLlm llm = ScriptedLlm.answering("must-not-be-sent");
    RecordingListener listener = new RecordingListener();
    LlmMessage current = LlmMessage.user("CURRENT-USER");
    ContextCompactor failing =
        (w, turnInput, tools, c) -> {
          throw new CompactionException("strict compaction failure");
        };

    TurnResult result =
        AgentLoop.builder()
            .window(window)
            .composer(composer)
            .llm(llm)
            .dispatcher((call, state) -> {})
            .listener(listener)
            .contextCompactor(failing)
            .build()
            .runTurn(List.of(current), List.of());

    assertThat(result.stopReason()).isEqualTo(StopReason.LLM_ERROR);
    assertThat(result.turns()).isZero();
    assertThat(llm.requests).isEmpty();
    assertThat(listener.compacted).isEmpty();
    assertThat(listener.errors).hasSize(1);
    assertThat(listener.errors.get(0)).isInstanceOf(CompactionException.class);
    assertThat(listener.errorInputs).containsExactly(List.of(current));
    assertThat(listener.terminalCallbacks()).isEqualTo(1);
    assertThat(window.rawMessages()).isEqualTo(rawBefore);
  }

  @Test
  void compactionSuccessThenMainLlmFailureKeepsTheCompactorWrittenUserExactlyOnce() {
    ContextWindow window = new ContextWindow("main-failure", List.of(LlmMessage.user("OLD-USER")));
    RecordingComposer composer = new RecordingComposer();
    ScriptedLlm llm = new ScriptedLlm();
    llm.failWith(new LlmException("main llm down"));
    RecordingListener listener = new RecordingListener();
    LlmMessage current = LlmMessage.user("CURRENT-USER");
    ProjectingCompactor compactor = new ProjectingCompactor("FAILED-MAIN-SUMMARY");

    TurnResult result =
        AgentLoop.builder()
            .window(window)
            .composer(composer)
            .llm(llm)
            .dispatcher((call, state) -> {})
            .listener(listener)
            .contextCompactor(compactor)
            .build()
            .runTurn(List.of(current), List.of());

    assertThat(result.stopReason()).isEqualTo(StopReason.LLM_ERROR);
    assertThat(result.turns()).isZero();
    assertThat(llm.requests).hasSize(1);
    assertThat(listener.compacted).hasSize(1);
    assertThat(listener.errors).hasSize(1);
    assertThat(listener.errorInputs).containsExactly(List.of());
    assertThat(window.rawMessages().stream().filter(current::equals).count()).isEqualTo(1);
    assertThat(window.messages()).hasSize(1);
    assertThat(textOf(window.messages().get(0)))
        .contains("FAILED-MAIN-SUMMARY")
        .contains("CURRENT-USER");
  }

  private static String textOf(LlmMessage message) {
    return message.content().stream()
        .filter(ContentPart.Text.class::isInstance)
        .map(ContentPart.Text.class::cast)
        .map(ContentPart.Text::text)
        .reduce("", String::concat);
  }

  private static final class ProjectingCompactor implements ContextCompactor {

    private final String summary;

    private ProjectingCompactor(String summary) {
      this.summary = summary;
    }

    @Override
    public CompactionOutcome compactIfNeeded(
        ContextWindow window,
        List<LlmMessage> turnInput,
        List<AgentTool> tools,
        ContextComposer composer) {
      int dropped = window.size();
      window.compact(summary);
      window.appendAll(turnInput);
      return new CompactionOutcome(List.of(), true, "threshold", 0, 0, 0, dropped, 0, null, 0L);
    }
  }

  private static final class RecordingComposer implements ContextComposer {

    private final List<LlmRequest> requests = new ArrayList<>();

    @Override
    public LlmRequest compose(
        List<LlmMessage> windowMessages, List<LlmMessage> turnInput, List<AgentTool> tools) {
      List<LlmMessage> messages = new ArrayList<>(1 + windowMessages.size() + turnInput.size());
      messages.add(HEAD);
      messages.addAll(windowMessages);
      messages.addAll(turnInput);
      LlmRequest request = new LlmRequest(messages, List.of());
      requests.add(request);
      return request;
    }
  }

  private static final class ScriptedLlm implements LlmClient {

    private final Deque<Object> script = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();

    static ScriptedLlm answering(String text) {
      ScriptedLlm llm = new ScriptedLlm();
      llm.script.addLast(LlmResponse.text(text));
      return llm;
    }

    void failWith(LlmException failure) {
      script.addLast(failure);
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      requests.add(request);
      Object next = script.pollFirst();
      if (next == null) {
        throw new AssertionError("主 LLM 脚本耗空");
      }
      if (next instanceof LlmException failure) {
        throw failure;
      }
      return (LlmResponse) next;
    }
  }

  private static final class RecordingListener implements TurnListener {

    private final List<CompactionOutcome> compacted = new ArrayList<>();
    private final List<LlmException> errors = new ArrayList<>();
    private final List<List<LlmMessage>> errorInputs = new ArrayList<>();
    private int finished;

    @Override
    public void onCompacted(CompactionOutcome outcome, LoopState state) {
      compacted.add(outcome);
    }

    @Override
    public void onFinished(String finalText, LoopState state) {
      finished++;
    }

    @Override
    public void onLlmError(LlmException error, List<LlmMessage> roundInput, LoopState state) {
      errors.add(error);
      errorInputs.add(List.copyOf(roundInput));
    }

    int terminalCallbacks() {
      return finished + errors.size();
    }
  }
}
