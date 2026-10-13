/**
 * 工作区标准（{@code workspace-mosire}，设计 {@code 设计-工作区标准与Docs系统.md} §4）。
 *
 * <p><b>本体</b>：工作区不是"一块具名物理目录"，而是「<b>虚拟路径空间 ↔ 物理落点</b>」的映射表 + 在<b>解析点</b>上的鉴权。 物理目录只是映射的一种落点；{@link
 * io.mosire.workspace.Resolver} 换掉不改调用方，物理布局对模型不可见。
 *
 * <p><b>三层职责</b>：本模块只做"哪个目录归谁、谁能碰、什么时候回收"（地籍 + 鉴权 + 生命周期），不读写文档内容—— 文档的条目语义、可见性判据与文件 IO 归
 * docs-mosire；通用权限接口归 agentlib-mosire。
 *
 * <p><b>几条不许违反的线</b>（设计 §4.4/§4.10）：
 *
 * <ul>
 *   <li>解析只有一个点（{@link io.mosire.workspace.WorkspaceRegistry#resolve}），读 / 找 / 改都经它；
 *   <li>物理范围只能缩小不能放大（G2/A2/M1/M3）：挂载先过"挂载者可达面"，能力先过"不超出既有权利"；
 *   <li>解析结果必须 containment（G1）；解析失败 / 越界一律 fail-closed（G5）；
 *   <li>面向模型的输出只出现虚拟路径（G4/A7），物理路径只进装配日志与审计事件；
 *   <li>能力是"在这个工作区里能干什么"的<b>唯一</b>决定点（A8）：只在挂载 / 授予时落格，运行期不产生第二处判定。
 * </ul>
 *
 * <p><b>依赖纪律</b>：本包<b>不得</b> import {@code io.mosire.brain.*} / {@code io.mosire.main.*}，也不依赖 docs
 * / shell 模块——工作区标准要被任何进程复用（含没有 Agent 运行时的工具与测试）。
 */
package io.mosire.workspace;
