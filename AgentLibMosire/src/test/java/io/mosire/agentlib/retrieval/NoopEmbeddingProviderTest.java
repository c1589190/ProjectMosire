package io.mosire.agentlib.retrieval;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D15 预留 SPI 的无实现桩：任何调用都必须显式失败（{@link UnsupportedOperationException}）。
 *
 * <p>为什么断言"抛异常"而不是"能构造"：桩的唯一职责是把未实现的语义召回挡在门外。若它静默返回空结果或 全零向量，调用方会把"没有 embedding
 * 能力"误读成"语义召回无命中"，从而在检索链路上静默丢失召回——失败必须响。
 */
class NoopEmbeddingProviderTest {

  @Test
  void embedThrowsUnsupportedOperation() {
    EmbeddingProvider provider = new NoopEmbeddingProvider();

    assertThatThrownBy(() -> provider.embed(List.of("用户喜欢用 Kafka")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /** 空输入也必须抛：桩不得对任何输入（包括平凡输入）给出"看似可用"的结果。 */
  @Test
  void embedThrowsEvenForEmptyInput() {
    EmbeddingProvider provider = new NoopEmbeddingProvider();

    assertThatThrownBy(() -> provider.embed(List.of()))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
