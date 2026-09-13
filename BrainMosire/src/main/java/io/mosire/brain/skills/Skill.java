package io.mosire.brain.skills;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 一个技能的<b>第一档元数据</b>（开发计划 §4.5 渐进披露）：只承载目录渲染与权限映射所需的最小事实，<b>不含正文</b>—— 正文按需经 {@link
 * SkillCatalog#load(String)} 读取。这是 Token Economy（R1）的第一道闸门：没被激活的技能，每回合只花一行目录的 token，而不是整篇 SKILL.md。
 *
 * <p>不变量（frontmatter 校验，计划 §4.5）：
 *
 * <ul>
 *   <li>{@code name} ≡ 技能目录名——目录布局即身份，SKILL.md 被复制到别处后不许继续自称原名（否则 catalog 里会悄悄出现 两个同名技能）；
 *   <li>{@code description} 长度 1–1024——这是 <b>frontmatter 校验</b>；渲染期还有一道 {@link
 *       SkillCatalog#MAX_ENTRY_CHARS} 的复合行截断（name + description + allowed-tools），两者是独立约束，互不代替。
 * </ul>
 *
 * <p>三档锚点都在本记录上：第一档 = 本记录自身；第二档正文 = {@link #skillFile()}；第三档 {@code references/}、 {@code scripts/}
 * 相对 {@link #directory()} 按需读取——本类不预读、不缓存，何时拉取由调用方决定。
 *
 * @param name 技能名（≡ 目录名）
 * @param description 一句话用途（1–1024 字符，UTF-16 code unit 口径）
 * @param allowedTools 该技能声明的工具白名单（空集 = 不声明约束）；按不可变快照保存
 * @param directory 技能根目录（{@code <skillsDir>/<name>}）
 */
public record Skill(String name, String description, Set<String> allowedTools, Path directory) {

  /** 技能正文文件名（技能目录内）：第二档披露的落点。 */
  public static final String SKILL_FILE_NAME = "SKILL.md";

  public static final int MIN_DESCRIPTION_LENGTH = 1;
  public static final int MAX_DESCRIPTION_LENGTH = 1024;

  public Skill {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(directory, "directory");
    allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
    Path file = skillFileOf(directory);
    String dirName = Objects.requireNonNullElse(directory.getFileName(), directory).toString();
    if (!name.equals(dirName)) {
      throw new IllegalArgumentException(
          "技能名必须与目录名一致：frontmatter name=" + name + "，目录名=" + dirName + "（" + file + "）");
    }
    if (description.length() < MIN_DESCRIPTION_LENGTH
        || description.length() > MAX_DESCRIPTION_LENGTH) {
      throw new IllegalArgumentException(
          "description 长度须在 "
              + MIN_DESCRIPTION_LENGTH
              + "–"
              + MAX_DESCRIPTION_LENGTH
              + " 之间（当前 "
              + description.length()
              + "）："
              + file);
    }
  }

  /** 第二档披露的落点：正文文件 {@code <directory>/SKILL.md}。 */
  public Path skillFile() {
    return skillFileOf(directory);
  }

  /**
   * 把本技能声明的 {@code allowed-tools} 映射成调用者的工具权限约束（计划 §4.5：{@code allowed-tools} → 该技能调用者权限约束）。
   *
   * <p>语义是<b>单调收紧</b>（红线 D9 的子集关系）：只做交集、永不放宽——
   *
   * <ul>
   *   <li>技能未声明 allowed-tools（空集）→ 原样返回调用者上下文（不产生任何约束）；
   *   <li>调用者白名单是通配 → 收紧为技能声明的集合；
   *   <li>调用者已有具体白名单 → 取交集（技能不能把自己没有的工具"要"过来）；
   *   <li>调用者的 {@code deniedTools} 与 destructive/sensitive/readOnly 标志原样保留——拒绝优先，且技能无权解除。
   * </ul>
   *
   * <p>只改写权限维度：身份（{@code caller}）、配置与本次调用参数原样传递。
   *
   * @param caller 一次工具调用的上下文
   * @return 权限被技能收紧后的新上下文；技能无声明时返回入参本身
   */
  public ToolContext constrain(ToolContext caller) {
    Objects.requireNonNull(caller, "caller");
    if (allowedTools.isEmpty()) {
      return caller;
    }
    AgentPermissionSet callerPermissions = caller.permissions();
    Set<String> effective = new LinkedHashSet<>();
    for (String tool : allowedTools) {
      if (callerPermissions.isToolAllowed(tool)) {
        effective.add(tool);
      }
    }
    AgentPermissionSet narrowed =
        new AgentPermissionSet(
            callerPermissions.grantedToken(),
            effective,
            callerPermissions.deniedTools(),
            callerPermissions.destructiveAllowed(),
            callerPermissions.sensitiveAllowed(),
            callerPermissions.readOnly());
    // 身份<b>逐字保留</b>：技能只收窄<b>工具白名单</b>（narrowed），不收窄档位——丢掉 identity 会让这次调用退回
    // "身份未穿透"的缺省（FULL），一个技能就能绕开不完全权限档的命令闸（S6 的红线：身份随收窄只减不增、绝不重置）
    return new ToolContext(
        caller.caller(), narrowed, caller.config(), caller.arguments(), caller.identity());
  }

  private static Path skillFileOf(Path directory) {
    return directory.resolve(SKILL_FILE_NAME);
  }
}
