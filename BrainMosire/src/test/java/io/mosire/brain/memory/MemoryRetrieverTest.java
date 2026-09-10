package io.mosire.brain.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.retrieval.EmbeddingProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 记忆存取 + 检索流水线（开发计划.md §4.3）：CJK trigram 命中、短词 LIKE 回退、按调用者身份过滤。
 *
 * <p>每例自建临时库：SQLite 的 FTS5 虚拟表与 WAL 状态是进程级文件资源，隔离文件比清理表更可靠。
 */
class MemoryRetrieverTest {

  @TempDir Path tempDir;

  private MemoryStore openStore() {
    return MemoryStore.open(tempDir.resolve("mosire.db"));
  }

  @Test
  void cjkWordInsideSentenceHitsThroughTrigram() {
    try (MemoryStore store = openStore()) {
      store.remember("偏好", "用户喜欢用 Kafka", "local", AccessToken.DEFAULT);
      MemoryRetriever retriever = new MemoryRetriever(store);

      // 句中拉丁词（默认 k=8 的重载）
      assertThat(retriever.retrieve("Kafka", AgentPermissionSet.unrestricted(AccessToken.DEFAULT)))
          .extracting(MemoryRecord::text)
          .containsExactly("用户喜欢用 Kafka");

      // CJK 红线：三字中文词是句内子串——trigram 命中；unicode61 下整段"用户喜欢用"是单一
      // token，同查询必然 miss（反例实证见 MemoryStore DDL 旁注释）
      assertThat(retriever.retrieve("喜欢用", AgentPermissionSet.unrestricted(AccessToken.DEFAULT)))
          .extracting(MemoryRecord::text)
          .containsExactly("用户喜欢用 Kafka");
    }
  }

  @Test
  void unrelatedWordDoesNotHit() {
    try (MemoryStore store = openStore()) {
      store.remember("偏好", "用户喜欢用 Kafka", "local", AccessToken.DEFAULT);
      MemoryRetriever retriever = new MemoryRetriever(store);

      assertThat(retriever.retrieve("卡夫卡", AgentPermissionSet.unrestricted(AccessToken.DEFAULT)))
          .isEmpty();
    }
  }

  @Test
  void shortQueryFallsBackToLike() {
    try (MemoryStore store = openStore()) {
      store.remember("偏好", "用户喜欢用 Kafka", "local", AccessToken.DEFAULT);
      MemoryRetriever retriever = new MemoryRetriever(store);
      AgentPermissionSet caller = AgentPermissionSet.unrestricted(AccessToken.DEFAULT);

      // 1–2 字无法构成 trigram，走 LIKE 子串匹配
      assertThat(retriever.retrieve("欢", caller)).hasSize(1);
      assertThat(retriever.retrieve("喜欢", caller)).hasSize(1);
    }
  }

  @Test
  void overPrivilegedMemoryIsInvisibleAndDoesNotConsumeK() {
    try (MemoryStore store = openStore()) {
      // 越权行后写：LIKE 路径按 id 倒序，若过滤晚于 LIMIT，k=1 时先被取出的就是它
      store.remember("偏好", "用户喜欢用 Kafka", "local", AccessToken.DEFAULT);
      store.remember("运维", "Kafka 集群口令轮换", "user", AccessToken.SYSTEM);
      MemoryRetriever retriever = new MemoryRetriever(store);

      // default 调用者：k=1 只可能取到可见行（越权行在 SQL 里就被滤掉，不占 K 槽位）
      assertThat(
              retriever.retrieve("Kafka", AgentPermissionSet.unrestricted(AccessToken.DEFAULT), 1))
          .extracting(MemoryRecord::text)
          .containsExactly("用户喜欢用 Kafka");
      // 全量取也只见自己那条
      assertThat(retriever.retrieve("Kafka", AgentPermissionSet.unrestricted(AccessToken.DEFAULT)))
          .hasSize(1);
      // 单调性另一侧：guest 看不到 default 级记忆
      assertThat(retriever.retrieve("Kafka", AgentPermissionSet.unrestricted(AccessToken.GUEST)))
          .isEmpty();
      // 高身份可见全部（过滤是"至少 X 级"而非"恰好 X 级"）
      assertThat(retriever.retrieve("Kafka", AgentPermissionSet.system())).hasSize(2);
    }
  }

