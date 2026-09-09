package io.mosire.brain.runtime;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * Agent 定义（数据不是代码——计划 D17）：一份"规格"驱动同一个 {@link AgentRuntime}——不再为不同形态的 Agent 硬编码类， 只组合不同的 Spec 即可（主
 * Agent 与子 Agent 的差异 = 数据差异）。
 *
 * <p><b>组合而非摊平</b>：既有 10 字段配置保持为 {@link AgentConfig}（D10 硬顶语义不变——{@code maxTurns=2000} / {@code
 * maxToolCallsPerTurn=30} / {@code timeBudget=10min}），本类只承载二期（P2）新增字段。子 Agent 权限单调收紧
 * （SubagentManager/AgentTemplate）仍以 {@link AgentConfig} 为准，不经过本类。
 *
 * <p><b>YAGNI（开发计划-二期 §六）</b>：只落当前需要的字段；枚举型字段一律用 {@code String}（"low"/"medium"/"high"、
 * "local"/"project"/"user"、"default"/"auto"）——合法值集由后续波次接线时再收敛，避免过早定型。 二期字段在本任务（P2-2 Task
 * 6）仅作数据承载，<b>不接线任何行为</b>；接线属 Task 7 与后续波次。
 */
public record AgentSpec(
    AgentConfig core,
    String provider,
    String reasoningEffort,
    int maxOutputTokens,
    Set<String> skills,
    String memoryScope,
    String permissionMode,
    Path workingDirectory) {

  /** 空 provider = 用 ModelProvider 的默认模型。 */
  public static final String DEFAULT_PROVIDER = "";

  public static final String DEFAULT_REASONING_EFFORT = "medium";
  public static final String DEFAULT_MEMORY_SCOPE = "local";
  public static final String DEFAULT_PERMISSION_MODE = "default";

  /** 0 = 不限制输出 token 数。 */
  public static final int UNLIMITED_OUTPUT_TOKENS = 0;

  /**
   * 便捷构造：全部二期字段取默认值（provider="" / effort=medium / 输出不限 / 无预载技能 / local 记忆 / default 权限模式 /
   * 无独立工作目录）。
   */
  public AgentSpec(AgentConfig core) {
    this(
        core,
        DEFAULT_PROVIDER,
        DEFAULT_REASONING_EFFORT,
        UNLIMITED_OUTPUT_TOKENS,
        Set.of(),
        DEFAULT_MEMORY_SCOPE,
        DEFAULT_PERMISSION_MODE,
        null);
  }

  /**
   * 校验：core 非空；skills 防御性拷贝（null = 空）；reasoningEffort / memoryScope / permissionMode 非空。 provider 与
   * workingDirectory 允许为 null/空串（空串 = 用默认）。
   */
  public AgentSpec {
    Objects.requireNonNull(core, "core");
    Objects.requireNonNull(reasoningEffort, "reasoningEffort");
    Objects.requireNonNull(memoryScope, "memoryScope");
    Objects.requireNonNull(permissionMode, "permissionMode");
    skills = Set.copyOf(skills == null ? Set.of() : skills);
    if (maxOutputTokens < 0) {
      throw new IllegalArgumentException("maxOutputTokens 不能为负: " + maxOutputTokens);
    }
  }

  /** 以既有 {@link AgentConfig} 为核心、二期字段全部取默认值起步。 */
  public static Builder builder(AgentConfig core) {
    return new Builder(core);
  }

  /** 逐字段 with 风格（参照 {@link AgentConfig.Builder} 的写法）；未设置项取默认值。 */
  public static final class Builder {
    private final AgentConfig core;
    private String provider = DEFAULT_PROVIDER;
    private String reasoningEffort = DEFAULT_REASONING_EFFORT;
    private int maxOutputTokens = UNLIMITED_OUTPUT_TOKENS;
    private Set<String> skills = Set.of();
    private String memoryScope = DEFAULT_MEMORY_SCOPE;
    private String permissionMode = DEFAULT_PERMISSION_MODE;
    private Path workingDirectory = null;

    private Builder(AgentConfig core) {
      this.core = Objects.requireNonNull(core, "core");
    }

    /** 空/缺省 = 用 ModelProvider 默认。 */
    public Builder provider(String provider) {
      this.provider = provider;
      return this;
    }

    /** "low"/"medium"/"high"；默认 "medium"。 */
    public Builder reasoningEffort(String reasoningEffort) {
      this.reasoningEffort = reasoningEffort;
      return this;
    }

    /** 0 = 不限制。 */
    public Builder maxOutputTokens(int maxOutputTokens) {
      this.maxOutputTokens = maxOutputTokens;
      return this;
    }

    /** 预载技能（P2-4 接线）；默认空。 */
    public Builder skills(Set<String> skills) {
      this.skills = Set.copyOf(skills == null ? Set.of() : skills);
      return this;
    }

    /** "local"/"project"/"user"；默认 "local"（P2-4 接线）。 */
    public Builder memoryScope(String memoryScope) {
      this.memoryScope = memoryScope;
      return this;
    }

    /** "default"/"auto"；默认 "default"。 */
    public Builder permissionMode(String permissionMode) {
      this.permissionMode = permissionMode;
      return this;
    }

    /** 可空（子 Agent 工作目录，装配层控制）。 */
    public Builder workingDirectory(Path workingDirectory) {
      this.workingDirectory = workingDirectory;
      return this;
    }

    public AgentSpec build() {
      return new AgentSpec(
          core,
          provider,
          reasoningEffort,
          maxOutputTokens,
          skills,
          memoryScope,
          permissionMode,
          workingDirectory);
    }
  }
}
