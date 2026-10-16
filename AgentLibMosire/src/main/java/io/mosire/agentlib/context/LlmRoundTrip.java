package io.mosire.agentlib.context;

import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.tool.AgentTool;
import java.util.List;
import java.util.Objects;

/**
 * 一轮 LLM 往返：拼装 → 发信 → 回灌，四步一条线。
 *
 * <p><b>★ 写回是往返的一部分，不是调用方的事后动作</b>：本方法返回时，窗口<b>一定</b>已经包含本次的本轮输入与这次答复。这条保证是"请求永远从窗口派生"
 * 的另一半——若写回交给调用方，任何一条忘记写回的出口（异常、提前 return、配额终止）都会让下一轮请求缺掉刚刚发生过的一问一答，表现为 Agent 失忆，
 * 而失忆是那种"看着还能跑"的故障。设计上明知会带来一处行为变化（既有管线的 QUOTA 出口不保留答复，走本方法后答复留在窗口并落库），也要保留这条不变量： token
 * 已经花了、答复已经产生了，把它从历史里丢掉并不对称。
 *
 * <p><b>不含什么（全是宿主策略，本类不做）</b>：工具执行（谁去跑 tool call）、终止判断（什么时候收口、哪种 StopReason）、重试与降级、上下文压缩、
 * 事件记账、落库。它们各自需要窗口、工具栈、事件存储、预算等宿主知识；本类只认识"窗口 + 装配器 + 模型客户端"三件东西，因此能同时服务不同宿主。
 *
 * <p><b>失败路径</b>：{@link LlmClient#chat} 抛出时，窗口<b>一个字节都没动</b>——本轮输入也没有写回。若宿主希望在"调用失败"时仍然把本轮输入留进历史
 * （既有管线的 LLM_ERROR 出口就是如此：它把本轮 user 一并落库，好让下一轮知道用户说过什么），必须在自己的 catch 里显式 {@code
 * window.appendAll(turnInput)} 再自行落库。本类不做这件事：它不认识
 * store，也不知道宿主的失败策略（重试要不要保留、失败的输入算不算历史），替宿主决定只会把一种策略偷偷固化进库里。
 *
 * <p><b>不重复写回</b>：{@code turnInput} 的语义是"这次请求相对窗口新增的内容"。宿主若已经把本轮输入 append 进窗口（0 条形态的用法），传空列表即可；
 * 两者都做会写回两遍——本类不去重，因为去重只能靠比对内容或猜 id，那是猜（同一条 user 文本本来就可以合法出现两次）。
 *
 * <p><b>线程安全</b>：不做同步、不承诺并发。一次 {@link #call} 期间窗口不得被别处改动——否则写回的尾部会插在别人的改动之后；调用方串行使用 （与 {@link
 * ContextWindow} 同口径）。只 append 不重排，因此也不破坏请求前缀的 prompt-cache 前提。
 */
public final class LlmRoundTrip {

  private LlmRoundTrip() {}

  /**
   * 跑一轮往返。
   *
   * <p>四步，顺序是契约：
   *
   * <ol>
   *   <li>{@code composer.compose(window.messages(), turnInput, tools)} 组请求（窗口内容现取，头部由装配器决定）；
   *   <li>{@code llm.chat(request)} 发信（失败即抛，不 catch、不重试）；
   *   <li>写回：先 {@code window.appendAll(turnInput)}、再 {@code
   *       window.append(response.assistantMessage())}——本轮输入在前，答复在后， 下一轮看到的历史顺序与模型刚看到的请求一致；
   *   <li>返回 {@link LlmResponse}（记账、工具执行、终止判断都留给宿主）。
   * </ol>
   *
   * <p>两个参数先拷成不可变快照：{@code compose} 与 {@code appendAll} 之间隔着一次（可能很慢的）网络调用，期间调用方若改了那两个 List，就会出现
   * "发出去的是 A、写回的是 B"的静默错位。快照把这条缝焊死，顺便让"元素为 null"在发信之前就响亮失败。
   *
   * @param llm 模型客户端（非 null）
   * @param window 本会话的窗口（非 null；返回时已包含本轮输入与这次答复）
   * @param composer 编排器（非 null；其返回的请求原样发给客户端）
   * @param turnInput 本轮输入（非 null，可为空列表；见类注"不重复写回"）
   * @param tools 本轮可用工具（非 null，可为空列表）
   * @return 这次调用的响应（非 null）
   * @throws io.mosire.agentlib.llm.LlmException 调用失败（{@link LlmClient#chat} 的契约）；抛出时窗口未被改动
   */
  public static LlmResponse call(
      LlmClient llm,
      ContextWindow window,
      ContextComposer composer,
      List<LlmMessage> turnInput,
      List<AgentTool> tools) {
    Objects.requireNonNull(llm, "llm");
    Objects.requireNonNull(window, "window");
    Objects.requireNonNull(composer, "composer");
    List<LlmMessage> input = List.copyOf(Objects.requireNonNull(turnInput, "turnInput"));
    List<AgentTool> toolList = List.copyOf(Objects.requireNonNull(tools, "tools"));

    LlmRequest request =
        Objects.requireNonNull(
            composer.compose(window.messages(), input, toolList), "composer.compose 返回的请求");
    LlmResponse response = llm.chat(request);
    window.appendAll(input);
    window.append(response.assistantMessage());
    return response;
  }
}
