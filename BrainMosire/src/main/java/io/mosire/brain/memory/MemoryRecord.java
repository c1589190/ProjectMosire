package io.mosire.brain.memory;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条已落库的记忆（计划 §4.3 的读取形态）。
 *
 * <p>不含写入者身份等级：权限过滤已在 SQL 层完成（越权行根本不出现在结果里），把等级再回传给上层只会诱使 调用方在 Java 侧重复判断一次同一件事。
 *
 * <p>{@code scope} 的词表同 {@code AgentSpec.memoryScope}（user/project/local）：本波次写入并原样回显， 由后续波次按调用者自己的
 * memoryScope 收窄可见范围。
 */
public record MemoryRecord(long id, String topic, String text, String scope, Instant createdAt) {

  public MemoryRecord {
    Objects.requireNonNull(topic, "topic");
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
