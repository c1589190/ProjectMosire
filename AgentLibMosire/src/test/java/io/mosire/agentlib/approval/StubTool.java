package io.mosire.agentlib.approval;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 桩工具：闸位可在两次调用之间改（模拟"同一工具不同参数落到不同档位"，如 bash 的 {@code ls} 与 {@code systemctl}），
 * 并数出工具体<b>真的跑了</b>几次——"被拒时副作用为零"这条判据全靠它。
 */
final class StubTool implements AgentTool {

  private final String name;
  private final ToolSpec spec;
  private final AtomicInteger calls = new AtomicInteger();

  volatile ToolGate gate = ToolGate.ALLOW;

  StubTool(String name, ToolSpec spec) {
    this.name = name;
    this.spec = spec;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public ToolSpec spec() {
    return spec;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    return gate;
  }

  /** 闸位自报 {@code null}（工具实现违约）：调用方必须按 fail-closed 处理，既不得当成放行，也不得当成"要不要问人"。 */
  StubTool nullGate() {
    gate = null;
    return this;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    calls.incrementAndGet();
    return ToolResult.ok(name + ":executed");
  }

  /** 工具体真的跑了几次（被拒的调用不得让它加一）。 */
  int calls() {
    return calls.get();
  }
}
