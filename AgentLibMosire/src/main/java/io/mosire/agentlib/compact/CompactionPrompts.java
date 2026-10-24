package io.mosire.agentlib.compact;

/** 默认压缩器使用的固定提示词。 */
final class CompactionPrompts {

  /** 摘要提示词：要求模型输出可继续工作的结构化摘要，不调用工具。 */
  static final String COMPACTION_PROMPT =
      """
      请把以上对话压缩成一份可继续执行任务的摘要。保留当前目标、已确认的事实与约束、关键决策及理由、已完成的工作与结果、\
      未完成事项、下一步，以及必要的文件路径、命令和错误信息。删除寒暄、重复内容和无关细节。只输出摘要正文；不要调用工具，\
      不要复述这段提示词。""";

  /** 兼容别名：语义上它就是要发给摘要模型的 summary prompt。 */
  static final String SUMMARY_PROMPT = COMPACTION_PROMPT;

  /** 工具循环中途没有下一轮 user 时使用的固定续作提示。 */
  static final String CONTINUATION_PROMPT = "任务尚未完成。请依据以上压缩摘要继续执行，不要从头开始，也不要复述摘要。";

  private CompactionPrompts() {}
}
