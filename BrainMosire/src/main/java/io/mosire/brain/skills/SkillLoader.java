package io.mosire.brain.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * SKILL.md 的 frontmatter 读取器：手写的行式 YAML 子集解析（不引 YAML 库——零重依赖是本项目既定约束）。
 *
 * <p>支持的结构（够用即止，超集不实现）：
 *
 * <ul>
 *   <li>文件首行必须是分隔行 {@code ---}，其后到下一个 {@code ---} 之间的行是 frontmatter，其余是正文；
 *   <li>{@code key: value} 标量行——按<b>首个</b>冒号切分（值里可以有冒号，如 "用途：审查"），两侧空白去除；
 *   <li>值两侧成对的单/双引号剥除（YAML 里写带冒号的值时的惯用写法）；
 *   <li>{@code allowed-tools} 支持内联逗号分隔（{@code Read, Grep}）与 YAML 块列表（键后跟 {@code - 项} 行）两种写法；
 *   <li>空行与 {@code #} 注释行忽略；未知键忽略——前向兼容其它技能实现新增的字段（license/version/…）， 缺字段要靠校验兜住，不能靠"多字段即报错"。
 * </ul>
 *
 * <p>失败一律抛 {@link IllegalArgumentException} 且消息带文件路径（同 {@code AgentTemplateStore} 的约定：绝不静默跳过）——
 * 技能是运行期可被 Agent 自己写入的内容（{@code learn_skill}），静默跳过等于让"写坏了"和"没写"无从区分。
 */
public final class SkillLoader {

  private static final String DELIMITER = "---";
  private static final char KEY_VALUE_SEPARATOR = ':';
  private static final String LIST_ITEM_PREFIX = "- ";
  private static final String COMMENT_PREFIX = "#";

  private SkillLoader() {}

  /**
   * 只读第一档元数据（frontmatter）：正文一个字节都不进内存，这是渐进披露的省 token 起点。
   *
   * @param skillDirectory 技能根目录（{@code <skillsDir>/<name>}）
   * @return 校验过的技能元数据（name ≡ 目录名、description 1–1024）
   */
  public static Skill readMetadata(Path skillDirectory) {
    Objects.requireNonNull(skillDirectory, "skillDirectory");
    Path file = skillDirectory.resolve(Skill.SKILL_FILE_NAME);
    Document document = read(skillDirectory, file);
    Map<String, List<String>> fields = parseFields(document.frontmatter(), file);
    return new Skill(
        singleValue(fields, "name", file),
        singleValue(fields, "description", file),
        allowedTools(fields),
        skillDirectory);
  }

  /**
   * 第二档：按需读取正文（frontmatter 之后的内容，首尾空白剥离——空白无语义，剥掉让 token 记账可复现）。
   *
   * <p>每次调用都重新读盘、不缓存：技能目录可由 Agent 自己重写（learning loop），缓存会让"刚学的技能"读不到新内容； 而 {@link SkillCatalog}
   * 只在激活时调用本方法，重复读盘的代价远小于陈旧内容进上下文。
   *
   * @param skill 已扫描到的技能元数据（提供落盘位置）
   * @return SKILL.md 正文
   */
  public static String readBody(Skill skill) {
    Objects.requireNonNull(skill, "skill");
    return read(skill.directory(), skill.skillFile()).body();
  }

  /** 一次读盘的结果：frontmatter 原始行 + 正文（正文已 strip）。 */
  private record Document(List<String> frontmatter, String body) {}

  private static Document read(Path skillDirectory, Path file) {
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("技能目录缺少 " + Skill.SKILL_FILE_NAME + "：" + skillDirectory);
    }
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalArgumentException("技能文件无法读取：" + file, e);
    }
    if (lines.isEmpty() || !lines.get(0).strip().equals(DELIMITER)) {
      throw new IllegalArgumentException(
          Skill.SKILL_FILE_NAME + " 必须以 frontmatter 分隔行 " + DELIMITER + " 开头：" + file);
    }
    int end = -1;
    for (int i = 1; i < lines.size(); i++) {
      if (lines.get(i).strip().equals(DELIMITER)) {
        end = i;
        break;
      }
    }
    if (end < 0) {
      throw new IllegalArgumentException("frontmatter 缺少结束分隔行 " + DELIMITER + "：" + file);
    }
    String body = String.join("\n", lines.subList(end + 1, lines.size())).strip();
    return new Document(List.copyOf(lines.subList(1, end)), body);
  }

  /** 行式解析 frontmatter：每个键收成值列表（块列表的 {@code - 项} 续行挂到当前键上）。 */
  private static Map<String, List<String>> parseFields(List<String> lines, Path file) {
    Map<String, List<String>> fields = new LinkedHashMap<>();
    List<String> current = null;
    for (String rawLine : lines) {
      String line = rawLine.strip();
      if (line.isEmpty() || line.startsWith(COMMENT_PREFIX)) {
        continue;
      }
      if (line.startsWith(LIST_ITEM_PREFIX) && current != null) {
        current.add(unquote(line.substring(LIST_ITEM_PREFIX.length()).strip()));
        continue;
      }
      int separator = line.indexOf(KEY_VALUE_SEPARATOR);
      if (separator < 0) {
        throw new IllegalArgumentException("frontmatter 行缺少 ':'：" + file + " → 「" + line + "」");
      }
      String key = line.substring(0, separator).strip().toLowerCase(Locale.ROOT);
      String value = unquote(line.substring(separator + 1).strip());
      current = fields.computeIfAbsent(key, ignored -> new ArrayList<>());
      if (!value.isEmpty()) {
        current.add(value);
      }
    }
    return fields;
  }

  /** 取单值字段：缺失或多值都是坏 frontmatter（多值说明写成了列表，语义不明）。 */
  private static String singleValue(Map<String, List<String>> fields, String key, Path file) {
    List<String> values = fields.get(key);
    if (values == null || values.isEmpty()) {
      throw new IllegalArgumentException("frontmatter 缺少字段 " + key + "：" + file);
    }
    if (values.size() > 1) {
      throw new IllegalArgumentException("frontmatter 字段 " + key + " 只能有一个值：" + file);
    }
    return values.get(0);
  }

  /** {@code allowed-tools}：内联逗号分隔与块列表两种写法都接受，逗号切分仅对本键生效（description 里的逗号不是分隔符）。 */
  private static Set<String> allowedTools(Map<String, List<String>> fields) {
    Set<String> tools = new LinkedHashSet<>();
    for (String value : fields.getOrDefault("allowed-tools", List.of())) {
      for (String tool : value.split(",")) {
        String trimmed = tool.strip();
        if (!trimmed.isEmpty()) {
          tools.add(trimmed);
        }
      }
    }
    return tools;
  }

  /** 剥除值两侧成对的引号（只剥成对的，避免误伤值为 {@code "} 开头的畸形内容）。 */
  private static String unquote(String value) {
    if (value.length() >= 2
        && (value.charAt(0) == '"' || value.charAt(0) == '\'')
        && value.charAt(value.length() - 1) == value.charAt(0)) {
      return value.substring(1, value.length() - 1).strip();
    }
    return value;
  }
}
