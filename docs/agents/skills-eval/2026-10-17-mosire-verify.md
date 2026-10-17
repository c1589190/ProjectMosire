# mosire-verify 评估记录（2026-10-17）

> **状态**：`evaluated-not-active`。本文件是候选 Skill 的评估证据，不是 active Skill 声明。
> **结论**：baseline 与 skill 都能正确完成两个场景；没有真实 RED 基线，因此**不创建 active Skill**。
> **候选位置**：`.work/skill-candidates/mosire-verify/SKILL.md`（本机、未入库、不可被宿主发现）。

## 1. 评估方法

- 每个场景两组、同一 checkout、同一模型/工具/权限、同一任务文本；skill 组只多一条：
  “先读 `.work/skill-candidates/mosire-verify/SKILL.md` 并严格按它执行。”
- 本 checkout 同一时刻只允许一个 Maven；两组串行执行。
- 不允许修改仓库文件、commit、push、跑全量 `verify`。
- 证据以 agent 的最终报告、Maven 日志与 `.work/gate` run manifest 为准；聊天记录本身不作为仓库状态。

## 2. 场景 A：真实聚焦测试 `VersionTest`

### baseline（不加载 skill）

- 命令示例：`./mvnw -B -ntp -Dtest=VersionTest -Dsurefire.failIfNoSpecifiedTests=false test -pl AgentLibMosire -am`
- 退出码：`0`
- 结果：`2 tests / 0 failures / 0 errors / 0 skipped`
- 结论：正确；明确写了这是 focused，不是全量 verify；列出了未验证项。
- 证据：`.work/versiontest-focused-20261017T2116/`（baseline 自建 manifest/log）。

### skill（加载候选 skill）

- 命令：`./scripts/gate.sh focused AgentLibMosire VersionTest`
- gate 退出码：`0`
- 结果：`2 tests / 0 failures / 0 errors / 0 skipped`；`CROSSCHECK log=2 xml=2 status=MATCH`
- 结论：正确；输出 `FOCUSED TEST PASSED (NOT a full gate)`；包含 run manifest、summary 与未验证项。
- 证据：`.work/gate/20261017T132033Z-228589-5819/`

## 3. 场景 B：不存在测试 `NoSuchPilotTestXYZ`

### baseline（不加载 skill）

- 命令示例（宽松）：`./mvnw -B -ntp -Dtest=NoSuchPilotTestXYZ -Dsurefire.failIfNoSpecifiedTests=false test -pl AgentLibMosire -am`
- Maven 退出码：`0`（`BUILD SUCCESS`）
- 测试计数：`0`；没有 `Tests run`，没有对应 Surefire 报告
- 结论：正确拒绝“通过”，明确说测试不存在/未执行，并做了 `VersionTest` 正对照。
- 未做：没有生成 `scripts/gate.sh` 的 run manifest；证据是手工日志与源码检查。

### skill（加载候选 skill）

- 命令：`./scripts/gate.sh focused AgentLibMosire NoSuchPilotTestXYZ`
- gate 退出码：`3`（`EVIDENCE_INCOMPLETE`）
- Maven 退出码：`0`（`BUILD SUCCESS`）
- 结果：`tests=0`、`report_files=0`；`RESULT EVIDENCE_INCOMPLETE`
- 结论：正确；没有把 Maven `BUILD SUCCESS` 当成测试通过；输出了 run manifest/summary。
- 证据：`.work/gate/20261017T132000Z-228262-23396/`

## 4. 判定

| 维度 | baseline | skill |
|---|---|---|
| 正向 focused 结果 | 正确 | 正确 |
| 负向 0 tests / 假绿 | 正确拒绝 | 正确拒绝 |
| run-bound 证据 | 手工拼 manifest/log，格式不固定 | 标准 manifest + summary + gate 退出码 |
| 步骤数量/一致性 | 更多手工检查与两套命令 | 固定入口、固定输出 |
| 失败基线（RED） | 未出现 | — |

**结论**：这不是一个“没有 skill 就会做错”的场景；baseline 已能正确判断。skill 的收益是**降低手工差异**，但不足以证明必须增加一个 active Skill。按照用户 2026-10-17 的口径“如果 Agent 不加载 Skill 也能通过同一场景，可能只需要脚本或 AGENTS”，本轮选择：

1. 不把候选移入 `.agents/skills/`；
2. 在 `AGENTS.md` 写入“验证优先使用 `scripts/gate.sh`”的一行规则；
3. 候选留在 `.work`，仅作为未来复评材料；
4. 如果后续出现真实 RED（例如 baseline 把 focused 说成 full、把 0 tests 说成 pass、漏掉 SpotBugs），再重新提议 active。

## 5. 未验证 / 残余风险

- 未验证宿主对 `.agents/skills` 的发现、显式调用、隐式调用。
- `docs-skills.yml` 未在 GitHub Actions 实跑。
- 没有新的 full `verify`；本记录引用的是 `1ed2372` 的 full gate 证据。
- baseline 使用手工 Maven 命令与源码检查；skill 组额外使用现有 `scripts/gate.sh`。两组都依赖现有 Maven/脚本，不能排除“脚本本身过严或过松”的残余风险；脚本自测见 `scripts/gate.sh selftest` 与 `.work/task-10-evidence/`。