  @Test
  void scopeIsStoredAndReturnedWithResult() {
    try (MemoryStore store = openStore()) {
      store.remember("构建", "项目用 Maven 构建", "project", AccessToken.DEFAULT);
      MemoryRetriever retriever = new MemoryRetriever(store);

      List<MemoryRecord> hits =
          retriever.retrieve("Maven", AgentPermissionSet.unrestricted(AccessToken.DEFAULT));
      assertThat(hits).hasSize(1);
      assertThat(hits.get(0).scope()).isEqualTo("project");
      assertThat(hits.get(0).topic()).isEqualTo("构建");
    }
  }

  @Test
  void kLimitsResultCount() {
    try (MemoryStore store = openStore()) {
      store.remember("偏好", "用户喜欢用 Kafka", "local", AccessToken.DEFAULT);
      store.remember("运维", "Kafka 集群三节点", "project", AccessToken.DEFAULT);
      store.remember("踩坑", "Kafka 消费位点回退", "local", AccessToken.DEFAULT);
      MemoryRetriever retriever = new MemoryRetriever(store);

      assertThat(
              retriever.retrieve("Kafka", AgentPermissionSet.unrestricted(AccessToken.DEFAULT), 2))
          .hasSize(2);
      assertThat(
              retriever.retrieve("Kafka", AgentPermissionSet.unrestricted(AccessToken.DEFAULT), 8))
          .hasSize(3);
    }
  }

  @Test
  void blankQueryReturnsNothing() {
    try (MemoryStore store = openStore()) {
      store.remember("偏好", "用户喜欢用 Kafka", "local", AccessToken.DEFAULT);
      MemoryRetriever retriever = new MemoryRetriever(store);
      AgentPermissionSet caller = AgentPermissionSet.unrestricted(AccessToken.DEFAULT);

      // 空/空白查询不得退化成 LIKE '%%' 全量倾倒
      assertThat(retriever.retrieve("", caller)).isEmpty();
      assertThat(retriever.retrieve("   ", caller)).isEmpty();
    }
  }

  /** D15：embedding 接入点是"预留"——默认不注入，故默认实例必须给出空的 provider。 */
  @Test
  void embeddingProviderIsAbsentByDefault() {
    try (MemoryStore store = openStore()) {
      MemoryRetriever retriever = new MemoryRetriever(store);

      assertThat(retriever.embeddingProvider()).isEmpty();
    }
  }

  /**
   * D15 验收守卫：注入了 provider，本波次检索仍不得调用它（语义召回实现未落地，接上就是空转或误召回）。
   *
   * <p>当后续波次真正落 {@link EmbeddingProvider} 实现并接线时，本用例是**必须被有意改写**的那个——它会红， 提醒改动者"接线了"这件事是决策而非顺带。
   */
  @Test
  void injectedEmbeddingProviderIsExposedButNotConsultedThisWave() {
    try (MemoryStore store = openStore()) {
      store.remember("偏好", "用户喜欢用 Kafka", "local", AccessToken.DEFAULT);
      AgentPermissionSet caller = AgentPermissionSet.unrestricted(AccessToken.DEFAULT);
      List<String> embeddedTexts = new ArrayList<>();
      EmbeddingProvider recording =
          texts -> {
            embeddedTexts.addAll(texts);
            return List.of();
          };

      MemoryRetriever retriever = new MemoryRetriever(store, recording);

      assertThat(retriever.embeddingProvider()).containsSame(recording);
      // 与未注入 provider 的实例逐条一致：接线前检索行为不得有任何变化
      assertThat(retriever.retrieve("Kafka", caller))
          .isEqualTo(new MemoryRetriever(store).retrieve("Kafka", caller));
      assertThat(retriever.retrieve("Kafka", caller, 1))
          .isEqualTo(new MemoryRetriever(store).retrieve("Kafka", caller, 1));
      assertThat(embeddedTexts).isEmpty();
    }
  }
}
