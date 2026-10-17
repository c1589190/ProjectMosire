# ProjectMosire 当前状态

> **更新时间**：2026-10-17 22:10（Asia/Shanghai）
> **本文是实时状态入口。** `AGENTS.md` 只放常驻纪律并指向这里；`README.md` 的里程碑表、
> `开发计划.md` 的历史执行状态都不再充当实时台账。

## 1. 当前源码、版本与发布基线

| 项目 | 当前事实 | 证据/口径 |
|---|---|---|
| 当前开发版本 | **0.2.3-SNAPSHOT** | 根 `pom.xml:9`；6 个模块 POM 的 `<parent>` 版本均已同步。本批尚未发布。 |
| 已发布版本（历史） | **0.2.2** | 发布基线 commit `c46e777472bfc48c79d9dad0663c8a1c6c54574c`；tag `v0.2.2`；发布历史见 §4。 |
| 最近 full gate 的源码 HEAD | **`33b97e28286c26d29f82d0a6ba9ff6102f6f6b43`** | gate manifest 记录 `worktree=dirty(tracked=12,untracked=8)`；该次全绿覆盖的是工作树内容，不是某个提交对象。 |
| 本批实现提交 | **`0481401`**（`feat(agentlib): 决策包五工具支持宿主读取端口`） | 提交内容是 gate 时的工作树代码、测试、设计文档与契约；提交后未重跑 full。 |
| 模块数 | **6** | 根 `pom.xml`：AgentLib、Workspace、Docs、Brain、Main、BashPlugin。 |
| 公开 API 兼容基线 | **0.2.2 起** | 用户 2026-10-17 裁定；后续公开 API 只增不减，变更时走下游消费方验证。 |
| 发布工作流 | **现有 `verify → deploy` 顺序与 `deploy` 继续跑测试的行为均不修改** | 用户 2026-10-17 裁定；本批未碰 `.github/workflows/**`。 |

## 2. Phase 2：scripts 最小检查闭环与 full gate

### 2.1 scripts 已提交

本次验证的源码基线 `1ed2372...` 已包含：

- `scripts/gate.sh`：run-bound gate；full 模式固定执行 `./mvnw -B -ntp verify`，focused 模式要求命名测试与模块范围；旧报告会归档，mtime 只作辅助。
- `scripts/test-summary.py`：解析 run manifest、Surefire XML 与 Maven 汇总；0 tests、failures、errors、缺失报告、mode 不匹配分别判红或 `EVIDENCE_INCOMPLETE`。
- `scripts/check-docs.py`：文档模块表/链接/STATUS/Pages 版本等一致性检查。
- `scripts/skills-lint.py`：工程 Skill 契约检查；当前 `.agents/skills` 不存在时按 0 active 通过。
- `.github/workflows/docs-skills.yml`：上述文档/脚本/Skill 检查的 CI 工作流；本身不跑 Maven。

### 2.2 scripts selftest 结果

控制方记录的最后一轮 selftest 证据为 `.work/task-10-evidence/final-checks.log`（当时 scripts 尚未提交；随后整体提交为 `1ed2372`）：

| 检查 | 结果 |
|---|---|
| `gate.sh selftest` | **23/23 PASS**（selftest 不调用真实 Maven） |
| `test-summary.py --selftest` | **10/10 PASS** |
| `check-docs.py --selftest` | **8/8 PASS** |
| `skills-lint.py --selftest` | **14/14 PASS** |
| `check-docs.py` 真实仓库 | PASS（modules=6, files=4, links=12） |
| `skills-lint.py` 真实仓库 | PASS（0 active skills；`.agents/skills` 不存在） |

> 本次 STATUS 修正后，又在当前工作树运行 `python3 scripts/check-docs.py` 与 `python3 scripts/skills-lint.py`：均 PASS（非 Maven 检查）。

### 2.3 最近一次 full gate

