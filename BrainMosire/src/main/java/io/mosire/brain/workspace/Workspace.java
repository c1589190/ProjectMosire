package io.mosire.brain.workspace;

import java.io.IOException;
import java.util.List;

/**
 * 工作区读写能力占位——<b>三期实现，本阶段（P2-5）只留形状</b>。
 *
 * <p><strong>设计意图</strong>：给 Agent 一个"有边界的工作目录"。读/找/改都经同一处解析路径，于是"哪些路径可见"这条规则只有一个归属地： 绝对路径、{@code
 * ..} 逃逸、符号链接指向区外、超大文件/二进制文件策略，全部由实现层统一落定；工具只表达"我要读/我要找"的意图， 不再各自拼路径、各自决定边界。
 *
 * <p>二期的 {@code bash} <b>明确不做</b>目录围栏（工具类 Javadoc 已写"目录围栏归三期的 Workspace，此处不假装已有"）——
 * 围栏（路径解析与越界拒绝）正是本接口在三期的第一等交付，而不是调用方的责任。
 *
 * <p><strong>本文件是占位，不是可用能力</strong>：无实现、不接线、不被任何生产代码引用（P2-5 的验收句即"编译通过"）。接口刻意 <b>不</b> extends
 * {@code AgentTool}——那会把三期的入参形态、权限三要素（level/sensitive/destructive）与结果渲染方式一并冻结在此刻，
 * 而这三件事要等三期的编辑器设计定稿；从本接口到 {@code AgentTool} 的适配器是三期的活。方法签名同理属<b>草案</b>：
 * 此处只钉"能力形状"（读/找/编辑），参数与失败语义（异常分类、越界时的行为）留待三期与适配器一起落定。
 */
public interface Workspace {

  /**
   * 读取一个文件的完整文本内容。
   *
   * @param path 工作区内的相对路径
   * @return 文件文本
   * @throws IOException 读取失败（不存在/不可读/越界——具体分类三期落定）
   */
  String read(String path) throws IOException;

  /**
   * 按 glob 模式列出工作区内的路径。
   *
   * @param pattern glob 模式（支持 {@code **} 递归通配）
   * @return 匹配到的路径（相对工作区根；排序稳定，便于模型复现与定位）
   * @throws IOException 遍历失败
   */
  List<String> glob(String pattern) throws IOException;

  /**
   * 在工作区内容里做正则检索——模型定位代码的主力手段。
   *
   * @param pattern 正则
   * @param pathGlob 限定参与检索的路径（glob）；null/空白 = 全工作区
   * @return 命中行（按路径、行号稳定排序）
   * @throws IOException 遍历/读取失败
   */
  List<GrepMatch> grep(String pattern, String pathGlob) throws IOException;

  /**
   * 一条检索命中（占位草案，三期可改）。
   *
   * @param path 命中的文件（相对工作区根）
   * @param lineNumber 行号（从 1 起）
   * @param line 命中行原文
   */
  record GrepMatch(String path, int lineNumber, String line) {}
}
