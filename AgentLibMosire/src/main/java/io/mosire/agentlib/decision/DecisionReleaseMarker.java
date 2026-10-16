package io.mosire.agentlib.decision;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * <b>「这条调用已经在包 P 里批过了」的带内标记</b>（设计 §5sexies⑥，P3d）：子体执行<b>被批子集</b>时把 {@code {packageId, index}}
 * 挂进这次工具调用的参数里，父侧据此认出"这是包 P 的第 i 条、已批且尚未执行" ⇒ 放行并标记已用。
 *
 * <p><b>为什么必须借参数带、而不是借身份/配置带</b>：入参是<b>唯一</b>由子进程决定、又能原样到达父侧的东西——身份由父侧在建链时绑死（模型给不出、子进程改不了，见 {@code
 * AgentToMcpServer} 的契约），{@code ToolContext.config} 也由建链方注入。{@link
 * io.mosire.agentlib.tool.ToolContext} 与 {@code AgentTool} 是冻结形状（本单不许加字段）⇒ 只剩参数这一条带内信道。
 *
 * <p><b>保留键</b>：{@link #ARGS_KEY} 是本仓自用的一把"保留参数名"。它只在<b>子体→父侧</b>这条桥上被解释（{@link
 * io.mosire.agentlib.tool.ToolCallAuthorizer} 的放行判定），而该判定只有在装配了父侧队列缝时才有值 ⇒ 对外部 MCP 面、进程内管线与
 * 任何未接线装配，这个键<b>与普通参数无异</b>（谁也不会去读它）。
 *
 * <p>★ <b>它到工具面前必须被剥掉</b>（{@link #strip}）：它不是工具的参数，让它进工具本体等于给工具塞一个陌生键。 剥离发生在父侧唯一入口（{@code
 * ToolCallAuthorizer}），见该类的 {@code effective} 上下文。
 *
 * <p><b>密钥纪律</b>：标记里只有包 id 与条目序号（都是 id/序号，不含参数、不含密钥）。
 */
public final class DecisionReleaseMarker {

  /**
   * 保留参数名：值是 {@code {"packageId": "<包 id>", "index": <条目序号>}}。
   *
   * <p>用带前缀的名字 + 单键嵌套对象（而不是两个平铺的通用键名）：参数名是模型可见面，撞名的代价是"某个真工具恰好有个叫 {@code packageId}
   * 的参数"时被误读成放行标记——前缀把它压到实际不可能。
   */
  public static final String ARGS_KEY = "_mosire_decision_release";

  private static final String PACKAGE_KEY = "packageId";
  private static final String INDEX_KEY = "index";

  private DecisionReleaseMarker() {}

  /**
   * 挂上标记（返回<b>新的</b>不可变参数表；原表不动）。
   *
   * @param args 原参数（工具真正要收的那一份）
   * @param packageId 包 id（子体本地那个包的 id，也就是提交时的 {@code requestId}）
   * @param index 条目在包内的身份
   */
  public static Map<String, Object> attach(Map<String, Object> args, String packageId, int index) {
    Objects.requireNonNull(args, "args");
    Objects.requireNonNull(packageId, "packageId");
    Map<String, Object> marked = new LinkedHashMap<>(args);
    Map<String, Object> claim = new LinkedHashMap<>();
    claim.put(PACKAGE_KEY, packageId);
    claim.put(INDEX_KEY, index);
    marked.put(ARGS_KEY, Map.copyOf(claim));
    return Map.copyOf(marked);
  }

  /**
   * 读标记（<b>只读</b>，不改状态、不剥键）：没有标记或形状不是本约定 ⇒ 空值。
   *
   * <p>形状认不出时返回空值（= "这不是一条放行重调"），而<b>不是</b>具名拒：那个键可能只是某个工具自己的普通参数，把
   * "认不出"当"伪造"会让普通调用被拒。真伪由父侧的放行簿判定（{@link SubagentDecisionLedger#release}）。
   */
  public static Optional<Claim> read(Map<String, Object> args) {
    if (args == null || !(args.get(ARGS_KEY) instanceof Map<?, ?> raw)) {
      return Optional.empty();
    }
    Object packageId = raw.get(PACKAGE_KEY);
    Object index = raw.get(INDEX_KEY);
    if (packageId == null || !(index instanceof Number numeric)) {
      return Optional.empty();
    }
    String id = String.valueOf(packageId);
    if (id.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new Claim(id, numeric.intValue()));
  }

  /** 剥掉标记（原参数表逐字保留；没有标记 ⇒ 原样返回同一份引用）。 */
  public static Map<String, Object> strip(Map<String, Object> args) {
    if (args == null || !args.containsKey(ARGS_KEY)) {
      return args;
    }
    Map<String, Object> stripped = new LinkedHashMap<>(args);
    stripped.remove(ARGS_KEY);
    return Map.copyOf(stripped);
  }

  /**
   * 标记的读值（包 id + 条目序号）。
   *
   * @param packageId 包 id（模板，非密钥）
   * @param index 条目序号（包内身份）
   */
  public record Claim(String packageId, int index) {

    public Claim {
      Objects.requireNonNull(packageId, "packageId");
      if (index < 0) {
        throw new IllegalArgumentException("index 不得为负: " + index);
      }
    }
  }
}
