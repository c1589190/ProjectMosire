package io.mosire.agentlib.tool;

import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.ToolGate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * <b>工具调用的唯一入口</b>：权限判定 → 命令闸（硬拒/需审批）→ 执行。
 *
 * <p><b>为什么需要它</b>（2026-09-12 S4 调查实测）：本仓有<b>两个</b>工具调用入口——Brain 管线（{@code
 * AgentPipeline.executeToolCall}）与 MCP 桥（{@code AgentToMcpServer.handleCall}）。前者经 {@link
 * ToolExecutionGuard} 判定，后者<b>直接 {@code tool.execute(context)}、连 {@link ToolExecutionGuard}
 * 都不经</b>（这正是各系统级工具不得不在工具体里 自造 "第二道闸" 的原因）。两条路各判各的，语义必然分叉：任何新判定（审批、资源作用域、未来的额度）只落一条路，另一条就没有它。
 * 本类把"解析工具 → 判定 → 执行"收成一条，两个入口都只调这里。
 *
 * <p><b>四段顺序不可换</b>：
 *
 * <ol>
 *   <li><b>工具不存在</b> ⇒ {@code TOOL_NOT_FOUND}——终局；
 *   <li><b>硬拒</b>（{@link ToolExecutionGuard} 权限判定）⇒ {@code PERMISSION_DENIED}——终局，<b>不进审批</b>。
 *       否则"审批放行"会把一个本就无权执行的调用洗白；
 *   <li><b>命令闸</b>（工具自报 {@link ToolGate}）：{@link ToolGate.Block} ⇒ {@link #COMMAND_BLOCKED}
 *       （同样<b>不进审批</b>：硬拒不可审批，防"审批把硬拒洗白"）；{@link ToolGate.Ask} ⇒ 问人（{@link
 *       ApprovalCoordinator}），未放行 ⇒ {@link #APPROVAL_DENIED}；
 *   <li><b>执行</b>。
 * </ol>
 *
 * <p><b>不装配编排器时 {@code Ask} 一律拒（fail-closed）</b>：{@link #of(ToolExecutionGuard)} / {@link
 * #standard()} 不带编排器，此时"需审批"的调用<b>不可能</b>静默放行——它直接得到 {@link #APPROVAL_DENIED}。
 * 这条与"审批面的任何故障都归拒"同源：本包在任何一条路上都不默认同意。
 *
 * <p><b>不持有的东西</b>：不写事件（审批事件由 {@link ApprovalCoordinator} 发，权限拒绝的审计仍归调用方，与 guard 同口径）；不持有存储。
 */
public final class ToolCallAuthorizer {

  /** 工具自报硬拒（{@link ToolGate.Block}）：终局，不进审批。 */
  public static final String COMMAND_BLOCKED = "COMMAND_BLOCKED";

  /** 审批未放行：拒 / 超时 / 无可用通道 / 通道故障 / 未装配编排器，一律 fail-closed 归此码。 */
  public static final String APPROVAL_DENIED = "APPROVAL_DENIED";

  private final ToolExecutionGuard guard;
  private final ApprovalCoordinator coordinator;
  private final Function<ToolContext, String> digest;

  public ToolCallAuthorizer(ToolExecutionGuard guard) {
    this(guard, null, ToolCallAuthorizer::defaultDigest);
  }

  private ToolCallAuthorizer(
      ToolExecutionGuard guard,
      ApprovalCoordinator coordinator,
      Function<ToolContext, String> digest) {
    this.guard = Objects.requireNonNull(guard, "guard");
    this.coordinator = coordinator;
    this.digest = Objects.requireNonNull(digest, "digest");
  }

  /** 以给定 guard 装配（等价于构造器；供调用点读起来像配置而非 new）。无审批编排器 ⇒ {@code Ask} 拒。 */
  public static ToolCallAuthorizer of(ToolExecutionGuard guard) {
    return new ToolCallAuthorizer(guard);
  }

  /**
   * 以给定 guard + 审批编排器装配。{@code coordinator} 为 {@code null} 时合法（= 上面那个工厂）： 此时 {@link ToolGate.Ask}
   * 一律拒（fail-closed），{@link ToolGate.Allow} 行为逐字不变。
   */
  public static ToolCallAuthorizer of(ToolExecutionGuard guard, ApprovalCoordinator coordinator) {
    return new ToolCallAuthorizer(guard, coordinator, ToolCallAuthorizer::defaultDigest);
  }

  /**
   * 同上，但指定脱敏摘要实现（C 包接 bash 后换成真正的命令脱敏：这是"摘要逻辑只有一处"的注入点， {@link #defaultDigest(ToolContext)}
   * 是缺省件）。摘要进 {@code approval.requested} 事件与提示面， 故实现<b>不得</b>回传命令明文。
   */
  public static ToolCallAuthorizer of(
      ToolExecutionGuard guard,
      ApprovalCoordinator coordinator,
      Function<ToolContext, String> digest) {
    return new ToolCallAuthorizer(guard, coordinator, digest);
  }

  /** 默认装配（默认三要素判定规则）。 */
  public static ToolCallAuthorizer standard() {
    return new ToolCallAuthorizer(new ToolExecutionGuard());
  }

  /**
   * 执行一次工具调用。
   *
   * @param registry 工具注册表
   * @param toolName 工具名（模型给出；不受信）
   * @param context 调用上下文（调用者身份 + 权限集 + 工具配置 + 参数）
   */
  public ToolResult execute(ToolRegistry registry, String toolName, ToolContext context) {
    Optional<ToolResult> denied = guard.denial(registry, toolName, context);
    if (denied.isPresent()) {
      return denied.get();
    }
    AgentTool tool = registry.find(toolName).orElseThrow();
    ToolGate gate = tool.gate(context);
    if (gate == null) {
      // 契约是"缺省 ALLOW"，返回 null 是工具实现坏了：按 fail-closed 处理，不猜它的意思
      return ToolResult.error(
          COMMAND_BLOCKED, "工具的闸位自报为空（ToolGate 不得为 null），按 fail-closed 硬拒: " + toolName);
    }
    switch (gate) {
      case ToolGate.Allow ignored -> {
        // 直放：既有工具（不覆写 gate）行为逐字不变
      }
      case ToolGate.Block blocked -> {
        // 硬拒不进审批：不登记、不发 approval.requested、通道里看不到它（防"审批把硬拒洗白"）
        return ToolResult.error(
            COMMAND_BLOCKED,
            "命令被硬拒（不可审批）: class=" + blocked.classKey() + " reason=" + blocked.reason());
      }
      case ToolGate.Ask ask -> {
        Optional<ToolResult> approvalDenied = askForApproval(tool, context, ask);
        if (approvalDenied.isPresent()) {
          return approvalDenied.get();
        }
      }
    }
    return tool.execute(context);
  }

  /**
   * 需要人裁决的一次调用：由本类（宿主侧）构造 {@link ApprovalRequest} 并交给编排器。
   *
   * <p><b>不可自报身份</b>：{@code tool}/{@code classKey}/{@code callerKey} 全部取自宿主已知的东西
   * （工具实例、工具自报的闸位、调用上下文），模型给不出也改不了。
   *
   * <p>返回空 = 已放行；非空 = 拒（含未装配编排器的 fail-closed 情形）。
   */
  private Optional<ToolResult> askForApproval(
      AgentTool tool, ToolContext context, ToolGate.Ask ask) {
    if (coordinator == null) {
      return Optional.of(
          ToolResult.error(
              APPROVAL_DENIED,
              "需人审批但未装配审批编排器（fail-closed 拒，不静默放行）: tool="
                  + tool.name()
                  + " class="
                  + ask.classKey()));
    }
    long now = System.currentTimeMillis();
    ApprovalRequest request =
        new ApprovalRequest(
            "ap-" + UUID.randomUUID(),
            tool.name(),
            ask.classKey(),
            ask.summary(),
            digest.apply(context),
            now,
            now + coordinator.timeout().toMillis(),
            context.caller().name());
    ApprovalDecision decision = coordinator.decide(request);
    if (decision == ApprovalDecision.APPROVE_ONCE || decision == ApprovalDecision.APPROVE_SESSION) {
      return Optional.empty();
    }
    // decision 为 null（编排器违约）也走到这里：没拿到显式批准就是拒
    return Optional.of(
        ToolResult.error(
            APPROVAL_DENIED,
            "审批未放行（" + decision + "）: tool=" + tool.name() + " class=" + ask.classKey()));
  }

  /**
   * 默认脱敏摘要：{@code sha256:<32 hex>}，算的是<b>按参数名排序</b>后的规范化串（同参同摘，便于对账）：逐项 {@code 键长:键 + 值类型 + 值长:值}
   * 再换行——<b>长度前缀定界</b>，因为值里可能出现任何字符（空格/冒号/换行），拿定界符本身当地界是不可靠的； 类型标记则挡住"渲染成同一串、其实是两回事"的碰撞 （{@code
   * {"a":"1"}} 与 {@code {"a":1}}，{@code {"x":["a","b"]}} 与 {@code {"x":"[a, b]"}} 都曾同摘）。
   *
   * <p><b>为什么是本类的一个静态方法</b>：摘要进 {@code approval.requested} 事件（持久面）与提示面， 只能有一处口径——散在各处就会有人漏掉（派发包
   * §一：「别把脱敏写死在各处」）。要换实现（C 包的 bash 命令脱敏、 B2 的命令落账）走 {@link #of(ToolExecutionGuard,
   * ApprovalCoordinator, Function)} 注入，不要就地改这里。
   *
   * <p><b>留一条诚实的边界</b>：sha256 是单向的，但参数里若有<b>低熵</b>密钥（短口令），摘要仍可被字典攻击反推——
   * 本方法只是"不留明文"的缺省件，不是"密钥可安全入摘要"的证明。接 bash 后由 C 包按命令形状决定到底摘什么。
   */
  public static String defaultDigest(ToolContext context) {
    StringBuilder canonical = new StringBuilder();
    for (Map.Entry<String, Object> argument : new TreeMap<>(context.arguments()).entrySet()) {
      String key = argument.getKey();
      Object raw = argument.getValue();
      String value = String.valueOf(raw);
      // 长度前缀定界：值里可能出现任何字符（空格/冒号/换行），把定界符本身当地界是不可靠的
      canonical
          .append(key.length())
          .append(':')
          .append(key)
          .append(typeTag(raw))
          .append(value.length())
          .append(':')
          .append(value)
          .append('\n');
    }
    byte[] raw = canonical.toString().getBytes(StandardCharsets.UTF_8);
    MessageDigest sha256;
    try {
      sha256 = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException absent) {
      // 任何标准 JVM 都有 SHA-256；真到这一步也不能退回明文，只能给"形状"
      return "unavailable:len=" + raw.length;
    }
    return "sha256:" + HexFormat.of().formatHex(sha256.digest(raw), 0, 16);
  }

  /**
   * 值的<b>类别</b>标记（进规范化串，见 {@link #defaultDigest(ToolContext)}）：挡住"渲染成同一串、其实是两回事"的碰撞。
   *
   * <p>只按<b>类别</b>分（{@code text}/{@code number}/{@code bool}/...）而不是照 {@code
   * getClass().getSimpleName()} 记实现类名： 同类别的两种实现（{@code List.of(...)} 与 {@code new
   * ArrayList<>(...)}）是<b>同一个逻辑参数</b>， 记成两种标记会让"同参同摘"这条对账口径碎掉。类别集合是闭词表，所以定界仍然无歧义
   * （键有长度前缀，标记里不含冒号、也不以数字开头）。
   */
  private static String typeTag(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof CharSequence) {
      return "text";
    }
    if (value instanceof Number) {
      return "number";
    }
    if (value instanceof Boolean) {
      return "bool";
    }
    if (value instanceof Character) {
      return "char";
    }
    if (value instanceof Map<?, ?>) {
      return "map";
    }
    if (value instanceof Collection<?>) {
      return "list";
    }
    return "other";
  }
}
