package io.mosire.agentlib.context;

import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.tool.AgentTool;
import java.util.List;

/**
 * 编排契约：把[窗口内容 + 本轮输入 + 工具目录]拼成"这一次要发出去的请求"。
 *
 * <p><b>形状是硬契约：{@code [头部] + windowMessages(原样) + turnInput(原样)}。</b>头部放什么由宿主决定（system 提示、技能/记忆预注入、
 * 分层的其余部分都算头部，随宿主），但<b>窗口内容必须逐条、按序、一个字节不改地回灌</b>，本轮输入紧随其后。为什么原样是硬要求：请求前缀的稳定性是 prompt-cache
 * 的前提——服务端按前缀命中缓存，装配器只要重排、筛选或改写回灌的那一段，每回合的缓存就全部失效（费用与首字延迟一起涨），而且"模型看到的
 * 历史"与"窗口里的历史"不再同源，事后无从复盘。要动窗口内容，请走 {@link ContextWindow} 的改写口，别在这一层动手。
 *
 * <p><b>为什么本轮输入是"列表"而不是单条 user 消息</b>（这条是接口可复用的试金石）：本轮输入在不同宿主里形态本来就不同——
 *
 * <ul>
 *   <li><b>1 条</b>：典型形态"本轮 user 收尾"（窗口里的历史 + 一条新的 user 消息 = 本次请求）；
 *   <li><b>0 条</b>：宿主已经把本轮输入 append 进窗口、窗口即请求（例如"每轮现插一条提醒，再照常发"的那种用法）；
 *   <li><b>n 条</b>：本轮有多段输入（提醒 + user，或多模态）。
 * </ul>
 *
 * <p>若签名收成单条 {@code LlmMessage}，0 条那条路就得让宿主伪造一条空壳消息，n 条那条路根本表达不了——接口会把宿主的形态钉死，也就谈不上复用。
 *
 * <p><b>工具面</b>：{@link LlmRoundTrip} 把本方法返回的 {@link LlmRequest} <b>原样</b>交给 {@link
 * io.mosire.agentlib.llm.LlmClient}， 不会按 {@code tools} 参数重新推导——所以"这次不发工具"就返回工具目录为空的请求。参数给的是 {@link
 * AgentTool}（可执行面：带执行器与权限声明）， 而 {@code LlmRequest.tools} 要的是 {@link
 * io.mosire.agentlib.llm.ToolDef}（线上面：name/description/jsonSchema），两者之间的转换 （取哪些工具、怎么描述）是宿主的活，本层不做。
 *
 * <p><b>契约与守卫</b>：本接口只<b>声明</b>契约，不做校验，也没有默认实现。位置契约（头部在前、窗口内容原样、本轮输入紧随）被违反时怎么响亮失败，是宿主实现的事
 * （既有管线的做法是抛 {@code IllegalStateException}）；接口不提供"帮忙检查一下"的默认方法——那会把宿主的位置契约悄悄固化进库里，而不同宿主的头部形状本就不同。
 *
 * <p><b>无副作用</b>：不得改窗口内容（传进来的已是不可变快照，改不动它）、不得改 {@code turnInput}；每次往返现组请求，<b>不缓存、不留第二份真相</b>——
 * 请求永远从窗口派生（见 {@link LlmRoundTrip}）。
 *
 * <p><b>线程安全</b>：与 {@link ContextWindow} 同口径——调用方串行；实现不必自行加锁。
 */
@FunctionalInterface
public interface ContextComposer {

  /**
   * 组一次请求。
   *
   * @param windowMessages 窗口当前内容的不可变快照（必须原样回灌，不改写、不重排、不筛选）
   * @param turnInput 本轮输入（相对窗口新增的部分；可为空列表 = 本轮没有额外输入）
   * @param tools 本轮可用的工具（可为空列表；实现自行转成 {@link io.mosire.agentlib.llm.ToolDef}）
   * @return 本次要发出的请求（其 {@code messages} 就是要上线的序列；非 null）
   */
  LlmRequest compose(
      List<LlmMessage> windowMessages, List<LlmMessage> turnInput, List<AgentTool> tools);
}
