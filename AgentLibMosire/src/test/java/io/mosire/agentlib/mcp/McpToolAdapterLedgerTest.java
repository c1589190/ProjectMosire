package io.mosire.agentlib.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.Digest;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 桥接工具的落账视图（F3）：{@link McpToolAdapter#ledgerArgs(ToolContext)} 一律安全摘要，绝不落明文。
 *
 * <p><b>缺陷形态（S5-E 实跑）</b>：子进程的 registry 为空，`bash` 是父级工具的桥接 ⇒ 子体拿到的是本类，而本类原先<b>不覆写</b> {@code
 * ledgerArgs} ⇒ 走 {@code AgentTool} 缺省"原样返回" ⇒ 模型写的命令<b>明文</b>落进子体自己的 {@code tool.call} 事件（子体库只有
 * events 表，这是它命令的唯一持久痕迹），与 S4-C 红线 4"命令明文不进事件库"冲突。
 *
 * <p><b>判别性</b>：去掉本类的覆写（退回缺省实现），下面每一条"明文不出现"的断言都会红；把摘要输入面改成"只看某一个字段" 或常量，{@code
 * digestMatchesTheApprovalDigestFunction} 会红。
 */
class McpToolAdapterLedgerTest {

  /** 与真实 bash 调用同形（{@code command}/{@code cwd}/{@code timeout}），命令里带一个"疑似密钥"。 */
  private static final Map<String, Object> BASH_ARGS =
      Map.of(
          "command",
          "curl -H 'Authorization: Bearer sk-live-SECRET123' https://x/y",
          "cwd",
          "/srv/work",
          "timeout",
          30);

  @Test
  void plaintextNeverAppearsInTheLedgerView() {
    Map<String, Object> view = ledgerViewOf(BASH_ARGS);

    assertThat(flatten(view))
        .as("命令明文与参数名以外的任何值都不许出现（密钥值、URL、cwd 值）")
        .doesNotContain("sk-live-SECRET123")
        .doesNotContain("curl")
        .doesNotContain("/srv/work");
    // 明文只在摘要里以指纹形态存在：视图里每个值都必须不是原参数值
    assertThat(view.values())
        .as("视图的值里不许混进任何原始参数值")
        .doesNotContainAnyElementsOf(BASH_ARGS.values());
  }

  /**
   * 摘要面 = 审批摘要的同一段代码、同一输入面（全部参数）⇒ 可跨进程对账（父侧 {@code approval.*} 事件的 {@code digest}）。
   *
   * <p>这条同时钉住"不是只摘某一个字段"：只摘 {@code command} 会让下面第一个断言红。
   */
  @Test
  void digestMatchesTheApprovalDigestFunction() {
    Map<String, Object> view = ledgerViewOf(BASH_ARGS);

    assertThat(view.get("digest")).isEqualTo(Digest.ofArguments(BASH_ARGS));
    // 与 ToolCallAuthorizer 的缺省摘要同源（该方法是父侧审批事件的 digest 来源）
    assertThat(view.get("digest"))
        .isEqualTo(ToolCallAuthorizer.defaultDigest(context(BASH_ARGS)))
        .isNotEqualTo(Digest.ofCommand((String) BASH_ARGS.get("command")))
        .as("摘要不带前缀就是没走 Digest")
        .asString()
        .startsWith(Digest.PREFIX);
  }

  /**
   * 摘要面经真实 MCP 的 JSON 往返后不变（跨进程对账的前提）：子体在本地算、父侧从线上收，两边必须是同一个指纹。
   *
   * <p>用与 MCP 传输同一套 mapper（{@code McpJsonDefaults}），不是随便找个 JSON 库自证。
   */
  @Test
  void digestSurvivesTheMcpJsonRoundTrip() throws IOException {
    String wire = McpJsonDefaults.getMapper().writeValueAsString(BASH_ARGS);
    Map<String, Object> arrived =
        McpJsonDefaults.getMapper().readValue(wire, new TypeRef<Map<String, Object>>() {});

    assertThat(ledgerViewOf(arrived).get("digest"))
        .as("线上往返后的参数与子体本地的参数同摘")
        .isEqualTo(ledgerViewOf(BASH_ARGS).get("digest"));
  }

  /** 同参同视图（对账与去重的前提），异参异摘（摘要不是常量）。 */
  @Test
  void sameArgumentsGiveTheSameViewAndDifferentArgumentsDoNot() {
    Map<String, Object> reordered = new LinkedHashMap<>();
    reordered.put("timeout", 30);
    reordered.put("cwd", "/srv/work");
    reordered.put("command", BASH_ARGS.get("command"));

    assertThat(ledgerViewOf(reordered)).as("Map 无序：同一份参数必须同视图").isEqualTo(ledgerViewOf(BASH_ARGS));
    assertThat(ledgerViewOf(Map.of("command", "pwd")).get("digest"))
        .as("换一条命令必须换一个指纹")
        .isNotEqualTo(ledgerViewOf(BASH_ARGS).get("digest"));
  }

  /** 形状：参数名（字典序）+ 值字符总长 + 摘要，三样都在，别的一样不落。 */
  @Test
  void viewCarriesSortedKeysLengthAndDigestOnly() {
    Map<String, Object> view = ledgerViewOf(Map.of("timeout", 30, "command", "pwd", "cwd", "/srv"));

    assertThat(view.keySet()).containsExactlyInAnyOrder("digest", "len", "keys");
    @SuppressWarnings("unchecked")
    List<String> keys = (List<String>) view.get("keys");
    assertThat(keys).as("参数名按字典序（视图要可逐字节比较）").containsExactly("command", "cwd", "timeout");
    assertThat(view.get("len")).as("3 + 4 + 2 个字符").isEqualTo(9L);
  }

  /** 空参不是异常路径：没有参数就没有明文可漏，视图照样成形（len=0、keys=[]）。 */
  @Test
  void emptyArgumentsStillProduceAShapedView() {
    Map<String, Object> view = ledgerViewOf(Map.of());

    assertThat(view.get("digest")).isEqualTo(Digest.ofArguments(Map.of()));
    assertThat(view.get("len")).isEqualTo(0L);
    assertThat(view.get("keys")).isEqualTo(List.of());
  }

  // ---------- 工具 ----------

  /** 走真实构造路径（{@link McpToolAdapter#of}——即 {@link McpToolSource} 映射工具列表时用的那个）。 */
  private static Map<String, Object> ledgerViewOf(Map<String, Object> arguments) {
    McpSchema.Tool tool = McpSchema.Tool.builder("bash", Map.of("type", "object")).build();
    McpCaller caller = (name, args) -> ToolResult.ok("（本用例不执行）");
    AgentTool adapted = McpToolAdapter.of(tool, caller);
    return adapted.ledgerArgs(context(arguments));
  }

  private static ToolContext context(Map<String, Object> arguments) {
    return new ToolContext(
        AccessToken.DEFAULT,
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build(),
        Map.of(),
        arguments);
  }

  /** 把视图（含嵌套）摊平成文本：判"明文不在里面"必须看全体，别只看第一层。 */
  private static String flatten(Object value) {
    StringBuilder out = new StringBuilder();
    collect(value, out);
    return out.toString();
  }

  private static void collect(Object value, StringBuilder out) {
    if (value instanceof Map<?, ?> map) {
      map.forEach(
          (key, item) -> {
            out.append(key).append('=');
            collect(item, out);
          });
    } else if (value instanceof Iterable<?> items) {
      items.forEach(item -> collect(item, out));
    } else {
      out.append(value).append('\n');
    }
  }
}
