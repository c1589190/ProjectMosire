package io.mosire.brain.skills;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.tool.ToolContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * <b>技能能力的唯一执行面</b>：把“一个调用者能对技能做什么”收成一处——可见面内的列表/检索/读正文/读资源，可编辑面内的创建/修改。 Skill 工具（{@code
 * skill_*}）与上下文预注入（{@code SKILL_INDEX} / 预载正文）都经它说话，不存在第二条读盘/判定路径。
 *
 * <p><b>权限从哪来、判定怎么做</b>：可见面/可编辑面取调用者的权限集第 7 维（{@link SkillPermissions}）。工具面上还有第二道同源判定： 每次真正读写某个路径前调
 * {@link io.mosire.agentlib.permission.ResourceAuthorizer#require}（宿主在唯一入口注入，见 {@code AgentTool}
 * 的资源 SPI）——它用的仍是同一个权限集、同一段 {@link ResourceScope#allows} 语义，只是把“谁判”留给宿主。装配面（没有工具上下文） 用权限集直判同一条规则。
 *
 * <p><b>“未知”与“不可见”同码</b>（{@link SkillException#SKILL_NOT_FOUND}）：两者若可分，工具面就成了隐藏技能的
 * 存在性探测器。判定顺序也因此固定为<b>先范围后存在</b>——越权者拿不到“这个技能存不存在”这一个 bit。
 *
 * <p><b>写入（{@link #create} / {@link #edit}）的三条纪律</b>：
 *
 * <ol>
 *   <li><b>先校验后落盘</b>：内容过 {@link SkillLoader#parse}（与读盘同一段解析）才允许创建目录/写文件，失败时盘上不留半成品；
 *   <li><b>原子替换</b>：同目录临时文件 + {@code ATOMIC_MOVE}（不支持时退回 {@code REPLACE_EXISTING}），不存在“写一半的
 *       SKILL.md”被下一次扫描读到；
 *   <li><b>写完即重扫</b>：{@link #refresh()} 失败一律响亮（{@link SkillException#SKILL_RELOAD_FAILED}）——
 *       “写成功但列表里看不见”是本项目最坏的静默形态之一。
 * </ol>
 *
 * <p><b>刷新时机</b>：工具面每次读之前重扫（{@link #search}/{@link #loadBody}/{@link #readResource} 内部重扫； {@code
 * skill_list} 由工具先调 {@link #refresh()} 再 {@link #visibleSkills()}——它同时是“扫描一次”的入口），
 * 于是<b>上一次写入立刻可见</b>（含其它进程写的：父/子进程各有一份快照）。装配面（{@link #visibleSkills()} / {@link #listing()} /
 * {@link #preloadBody}）<b>不重扫</b>：那是每回合都走的路径，目录 I/O 的代价与收益不成比例。
 *
 * <p><b>token 预算</b>：{@link #MAX_BODY_CHARS} 是单次正文返回的字符硬顶（超出截断并附披露行）；{@link #MAX_RESOURCE_CHARS}
 * 同理管第三档资源；检索结果的 snippet 各自收短（{@link #SEARCH_SNIPPET_CHARS}）。目录档的预算在 {@link SkillCatalog}。
 *
 * <p><b>线程安全</b>：实例不可变（字段全 final），可并发使用；共享的 {@link SkillCatalog} 自身的快照语义见其类注释（写路径假设
 * 调用方串行——与运行时“同一实例同一时刻一个回合”的约定一致）。
 */
public final class SkillAccess {

  /** 单次返回的技能正文上限（字符）：全文更长时截断并附一行披露（模型据此知道“还有内容”）。 */
  public static final int MAX_BODY_CHARS = 20_000;

  /** 单次返回的技能资源文件上限（字符），口径同 {@link #MAX_BODY_CHARS}。 */
  public static final int MAX_RESOURCE_CHARS = 20_000;

  /** 检索命中行的摘要长度（字符）。 */
  public static final int SEARCH_SNIPPET_CHARS = 200;

  /** 检索缺省条数上限。 */
  public static final int DEFAULT_SEARCH_LIMIT = 10;

  /** 检索条数上限的硬顶（模型可以要更多，但不会超过这个数——结果是进上下文的，不能无界）。 */
  public static final int MAX_SEARCH_LIMIT = 50;

  /** 正文超限时的披露行（模型必须知道它读到的不是全文）。 */
  static final String TRUNCATION_NOTICE = "\n\n…（正文超长已截断，完整内容见技能目录内的 SKILL.md）";

  /** 技能名的形态（≡ 目录名，见 {@link SkillPermissions#NAME_PATTERN}）。 */
  private static final Pattern NAME = Pattern.compile(SkillPermissions.NAME_PATTERN);

  private final SkillCatalog catalog;
  private final Path root;
  private final ResourceScope visible;
  private final ResourceScope editable;
  private final ResourceAuthorizerRef authorizer;

  private SkillAccess(
      SkillCatalog catalog,
      Path root,
      ResourceScope visible,
      ResourceScope editable,
      ResourceAuthorizerRef authorizer) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.root = canonicalRoot(Objects.requireNonNull(root, "root"));
    this.visible = Objects.requireNonNull(visible, "visible");
    this.editable = Objects.requireNonNull(editable, "editable");
    this.authorizer = authorizer;
  }

  /**
   * 装配面入口（无工具上下文）：范围直判。
   *
   * @param permissions 调用者权限集（决定可见/可编辑面）
   * @param catalog 技能目录（与本访问面共享同一实例，激活/快照状态才连贯）
   * @param root 技能根目录（{@code <skillsDir>}）
   */
  public static SkillAccess of(AgentPermissionSet permissions, SkillCatalog catalog, Path root) {
    return new SkillAccess(
        catalog,
        root,
        SkillPermissions.visible(permissions),
        SkillPermissions.editable(permissions),
        null);
  }

  /**
   * 工具面入口：范围来自同一次调用的权限集，逐路径判定交给宿主注入的判定者（{@link ToolContext#resources()}）。
   *
   * <p>{@code context} 由唯一入口拼装，模型改不了其中的权限集与判定者（见 {@code ToolContext} 的类注释）。
   */
  public static SkillAccess of(ToolContext context, SkillCatalog catalog, Path root) {
    Objects.requireNonNull(context, "context");
    return new SkillAccess(
        catalog,
        root,
        SkillPermissions.visible(context.permissions()),
        SkillPermissions.editable(context.permissions()),
        new ResourceAuthorizerRef(context));
  }

  /** 技能根目录（realpath 优先；判定与执行同源）。 */
  public Path root() {
    return root;
  }

  /** 共享的技能目录快照（诊断用）。 */
  public SkillCatalog catalog() {
    return catalog;
  }

  /** 本次访问面的可见范围（诊断/审计用）。 */
  public ResourceScope visibleScope() {
    return visible;
  }

  /** 本次访问面的可编辑范围（诊断/审计用；恒 ⊆ {@link #visibleScope()}）。 */
  public ResourceScope editableScope() {
    return editable;
  }

  /**
   * 重扫技能根目录，让后续列表/检索/加载看到盘上最新状态。
   *
   * <p>调用时机：每次技能工具读写之前（工具面保证“上一次写入立刻可见”，含<b>其它进程</b>写的——如父进程与子进程各有一份快照）。 目录不存在 =
   * 空目录（合法，不是错误）；<b>目录里有一个坏技能 = 响亮失败</b>（扫描的既有语义：坏掉与没写必须可分）。
   *
   * @throws SkillException {@link SkillException#SKILL_RELOAD_FAILED} 扫描失败
   */
  public void refresh() {
    try {
      catalog.scanTolerant(root);
    } catch (RuntimeException e) {
      throw new SkillException(
          SkillException.SKILL_RELOAD_FAILED, "技能目录无法扫描（" + root + "）: " + e.getMessage(), e);
    }
  }

  /**
   * 最近一次扫描里<b>坏掉因而不可见</b>的技能（名字 → 原因）：工具面把它放进结果里披露。
   *
   * <p>为什么不是异常：坏技能只影响它自己——其余健康技能的列表/检索/加载照常，坏掉这件事靠结果里的这一项“响亮可见”， 不靠把整次调用打回去（那样一个手改坏的文件就能让技能面整体不可用）。
   */
  public Map<String, String> broken() {
    return catalog.broken();
  }

  /** 可见技能（扫描序；不含正文）。调用方自行决定是否先 {@link #refresh()}。 */
  public List<Skill> visibleSkills() {
    List<Skill> hits = new ArrayList<>();
    for (Skill skill : catalog.catalog()) {
      if (visible.allowsDir(skill.skillFile())) {
        hits.add(skill);
      }
    }
    return List.copyOf(hits);
  }

  /** 可见技能的目录档文本（扫描序 + 预算淘汰；正文一字不进）。 */
  public String listing() {
    return catalog.listing(skill -> visible.allowsDir(skill.skillFile()));
  }

  /**
   * 关键词检索（本期<b>只做关键词</b>，不引 embedding/语义重排）。
   *
   * <p>匹配字段：<b>技能名 / description / 正文</b>三者拼接后的文本；大小写不敏感；查询按空白切成多个词，<b>全部命中</b>才算匹配
   * （AND）。只检索可见技能——隐藏技能的名字都不会出现在结果里。
   *
   * @param query 查询串（空白 → 空结果，不是错误）
   * @param limit 条数上限（≤0 取 {@link #DEFAULT_SEARCH_LIMIT}，上限 {@link #MAX_SEARCH_LIMIT}）
   */
  public SearchResult search(String query, int limit) {
    String needle = query == null ? "" : query.strip();
    if (needle.isEmpty()) {
      return new SearchResult(List.of(), Map.of(), 0);
    }
    refresh();
    int capped = limit <= 0 ? DEFAULT_SEARCH_LIMIT : Math.min(limit, MAX_SEARCH_LIMIT);
    List<String> terms = new ArrayList<>();
    for (String term : needle.toLowerCase(Locale.ROOT).split("\\s+")) {
      if (!term.isEmpty()) {
        terms.add(term);
      }
    }
    List<Skill> candidates = visibleSkills();
    List<SearchHit> hits = new ArrayList<>();
    Map<String, String> unreadable = new LinkedHashMap<>();
    int scanned = 0;
    for (Skill skill : candidates) {
      scanned++;
      String body;
      try {
        body = SkillLoader.readBody(skill);
      } catch (RuntimeException e) {
        // 单个技能读不动不静默：进结果里的 unreadable 列表（模型看得见“这个技能这轮没参与检索”）
        unreadable.put(skill.name(), shortReason(e));
        continue;
      }
      String name = skill.name().toLowerCase(Locale.ROOT);
      String description = skill.description().toLowerCase(Locale.ROOT);
      String text = body.toLowerCase(Locale.ROOT);
      String matchedIn = matchedField(terms, name, description, text);
      if (matchedIn == null) {
        continue;
      }
      hits.add(
          new SearchHit(
              skill.name(),
              skill.description(),
              matchedIn,
              snippetOf(body, skill.description(), terms)));
      if (hits.size() >= capped) {
        break;
      }
    }
    return new SearchResult(List.copyOf(hits), Map.copyOf(unreadable), scanned);
  }

  /**
   * 装配面预载正文（{@code LOADED_SKILLS} 层）：<b>不重扫</b>——每回合组装路径上不做目录 I/O，快照由工具面/装配期刷新。
   *
   * <p>与 {@link #loadBody(String)} 的区别就这一条（重扫与否）：预载是“每回合都走”的路径，重扫的代价与收益不成比例； 而工具面是显式动作，必须看见上一次写入。
   *
   * @param skill 可见技能（调用方已按 {@link #visibleSkills()} 过滤；这里仍复判一次可见面，双保险）
   */
  public String preloadBody(Skill skill) {
    Objects.requireNonNull(skill, "skill");
    requireRead(skill.skillFile());
    return truncate(catalog.load(skill.name()), MAX_BODY_CHARS);
  }

  /**
   * 读一个可见技能的正文（第二档披露）。<b>调用即激活</b>：正文随工具结果留在当前会话（会话历史），后续回合照常回灌。
   *
   * @throws SkillException 未知或不可见（同码）、读盘失败、重扫失败
   */
  public String loadBody(String name) {
    refresh();
    Skill skill = requireVisible(name);
    try {
      return truncate(SkillLoader.readBody(skill), MAX_BODY_CHARS);
    } catch (RuntimeException e) {
      throw new SkillException(
          SkillException.SKILL_READ_FAILED,
          "技能正文读取失败（" + skill.skillFile() + "）: " + e.getMessage(),
          e);
    }
  }

  /**
   * 读技能目录内的一个资源文件（第三档披露：{@code references/}、{@code scripts/} 等）。
   *
   * <p>路径必须落在<b>可见的技能目录</b>之内（解析后仍在目录内，{@code ..} 逃逸一律拒）；单文件内容上限 {@link
   * #MAX_RESOURCE_CHARS}。二进制文件不做探测（按 UTF-8 读，坏字节由解码器替换为 U+FFFD）——本工具面向文本文档， 需要在沙箱里跑脚本的场景走 shell 工具。
   *
   * @param name 技能名
   * @param relativePath 技能目录内的相对路径（如 {@code references/api.md}）
   */
  public String readResource(String name, String relativePath) {
    Objects.requireNonNull(relativePath, "relativePath");
    refresh();
    Skill skill = requireVisible(name);
    if (relativePath.isBlank()) {
      throw new SkillException(SkillException.INVALID_ARGUMENTS, "path 不能为空");
    }
    Path base = skill.directory().toAbsolutePath().normalize();
    Path target;
    try {
      target = base.resolve(relativePath).normalize();
    } catch (RuntimeException e) {
      throw new SkillException(
          SkillException.INVALID_ARGUMENTS, "path 不是合法相对路径: " + relativePath, e);
    }
    if (!target.startsWith(base) || target.equals(base)) {
      throw new SkillException(
          SkillException.INVALID_ARGUMENTS, "path 必须落在技能目录内（不得用 .. 逃逸）: " + relativePath);
    }
    requireRead(target);
    if (!Files.isRegularFile(target)) {
      throw new SkillException(SkillException.SKILL_NOT_FOUND, "技能资源不存在: " + relativePath);
    }
    try {
      return truncate(Files.readString(target, StandardCharsets.UTF_8), MAX_RESOURCE_CHARS);
    } catch (IOException e) {
      throw new SkillException(
          SkillException.SKILL_READ_FAILED, "技能资源读取失败（" + target + "）: " + e.getMessage(), e);
    }
  }

  /**
   * 在可编辑面内创建一份新技能文档（{@code <root>/<name>/SKILL.md}）。
   *
   * @param name 技能名（≡ 目录名，形态见 {@link SkillPermissions#NAME_PATTERN}）
   * @param description 一句话用途（1–1024，单行）
   * @param body 正文（可空——空正文是合法技能，只是信息量少）
   * @param allowedTools 该技能声明的工具白名单（null = 不声明约束）；只影响技能自己声明的约束，不改变调用者的权限集
   * @throws SkillException 参数非法 / 无编辑权限（走 {@code ResourceDeniedException}）/ 已存在 / 校验失败 / 写失败 / 重扫失败
   */
  public Skill create(String name, String description, String body, Set<String> allowedTools) {
    Path file = targetFile(name);
    requireWrite(file);
    if (Files.exists(skillDirectory(name))) {
      throw new SkillException(SkillException.SKILL_EXISTS, "技能已存在（要改内容请用 skill_edit）: " + name);
    }
    String text = compose(name, description, body, allowedTools);
    validate(text, skillDirectory(name));
    try {
      Files.createDirectories(skillDirectory(name));
    } catch (IOException e) {
      throw new SkillException(
          SkillException.SKILL_WRITE_FAILED,
          "技能目录创建失败（" + skillDirectory(name) + "）: " + e.getMessage(),
          e);
    }
    writeAtomically(skillDirectory(name), file, text);
    refresh();
    return catalog
        .lookup(parsedName(name))
        .orElseThrow(
            () -> new SkillException(SkillException.SKILL_RELOAD_FAILED, "写入后目录里读不到该技能: " + name));
  }

  /**
   * 修改一份已存在的技能文档：{@code null} 表示“这一项不动”，非 null 覆盖。
   *
   * <p>语义是<b>整份重写</b>（frontmatter 由本方法重新渲染），因此 body 传 null 时保留原正文——不做行级补丁（技能文档是
   * 模型写的整体，行级合并的边界情况远多于收益）。
   *
   * @throws SkillException 参数非法 / 无编辑权限 / 不存在（或不可见）/ 校验失败 / 写失败 / 重扫失败
   */
  public Skill edit(String name, String description, String body, Set<String> allowedTools) {
    Path file = targetFile(name);
    requireWrite(file);
    if (description == null && body == null && allowedTools == null) {
      throw new SkillException(
          SkillException.INVALID_ARGUMENTS, "至少要给出 description / body / allowedTools 之一");
    }
    refresh();
    Skill existing =
        catalog
            .lookup(name)
            .orElseThrow(
                () -> new SkillException(SkillException.SKILL_NOT_FOUND, "技能不存在或不可见: " + name));
    String currentBody;
    try {
      currentBody = SkillLoader.readBody(existing);
    } catch (RuntimeException e) {
      throw new SkillException(
          SkillException.SKILL_READ_FAILED,
          "技能正文读取失败（" + existing.skillFile() + "）: " + e.getMessage(),
          e);
    }
    String nextDescription = description == null ? existing.description() : description;
    Set<String> nextTools = allowedTools == null ? existing.allowedTools() : allowedTools;
    String nextBody = body == null ? currentBody : body;
    String text = compose(name, nextDescription, nextBody, nextTools);
    validate(text, skillDirectory(name));
    writeAtomically(skillDirectory(name), file, text);
    refresh();
    return catalog
        .lookup(name)
        .orElseThrow(
            () -> new SkillException(SkillException.SKILL_RELOAD_FAILED, "写入后目录里读不到该技能: " + name));
  }

  /** 可见性判定 + 元数据（未知与不可见同码，见类 javadoc）。 */
  private Skill requireVisible(String name) {
    Path file = targetFile(name);
    requireRead(file);
    return catalog
        .lookup(name)
        .orElseThrow(
            () -> new SkillException(SkillException.SKILL_NOT_FOUND, "技能不存在或不可见: " + name));
  }

  /** 技能名 → 技能目录（形态校验在此，路径穿越在形态这一关就死掉）。 */
  private Path skillDirectory(String name) {
    if (name == null || name.isBlank()) {
      throw new SkillException(SkillException.INVALID_ARGUMENTS, "技能名不能为空");
    }
    String trimmed = name.strip();
    if (!NAME.matcher(trimmed).matches()) {
      throw new SkillException(
          SkillException.INVALID_ARGUMENTS,
          "技能名必须匹配 " + SkillPermissions.NAME_PATTERN + "（小写字母/数字/短横线，且不含路径分隔符）: " + name);
    }
    return root.resolve(trimmed);
  }

  private Path targetFile(String name) {
    return skillDirectory(name).resolve(Skill.SKILL_FILE_NAME);
  }

  /** 读权限：工具面走宿主注入的判定者（失败即 {@link ResourceDeniedException}），装配面用可见面直判。 */
  private void requireRead(Path file) {
    if (authorizer != null) {
      authorizer.require(Operation.READ, SkillNamespaces.SKILLS, file);
      return;
    }
    if (!visible.allowsDir(file)) {
      throw denied(Operation.READ, SkillNamespaces.SKILLS, file, visible);
    }
  }

  /** 写权限：口径同上，命名空间是 {@code skills-edit}。 */
  private void requireWrite(Path file) {
    if (authorizer != null) {
      authorizer.require(Operation.WRITE, SkillNamespaces.SKILLS_EDIT, file);
      return;
    }
    if (!editable.allowsDir(file)) {
      throw denied(Operation.WRITE, SkillNamespaces.SKILLS_EDIT, file, editable);
    }
  }

  private static ResourceDeniedException denied(
      Operation operation, String namespace, Path file, ResourceScope scope) {
    return new ResourceDeniedException(
        operation
            + " "
            + ResourceId.of(namespace, file.toString()).fullId()
            + " 不在调用者的可达面内: 调用者可达="
            + scope.summary()
            + "（这是显式表态，不是'未表态'）");
  }

  /** 组一份 SKILL.md 文本（frontmatter + 正文）；description/allowed-tools 的单行约束在这里响亮失败。 */
  private static String compose(
      String name, String description, String body, Set<String> allowedTools) {
    String cleanDescription = requireSingleLine(description, "description");
    if (cleanDescription.isBlank()) {
      throw new SkillException(SkillException.INVALID_ARGUMENTS, "description 不能为空");
    }
    StringBuilder text = new StringBuilder();
    text.append("---\n");
    text.append("name: ").append(name.strip()).append('\n');
    text.append("description: ").append(cleanDescription).append('\n');
    if (allowedTools != null && !allowedTools.isEmpty()) {
      Set<String> sorted = new TreeSet<>();
      for (String tool : allowedTools) {
        String clean = requireSingleLine(tool, "allowed-tools 元素");
        if (clean.isBlank()) {
          throw new SkillException(SkillException.INVALID_ARGUMENTS, "allowed-tools 元素不能为空白");
        }
        if (clean.indexOf(',') >= 0) {
          throw new SkillException(
              SkillException.INVALID_ARGUMENTS, "allowed-tools 元素不能含逗号（逗号是分隔符）: " + tool);
        }
        sorted.add(clean);
      }
      text.append("allowed-tools: ").append(String.join(", ", sorted)).append('\n');
    }
    text.append("---\n\n");
    text.append(body == null ? "" : body.strip());
    text.append('\n');
    return text.toString();
  }

  private static String requireSingleLine(String value, String field) {
    Objects.requireNonNull(value, field);
    String stripped = value.strip();
    if (stripped.indexOf('\n') >= 0 || stripped.indexOf('\r') >= 0) {
      throw new SkillException(SkillException.INVALID_ARGUMENTS, field + " 必须是单行文本");
    }
    return stripped;
  }

  /** 用读盘同一段解析校验待写入的文本；失败是 {@code SKILL_INVALID}（不落盘）。 */
  private static void validate(String text, Path skillDirectory) {
    try {
      SkillLoader.parse(text, skillDirectory);
    } catch (IllegalArgumentException e) {
      throw new SkillException(SkillException.SKILL_INVALID, "技能文档校验失败: " + e.getMessage(), e);
    }
  }

  /** 已校验过的技能名（{@link #skillDirectory(String)} 保证形态，这里只做去掉首尾空白）。 */
  private static String parsedName(String name) {
    return name.strip();
  }

  /** 临时文件 + 原子替换（同一目录内，保证同文件系统）；失败即 {@code SKILL_WRITE_FAILED}，不留半成品。 */
  private static void writeAtomically(Path directory, Path file, String text) {
    Path temp = null;
    try {
      temp = Files.createTempFile(directory, ".SKILL-", ".tmp");
      Files.writeString(temp, text, StandardCharsets.UTF_8);
      try {
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
      }
      temp = null;
    } catch (IOException e) {
      throw new SkillException(
          SkillException.SKILL_WRITE_FAILED, "技能文档写入失败（" + file + "）: " + e.getMessage(), e);
    } finally {
      if (temp != null) {
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
          // 临时文件清理失败不改变“写入失败”的结论；残留文件以 .SKILL- 前缀可见，不影响扫描（只扫子目录）
        }
      }
    }
  }

  private static String matchedField(
      List<String> terms, String name, String description, String body) {
    boolean inName = containsAll(name, terms);
    boolean inDescription = containsAll(description, terms);
    boolean inBody = containsAll(body, terms);
    if (inName) {
      return "name";
    }
    if (inDescription) {
      return "description";
    }
    if (inBody) {
      return "body";
    }
    return null;
  }

  private static boolean containsAll(String haystack, List<String> terms) {
    for (String term : terms) {
      if (!haystack.contains(term)) {
        return false;
      }
    }
    return true;
  }

  /** 命中摘要：优先正文里第一个命中词附近的窗口；正文没命中（名字/描述命中）就给描述本身。 */
  private static String snippetOf(String body, String description, List<String> terms) {
    String lower = body.toLowerCase(Locale.ROOT);
    int at = -1;
    for (String term : terms) {
      int found = lower.indexOf(term);
      if (found >= 0 && (at < 0 || found < at)) {
        at = found;
      }
    }
    if (at < 0) {
      return description;
    }
    int start = Math.max(0, at - SEARCH_SNIPPET_CHARS / 4);
    int end = Math.min(body.length(), start + SEARCH_SNIPPET_CHARS);
    String window = body.substring(start, end).replaceAll("\\s+", " ").strip();
    return (start > 0 ? "…" : "") + window + (end < body.length() ? "…" : "");
  }

  private static String truncate(String text, int maxChars) {
    if (text.length() <= maxChars) {
      return text;
    }
    return text.substring(0, maxChars) + TRUNCATION_NOTICE;
  }

  private static String shortReason(RuntimeException e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }

  /**
   * 技能根目录的规范形态：绝对化 + 词法归一，<b>存在时取 realpath</b>。
   *
   * <p>为什么必须与权限前缀同源：权限范围前缀由 {@link ResourceScope#ofDirs} 在根存在时取 realpath，若访问面这一侧拿的是
   * “带软链的写法”，同一个文件会有两个名字，判定与执行就会分叉（“允许的路径”与“打开的路径”不是同一个串）。取不到 realpath
   * （不存在/无权限）就退回词法值——那时判定用的也是词法值，仍然同源。
   */
  public static Path canonicalRoot(Path root) {
    Path absolute = root.toAbsolutePath().normalize();
    try {
      return Files.exists(absolute) ? absolute.toRealPath() : absolute;
    } catch (IOException e) {
      return absolute;
    }
  }

  /**
   * 一次检索的结果。
   *
   * @param hits 命中（扫描序，最多 {@code limit} 条）
   * @param unreadable 正文读不动的可见技能（名字 → 简短原因）——不静默：模型看得见“它这轮没参与检索”
   * @param scanned 参与检索的可见技能数（含读不动的）
   */
  public record SearchResult(List<SearchHit> hits, Map<String, String> unreadable, int scanned) {

    public SearchResult {
      hits = List.copyOf(hits == null ? List.of() : hits);
      unreadable = Map.copyOf(unreadable == null ? Map.of() : unreadable);
    }
  }

  /**
   * 一条检索命中。
   *
   * @param name 技能名
   * @param description 用途
   * @param matchedIn 命中字段（{@code name} / {@code description} / {@code body}）
   * @param snippet 命中处摘要（正文没命中时给 description）
   */
  public record SearchHit(String name, String description, String matchedIn, String snippet) {}

  /**
   * 工具面的“谁判”持有者：只包一层 {@link ToolContext}，把命名空间这个参数补上。
   *
   * <p>单独抽出来是为了让装配面（无判定者）与工具面共用同一份 {@link SkillAccess} 实现——两套实现迟早分叉。
   */
  private static final class ResourceAuthorizerRef {

    private final ToolContext context;

    ResourceAuthorizerRef(ToolContext context) {
      this.context = context;
    }

    void require(Operation operation, String namespace, Path file) {
      context.resources().require(operation, ResourceId.of(namespace, file.toString()));
    }
  }

  /** 未使用的可选读取口（保留给诊断：由名字取可见元数据而不读正文）。 */
  public Optional<Skill> lookupVisible(String name) {
    Skill skill = catalog.lookup(name).orElse(null);
    if (skill == null || !visible.allowsDir(skill.skillFile())) {
      return Optional.empty();
    }
    return Optional.of(skill);
  }

  /** 可见技能名集合（装配期预载用；扫描序）。 */
  public Set<String> visibleNames() {
    Set<String> names = new LinkedHashSet<>();
    for (Skill skill : visibleSkills()) {
      names.add(skill.name());
    }
    return names;
  }
}
