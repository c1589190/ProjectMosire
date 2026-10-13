package io.mosire.workspace;

/**
 * 工作区审计事件（M8：每次挂载 / 卸载 / 拒绝都留痕）。
 *
 * <p><b>本模块不依赖事件库</b>：事件库是进程装配件，工作区标准要被任何进程复用（包括没有事件库的测试与工具）。 因此这里只定义<b>事件形状</b>，由装配方注入一个 {@code
 * Consumer<WorkspaceEvent>} 汇聚点（R3b 接到事件库）。
 *
 * <p><b>{@link #detail()} 可以含物理路径</b>：G4 的纪律是"物理路径只进装配日志与审计事件"——事件是内部留痕，不是模型面； 反过来也意味着<b>不得</b>把
 * {@link #detail()} 原样回灌给模型。密钥/凭据一律不进本结构（红线 6）。
 *
 * @param type 事件类型（{@link #MOUNTED} / {@link #UNMOUNTED} / {@link #DENIED}）
 * @param holder 发起者身份键（创建者面；拒绝事件里 = 调用者）
 * @param user 受影响的<b>使用者</b>身份键（挂载索引键）；解析失败等拿不到时为空串
 * @param mount 挂载名（拿不到时为空串）
 * @param detail 人读细节（物理落点、纪律、能力、守卫编号、拒因）——<b>只进日志与事件库</b>
 */
public record WorkspaceEvent(String type, String holder, String user, String mount, String detail) {

  /** 挂载成功。 */
  public static final String MOUNTED = "workspace.mounted";

  /** 卸载成功（含级联失效的每一条）。 */
  public static final String UNMOUNTED = "workspace.unmounted";

  /** 被拒（守卫 / 能力 / 解析越界 / 名字冲突 / 卸载越权）。 */
  public static final String DENIED = "workspace.denied";

  public WorkspaceEvent {
    type = type == null ? "" : type.strip();
    if (type.isEmpty()) {
      throw new IllegalArgumentException("工作区事件的 type 不能为空");
    }
    holder = holder == null ? "" : holder;
    user = user == null ? "" : user;
    mount = mount == null ? "" : mount;
    detail = detail == null ? "" : detail;
  }

  /** 造一条挂载事件。 */
  public static WorkspaceEvent mounted(String holder, String user, String mount, String detail) {
    return new WorkspaceEvent(MOUNTED, holder, user, mount, detail);
  }

  /** 造一条卸载事件（级联失效也用它，detail 里写清是谁的卸载带动了它）。 */
  public static WorkspaceEvent unmounted(String holder, String user, String mount, String detail) {
    return new WorkspaceEvent(UNMOUNTED, holder, user, mount, detail);
  }

  /** 造一条拒绝事件。 */
  public static WorkspaceEvent denied(String holder, String user, String mount, String detail) {
    return new WorkspaceEvent(DENIED, holder, user, mount, detail);
  }
}
