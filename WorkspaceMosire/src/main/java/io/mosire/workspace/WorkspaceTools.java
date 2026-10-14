package io.mosire.workspace;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * 挂载管理的工具面（设计 §4.7）：{@code mount_workspace} / {@code unmount_workspace} / {@code
 * list_workspaces}，外加能力自述的 {@code workspace_info}（设计 §5.11）。
 *
 * <p><b>前三个工具共用一条权限面</b>：{@link ResourceManifest}{@code .of("workspaces", DENY)}——调用者没在权限集里显式表态
 * {@code workspaces} ⇒ 执行前就被既有资源前置闸拒（"未表态 = 一个挂载都建不了"）。这是设计 §4.3 的工具面形状，本类不自造 第二套判定。
 *
 * <p><b>{@code workspace_info} 是刻意的例外</b>（{@link ResourceManifest#NONE}，不声明任何资源面）：它不读盘、不读别人的东西，只输出
 * "调用者自己"的挂载名与工具契约。若也声明 {@code workspaces} 面，最需要它的调用者（面空/未表态）恰好会被前置闸拒——那与"给模型一条
 * 自救渠道"的目的正相反。代价是它内部不得调资源 SPI（{@code workspace_info} 的 body 里没有 {@code require} 调用；前三个工具仍各自调一次），
 * 且输出必须只含调用者自己的东西、绝不含物理落点（A7/G4）——这两条进负向判据（设计 §5.11）。
 *
 * <p><b>工具只调不判</b>：{@code mount_workspace} 在真正登记前调 {@code context.resources().require(WRITE,
 * workspaces:<绝对路径>)}——"这个物理范围在不在我的挂载面里"由既有 {@link
 * io.mosire.agentlib.permission.ResourceAuthorizer} 判（唯一入口注入的判定者）；结构性的 M1/M2/M3/M4/M9/M11/M12 守卫在
 * {@link WorkspaceRegistry#mount} 里。
 *
 * <p><b>只创建 {@link Origin#RUNTIME} 挂载</b>（M6）：工具面<b>不接受</b> {@code origin} 参数——要进 bash 遮蔽面必须装配期
 * 声明，那条路只能由进程内的装配代码走。
 *
 * <p><b>落点形态 = 派生形态</b>（设计 §4.7 的"工具面的落点形态"行，★ R6 修订）：{@code mount_workspace} <b>不接受绝对物理
 * 路径</b>——它只接受 {@code name} / {@code parent} / {@code subdir} / {@code kind} / {@code
 * capabilities}，落点 = 解析 {@code parent}（自己已有的挂载名）得到的物理目录 + {@code subdir}（必须是<b>真子目录</b>，复用 W1/W2
 * 的判据）。理由： ① 面向模型只出虚拟路径（A7/G4），而旧形态要模型输入绝对物理路径，自相矛盾且实测无从得知（R5/R5c）；② 派生形态天然满足"只能在自己的
 * 地界里划更小的工作区"。<b>绝对 {@code dir} 形态只保留在注册表 API</b>（{@link WorkspaceRegistry#mount}，装配期宿主代码用）：
 * 工具面无此入参。
 *
 * <p><b>归属与血缘写死</b>：新挂载 {@code holder = user = 调用者本人}（工具面只服务"给自己划一块更小的工作区"；"父为子挂载"走 spawn 侧的注册表
 * API，M2），{@code derivedFrom = parent}（卸载 {@code parent} 时本挂载一并失效，M10）。
 *
 * <p><b>面向模型的输出只含虚拟路径</b>（G4/A7）：物理落点只进装配日志与审计事件。因此 {@code list_workspaces} 不列物理 目录、{@code
 * mount_workspace} 的返回也不回显落点，只回显虚拟名与 {@code parent}。{@code list_workspaces} 还必须告诉模型<b>哪些名字能当 {@code
 * parent}</b>（否则模型仍无从下手）：列的就是调用者自己切片里的挂载名。
 *
 * <p><b>工具等级</b>：{@code DEFAULT} 级、非敏感、非破坏（登记不等于写文件：本模块不创建目录、不改文件）；可外发给子 Agent（子体要能为自己派的小 Agent
 * 划更小的工作区）。{@code workspace_info} 同档：它只读内存里的挂载表，不读盘、不改任何东西，而且子体正是最需要"我有哪些挂载"的那一类调用者。
 */
public final class WorkspaceTools {

  /** 挂载一个新工作区（运行期新建，{@link Origin#RUNTIME}）。 */
  public static final String MOUNT_WORKSPACE = "mount_workspace";

  /** 卸载一个工作区（只注销挂载，保留物理目录；其下子工作区级联失效）。 */
  public static final String UNMOUNT_WORKSPACE = "unmount_workspace";

  /** 列出自己名下（可解析）的工作区。 */
  public static final String LIST_WORKSPACES = "list_workspaces";

  /**
   * 自述：工作区是什么 + 调用者自己的挂载清单 + 纪律怎么用（设计 §5.11）。
   *
   * <p><b>这个常量是对外的名字</b>：别的工具族要指路"不知道挂载名就先调它"时引这里，不要手抄字面量——名字改一次，指路的地方自动跟着变。
   */
  public static final String WORKSPACE_INFO = "workspace_info";

  /** 本能力注册的工具名集合（装配与"子进程不要把同名工具重复桥进来"的判断都用它）。 */
  public static final Set<String> NAMES =
      Set.of(MOUNT_WORKSPACE, UNMOUNT_WORKSPACE, LIST_WORKSPACES, WORKSPACE_INFO);

  /** 三个工具共同的资源面：{@code workspaces} 命名空间，缺省 DENY（未表态 = 一个挂载都建不了，§4.7）。 */
  private static final ResourceManifest MANIFEST =
      ResourceManifest.of(WorkspacePermissions.WORKSPACES, ResourcePolicy.DENY);

  private WorkspaceTools() {
    throw new AssertionError("No instances");
  }

  /** 造出全套工作区工具（四个工具共享同一个注册表实例）。 */
  public static List<AgentTool> all(WorkspaceRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    return List.of(mount(registry), unmount(registry), list(registry), info(registry));
  }

  /**
   * {@code mount_workspace}：在自己的某一块工作区（{@code parent}）里划出一块更小的工作区。
   *
   * <p>入参是<b>派生形态</b>（§4.7）：没有物理路径；落点由唯一解析点解析 {@code parent} 得到的目录 + {@code subdir} 派生。
   */
  static AgentTool mount(WorkspaceRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    Map<String, Object> schema =
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "name",
                Map.of(
                    "type",
                    "string",
                    "description",
                    "新挂载名（虚拟路径的第一段）：[a-z0-9][a-z0-9-]{0,63}；同一使用者下不得重名"),
                "parent",
                Map.of(
                    "type",
                    "string",
                    "description",
                    "从哪一块已有的工作区里划出来（你自己切片的挂载名，见 list_workspaces）。只接受虚拟挂载名，不接受物理路径"),
                "subdir",
                Map.of(
                    "type",
                    "string",
                    "description",
                    "相对 parent 落点的子目录（必须是真子目录：非空、不以 '/' 开头、不含空段与 '.'/'..' 段）"),
                "kind",
                Map.of(
                    "type",
                    "string",
                    "enum",
                    List.of("LOG", "FILE", "SCRATCH", "REFERENCE"),
                    "description",
                    "纪律：LOG=追加型文档（shell 永不写）、FILE=文件型文档、SCRATCH=暂存、REFERENCE=只读引用"),
                "capabilities",
                Map.of(
                    "type",
                    "array",
                    "items",
                    Map.of("type", "string"),
                    "description",
                    "要在这个工作区里授予的能力 id（可空；未注册的 id 会被拒；不得超出 parent 的能力）")),
            "required",
            List.of("name", "parent", "subdir", "kind"));
    return new WorkspaceTool(
        MOUNT_WORKSPACE,
        "在自己的某一块工作区里划出一块更小的工作区（虚拟路径 = 你给出的挂载名）。"
            + "落点由 parent（你已有的挂载名，见 list_workspaces）+ subdir（相对子目录）派生，本工具不接受物理路径；"
            + "必须是 parent 落点下的真子目录（不能是自己的根）；能力不得超出 parent；"
            + "工具面只能建运行期挂载（不进 shell 遮蔽面）；卸载 parent 时本挂载一并失效。",
        schema,
        MANIFEST,
        ToolSpec.level(AccessToken.DEFAULT, false, false),
        context -> {
          MountName name = mountNameArg(requiredString(context, "name"));
          MountName parent = mountNameArg(requiredString(context, "parent"));
          String subdir = subdirArg(requiredString(context, "subdir"), parent);
          Kind kind = kindArg(requiredString(context, "kind"));
          Set<String> capabilities = capabilitySet(context);
          WorkspaceCaller caller = callerOf(context);
          // parent 的物理落点 = 唯一解析点解析"该挂载的根"（§4.5 五步：拆名 → 查表 → 准入 → 映射 → containment）。
          // parent 不在你的挂载表里 / 你够不着 / 没有能力 ⇒ 由它给既有错误码（WORKSPACE_NOT_FOUND / RESOURCE_DENIED），
          // 本类不另判一次"这个 parent 能不能用"。
          Path parentDir = parentDir(registry, parent, caller);
          Path dir = parentDir.resolve(subdir).normalize();
          // 唯一入口的资源判定：派生落点必须在调用者的 workspaces 可达面内（M1 的工具面那一半，不自造判定器）。
          // 派生落点天然 ⊆ parent 的落点，但这里**不绕过**它——判定仍走注入的 ResourceAuthorizer。
          context
              .resources()
              .require(
                  Operation.WRITE, ResourceId.of(WorkspacePermissions.WORKSPACES, dir.toString()));
          Mount spec =
              new Mount(
                  name,
                  caller.holder(),
                  // user = 调用者本人（工具面只服务"给自己划"，M2 的"为后代挂载"在 spawn 侧的注册表 API 上）
                  caller.holder(),
                  dir,
                  Resolvers.direct(),
                  kind,
                  capabilities,
                  Origin.RUNTIME,
                  // derivedFrom = parent（M10）：卸载 parent ⇒ 本挂载级联失效，不按物理包含判
                  Optional.of(parent));
          Mount mounted = registry.mount(spec, caller);
          return ToolResult.ok(
              "已挂载 虚拟路径="
                  + mounted.name().value()
                  + " 使用者="
                  + mounted.user()
                  + " 纪律="
                  + mounted.kind()
                  + " 能力="
                  + renderCapabilities(mounted.capabilities())
                  + " 派生自="
                  + parent.value()
                  + " 来源=运行期（shell 看不见该挂载）");
        });
  }

  /** {@code unmount_workspace}：按挂载名卸载（只限创建者或其祖先；其下子工作区级联失效）。 */
  static AgentTool unmount(WorkspaceRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    Map<String, Object> schema =
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("name", Map.of("type", "string", "description", "要卸载的挂载名（虚拟路径的第一段）")),
            "required",
            List.of("name"));
    return new WorkspaceTool(
        UNMOUNT_WORKSPACE,
        "卸载一个工作区（只注销挂载，物理目录保留在盘上；由它派生的子工作区挂载会一并失效）。" + "只有创建者或其祖先能卸载。",
        schema,
        MANIFEST,
        ToolSpec.level(AccessToken.DEFAULT, false, false),
        context -> {
          String name = requiredString(context, "name");
          registry.unmount(new MountName(name), callerOf(context));
          return ToolResult.ok("已卸载 虚拟路径=" + name + "（由它派生的子工作区挂载一并失效；物理目录保留）");
        });
  }

  /**
   * {@code list_workspaces}：列出自己名下可解析的工作区（只有虚拟路径，不列物理落点）。
   *
   * <p><b>为什么必须把"能当 {@code parent} 的名字"写出来</b>（★ R6）：{@code mount_workspace} 的落点由 {@code parent}
   * 派生，而模型看不到物理布局——不告诉它有哪些名字可用，它仍然无从下手（R5/R5c 的实测就是"只有人把路径喂给它才跑得动"）。 可用的名字 = 调用者自己切片（{@code user ==
   * holder}）里的挂载名，正是本方法列出的那批；输出仍然只含虚拟路径（A7/G4）。
   */
  static AgentTool list(WorkspaceRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    Map<String, Object> schema = Map.of("type", "object", "properties", Map.of());
    return new WorkspaceTool(
        LIST_WORKSPACES,
        "列出你名下的工作区（虚拟路径、纪律、能力、归属、使用者、来源）。" + "这些虚拟路径的第一段就是可用的挂载名——mount_workspace 的 parent 只能从中挑。",
        schema,
        MANIFEST,
        ToolSpec.level(AccessToken.DEFAULT, false, false),
        context -> {
          WorkspaceCaller caller = callerOf(context);
          List<Mount> mounts = registry.mounts(caller.holder());
          if (mounts.isEmpty()) {
            return ToolResult.ok("（没有已挂载的工作区：一个 parent 也没有，先请装配方给你挂一块）");
          }
          StringBuilder text = new StringBuilder("工作区 ").append(mounts.size()).append(" 个：\n");
          Set<String> parentCandidates = new TreeSet<>();
          for (Mount mount : mounts) {
            parentCandidates.add(mount.name().value());
            text.append("- 虚拟路径=")
                .append(mount.name().value())
                .append(" 纪律=")
                .append(mount.kind())
                .append(" 能力=")
                .append(renderCapabilities(mount.capabilities()))
                .append(" 归属=")
                .append(mount.holder())
                .append(" 使用者=")
                .append(mount.user())
                .append(" 来源=")
                .append(mount.origin())
                .append('\n');
          }
          text.append("可用作 parent 的挂载名：")
              .append(parentCandidates)
              .append("（每个条目的虚拟路径第一段；parent 只接受这种名字，不接受物理路径）");
          return ToolResult.ok(text.toString());
        });
  }

  /**
   * {@code workspace_info}：回答"工作区是什么、我有哪些挂载、纪律怎么用"（设计 §5.11）。
   *
   * <p><b>无参数</b>：模型不需要事先知道任何名字——这正是这个工具存在的理由（隔壁真机事故里，模型拿工具名当挂载名猜， 每次必失败；根因是它没有任何渠道知道自己的挂载叫什么）。
   *
   * <p><b>唯一不声明资源面的工作区工具</b>（{@link ResourceManifest#NONE}）：见类注释的资源声明段。本工具的 body 里<b>没有</b> {@code
   * resources()} 调用——不声明面却去调它，等于期望一个必然拒绝的判定者放行。
   *
   * <p><b>零挂载不抛异常</b>：解析不出身份或挂载表为空时给"你还没有工作区 + 让上级给你挂一块"的清楚回答（设计 §5.11 判据）。
   */
  static AgentTool info(WorkspaceRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    Map<String, Object> schema = Map.of("type", "object", "properties", Map.of());
    return new WorkspaceTool(
        WORKSPACE_INFO,
        "告诉你自己的工作区是什么、有哪些挂载（虚拟名 / 纪律 / 能力 / 来源 / 能否作 parent）以及纪律怎么用。"
            + "想知道自己的挂载叫什么名字、不知道该从哪块地开始读写、刚被 "
            + WorkspaceException.WORKSPACE_NOT_FOUND
            + " 拒了 ⇒ 调我。无参数；一个挂载都没有的人调它也会得到清楚回答（不是错误）。",
        schema,
        ResourceManifest.NONE,
        ToolSpec.level(AccessToken.DEFAULT, false, false),
        context -> ToolResult.ok(WorkspaceInfo.render(registry, context)));
  }

  /** 调用者 = 宿主绑定的实例 id（身份不由模型给出，见 {@link WorkspaceCaller}）+ 本次调用的权限集。 */
  private static WorkspaceCaller callerOf(ToolContext context) {
    return new WorkspaceCaller(context.identity().instanceId(), context.permissions());
  }

  private static String requiredString(ToolContext context, String name) {
    String text = text(context, name);
    if (text.isEmpty()) {
      throw WorkspaceException.invalid(name + " 为必填参数");
    }
    return text;
  }

  private static String text(ToolContext context, String name) {
    Object value = context.arguments().get(name);
    return value == null ? "" : String.valueOf(value).strip();
  }

  /**
   * 挂载名入参（{@code name} / {@code parent}）：形态由 {@link MountName} 判（<b>唯一</b>一处口径，与虚拟路径第一段同源）。
   *
   * <p><b>物理路径在这里就被挡住</b>：{@code /…}、{@code ..}、大写/空格都不匹配 {@link MountName#PATTERN} ⇒ 具名 {@code
   * INVALID_ARGUMENTS}，错误消息只回显模型自己给的那个串（不含任何物理路径，A7）。
   */
  private static MountName mountNameArg(String text) {
    if (!MountName.isValid(text)) {
      throw WorkspaceException.invalid("必须是合法挂载名（" + MountName.PATTERN + "），不接受物理路径: " + text);
    }
    return new MountName(text);
  }

  /**
   * {@code subdir} 入参：必须是 <b>parent 落点下的真子目录</b>的相对拼法（W1/W2）。
   *
   * <p><b>不另造一套校验</b>：把 {@code parent + "/" + subdir} 当一条虚拟路径交给 {@link VirtualPath#parse} ——"不得以
   * {@code /} 开头、不得含空段、不得含 {@code .}/{@code ..} 段、第一段必须是合法挂载名"这四条守卫与解析虚拟路径
   * 完全同一处实现，本方法只取出它算出的相对部分。因此 {@code ../x} / {@code /x} / {@code a//b} / {@code a/./b} 得到的是
   * 同一句既有的具名拒。
   *
   * <p><b>"真子目录"那一半不在这里判</b>：它由 {@link WorkspaceRegistry#mount} 的 M9/W1 判（落点必须严格落在自己的工作区
   * 目录之内，等于自己的根即拒）——守卫只有一个归属地。本方法交付的相对拼法不含 {@code ..}/空段，因此派生落点结构上 只可能更深，不可能等于 parent 的根。
   */
  private static String subdirArg(String subdir, MountName parent) {
    return VirtualPath.parse(parent.value() + "/" + subdir).relative();
  }

  /**
   * 解析 {@code parent} 的物理落点（该挂载的根）：走<b>唯一解析点</b>（{@link WorkspaceRegistry#resolve}）。
   *
   * <p>用虚拟挂载名（相对部分为空 ⇒ 该挂载的根）而不是去读挂载表，是为了复用解析点的全部四步——名字不存在、无权、映射越界都在那里
   * 得到既有错误码；本方法不产生第二条判据，也不向模型回显任何物理路径。
   */
  private static Path parentDir(
      WorkspaceRegistry registry, MountName parent, WorkspaceCaller caller) {
    return registry.resolve(parent.value(), caller);
  }

  private static Kind kindArg(String text) {
    try {
      return Kind.valueOf(text.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw WorkspaceException.invalid("kind 必须是 LOG / FILE / SCRATCH / REFERENCE 之一: " + text);
    }
  }

  /** {@code capabilities} 参数：数组（元素为字符串）或逗号分隔的单字符串都接受；缺省 = 空集（一个能力都不给）。 */
  private static Set<String> capabilitySet(ToolContext context) {
    Object value = context.arguments().get("capabilities");
    if (value == null) {
      return Set.of();
    }
    List<String> items = new ArrayList<>();
    if (value instanceof List<?> list) {
      for (Object item : list) {
        if (item == null) {
          throw WorkspaceException.invalid("capabilities 的元素不能为 null");
        }
        items.add(String.valueOf(item).strip());
      }
    } else if (value instanceof String text) {
      for (String piece : text.split(",")) {
        items.add(piece.strip());
      }
    } else {
      throw WorkspaceException.invalid("capabilities 必须是字符串数组（或逗号分隔的字符串）: " + value);
    }
    Set<String> capabilities = new TreeSet<>();
    for (String item : items) {
      if (!item.isEmpty()) {
        capabilities.add(item);
      }
    }
    return Set.copyOf(capabilities);
  }

  /**
   * 能力集合的稳定渲染（空集写 {@code []}：那正是"一个能力都不给"的诚实写法）。
   *
   * <p><b>包级可见</b>：{@link WorkspaceInfo} 复用同一份渲染——两个工具对"能力"的写法若各写一套，同一个挂载在两处就会长得不一样。
   */
  static String renderCapabilities(Set<String> capabilities) {
    return new TreeSet<>(capabilities).toString();
  }

  /**
   * 共同骨架：把 {@link WorkspaceException} 转成错误码（权限拒绝仍走唯一入口的统一转码）。
   *
   * <p><b>资源面是构造参数而不是常量</b>：前三个工具共用 {@link #MANIFEST}，{@code workspace_info} 用 {@link
   * ResourceManifest#NONE}（见类注释）。骨架只保管这份声明、从不读它——"工具声明了什么面"与"这次调用能不能过"是两件事，后者归唯一入口的判定者。
   */
  private static final class WorkspaceTool implements AgentTool {

    private final String name;
    private final String description;
    private final Map<String, Object> schema;
    private final ResourceManifest manifest;
    private final ToolSpec spec;
    private final Function<ToolContext, ToolResult> body;

    WorkspaceTool(
        String name,
        String description,
        Map<String, Object> schema,
        ResourceManifest manifest,
        ToolSpec spec,
        Function<ToolContext, ToolResult> body) {
      this.name = name;
      this.description = description;
      this.schema = schema;
      this.manifest = manifest;
      this.spec = spec;
      this.body = body;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String description() {
      return description;
    }

    /**
     * EI_EXPOSE_REP 抑制：{@code schema} 是 {@code Map.of}/{@code List.of}/字符串组成的<b>深不可变</b>树（与 {@code
     * SkillTools.SkillTool} 同款理由），装配期定型后不再改写。
     */
    @SuppressFBWarnings("EI_EXPOSE_REP")
    @Override
    public Map<String, Object> jsonSchema() {
      return schema;
    }

    @Override
    public ToolSpec spec() {
      return spec;
    }

    @Override
    public ResourceManifest resources() {
      return manifest;
    }

    @Override
    public ToolResult execute(ToolContext context) {
      try {
        return body.apply(context);
      } catch (WorkspaceException e) {
        return ToolResult.error(e.code(), e.getMessage());
      }
    }
  }
}
