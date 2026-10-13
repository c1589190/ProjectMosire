package io.mosire.docs;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.tool.ToolContext;
import java.util.Set;

/**
 * 一次读取的<b>观看者</b>（设计 §5.4）：可见性判据的唯一输入之一。
 *
 * <p><b>身份不由模型给出</b>：{@link #actorId()} 取自宿主绑定的 {@code ToolContext.identity().instanceId()} （主
 * Agent = {@code main}，子 Agent = 实例 id），与 {@code ContextAccessJudge} 同一条纪律——模型给不出也改不了。
 *
 * <p><b>"身份解不出"是一个显式取值</b>（设计 §5.4 的 ★）：{@code actorId} 为空或为 {@code unknown}（{@link
 * io.mosire.agentlib.permission.AgentIdentity#UNKNOWN} 的 id）时，判据<b>跳过 affiliations 支、只走
 * tags</b>——"不知道你是谁" 不许读成"按归属放行"。因此不要用 {@code ""} 冒充"无归属者"：想表达"我有这些归属"就显式给集合，想表达"我是谁解不出"就给空/unknown
 * 的 actorId。
 *
 * @param actorId 观看者身份键（空 = 解不出；{@code unknown} 同义）
 * @param affiliations 观看者的归属集合（身份解不出时判据不读它）
 * @param seesAll GM 短路位（无条件可见；<b>在判据之前短路</b>，不落 tags、不进三支）
 */
public record DocViewer(String actorId, Set<String> affiliations, boolean seesAll) {

  public DocViewer {
    actorId = actorId == null ? "" : actorId.strip();
    // 内联 Set.copyOf：让 SpotBugs 看得见不可变来源
    affiliations = affiliations == null ? Set.of() : Set.copyOf(affiliations);
  }

  /** 普通观看者（无 GM 短路位）。 */
  public static DocViewer of(String actorId, Set<String> affiliations) {
    return new DocViewer(actorId, affiliations, false);
  }

  /**
   * 由工具上下文解析观看者：身份取宿主绑定的实例 id，GM 短路位取 {@link AccessToken#SYSTEM}。
   *
   * <p><b>为什么 GM 位按 token 取</b>：{@code SYSTEM} 桶在本仓就是"系统级全授权"（{@link
   * io.mosire.agentlib.permission.AgentPermissionSet#system()}），文档面的"无条件可见"与它同义；管理面（将来的 HTTP/GUI）用
   * {@link #seesAll(String)} 或直接 {@code new DocViewer(id, Set.of(), true)} 表达。
   */
  public static DocViewer from(ToolContext context) {
    String actorId = context.identity().instanceId();
    boolean gm =
        context.caller() == AccessToken.SYSTEM
            || context.permissions().grantedToken() == AccessToken.SYSTEM;
    return new DocViewer(actorId, Set.of(), gm);
  }

  /** GM 观看者（无条件可见；归属集合留空——判据用不到它）。 */
  public static DocViewer seesAll(String actorId) {
    return new DocViewer(actorId, Set.of(), true);
  }

  /**
   * 身份能否解出（设计 §5.4 的 ★：解不出 ⇒ 跳过 affiliations，只走 tags）。
   *
   * <p>空串与 {@code unknown}（{@link io.mosire.agentlib.permission.AgentIdentity#UNKNOWN} 的实例
   * id）都算解不出： 前者是"没绑定身份"，后者是宿主明确标注的"中立身份"，两者都不该被当成一个真实的归属主体。
   */
  public boolean resolvable() {
    return !actorId.isEmpty() && !"unknown".equals(actorId);
  }
}
