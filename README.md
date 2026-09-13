# ProjectMosire

一个 **Java 21 + Maven 多模块、轻量化、可自管理** 的 Agent 软件：以一个大 Agent（Brain）为核心，可自由创建/销毁多个权限各异的子 Agent；对外用 **MCP**（工具/数据）、**A2A**（Agent 间互操作）、**AG-UI**（用户界面）说话；对内用**统一事件存储 + 个人信息模型 + 权限/身份**管理一切状态；且**运行时自身（配置、模块、skills、子 Agent）可被运行中的 Agent 管理**。

## 模块

| 模块 | 职责 | 依赖 |
|---|---|---|
| `AgentLibMosire` | 通用 Agent 工具包（LLM 客户端/接口、AgentTool 接口与 MCP 适配、权限、事件存储、配置、进程管理）——无 Agent 假设，可独立复用 | — |
| `BrainMosire` | Agent 运行时（对话、工具载入、上下文构造/压缩、子 Agent 编排、skills、记忆） | agentlib-mosire |
| `MainMosire` | 入口/网关（组装、CLI、AdminREST、A2A/AG-UI/MCP 入口、发行） | brain-mosire |

## 文档

- **`开发计划.md`** — **现行计划**（2026-09-14 重写：三期收尾 / 多 provider 路由 / 文档与门禁清理）
- **`.superpowers/sdd/2026-09-12-三期/README.md` + `progress.md`** — **权威实时状态**（任务表 + 账本；目录 gitignore，不入版本库）
- `docs/archive/2026-09-14-旧计划/` — **已归档弃用**的三代计划（P0 `开发计划.md`、二期、中期、三期总纲与五个小段、`开发进度.md`）。
  归档目录的 README 列明每个文件曾是什么、以及**仍然开着的接续项**——引用这些文件名的注释（全仓 83 处）按文件名仍可检索到
- `设计-*.md`（仓库根，6 份）— **现行设计文档**：身份与血缘 / 子Agent终局与等取口 / 子Agent上下文读取 / Bash工具与工作目录围栏 / 审批与工具内权限（含 5 条待裁决开口项）
- `Research/调查报告.md` — 2026-09-08 调查报告（协议选型、架构参考、技术栈、风险；**不是**开发计划）
- `Research/notes/` — 调查任务书、四路子调查笔记、二轮调查（版本快照与 A2A-CDI 专项）、SpotBugs effort 标记取舍
- `AGENTS.md` — 仓库约定（模块边界、门禁、红线）

## 快速开始

```bash
# 依赖：JDK 21（Maven 由仓库内 wrapper 提供，./mvnw 锁定 3.9.16）
./mvnw spotless:apply      # 首次：统一格式（勿加 -N，会漏掉模块源码）
./mvnw verify              # 门禁：Spotless → Checkstyle → SpotBugs → 测试
```

## 里程碑状态

> ⚠️ **本表是 P0 视角的历史快照（2026-09-11 前后），数字已陈旧**（如 M2 行的"188 测试""SpotBugs 告警 3+4+10"
> 均非现状）。**当前进度以 `.superpowers/sdd/2026-09-12-三期/README.md` 的任务表为准。**

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 仓库与构建骨架 | ✅ 完成（`./mvnw verify` 全绿） |
| M1 | 骨架 + 主循环 demo（FakeLlmClient 一回合） | ✅ 完成（`./mvnw verify` 全绿；`java -jar MainMosire/target/mosire.jar run --demo` 一回合事件入库、curl /health OK） |
| M2 | MCP 双向 + A2A server/client + AG-UI SSE 子集 + 子 Agent 子进程 | ✅ 完成（MCP 双向、A2A server/client 互操作、AG-UI SSE 子集、子 Agent 子进程编排均已实现；三模块 188 测试全绿；一键离线验收：`scripts/smoke.sh`；全量 `./mvnw verify` 仅剩既有 SpotBugs 告警——AgentLib 3 + Brain 4 + Main 10，均非本次改动引入，见 `docs/archive/2026-09-14-旧计划/开发计划-中期.md` §二 R2） |
| M3 | 自管理闭环 + 事件存储 + 记忆 + skills | 部分落地（九需求域已并入二期 P2-1~P2-7 并完成 6/7 波次；**欠账仍开着**，见 `docs/archive/2026-09-14-旧计划/README.md` §接续项 3） |
| M4 | 后续：MCP 3.0 评估、ACP、jlink/AppCDS、OTel | 未开始 |
