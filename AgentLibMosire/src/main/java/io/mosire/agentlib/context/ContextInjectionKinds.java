package io.mosire.agentlib.context;

/**
 * {@link ContextInjection#kind()} 的标准常量。
 *
 * <p>kind 是<b>开放字符串</b>：宿主可以定义自己的类型；这里只钉住本仓标准路径会产生的四个值。事件消费者按 kind 解释语义，不要猜正文。
 */
public final class ContextInjectionKinds {

  /** 工具面发生了变化（新增/移除/定义更新）。 */
  public static final String TOOL_SURFACE_CHANGED = "tool_surface_changed";

  /** 权限/审批/命令档位等策略变化。 */
  public static final String POLICY_CHANGED = "policy_changed";

  /** 系统级通知（非工具面）。 */
  public static final String SYSTEM_NOTICE = "system_notice";

  /** 普通提醒。 */
  public static final String REMINDER = "reminder";

  private ContextInjectionKinds() {
    throw new AssertionError("No instances");
  }
}
