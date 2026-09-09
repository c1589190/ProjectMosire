package io.mosire.brain.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.runtime.AgentConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class BasicContextAssemblerTest {

  private static final String PROMPT = "你是测试 Agent。";

  private static AgentConfig config() {
    return AgentConfig.builder("main").systemPrompt(PROMPT).build();
  }

  /** 最小假工具：只为了渲染工具目录，从不执行。 */
  private record FakeTool(String name, String description) implements AgentTool {
    @Override
    public ToolResult execute(ToolContext context) {
      throw new UnsupportedOperationException("测试假工具不执行");
    }
  }

  private static List<AgentTool> twoTools() {
    return List.of(new FakeTool("toolA", "做 A 事"), new FakeTool("toolB", ""));
  }

  private static List<LlmMessage> history() {
    return List.of(LlmMessage.user("早"), LlmMessage.assistant(List.of(new ContentPart.Text("早！"))));
  }

  private static String systemTextOf(LlmRequest request) {
    return ((ContentPart.Text) request.messages().get(0).content().get(0)).text();
  }

  /** 消息序列布局契约：[system] + history（逐条原样）+ [user]——管线据此剥离 system 头累积历史。 */
  @Test
  void buildRequestLayoutIsSystemThenHistoryThenUser() {
    BasicContextAssembler assembler = new BasicContextAssembler();
    List<LlmMessage> history = history();

    LlmRequest request = assembler.buildRequest(config(), "干活", history, twoTools());

    assertThat(request.messages()).hasSize(history.size() + 2);
    assertThat(request.messages().get(0).role()).isEqualTo(LlmMessage.ROLE_SYSTEM);
    assertThat(request.messages().subList(1, 1 + history.size()))
        .containsExactlyElementsOf(history);
    LlmMessage last = request.messages().get(request.messages().size() - 1);
    assertThat(last.role()).isEqualTo(LlmMessage.ROLE_USER);
    assertThat(((ContentPart.Text) last.content().get(0)).text()).isEqualTo("干活");
  }

  /** system 文本逐字节锁定当前格式：prompt + 空行 + 工具目录（有描述加冒号、无描述只列名）+ 空行 + 收尾指令。 */
  @Test
  void buildRequestSystemTextIsByteIdenticalToLockedFormat() {
    BasicContextAssembler assembler = new BasicContextAssembler();

    LlmRequest request = assembler.buildRequest(config(), "干活", history(), twoTools());

    String expected =
        PROMPT
            + "\n\n你有以下工具可用（一律直接调用，不要凭空想象它们不存在）：\n"
            + "- toolA: 做 A 事\n"
            + "- toolB\n"
            + "\n完成用户请求后，请用纯文本回复结果，不要虚构工具执行过程。";
    assertThat(systemTextOf(request)).isEqualTo(expected);
  }

  /** 无工具时目录区为占位行（当前没有任何可用工具）——同样的固定三段结构。 */
  @Test
  void buildRequestWithNoToolsKeepsPlaceholderDirectoryLine() {
    BasicContextAssembler assembler = new BasicContextAssembler();

    LlmRequest request = assembler.buildRequest(config(), "干活", List.of(), List.of());

    assertThat(systemTextOf(request))
        .isEqualTo(
            PROMPT
                + "\n\n你有以下工具可用（一律直接调用，不要凭空想象它们不存在）：\n"
                + "（当前没有任何可用工具）\n"
                + "\n完成用户请求后，请用纯文本回复结果，不要虚构工具执行过程。");
    assertThat(request.tools()).isEmpty();
  }

  /** 工具定义照传（顺序保持），与消息序列同发。 */
  @Test
  void buildRequestPassesToolDefsInOrder() {
    BasicContextAssembler assembler = new BasicContextAssembler();

    LlmRequest request = assembler.buildRequest(config(), "干活", List.of(), twoTools());

    assertThat(request.tools()).hasSize(2);
    assertThat(request.tools().get(0).name()).isEqualTo("toolA");
    assertThat(request.tools().get(1).name()).isEqualTo("toolB");
  }

  /** composition 与 buildRequest 同输入同源：SYSTEM 估算 = 组装出的 system 文本长度 / 4，其余层为 0。 */
  @Test
  void compositionMatchesAssembledSystemTextAndLeavesOtherLayersZero() {
    BasicContextAssembler assembler = new BasicContextAssembler();
    List<AgentTool> tools = twoTools();

    LlmRequest request = assembler.buildRequest(config(), "干活", history(), tools);
    ContextComposition composition = assembler.composition(config(), "干活", history(), tools);

    int expected = systemTextOf(request).length() / 4;
    assertThat(composition.estimatedTokens()).containsEntry(ContextLayer.SYSTEM, expected);
    for (ContextLayer layer : ContextLayer.values()) {
      if (layer != ContextLayer.SYSTEM) {
        assertThat(composition.estimatedTokens()).containsEntry(layer, 0);
      }
    }
    assertThat(composition.total()).isEqualTo(expected);
  }

  /** 预算是建议值：自定义 ContextPolicy 不得改变 buildRequest 输出（本任务不截断）。 */
  @Test
  void customPolicyDoesNotChangeBuildRequestOutput() {
    BasicContextAssembler strict =
        new BasicContextAssembler(ContextPolicy.defaults().withBudget(ContextLayer.SYSTEM, 1));
    BasicContextAssembler plain = new BasicContextAssembler();
    AgentConfig config = config();

    assertThat(strict.buildRequest(config, "干活", history(), twoTools()).messages())
        .isEqualTo(plain.buildRequest(config, "干活", history(), twoTools()).messages());
  }

  /** 无参与 defaults() 构造等价（同一行为）。 */
  @Test
  void defaultConstructorMatchesDefaultsPolicyConstructor() {
    BasicContextAssembler noArg = new BasicContextAssembler();
    BasicContextAssembler withDefaults = new BasicContextAssembler(ContextPolicy.defaults());
    AgentConfig config = config();

    assertThat(noArg.buildRequest(config, "干活", List.of(), twoTools()))
        .isEqualTo(withDefaults.buildRequest(config, "干活", List.of(), twoTools()));
    assertThat(noArg.composition(config, "干活", List.of(), twoTools()))
        .isEqualTo(withDefaults.composition(config, "干活", List.of(), twoTools()));
  }
}
