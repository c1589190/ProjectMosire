package io.mosire.agentlib.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具注册表：按名字维护可用工具，线程安全。
 *
 * <p>快照语义：{@link #list()} 返回按名称排序的不可变小抄本，供 LLM 工具目录与审计使用—— 目录长得慢，调用方想保持稳定就取一次快照（计划 §3.1
 * 的分页快照在工具量大时启用）。
 *
 * <p>变更监听（{@link #onChange}）：注册/移除成功后同步回调，供 {@code AgentToMcpServer} 等消费方做 addTool/removeTool
 * 增量同步（M2 增删路径以通知为准，不做周期轮询）。
 */
public final class ToolRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(ToolRegistry.class);

  private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();
  private final CopyOnWriteArrayList<Runnable> changeListeners = new CopyOnWriteArrayList<>();

  /** 注册一个工具；名字重复抛 {@link IllegalArgumentException}；成功后广播变更。 */
  public AgentTool register(AgentTool tool) {
    putChecked(tool);
    notifyChanged();
    return tool;
  }

  /** 批量注册；遇到重复立即抛错（不部分成功，调用方需保证先归并）；广播一次变更。 */
  public void registerAll(List<AgentTool> newTools) {
    for (AgentTool tool : newTools) {
      putChecked(tool);
    }
    if (!newTools.isEmpty()) {
      notifyChanged();
    }
  }

  /** 移除一个工具；不存在返回 null；移除成功广播变更。 */
  public AgentTool unregister(String name) {
    AgentTool removed = tools.remove(name);
    if (removed != null) {
      notifyChanged();
    }
    return removed;
  }

  /** 批量移除（外部 MCP 工具源整组消失用）；返回实际移除数；广播一次变更。 */
  public int unregisterAll(Collection<String> names) {
    int count = 0;
    for (String name : names) {
      if (tools.remove(name) != null) {
        count++;
      }
    }
    if (count > 0) {
      notifyChanged();
    }
    return count;
  }

  /** 注册变更监听（同步调用，勿阻塞）；close 句柄撤销监听。 首事件即"当时快照"之外的下一步变更——需要快照请自行调用 {@link #list()}。 */
  public AutoCloseable onChange(Runnable listener) {
    Runnable l = Objects.requireNonNull(listener, "listener");
    changeListeners.add(l);
    return () -> changeListeners.remove(l);
  }

  public Optional<AgentTool> find(String name) {
    return Optional.ofNullable(tools.get(name));
  }

  /** 按名称排序的快照副本。 */
  public List<AgentTool> list() {
    List<AgentTool> snapshot = new ArrayList<>(tools.values());
    snapshot.sort(Comparator.comparing(AgentTool::name));
    return List.copyOf(snapshot);
  }

  public int size() {
    return tools.size();
  }

  /** 实际写入（重复检查），不广播——批量的广播统一在收尾处。 */
  private void putChecked(AgentTool tool) {
    AgentTool existing = tools.putIfAbsent(tool.name(), tool);
    if (existing != null) {
      throw new IllegalArgumentException("工具名重复: " + tool.name());
    }
  }

  /** 变更广播（同步）：监听方异常只记日志——变更本身已生效，不能回滚语义。 */
  private void notifyChanged() {
    for (Runnable listener : changeListeners) {
      try {
        listener.run();
      } catch (RuntimeException e) {
        LOG.warn("ToolRegistry 变更监听器异常", e);
      }
    }
  }
}
