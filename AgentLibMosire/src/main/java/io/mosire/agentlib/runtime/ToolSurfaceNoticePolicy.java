package io.mosire.agentlib.runtime;

import io.mosire.agentlib.context.ContextInjection;
import io.mosire.agentlib.context.ContextInjectionKinds;
import io.mosire.agentlib.tool.AgentTool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 工具面 revision 变化时，生成一条 {@link ContextInjection} 的策略。
 *
 * <p><b>为什么是策略而不是写死</b>：不同宿主对“工具变了要不要提醒、怎么提醒、提醒到什么粒度”的偏好不同；{@link #none()} 可以完全关闭，
 * 宿主也可以实现自己的版本（例如只提醒某个 group 的变化）。
 *
 * <p><b>标准策略</b>只做名字级 diff：新增、移除、同名但 description/jsonSchema 变化。它<b>不</b>读写 system 提示词——注入是一条独立的
 * user 消息（默认渲染器见 {@code ContextInjectionRenderer}）。
 */
@FunctionalInterface
public interface ToolSurfaceNoticePolicy {

  /**
   * 生成一条注入。
   *
   * @param previousRevision 上一次已通知的 revision
   * @param previous 上一次已通知的工具面
   * @param currentRevision 当前 revision
   * @param current 当前工具面
   * @return 需要投递的注入；空 = 不提醒
   */
  Optional<ContextInjection> notice(
      long previousRevision,
      List<AgentTool> previous,
      long currentRevision,
      List<AgentTool> current);

  /** 完全关闭自动注入。 */
  static ToolSurfaceNoticePolicy none() {
    return (previousRevision, previous, currentRevision, current) -> Optional.empty();
  }

  /** 标准策略：名字级新增/移除/定义更新。 */
  static ToolSurfaceNoticePolicy standard() {
    return (previousRevision, previous, currentRevision, current) -> {
      Objects.requireNonNull(previous, "previous");
      Objects.requireNonNull(current, "current");
      Map<String, AgentTool> before = byName(previous);
      Map<String, AgentTool> after = byName(current);

      List<String> added = new ArrayList<>();
      for (String name : after.keySet()) {
        if (!before.containsKey(name)) {
          added.add(name);
        }
      }
      List<String> removed = new ArrayList<>();
      for (String name : before.keySet()) {
        if (!after.containsKey(name)) {
          removed.add(name);
        }
      }
      List<String> updated = new ArrayList<>();
      for (Map.Entry<String, AgentTool> entry : after.entrySet()) {
        AgentTool old = before.get(entry.getKey());
        if (old == null) {
          continue;
        }
        if (!Objects.equals(descriptionOf(old), descriptionOf(entry.getValue()))
            || !Objects.equals(old.jsonSchema(), entry.getValue().jsonSchema())) {
          updated.add(entry.getKey());
        }
      }
      if (added.isEmpty() && removed.isEmpty() && updated.isEmpty()) {
        return Optional.empty();
      }

      String text =
          "工具面已变化（revision "
              + currentRevision
              + "）："
              + describe("新增", added)
              + describe("移除", removed)
              + describe("更新", updated)
              + "请重新评估可用工具，不要调用已移除的工具。";
      String summary =
          "工具面变化：+"
              + added.size()
              + "/-"
              + removed.size()
              + (updated.isEmpty() ? "" : "/~" + updated.size());
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("revision", currentRevision);
      data.put("added", List.copyOf(added));
      data.put("removed", List.copyOf(removed));
      data.put("updated", List.copyOf(updated));
      return Optional.of(
          new ContextInjection(
              "tool-surface-" + currentRevision,
              ContextInjectionKinds.TOOL_SURFACE_CHANGED,
              ContextInjection.Priority.HIGH,
              summary,
              text,
              data,
              null));
    };
  }

  private static Map<String, AgentTool> byName(List<AgentTool> tools) {
    Map<String, AgentTool> map = new LinkedHashMap<>();
    for (AgentTool tool : tools) {
      map.put(tool.name(), tool);
    }
    return map;
  }

  private static String descriptionOf(AgentTool tool) {
    return tool.description() == null ? "" : tool.description();
  }

  /** 名字列表最多展示 20 个；其余只报数量。 */
  private static String describe(String label, List<String> names) {
    if (names.isEmpty()) {
      return "";
    }
    int shown = Math.min(names.size(), 20);
    String joined = String.join("、", names.subList(0, shown));
    String suffix = names.size() > shown ? " 等 " + names.size() + " 个" : "";
    return label + " " + joined + suffix + "；";
  }
}
