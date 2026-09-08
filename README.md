# ProjectMosire

一个 **Java 21 + Maven 多模块、轻量化、可自管理** 的 Agent 软件：以一个大 Agent（Brain）为核心，可自由创建/销毁多个权限各异的子 Agent；对外用 **MCP**（工具/数据）、**A2A**（Agent 间互操作）、**AG-UI**（用户界面）说话；对内用**统一事件存储 + 个人信息模型 + 权限/身份**管理一切状态；且**运行时自身（配置、模块、skills、子 Agent）可被运行中的 Agent 管理**。

## 模块

| 模块 | 职责 | 依赖 |
|---|---|---|
| `AgentLibMosire` | 通用 Agent 工具包（LLM 客户端/接口、AgentTool 接口与 MCP 适配、权限、事件存储、配置、进程管理）——无 Agent 假设，可独立复用 | — |
| `BrainMosire` | Agent 运行时（对话、工具载入、上下文构造/压缩、子 Agent 编排、skills、记忆） | agentlib-mosire |
| `MainMosire` | 入口/网关（组装、CLI、AdminREST、A2A/AG-UI/MCP 入口、发行） | brain-mosire |

## 文档

- `开发计划.md` — P0 开发计划（冻结决策 D1–D12、里程碑 M0–M4、验收标准）
- `开发计划-中期.md` — 中期执行计划（R 波修复 → W 波 M2 收官 → M3 波次；含现状审计结论与逐项验收标准）
- `Research/调查报告.md` — 2026-09-08 调查报告（协议选型、架构参考、技术栈、风险；**不是**开发计划）
- `Research/notes/` — 调查任务书、四路子调查笔记、二轮调查（版本快照与 A2A-CDI 专项）、SpotBugs effort 标记取舍
- `AGENTS.md` — 仓库约定（模块边界、门禁、红线）

## 快速开始

```bash
# 依赖：JDK 21（Maven 由仓库内 wrapper 提供，./mvnw 锁定 3.9.16）
./mvnw -N spotless:apply   # 首次：统一格式
./mvnw verify              # 门禁：Spotless → Checkstyle → SpotBugs → 测试
```

## 里程碑状态

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 仓库与构建骨架 | ✅ 完成（`./mvnw verify` 全绿） |
| M1 | 骨架 + 主循环 demo（FakeLlmClient 一回合） | ✅ 完成（`./mvnw verify` 全绿；`java -jar MainMosire/target/mosire.jar run --demo` 一回合事件入库、curl /health OK） |
| M2 | MCP 双向 + A2A server/client + AG-UI SSE 子集 + 子 Agent 子进程 | 进行中（MCP 双向、A2A server/client 已实现并通过互操作测试；AG-UI、子 Agent 编排未开始——缺口与波次见 `开发计划-中期.md`） |
| M3 | 自管理闭环 + 事件存储 + 记忆 + skills | 未开始（拆解为 M3-A/B/C/D，见 `开发计划-中期.md` §三） |
| M4 | 后续：MCP 3.0 评估、ACP、jlink/AppCDS、OTel | 未开始 |
