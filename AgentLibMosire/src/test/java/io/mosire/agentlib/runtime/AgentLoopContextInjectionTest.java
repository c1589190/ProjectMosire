package io.mosire.agentlib.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.context.ContextComposer;
import io.mosire.agentlib.context.ContextInjection;
import io.mosire.agentlib.context.ContextInjectionKinds;
import io.mosire.agentlib.context.ContextWindow;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.llm.ToolDef;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 上下文注入与动态工具面的骨架级判据：
 *
 * <ul>
 *   <li>注入在压缩后、LLM 前追加；成功才清队列并回调，失败重试；
 *   <li>工具面 revision 变化 ⇒ 下一轮工具定义更新 + 标准注入；策略 none 可关闭；
 *   <li>渲染器完全可替换（role/信封由宿主决定）。
 * </ul>
 */
class AgentLoopContextInjectionTest {

  @Test
  void pendingInjectionIsRenderedOnceAndCommittedOnlyAfterSuccessfulRound() {
    ContextWindow window = new ContextWindow("inject-ok");
    RecordingComposer composer = new RecordingComposer();
    ScriptedLlm llm = new ScriptedLlm();
    llm.enqueue(LlmResponse.text("收到"));
    RecordingListener listener = new RecordingListener();
    window.inject(
        ContextInjection.of("i-1", ContextInjectionKinds.REMINDER, "提醒", "请重新评估当前工具", Map.of()));

    TurnResult result =
        AgentLoop.builder()
            .window(window)
            .composer(composer)
            .llm(llm)
            .dispatcher((call, state) -> {})
            .listener(listener)
            .build()
            .runTurn(List.of(LlmMessage.user("继续")), List.of());

    assertThat(result.stopReason()).isEqualTo(StopReason.FINISHED);
    assertThat(llm.requests).hasSize(1);
    assertThat(userTexts(llm.requests.get(0)))
        .anySatisfy(
            text ->
                assertThat(text)
                    .contains("<system-reminder kind=\"reminder\" id=\"i-1\">")
                    .contains("请重新评估当前工具"));
    assertThat(window.pendingInjections()).isEmpty();
    assertThat(listener.injected).hasSize(1);
    assertThat(listener.injected.get(0)).extracting(ContextInjection::id).containsExactly("i-1");
  }

  @Test
  void failedRoundKeepsInjectionPendingAndRetriesNextRound() {
    ContextWindow window = new ContextWindow("inject-retry");
    RecordingComposer composer = new RecordingComposer();
    ScriptedLlm llm = new ScriptedLlm();
    llm.failWith(new LlmException("第一次失败"));
    llm.enqueue(LlmResponse.text("第二次成功"));
    RecordingListener listener = new RecordingListener();
    window.inject(
        ContextInjection.of(
            "i-retry", ContextInjectionKinds.SYSTEM_NOTICE, "通知", "网络抖动，稍后重试", Map.of()));

    TurnResult first =
        AgentLoop.builder()
            .window(window)
            .composer(composer)
            .llm(llm)
            .dispatcher((call, state) -> {})
            .listener(listener)
            .build()
            .runTurn(List.of(LlmMessage.user("继续")), List.of());

    assertThat(first.stopReason()).isEqualTo(StopReason.LLM_ERROR);
    assertThat(window.pendingInjections()).hasSize(1);
    assertThat(listener.injected).isEmpty();

    TurnResult second =
        AgentLoop.builder()
            .window(window)
            .composer(composer)
            .llm(llm)
            .dispatcher((call, state) -> {})
            .listener(listener)
            .build()
            .runTurn(List.of(LlmMessage.user("继续")), List.of());

    assertThat(second.stopReason()).isEqualTo(StopReason.FINISHED);
    assertThat(window.pendingInjections()).isEmpty();
    assertThat(listener.injected).hasSize(1);
    assertThat(listener.injected.get(0))
        .extracting(ContextInjection::id)
        .containsExactly("i-retry");
  }

  @Test
  void toolSurfaceRevisionChangeUpdatesNextRequestAndInjectsStandardNotice() {
    ContextWindow window = new ContextWindow("surface");
    RecordingComposer composer = new RecordingComposer();
    ScriptedLlm llm = new ScriptedLlm();
    MutableSurface surface = new MutableSurface(0L, List.of(tool("old_tool")));
    RecordingListener listener = new RecordingListener();

    ContentPart.ToolCall call = new ContentPart.ToolCall("call-1", "old_tool", Map.of());
    llm.enqueue(new LlmResponse(LlmMessage.assistant(List.of(call)), "fake", 1, 1));
    llm.enqueue(LlmResponse.text("完成"));

    TurnResult result =
        AgentLoop.builder()
            .window(window)
            .composer(composer)
            .llm(llm)
            .dispatcher(
                (toolCall, state) -> {
                  window.append(
                      LlmMessage.tool(
                          new ContentPart.ToolResult(toolCall.id(), toolCall.name(), "ok", null)));
                  // 工具执行期间工具面变化：下一轮应看到新面 + 一条标准注入
                  surface.update(1L, List.of(tool("new_tool")));
                })
            .listener(listener)
            .build()
            .runTurn(List.of(LlmMessage.user("跑")), surface);

    assertThat(result.stopReason()).isEqualTo(StopReason.FINISHED);
    assertThat(llm.requests).hasSize(2);
    assertThat(toolNames(llm.requests.get(0))).containsExactly("old_tool");
    assertThat(toolNames(llm.requests.get(1))).containsExactly("new_tool");
    assertThat(userTexts(llm.requests.get(1)))
        .anySatisfy(
            text ->
                assertThat(text)
                    .contains("<system-reminder kind=\"tool_surface_changed\"")
                    .contains("工具面已变化")
                    .contains("new_tool"));
    assertThat(listener.injected).hasSize(1);
    assertThat(listener.injected.get(0).get(0).kind())
        .isEqualTo(ContextInjectionKinds.TOOL_SURFACE_CHANGED);
  }

