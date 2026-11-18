# 宿主配置

独立服务由 `Main` 读取配置并装配 Brain。Java 嵌入由宿主提供 `LlmClient`、运行时数据目录和显式权限。 [Java 接入](../guides/embed-agent.md)

## 服务配置和持久状态

| 项 | 位置或入口 | 含义 |
|---|---|---|
| 数据目录 | `run --data-dir` | 配置、`events.db`、工作区和子进程状态的根。缺省 `.work/mosire` |
| 模型 | `<data-dir>/config.json` | `llm.routes.<id>` 配置 `baseUrl`、`model`、`credentialsRef` 和模型能力。用 `llm.route` 选路由。支持既有单一路由写法 |
| 首次配置 | 交互终端或 `run --setup` | 模型配置向导，HTTP 入口绑定回环地址 |
| 模板 | `run --templates-dir` | 允许宿主启用受权限单调性约束的子 Agent |
| 插件 | `plugins.enabled`、`plugins.dir` | 默认查主 JAR 同级 `plugins/`。设 `enabled=false` 可关闭 |
| 工具执行超时 | `tool.executionTimeoutMs` | 主 Agent 的模型工具调用。单位毫秒，缺省 600000，由工具调用入口处理非法值 |
| 审批 | `approval.http`、`approval.httpPort`、`approval.timeoutSeconds` | 宿主审批通道与等候时间 |

使用向导写入凭据，或由嵌入宿主管理凭据来源。在模板、进程参数和日志中使用标识或路径引用。配置格式见 [模型路由设计](../architecture/设计-多provider路由.md)及[配置引导](../architecture/设计-配置引导.md)。

## 网关

| 网关 | 配置 | 缺省 |
|---|---|---|
| Admin | `--port` | 回环 8080 |
| A2A | `--a2a-address`、`--a2a-port` | 回环、自动分配端口 |
| AG-UI | `--agui-address`、`--agui-port` | 回环、自动分配端口 |
| Debug | `--debug-port` | 回环、自动分配端口 |
| MCP | `--mcp-link`、`--no-mcp-expose` | stdio 工具面，外部来源显式接入 |

服务输出实际地址。自定义嵌入宿主可以使用 AgentLib 的 HTTP MCP，并对非回环绑定显式配置安全策略。 [MCP HTTP 安全](../architecture/设计-MCP-HTTP安全.md)
