package io.mosire.agentlib.agent;

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
   * 默认轮次硬顶（计划 D10；2026-09-08 用户修订 20→2000；<b>2026-10-16 用户裁定再修订 2000→114514</b>）。
   *
   * <p><b>用户原话</b>：「把所有限制单个Agent执行轮数的限制改成114514！！！！」——即：<b>实际取消轮次上限</b>（这个数在任何真实 任务里都到不了）。
   *
   * <p>为什么这会成立：防跑飞的实际兜底本来就是 {@link #DEFAULT_TIME_BUDGET}（墙钟）——轮次硬顶只防御"每轮极快的空转循环"， 而那种循环也会很快撞上墙钟预算或
   * token 配额。留一个大数而不是 0/负数：{@code maxTurns} 的契约是"必须为正" （见模板与 Builder 的守卫），"不限"用"大到到不了"表达，比引入一个"0 =
   * 不限"的哨兵值少一条分支。
   *
   * <p>★ 时间预算与配额<b>没有被这次裁定改动</b>（10min / 30 次工具调用每轮仍在）：用户只裁了"轮数"。
   */
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
