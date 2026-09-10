package io.mosire.agentlib.retrieval;

import java.util.List;

/**
 * {@link EmbeddingProvider} 的无实现桩（决策 D15）：任何调用都抛 {@link UnsupportedOperationException}。
 *
 * <p>为什么是"抛"而不是"返回空列表/零向量"：桩的职责是占住 D15 预留的接入点，并让"未实现"这件事实**响**。 静默返回空结果或全零向量，上层会把"没有 embedding
 * 能力"读成"语义召回无命中"——检索链路少召回一批记忆 且毫无迹象；这类静默降级比启动即失败难查得多。
 *
 * <p>接线状态：本波次（P2-4/Task 13）<b>未接线</b>——{@code MemoryRetriever} 默认不注入任何 provider， 检索只走
 * FTS5/LIKE。等到语义召回实现落地时，本类不再被使用（它是"无声明的默认值"的显式替代品）。
 */
public final class NoopEmbeddingProvider implements EmbeddingProvider {

  @Override
  public List<float[]> embed(List<String> texts) {
    throw new UnsupportedOperationException(
        "EmbeddingProvider 尚未实现（D15 预留 SPI）：二期只落 FTS5 检索，语义召回留待后续波次");
  }
}