  @Test
  void noticePolicyNoneUpdatesToolsWithoutInjecting() {
    ContextWindow window = new ContextWindow("surface-none");
    RecordingComposer composer = new RecordingComposer();
    ScriptedLlm llm = new ScriptedLlm();
    MutableSurface surface = new MutableSurface(0L, List.of(tool("old_tool")));
    RecordingListener listener = new RecordingListener();

    ContentPart.ToolCall call = new ContentPart.ToolCall("call-1", "old_tool", Map.of());
    llm.enqueue(new LlmResponse(LlmMessage.assistant(List.of(call)), "fake", 1, 1));
    llm.enqueue(LlmResponse.text("完成"));

    AgentLoop.builder()
        .window(window)
        .composer(composer)
        .llm(llm)
        .dispatcher(
            (toolCall, state) -> {
              window.append(
                  LlmMessage.tool(
                      new ContentPart.ToolResult(toolCall.id(), toolCall.name(), "ok", null)));
              surface.update(1L, List.of(tool("new_tool")));
            })
        .toolSurfaceNoticePolicy(ToolSurfaceNoticePolicy.none())
        .listener(listener)
        .build()
        .runTurn(List.of(LlmMessage.user("跑")), surface);

    assertThat(toolNames(llm.requests.get(1))).containsExactly("new_tool");
    assertThat(userTexts(llm.requests.get(1)))
        .noneSatisfy(text -> assertThat(text).contains("<system-reminder"));
    assertThat(listener.injected).isEmpty();
  }

  @Test
  void rendererIsFullyCustomizable() {
    ContextWindow window = new ContextWindow("custom-render");
    RecordingComposer composer = new RecordingComposer();
    ScriptedLlm llm = new ScriptedLlm();
    llm.enqueue(LlmResponse.text("好"));
    window.inject(
        ContextInjection.of(
            "custom-1", ContextInjectionKinds.POLICY_CHANGED, "策略", "命令档位改为完整", Map.of()));

    AgentLoop.builder()
        .window(window)
        .composer(composer)
        .llm(llm)
        .dispatcher((call, state) -> {})
        .injectionRenderer(injection -> LlmMessage.system("[CUSTOM]" + injection.text()))
        .build()
        .runTurn(List.of(LlmMessage.user("继续")), List.of());

    assertThat(llm.requests.get(0).messages())
        .anySatisfy(message -> assertThat(textOf(message)).contains("[CUSTOM]命令档位改为完整"));
  }

  private static List<String> userTexts(LlmRequest request) {
    return request.messages().stream()
        .filter(message -> LlmMessage.ROLE_USER.equals(message.role()))
        .map(AgentLoopContextInjectionTest::textOf)
        .toList();
  }

  private static String textOf(LlmMessage message) {
    StringBuilder text = new StringBuilder();
    for (ContentPart part : message.content()) {
      if (part instanceof ContentPart.Text textPart) {
        text.append(textPart.text());
      }
    }
    return text.toString();
  }

  private static List<String> toolNames(LlmRequest request) {
    return request.tools().stream().map(ToolDef::name).toList();
  }

  private static AgentTool tool(String name) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public ToolResult execute(io.mosire.agentlib.tool.ToolContext context) {
        return ToolResult.ok("ok");
      }
    };
  }

  private static final class RecordingComposer implements ContextComposer {

    @Override
    public LlmRequest compose(
        List<LlmMessage> windowMessages, List<LlmMessage> turnInput, List<AgentTool> tools) {
      List<LlmMessage> messages = new ArrayList<>();
      messages.add(LlmMessage.system("头部"));
      messages.addAll(windowMessages);
      messages.addAll(turnInput);
      return new LlmRequest(
          messages,
          tools.stream()
              .map(tool -> new ToolDef(tool.name(), tool.description(), tool.jsonSchema()))
              .toList());
    }
  }

  private static final class ScriptedLlm implements LlmClient {

    private final Deque<Object> script = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();

    void enqueue(LlmResponse response) {
      script.addLast(response);
    }

    void failWith(LlmException exception) {
      script.addLast(exception);
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      requests.add(request);
      Object next = script.pollFirst();
      if (next == null) {
        throw new LlmException("脚本已耗空（第 " + requests.size() + " 次调用）");
      }
      if (next instanceof LlmException exception) {
        throw exception;
      }
      return (LlmResponse) next;
    }
  }

  private static final class RecordingListener implements TurnListener {

    private final List<List<ContextInjection>> injected = new ArrayList<>();

    @Override
    public void onContextInjected(List<ContextInjection> injections, LoopState state) {
      injected.add(List.copyOf(injections));
    }
  }

  private static final class MutableSurface implements ToolSurface {

    private long revision;
    private List<AgentTool> tools;

    MutableSurface(long revision, List<AgentTool> tools) {
      this.revision = revision;
      this.tools = List.copyOf(tools);
    }

    void update(long revision, List<AgentTool> tools) {
      this.revision = revision;
      this.tools = List.copyOf(tools);
    }

    @Override
    public long revision() {
      return revision;
    }

    @Override
    public List<AgentTool> tools() {
      return tools;
    }
  }
}
