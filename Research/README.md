# Research —— Mosire Agent 软件开发调查目录

> 本目录收集"用 Java 构建多模块、兼容通用 Agent 协议、可创建不同权限子 Agent、可自管理、轻量化 Agent 软件"的**开发调查**材料。
> 主要交付物是 `调查报告.md`（调查报告，**不是**开发计划）。

## 目录结构

```
Research/
├── README.md                  ← 本文件
├── 调查报告.md                 ← 最终交付：写这个 Agent 需要注意哪些方面
├── repos/                     ← 18 个参考仓库浅克隆（shallow，共约 2.2 GB）
├── downloads/                 ← （预留）下载的规范文件/PDF
└── notes/                     ← 调查过程笔记与素材
    ├── 00-任务与原始资料摘要.md      ← 任务书 + 用户 2026-09-07 生态资料摘要 + 核实清单
    ├── 一手仓库抽查笔记.md           ← 主 agent 对 repos 的源码级抽查（协议 SDK 版本/层级/坐标等）
    ├── protocol-stack.md           ← 子调查 A：协议栈现状核查（待收稿）
    ├── reference-architecture.md   ← 子调查 B：参考项目架构分析（待收稿）
    ├── java-stack.md               ← 子调查 C：Java 技术栈注意点（待收稿）
    ├── previous-practice.md        ← 子调查 D：~/DevMosire 既有实践盘点（已收稿）
    ├── 边界与核心注意点分析.md       ← 主 agent 的核心注意点分析
    ├── 一手仓库抽查笔记.md           ← 主 agent 对 repos 的源码级抽查（19 节）
    ├── _报告骨架.md                 ← 报告提纲（中间稿）
    └── _报告草稿-第一~六部分.md      ← 各章节撰写中间稿（最终内容已并入调查报告.md）
```

## 已克隆仓库清单（repos/）

| 目录 | 上游仓库 | 用途 |
|---|---|---|
| modelcontextprotocol | modelcontextprotocol/modelcontextprotocol | MCP 规范本体（2026-07-28 GA 已核实） |
| java-sdk | modelcontextprotocol/java-sdk | MCP 官方 Java SDK（Tier 2，2.0.x/3.x 路线） |
| A2A | a2aproject/A2A | A2A 规范本体（v1.0.1，a2a.proto） |
| a2a-java | a2aproject/a2a-java | A2A 官方 Java SDK（client+server，三 transport） |
| agent-client-protocol | agentclientprotocol/agent-client-protocol | ACP 规范本体（协议版本 1） |
| acp-java-sdk | agentclientprotocol/java-sdk | ACP Java SDK（0.16.0） |
| ag-ui | ag-ui-protocol/ag-ui | AG-UI 规范 + SDK（含 community Java SDK） |
| a2ui | a2ui-project/a2ui | A2UI 规范（v0.9.1 + v1.0 RC） |
| agentskills | agentskills/agentskills | Agent Skills 规范 + skills-ref 校验工具 |
| hermes-agent | NousResearch/hermes-agent | 个人 Agent runtime 参照（learning loop/subagents/state） |
| opencode | anomalyco/opencode | client/server 解耦架构参照 |
| codex | openai/codex | sandbox（bwrap）/execpolicy/agent-identity 参照 |
| goose | block/goose（→ aaif-goose/goose） | 多界面单 core 参照 |
| metagpt | foundationagents/metagpt | 角色/SOP 组织型多 Agent 参照 |
| letta | letta-ai/letta | memory-first 思想（源码已迁 letta-code） |
| browser-use | browser-use/browser-use | 复杂环境 action space 抽象参照 |
| OpenHands | OpenHands/OpenHands | 现为前端仓库；沙箱设计看 agent-server |
| agent-framework | microsoft/agent-framework | AutoGen 后继（.NET/Python/Go，无 Java） |

## 结论分级约定（贯穿所有笔记）

- 【本机验证】：在本机/克隆仓库中直接看到的事实；
- 【有源核实】：有可引用的公开来源；
- 【据文档/社区说法】：来自项目文档或社区但未核实源码；
- 【未能核实】：用户材料中的声明，未找到可证实来源。

## 关键一手结论速览（详见各笔记）

1. **MCP Java SDK 是官方 Tier 2**（非 Tier 1）；2.0.x 追踪 2025-11-25 规范；2026-07-28 规范支持在 3.x（预计 2026-09）。
2. **A2A v1.0.1**（v1.0 发布于 2026-03-12），官方 Java SDK（Apache-2.0，JSON-RPC/gRPC/REST，client+server，有 TCK）。
3. **AG-UI Java SDK 存在但是社区级**（com.ag-ui.community:java-ag-ui 0.1.0，走 JitPack）；规范草稿含 subagents 事件族。
4. **ACP Java SDK 0.16.0**（Apache-2.0，annotation/sync/async，stdio+WebSocket）。
5. **Agent Skills 规范要点**：SKILL.md frontmatter（name 须与目录名一致、description 1-1024 字符、allowed-tools experimental）+ <5000 tokens 渐进披露。
6. 用户既有实践（GSimulator/iBotMosire）已覆盖：ToolLoop、权限 Guard、子 Agent 配置 CRUD 自管理、事件总线、手写 MCP；缺：A2A/AG-UI/ACP、进程级沙箱、统一事件存储。
