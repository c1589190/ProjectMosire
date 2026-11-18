# ProjectMosire

ProjectMosire 提供 Java 21 Agent SDK 和可独立运行的 Agent 服务。SDK 支持模型调用、Java 工具、工作区与文档。Brain 在同一套接口上管理会话、事件与审批。服务提供 MCP、A2A 和 AG-UI 接入。

## Java 接入

从 [Java 接入指南](docs/guides/embed-agent.md)开始。只需要模型和工具时，使用 `io.mosire:agentlib-mosire`。工作区和文档按需增加。需要持久化会话和框架装配时，使用 0.2.7 开发版的 `io.mosire:brain-mosire`。

已发布的固定版本在 [Mosire Maven 仓库](https://c1589190.github.io/ProjectMosire/)。开发版安装、各坐标的用途和版本对齐方式见 [Maven 制品参考](docs/reference/artifacts.md)。

## 启动服务

发行包解压后，进入同时包含 `mosire.jar` 和 `plugins/` 的目录，先运行一次离线对话。

```bash
java -jar mosire.jar run --fake --no-mcp-expose --port 0 --data-dir .work/mosire-demo
```

使用启动信息中的 Admin 地址访问 `/health`。对话接口、真实模型配置和从源码构建的步骤见 [服务指南](docs/guides/run-server.md)。

## 模块

| 模块 | 作用 |
|---|---|
| `AgentLibMosire` | 模型客户端、Agent 循环、工具与权限 SPI |
| `WorkspaceMosire` | 挂载、虚拟路径、能力授权和安全 IO |
| `DocsMosire` | 文件与日志文档、Java API 和 Agent 工具 |
| `BrainMosire` | 可嵌入 Agent 会话、存储、上下文、审批和子 Agent 编排 |
| `MainMosire` | CLI、配置装配、网关与服务发行 |
| `BashPluginMosire` | PF4J Bash 工具插件 |

## 维护

验证入口为 `scripts/gate.sh full`。开发基线、验证记录和发布状态见 [维护状态](docs/agents/STATUS.md)。机制、公开契约和其他维护入口见 [文档索引](docs/README.md)。

## 许可证

项目采用 Apache License 2.0，第三方依赖适用各自的许可证。 [LICENSE](LICENSE) · [NOTICE](NOTICE)
