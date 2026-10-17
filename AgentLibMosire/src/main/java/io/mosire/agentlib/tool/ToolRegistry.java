package io.mosire.agentlib.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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
 *
 * <p><b>批量原子性</b>：{@link #registerAll(List)} 先全量校验（null / 空白名 / 批内重复 / 与现有冲突），全部通过后在
 * 同一把写锁内一次写入；任何失败都不会留下半套工具。读接口（{@link #list()}、{@link #find(String)} 等）持读锁，
 * 因此并发观察者也看不到"只写了一半"的中间态。单条 {@link #register(AgentTool)} / {@link #unregister(String)} 的语义不变。
 */
public final class ToolRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(ToolRegistry.class);

  /** 无源工具的内部哨兵值（ConcurrentHashMap 不允许 null value）；真实 sourceId 非空白，永不与它相等。 */
  private static final String NO_SOURCE = "";

  /**
   * 写锁保护所有变更（单条注册/批量注册/移除）与来源归属表的一致性；读锁保证快照不会读到批量写入的中间态。
   *
   * <p>为什么不用"先校验再逐个 putIfAbsent"：那样并发批量注册会交错，出现"A 写一半、B 写另一半"的半套状态； 写锁把"校验 +
   * 写入"合成一个临界区，批量注册要么整批可见、要么完全不可见。
   */
  private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();

  private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();
  private final Map<String, String> toolToSource = new ConcurrentHashMap<>();
  private final CopyOnWriteArrayList<Runnable> changeListeners = new CopyOnWriteArrayList<>();

  /** 注册一个工具（内建路径，无来源归属）；名字重复抛 {@link IllegalArgumentException}；成功后广播变更。 */
  public AgentTool register(AgentTool tool) {
    readWriteLock.writeLock().lock();
    try {
      putChecked(null, tool);
    } finally {
      readWriteLock.writeLock().unlock();
    }
    notifyChanged();
    return tool;
  }

  /**
   * 以指定供给源注册一个工具（sourceId 供审计与按源卸载，见二期 D13）； 其余语义同 {@link #register(AgentTool)}：名字重复抛 {@link
   * IllegalArgumentException}，成功后广播变更。
   */
  public AgentTool register(String sourceId, AgentTool tool) {
    Objects.requireNonNull(sourceId, "sourceId");
    readWriteLock.writeLock().lock();
    try {
      putChecked(sourceId, tool);
    } finally {
      readWriteLock.writeLock().unlock();
    }
    notifyChanged();
    return tool;
  }

  /**
   * 批量注册（无来源归属）；全有或全无，成功后广播一次变更。
   *
   * <p>先对整批做全量校验——元素为 null、名字 null/空白、批内重名、与现有工具重名——任何一项不通过都抛 {@link IllegalArgumentException}
   * 且<b>不写入任何一条</b>；全部通过后在同一把写锁内写入整批，并发调用方与 读接口都看不到半套。
   *
   * @param newTools 待注册工具；空列表是合法的空操作
   * @throws IllegalArgumentException 校验失败（含 null 元素、空白名、重名）
   */
  public void registerAll(List<AgentTool> newTools) {
    Objects.requireNonNull(newTools, "newTools");
    readWriteLock.writeLock().lock();
    try {
      List<PendingTool> pending = validateBatch(newTools);
      for (PendingTool entry : pending) {
        tools.put(entry.name(), entry.tool());
        toolToSource.put(entry.name(), NO_SOURCE);
      }
    } finally {
      readWriteLock.writeLock().unlock();
    }
    if (!newTools.isEmpty()) {
      notifyChanged();
    }
  }

  /** 移除一个工具；不存在返回 null；移除成功广播变更。 */
  public AgentTool unregister(String name) {
    AgentTool removed;
    readWriteLock.writeLock().lock();
    try {
      removed = tools.remove(name);
      if (removed != null) {
        toolToSource.remove(name);
      }
    } finally {
      readWriteLock.writeLock().unlock();
    }
    if (removed != null) {
      notifyChanged();
    }
    return removed;
  }

  /** 批量移除（外部 MCP 工具源整组消失用）；返回实际移除数；广播一次变更。 */
  public int unregisterAll(Collection<String> names) {
    int count = 0;
    readWriteLock.writeLock().lock();
    try {
      for (String name : names) {
        if (tools.remove(name) != null) {
          toolToSource.remove(name);
          count++;
        }
      }
    } finally {
      readWriteLock.writeLock().unlock();
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
    int count = 0;
    readWriteLock.writeLock().lock();
    try {
      List<String> names = new ArrayList<>();
      for (Map.Entry<String, String> entry : toolToSource.entrySet()) {
        if (sourceId.equals(entry.getValue())) {
          names.add(entry.getKey());
        }
      }
      for (String name : names) {
        if (tools.remove(name) != null) {
          count++;
        }
        toolToSource.remove(name);
      }
    } finally {
      readWriteLock.writeLock().unlock();
    }
    if (count > 0) {
      notifyChanged();
    }
    return count;
  }

  /**
   * 当前在册的供给源 id 集合（只读枚举，按字典序；无源注册的哨兵值不参与——它永不参与按源匹配）。 供 {@code source_list} 一类消费方回答"现在有哪些源"。
   *
   * <p><b>两层信息分工（消费方须知晓）</b>：本表只知道<b>当前在册</b>的源——某工具源的工具被整组摘除后（插件 disable、MCP 源下线），其 sourceId
   * 即从这里消失；<b>已发现但当前无工具在册的供给源</b>（如被 disable 的插件）不在本表里，那部分只能由该供给源自己维护的清单回答（见 {@code
   * PluginToolSource#list()}），{@code source_list} 是两层的合并。
   */
  public Set<String> sourceIds() {
    readWriteLock.readLock().lock();
    try {
      Set<String> ids = new TreeSet<>();
      for (String sourceId : toolToSource.values()) {
        if (!NO_SOURCE.equals(sourceId)) {
          ids.add(sourceId);
        }
      }
      return Collections.unmodifiableSet(ids);
    } finally {
      readWriteLock.readLock().unlock();
    }
  }

  /**
   * 按供给源枚举当前在册的工具（按名称排序的不可变小抄本，口径同 {@link #list()}）。
   *
   * <p>空白 id 显式拒绝：空白会命中无源哨兵（内建/无源注册路径），按源枚举它不是"某个源"，而是"全部无源工具"——与 {@link
   * #unregisterAllBySource(String)} 同口径，避免把哨兵当普通 sourceId 用。
   *
   * @throws IllegalArgumentException sourceId 为空白（null 亦按空白拒绝）
   */
  public List<AgentTool> listBySource(String sourceId) {
    if (sourceId == null || sourceId.isBlank()) {
      throw new IllegalArgumentException("sourceId 不能为空白");
    }
    readWriteLock.readLock().lock();
    try {
      List<AgentTool> snapshot = new ArrayList<>();
      for (Map.Entry<String, String> entry : toolToSource.entrySet()) {
        if (sourceId.equals(entry.getValue())) {
          AgentTool tool = tools.get(entry.getKey());
          if (tool != null) {
            snapshot.add(tool);
          }
        }
      }
      snapshot.sort(Comparator.comparing(AgentTool::name));
      return List.copyOf(snapshot);
    } finally {
      readWriteLock.readLock().unlock();
    }
  }

  /** 注册变更监听（同步调用，勿阻塞）；close 句柄撤销监听。 首事件即"当时快照"之外的下一步变更——需要快照请自行调用 {@link #list()}。 */
  public AutoCloseable onChange(Runnable listener) {
    Runnable l = Objects.requireNonNull(listener, "listener");
    changeListeners.add(l);
    return () -> changeListeners.remove(l);
  }

  public Optional<AgentTool> find(String name) {
    readWriteLock.readLock().lock();
    try {
      return Optional.ofNullable(tools.get(name));
    } finally {
      readWriteLock.readLock().unlock();
    }
  }

  /** 按名称排序的快照副本。 */
  public List<AgentTool> list() {
    readWriteLock.readLock().lock();
    try {
      List<AgentTool> snapshot = new ArrayList<>(tools.values());
      snapshot.sort(Comparator.comparing(AgentTool::name));
      return List.copyOf(snapshot);
    } finally {
      readWriteLock.readLock().unlock();
    }
  }

  public int size() {
    readWriteLock.readLock().lock();
    try {
      return tools.size();
    } finally {
      readWriteLock.readLock().unlock();
    }
  }

  /** 实际写入（重复检查 + 来源归属登记），不广播——批量的广播统一在收尾处。调用方必须持有写锁。 */
  private void putChecked(String sourceId, AgentTool tool) {
    AgentTool existing = tools.putIfAbsent(tool.name(), tool);
    if (existing != null) {
      throw new IllegalArgumentException("工具名重复: " + tool.name());
    }
    toolToSource.put(tool.name(), sourceId == null ? NO_SOURCE : sourceId);
  }

  /**
   * 批量注册的全量校验：只看不写，任何一项不合法即抛错（调用方据此保证零部分注册）。
   *
   * <p>名字在此时取一次并随校验结果返回，写入阶段不再调用 {@code tool.name()}——避免校验与写入之间名字变化 导致"校验的是 A、写进去的是
   * B"。调用方必须持有写锁，否则校验与写入之间的现有集合可能被别人改动。
   */
  private List<PendingTool> validateBatch(List<AgentTool> newTools) {
    List<PendingTool> pending = new ArrayList<>(newTools.size());
    Set<String> batchNames = new HashSet<>();
    for (AgentTool tool : newTools) {
      if (tool == null) {
        throw new IllegalArgumentException("批量注册的工具不能为 null");
      }
      String name = tool.name();
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("工具名不能为空白: " + tool.getClass().getName());
      }
      if (!batchNames.add(name)) {
        throw new IllegalArgumentException("批量注册内工具名重复: " + name);
      }
      if (tools.containsKey(name)) {
        throw new IllegalArgumentException("工具名重复: " + name);
      }
      pending.add(new PendingTool(name, tool));
    }
    return pending;
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

  /** 校验产物：名字只取一次，写入阶段按它落库。 */
  private record PendingTool(String name, AgentTool tool) {}
}
