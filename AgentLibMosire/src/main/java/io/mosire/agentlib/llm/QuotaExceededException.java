package io.mosire.agentlib.llm;

/**
 * 配额超限——Brain 主循环捕获后按"防跑飞硬顶"语义终止回合（计划 D10 / §3.3）。
 *
 * <p>分类固定为 {@link Kind#INTERNAL}（既不可重试、也不可降级）：这是<b>本地账本</b>超限，是"我们自己的硬顶生效了"，不是供应商抖动——
 * 重试只会把预算烧得更狠，降级则会绕过用户设的上限。{@code degradable()=false} 在这里是<b>有意的</b>，后续若有人把它"修"成可降级， 等于把防跑飞硬顶废掉。
 */
public class QuotaExceededException extends LlmException {

  public QuotaExceededException(String message) {
    super(message, Kind.INTERNAL);
  }
}
