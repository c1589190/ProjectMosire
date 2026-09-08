package io.mosire.main.gateway.a2a;

import io.mosire.brain.runtime.AgentRuntime;
import io.mosire.brain.runtime.StopReason;
import io.mosire.brain.runtime.TurnResult;
import io.mosire.main.gateway.a2a.A2aTaskService.A2aMessageRunner;
import io.mosire.main.gateway.a2a.A2aTaskService.TaskEditor;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;

/**
 * A2A → Brain 的 runner 桥（W2b 核心）：把 A2A 任务消息的文本交给主 Agent 执行编排，并把执行结果推进为任务快照。
 *
 * <p>每任务一回合：{@link AgentRuntime#chat} 不接受 history 入参（R3 修复后 history 在管线内部经参数回灌、按 {@code
 * AgentRuntime} 实例累积）——桥层面把每次 A2A 消息都编排为一次 {@code chat}；同一 Agent 实例的连续对话经
 * <b>新任务</b>延续：后续任务沿用实例此前全部回合的会话上下文（串行执行约定下提交序 = 对话序，见 Brain Javadoc）。
 *
 * <p><b>同任务在途续接的限制（App 装配的串行执行器下）</b>：消息带 {@code taskId} 时服务先接受为 WORKING 并排队 续接 runner，但续接 runner
 * 必然排在第一回合的 runner 之后；第一回合结束时任务已成终态（COMPLETED/FAILED）， 续接 runner 的 WORKING
 * 推进被状态机拒绝（终态→不同），续接消息不会产生第二回合——"每任务一回合"即此语义。 多轮对话请走"新任务"路径（本类不感知任务数，上下文由 runtime 实例承载）。
 *
 * <p>快照推进：{@code WORKING}（入场）→（chat 成功）→ 追加产物 {@code final-1/final}（finalText）→ {@code
 * COMPLETED}；chat 返回非 {@link StopReason#FINISHED}（硬顶/LLM 错误等）→ 抛 {@link A2AError}，由 {@link
 * A2aTaskService} 合成 {@code FAILED}（错误语义保真到任务元数据）。消息不含文本 part → 参数错误（同样合成 FAILED）。
 *
 * <p>边界（D2）：本类只做"消息文本 → chat → 快照"的编排，不含对话/Agent 行为逻辑。
 */
public final class A2aAgentRunner implements A2aMessageRunner {

  /** 桥接触发的 Agent 内部错误码（A2A 通用内部错误范本；客户侧只能经任务元数据观察到错误信息）。 */
  private static final int AGENT_ERROR_CODE = -32000;

  private final AgentRuntime runtime;

  public A2aAgentRunner(AgentRuntime runtime) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
  }

  @Override
  public void run(String taskId, Message message, TaskEditor editor) throws A2AError {
    String userText = userText(message);
    editor.transition(TaskState.TASK_STATE_WORKING);
    TurnResult result = runtime.chat(userText);
    if (result.stopReason() != StopReason.FINISHED) {
      throw new A2AError(
          AGENT_ERROR_CODE,
          "Agent 回合未完成: " + result.stopReason(),
          Map.of("taskId", taskId, "stopReason", result.stopReason().name()));
    }
    editor.appendArtifact(
        Artifact.builder()
            .artifactId("final-1")
            .name("final")
            .parts(List.of(new TextPart(result.text())))
            .build());
    editor.transition(TaskState.TASK_STATE_COMPLETED);
  }

  /** 消息文本：拼接全部 TextPart（多文本段按行连接）；无文本段 → 参数错误。 */
  private static String userText(Message message) throws InvalidParamsError {
    StringBuilder text = new StringBuilder();
    for (Object part : message.parts()) {
      if (part instanceof TextPart textPart) {
        if (!text.isEmpty()) {
          text.append('\n');
        }
        text.append(textPart.text());
      }
    }
    if (text.isEmpty()) {
      throw new InvalidParamsError("消息不含文本内容（TextPart），无法交给 Agent");
    }
    return text.toString();
  }
}
