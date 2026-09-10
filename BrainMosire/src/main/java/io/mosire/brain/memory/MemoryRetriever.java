package io.mosire.brain.memory;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 记忆检索流水线（计划 §4.3）：元数据过滤 → 关键词匹配 → 取前 K 条。
 *
 * <ol>
 *   <li><b>元数据过滤</b>：调用者身份等级换算成"可见身份集合"，下推到 SQL（{@code required_token IN (...)}） ——必须在 LIMIT
 *       之前生效：若先取 K 再过滤，越权行会白占槽位，调用者拿到的条数就少于 K 且不可控。
 *   <li><b>关键词匹配</b>：≥3 字符走 FTS5 trigram（CJK 安全，句内子串可命中）；1–2 字构不成 trigram， 回退 LIKE
 *       子串匹配，语义保持一致（都是子串，而非分词）。
 *   <li><b>取 K</b>：FTS 路径按 bm25 相关度升序，LIKE 路径按写入倒序。
 * </ol>
 *
 * <p>本波次不做 scope 过滤（user/project/local 只写不筛），也不发射 {@code memory.recall} 事件—— 两者都留给后续波次；embedding
 * 语义召回由 Task 13 的 SPI 接入点负责，本类当前不依赖它。
 */
public final class MemoryRetriever {

  /** 计划 §4.3：默认召回条数 K=8。 */
  public static final int DEFAULT_K = 8;

  /** trigram 最小成形长度：1–2 字查询无三元组可用，回退 LIKE。 */
  private static final int MIN_TRIGRAM_LENGTH = 3;

  private final MemoryStore store;

  public MemoryRetriever(MemoryStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  /** 按默认 K（{@link #DEFAULT_K}）检索调用者可见的记忆。 */
  public List<MemoryRecord> retrieve(String query, AgentPermissionSet permissionSet) {
    return retrieve(query, permissionSet, DEFAULT_K);
  }

  /**
   * 检索调用者可见的记忆，最多 {@code k} 条。
   *
   * @param query 检索词；空白查询返回空列表（否则会退化成匹配一切的全量倾倒）
   * @param permissionSet 调用者权限集——身份维度决定可见集合（授权维度属工具白名单，与记忆可见性无关）
   * @param k 召回条数上限，必须为正
   */
  public List<MemoryRecord> retrieve(String query, AgentPermissionSet permissionSet, int k) {
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(permissionSet, "permissionSet");
    if (k <= 0) {
      throw new IllegalArgumentException("k 必须为正: " + k);
    }
    String normalized = query.strip();
    if (normalized.isEmpty()) {
      return List.of();
    }
    List<AccessToken> visibleTokens = visibleTokens(permissionSet.grantedToken());
    return normalized.codePointCount(0, normalized.length()) >= MIN_TRIGRAM_LENGTH
        ? store.searchByFts(normalized, visibleTokens, k)
        : store.searchByLike(normalized, visibleTokens, k);
  }

  /**
   * 身份维度过滤：调用者可见"要求等级不高于自身"的全部记忆（{@link AccessToken#atLeast} 单调，GRANT 只加不减）。
   *
   * <p>用枚举全量筛出集合、由 SQL 做集合比较，而不是在 SQL 里比大小或写死 rank 数字：等级语义只在 {@link AccessToken} 一处定义，SQL
   * 只认名字，新增等级时无需改 SQL。
   *
   * <p>集合恒非空（{@link AccessToken#GUEST} 对任何调用者都成立），SQL 的 IN 列表因此不会是空括号（那是语法错误）。
   */
  private static List<AccessToken> visibleTokens(AccessToken caller) {
    return Arrays.stream(AccessToken.values()).filter(caller::atLeast).toList();
  }
}
