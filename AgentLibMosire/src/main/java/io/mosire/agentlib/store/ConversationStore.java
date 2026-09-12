package io.mosire.agentlib.store;

import io.mosire.agentlib.llm.LlmMessage;
import java.util.List;
import java.util.Optional;

/**
 * 会话持久化（计划 §六 数据模型：{@code conversations}/{@code messages} 两表；Brain 的 {@code AgentPipeline} 是唯一消费方）。
 *
 * <p><b>prompt-cache 不变量（本接口的核心契约）</b>：
 *
 * <ul>
 *   <li>{@link #append} <b>只追加</b>——已落库的消息既不改写也不删除；一条消息落库即为永久（行标识与插入时序都不再变）。
 *       缓存前缀稳定靠的就是"前缀永远按同样的顺序、同样的内容回灌"。
 *   <li>唯一能让历史"变短"的路径是<b>显式</b>调用 {@link #compact}；{@link #append} 内部绝不做任何压缩/替换。 替换只发生在 compact
 *       记录之后——审计上永远查得到原始行。
 * </ul>
 *
 * <p><b>{@link #load} 的形状</b>：无压缩记录时 = 全部原始消息（按追加序）；有压缩记录时 = 摘要消息 + 压缩点之后的消息。 压缩点之前的原始消息不删除，只是不再被
 * {@code load} 返回。
 *
 * <p><b>并发</b>：实现只需保证"每次调用自身原子"（与 T12 的 {@code MemoryStore} 同口径）；本接口<b>不是</b>并发方案， 上层（{@code
 * AgentPipeline}）"同一实例任意时刻只跑一个回合"的调用约定不因持久化而放宽。
 *
 * <p>{@code conversationId} 是调用方显式给定的会话标识（不隐式取全局状态）：同一 Store 实例上的不同 id 之间完全隔离。
 */
public interface ConversationStore {

  /**
   * 追加一条消息到会话尾部（会话不存在则新建）。
   *
   * @param conversationId 会话标识（非 null）
   * @param message 待追加的消息（非 null）
   */
  void append(String conversationId, LlmMessage message);

  /**
   * 读取会话的可见历史：摘要（若曾显式压缩）+ 压缩点之后的消息，按追加序。
   *
   * <p>返回不可变列表；未知会话返回空列表。
   */
  List<LlmMessage> load(String conversationId);

  /**
   * 显式压缩：把当前会话尾部记为压缩点，{@code summary} 取代压缩点之前的消息出现在 {@link #load} 里。
   *
   * <p>压缩点之前的原始消息<b>不删除</b>（留档可审计）；再次调用会替换摘要并前移压缩点（最近一次压缩生效）。 调用方（T17 的 {@code
   * Compactor}）负责把上一版摘要并入新版摘要——本接口不做摘要合并。
   *
   * @param conversationId 会话标识（非 null）
   * @param summary 摘要正文（非 null；空串表示压缩点之前不再有摘要消息）
   */
  void compact(String conversationId, String summary);

  /**
   * 当前活动会话 id（<b>会话指针</b>）：上次被 {@link #setCurrentConversationId} 记下的那个 id；从未记过 → 空。
   *
   * <p><b>为什么需要它</b>：{@link #load} 按 id 寻址，而"重启后该接着哪条会话聊"这件事本身不在任何一条会话里。没有指针时，装配层只能用 <b>固定</b>的初始
   * id 寻址 ⇒ 重置（D26：换新会话 id）后的重启会退回初始会话，把<b>用户已经清掉的那段历史重新灌回工作集</b> （"重置被重启撤销"）。指针是"重置跨进程有效"的唯一载体。
   *
   * <p><b>指针只是续聊的起点，不是内容</b>：它不参与 {@link #load} 的形状，也不因 {@link #append} 而变——只有显式的 {@link
   * #setCurrentConversationId} 会改它。
   *
   * @return 当前活动会话 id；本实现不持久化会话指针时恒为空（如不落库的实现）
   */
  default Optional<String> currentConversationId() {
    return Optional.empty();
  }

  /**
   * 记下当前活动会话 id（下次 {@link #currentConversationId} 返回它）。幂等：同 id 重复记写无额外语义。
   *
   * <p>{@link #append} 之前调用即可——它<b>不要求</b>该会话已有内容（重置出来的新会话在首条消息落库前就是"当前会话"）。
   *
   * @param conversationId 会话标识（非 null）
   */
  default void setCurrentConversationId(String conversationId) {
    // 缺省空实现：不持久化的 store 没有"当前会话"这一说（无状态可续）
  }
}
