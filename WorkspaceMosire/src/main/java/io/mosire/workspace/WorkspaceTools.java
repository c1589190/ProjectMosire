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
import java.nio.file.InvalidPathException;
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
 * 挂载管理的工具面（设计 §4.7）：{@code mount_workspace} / {@code unmount_workspace} / {@code list_workspaces}。
 *
 * <p><b>三个工具共用一条权限面</b>：{@link ResourceManifest}{@code .of("workspaces", DENY)}——调用者没在权限集里显式表态
 * {@code workspaces} ⇒ 执行前就被既有资源前置闸拒（"未表态 = 一个挂载都建不了"）。这是设计 §4.3 的工具面形状，本类不自造 第二套判定。
 *
 * <p><b>工具只调不判</b>：{@code mount_workspace} 在真正登记前调 {@code context.resources().require(WRITE,
 * workspaces:<绝对路径>)}——"这个物理范围在不在我的挂载面里"由既有 {@link
 * io.mosire.agentlib.permission.ResourceAuthorizer} 判（唯一入口注入的判定者）；结构性的 M1/M2/M3/M4/M9/M11/M12 守卫在
 * {@link WorkspaceRegistry#mount} 里。
 *
 * <p><b>只创建 {@link Origin#RUNTIME} 挂载</b>（M6）：工具面<b>不接受</b> {@code origin} 参数——要进 bash 遮蔽面必须装配期
 * 声明，那条路只能由进程内的装配代码走。
 *
 * <p><b>面向模型的输出只含虚拟路径</b>（G4/A7）：物理落点只进装配日志与审计事件。因此 {@code list_workspaces} 不列物理 目录、{@code
 * mount_workspace} 的返回也不回显落点。
 *
 * <p><b>工具等级</b>：{@code DEFAULT} 级、非敏感、非破坏（登记不等于写文件：本模块不创建目录、不改文件）；可外发给子 Agent（子体要能为自己派的小 Agent
 * 划更小的工作区）。
 */
public final class WorkspaceTools {

  /** 挂载一个新工作区（运行期新建，{@link Origin#RUNTIME}）。 */
  public static final String MOUNT_WORKSPACE = "mount_workspace";

  /** 卸载一个工作区（只注销挂载，保留物理目录；其下子工作区级联失效）。 */
  public static final String UNMOUNT_WORKSPACE = "unmount_workspace";

  /** 列出自己名下（可解析）的工作区。 */
  public static final String LIST_WORKSPACES = "list_workspaces";

  /** 本能力注册的工具名集合（装配与"子进程不要把同名工具重复桥进来"的判断都用它）。 */
  public static final Set<String> NAMES =
      Set.of(MOUNT_WORKSPACE, UNMOUNT_WORKSPACE, LIST_WORKSPACES);

  /** 三个工具共同的资源面：{@code workspaces} 命名空间，缺省 DENY（未表态 = 一个挂载都建不了，§4.7）。 */
  private static final ResourceManifest MANIFEST =
      ResourceManifest.of(WorkspacePermissions.WORKSPACES, ResourcePolicy.DENY);

  private WorkspaceTools() {
    throw new AssertionError("No instances");
  }

  /** 造出全套工作区工具（三个工具共享同一个注册表实例）。 */
  public static List<AgentTool> all(WorkspaceRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    return List.of(mount(registry), unmount(registry), list(registry));
  }

  /** {@code mount_workspace}：在自己的可达面内登记一个工作区（能力必须已注册且不超出自己的权利）。 */
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
                    "挂载名（虚拟路径的第一段）：[a-z0-9][a-z0-9-]{0,63}；同一使用者下不得重名"),
                "dir",
                Map.of("type", "string", "description", "物理目录（绝对路径、已归一）：必须落在你 workspaces 可达面之内"),
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
                    "要在这个工作区里授予的能力 id（可空；未注册的 id 会被拒）"),
                "user",
                Map.of("type", "string", "description", "使用者身份键（缺省=自己）；只能是自己或自己的后代（血缘子树）"),
                "derivedFrom",
                Map.of(
                    "type",
                    "string",
                    "description",
                    "派生来源挂载名（可选）：你名下已有的挂载名。它被卸载时本挂载一并失效（M10）；缺省 = 独立挂载，不随任何挂载失效")),
            "required",
            List.of("name", "dir", "kind"));
    return new WorkspaceTool(
        MOUNT_WORKSPACE,
        "在当前进程里登记一个工作区（虚拟路径 = 你给出的挂载名）。物理范围必须在你的 workspaces 可达面内；"
            + "只能在你的血缘子树内指定使用者；工具面只能建运行期挂载（不进 shell 遮蔽面）；"
            + "可选 derivedFrom 声明\"从哪一块划出来的\"，卸载来源时本挂载一并失效。",
        schema,
        ToolSpec.level(AccessToken.DEFAULT, false, false),
        context -> {
          String name = requiredString(context, "name");
          Path dir = physicalDir(requiredString(context, "dir"));
          Kind kind = kindArg(requiredString(context, "kind"));
          Set<String> capabilities = capabilitySet(context);
          WorkspaceCaller caller = callerOf(context);
          String user = optionalString(context, "user").orElse(caller.holder());
          Optional<MountName> derivedFrom = derivedFromArg(context);
          // 唯一入口的资源判定：物理范围必须在调用者的 workspaces 可达面内（M1 的工具面那一半，不自造判定器）
          context
              .resources()
              .require(
                  Operation.WRITE, ResourceId.of(WorkspacePermissions.WORKSPACES, dir.toString()));
          Mount spec =
              new Mount(
                  new MountName(name),
                  caller.holder(),
                  user,
                  dir,
                  Resolvers.direct(),
                  kind,
                  capabilities,
                  Origin.RUNTIME,
                  derivedFrom);
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
                  + mounted.derivedFrom().map(source -> " 派生自=" + source.value()).orElse("")
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
        ToolSpec.level(AccessToken.DEFAULT, false, false),
        context -> {
          String name = requiredString(context, "name");
          registry.unmount(new MountName(name), callerOf(context));
          return ToolResult.ok("已卸载 虚拟路径=" + name + "（由它派生的子工作区挂载一并失效；物理目录保留）");
        });
  }

  /** {@code list_workspaces}：列出自己名下可解析的工作区（只有虚拟路径，不列物理落点）。 */
  static AgentTool list(WorkspaceRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    Map<String, Object> schema = Map.of("type", "object", "properties", Map.of());
    return new WorkspaceTool(
        LIST_WORKSPACES,
        "列出你名下的工作区（虚拟路径、纪律、能力、归属、使用者、来源）。",
        schema,
        ToolSpec.level(AccessToken.DEFAULT, false, false),
        context -> {
          WorkspaceCaller caller = callerOf(context);
          List<Mount> mounts = registry.mounts(caller.holder());
          if (mounts.isEmpty()) {
            return ToolResult.ok("（没有已挂载的工作区）");
          }
          StringBuilder text = new StringBuilder("工作区 ").append(mounts.size()).append(" 个：\n");
          for (Mount mount : mounts) {
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
          return ToolResult.ok(text.toString());
        });
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

  private static Optional<String> optionalString(ToolContext context, String name) {
    String text = text(context, name);
    return text.isEmpty() ? Optional.empty() : Optional.of(text);
  }

  private static String text(ToolContext context, String name) {
    Object value = context.arguments().get(name);
    return value == null ? "" : String.valueOf(value).strip();
  }

  /**
   * {@code derivedFrom} 入参（可选，M10）：只接受合法挂载名（形态与 {@code name} 同一口径）。
   *
   * <p><b>"引用的挂载是否真实存在"不在这里判</b>：那要读挂载表，而挂载表只有一个判定归属地——{@link WorkspaceRegistry#mount} （指向不存在的名字会被
   * M10 具名拒，不静默退化成一个永不级联的挂载）。
   */
  private static Optional<MountName> derivedFromArg(ToolContext context) {
    Optional<String> text = optionalString(context, "derivedFrom");
    if (text.isEmpty()) {
      return Optional.empty();
    }
    if (!MountName.isValid(text.get())) {
      throw WorkspaceException.invalid(
          "derivedFrom 必须是合法挂载名（" + MountName.PATTERN + "）: " + text.get());
    }
    return Optional.of(new MountName(text.get()));
  }

  /** 物理目录入参：绝对 + 已归一（A6）；不合法即 {@code INVALID_ARGUMENTS}，不猜。 */
  private static Path physicalDir(String text) {
    Path dir;
    try {
      dir = Path.of(text);
    } catch (InvalidPathException e) {
      throw WorkspaceException.invalid("dir 不是合法路径（含无法成为路径的字符）");
    }
    if (!dir.isAbsolute()) {
      throw WorkspaceException.invalid("dir 必须是绝对路径（挂载的物理落点不接受相对路径）");
    }
    if (!dir.normalize().equals(dir)) {
      throw WorkspaceException.invalid("dir 必须已归一（不接受 '.' / '..' 段或重复分隔符）");
    }
    return dir;
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

  /** 能力集合的稳定渲染（空集写 {@code []}：那正是"一个能力都不给"的诚实写法）。 */
  private static String renderCapabilities(Set<String> capabilities) {
    return new TreeSet<>(capabilities).toString();
  }

  /** 共同骨架：把 {@link WorkspaceException} 转成错误码（权限拒绝仍走唯一入口的统一转码）。 */
  private static final class WorkspaceTool implements AgentTool {

    private final String name;
    private final String description;
    private final Map<String, Object> schema;
    private final ToolSpec spec;
    private final Function<ToolContext, ToolResult> body;

    WorkspaceTool(
        String name,
        String description,
        Map<String, Object> schema,
        ToolSpec spec,
        Function<ToolContext, ToolResult> body) {
      this.name = name;
      this.description = description;
      this.schema = schema;
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
      return MANIFEST;
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
