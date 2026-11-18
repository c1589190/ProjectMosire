package io.mosire.agentlib.agent;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * 宿主提供的 Agent 配置。默认预算由 {@link #DEFAULT_MAX_TURNS}、{@link #DEFAULT_MAX_TOOL_CALLS_PER_TURN} 和 {@link
 * #DEFAULT_TIME_BUDGET} 定义。
 *
 * @param maxTurns 一次用户回合允许完成的模型往返上限
 * @param maxToolCallsPerTurn 一次模型响应允许包含的工具调用数上限
 * @param timeBudget 一次用户回合的墙钟预算
 * @param quotaMaxTokens 给宿主停因策略的 token 额度，0 表示不限
 */
public record AgentConfig(
    String id,
    String description,
    String systemPrompt,
    String model,
    Set<String> allowedTools,
    Set<String> deniedTools,
    int maxTurns,
    int maxToolCallsPerTurn,
    Duration timeBudget,
    long quotaMaxTokens) {

  /** 默认模型往返上限（2026-10-16 用户裁定）。日常回合由墙钟预算和 token 配额约束。 */
  public static final int DEFAULT_MAX_TURNS = 114514;

  public static final int DEFAULT_MAX_TOOL_CALLS_PER_TURN = 30;
  public static final Duration DEFAULT_TIME_BUDGET = Duration.ofMinutes(10);

  /** 0 = 不限配额。 */
  public static final long NO_QUOTA = 0L;

  public AgentConfig {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(systemPrompt, "systemPrompt");
    Objects.requireNonNull(model, "model");
    allowedTools = Set.copyOf(allowedTools == null ? Set.of() : allowedTools);
    deniedTools = Set.copyOf(deniedTools == null ? Set.of() : deniedTools);
    Objects.requireNonNull(timeBudget, "timeBudget");
  }

  public static Builder builder(String id) {
    return new Builder(id);
  }

  public static final class Builder {
    private final String id;
    private String description = "";
    private String systemPrompt = "你是 Mosire 主 Agent——一个模块化、可自管理、受权限约束的 Agent。";
    private String model = "fake";
    private Set<String> allowedTools = Set.of("*");
    private Set<String> deniedTools = Set.of();
    private int maxTurns = DEFAULT_MAX_TURNS;
    private int maxToolCallsPerTurn = DEFAULT_MAX_TOOL_CALLS_PER_TURN;
    private Duration timeBudget = DEFAULT_TIME_BUDGET;
    private long quotaMaxTokens = NO_QUOTA;

    private Builder(String id) {
      this.id = Objects.requireNonNull(id, "id");
    }

    public Builder description(String description) {
      this.description = description;
      return this;
    }

    public Builder systemPrompt(String systemPrompt) {
      this.systemPrompt = systemPrompt;
      return this;
    }

    public Builder model(String model) {
      this.model = model;
      return this;
    }

    /** 白名单；默认 {@code "*"} = 不做白名单限制。 */
    public Builder allowedTools(Set<String> allowedTools) {
      this.allowedTools = Set.copyOf(allowedTools);
      return this;
    }

    public Builder deniedTools(Set<String> deniedTools) {
      this.deniedTools = Set.copyOf(deniedTools);
      return this;
    }

    public Builder maxTurns(int maxTurns) {
      if (maxTurns <= 0) {
        throw new IllegalArgumentException("maxTurns 必须为正: " + maxTurns);
      }
      this.maxTurns = maxTurns;
      return this;
    }

    public Builder maxToolCallsPerTurn(int maxToolCallsPerTurn) {
      if (maxToolCallsPerTurn <= 0) {
        throw new IllegalArgumentException("maxToolCallsPerTurn 必须为正: " + maxToolCallsPerTurn);
      }
      this.maxToolCallsPerTurn = maxToolCallsPerTurn;
      return this;
    }

    public Builder timeBudget(Duration timeBudget) {
      this.timeBudget = Objects.requireNonNull(timeBudget, "timeBudget");
      return this;
    }

    public Builder quotaMaxTokens(long quotaMaxTokens) {
      if (quotaMaxTokens < 0) {
        throw new IllegalArgumentException("quotaMaxTokens 不能为负: " + quotaMaxTokens);
      }
      this.quotaMaxTokens = quotaMaxTokens;
      return this;
    }

    public AgentConfig build() {
      return new AgentConfig(
          id,
          description,
          systemPrompt,
          model,
          allowedTools,
          deniedTools,
          maxTurns,
          maxToolCallsPerTurn,
          timeBudget,
          quotaMaxTokens);
    }
  }
}
