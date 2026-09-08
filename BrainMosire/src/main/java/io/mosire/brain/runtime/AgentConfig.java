package io.mosire.brain.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Agent 配置（数据不是代码——计划 D10）。
 *
 * <p>M1 为构造器 + 校验；M3 起由 ConfigStore 经 JSON Schema（{@code configs/schemas/}）装载， 本类的构造器保持不变、新增一个
 * fromJson。默认值即硬顶常量（计划 D10，2026-09-08 用户修订 maxTurns 20→2000）： {@code maxTurns=2000} / {@code
 * maxToolCallsPerTurn=30} / {@code timeBudget=10min}； 一个 Agent 实例可以解绑自己的配额（配额归调用方：子 Agent
 * 的配额由父级决定，M2）。
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

  /**
   * 默认轮次硬顶（计划 D10；2026-09-08 用户修订 20→2000）。
   *
   * <p>为什么放大：20 轮对长任务（多文件改造、深链调查）明显不够用；防跑飞的实际兜底由 {@link #DEFAULT_TIME_BUDGET}（墙钟）承担——
   * 轮次硬顶只防御"每轮极快的空转循环"，预算硬顶防御"真实耗时失控"。
   */
  public static final int DEFAULT_MAX_TURNS = 2000;

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
