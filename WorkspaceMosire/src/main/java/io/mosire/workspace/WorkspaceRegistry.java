package io.mosire.workspace;

import io.mosire.agentlib.permission.AgentPermissionSet;
import java.nio.file.Path;
import java.util.List;

/**
 * 工作区注册表（设计 §4.3）：挂载表 + <b>唯一解析点</b>鉴权 + 与通用权限接口的落格 + bash 侧遮蔽面 + 审计。
 *
 * <p><b>为什么只有一个解析点</b>：读 / 找 / 改都经同一处解析路径，"哪些路径可见"这条规则就只有一个归属地（与已删占位接口 {@code
 * io.mosire.brain.workspace.Workspace} 的自述同构）。任何工具族<b>不得</b>自行拼物理路径或在工作区之外另立一套 "能不能用"（A8）。
 *
 * <p><b>解析与鉴权流程（§4.5，五步，唯一解析点）</b>：
 *
 * <pre>
 * resolve(虚拟路径, caller):
 *   ① 拆成 (挂载名, 相对路径)                        —— 拆不出 ⇒ INVALID_ARGUMENTS
 *   ② 在 caller.holder 的挂载表里找该挂载            —— 找不到 ⇒ WORKSPACE_NOT_FOUND（与"无权"同码，不泄露存在性）
 *   ③ 校验准入（调用者面是否覆盖该挂载落点）         —— 不满足 ⇒ RESOURCE_DENIED
 *   ④ resolver.resolve(dir, 相对路径)
 *   ⑤ containment：结果必须落在该挂载的物理范围内    —— 不满足 ⇒ 拒（G1/G5）
 * </pre>
 *
 * <p><b>实现必须线程安全</b>（挂载表在父进程内存，按 holder 键索引）。
 */
public interface WorkspaceRegistry {

  /**
   * 挂载（装配期 + 运行期同一入口）；守卫 M1–M4。
   *
   * <p>守卫逐条：
   *
   * <ul>
   *   <li><b>M2</b>：{@code spec.holder} 恒为创建者本人；{@code spec.user} 只能是自己或自己的后代（血缘子树）；
   *   <li><b>M1/A2</b>：物理范围 ⊆ 调用者在 {@code workspaces} 命名空间的表态范围，且 ⊆ 调用者在每个能力落格命名空间上的 可达面；
   *   <li><b>M3</b>：能力 id 必须已注册，且能力落格后仍 ⊆ 调用者面；
   *   <li><b>M4/A4</b>：同一使用者下虚拟挂载名唯一，冲突<b>具名拒</b>；
   *   <li><b>M9/W1/W2/M11/M12</b>：落点在自己某个工作区目录之内时，必须是<b>真子目录</b>（等于自己的根 ⇒ 拒），能力只能 更窄（含 shell
   *       可见位与命令白名单）。
   * </ul>
   *
   * <p><b>不创建物理目录</b>：挂载是一次登记（谁用哪个落点、能干什么），建目录是写文档时的动作——登记产生文件系统副作用会让 "拒了却留下半个目录"成为可能。
   *
   * <p><b>G6/M7（子体挂载集）</b>：子体的挂载由父侧经本方法登记（{@code holder} = 父、{@code user} = 子），因此"子体挂载集 ⊆
   * 调用者的挂载集、且每个挂载的物理范围 ⊆ 调用者可达面"由 M1/M2 结构性成立——挂载集是物化在<b>父侧运行期状态</b>里的数据，不留
   * "运行期往上问父级"这条路（§4.9）。父挂载集 ∩ 模板 ∩ 请求的求交发生在派生链（spawn）那一侧。
   *
   * @return 实际登记的挂载（= {@code spec}）
   * @throws WorkspaceException 守卫拒绝（{@code RESOURCE_DENIED} / {@code WORKSPACE_NAME_CONFLICT}）或入参非法
   */
  Mount mount(Mount spec, WorkspaceCaller caller);

