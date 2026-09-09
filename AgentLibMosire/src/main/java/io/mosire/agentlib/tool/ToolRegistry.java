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
 *
 * <p>来源归属：工具可带供给源 id 注册（二期 ToolSource 的 L2 层），内部维护 工具名 → sourceId 映射，支持按源整组卸载； 无源注册（内建路径）sourceId
 * 记为 null，永不参与按源匹配。注意：进入本表 = 全局可用，与某个 Agent 的白名单（L3）无关。
 */
public final class ToolRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(ToolRegistry.class);

  /** 无源工具的内部哨兵值（ConcurrentHashMap 不允许 null value）；真实 sourceId 非空白，永不与它相等。 */
  private static final String NO_SOURCE = "";

  private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();
  private final Map<String, String> toolToSource = new ConcurrentHashMap<>();
  private final CopyOnWriteArrayList<Runnable> changeListeners = new CopyOnWriteArrayList<>();

  /** 注册一个工具（内建路径，无来源归属）；名字重复抛 {@link IllegalArgumentException}；成功后广播变更。 */
  public AgentTool register(AgentTool tool) {
    putChecked(null, tool);
    notifyChanged();
    return tool;
  }

  /**
   * 以指定供给源注册一个工具（sourceId 供审计与按源卸载，见二期 D13）； 其余语义同 {@link #register(AgentTool)}：名字重复抛 {@link
   * IllegalArgumentException}，成功后广播变更。
   */
  public AgentTool register(String sourceId, AgentTool tool) {
    Objects.requireNonNull(sourceId, "sourceId");
    putChecked(sourceId, tool);
    notifyChanged();
    return tool;
  }

  /** 批量注册；遇到重复立即抛错（不部分成功，调用方需保证先归并）；广播一次变更。 */
  public void registerAll(List<AgentTool> newTools) {
    for (AgentTool tool : newTools) {
      putChecked(null, tool);
    }
    if (!newTools.isEmpty()) {
      notifyChanged();
    }
  }

  /** 移除一个工具；不存在返回 null；移除成功广播变更。 */
  public AgentTool unregister(String name) {
    AgentTool removed = tools.remove(name);
    if (removed != null) {
      toolToSource.remove(name);
      notifyChanged();
    }
    return removed;
  }

  /** 批量移除（外部 MCP 工具源整组消失用）；返回实际移除数；广播一次变更。 */
  public int unregisterAll(Collection<String> names) {
    int count = 0;
    for (String name : names) {
      if (tools.remove(name) != null) {
        toolToSource.remove(name);
        count++;
      }
    }
    if (count > 0) {
      notifyChanged();
    }
    return count;
  }

  /**
   * 按供给源整组移除（该源下线/插件卸载用）；返回实际移除数；有移除则广播一次变更。 幂等：源不存在或已清空返回 0。与 {@link #unregisterAll(Collection)}
   * 意义不同（按 sourceId 匹配 vs 按工具名匹配），故取独立名字避免重载歧义。
   */
  public int unregisterAllBySource(String sourceId) {
    if (sourceId == null || sourceId.isBlank()) {
      // 空白 id 会命中无源哨兵值，把内建工具整组误删——必须显式拒绝
      throw new IllegalArgumentException("sourceId 不能为空白");
    }
    List<String> names = new ArrayList<>();
    for (Map.Entry<String, String> entry : toolToSource.entrySet()) {
      if (sourceId.equals(entry.getValue())) {
        names.add(entry.getKey());
      }
    }
    int count = 0;
    for (String name : names) {
      if (tools.remove(name) != null) {
        count++;
      }
      toolToSource.remove(name);
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

  /** 实际写入（重复检查 + 来源归属登记），不广播——批量的广播统一在收尾处。 */
  private void putChecked(String sourceId, AgentTool tool) {
    AgentTool existing = tools.putIfAbsent(tool.name(), tool);
    if (existing != null) {
      throw new IllegalArgumentException("工具名重复: " + tool.name());
    }
    toolToSource.put(tool.name(), sourceId == null ? NO_SOURCE : sourceId);
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
