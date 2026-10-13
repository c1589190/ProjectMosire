package io.mosire.workspace;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 挂载名（设计 §4.2）：虚拟路径的第一段，{@code [a-z0-9][a-z0-9-]{0,63}}。
 *
 * <p><b>为什么要卡形态而不是"随便一个字符串"</b>：挂载名同时是①虚拟路径的根、②物理落点的目录名习惯（{@code
 * <dataDir>/workspaces/main}）、③审计事件里的一个字段。三者若允许任意字符，"主 Agent 的 workspace"与"子 Agent 的
 * workspace"就会因为一个空格、一个大写字母而变成两个不同的东西，而人看不出差别。
 *
 * <p><b>与技能名/模板 id 同形</b>（同一个正则）：这三者都直接当目录名用，形态一致就不必在拼路径处各写一套净化。
 *
 * <p>构造即校验（非法形态抛 {@link IllegalArgumentException}）：这是一个值类型，"半合法的挂载名"没有存在意义——挂在
 * 挂载表里的名字必须是能出现在虚拟路径里的名字。
 */
public record MountName(String value) {

  /** 形态约束（与 {@code AgentTemplate} 的实例 id、技能名同形，可直接当目录名用）。 */
  public static final String PATTERN = "[a-z0-9][a-z0-9-]{0,63}";

  private static final Pattern SHAPE = Pattern.compile(PATTERN);

  public MountName {
    value = value == null ? "" : value.strip();
    if (!SHAPE.matcher(value).matches()) {
      throw new IllegalArgumentException("挂载名必须匹配 " + PATTERN + "（实际: '" + value + "'）");
    }
  }

  /** 语法糖（与构造同义，读起来更像"造一个名字"）。 */
  public static MountName of(String value) {
    return new MountName(value);
  }

  /** 是否是可当挂载名用的字符串（给"先问再建"的调用点用，避免用异常做流程控制）。 */
  public static boolean isValid(String value) {
    return value != null && SHAPE.matcher(value.strip()).matches();
  }

  /** 形态校验（解析虚拟路径时用）：不合法即抛 {@link IllegalArgumentException}，不返回 null。 */
  static MountName require(String value) {
    Objects.requireNonNull(value, "value");
    return new MountName(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