| 项目 | 事实 |
|---|---|
| 命令 | `./scripts/gate.sh full`（内部 Maven 命令：`./mvnw -B -ntp verify`）。 |
| 开始时间 | `2026-10-17 22:04:47 +08:00`。 |
| `source_sha` | **`33b97e28286c26d29f82d0a6ba9ff6102f6f6b43`**（gate 时工作树 dirty；本批实现随后提交为 `0481401`）。 |
| `run_id` | **`20261017T140447Z-245064-28641`**。 |
| 退出码 / 输出 | `rc=0`；输出 **`FULL VERIFY PASSED`**。 |
| 测试结果 | **1623 tests / 0 failures / 0 errors / 6 skipped**（AgentLib 679、Workspace 62、Docs 81、Brain 472、Bash 104 + 6 skipped、Main 225）。 |
| SpotBugs | 6 个模块均为 `BugInstance size is 0`。 |
| Maven / crosscheck | `MAVEN build=SUCCESS`；`CROSSCHECK log=1623 xml=1623 status=MATCH`。 |
| run-bound 证据 | manifest 记录 source SHA、开始时间、mode=full、scope=repo、命令、worktree=`dirty(tracked=10,untracked=8)`；`ADVISORY mtime_before_start=0`，mtime 仅辅助。 |
| 证据路径 | `.work/gate/20261017T140447Z-245064-28641/summary.txt`。 |

> 更早的 `1ed2372` 全绿证据（run `20261017T130949Z-218934-4482`，1618 tests）保留为历史证据，不冒充本批读取端口改动。

### 2.4 focused 判定样本

| 样本 | 结果 |
|---|---|
| 本批 `DecisionPackagesTest,DecisionPackageToolsTest,DecisionPackageReadPortToolsTest` | run `20261017T140440Z-244762-28701`：39 tests / 0 failures / 0 errors / 0 skipped；输出 `FOCUSED TEST PASSED (NOT a full gate)`。 |
| `VersionTest`（历史 run `20261017T130336Z-211215-31313`，source SHA `996f1fb...`） | 2 tests / 0 failures / 0 errors / 0 skipped；输出 `FOCUSED TEST PASSED (NOT a full gate)`。 |
| `DefinitelyNoSuchTestXYZ`（历史 run `20261017T130338Z-211526-15162`，source SHA `996f1fb...`） | 无 Surefire 报告；输出 `EVIDENCE_INCOMPLETE`，不假绿。 |

### 2.5 Phase 3：`mosire-verify` pilot 结论（不激活）

| 项目 | 结果 |
|---|---|
| 场景 A：`VersionTest` | baseline 与 skill 均得到 `2/0/0/0`；skill 额外输出 gate run manifest/summary 与 `FOCUSED TEST PASSED (NOT a full gate)`。 |
| 场景 B：`NoSuchPilotTestXYZ` | baseline 与 skill 均拒绝把 0 tests 说成通过；skill 通过 gate 得到 `EVIDENCE_INCOMPLETE` 与 run-bound 证据。 |
| RED 基线 | **未出现**；baseline 两个场景都正确。 |
| 决定 | **不创建 active `mosire-verify`**；按“没有 RED 不建 Skill”原则，只把“验证优先用 `scripts/gate.sh` + run-bound summary”写入 `AGENTS.md`。 |
| 候选位置 | `.work/skill-candidates/mosire-verify/`（本机、未入库、宿主不可发现）；评估记录见 `docs/agents/skills-eval/2026-10-17-mosire-verify.md`。 |

### 2.6 AGENTS 压缩（2026-10-17）

- `AGENTS.md` 从 **476 行 / 46.7KB 压到 174 行 / 14.1KB**；只保留常驻红线、六模块边界、并发/验证/文档/提交核心规则与路由。
- 详细派单/评审/自查迁入 `docs/agents/WORKFLOW.md`；并发/门禁/证据/变异/时钟迁入 `docs/agents/VERIFICATION.md`；本机环境迁入 `docs/agents/ENVIRONMENT.md`。

### 2.7 决策包读取端口（2026-10-17，本批）

