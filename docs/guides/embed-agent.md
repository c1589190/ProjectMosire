# 在 Java 应用中使用 Mosire

需要 JDK 21。先按需选择 [Maven 制品](../reference/artifacts.md)。本页代码使用 0.2.7 开发版的公开入口。

## 一次对话

仅需 `agentlib-mosire`。生产调用时由宿主构造 `LlmClient` 并管理模型凭据。下面的固定回复可离线执行。

```java
import io.mosire.agentlib.agent.AgentConfig;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.runtime.SimpleAgent;

var config = AgentConfig.builder("example").systemPrompt("请简短回答").build();
LlmClient model = request -> LlmResponse.text("你好");
var agent = SimpleAgent.create(config, model);
var result = agent.chat("打个招呼");
System.out.println(result.finalText().orElseThrow());
```

同一个 `SimpleAgent` 实例保有内存历史。宿主串行调用 `chat`。一次用户消息到终止是一回合，`TurnResult.turns()` 是其中已完成的模型往返数。

## 注册 Java 工具

继续使用 AgentLib。工具包装、注册和授权分别表达工具如何运行、它从哪里可见、谁可以调用它。

```java
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.util.List;
import java.util.Map;

var tools = new ToolRegistry();
tools.register(AgentTool.of(
    "echo", "返回给定文本",
    Map.of(
        "type", "object",
        "properties", Map.of("text", Map.of("type", "string")),
        "required", List.of("text")),
    ToolSpec.DEFAULT,
    call -> ToolResult.ok((String) call.arguments().get("text"))));
var permissions = AgentPermissionSet.builder(AccessToken.DEFAULT).allow("echo").build();
var agentWithTool = SimpleAgent.create(config, model, tools, permissions);
```

对有副作用的工具，显式声明相应的 `ToolSpec` 和资源要求。缺少授权时调用被拒绝。需要人批准的工具走 Brain 的审批装配。

## 加入工作区或文档

- 需要虚拟路径、挂载和路径授权时声明 `workspace-mosire`。宿主创建 `WorkspaceRegistry`，在配置的资源范围内为 Agent 身份挂载目录。用 `WorkspaceTools.all(registry)` 获取工具并注册到同一个 `ToolRegistry`。
- 需要文档和日志时声明 `docs-mosire`。宿主在能力目录中注册 `DocsCapability`，将能力赋予相应挂载。Java 代码通过 `DocsApi` 调用，Agent 工具通过 `DocsTools.all(registry)` 注册。
- 构造时传入的身份 id 与工作区 `WorkspaceCaller.holder()` 对齐。工作区、文档读取和文档写入的资源需要分别授权。详见 [工作区契约](../upstream/workspace-path-contract.md)和[文档契约](../upstream/docs-storage-contract.md)。

## 持久化会话

声明 `brain-mosire`。一个数据目录供一个活动运行时使用。再次用相同目录和 Agent id 打开时，从现有会话指针和历史续接。

```java
import io.mosire.brain.runtime.AgentRuntime;
import io.mosire.brain.runtime.AgentSpec;
import java.nio.file.Path;

try (var runtime = AgentRuntime.builder(new AgentSpec(config), model)
    .dataDirectory(Path.of(".work", "example-agent"))
    .toolRegistry(tools)
    .permissionSet(permissions)
    .build()) {
  System.out.println(runtime.chat("继续上次对话").finalText().orElse(""));
}
```

Runtime 关闭其打开的数据库和事件总线。采用 `storage(RuntimeStorage)` 的宿主可在多个装配组件间共享储存资源，由宿主维持数据目录的会话所有权和关闭顺序。调用 `chat`、`resetSession` 和 `close` 时保持串行，可从其他线程调用 `cancel`。审批编排器、决策桥和子 Agent 的宿主执行器显式接入。

需要从库内提供 HTTP MCP 时，用 AgentLib 的 `AgentToMcpServer` 传入宿主构造的 `McpHttpSecurity`。宿主负责读取 HTTPS 配置和认证凭据。独立服务入口见 [服务指南](run-server.md)。