  /**
   * 卸载（守卫 M5 + 级联 M10）。<b>只注销挂载，保留物理目录</b>（文档是数据；物理清理是独立动作）。
   *
   * <p>查找分两档（虚拟挂载名在使用者切片内唯一，因此需要一个确定的定位顺序）：
   *
   * <ol>
   *   <li>先在自己的挂载切片（{@code user == caller.holder}）里按名字找；找到 ⇒ 过 M5 后卸载；
   *   <li>否则在"由自己创建"的挂载里找（跨切片，{@code holder == caller.holder}）：<b>恰好一条</b> ⇒ 卸载； 多条 ⇒
   *       具名拒（无法确定目标，不猜）；零条 ⇒ {@code WORKSPACE_NOT_FOUND}（与"无权"同码）。
   * </ol>
   *
   * <p><b>M5</b>：卸载只允许 {@code holder}（创建者）或其祖先执行。<b>M10/W4</b>：<b>沿 {@link Mount#derivedFrom()}
   * 链</b>级联——由被卸载挂载派生出的挂载一并失效（fail-closed：条目被移除，解析绝不回落成物理路径）。<b>不按物理包含判级联</b>：物理嵌套 是 W1
   * 的正常形态，装配期独立挂载之间也可能物理嵌套（{@code workspaces} ⊃ {@code workspace}）。
   *
   * @throws WorkspaceException {@code WORKSPACE_NOT_FOUND}（名字不在表里）或 {@code RESOURCE_DENIED}（M5）
   */
  void unmount(MountName name, WorkspaceCaller caller);

  /**
   * 某身份键<b>可使用</b>的挂载（{@code user == holder} 的切片，与 {@link #resolve} 同一索引）。
   *
   * <p>排序按挂载名，保证同一状态下两次调用的输出一致（投影要能对账）。
   */
  List<Mount> mounts(String holder);

  /**
   * 唯一解析点：虚拟路径 → 物理落点（流程见类 javadoc）。
   *
   * @return 物理落点（绝对路径）
   * @throws WorkspaceException {@code INVALID_ARGUMENTS} / {@code WORKSPACE_NOT_FOUND} / {@code
   *     RESOURCE_DENIED}
   */
  Path resolve(String virtualPath, WorkspaceCaller caller);

  /**
   * 投影：把物理树投影成<b>虚拟条目</b>（设计 §4.6）。本层只做"挂载 / 能力"级别的裁剪——条目级可见性归文档层的判据， 不在这里重复判（避免两处判同一件事）。
   *
   * <p>输出<b>只含虚拟路径</b>（G4）；列的是该前缀下的直接子项（不递归，见 {@link Resolver#list}）。
   *
   * @throws WorkspaceException 同 {@link #resolve}
   */
  List<String> list(String virtualPrefix, WorkspaceCaller caller);

  /**
   * 与通用权限接口的落格（§4.8）：把 {@code holder} 名下挂载的物理前缀<b>并进</b>对应命名空间，返回新的权限集。
   *
   * <p><b>只做并进 + 不放大（A5）</b>：其它维度（工具白名单、拒绝名单、破坏性/敏感位、{@code readOnly}）逐字保留；可达面这一 维只并进"该能力落格的命名空间 +
   * 该挂载的物理落点"，而该落点在挂载时已过 M1/M3（⊆ 挂载者可达面）——因此并进不会引入任何 超范围前缀（"绝不放大"的落点）。<b>显式 {@code none()}
   * 的命名空间也会被并进</b>：落格是"能力授予"的定义动作 （A8：能力是唯一决定点），要挡住某个能力应当不声明它。
   */
  AgentPermissionSet grant(String holder, AgentPermissionSet permissions);

  /**
   * bash 侧遮蔽面（静态清单）：<b>只返回 {@link Origin#ASSEMBLY} 的条目</b>（M6）——运行期新建的挂载不进遮蔽面（shell 看不见它）。
   *
   * <p>条目按能力落格的命名空间逐个产出（命名空间无关：本实现不认任何具体能力 id），纪律（{@link Kind}）随条目带出， 由执行侧决定只读 bind / 遮蔽（G7 的逐条目求与在
   * {@code SandboxMask.fence} 一侧）。
   */
  List<MaskEntry> maskEntries();

  /** 本次运行期的审计留痕（挂载 / 卸载 / 拒绝；装配方另经注入的汇聚点把它们接进事件库）。 */
  List<WorkspaceEvent> events();
}