- 实现提交：`0481401`（`feat(agentlib): 决策包五工具支持宿主读取端口`）。
- 新增 `DecisionPackageReadPort`：`find/pending/all/verdicts/expiryReason`。
- `DecisionPackages implements DecisionPackageReadPort`；旧 `itemVerdicts`、旧构造器/工厂和旧行为保留。
- `DecisionPackageTools` 的读路径改为端口；新增全部 `of/all/tools` 端口重载；工具名、schema、权限、错误码和 JSON 字段不变。
- `find` 可读历史/终局包；`pending()/all()` 仍由宿主按当前 tick/领域状态裁剪。
- `DecisionPackages.verdicts` 为兼容适配器：保留旧 `itemVerdicts` 的 `DECIDING` 投影规则（包括既有把累积裁决摊到未表态条目的行为）；纯累计语义由 Simos core 直接返回 `DecisionPackageSnapshot.verdicts()`。
- 设计和证据见 `设计-决策包读取端口.md`。

## 3. 工作树状态

本批功能提交为 `0481401`；文档收尾（STATUS/SKILLS）随后落库。功能提交后的预期工作树只剩下开工前已有的 5 个未跟踪文件：

```text
?? AgentLibMosire/.factorypath
?? BashPluginMosire/.factorypath
?? BrainMosire/.factorypath
?? MainMosire/.factorypath
?? configs/agents/bash-agent.json
```

- `.work/gate/**` 为 run-bound 证据，默认不入库；
- 本批改动未提交进任何历史归档，未修改 `docs/archive/**`；
- 本批没有修改 `DecisionPackageTransitions`、`DecisionPackageSnapshot`、`DecisionSubmissionPort`、HTTP 审批面或发布工作流；没有修改 Simos 仓库。

## 4. 0.2.2 发布历史与 Pages（保留）

### 4.1 v0.2.2 发布证据

| 项目 | 事实 |
|---|---|
| 发布 tag | `v0.2.2`，tagger date `2026-10-17 19:50:30 +08:00`；release commit `c46e777...` 的 author/commit date 为 `2026-10-17 19:50:27 +08:00`。 |
| 发布前门禁 | `.work/release-0.2.2-verify.log`，结束于 2026-10-17 19:50:16 +08:00，`BUILD SUCCESS`。 |
| 测试总数 | **1618 tests / 0 failures / 0 errors / 6 skipped**（这是 0.2.2 发布提交的历史证据，不是当前工作树实时测试数）。 |
| 分模块 | AgentLib 674、Workspace 62、Docs 81、Brain 472、Bash 104（6 skipped）、Main 225。 |
| SpotBugs | 6 个模块均为 `BugInstance size is 0`；见同一日志。 |
| 发布范围 | Pages 只发布父 pom + `agentlib-mosire` / `workspace-mosire` / `docs-mosire`；Brain/Main/Bash 不发 Pages。 |

### 4.2 Pages（GitHub Pages 只读 Maven 仓库）

| 项目 | 事实 |
|---|---|
| 仓库地址 | <https://c1589190.github.io/ProjectMosire/> |
| `gh-pages` 最新提交（本批核对） | `ca9d83b4b10bcd9e7f11db8a40c39ce7a21b2289`，提交时间 `2026-10-17 11:56:22 UTC`（`19:56:22 +08:00`），消息 `deploy: c46e777472bfc48c79d9dad0663c8a1c6c54574c`。 |
| 已发布版本目录 | `0.1.0`、`0.2.0`、`0.2.1`、`0.2.2`。 |
| 0.2.2 制品 | 父 pom + AgentLib/Workspace/Docs 三库的 `jar`、`sources.jar`、`pom` 及 `md5`/`sha1`。 |
| 落地页（0.2.2 历史快照） | `index.html` 当时显示 **0.2.2**，链接指向上述三库与父 pom。 |
| 核对方式 | `git ls-remote origin refs/heads/gh-pages` 后 `git fetch --depth=1 origin gh-pages`，再用 `git ls-tree` / `git show FETCH_HEAD:index.html` 核对；**未在浏览器里逐个下载 jar**。 |

### 4.3 发布工作流的已知边界

- `.github/workflows/publish-maven.yml` 在 tag `v*` 或手动 dispatch 时运行；先 `./mvnw -B verify`，再 `deploy` 到 Pages 暂存目录。
- 工作流拒绝 `-SNAPSHOT`、拒绝覆盖 gh-pages 上已存在的版本。
- 用户已裁定**不修改“deploy 再跑测试”的行为**；STATUS 只记录事实，不提出工作流改动。

