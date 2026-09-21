package io.mosire.agentlib.tool;

import io.mosire.agentlib.llm.ToolDef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * <b>{@link AgentTool} → {@link ToolDef} 的唯一转换点</b>：一条逐字直传的映射，宿主（Brain、Simulator 等）对齐此处即可。
 *
 * <p><b>为什么官方入口只此一处</b>：B4 之前这条映射在本仓有两份<b>逐字相同</b>的实现（Brain 的 {@code
 * BasicContextAssembler#buildRequest} 与 {@code AgentPipeline#toolsDefs}），每给工具定义加一个字段就要改两遍。漂移的
 * 代价不在编译期：两处只要漏改一处，模型看到的世界与宿主以为的世界就不再一致，而且<b>没有任何判据能测出来</b>—— 少一个字段不会报错，只会让模型自己猜。
 *
 * <p><b>为什么不放在 Brain</b>：Brain 是这条契约的消费者，不是定义者。{@link AgentTool} 与 {@link ToolDef} 都是 AgentLib
 * 的类型，两者的对应关系属于 AgentLib。放进 Brain 等于宣布"官方转换只对 Brain 可用"，其它宿主（如 SimulatorMosire）只剩两条路：依赖
 * Brain（破坏模块边界），或再抄一份（正是本类要消灭的东西）。
 *
 * <p><b>为什么不放进 {@link ToolDef} 自己</b>：依赖方向不允许。{@code ToolDef} 在 {@code llm} 包，且刻意独立于工具
 * 抽象（见其类注释）——llm 一旦出现吃 {@link AgentTool} 的工厂，只想"拿 llm 调模型"的程序就被迫拖进整套工具栈 （{@code ToolSpec}、{@code
 * ResourceManifest}、审批闸……）。{@code tool → llm} 是本仓允许的方向，本类正落在这条方向上。
 *
 * <p><b>映射表（三项逐字直传，不加工、不补默认值）</b>：
 *
 * <ul>
 *   <li>{@link ToolDef#name()} ← {@link AgentTool#name()}；
 *   <li>{@link ToolDef#description()} ← {@link AgentTool#description()}（缺省空串：合法，但模型可读性差）；
 *   <li>{@link ToolDef#jsonSchema()} ← {@link AgentTool#jsonSchema()}（缺省"无参数"对象 schema）。
 * </ul>
 *
 * <p><b>诚实的边界（D24：不编造填充值）</b>：{@link AgentTool#jsonSchema()} 允许返回带 {@code null} <b>值</b>的 Map
 * ——{@code McpToolAdapter} 就明确产出这种 schema（外部 MCP 服务器的 inputSchema 是任意 JSON，其代码注释直言"不能
 * Map.copyOf"）。而 {@link ToolDef} 顶层不收 {@code null} 键/值（浅层约束：嵌在 {@code properties} 里的 {@code null}
 * 值照常通过，Jackson 也容忍），故顶层带 {@code null} 的工具过本方法会抛 NPE——<b>本类不"修"它</b>：抹掉 {@code null}
 * 或换一个默认值，是替模型编造契约，比响亮失败更坏。要动的是上游 schema 本身。
 *
 * <p><b>诊断留在规则那一侧</b>：这条约束的判据与消息都在 {@link ToolDef}（规则的归属方），本类保持逐字直传。NPE 的 消息会点名是哪条 schema 的哪个键为
 * {@code null}，但<b>不带工具名</b>——本类刻意不 catch 后再包一层：那会把"谁的规则" 搅浑，下一个字段加进来时又多一处要跟着改，正是本类要消灭的东西。
 *
 * <p>本类无状态、无 IO：转换结果只取决于工具自身。
 */
public final class ToolDefs {

  private ToolDefs() {}

  /**
   * 单个工具：{@code name}/{@code description}/{@code jsonSchema} 三项逐字直传。
   *
   * <p><b>不在这里复核字段</b>是有意的：三项非 {@code null} 是 {@link ToolDef} 的既定要求，判据留在字段的归属方；
   * 本类再抄一份校验，等于给"下次加字段"多造一个漂移点——而那正是本类存在的理由。
   *
   * @param tool 待转换的工具；不得为 {@code null}
   * @return 对应的模型侧工具定义
   * @throws NullPointerException {@code tool} 为 {@code null}，或该工具的三个访问器之一返回 {@code null}，或 schema
   *     顶层含 {@code null} 键/值（后两者由 {@link ToolDef} 抛出，消息点名键、不带工具名）
   */
  public static ToolDef of(AgentTool tool) {
    Objects.requireNonNull(tool, "tool");
    return ToolDef.of(tool.name(), tool.description(), tool.jsonSchema());
  }

  /**
   * 批量转换，<b>保序</b>：返回列表与传入顺序逐一对应。
   *
   * <p><b>为什么强调保序</b>：工具顺序就是模型看到的顺序，也是请求 JSON 里 function 数组的顺序；顺序一变，prompt-cache 前缀就失效（与 {@code
   * BasicContextAssembler} 原样回灌 history 是同一条理由）。故本方法<b>不</b>排序、<b>不</b>去重、 <b>不</b>按名字归一——顺序是调用方的事。
   *
   * <p><b>边界</b>：空集合 ⇒ 空列表（合法：没有工具的回合整段不发 {@code tools} 字段）；集合本身或任一元素为 {@code null} ⇒ 立即抛 {@code
   * NullPointerException} 并带上位置。静默跳过 {@code null} 元素等于让一个工具凭空消失 ——那是安静地说错。
   *
   * @param tools 待转换的工具（遍历顺序即输出顺序）；不得为 {@code null}
   * @return 与输入等长、同序、不可变的定义列表
   * @throws NullPointerException {@code tools} 为 {@code null}，或其中含 {@code null} 元素
   */
  public static List<ToolDef> ofAll(Collection<? extends AgentTool> tools) {
    Objects.requireNonNull(tools, "tools");
    List<ToolDef> defs = new ArrayList<>(tools.size());
    int index = 0;
    for (AgentTool tool : tools) {
      defs.add(of(Objects.requireNonNull(tool, "tools 第 " + index + " 个元素（遍历序）")));
      index++;
    }
    return List.copyOf(defs);
  }
}
