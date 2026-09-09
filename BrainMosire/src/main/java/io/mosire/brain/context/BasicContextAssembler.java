package io.mosire.brain.context;

import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.ToolDef;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.brain.runtime.AgentConfig;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 基础实现：system = 行为提示 + 工具目录（一行一个名字+描述）；历史逐条原样；user 原样；工具定义照传。
 *
 * <p>内部按 {@link ContextLayer} 固定层序组装段落（{@code Segment}），当前只有 SYSTEM 层有内容，其余层为空占位—— 后续层接入时在 {@code
 * systemSegments} 按序插入。{@link ContextPolicy} 作为每层预算的来源随构造注入（本任务只记账不截断， 预算是建议值，不改变输出）。
 */
public final class BasicContextAssembler implements ContextAssembler {

  private final ContextPolicy policy;

  /** 使用默认预算策略（{@link ContextPolicy#defaults()}）。 */
  public BasicContextAssembler() {
    this(ContextPolicy.defaults());
  }

  public BasicContextAssembler(ContextPolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  /** 本装配器生效的每层预算策略（诊断与 {@code context} CLI 用）。当前预算只是建议值——接入截断/淘汰前不影响 {@code buildRequest} 输出。 */
  public ContextPolicy policy() {
    return policy;
  }

  @Override
  public LlmRequest buildRequest(
      AgentConfig config, String userMessage, List<LlmMessage> history, List<AgentTool> tools) {
    StringBuilder system = new StringBuilder();
    for (Segment segment : systemSegments(config, tools)) {
      system.append(segment.text());
    }

    // 契约：[system] + history（逐条原样）+ [user]——history 原样回灌以保 prompt-cache 前缀稳定
    List<LlmMessage> messages = new ArrayList<>(history.size() + 2);
    messages.add(LlmMessage.system(system.toString()));
    messages.addAll(history);
    messages.add(LlmMessage.user(userMessage));
    List<ToolDef> toolDefs =
        tools.stream().map(t -> new ToolDef(t.name(), t.description(), t.jsonSchema())).toList();
    return new LlmRequest(messages, toolDefs);
  }

  @Override
  public ContextComposition composition(
      AgentConfig config, String userMessage, List<LlmMessage> history, List<AgentTool> tools) {
    Map<ContextLayer, Integer> tokens = new EnumMap<>(ContextLayer.class);
    for (ContextLayer layer : ContextLayer.values()) {
      tokens.put(layer, 0);
    }
    for (Segment segment : systemSegments(config, tools)) {
      tokens.merge(segment.layer(), estimateTokens(segment.text()), Integer::sum);
    }
    return new ContextComposition(tokens);
  }

  /** 一个层内段落：层归属 + 原文（composition 按它记账，buildRequest 按层序拼接）。 */
  private record Segment(ContextLayer layer, String text) {}

  /**
   * 按 {@link ContextLayer} 声明顺序产出 system 消息的段落。当前只有 SYSTEM 层（行为提示 + 工具目录 +
   * 收尾指令）有内容；RULES/SKILL_INDEX/LOADED_SKILLS/MEMORY/COMPACT_SUMMARY 是空占位，接入时在对应位置
   * 插入新段落、既有段落保持字节不动（前缀契约）。
   */
  private List<Segment> systemSegments(AgentConfig config, List<AgentTool> tools) {
    StringBuilder text = new StringBuilder(config.systemPrompt());
    text.append("\n\n你有以下工具可用（一律直接调用，不要凭空想象它们不存在）：\n");
    if (tools.isEmpty()) {
      text.append("（当前没有任何可用工具）\n");
    } else {
      for (AgentTool tool : tools) {
        text.append("- ").append(tool.name());
        if (!tool.description().isBlank()) {
          text.append(": ").append(tool.description());
        }
        text.append('\n');
      }
    }
    text.append("\n完成用户请求后，请用纯文本回复结果，不要虚构工具执行过程。");
    return List.of(new Segment(ContextLayer.SYSTEM, text.toString()));
  }

  /** token 估算：≈ 字符数 / 4（整数除法、向下取整）——无真实分词器前的占位算法，后续波次换 JTokkit， 届时只改这一处。 */
  private static int estimateTokens(String text) {
    return text.length() / 4;
  }
}
