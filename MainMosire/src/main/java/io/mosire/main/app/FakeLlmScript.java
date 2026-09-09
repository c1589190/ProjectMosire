package io.mosire.main.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * CLI 脚本化假 LLM（{@code run --fake-script}）——W5 smoke.sh 的离线确定性中轴：主进程在 CLI 形态下（无测试注入面） 也能经 A2A/AG-UI
 * 黑盒驱动"spawn → 工具调用 → kill"完整链。
 *
 * <p><strong>步骤格式</strong>（{@code ;} 分隔，每步或文本或工具调用）：
 *
 * <ul>
 *   <li>{@code text:<正文>}——纯文本助手回复；
 *   <li>{@code tool:<工具名>:<args JSON>}——单次工具调用；参数为 JSON 对象，可省略（默认 {@code {}}）。
 * </ul>
 *
 * <p><strong>{@code $spawnedId} 占位符</strong>：tool 步骤参数的字符串值恰为 {@code $spawnedId} 时，播放时替换为
 * <b>请求消息里最近一次</b> {@code spawn_sub_agent} 工具结果的 instanceId——让"kill 需要在 spawn 之后才知道实例 id"的
 * 黑盒链免于硬编码；请求中无 spawn 结果时抛 {@link LlmException}（响亮失败，不做静默猜测）。
 *
 * <p>约定与 {@code FakeLlmClient} 一致：按序出队、耗尽后 {@code chat} 抛 {@link LlmException}（跑飞即暴露）；格式错误在 解析期（CLI
 * 启动）即抛 {@link IllegalArgumentException}。
 */
public final class FakeLlmScript {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** spawn 结果实例 id 占位符（tool 步骤参数 JSON 的字符串值）。 */
  public static final String SPAWNED_ID = "$spawnedId";

  /** spawn_sub_agent 工具名（占位符扫描目标）。 */
  private static final String SPAWN_TOOL = "spawn_sub_agent";

  private FakeLlmScript() {}

  /** 解析脚本（{@code ;} 分隔；空步跳过）；必须至少一个有效步骤，否则 {@link IllegalArgumentException}。 */
  public static LlmClient parse(String script) {
    if (script == null || script.isBlank()) {
      throw new IllegalArgumentException("--fake-script 不能为空");
    }
    Deque<Step> steps = new ArrayDeque<>();
    for (String raw : script.split(";")) {
      String stepStr = raw.trim();
      if (!stepStr.isEmpty()) {
        steps.add(parseStep(stepStr));
      }
    }
    if (steps.isEmpty()) {
      throw new IllegalArgumentException("--fake-script 没有任何有效步骤");
    }
    return new Scripted(steps);
  }

  private static Step parseStep(String step) {
    if (step.startsWith("text:")) {
      String text = step.substring("text:".length()).trim();
      if (text.isEmpty()) {
        throw new IllegalArgumentException("text 步骤的回复文本不能为空: " + step);
      }
      return new TextStep(text);
    }
    if (step.startsWith("tool:")) {
      String rest = step.substring("tool:".length());
      int colon = rest.indexOf(':');
      String tool = colon < 0 ? rest : rest.substring(0, colon);
      String argsJson = colon < 0 ? "{}" : rest.substring(colon + 1);
      if (tool.isBlank()) {
        throw new IllegalArgumentException("tool 步骤的工具名不能为空: " + step);
      }
      if (argsJson.isBlank()) {
        argsJson = "{}";
      }
      JsonNode args;
      try {
        args = JSON.readTree(argsJson);
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException("tool 步骤的参数不是合法 JSON: " + argsJson, e);
      }
      if (!args.isObject()) {
        throw new IllegalArgumentException("tool 步骤的参数必须是 JSON 对象: " + argsJson);
      }
      return new ToolStep(tool, (ObjectNode) args);
    }
    throw new IllegalArgumentException(
        "步骤格式必须是 text:<文本> 或 tool:<工具>:<args JSON>（分号分隔，空步忽略）: " + step);
  }

  /** 脚本播放器（每 CLI 进程一个；与 FakeLlmClient 同约定：按序出队、耗尽即抛）。 */
  private static final class Scripted implements LlmClient {

    private final Deque<Step> steps;
    private int calls;

    Scripted(Deque<Step> steps) {
      this.steps = steps;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      calls++;
      Step step = steps.pollFirst();
      if (step == null) {
        throw new LlmException("FakeLlmScript 脚本已耗空（第 " + calls + " 次调用）——按回合补齐步骤（text/tool 交替）");
      }
      return switch (step) {
        case TextStep text -> LlmResponse.text(text.text());
        case ToolStep tool ->
            LlmResponse.toolCall("fake-" + calls, tool.tool(), arguments(tool.args(), request));
      };
    }

    /** 占位符替换 + 对象化（占位符只沿字符串值匹配——JSON 结构照传）。 */
    private static Map<String, Object> arguments(ObjectNode args, LlmRequest request) {
      ObjectNode resolved = args.deepCopy();
      resolve(resolved, request);
      return JSON.convertValue(resolved, Map.class);
    }

    private static void resolve(ObjectNode node, LlmRequest request) {
      var fields = node.fields();
      while (fields.hasNext()) {
        var entry = fields.next();
        JsonNode child = entry.getValue();
        if (child.isObject()) {
          resolve((ObjectNode) child, request);
        } else if (child.isArray()) {
          resolveArray((ArrayNode) child, request);
        } else if (child.isTextual() && SPAWNED_ID.equals(child.asText())) {
          String instanceId = lastSpawnedId(request);
          if (instanceId == null) {
            throw new LlmException(SPAWNED_ID + " 无法解析：请求消息中无 spawn_sub_agent 工具结果（不能把占位符留给 kill）");
          }
          node.put(entry.getKey(), instanceId);
        }
      }
    }

    /** 数组元素沿同一占位符规则递归。 */
    private static void resolveArray(ArrayNode array, LlmRequest request) {
      for (JsonNode item : array) {
        if (item.isObject()) {
          resolve((ObjectNode) item, request);
        } else if (item.isArray()) {
          resolveArray((ArrayNode) item, request);
        }
      }
    }

    /** 从请求消息倒序找最近一次 spawn_sub_agent 的工具结果 → instanceId（结果 content=工具生成的快照 JSON）。 */
    private static String lastSpawnedId(LlmRequest request) {
      for (int i = request.messages().size() - 1; i >= 0; i--) {
        for (ContentPart part : request.messages().get(i).content()) {
          if (part instanceof ContentPart.ToolResult result
              && SPAWN_TOOL.equals(result.name())
              && result.content() != null) {
            try {
              String instanceId = JSON.readTree(result.content()).path("instanceId").asText();
              if (!instanceId.isBlank()) {
                return instanceId;
              }
            } catch (JsonProcessingException ignored) {
              // 该次 spawn 结果不可解析——继续往前找更早的结果
            }
          }
        }
      }
      return null;
    }
  }

  private sealed interface Step permits TextStep, ToolStep {}

  /** 文本回复步骤。 */
  private record TextStep(String text) implements Step {}

  /** 工具调用步骤（args 为解析后的 JSON 对象——占位符在播放时替换）。 */
  private record ToolStep(String tool, ObjectNode args) implements Step {}
}
