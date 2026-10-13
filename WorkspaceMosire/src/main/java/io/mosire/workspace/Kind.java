package io.mosire.workspace;

/**
 * 挂载的<b>纪律 / 类别</b>（设计 §4.1、§4.8）：决定这个挂载在 shell 一侧的地位。
 *
 * <p><b>为什么纪律要落在挂载上</b>：追加型文档（{@link #LOG}）的不可变语义与 {@code sed -i} 天生冲突——把"这是日志"写在挂载
 * 上，比在每条命令上判"这次写得对不对"便宜、且不会漏。纪律是<b>声明期</b>的一次性表态，运行期不再有第二处判定（A8）。
 *
 * <table>
 *   <caption>纪律 → shell 待遇（设计 §4.8；v1 无 {@code bash} 能力 ⇒ 一律遮蔽）</caption>
 *   <tr><th>纪律</th><th>语义</th><th>shell 待遇</th></tr>
 *   <tr><td>{@link #LOG}</td><td>追加型文档（条目不可变：追加 / 作废 / 留痕 / 可重放）</td>
 *       <td><b>只读 bind 或遮蔽</b>——shell 永远不是追加型文档的写入路径</td></tr>
 *   <tr><td>{@link #FILE}</td><td>文件型文档（语义 = Linux 文件语义本身，没有额外不变量）</td>
 *       <td>本挂载<b>有</b> {@code bash} 能力时才可写；否则遮蔽（写只经文档工具）</td></tr>
 *   <tr><td>{@link #SCRATCH}</td><td>通用暂存区</td><td>同上（有 {@code bash} 能力才可写）</td></tr>
 *   <tr><td>{@link #REFERENCE}</td><td>只读引用（外部文档根、技能根）</td><td><b>只读 bind</b></td></tr>
 * </table>
 *
 * <p><b>纪律不改变能力落格</b>：能力 → 命名空间前缀的落格与纪律无关（那是 {@link WorkspaceRegistry#grant} 的事）； 纪律只在"喂 bash
 * 侧的清单"（{@link MaskEntry#kind()}）里被带出去，由执行侧决定只读 bind 还是遮蔽——本模块不产出第二条 运行期判据（设计 §4.8 的"不重复判"）。
 */
public enum Kind {
  /** 追加型文档：条目不可变，shell 不得成为其写入路径。 */
  LOG,

  /** 文件型文档：语义即 Linux 文件语义（本挂载有 {@code bash} 能力时可写）。 */
  FILE,

  /** 通用暂存区。 */
  SCRATCH,

  /** 只读引用（外部文档根、技能根）。 */
  REFERENCE
}
