package io.mosire.agentlib.mcp;

import io.mosire.agentlib.plugin.ToolSource;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具供给源（{@link ToolSource}，当前即 MCP 工具源）→ 本地 {@link ToolRegistry} 的实时同步桥（MCP 双向闭环的"入"侧）。
 *
 * <p>上游只负责连接与工具目录变更事件；本桥把它变成 Registry 的"活工具集"：bind 时回放当前快照完成初始注册， 之后每次变更事件触发 {@code sync()}，按名字做幂等
 * diff（多余的下架、缺的补上），close 时整组下架（恢复注册表原状）。
 *
 * <p>时序：bridge 可在 {@code source.connect()} 前后 bind——未连接时 {@code listTools()} 快照为空（空 diff 为
 * no-op），连接后的基线/变更事件再驱动同步；diff 幂等所以重复触发无副作用。
 *
 * <p>边界（M2 口径）：以工具名为身份——同名工具的定义变更（schema/description 更新）按"名字相同未变化"处理，不强制替换； 多源同名冲突由调用方（Brain
 * 载入层）负责归一。
 */
public final class McpSourceBridge implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(McpSourceBridge.class);

  private final ToolSource source;
  private final ToolRegistry registry;
  private final Predicate<String> filter;
  private final AutoCloseable subscription;

  /** 私有锁对象（SpotBugs USO：避免类固有锁被外泄——bind 静态工厂会共享实例）。 */
  private final Object lock = new Object();

  private volatile boolean closed;
  private volatile Set<String> registeredNames = Set.of();

  private McpSourceBridge(ToolSource source, ToolRegistry registry, Predicate<String> filter) {
    this.source = Objects.requireNonNull(source, "source");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.filter = Objects.requireNonNull(filter, "filter");
    // 先订阅后回放：订阅与初始快照之间的变更事件不会丢失（sync 幂等，重复触发无副作用）
    this.subscription = source.onChange(this::sync);
    sync();
  }

  /**
   * 绑定：注册同步（立即按当前快照 diff 一次，之后跟随变更事件）。
   *
   * @param source 已连接或未连接均可（见类注释时序）
   * @param registry 目标注册表
   */
  public static McpSourceBridge bind(ToolSource source, ToolRegistry registry) {
    return bind(source, registry, name -> true);
  }

  /**
   * 绑定（带名字过滤；W3b 子 Agent 侧白名单落地）：只把过滤命中的工具同步进注册表，未命中的（父侧 暴露面全量中的其余工具）对子侧不可见。
   *
   * @param source 已连接或未连接均可（见类注释时序）
   * @param registry 目标注册表
   * @param filter 工具名过滤器（命中才同步；每次目录变更重放时都按新全量重新过滤，目录收窄会自动下架）
   */
  public static McpSourceBridge bind(
      ToolSource source, ToolRegistry registry, Predicate<String> filter) {
    return new McpSourceBridge(source, registry, filter);
  }

  /** 按名字 diff 同步（幂等）：读当前快照全集，先清后补，避免两个快照间同名工具"先移除再注册"的竞态（注册表拒绝重名）。 */
  private void sync() {
    synchronized (lock) {
      if (closed) {
        return;
      }
      List<AgentTool> tools = source.listTools();
      Set<String> desired =
          tools.stream()
              .map(AgentTool::name)
              .filter(filter)
              .collect(Collectors.toCollection(LinkedHashSet::new));
      List<String> toRemove = new ArrayList<>(registeredNames);
      toRemove.removeAll(desired);
      registry.unregisterAll(toRemove);
      List<AgentTool> toAdd =
          tools.stream()
              .filter(t -> filter.test(t.name()))
              .filter(t -> !registeredNames.contains(t.name()))
              .toList();
      registry.registerAll(toAdd);
      registeredNames = desired;
      LOG.debug("工具源同步: source={} 现有 {} 个", source.id(), desired.size());
    }
  }

  /** 当前已同步到注册表的工具名（测试/审计）。 */
  public Set<String> synchronizedNames() {
    synchronized (lock) {
      return Set.copyOf(registeredNames);
    }
  }

  /** 解除绑定并整组下架本桥带入的工具（含撤销对源的事件订阅）。 */
  @Override
  public void close() {
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      registry.unregisterAll(registeredNames);
      registeredNames = Set.of();
    }
    try {
      subscription.close();
    } catch (Exception e) {
      LOG.debug("撤销工具源订阅失败（忽略）: {}", e.getMessage());
    }
  }
}
