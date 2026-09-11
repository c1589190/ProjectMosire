package io.mosire.agentlib.plugin;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.pf4j.JarPluginManager;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 插件工具源（二期 D13 / P2-7）：扫描构造注入的 {@code plugins/} 目录，把其中的插件 JAR 装载成一个个工具供给源， 其声明的工具按源登记进全局 {@link
 * ToolRegistry}；{@code disable} 时整组摘除。
 *
 * <p><b>生命周期</b>（每步都有测试 pin）：
 *
 * <ol>
 *   <li>{@link #loadAll()} 扫描目录里的 {@code *.jar}（按文件名序，确定性），逐个 {@code loadPlugin → startPlugin}；
 *   <li>取该插件的 {@link ToolSource} 扩展（扩展点即插件 API，见 {@code ToolSource} 接口注释），校验其声明的 {@code
 *       id()}（见下"归属校验"）；
 *   <li>以<b>该 id 为 sourceId</b> 逐个 {@code registry.register(sourceId, tool)}（<b>不是</b> {@code
 *       registerAll}，见下"注册路径"）；
 *   <li>{@link #disable(String)}：按本类亲自登记过的 sourceId {@code unregisterAllBySource} 整组摘除工具 → 停止 →
 *       卸载（释放 PluginClassLoader 与 JAR 句柄）；{@link #enable(String)} 从原 JAR 重新装载。
 * </ol>
 *
 * <p><b>信任边界（红线 4，勿误读）</b>：PF4J 的 {@code PluginClassLoader} <b>不是安全边界</b>——它只隔离类与依赖版本冲突；
 * 插件与宿主<b>同进程、同 JVM、同权限</b>，能读环境变量、读写文件、起子进程。因此 <b>{@code plugins/} 目录只应放入自己审计过的代码</b>
 * （运维前提，不是可选建议）；本类提供的一切"隔离"均仅指类加载维度。真正的信任边界是<b>进程边界</b>（子 Agent 形态），进程内插件不适用。
 *
 * <p><b>注册路径与 MCP 的已知分叉</b>：本类走 {@code register(sourceId, tool)}，与 {@code App}
 * 内建源同路，故"按源卸载"对本源与内建源成立； 而 {@code McpSourceBridge} 走的是 {@code registerAll}（不带 sourceId，落无源哨兵）+ 按名字
 * diff 下架——<b>对 MCP 源不成立</b>。 本类不重构 MCP 侧（避免无谓回归），该分叉须由 {@code source_list} 一类消费方知晓（见 {@link
 * ToolRegistry#sourceIds()} 的两层说明）。
 *
 * <p><b>归属校验（防 id 冒用）</b>：{@code ToolSource.id()} 的唯一性只是接口约定，{@code ToolRegistry} 不校验；若插件声明 {@code
 * id()="builtin"}，一次 {@code unregisterAllBySource("builtin")} 就会误删内建工具。故本类装载前校验声明的 id：<b>空白</b>、
 * <b>与当前在册源冲突</b>（含内建 {@value #HOST_BUILTIN_SOURCE_ID}）、<b>与本实例已装载/已停用插件冲突</b>三者一律
 * <b>拒绝装载该插件并响亮报错</b>（不静默跳过、不静默改名——改名会让审计与 {@code source_*} 的名字对不上）；{@code disable} 只按本类亲自登记过的
 * (pluginId → sourceId) 映射卸载，<b>不接受外部传入的任意 id</b>。
 *
 * <p><b>本阶段不做</b>（YAGNI，明确边界）：<b>不支持原地重载</b>（reload/热替换有类加载器泄漏风险，属未验证区；运行中新增的 JAR 需重启或再次 {@link
 * #loadAll()}）；<b>不做任何按 Agent 的放行判断</b>——工具进 registry 只等于"全局可用"，某 Agent 能否调用由其 L3 白名单决定，本类
 * <b>不</b>把插件工具加进任何 Agent 的白名单；不改 {@code App}/{@code Brain}/{@code Main}（装配归启动装配任务）。
 *
 * <p><b>插件 JAR 的形态</b>（本仓库实测 PF4J {@value #PF4J_PROBE_VERSION}，全离线）：JAR 的 {@code
 * META-INF/MANIFEST.MF} 须含 {@code Plugin-Id}/{@code Plugin-Version}（描述符；缺失则 PF4J
 * <b>只记日志并静默跳过</b>，本类据此响亮报错）， 且类路径下须有 {@code META-INF/extensions.idx}——PF4J 3.x 默认装配的 {@code
 * IndexedExtensionFinder} <b>只认这条索引</b>，APT 处理器 {@code
 * org.pf4j.processor.ExtensionAnnotationProcessor}（随 pf4j jar 自带）会为 {@code @Extension} 类生成它；实测仅放
 * {@code META-INF/services/...}（ServiceLoader 形态）<b>不会被发现</b>。
 *
 * <p>状态变更经注入的 {@link PluginListener} 外发（事件类型与落库在宿主侧，本类不写事件字符串）。
 */
public final class PluginToolSource implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(PluginToolSource.class);

  /** 宿主内建源的 id（{@code App} 以 {@code new BuiltinToolSource("builtin", …)} 装配）；插件不得冒用，故列为保留名。 */
  public static final String HOST_BUILTIN_SOURCE_ID = "builtin";

  /** 只把该后缀的文件当插件（大小写不敏感）。 */
  private static final String JAR_SUFFIX = ".jar";

  /** R7 实测结论所依据的 PF4J 版本（写进文档便于复核，改动依赖版本时须重跑实测）。 */
  private static final String PF4J_PROBE_VERSION = "3.15.1";

  /** 保留名：宿主内建源 id —— 即使此刻 registry 里还没有内建工具，插件也不得占用（否则内建工具随后注册进来就会与插件同源）。 */
  private static final Set<String> RESERVED_SOURCE_IDS = Set.of(HOST_BUILTIN_SOURCE_ID);

  private final Path pluginsDir;
  private final ToolRegistry registry;
  private final PluginListener listener;
  private final PluginManager plugins;

  /** 已发现的插件：pluginId → 登记项（含已 disable 的——它们不在 registry 里，只能由本表回答）。 */
  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  /** 私有锁对象（SpotBugs USO：勿外泄类固有锁）：装载/启停/关闭串行化，避免半装载状态对外可见。 */
  private final Object lock = new Object();

  private volatile boolean closed;

  /**
   * @param pluginsDir 插件目录：<b>显式注入</b>（不读 cwd、不读环境变量）；须已存在的目录，其中的 {@code *.jar} 视为插件
   * @param registry 目标注册表（工具按源登记于此）
   * @param listener 生命周期监听（{@link PluginListener#none()} = 不监听）
   */
  public PluginToolSource(Path pluginsDir, ToolRegistry registry, PluginListener listener) {
    Objects.requireNonNull(pluginsDir, "pluginsDir");
    if (!Files.isDirectory(pluginsDir)) {
      throw new IllegalArgumentException("插件目录不存在或不是目录: " + pluginsDir);
    }
    this.pluginsDir = pluginsDir;
    this.registry = Objects.requireNonNull(registry, "registry");
    this.listener = Objects.requireNonNull(listener, "listener");
    this.plugins = new JarPluginManager(pluginsDir);
  }

  /** 插件目录（构造注入的那个，只读）。 */
  public Path pluginsDir() {
    return pluginsDir;
  }

  /**
   * 扫描目录并启用全部尚未生效的插件（启动装配入口）：对每个 {@code *.jar} 走"装载 → 启动 → 校验 id → 登记工具"，返回本次成功启用的
   * pluginId。已在本实例清单里的插件（无论启用还是已 disable）原样跳过——<b>disable 是运维意图，不会被本方法悄悄撤销</b>。
   *
   * <p><b>失败语义</b>：单个插件失败（坏 JAR / 缺描述符 / 无扩展点 / id 冲突 / 工具名冲突）不阻断其余插件与其它供给源；本方法在遍历结束后把全部失败
   * <b>聚合抛</b> {@link PluginLoadException}（每项含 jar 文件名与原因）——响亮失败，绝不静默跳过。
   *
   * @return 本次启用的 pluginId（按 jar 文件名序）
   * @throws PluginLoadException 有任一插件装载失败（消息含全部失败项）
   * @throws IllegalStateException 已 {@link #close()}
   */
  public List<String> loadAll() {
    synchronized (lock) {
      ensureOpen();
      List<String> enabled = new ArrayList<>();
      List<String> failures = new ArrayList<>();
      for (Path jar : jars()) {
        if (known(jar)) {
          LOG.debug("插件已在本实例清单里，跳过: {}", jar.getFileName());
          continue;
        }
        try {
          enabled.add(startJar(jar));
        } catch (PluginLoadException e) {
          // 单个插件的失败不污染其它插件与其它源；聚合后统一响亮抛出（每项都带 jar 文件名，便于定位）
          failures.add(jar.getFileName() + ": " + e.getMessage());
        }
      }
      if (!failures.isEmpty()) {
        throw new PluginLoadException(failures);
      }
      return List.copyOf(enabled);
    }
  }

  /**
   * 启用一个已发现但已停用的插件（从原 JAR 重新装载；再次校验 id 与工具名冲突）。已是启用态则幂等返回。
   *
   * @throws IllegalArgumentException 未知 pluginId（含把 sourceId 当 pluginId 传错的情形；也绝不会接受 {@code
   *     "builtin"} 这类外部 id）
   * @throws PluginLoadException 重新装载失败（失败即回滚，不留半装载）
   * @throws IllegalStateException 已 {@link #close()}
   */
  public void enable(String pluginId) {
    synchronized (lock) {
      ensureOpen();
      Entry entry = requireKnown(pluginId);
      if (entry.started()) {
        return;
      }
      startJar(entry.jar());
    }
  }

  /**
   * 停用一个插件：按<b>本类亲自登记过的</b> sourceId 整组摘除其工具 → 停止 → 卸载（释放类加载器/JAR 句柄）。其它源的工具一条不动。 已是停用态则幂等返回。
   *
   * @throws IllegalArgumentException 未知 pluginId —— 本方法<b>不接受</b>任意 id 去整组卸载（防冒用：传 {@code
   *     "builtin"} 只会得到 这个异常，内建工具不受影响）
   * @throws IllegalStateException 已 {@link #close()}
   */
  public void disable(String pluginId) {
    synchronized (lock) {
      ensureOpen();
      Entry entry = requireKnown(pluginId);
      if (!entry.started()) {
        return;
      }
      stopAndUnregister(entry);
    }
  }

  /**
   * 已发现插件的状态清单（<b>含已 disable 的</b>；按 pluginId 排序）。两层信息分工：registry 只知道当前在册的源（{@link
   * ToolRegistry#sourceIds()}），已停用插件的工具已摘除、不在 registry 里，只能由本清单回答；{@code source_list} 一类消费方是两层的合并。
   *
   * <p>装载失败的插件<b>不</b>出现在清单里（失败是响亮的异常与日志，不是清单里一行静默状态）；{@link #close()} 后清单仍保留（全部为 {@code STOPPED}）。
   */
  public List<PluginStatus> list() {
    synchronized (lock) {
      List<PluginStatus> snapshot = new ArrayList<>();
      for (Entry entry : entries.values()) {
        snapshot.add(entry.toStatus());
      }
      snapshot.sort(Comparator.comparing(PluginStatus::pluginId));
      return List.copyOf(snapshot);
    }
  }

  /**
   * 停用全部已启用的插件（工具整组摘除、类加载器释放）并关闭本实例；此后 {@link #loadAll()}/{@link #enable}/{@link #disable} 抛 {@link
   * IllegalStateException}，{@link #list()} 仍可读。幂等。
   */
  @Override
  public void close() {
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      for (Entry entry : entries.values()) {
        if (entry.started()) {
          stopAndUnregister(entry);
        }
      }
      // 兜底：把 PF4J 侧残留的插件全部卸载（本类清单之外的、被外部装载进来的）
      plugins.unloadPlugins();
    }
  }

  // ---------- 内部实现 ----------

  /** 目录里的插件 JAR（按文件名序，确定性；非 jar 文件忽略）。 */
  private List<Path> jars() {
    try (Stream<Path> listing = Files.list(pluginsDir)) {
      return listing
          .filter(Files::isRegularFile)
          .filter(p -> fileNameOf(p).toLowerCase(Locale.ROOT).endsWith(JAR_SUFFIX))
          .sorted(Comparator.comparing(PluginToolSource::fileNameOf))
          .toList();
    } catch (IOException e) {
      throw new PluginLoadException("插件目录不可读: " + pluginsDir, e);
    }
  }

  /** 文件名（{@code getFileName()} 对根路径可为 null，这里统一兜底；比较/匹配都用它，避免对 null 解引用）。 */
  private static String fileNameOf(Path path) {
    Path name = path.getFileName();
    return name == null ? path.toString() : name.toString();
  }

  /** 该 JAR 是否已在本实例清单里（启用或已停用——disable 是运维意图，loadAll 不撤销它）。 */
  private boolean known(Path jar) {
    for (Entry entry : entries.values()) {
      if (entry.jar().equals(jar)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 装载并启用一个 JAR：loadPlugin → startPlugin → 取 ToolSource 扩展 → 校验 id → 登记工具 → 记清单 → 通知。任何一步失败都回滚
   * （停止+卸载+不记清单）并抛 {@link PluginLoadException}。
   */
  private String startJar(Path jar) {
    String pluginId;
    try {
      pluginId = plugins.loadPlugin(jar);
    } catch (RuntimeException e) {
      // 实测：JAR 不可读/描述符非法 → 返回 null（只记日志）；而 Plugin-Id 与已装载插件重复 → 抛 PluginRuntimeException
      // （抛出点在创建类加载器之前，故不污染已装载的那个插件）。两种都归到响亮失败。
      throw new PluginLoadException(
          "插件装载失败（JAR 不可读 / 描述符非法 / Plugin-Id 与已装载插件重复）: "
              + jar.getFileName()
              + " —— "
              + e.getMessage(),
          e);
    }
    if (pluginId == null) {
      // PF4J 对"无 Plugin-Id 描述符/坏 JAR"只记日志并静默跳过——本类必须自己把静默变响亮
      throw new PluginLoadException(
          "插件未装载（PF4J 未识别）: "
              + jar.getFileName()
              + " —— 检查 META-INF/MANIFEST.MF 是否含 Plugin-Id/Plugin-Version");
    }
    Entry prior = entries.get(pluginId);
    if (prior != null && !prior.jar().equals(jar)) {
      // 兜底（正常由 PF4J 在 loadPlugin 处抛错拦住）：不得回滚已合法装载的那个插件
      throw new PluginLoadException(
          "插件 id 重复: "
              + pluginId
              + " 已被 "
              + prior.jar().getFileName()
              + " 占用（"
              + jar.getFileName()
              + " 被拒）");
    }
    String version = versionOf(pluginId);
    try {
      PluginState state = plugins.startPlugin(pluginId);
      if (state != PluginState.STARTED) {
        throw new PluginLoadException("插件启动失败: " + pluginId + " state=" + state);
      }
      List<ToolSource> sources;
      try {
        sources = plugins.getExtensions(ToolSource.class, pluginId);
      } catch (RuntimeException e) {
        throw new PluginLoadException("插件扩展点实例化失败: " + pluginId, e);
      }
      if (sources.size() != 1) {
        throw new PluginLoadException(
            "插件必须恰好提供一个 ToolSource 扩展（实际 "
                + sources.size()
                + " 个）: "
                + pluginId
                + " —— 检查 JAR 内 @Extension 生成的 META-INF/extensions.idx 是否缺失，或扩展类是否可实例化");
      }
      String sourceId = requireUsableSourceId(pluginId, sources.get(0).id(), jar);
      List<String> toolNames = registerTools(pluginId, sourceId, sources.get(0).listTools());
      entries.put(pluginId, new Entry(pluginId, jar, version, sourceId, true));
      LOG.info("插件启用: {} v{} sourceId={} 工具 {} 个", pluginId, version, sourceId, toolNames.size());
      notifyListener(pluginId, version, PluginListener.State.STARTED);
      return pluginId;
    } catch (RuntimeException e) {
      // 回滚：不留"已启动但未记清单"或"记了清单但工具没登记全"的半装载状态
      entries.remove(pluginId);
      rollback(pluginId, version);
      notifyListener(pluginId, version, PluginListener.State.FAILED);
      // 归一为本类自己的失败类型（本方法对外的失败契约是 PluginLoadException，不把 PF4J 的原始类型透给调用方）
      throw e instanceof PluginLoadException loadFailure
          ? loadFailure
          : new PluginLoadException("插件启用失败: " + pluginId + " —— " + e.getMessage(), e);
    }
  }

  /** 停用：按本源登记过的 id 整组摘工具 → 停 → 卸载（释放类加载器/JAR 句柄）→ 记状态 → 通知。 */
  private void stopAndUnregister(Entry entry) {
    int removed = registry.unregisterAllBySource(entry.sourceId());
    try {
      PluginState state = plugins.stopPlugin(entry.pluginId());
      if (state != PluginState.STOPPED) {
        LOG.warn("插件停止后状态非 STOPPED: {} state={}", entry.pluginId(), state);
      }
      if (!plugins.unloadPlugin(entry.pluginId())) {
        LOG.warn("插件未能卸载（类加载器未释放）: {}", entry.pluginId());
      }
    } finally {
      entries.put(entry.pluginId(), entry.stopped());
    }
    LOG.info("插件停用: {} sourceId={} 摘除工具 {} 个", entry.pluginId(), entry.sourceId(), removed);
    notifyListener(entry.pluginId(), entry.version(), PluginListener.State.STOPPED);
  }

  /** 失败回滚：停止+卸载（best effort，不掩盖原始失败）。 */
  private void rollback(String pluginId, String version) {
    try {
      plugins.stopPlugin(pluginId);
      plugins.unloadPlugin(pluginId);
    } catch (RuntimeException e) {
      LOG.warn("插件失败回滚异常（忽略，原始失败已上报）: {}", pluginId, e);
    }
    LOG.warn("插件未启用（已回滚）: {} v{}", pluginId, version);
  }

  /** 归属校验（R3）：空白 / 保留名（内建源）/ 与在册源冲突 / 与本实例清单冲突 → 拒绝装载并响亮报错。 */
  private String requireUsableSourceId(String pluginId, String sourceId, Path jar) {
    if (sourceId == null || sourceId.isBlank()) {
      throw new PluginLoadException(
          "插件声明的 sourceId 为空白: " + pluginId + " —— 空白会命中 ToolRegistry 的无源哨兵（整组卸载会误伤内建工具），拒绝装载");
    }
    if (RESERVED_SOURCE_IDS.contains(sourceId)) {
      throw new PluginLoadException(
          "插件冒用宿主保留的 sourceId: "
              + sourceId
              + "（插件 "
              + pluginId
              + " / "
              + jar.getFileName()
              + "）—— 拒绝装载");
    }
    Set<String> registered = registry.sourceIds();
    if (registered.contains(sourceId)) {
      throw new PluginLoadException(
          "插件声明的 sourceId 与当前在册的供给源冲突: "
              + sourceId
              + "（插件 "
              + pluginId
              + " / "
              + jar.getFileName()
              + "；在册: "
              + registered
              + "）—— 拒绝装载（不静默改名：改名会让审计与 source_* 的名字对不上）");
    }
    for (Entry entry : entries.values()) {
      if (sourceId.equals(entry.sourceId()) && !pluginId.equals(entry.pluginId())) {
        throw new PluginLoadException(
            "插件声明的 sourceId 与本实例已发现的插件冲突: "
                + sourceId
                + "（"
                + pluginId
                + " / "
                + jar.getFileName()
                + " 与 "
                + entry.pluginId()
                + "）—— 后到者被拒");
      }
    }
    return sourceId;
  }

  /** 以 sourceId 逐个登记：全有或全无——中途失败（名字重复等）回滚本批，绝不留半个插件的工具在册。 */
  private List<String> registerTools(String pluginId, String sourceId, List<AgentTool> tools) {
    List<AgentTool> declared = Objects.requireNonNull(tools, "listTools() 不得返回 null");
    List<String> registered = new ArrayList<>();
    try {
      for (AgentTool tool : declared) {
        registry.register(sourceId, Objects.requireNonNull(tool, "listTools() 不得含 null 元素"));
        registered.add(tool.name());
      }
    } catch (RuntimeException e) {
      registry.unregisterAll(registered); // 只回滚本批刚登记的名字，不碰其它源
      throw new PluginLoadException("插件工具登记失败（名字冲突？）: " + pluginId + " sourceId=" + sourceId, e);
    }
    return List.copyOf(registered);
  }

  private Entry requireKnown(String pluginId) {
    Objects.requireNonNull(pluginId, "pluginId");
    Entry entry = entries.get(pluginId);
    if (entry == null) {
      throw new IllegalArgumentException(
          "未知插件: "
              + pluginId
              + "（本实例已发现: "
              + entries.keySet()
              + "；运行中新增的 JAR 需再次 loadAll，本阶段不支持热重载）");
    }
    return entry;
  }

  private String versionOf(String pluginId) {
    PluginWrapper wrapper = plugins.getPlugin(pluginId);
    return wrapper == null ? "" : wrapper.getDescriptor().getVersion();
  }

  /** 通知监听器：异常只记日志，不回滚已生效的状态（与 {@code ToolRegistry.onChange} 同口径）。 */
  private void notifyListener(String pluginId, String version, PluginListener.State state) {
    try {
      listener.onStateChanged(pluginId, version, state);
    } catch (RuntimeException e) {
      LOG.warn("插件监听器异常（忽略）: pluginId={} state={}", pluginId, state, e);
    }
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("PluginToolSource 已关闭");
    }
  }

  /** 一个已发现插件的登记项（不可变；状态变更整项替换，避免读到半更新状态）。 */
  private record Entry(
      String pluginId, Path jar, String version, String sourceId, boolean started) {

    PluginStatus toStatus() {
      return new PluginStatus(
          pluginId,
          sourceId,
          version,
          started ? PluginListener.State.STARTED : PluginListener.State.STOPPED);
    }

    Entry stopped() {
      return new Entry(pluginId, jar, version, sourceId, false);
    }
  }

  /** 插件状态快照（{@code source_list} 的第二层信息，见 {@link #list()}）。 */
  public record PluginStatus(
      String pluginId, String sourceId, String version, PluginListener.State state) {}

  /** 插件装载失败（坏 JAR / 缺描述符 / 无 ToolSource 扩展 / id 冲突 / 工具名冲突）：一律响亮抛出，绝不静默跳过。 */
  public static final class PluginLoadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 聚合多项失败时逐项列出（{@link #loadAll()} 的用法）；单项失败时为空表。 */
    private final List<String> failures;

    public PluginLoadException(String message) {
      this(message, List.of(), null);
    }

    public PluginLoadException(String message, Throwable cause) {
      this(message, List.of(), cause);
    }

    /** 聚合失败：消息形如"插件装载失败 N 项: 每项含 jar 文件名与原因"。 */
    public PluginLoadException(List<String> failures) {
      super("插件装载失败 " + failures.size() + " 项: " + String.join(" | ", failures));
      this.failures = List.copyOf(failures);
    }

    private PluginLoadException(String message, List<String> failures, Throwable cause) {
      super(message, cause);
      this.failures = failures;
    }

    /** 逐项失败描述（聚合场景非空；{@link #loadAll()} 遍历结束后一次性抛出）。 */
    public List<String> failures() {
      return List.copyOf(failures); // 防御性拷贝：不把内部表示暴露给调用方
    }
  }
}
