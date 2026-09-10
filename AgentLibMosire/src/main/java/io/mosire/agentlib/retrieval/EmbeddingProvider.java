package io.mosire.agentlib.retrieval;

import java.util.List;

/**
 * 文本向量化扩展点——决策 D15 为检索流水线<b>预留</b>的语义召回 SPI。
 *
 * <p>为什么二期只立接口不落实现：D15 定下"FTS5 先行"——SQLite FTS5（trigram tokenizer）已能扛住 关键词与 CJK
 * 子串召回，语义召回是长期解而非当前瓶颈。本波次交付接口 + 无实现桩，把"语义召回" 收敛成一个窄接口：将来无论换本地模型、远端 API 还是加缓存层，都只替换实现，存储与检索调用方不动。
 *
 * <p>为什么落在 agentlib 而不是 Brain：本模块不得出现 Brain 类型（依赖单向 Main → Brain → AgentLib）。消费方 {@code
 * io.mosire.brain.memory.MemoryRetriever} 依赖本接口，方向是 Brain → AgentLib。
 *
 * <p>实现约定：<b>批量而非单条</b>——检索的形状是"一次查询 × N 条候选"，逐条调用会把网络往返放大成 N 倍；入参与返回值等长同序（见 {@link
 * #embed}）。实现不得缓存并返回内部数组引用，否则调用方一改就 污染后续调用。
 */
@FunctionalInterface
public interface EmbeddingProvider {

  /**
   * 把一批文本编码成向量：第 i 个向量对应第 i 条文本。
   *
   * <p>契约（返回值即调用方所有，实现每次调用新建）：
   *
   * <ul>
   *   <li><b>等长同序</b>：返回列表与 {@code texts} 长度相同、次序一致——调用方按位置对齐原文，不做内容反查；
   *   <li><b>维度恒定</b>：同一实现内所有向量维度相同，否则两两相似度不可比；
   *   <li><b>空入参返回空列表</b>，不得返回 null；
   *   <li><b>失败不静默</b>：无法编码时抛异常（无实现时即 {@link UnsupportedOperationException}），
   *       由调用方决定降级策略——静默返回空/零向量会把"没有语义召回"伪装成"语义召回无命中"。
   * </ul>
   *
   * @param texts 待编码文本；不得为 null，元素亦不得为 null
   * @return 与 {@code texts} 等长同序的向量列表
   */
  List<float[]> embed(List<String> texts);
}
