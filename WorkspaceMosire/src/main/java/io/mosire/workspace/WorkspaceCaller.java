package io.mosire.workspace;

import io.mosire.agentlib.permission.AgentPermissionSet;
import java.util.Objects;

/**
 * 调用者 = 身份（holder 键）+ 权限集；解析与鉴权都以它为准（设计 §4.3）。
 *
 * <p><b>{@code holder} 是什么</b>：调用者的<b>身份键</b>——主 Agent 是 {@code main}，子 Agent 是它的实例 id（{@code
 * <模板id>-<hex>}）。这个键同时干两件事：① 作为 {@link Mount#user()} 在挂载表里索引（"哪些挂载是我的"）；② 作为 {@link Mount#holder()}
 * 表达"谁创建了它"（生命周期权限）。<b>身份不是模型自报的</b>：它取自 {@code ToolContext.identity().instanceId()}（宿主在建链时绑定），与
 * {@code ContextAccessJudge} 同一条纪律。
 *
 * <p><b>为什么身份键不是 token</b>：{@code AccessToken} 只有三个桶，"主 Agent 与全体子 Agent 同为 {@code DEFAULT}"，
 * 答不出"这是谁在问"——而挂载表恰恰是按"谁"索引的。
 *
 * <p><b>血缘</b>：M2/M5/M9 的"自己或后代"判定用<b>血缘路径</b>（{@code main/a1/a1-1}）而不是实例 id；两者的对应关系由 装配方经 {@code
 * DefaultWorkspaceRegistry.create(..., lineagePathOf)} 注入（本模块不得依赖 Brain）。缺省映射 = 身份键
 * 本身就是血缘路径：那种装配下只有"自己"能匹配（fail-closed），共享前缀的父子链要显式注入映射。
 *
 * @param holder 调用者身份键（非空白；血缘路径形如 {@code main/a1}）
 * @param permissions 调用者权限集（非 null；"没有权限集"不是一种调用者）
 */
public record WorkspaceCaller(String holder, AgentPermissionSet permissions) {

  public WorkspaceCaller {
    holder = holder == null ? "" : holder.strip();
    if (holder.isEmpty()) {
      throw new IllegalArgumentException("调用者身份键（holder）不能为空");
    }
    Objects.requireNonNull(permissions, "permissions");
  }

  /** 语法糖。 */
  public static WorkspaceCaller of(String holder, AgentPermissionSet permissions) {
    return new WorkspaceCaller(holder, permissions);
  }

  /** 本调用者在某命名空间上的表态（未表态 ⇒ {@code none()}，fail-closed）。 */
  public io.mosire.agentlib.permission.ResourceScope scope(String namespace) {
    return WorkspacePermissions.declared(permissions, namespace);
  }
}