## 5. 未验证 / 未做事项

- `docs-skills.yml` 已在 `33b97e2` 上实跑成功（run `39036796799`），`verify` 也在同一 SHA 上成功（run `39036796619`）；本批读取端口改动尚未 commit/push，因此**没有本批 CI 证据**。
- **宿主 Skill 的发现/显式触发/隐式触发均未验证**；`.agents/skills/` 不存在，active 工程 Skill 仍为 0。
- 本批 full gate 已在工作树 dirty 状态下通过；manifest 的 `source_sha` 是 HEAD `33b97e2`，不等于“已提交包含本批改动的 SHA”。提交后如需发布级证据，应按新 SHA 重跑。
- 已用 `javap` 对照 0.2.2 jar：`DecisionPackageTools` 旧公开成员无删除，新增 7 个端口重载；`DecisionPackages` 旧公开方法无删除（类声明新增 `implements`）。未配置/运行 japicmp。
- 未重新跑下游 `~/SimulatorMosire` 消费方测试；Simos 的 `CoreDecisionPackageReadPort` / `CoreDecisionSubmissionPort` 尚未实现。
- 未在浏览器实际下载 Pages 上的 jar；Pages 事实只到 git 对象/路径级核对。
- 本批不 tag、不刷新 Pages、不发布 `0.2.3`；`0.2.3-SNAPSHOT` 只在开发/本机安装链上。提交与推送按用户当次指令执行，状态以 `git status`/远端 `main` 为准。
- 迁移台账中的 pending 规则尚未裁定，见 `docs/archive/2026-10-17-agents-rule-migration.md`。

## 6. 下一步

1. 把 `0.2.3-SNAPSHOT` 的 AgentLib 安装到本机 Maven 仓（或等待正式发布），供 Simos 更新 `agentlib-mosire.version` 后接入。
2. Simos 实现 `CoreDecisionPackageReadPort`（`find` 读历史、`verdicts` 直接返回 `DecisionPackageSnapshot.verdicts()`、`pending/all` 按 tick 投影）与 `CoreDecisionSubmissionPort`，跑重启/持久化验收。
3. 下游通过后，再决定是否把版本固定为 `0.2.3`、更新 STATUS/tag/Pages 并发布。
4. `mosire-verify` 已完成 pilot 并判定不激活；不再创建 active Skill。后续只有出现真实 RED 时，才重新提议候选。
5. 评估 handoff 时先看 `STATUS.md` + 模板 + `check-docs.py` 是否足够；不够再考虑候选，不按数量配额创建。
6. 处理规则迁移台账中的 pending 条目；涉及 AGENTS/门禁/发布工作流的变化先走设计或用户裁定。

## 7. 当前文档权威关系

| 文档 | 角色 |
|---|---|
| `AGENTS.md` | 常驻纪律、模块边界、红线；顶部指向本文。 |
| `docs/agents/STATUS.md`（本文） | **实时状态入口**；最近验证源码基线、Phase 2 gate、dirty、发布/Pages、未验证、下一步。 |
| `docs/agents/SKILLS.md` | 工程 Skill v2 方案；当前 **0 个 active 工程 Skill**。 |
| `docs/agents/WORKFLOW.md` | 控制方、派单、责任区、评审与收工自查的操作手册。 |
| `docs/agents/VERIFICATION.md` | 并发、gate、Surefire/SpotBugs、run-bound 证据与变异规则。 |
| `docs/agents/ENVIRONMENT.md` | 本机环境、EOL、工具差异与临时数据约束。 |
| `docs/agents/skills-eval/2026-10-17-mosire-verify.md` | `mosire-verify` pilot 的 baseline/skill 对照与“不激活”结论；不是 active Skill。 |
| `设计-决策包读取端口.md` | 本批读口设计、用户原话、验收判据与免责边界。 |
| `开发计划.md` | 范围/顺序与验收判据；不重复实时记账。 |
| 根 `设计-*.md` + `docs/upstream/**` | 机制与上游契约的权威设计。 |
| `docs/archive/**` | 历史归档；不得当实时状态。 |
| `.superpowers/**` | 历史/本机工具路径；**不是权威实时状态**，本仓不依赖它记账。 |
