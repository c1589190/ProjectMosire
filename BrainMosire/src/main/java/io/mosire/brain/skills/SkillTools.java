package io.mosire.brain.skills;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 技能工具面：{@code skill_list} / {@code skill_search} / {@code skill_load} / {@code
 * skill_read_resource} / {@code skill_create} / {@code skill_edit}——主 Agent 与任意子 Agent
 * 共用同一套实现，权限从<b>调用者自己的权限集</b>现取。
 *
 * <p><b>判定链（三段落在一处，缺一不可）</b>：
 *
 * <ol>
 *   <li><b>工具名级</b>：{@code ToolExecutionGuard} 按调用者白名单/拒绝名单判（子 Agent 的模板白名单要列出技能工具名， deny-default
 *       才有意义）；
 *   <li><b>资源声明级</b>：每个工具声明自己的命名空间与缺省策略 {@link ResourcePolicy#DENY}（读工具声明 {@link
 *       SkillNamespaces#SKILLS}，写工具声明 {@link SkillNamespaces#SKILLS_EDIT}）。缺省 DENY 的含义是：<b>调用者没在
 *       权限集里显式表态 ⇒ 这次调用在执行前就被拒</b>——“没配技能”永远读作“没有技能”，不会静默变成“随便用”；
 *   <li><b>逐路径级</b>：{@link SkillAccess} 在真正读写前调 {@code context.resources().require}（宿主在唯一入口注入的判定者），
 *       判否抛 {@code ResourceDeniedException}，由 {@code ToolCallAuthorizer} 统一转成 {@code
 *       RESOURCE_DENIED}。
 * </ol>
 *
 * <p><b>工具清单与契约</b>（实现说明里同样写死，模型只能看见 schema，看不见这段注释）：
 *
 * <table>
 *   <caption>技能工具</caption>
 *   <tr><th>工具</th><th>入参</th><th>返回</th><th>权限面</th></tr>
 *   <tr><td>{@code skill_list}</td><td>无</td><td>{@code {count, skills:[{name,description,allowedTools,editable,directory}]}}</td><td>可见面</td></tr>
 *   <tr><td>{@code skill_search}</td><td>{@code query}(必填), {@code limit}</td><td>{@code {query,hits:[{name,description,matchedIn,snippet}],unreadable,scanned}}</td><td>可见面</td></tr>
 *   <tr><td>{@code skill_load}</td><td>{@code name}(必填)</td><td>{@code {name,description,allowedTools,directory,body}}</td><td>可见面</td></tr>
 *   <tr><td>{@code skill_read_resource}</td><td>{@code name},{@code path}(均必填)</td><td>{@code {name,path,content}}</td><td>可见面</td></tr>
 *   <tr><td>{@code skill_create}</td><td>{@code name},{@code description},{@code body?},{@code allowedTools?}</td><td>新技能元数据</td><td>可编辑面</td></tr>
 *   <tr><td>{@code skill_edit}</td><td>{@code name} + 至少一项变更（{@code description}/{@code body}/{@code allowedTools}）</td><td>改后元数据</td><td>可编辑面</td></tr>
 * </table>
 *
 * <p><b>激活的上下文去向</b>：{@code skill_load} 的正文作为<b>工具结果</b>落进当前会话历史，此后每个回合随历史原样回灌——这是本实现
 * 选定的“激活”语义（另一条候选是每回合重注入 {@code LOADED_SKILLS} 层，本期不选：工具结果路径对主/子 Agent 一致，且不需要为每个
 * 子进程维护第二份激活状态）。目录档（{@code SKILL_INDEX}）仍由上下文装配器按可见面每回合预注入。
 *
 * <p><b>工具等级</b>：读工具 {@code DEFAULT} 级、非敏感非破坏；写工具 {@code DEFAULT} 级 + <b>破坏性</b>（写文件系统）—— 想给子 Agent
 * 技能编辑权，模板必须显式 {@code destructiveAllowed=true}，与 bash 的放行口径一致。
 */
public final class SkillTools {

  /** 列出可见技能（第一档元数据，不含正文）。 */
  public static final String SKILL_LIST = "skill_list";

  /** 关键词检索（只在本调用者可见的技能里检索）。 */
  public static final String SKILL_SEARCH = "skill_search";

  /** 加载技能正文（第二档；正文作为工具结果留在会话里）。 */
  public static final String SKILL_LOAD = "skill_load";

  /** 读技能目录内的资源文件（第三档：references/scripts 等）。 */
  public static final String SKILL_READ_RESOURCE = "skill_read_resource";

  /** 在可编辑面内新建技能。 */
  public static final String SKILL_CREATE = "skill_create";

  /** 在可编辑面内修改技能。 */
  public static final String SKILL_EDIT = "skill_edit";

  /** 本能力注册的工具名集合（装配、诊断与“子进程不要把同名工具重复桥进来”的判断都用它）。 */
  public static final Set<String> NAMES =
      Set.of(SKILL_LIST, SKILL_SEARCH, SKILL_LOAD, SKILL_READ_RESOURCE, SKILL_CREATE, SKILL_EDIT);

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 读工具声明的资源面：{@code skills} 命名空间，缺省 DENY（调用者不表态就够不着）。 */
  private static final ResourceManifest READ_MANIFEST =
      ResourceManifest.of(SkillNamespaces.SKILLS, ResourcePolicy.DENY);

  /** 写工具声明的资源面：{@code skills-edit} 命名空间，缺省 DENY。 */
  private static final ResourceManifest WRITE_MANIFEST =
      ResourceManifest.of(SkillNamespaces.SKILLS_EDIT, ResourcePolicy.DENY);

  private SkillTools() {}

  /**
   * 造出全套技能工具（同一 {@link SkillCatalog} 实例被所有工具共享：激活时间戳与快照状态因此连贯）。
   *
   * @param catalog 技能目录（快照 + 第一档渲染）
   * @param skillsRoot 技能根目录（{@code <skillsDir>}；工具按调用者的可见/可编辑面在其中落点）
   */
  public static List<AgentTool> all(SkillCatalog catalog, Path skillsRoot) {
    Objects.requireNonNull(catalog, "catalog");
    Objects.requireNonNull(skillsRoot, "skillsRoot");
    return List.of(
        list(catalog, skillsRoot),
        search(catalog, skillsRoot),
        load(catalog, skillsRoot),
        readResource(catalog, skillsRoot),
        create(catalog, skillsRoot),
        edit(catalog, skillsRoot));
  }

  /** {@code skill_list}：列可见技能（名字 + 用途 + 可用工具 + 是否可编辑 + 资源目录）。 */
  static AgentTool list(SkillCatalog catalog, Path skillsRoot) {
    return new SkillTool(
        SKILL_LIST,
        "列出当前可见的技能（只有名称、用途与资源目录，正文用 skill_load 按需加载）。",
        Map.of("type", "object", "properties", Map.of()),
        ToolSpec.level(AccessToken.DEFAULT, false, false),
        READ_MANIFEST,
        context -> {
          SkillAccess access = SkillAccess.of(context, catalog, skillsRoot);
          List<Map<String, Object>> rows = new ArrayList<>();
          access.refresh();
          for (Skill skill : access.visibleSkills()) {
            rows.add(metadataOf(access, skill));
          }
          Map<String, Object> body = new LinkedHashMap<>();
          body.put("count", rows.size());
          body.put("skills", rows);
          if (!access.broken().isEmpty()) {
            body.put("broken", access.broken());
          }
          return ToolResult.ok(toJson(body));
        });
  }

  /** {@code skill_search}：关键词检索（name/description/正文三个字段，AND 语义，大小写不敏感）。 */
  static AgentTool search(SkillCatalog catalog, Path skillsRoot) {
    return new SkillTool(
        SKILL_SEARCH,
        "按关键词检索可见技能（匹配技能名、用途与正文；多个词用空格分隔，需全部命中；只做关键词，不做语义检索）。",
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "query",
                Map.of("type", "string", "description", "检索关键词（空格分隔多个词，需全部命中）"),
                "limit",
                Map.of(
                    "type",
                    "integer",
                    "description",
                    "最多返回条数（缺省 "
                        + SkillAccess.DEFAULT_SEARCH_LIMIT
                        + "，上限 "
                        + SkillAccess.MAX_SEARCH_LIMIT
                        + "）")),
            "required",
            List.of("query")),
        ToolSpec.level(AccessToken.DEFAULT, false, false),
        READ_MANIFEST,
        context -> {
          String query = strArg(context, "query");
          if (query == null) {
            return ToolResult.error(SkillException.INVALID_ARGUMENTS, "query 为必填参数");
          }
          SkillAccess access = SkillAccess.of(context, catalog, skillsRoot);
          SkillAccess.SearchResult result = access.search(query, intArg(context, "limit"));
          Map<String, Object> body = new LinkedHashMap<>();
          body.put("query", query);
          body.put("scanned", result.scanned());
          body.put("hits", result.hits());
          if (!result.unreadable().isEmpty()) {
            body.put("unreadable", result.unreadable());
          }
          if (!access.broken().isEmpty()) {
            body.put("broken", access.broken());
          }
          return ToolResult.ok(toJson(body));
        });
  }

  /** {@code skill_load}：读正文（第二档）；正文随工具结果进入会话历史，后续回合照常回灌。 */
  static AgentTool load(SkillCatalog catalog, Path skillsRoot) {
    return new SkillTool(
        SKILL_LOAD,
        "加载一个可见技能的正文（SKILL.md）。正文会留在当前会话里，后续回合仍然可见；"
            + "技能目录内的 references/scripts 等资源用 skill_read_resource 按需读取。",
        Map.of(
            "type", "object",
            "properties",
                Map.of("name", Map.of("type", "string", "description", "技能名（见 skill_list）")),
            "required", List.of("name")),
        ToolSpec.level(AccessToken.DEFAULT, false, false),
        READ_MANIFEST,
        context -> {
          String name = strArg(context, "name");
          if (name == null) {
            return ToolResult.error(SkillException.INVALID_ARGUMENTS, "name 为必填参数");
          }
          SkillAccess access = SkillAccess.of(context, catalog, skillsRoot);
          String content = access.loadBody(name);
          Skill skill = access.lookupVisible(name).orElse(null);
          Map<String, Object> body = new LinkedHashMap<>();
          body.put("name", name);
          if (skill != null) {
            body.put("description", skill.description());
            body.put("allowedTools", List.copyOf(skill.allowedTools()));
            body.put("directory", skill.directory().toString());
          }
          body.put("body", content);
          return ToolResult.ok(toJson(body));
        });
  }

  /** {@code skill_read_resource}：第三档按需读取（相对路径，`..` 逃逸一律拒）。 */
  static AgentTool readResource(SkillCatalog catalog, Path skillsRoot) {
    return new SkillTool(
        SKILL_READ_RESOURCE,
        "读取可见技能目录内的一个资源文件（如 references/xxx.md、scripts/xxx.sh）；路径必须是技能目录内的相对路径。",
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "name", Map.of("type", "string", "description", "技能名"),
                "path", Map.of("type", "string", "description", "技能目录内的相对路径")),
            "required",
            List.of("name", "path")),
        ToolSpec.level(AccessToken.DEFAULT, false, false),
        READ_MANIFEST,
        context -> {
          String name = strArg(context, "name");
          String path = strArg(context, "path");
          if (name == null || path == null) {
            return ToolResult.error(SkillException.INVALID_ARGUMENTS, "name 与 path 为必填参数");
          }
          SkillAccess access = SkillAccess.of(context, catalog, skillsRoot);
          String content = access.readResource(name, path);
          Map<String, Object> body = new LinkedHashMap<>();
          body.put("name", name);
          body.put("path", path);
          body.put("content", content);
          return ToolResult.ok(toJson(body));
        });
  }

  /** {@code skill_create}：可编辑面内新建（frontmatter 先校验后落盘，原子替换，写完即重扫）。 */
  static AgentTool create(SkillCatalog catalog, Path skillsRoot) {
    return new SkillTool(
        SKILL_CREATE,
        "在当前可编辑范围内新建一个技能（写入 <技能根>/<name>/SKILL.md，frontmatter 会先校验再落盘）。",
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "name",
                Map.of("type", "string", "description", "技能名（小写字母/数字/短横线，≡ 目录名）"),
                "description",
                Map.of("type", "string", "description", "一句话用途（1–1024 字符，单行）"),
                "body",
                Map.of("type", "string", "description", "技能正文（可省略）"),
                "allowedTools",
                Map.of(
                    "type", "array",
                    "items", Map.of("type", "string"),
                    "description", "该技能声明的工具白名单（可省略；只用于技能自身的权限收紧提示）")),
            "required",
            List.of("name", "description")),
        ToolSpec.level(AccessToken.DEFAULT, false, true),
        WRITE_MANIFEST,
        context -> {
          String name = strArg(context, "name");
          String description = strArg(context, "description");
          if (name == null || description == null) {
            return ToolResult.error(SkillException.INVALID_ARGUMENTS, "name 与 description 为必填参数");
          }
          SkillAccess access = SkillAccess.of(context, catalog, skillsRoot);
          SkillAccess.WriteResult created =
              access.create(
                  name,
                  description,
                  context.arguments().containsKey("body")
                      ? String.valueOf(context.arguments().get("body"))
                      : null,
                  toolSetArg(context, "allowedTools"));
          return ToolResult.ok(writeBody(created));
        });
  }

  /** {@code skill_edit}：可编辑面内修改（未给的字段保持不变；同样先校验后落盘）。 */
  static AgentTool edit(SkillCatalog catalog, Path skillsRoot) {
    return new SkillTool(
        SKILL_EDIT,
        "修改一个已有技能的 SKILL.md（description/body/allowedTools 未给的项保持不变；frontmatter 会先校验再落盘）。",
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "name", Map.of("type", "string", "description", "技能名"),
                "description", Map.of("type", "string", "description", "新的一句话用途（省略 = 不变）"),
                "body", Map.of("type", "string", "description", "新的技能正文（省略 = 不变）"),
                "allowedTools",
                    Map.of(
                        "type", "array",
                        "items", Map.of("type", "string"),
                        "description", "新的工具白名单（省略 = 不变；空数组 = 清空声明）")),
            "required",
            List.of("name")),
        ToolSpec.level(AccessToken.DEFAULT, false, true),
        WRITE_MANIFEST,
        context -> {
          String name = strArg(context, "name");
          if (name == null) {
            return ToolResult.error(SkillException.INVALID_ARGUMENTS, "name 为必填参数");
          }
          SkillAccess access = SkillAccess.of(context, catalog, skillsRoot);
          SkillAccess.WriteResult edited =
              access.edit(
                  name,
                  strArg(context, "description"),
                  // body 要区分“没给”（保持不变）与“给了空串”（清空正文），故不走 strArg 的空白归一
                  context.arguments().containsKey("body")
                      ? String.valueOf(context.arguments().get("body"))
                      : null,
                  context.arguments().containsKey("allowedTools")
                      ? toolSetArg(context, "allowedTools")
                      : null);
          return ToolResult.ok(writeBody(edited));
        });
  }

  /**
   * 写结果的对外 JSON：技能元数据 + 可选**披露**（D1）。写成功但目录重扫失败时，结果里必须说清"写成了"—— 否则模型会把一次成功的写当成失败并重试（撞 {@code
   * SKILL_EXISTS}，两条账互相矛盾）。
   */
  private static String writeBody(SkillAccess.WriteResult result) {
    Map<String, Object> body = new LinkedHashMap<>(metadataBody(result.skill()));
    result.disclosure().ifPresent(text -> body.put("disclosure", text));
    return toJson(body);
  }

  private static Map<String, Object> metadataOf(SkillAccess access, Skill skill) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("name", skill.name());
    row.put("description", skill.description());
    row.put("allowedTools", List.copyOf(skill.allowedTools()));
    row.put("editable", access.editableScope().allowsDir(skill.skillFile()));
    row.put("directory", skill.directory().toString());
    return row;
  }

  /** 创建/修改后的元数据回执（不含正文——正文写入是调用方给的，回执重复一遍纯属浪费 token）。 */
  private static Map<String, Object> metadataBody(Skill skill) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", skill.name());
    body.put("description", skill.description());
    body.put("allowedTools", List.copyOf(skill.allowedTools()));
    body.put("directory", skill.directory().toString());
    return body;
  }

  private static String strArg(ToolContext context, String name) {
    Object value = context.arguments().get(name);
    if (value == null) {
      return null;
    }
    String text = value instanceof String string ? string : String.valueOf(value);
    return text.isBlank() ? null : text;
  }

  /** 整数参数：缺失 → 0（调用方按缺省处理）；存在但非数字 → {@link SkillException#INVALID_ARGUMENTS}（不静默用缺省）。 */
  private static int intArg(ToolContext context, String name) {
    Object value = context.arguments().get(name);
    if (value == null) {
      return 0;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value).strip());
    } catch (NumberFormatException e) {
      throw new SkillException(SkillException.INVALID_ARGUMENTS, "参数 " + name + " 必须是整数: " + value);
    }
  }

  /**
   * {@code allowedTools} 参数：数组（元素为字符串）或逗号分隔的单字符串都接受（模型两种写法都常见）；字段缺失 → {@code null}
   * （“这一项不动/不声明”），空数组 → 空集（“清空声明”）——两者是不同取值，不合并。
   */
  private static Set<String> toolSetArg(ToolContext context, String name) {
    Object value = context.arguments().get(name);
    if (value == null) {
      return null;
    }
    List<String> items = new ArrayList<>();
    if (value instanceof List<?> list) {
      for (Object item : list) {
        if (item == null) {
          throw new SkillException(SkillException.INVALID_ARGUMENTS, name + " 的元素不能为 null");
        }
        items.add(String.valueOf(item));
      }
    } else if (value instanceof String text) {
      for (String piece : text.split(",")) {
        items.add(piece);
      }
    } else {
      throw new SkillException(
          SkillException.INVALID_ARGUMENTS, name + " 必须是字符串数组（或逗号分隔的字符串）: " + value);
    }
    return Set.copyOf(items);
  }

  private static String toJson(Object payload) {
    try {
      return JSON.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new SkillException(
          SkillException.INVALID_ARGUMENTS, "技能工具结果序列化失败: " + e.getMessage(), e);
    }
  }

  /**
   * 共同骨架：把 {@link SkillException} 转成错误码（权限拒绝仍走 {@code ResourceDeniedException}，由唯一入口转码）。
   *
   * <p><b>为什么是 {@code final}</b>：本类曾为"可被提议"的子类（{@code ProposableSkillTool}）而开放继承——本类由 6 个技能工具
   * <b>共用</b>（读写都有），而那个标记只属于写入类那 2 个，所以标记必须落在<b>具体工具</b>上。2026-10-16 随标记位（{@code
   * DecisionProposable}）一并回退：进包口径改成"工具符合条件就自动进包"（设计 §5 裁定 1/6），不再需要声明位，也就不再需要子类。
   */
  private static final class SkillTool implements AgentTool {

    private final String name;
    private final String description;
    private final Map<String, Object> schema;
    private final ToolSpec spec;
    private final ResourceManifest manifest;
    private final Function<ToolContext, ToolResult> body;

    SkillTool(
        String name,
        String description,
        Map<String, Object> schema,
        ToolSpec spec,
        ResourceManifest manifest,
        Function<ToolContext, ToolResult> body) {
      this.name = name;
      this.description = description;
      this.schema = schema;
      this.spec = spec;
      this.manifest = manifest;
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
     * EI_EXPOSE_REP 抑制：{@code schema} 是 {@code Map.of}/{@code List.of}/字符串/装箱标量组成的<b>深不可变</b>树 （与
     * {@code ShellTool.SCHEMA} 同款理由），装配期定型后不再改写；为它做防御性拷贝只会在每次工具装配路径上白分配一棵新树。
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

    /** 事件里只落元数据（正文长度等），不把整篇技能正文灌进事件库——落账要能对账，不是复制一份内容。 */
    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
      Map<String, Object> args = context.arguments();
      if (!SKILL_CREATE.equals(name) && !SKILL_EDIT.equals(name)) {
        return args;
      }
      Map<String, Object> ledger = new LinkedHashMap<>();
      Object skillName = args.get("name");
      if (skillName != null) {
        ledger.put("name", skillName);
      }
      Object description = args.get("description");
      if (description != null) {
        ledger.put("description", description);
      }
      Object body = args.get("body");
      ledger.put("bodyChars", body == null ? 0 : String.valueOf(body).length());
      Object tools = args.get("allowedTools");
      if (tools != null) {
        ledger.put("allowedTools", tools);
      }
      return Map.copyOf(ledger);
    }

    @Override
    public ToolResult execute(ToolContext context) {
      try {
        return body.apply(context);
      } catch (SkillException e) {
        return ToolResult.error(e.code(), e.getMessage());
      }
    }
  }
}
