package io.mosire.agentlib.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** {@link BuiltinToolSource} 离线测试：id/快照固定性、onChange no-op 语义。 */
class BuiltinToolSourceTest {

  /** 最小工具桩（只看 name 维度）。 */
  private static AgentTool tool(String name) {
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

  @Test
  void idAndToolsAreFixedAfterConstruction() {
    List<AgentTool> mutable = new ArrayList<>();
    mutable.add(tool("spawn_sub_agent"));
    BuiltinToolSource source = new BuiltinToolSource("builtin", mutable);

    assertThat(source.id()).isEqualTo("builtin");
    assertThat(source.listTools()).extracting(AgentTool::name).containsExactly("spawn_sub_agent");

    // 防御性拷贝：构造后改动输入列表不影响源（内置工具集"焊死"语义）
    mutable.add(tool("kill_sub_agent"));
    assertThat(source.listTools()).extracting(AgentTool::name).containsExactly("spawn_sub_agent");

    // 返回快照不可变：外部无法借 listTools() 撬动源内状态
    assertThat(source.listTools()).isUnmodifiable();
    assertThatCode(() -> source.listTools().add(tool("x")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void onChangeIsNoOpWithWorkingHandle() {
    BuiltinToolSource source =
        new BuiltinToolSource("builtin", List.of(tool("spawn_sub_agent"), tool("list_sub_agents")));
    AtomicInteger fired = new AtomicInteger();

    AutoCloseable handle = source.onChange(fired::incrementAndGet);
    // 静态列表永不变化：订阅即 no-op，不会触发任何回调
    assertThat(fired.get()).isEqualTo(0);
    // 句柄可用：close 不抛异常（可重复收尾）
    assertThatCode(handle::close).doesNotThrowAnyException();
    assertThat(fired.get()).isEqualTo(0);
  }
}
