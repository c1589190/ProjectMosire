package io.mosire.agentlib.decision;

import java.util.Map;
import java.util.Optional;

/**
 * 提议时的逐条校验（设计 §5.8 冻结形状；红线 1）：宿主注入，{@link DecisionPackages#propose} 逐条问它。
 *
 * <p><b>为什么必须在提议时就校验</b>：不校验，人批的就是一个"注定失败"的东西——批完才发现解析不了、 过不了权限、够不着资源面，白烧一次人的注意力；而这正是隔壁 #1/#2
 * 出血的共同前提（设计 §5.1）。 所以 propose 逐条问本口：<b>任一条不过 ⇒ 整包不建</b>（连包 id 都不发——"不给注定失败的东西发编号"）。
 *
 * <p><b>返回语义（fail-closed）</b>：{@code Optional.of(理由)} = 拒；{@code Optional.empty()} = 放行。 必须返回非
 * null；返回 {@code null} 会被包层当作契约违规当场亮出来，<b>不会</b>被当成放行。
 *
 * <p><b>抛异常</b>：校验器自己炸了 ⇒ 包不建、异常原样上抛——"校验不了"不等于"通过"。
 *
 * <p><b>实现要求</b>：只看不写（校验 ≠ 授权：不得顺手 {@code grantSession}、不得改权限集）； 不得改动传入的 {@code
 * args}（它是包内条目的明文，后面还要原样交给提交口）。 校验内容由宿主决定：能解析 / 过权限面 / 过资源面准入 / 过角色（§5.1、B-5）。
 */
public interface ItemValidator {

  /**
   * 这一条能不能进包。
   *
   * @param toolName 工具名
   * @param args 调用参数明文（只读）
   * @return 拒 ⇒ 带理由的 {@link Optional}（理由会进具名拒的消息，供人/模型改路）；放行 ⇒ 空
   */
  Optional<String> deny(String toolName, Map<String, Object> args);
}
