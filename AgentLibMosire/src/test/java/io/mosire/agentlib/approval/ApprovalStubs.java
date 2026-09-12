package io.mosire.agentlib.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 审批用例的公共夹具：权限集 / 上下文 / 注册表 / 事件 payload 解析。 */
final class ApprovalStubs {

  private static final ObjectMapper JSON = new ObjectMapper();

  private ApprovalStubs() {
    throw new AssertionError("No instances");
  }

  /**
   * 调用上下文：权限集用"白名单通配 + 敏感/破坏双放行"——让"被挡住"这件事只能来自审批闸与身份级别， 不会与白名单混淆（沿用 {@code
   * ToolCallAuthorizerTest#unrestricted} 的套路）。
   */
  static ToolContext context(AccessToken caller) {
    return context(caller, Map.of());
  }

  static ToolContext context(AccessToken caller, Map<String, Object> arguments) {
    return new ToolContext(caller, AgentPermissionSet.unrestricted(caller), Map.of(), arguments);
  }

  /** 睡眠但不吞中断（用例里只用来给"人"安排一个答复时刻/给等待一个轮询间隔）。 */
  static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  static ToolRegistry registryWith(AgentTool... tools) {
    ToolRegistry registry = new ToolRegistry();
    for (AgentTool tool : tools) {
      registry.register(tool);
    }
    return registry;
  }

  static JsonNode payload(Event event) {
    try {
      return JSON.readTree(event.payload());
    } catch (JsonProcessingException broken) {
      throw new AssertionError("事件 payload 不是合法 JSON: " + event.payload(), broken);
    }
  }

  /** payload 的字段名集合（用来咬"字段恰好是这些、不多不少"）。 */
  static Set<String> fields(JsonNode payload) {
    Set<String> names = new TreeSet<>();
    payload.fieldNames().forEachRemaining(names::add);
    return names;
  }
}
