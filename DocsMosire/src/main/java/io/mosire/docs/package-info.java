/**
 * Docs 系统（设计 §5）：<b>两种纪律</b>的文档引擎 + 工具面。
 *
 * <p><b>{@code LOG}</b>（追加型）：条目不可变——只追加、不覆盖、不删除，作废 = 再追加一条 {@code VOIDED}；条目身份由 Docs 派生（ {@code
 * <地址>#<序号>}，序号 = 日志行序）；可见性判据只有一处（{@link io.mosire.docs.DocVisibility#canSee}）。
 *
 * <p><b>{@code FILE}</b>（文件型）：虚拟路径即身份；行寻址就地编辑 + <b>自实现 IO</b>（同目录临时文件 + {@code
 * ATOMIC_MOVE}，盘上永不出现半成品）。
 *
 * <p><b>两个门</b>（N11）：库 API（进程内代码直接调 {@link io.mosire.docs.DocStore} / {@link
 * io.mosire.docs.DocFileStore} + 可见性判据）与 AgentTool 面（{@code doc_*} 七个工具 + {@code docs}/{@code
 * docs-edit} 资源面）。
 *
 * <p><b>本模块刻意不做的事</b>（v1 的用户裁定）：
 *
 * <ul>
 *   <li><b>不挂命令面</b>（N13）：没有 {@code bash} 入口、没有 {@code doc_exec}、不碰沙箱——{@code FILE} 的读写全由本模块自实现 IO；
 *   <li><b>不定义布局</b>（N7）：虚拟↔物理的映射归 {@code workspace-mosire} 的 resolver，本模块只声明"我用哪一个挂载做我的根"；
 *   <li><b>不注册能力</b>：{@link io.mosire.docs.DocsCapability#docs()} 只是"能力定义"，落格（写进权限集）由工作区模块的 {@code
 *       grant} 完成（A8：能力是唯一决定点，运行期不得有第二处判定）。
 * </ul>
 *
 * <p><b>依赖方向</b>：{@code agentlib ← workspace ← docs}。本模块不得 import {@code io.mosire.brain.*} /
 * {@code io.mosire.main.*}，也不依赖任何插件（含 {@code bash}）。
 */
package io.mosire.docs;
