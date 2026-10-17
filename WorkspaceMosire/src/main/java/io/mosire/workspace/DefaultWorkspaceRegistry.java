package io.mosire.workspace;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 标准工作区注册表（设计 §4 的实现）。
 *
 * <p><b>结构一眼看全</b>：挂载表 = "使用者身份键 → （挂载名 → 挂载）"，按使用者分片（{@link Mount#user()} 是解析索引）；
 * 每个挂载自己记住创建者（{@link Mount#holder()}，生命周期权限）。因此"同一虚拟挂载名"在不同使用者下不冲突—— 设计 §4.7 的递归结构（main / A / B
 * 各自有一个 {@code workspace}）就是靠这条成立的。
 *
 * <p><b>并发</b>：挂载表在父进程内存；写（mount/unmount）由一把锁串行，读走 {@link ConcurrentHashMap} 的弱一致视图 （设计
 * §4.9："单写者：工具入口串行"）。物理目录是真值，本类只登记不搬运。
 *
 * <p><b>不创建物理目录</b>：登记不产生文件系统副作用（否则"被拒却留下半个目录"会成为可能）；目录由写文档/装配的动作 自己建。
 *
 * <p><b>血缘映射</b>：M2/M5/M9 的"自己或后代"判定用血缘路径。身份键与血缘路径的对应由装配方经 {@code lineagePathOf} 注入（本模块不得依赖
 * Brain）；缺省 = 身份键就是血缘路径，此时只有"自己"能匹配（fail-closed）。
 *
 * <p><b>W3（深度）不在这里实现</b>：设计明写"深度复用既有子 Agent 深度限制（{@code subagents.maxDepth}），不新增第二个
 * 深度旋钮"——工作区模块没有也不该有这个配置，深度闸在派生链（spawn）那一侧。
 */
public final class DefaultWorkspaceRegistry implements WorkspaceRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultWorkspaceRegistry.class);

  /** 运行期审计留痕上限：长跑进程里 {@link #events()} 不该无界增长（超出丢最旧；事件库的持久留痕由注入的汇聚点负责）。 */
  public static final int EVENT_HISTORY_LIMIT = 4096;

  private final CapabilityCatalog catalog;
  private final Consumer<WorkspaceEvent> sink;
  private final Function<String, String> lineagePathOf;

  /** 使用者身份键 → （挂载名 → 挂载）。 */
  private final Map<String, Map<MountName, Mount>> slices = new ConcurrentHashMap<>();

  private final Deque<WorkspaceEvent> history = new ConcurrentLinkedDeque<>();

  /** mount/unmount 的原子化锁（"查重名 → 写入"这类组合动作必须整体成立）。 */
  private final Object lock = new Object();

  /** 注入的是<b>装配方持有</b>的汇聚点与血缘映射：本类只调用它们，不修改、不外传（工作区标准不得依赖事件库或 Brain）。 */
  private DefaultWorkspaceRegistry(
      CapabilityCatalog catalog,
      Consumer<WorkspaceEvent> sink,
      Function<String, String> lineagePathOf) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.sink = sink;
    this.lineagePathOf = lineagePathOf;
  }

  /**
   * 建一个注册表（完整形态）。
   *
   * @param catalog 能力目录（装配期注册好；本模块不注册任何能力）
   * @param sink 审计事件汇聚点（{@code null} = 只留 {@link #events()} 的内存留痕；R3b 接到事件库）
   * @param lineagePathOf 身份键 → 血缘路径（{@code main} / {@code main/a1} / …）；{@code null} 或返回空 ⇒ 解析不出，
   *     M2/M5 的子树判定一律判否（fail-closed）
   */
  public static DefaultWorkspaceRegistry create(
      CapabilityCatalog catalog,
      Consumer<WorkspaceEvent> sink,
      Function<String, String> lineagePathOf) {
    return new DefaultWorkspaceRegistry(catalog, sink, lineagePathOf);
  }

  /** 缺省装配：血缘路径 = 身份键本身（只有"自己"能匹配），无事件汇聚点。 */
  public static DefaultWorkspaceRegistry create(
      CapabilityCatalog catalog, Consumer<WorkspaceEvent> sink) {
    return new DefaultWorkspaceRegistry(catalog, sink, null);
  }

  /** 缺省装配（无常驻事件汇聚点）：血缘路径 = 身份键本身。 */
  public static DefaultWorkspaceRegistry create(CapabilityCatalog catalog) {
    return new DefaultWorkspaceRegistry(catalog, null, null);
  }

  // ---------------------------------------------------------------------------------------------
  // 挂载表：mount / unmount / mounts
  // ---------------------------------------------------------------------------------------------

  @Override
  public Mount mount(Mount spec, WorkspaceCaller caller) {
    Objects.requireNonNull(spec, "spec");
    Objects.requireNonNull(caller, "caller");
    synchronized (lock) {
      // M2：holder 恒为创建者本人（不许代他人署名，N11）
      if (!caller.holder().equals(spec.holder())) {
        throw deny(
            "M2",
            caller,
            spec.user(),
            spec.name().value(),
            "不能以他人的名义创建工作区（holder 恒为创建者本人）",
            "spec.holder=" + spec.holder());
      }
      // M2：user 只能是自己或自己的后代（血缘子树口径）
      if (!Lineage.inSubtree(lineageOf(caller.holder()), lineageOf(spec.user()))) {
        throw deny(
            "M2",
            caller,
            spec.user(),
            spec.name().value(),
            "只能为自己或自己的后代创建工作区（user 必须落在自己的血缘子树内）",
            "callerPath=" + lineageOf(caller.holder()) + " userPath=" + lineageOf(spec.user()));
      }
      // M3：能力 id 必须已注册（未注册的能力不落格，声明它等于给一个假承诺）
      for (String id : spec.capabilities()) {
        if (catalog.get(id).isEmpty()) {
          throw deny(
              "M3",
              caller,
              spec.user(),
              spec.name().value(),
              "挂载声明了未注册的能力 id: " + id,
              "capability=" + id);
        }
      }
      // M1/A2：物理范围 ⊆ 调用者在 workspaces 命名空间的表态范围（缺省 DENY）
      if (!WorkspacePermissions.canMountWithin(caller.permissions(), spec.dir())) {
        throw deny(
            "M1",
            caller,
            spec.user(),
            spec.name().value(),
            "物理范围不在调用者的 workspaces 可达面内（缺省 DENY：未表态 = 一个挂载都建不了）",
            "dir="
                + spec.dir()
                + " workspaces="
                + WorkspacePermissions.mountFace(caller.permissions()).summary());
      }
      // M1 第二半 / M3：能力落格后必须仍 ⊆ 调用者面（能力不能放大自己已有的权利）
      for (String id : spec.capabilities()) {
        Capability capability = catalog.get(id).orElseThrow();
        for (String namespace : capability.namespaceNames()) {
          if (!WorkspacePermissions.covered(
              WorkspacePermissions.declared(caller.permissions(), namespace), spec.dir())) {
            throw deny(
                "M1/M3",
                caller,
                spec.user(),
                spec.name().value(),
                "能力 " + id + " 落格的命名空间 " + namespace + " 覆盖不到该物理范围（能力不得超出既有权利）",
                "dir=" + spec.dir() + " namespace=" + namespace);
          }
        }
      }
      // M1 的真实路径补充：挂载根可能是软链；声明的词法落点在面内，不代表真实落点也在面内。
      // 这一判只在挂载期做——resolveForIo 的读取者不需要 workspaces（创建挂载）面。
      VirtualPath mountRoot = new VirtualPath(spec.name(), "");
      Path realDir = realMountRoot(spec, caller, mountRoot);
      admitMountFaceRealRoot(spec, caller, mountRoot, realDir);
      admitRealRoot(spec, caller, mountRoot, realDir);

      // M9/W1/W2/M11/M12：落点在自己某个工作区目录之内时，只能更小、只能更窄
      Mount own = ownMountContaining(caller.holder(), spec.dir());
      if (own != null) {
        if (!own.strictlyContains(spec.dir())) {
          throw deny(
              "M9/W1",
              caller,
              spec.user(),
              spec.name().value(),
              "子工作区必须是自己的目录下的真子目录（等于自己的工作区根 = 把权限整份复制）",
              "dir=" + spec.dir() + " parentDir=" + own.dir());
        }
        requireNarrower(caller, spec, own);
      }
      // M10（本实现的 fail-closed 加固，不是设计新增的判据，控制方可否决）：派生关系必须指向真实存在的挂载——
      // derivedFrom 指一个不存在的名字等于"以为会级联、实际不会"的静默失效，那正是本次修订要消灭的含糊。
      // 引用范围限定在创建者自己的切片（口径与 cascade 的 derivedFrom(Mount) 一致）。
      if (spec.derivedFrom().isPresent()) {
        Mount source = lookup(spec.holder(), spec.derivedFrom().get());
        if (source == null) {
          throw deny(
              "M10",
              caller,
              spec.user(),
              spec.name().value(),
              "声明的派生来源挂载不在创建者自己名下（derivedFrom 只能指向你自己切片里已有的挂载）",
              "derivedFrom=" + spec.derivedFrom().get());
        }
      }
      // M4/A4：同一使用者下虚拟挂载名唯一（具名拒，不静默替换、不覆盖他人挂载）
      Mount occupied = lookup(spec.user(), spec.name());
      if (occupied != null) {
        throw conflict(
            "M4",
            caller,
            spec.user(),
            spec.name().value(),
            "该使用者名下已有一个同名挂载（具名拒，不静默替换）",
            "dir=" + spec.dir() + " existing=" + occupied.dir());
      }
      // 校验都过了才建切片：被拒的挂载不该在表里留下空壳
      slices.computeIfAbsent(spec.user(), key -> new ConcurrentHashMap<>()).put(spec.name(), spec);
      emit(
          WorkspaceEvent.mounted(
              spec.holder(),
              spec.user(),
              spec.name().value(),
              "dir="
                  + spec.dir()
                  + " kind="
                  + spec.kind()
                  + " capabilities="
                  + spec.capabilities()
                  + " origin="
                  + spec.origin()
                  + " resolver="
                  + spec.resolver().getClass().getSimpleName()));
      return spec;
    }
  }

  @Override
  public void unmount(MountName name, WorkspaceCaller caller) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(caller, "caller");
    synchronized (lock) {
      // ① 先在自己的切片（user == caller.holder）里找：虚拟名在使用者切片内唯一，这一步是无歧义的
      Map<MountName, Mount> own = slices.get(caller.holder());
      Mount target = own == null ? null : own.get(name);
      if (target != null) {
        // M5：卸载只允许 holder（创建者）或其祖先执行
        if (!Lineage.inSubtree(lineageOf(caller.holder()), lineageOf(target.holder()))) {
          throw deny(
              "M5",
              caller,
              target.user(),
              name.value(),
              "只有创建者或其祖先能卸载这个挂载",
              "mount.holder=" + target.holder() + " dir=" + target.dir());
        }
        remove(target, "调用者卸载");
        return;
      }
      // ② 再找"由自己创建"的挂载（跨切片，holder == caller.holder）：祖先卸载子体的工作区走这一档
      List<Mount> created = createdBy(caller.holder(), name);
      if (created.isEmpty()) {
        throw notFound(caller, name, "该调用者的挂载表里没有这个挂载（与\"无权\"同码，不泄露存在性）");
      }
      if (created.size() > 1) {
        throw deny(
            "M5",
            caller,
            "",
            name.value(),
            "有多条同名挂载由你创建，无法确定卸载哪一条（不猜）",
            "matches=" + created.size());
      }
      remove(created.get(0), "调用者卸载（由自己创建，跨使用者切片）");
    }
  }

  @Override
  public List<Mount> mounts(String holder) {
    String key = holder == null ? "" : holder.strip();
    if (key.isEmpty()) {
      return List.of();
    }
    Map<MountName, Mount> slice = slices.get(key);
    if (slice == null) {
      return List.of();
    }
    List<Mount> out = new ArrayList<>(slice.values());
    out.sort(Comparator.comparing(mount -> mount.name().value()));
    return List.copyOf(out);
  }

  // ---------------------------------------------------------------------------------------------
  // 唯一解析点：resolve / list
  // ---------------------------------------------------------------------------------------------

  @Override
  public Path resolve(String virtualPath, WorkspaceCaller caller) {
    Objects.requireNonNull(caller, "caller");
    // ① 拆成（挂载名, 相对路径）
    VirtualPath parsed = VirtualPath.parse(virtualPath);
    // ② 在 caller 的挂载表里找该挂载（找不到 = 与"无权"同码，不泄露存在性）
    Mount mount = lookup(caller.holder(), parsed.mount());
    if (mount == null) {
      throw notFound(caller, parsed.mount(), "虚拟路径=" + parsed.label());
    }
    // ③ 准入：调用者面是否持有该挂载的能力
    admit(mount, caller, parsed);
    // ④ 映射策略
    Path resolved = mount.resolver().resolve(mount.dir(), parsed.relative());
    if (resolved == null || !resolved.isAbsolute()) {
      throw deny(
          "G1",
          caller,
          mount.user(),
          mount.name().value(),
          "映射策略返回了非绝对路径（fail-closed，不替它猜）",
          "resolved=" + resolved);
    }
    // ⑤ containment：结果必须落在该挂载被授权的物理范围内（G1/G5）
    if (!mount.contains(resolved)) {
      throw deny(
          "G1",
          caller,
          mount.user(),
          mount.name().value(),
          "映射策略把虚拟路径解析到了该挂载的授权范围之外",
          "resolved=" + resolved + " mountDir=" + mount.dir());
    }
    return resolved;
  }

  @Override
  public ResolvedPath resolveForIo(
      String virtualPath, WorkspaceCaller caller, IoMode mode, SymlinkPolicy policy) {
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(policy, "policy");
    if (mode != IoMode.READ && policy != SymlinkPolicy.REJECT) {
      throw WorkspaceException.invalid("写路径不允许软链策略: " + policy);
    }

    VirtualPath parsed = VirtualPath.parse(virtualPath);
    Mount mount = lookup(caller.holder(), parsed.mount());
    if (mount == null) {
      throw notFound(caller, parsed.mount(), "虚拟路径=" + parsed.label());
    }
    admit(mount, caller, parsed);

    Path mapped = mappedPath(mount, parsed, caller);
    Path realRoot = realMountRoot(mount, caller, parsed);
    admitRealRoot(mount, caller, parsed, realRoot);
    Path relative = mount.dir().relativize(mapped);
    return secureTarget(mount, parsed, mapped, relative, realRoot, mode, policy, caller);
  }

  @Override
  public List<String> list(String virtualPrefix, WorkspaceCaller caller) {
    Objects.requireNonNull(caller, "caller");
    VirtualPath parsed = VirtualPath.parse(virtualPrefix);
    Mount mount = lookup(caller.holder(), parsed.mount());
    if (mount == null) {
      throw notFound(caller, parsed.mount(), "虚拟前缀=" + parsed.label());
    }
    admit(mount, caller, parsed);
    // 目录列举也是文件系统读取：先过同一条安全解析（默认 REJECT）。
    // 否则“挂载内的软链目录”会把挂载外的条目投影出来——resolve 只承诺词法归属，不能当 IO 边界。
    resolveForIo(virtualPrefix, caller, IoMode.READ, SymlinkPolicy.REJECT);
    List<String> entries = mount.resolver().list(mount.dir(), parsed.relative());
    if (entries == null) {
      throw deny(
          "G1",
          caller,
          mount.user(),
          mount.name().value(),
          "映射策略返回了空表（null）——约定是空列表，不是 null",
          "prefix=" + parsed.label());
    }
    List<String> projected = new ArrayList<>(entries.size());
    for (String entry : entries) {
      // 投影输出只含虚拟路径（G4/A7）：执行侧扩展点的输出也要过拼法守卫
      projected.add(parsed.mount().value() + "/" + VirtualPath.requireRelativeEntry(entry));
    }
    projected.sort(null);
    return List.copyOf(projected);
  }

  /** 映射策略的出口（与 {@link #resolve} 同一份 containment 口径）：只做词法映射与挂载范围检查，不承诺软链安全。 */
  private Path mappedPath(Mount mount, VirtualPath parsed, WorkspaceCaller caller) {
    Path resolved = mount.resolver().resolve(mount.dir(), parsed.relative());
    if (resolved == null || !resolved.isAbsolute()) {
      throw deny(
          "G1",
          caller,
          mount.user(),
          mount.name().value(),
          "映射策略返回了非绝对路径（fail-closed，不替它猜）",
          "resolved=" + resolved);
    }
    Path normalized = resolved.normalize();
    if (!mount.contains(normalized)) {
      throw deny(
          "G1",
          caller,
          mount.user(),
          mount.name().value(),
          "映射策略把虚拟路径解析到了该挂载的授权范围之外",
          "resolved=" + normalized + " mountDir=" + mount.dir());
    }
    return normalized;
  }

  /**
   * 挂载根的真实路径换算：根存在取 realpath；根不存在取最近存在父目录的 realpath + 尾段。
   *
   * <p><b>只做 canonicalization，不做权限判定</b>：权限分成两种面，各自只在自己的动作上判——挂载期判 {@code workspaces}（“能在哪里建挂载”，见
   * {@link #admitMountFaceRealRoot}），解析期判 capability 面（“能读哪里”，见 {@link #admitRealRoot}）。把 {@code
   * workspaces} 面带到 {@link #resolveForIo} 会错误拒绝只有 capability 面、没有创建挂载面的读取者。
   */
  private Path realMountRoot(Mount mount, WorkspaceCaller caller, VirtualPath parsed) {
    Path declared = mount.dir();
    Path real;
    try {
      if (Files.exists(declared, LinkOption.NOFOLLOW_LINKS)) {
        real = declared.toRealPath();
        if (!Files.isDirectory(real)) {
          throw deny(
              "G1/real",
              caller,
              mount.user(),
              mount.name().value(),
              "挂载根的真实落点不是目录（fail-closed）",
              "realRoot=" + real);
        }
      } else {
        real = canonicalizeMissing(declared, caller, mount, parsed);
      }
    } catch (IOException e) {
      throw new WorkspaceException(
          WorkspaceException.RESOURCE_DENIED, "挂载根的真实路径解析失败（fail-closed）", e);
    }
    return real;
  }

  /**
   * M1 的真实路径补充，<b>只在挂载期调用</b>：声明的词法落点在面内，不代表真实落点也在面内（根经软链落到 {@code workspaces}
   * 面外时必须拒，软链不得放大创建挂载的权限）。
   */
  private void admitMountFaceRealRoot(
      Mount mount, WorkspaceCaller caller, VirtualPath parsed, Path realRoot) {
    if (!WorkspacePermissions.canMountWithin(caller.permissions(), realRoot)) {
      throw deny(
          "M1/real",
          caller,
          mount.user(),
          mount.name().value(),
          "挂载根的真实落点不在调用者的 workspaces 面内（软链不得放大权限）",
          "path=" + parsed.label() + " realRoot=" + realRoot);
    }
  }

  /**
   * 真实挂载根也必须落在调用者的 capability 面内；否则“挂载根是软链”就会绕过 {@code admit} 的词法准入。
   *
   * <p><b>空能力集不做能力检查</b>：空能力挂载允许登记（此时真实根检查只剩挂载期的 M1 钩子 {@link
   * #admitMountFaceRealRoot}），但它不落任何能力格、也不进遮蔽面；解析时由 {@link #admit} 以“没有声明任何能力”具名拒。因此这里没有能力命名空间
   * 可查，直接跳过即可——fail-closed 由解析期的 {@code admit} 保持。
   */
  private void admitRealRoot(
      Mount mount, WorkspaceCaller caller, VirtualPath parsed, Path realRoot) {
    if (mount.capabilities().isEmpty()) {
      return;
    }
    for (String id : mount.capabilities()) {
      Capability capability = catalog.get(id).orElse(null);
      if (capability == null) {
        continue;
      }
      if (WorkspacePermissions.admits(caller.permissions(), capability, realRoot)) {
        return;
      }
    }
    throw deny(
        "§4.5③/real",
        caller,
        mount.user(),
        mount.name().value(),
        "调用者的能力面覆盖不到挂载根的真实落点",
        "path=" + parsed.label() + " realRoot=" + realRoot);
  }

  /** 按 mode/policy 做真实路径检查并返回结果。 */
  private ResolvedPath secureTarget(
      Mount mount,
      VirtualPath parsed,
      Path physical,
      Path relative,
      Path realRoot,
      IoMode mode,
      SymlinkPolicy policy,
      WorkspaceCaller caller) {
    if (policy == SymlinkPolicy.RESOLVE_WITHIN_ROOT) {
      if (mode != IoMode.READ) {
        throw WorkspaceException.invalid("RESOLVE_WITHIN_ROOT 只允许用于 READ");
      }
      if (!Files.exists(physical, LinkOption.NOFOLLOW_LINKS)) {
        throw notFound(caller, parsed.mount(), "安全 IO 目标不存在: " + parsed.label());
      }
      Path real = realPath(physical, caller, mount, parsed);
      requireWithinRoot(real, realRoot, caller, mount, parsed);
      return new ResolvedPath(mount, relative.toString(), physical, real, true);
    }

    boolean exists = walkWithoutSymlinks(mount, physical, relative, mode, caller, parsed);
    if (mode == IoMode.READ || mode == IoMode.WRITE_EXISTING) {
      if (!exists) {
        throw notFound(caller, parsed.mount(), "安全 IO 目标不存在: " + parsed.label());
      }
      Path real = realPath(physical, caller, mount, parsed);
      requireWithinRoot(real, realRoot, caller, mount, parsed);
      return new ResolvedPath(mount, relative.toString(), physical, real, true);
    }

    // CREATE
    if (exists) {
      Path real = realPath(physical, caller, mount, parsed);
      requireWithinRoot(real, realRoot, caller, mount, parsed);
      return new ResolvedPath(mount, relative.toString(), physical, real, true);
    }
    Path real = canonicalizeMissing(physical, caller, mount, parsed);
    requireWithinRoot(real, realRoot, caller, mount, parsed);
    return new ResolvedPath(mount, relative.toString(), physical, real, false);
  }

  /**
   * 逐段检查软链。返回目标当前是否存在；一旦遇到不存在的段就停止（后续段不可能存在）。
   *
   * <p>写路径（WRITE_EXISTING / CREATE）与默认读路径都走这里；{@link SymlinkPolicy#RESOLVE_WITHIN_ROOT} 只在 READ
   * 分支单独处理，不经过本方法。
   */
  private boolean walkWithoutSymlinks(
      Mount mount,
      Path physical,
      Path relative,
      IoMode mode,
      WorkspaceCaller caller,
      VirtualPath parsed) {
    Path current = mount.dir();
    if (relative.toString().isEmpty()) {
      // 空相对路径 = 挂载根本身。Java 的 Path.of("") 也有 1 个空段，若不特判会把软链挂载根当成
      // “路径中含软链”拒掉；而契约 §9.4 明确：挂载根可以是软链（挂载期已核真实根在权限面内）。
      return Files.exists(current, LinkOption.NOFOLLOW_LINKS);
    }
    int segments = relative.getNameCount();
    for (int i = 0; i < segments; i++) {
      Path next = current.resolve(relative.getName(i));
      if (Files.isSymbolicLink(next)) {
        throw deny(
            "G1/symlink",
            caller,
            mount.user(),
            mount.name().value(),
            "路径中包含软链（默认拒绝；读路径可显式使用 RESOLVE_WITHIN_ROOT）",
            "path=" + parsed.label() + " segment=" + next.getFileName());
      }
      if (!Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
        return false;
      }
      if (i < segments - 1 && !Files.isDirectory(next)) {
        throw deny(
            "G1/not-directory",
            caller,
            mount.user(),
            mount.name().value(),
            "路径中间段不是目录（fail-closed）",
            "path=" + parsed.label());
      }
      current = next;
    }
    return Files.exists(current, LinkOption.NOFOLLOW_LINKS);
  }

  private Path realPath(Path physical, WorkspaceCaller caller, Mount mount, VirtualPath parsed) {
    try {
      return physical.toRealPath();
    } catch (IOException e) {
      throw notFound(caller, parsed.mount(), "安全 IO 目标无法解析真实路径: " + parsed.label());
    }
  }

  private void requireWithinRoot(
      Path real, Path realRoot, WorkspaceCaller caller, Mount mount, VirtualPath parsed) {
    if (!real.startsWith(realRoot)) {
      throw deny(
          "G1/real",
          caller,
          mount.user(),
          mount.name().value(),
          "真实路径落在挂载根之外（软链逃逸，fail-closed）",
          "path=" + parsed.label() + " real=" + real + " root=" + realRoot);
    }
  }

  /** 目标不存在时，找最近存在父目录的 realpath，再拼上尚不存在的尾段；尾段里出现软链一律拒。 */
  private Path canonicalizeMissing(
      Path path, WorkspaceCaller caller, Mount mount, VirtualPath parsed) {
    Path cursor = path;
    List<Path> tail = new ArrayList<>();
    while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
      Path name = cursor.getFileName();
      if (name == null) {
        break;
      }
      tail.add(0, name);
      cursor = cursor.getParent();
    }
    if (cursor == null) {
      throw deny(
          "G1/missing-parent",
          caller,
          mount.user(),
          mount.name().value(),
          "目标没有可解析的已存在父目录（fail-closed）",
          "path=" + parsed.label());
    }
    if (Files.isSymbolicLink(cursor)) {
      throw deny(
          "G1/symlink",
          caller,
          mount.user(),
          mount.name().value(),
          "最近存在父目录本身是软链（写路径拒绝）",
          "path=" + parsed.label() + " parent=" + cursor.getFileName());
    }
    if (!Files.isDirectory(cursor)) {
      throw deny(
          "G1/not-directory",
          caller,
          mount.user(),
          mount.name().value(),
          "最近存在父目录不是目录（fail-closed）",
          "path=" + parsed.label());
    }
    Path real;
    try {
      real = cursor.toRealPath();
    } catch (IOException e) {
      throw deny(
          "G1/real-parent",
          caller,
          mount.user(),
          mount.name().value(),
          "父目录真实路径解析失败（fail-closed）",
          "path=" + parsed.label());
    }
    for (Path segment : tail) {
      Path next = real.resolve(segment);
      if (Files.isSymbolicLink(next)) {
        throw deny(
            "G1/symlink",
            caller,
            mount.user(),
            mount.name().value(),
            "待创建路径尾段含软链（fail-closed）",
            "path=" + parsed.label());
      }
      real = next;
    }
    return real.normalize();
  }

  // ---------------------------------------------------------------------------------------------
  // 落格与遮蔽面
  // ---------------------------------------------------------------------------------------------

  @Override
  public AgentPermissionSet grant(String holder, AgentPermissionSet permissions) {
    Objects.requireNonNull(permissions, "permissions");
    String key = holder == null ? "" : holder.strip();
    if (key.isEmpty()) {
      throw WorkspaceException.invalid("落格的目标身份键（holder）不能为空");
    }
    AgentPermissionSet result = permissions;
    for (Mount mount : mounts(key)) {
      for (String id : mount.capabilities()) {
        Capability capability = catalog.get(id).orElse(null);
        if (capability == null) {
          LOG.warn(
              "挂载引用的能力 id 未注册，不落格（fail-closed）: holder={} mount={} capability={}",
              key,
              mount.name().value(),
              id);
          continue;
        }
        for (String namespace : capability.namespaceNames()) {
          // 落格只能"补进未表态"，**不得越过目标自己的显式表态**（控制方裁定，2026-10-13）：目标的权限集若在该
          // 命名空间上已显式表态（含显式 none()）且不含本落点，并进就等于把模板/请求收窄过的面静默放大。
          // fail-closed + 响亮：具名拒（RESOURCE_DENIED）+ denied 事件，让装配方去改配置，而不是让守卫替它拦。
          ResourceScope targetFace = permissions.resourceScopes().declaredScope(namespace);
          Path landing = mount.dir();
          if (targetFace != null && !targetFace.covers(ResourceScope.ofDir(landing))) {
            throw reject(
                WorkspaceException.RESOURCE_DENIED,
                "A5",
                null,
                key,
                mount.name().value(),
                "目标权限集在命名空间 " + namespace + " 上已有显式表态且不含该落点，落格会放大它的面（fail-closed，不静默并进）",
                "namespace=" + namespace + " dir=" + landing + " target=" + targetFace.summary());
          }
          result =
              WorkspacePermissions.withNamespace(
                  result, namespace, WorkspacePermissions.land(targetFace, landing));
        }
      }
    }
    return result;
  }

  @Override
  public List<MaskEntry> maskEntries() {
    List<MaskEntry> entries = new ArrayList<>();
    for (Map<MountName, Mount> slice : slices.values()) {
      for (Mount mount : slice.values()) {
        // M6：只返回装配期条目——运行期新建的挂载不进遮蔽面（shell 看不见它）
        if (mount.origin() != Origin.ASSEMBLY) {
          continue;
        }
        for (String id : mount.capabilities()) {
          Capability capability = catalog.get(id).orElse(null);
          if (capability == null) {
            // 不知道该进哪个命名空间 ⇒ 不产出条目（= 该路径不在 bash 面里，fail-closed）
            LOG.warn(
                "装配期挂载引用的能力 id 未注册，遮蔽面不产出条目: mount={} capability={}", mount.name().value(), id);
            continue;
          }
          for (String namespace : capability.namespaceNames()) {
            entries.add(new MaskEntry(namespace, mount.dir(), mount.kind()));
          }
        }
      }
    }
    // 次序稳定（装配面下发的清单不该随 map 遍历顺序抖动）；完全相同的条目只留一条，
    // 但"同一路径的不同命名空间/纪律"必须都留着——G7 的逐条目求与正是靠它们
    entries.sort(
        Comparator.comparing(MaskEntry::namespace)
            .thenComparing(entry -> entry.path().toString())
            .thenComparing(entry -> entry.kind().name()));
    return List.copyOf(new LinkedHashSet<>(entries));
  }

  @Override
  public List<WorkspaceEvent> events() {
    return List.copyOf(history);
  }

  // ---------------------------------------------------------------------------------------------
  // 内部
  // ---------------------------------------------------------------------------------------------

  /** 解析索引：使用者切片的查表（**唯一**索引口径，"哪些挂载是我的"只此一处，A8）。 */
  private Mount lookup(String holder, MountName name) {
    Map<MountName, Mount> slice = slices.get(holder);
    return slice == null ? null : slice.get(name);
  }

  /** ③ 准入：调用者面是否持有该挂载的能力（挂载时已落格，这里只按调用者面判）。 */
  private void admit(Mount mount, WorkspaceCaller caller, VirtualPath parsed) {
    if (mount.capabilities().isEmpty()) {
      throw deny(
          "A8",
          caller,
          mount.user(),
          mount.name().value(),
          "该挂载没有声明任何能力（能力是\"在这个工作区里能干什么\"的唯一决定点）",
          "path=" + parsed.label());
    }
    for (String id : mount.capabilities()) {
      Capability capability = catalog.get(id).orElse(null);
      if (capability == null) {
        LOG.warn(
            "挂载引用的能力 id 未注册，不参与准入判定（fail-closed）: mount={} capability={}",
            mount.name().value(),
            id);
        continue;
      }
      if (WorkspacePermissions.admits(caller.permissions(), capability, mount.dir())) {
        return;
      }
    }
    throw deny(
        "§4.5③",
        caller,
        mount.user(),
        mount.name().value(),
        "调用者没有该挂载的能力（可达面覆盖不到这个落点）",
        "path="
            + parsed.label()
            + " caller面="
            + WorkspacePermissions.summary(caller.permissions()));
  }

  /** 调用者自己名下的挂载里，包含 {@code dir} 的那一个（最深的那个 = 直接父工作区）。 */
  private Mount ownMountContaining(String holder, Path dir) {
    Map<MountName, Mount> slice = slices.get(holder);
    if (slice == null) {
      return null;
    }
    Mount best = null;
    for (Mount mount : slice.values()) {
      if (!mount.contains(dir)) {
        continue;
      }
      if (best == null || mount.dir().getNameCount() > best.dir().getNameCount()) {
        best = mount;
      }
    }
    return best;
  }

  /**
   * 子工作区的能力只能更窄（M11 / M12 / W6）：能力 id ⊆ 父的；同名能力的 shell 可见位与命令白名单不得放宽。
   *
   * <p><b>v1 的诚实边界</b>：{@link Mount#capabilities()} 是能力 id 集合，同一 id 在同一个 {@link CapabilityCatalog}
   * 里必然是同一份定义——因此"白名单只能更窄"在 v1 退化为"同一 id 相等"。为每挂载白名单留的形状属于命令面模块 （设计
   * §5.10，不在本期），比较点留在这里，届时只需要让挂载能携带更窄的白名单。
   */
  private void requireNarrower(WorkspaceCaller caller, Mount spec, Mount own) {
    for (String id : spec.capabilities()) {
      if (!own.capabilities().contains(id)) {
        throw deny(
            "M11",
            caller,
            spec.user(),
            spec.name().value(),
            "子工作区声明的能力超出父在自己工作区上的能力: " + id,
            "parent=" + own.capabilities() + " child=" + spec.capabilities());
      }
      Capability child = catalog.get(id).orElseThrow();
      Capability parent = catalog.get(id).orElseThrow();
      if (child.shellVisible() && !parent.shellVisible()) {
        throw deny(
            "M11/W6",
            caller,
            spec.user(),
            spec.name().value(),
            "子能力不得把 shell 可见位从不可见放宽成可见: " + id,
            "capability=" + id);
      }
      if (!parent.allowedCommands().containsAll(child.allowedCommands())) {
        throw deny(
            "M12",
            caller,
            spec.user(),
            spec.name().value(),
            "子能力的命令白名单超出父的白名单: " + id,
            "parent=" + parent.allowedCommands() + " child=" + child.allowedCommands());
      }
    }
  }

  /** 卸载一条挂载：注销 + 留痕 + 级联失效（M10/W4）。 */
  private void remove(Mount target, String reason) {
    detach(target);
    emit(
        WorkspaceEvent.unmounted(
            target.holder(),
            target.user(),
            target.name().value(),
            reason + " dir=" + target.dir() + "（物理目录保留：文档是数据）"));
    cascade(target);
  }

  /** 把一条挂载从它的使用者切片里摘掉（摘表是幂等的：不在表里就当已经摘过）。 */
  private void detach(Mount target) {
    Map<MountName, Mount> slice = slices.get(target.user());
    if (slice != null) {
      slice.remove(target.name());
      if (slice.isEmpty()) {
        slices.remove(target.user(), slice);
      }
    }
  }

  /**
   * M10/W4 级联失效：<b>沿 {@link Mount#derivedFrom()} 链</b>传递——被卸载挂载派生出的挂载一并注销，再对它们递归。
   *
   * <p><b>为什么不按物理包含判</b>（R5 后修订，设计 §4.10 M10）：W1 让子工作区<b>必然</b>物理嵌套在父工作区目录内，但物理嵌套本身不是派生关系
   * ——装配期独立挂载之间也可能物理嵌套（{@code workspaces} ⊃ {@code workspace}），按包含判会把无关挂载一起带走：R5 实测 {@code
   * unmount_workspace("workspaces")} 把独立的 {@code workspace} 也级联失效了，此后 {@code workspace/…} 全部 {@code
   * WORKSPACE_NOT_FOUND}。派生关系是登记时写下的显式事实，只有它知道"谁是从这块划出去的"。
   *
   * <p>失效后条目不存在 ⇒ 解析 {@code WORKSPACE_NOT_FOUND}，<b>不回落、不静默</b>。先摘表再递归，因此互相派生（环）与重复访问都不会
   * 再命中，不会无限递归。
   */
  private void cascade(Mount parent) {
    for (Mount mount : derivedFrom(parent)) {
      detach(mount);
      emit(
          WorkspaceEvent.unmounted(
              mount.holder(),
              mount.user(),
              mount.name().value(),
              "级联失效（M10/W4）：派生来源挂载 "
                  + parent.name().value()
                  + " 被卸载，由它派生的子工作区挂载一并失效；dir="
                  + mount.dir()
                  + "（不回落、不静默）"));
      cascade(mount);
    }
  }

  /**
   * 由 {@code parent} <b>派生</b>出来的挂载（M10）：登记时写下 {@code derivedFrom == parent.name()}，且创建者正是 {@code
   * parent} 的使用者。
   *
   * <p><b>为什么带"创建者"这一档</b>：{@code derivedFrom} 只记名字，而名字的唯一性按<b>使用者</b>分域（M4/A4）——同一个名字在不同使用者的
   * 切片里是不同挂载。派生关系由"谁创建了它"确定指代：{@code holder} 就是创建者，而创建者引用的那块挂载必在它自己的切片里 （{@code user ==
   * holder}）。少了这一档，一份独立划出的同名挂载会被别人的卸载带走。
   */
  private List<Mount> derivedFrom(Mount parent) {
    List<Mount> derived = new ArrayList<>();
    for (Map<MountName, Mount> slice : slices.values()) {
      for (Mount mount : slice.values()) {
        if (!mount.holder().equals(parent.user())) {
          continue;
        }
        if (mount.derivedFrom().isPresent() && mount.derivedFrom().get().equals(parent.name())) {
          derived.add(mount);
        }
      }
    }
    return derived;
  }

  /** "由该身份键创建"的挂载（跨切片；② 档卸载用）。 */
  private List<Mount> createdBy(String holder, MountName name) {
    List<Mount> matches = new ArrayList<>();
    for (Map<MountName, Mount> slice : slices.values()) {
      Mount mount = slice.get(name);
      if (mount != null && mount.holder().equals(holder)) {
        matches.add(mount);
      }
    }
    return matches;
  }

  /** 身份键 → 血缘路径；注入缺失、映射抛错、映射不出 ⇒ 空串（子树判定一律判否，fail-closed）。 */
  private String lineageOf(String holderKey) {
    String key = holderKey == null ? "" : holderKey.strip();
    if (key.isEmpty()) {
      return "";
    }
    Function<String, String> mapper = lineagePathOf;
    if (mapper == null) {
      return key;
    }
    String mapped;
    try {
      mapped = mapper.apply(key);
    } catch (RuntimeException e) {
      LOG.warn("血缘路径映射抛错，按解析不出处理（fail-closed）: holder={} {}", key, e.toString());
      return "";
    }
    return mapped == null ? "" : mapped.strip();
  }

  /** 权限/守卫拒绝（错误码 {@code RESOURCE_DENIED}）：日志留物理细节、事件留审计、异常只带"这一类被拒"。 */
  private WorkspaceException deny(
      String guard,
      WorkspaceCaller caller,
      String user,
      String mount,
      String reason,
      String auditDetail) {
    return reject(
        WorkspaceException.RESOURCE_DENIED, guard, caller, user, mount, reason, auditDetail);
  }

  /** 虚拟挂载名冲突（M4 的<b>具名拒</b>）：错误码与"无权"分开，好让人一眼看出是撞名而不是没权限。 */
  private WorkspaceException conflict(
      String guard,
      WorkspaceCaller caller,
      String user,
      String mount,
      String reason,
      String auditDetail) {
    return reject(
        WorkspaceException.WORKSPACE_NAME_CONFLICT,
        guard,
        caller,
        user,
        mount,
        reason,
        auditDetail);
  }

  /** 拒绝的共同骨架（文案纪律见 {@link WorkspaceException}：异常消息不含物理路径）。 */
  private WorkspaceException reject(
      String code,
      String guard,
      WorkspaceCaller caller,
      String user,
      String mount,
      String reason,
      String auditDetail) {
    String holder = caller == null ? "" : caller.holder();
    LOG.warn(
        "工作区操作被拒: code={} guard={} holder={} user={} mount={} {}",
        code,
        guard,
        holder,
        user,
        mount,
        auditDetail);
    emit(
        WorkspaceEvent.denied(
            holder,
            user,
            mount,
            guard + ": " + reason + (auditDetail.isBlank() ? "" : " ｜ " + auditDetail)));
    return new WorkspaceException(code, "工作区操作被拒（" + guard + "）: " + reason);
  }

  /** "不存在/不可见"：与 {@link #deny} 分开，因为错误码必须与"无权"同码（设计 §4.5）。 */
  private WorkspaceException notFound(WorkspaceCaller caller, MountName name, String detail) {
    String holder = caller == null ? "" : caller.holder();
    LOG.warn("工作区找不到: holder={} mount={} {}", holder, name.value(), detail);
    emit(
        WorkspaceEvent.denied(
            holder, holder, name.value(), WorkspaceException.WORKSPACE_NOT_FOUND + ": " + detail));
    return WorkspaceException.notFound("工作区不存在或不可见: " + name.value());
  }

  /** 落一条事件：内存留痕（有界）+ 交汇聚点（汇聚点抛错不改本模块行为，但留 WARN）。 */
  private void emit(WorkspaceEvent event) {
    history.addLast(event);
    while (history.size() > EVENT_HISTORY_LIMIT) {
      history.pollFirst();
    }
    Consumer<WorkspaceEvent> target = sink;
    if (target == null) {
      return;
    }
    try {
      target.accept(event);
    } catch (RuntimeException e) {
      LOG.warn("工作区事件汇聚点抛错（审计失败不改业务行为；本模块内存留痕仍在）: type={} {}", event.type(), e.toString());
    }
  }
}
