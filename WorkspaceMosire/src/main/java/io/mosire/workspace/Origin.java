package io.mosire.workspace;

/**
 * 挂载来源：决定它进不进 bash 遮蔽面（设计 §4.9 M6）。
 *
 * <ul>
 *   <li>{@link #ASSEMBLY}：<b>装配期声明</b>——进程启动时的静态声明，进遮蔽面（{@link WorkspaceRegistry#maskEntries()}
 *       只返回这一类）；
 *   <li>{@link #RUNTIME}：<b>运行期新建</b>——{@code mount_workspace} 工具建的挂载，不进遮蔽面（shell 看不见它）。
 * </ul>
 *
 * <p><b>为什么运行期挂载默认不进遮蔽面</b>：遮蔽面是下发给 bash 侧的<b>静态</b>清单（{@code
 * sandbox.maskedPaths}），若运行期挂载也往里塞，就等于"运行期改静态 config"——清单的读者在另一个进程/另一份配置里， 这条竞态没有便宜的收口点；而且 AgentLib
 * 与 bash 插件都要跟着改（M6 的保守规则）。要 shell 可见，就必须在装配期 声明清楚——那是一个可审计的、启动时就定下来的决定。
 */
public enum Origin {
  /** 装配期声明（进遮蔽面）。 */
  ASSEMBLY,

  /** 运行期新建（不进遮蔽面）。 */
  RUNTIME
}
