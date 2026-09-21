package io.mosire.agentlib.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.llm.ToolDef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link ToolDefs} 的单元测试：官方转换点的<b>逐字段直传</b>、保序、以及 null/空集合的边界口径。
 *
 * <p>工具桩沿用本包既有惯例（匿名 {@link AgentTool} + 私有静态工厂，见 {@link ToolRegistryTest}）：不新造夹具类。
 */
class ToolDefsTest {

  /** 三项逐字直传：转换不得改名、不得裁剪描述、不得重写 schema。 */
  @Test
  void mapsEveryFieldVerbatim() {
    Map<String, Object> schema =
        Map.of("type", "object", "properties", Map.of("cmd", Map.of("type", "string")));
    AgentTool tool = fakeTool("bash", "执行一条命令", schema);

    ToolDef def = ToolDefs.of(tool);

    assertThat(def.name()).isEqualTo("bash");
    assertThat(def.description()).isEqualTo("执行一条命令");
    assertThat(def.jsonSchema()).isEqualTo(schema);
  }

  /** 接口缺省值原样带到模型侧：{@code description} 是空串（不是 null）、{@code jsonSchema} 是"无参数"对象 schema。 */
  @Test
  void carriesInterfaceDefaultsThroughWithoutFillingIn() {
    ToolDef def = ToolDefs.of(fakeTool("no_args"));

    assertThat(def.name()).isEqualTo("no_args");
    // 空串而非 null：ToolDef 只要求非 null，转换点不得替工具编造描述
    assertThat(def.description()).isEmpty();
    assertThat(def.jsonSchema()).isEqualTo(Map.of("type", "object", "properties", Map.of()));
  }

  /** 保序：输出顺序 = 传入顺序（顺序即模型看到的顺序，也是 prompt-cache 前缀的一部分）。 */
  @Test
  void ofAllPreservesInputOrder() {
    List<ToolDef> defs =
        ToolDefs.ofAll(List.of(fakeTool("c", "三"), fakeTool("a", "一"), fakeTool("b", "二")));

    assertThat(defs).extracting(ToolDef::name).containsExactly("c", "a", "b");
  }

  /** 入参是 {@code Collection} 而非 {@code List}：任何集合类型都收，顺序按遍历序。 */
  @Test
  void ofAllAcceptsAnyCollectionTypeInIterationOrder() {
    Collection<AgentTool> tools =
        new LinkedHashSet<>(
            List.of(fakeTool("first", ""), fakeTool("second", ""), fakeTool("third", "")));

    assertThat(ToolDefs.ofAll(tools))
        .extracting(ToolDef::name)
        .containsExactly("first", "second", "third");
  }

  /** 空集合 ⇒ 空列表（合法输入：没有工具的回合整段不发 {@code tools} 字段），不是 null、也不是异常。 */
  @Test
  void ofAllOnEmptyCollectionGivesEmptyList() {
    List<ToolDef> defs = ToolDefs.ofAll(List.of());

    assertThat(defs).isNotNull().isEmpty();
    // 不可变：与 List.copyOf 同一口径
    assertThatThrownBy(() -> defs.add(ToolDef.of("x", "x", Map.of())))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /** null 元素响亮失败并<b>带上位置</b>：静默跳过等于让一个工具凭空消失。 */
  @Test
  void ofAllRejectsNullElementWithPosition() {
    List<AgentTool> tools = new ArrayList<>();
    tools.add(fakeTool("ok", ""));
    tools.add(null);

    assertThatThrownBy(() -> ToolDefs.ofAll(tools))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("第 1 个元素");
  }

  /** null 集合、null 工具：一律 {@code NullPointerException}，不退化成"空转换"。 */
  @Test
  void nullInputsFailLoudly() {
    assertThatThrownBy(() -> ToolDefs.ofAll(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ToolDefs.of(null)).isInstanceOf(NullPointerException.class);
  }

  /** 转换结果是快照：事后改动工具持有的 schema 不影响已转换的定义（模型侧不会被回改）。 */
  @Test
  void conversionSnapshotsSchema() {
    Map<String, Object> mutable = new LinkedHashMap<>();
    mutable.put("type", "object");
    ToolDef def = ToolDefs.of(fakeTool("t", "d", mutable));

    mutable.put("injected", true);

    assertThat(def.jsonSchema()).doesNotContainKey("injected");
    assertThat(def.jsonSchema()).isEqualTo(Map.of("type", "object"));
  }

  /**
   * 已知边界（D24，见 {@link ToolDefs} 类注释）：schema <b>顶层</b>带 {@code null} 值的工具转不过去—— {@link ToolDef} 顶层不收
   * {@code null} 键/值，且本转换点<b>刻意不兜底</b>（抹掉 {@code null} 是替模型编造契约）。
   *
   * <p>这条用例把两件事一起钉成契约：① <b>响亮失败</b>——不静默抹掉、不换默认值；② <b>失败可归因</b>——消息必须点名 是哪个键。第二条不是锦上添花：{@code
   * Map.copyOf} 原生只抛<b>无消息</b>的 NPE，而 schema 常来自外部（MCP 服务器的 inputSchema 是任意 JSON），工具一多就无从知道坏的是哪一条；故
   * llm 侧在 copy 前手扫一遍补上键名。
   *
   * <p>哪天 llm 侧放开了这条约束，本用例会失败并提醒改写它与此处注释，而不是悄悄变成另一种行为。
   */
  @Test
  void topLevelNullSchemaValueFailsLoudlyInsteadOfBeingCoerced() {
    Map<String, Object> schemaWithNull = new LinkedHashMap<>();
    schemaWithNull.put("type", "object");
    // 外部 MCP 服务器的 inputSchema 就可能长这样（McpToolAdapter 因此不用 Map.copyOf 取快照）
    schemaWithNull.put("properties", null);

    assertThatThrownBy(() -> ToolDefs.of(fakeTool("mcp_like", "外部工具", schemaWithNull)))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("properties");
  }

  /**
   * {@link AgentTool#jsonSchema()} 的接口缺省值（副本）。<b>只为省掉本文件里重复的实参</b>：真正的缺省契约由 {@link
   * #carriesInterfaceDefaultsThroughWithoutFillingIn} 用"只覆写 name/execute"的一参形态逐字证明，不靠这个副本。
   */
  private static final Map<String, Object> DEFAULT_SCHEMA =
      Map.of("type", "object", "properties", Map.of());

  /** 只覆写 {@code name}/{@code description}/{@code jsonSchema}：三项都由调用方给足。 */
  private static AgentTool fakeTool(
      String name, String description, Map<String, Object> jsonSchema) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return description;
      }

      @Override
      public Map<String, Object> jsonSchema() {
        return jsonSchema;
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok(name);
      }
    };
  }

  /** {@code jsonSchema} 用 {@link #DEFAULT_SCHEMA}；只关心名字/描述的用例走这个重载。 */
  private static AgentTool fakeTool(String name, String description) {
    return fakeTool(name, description, DEFAULT_SCHEMA);
  }

  /** 只覆写 {@code name}/{@code execute}：{@code description} 与 {@code jsonSchema} 走接口的真缺省实现。 */
  private static AgentTool fakeTool(String name) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok(name);
      }
    };
  }
}
