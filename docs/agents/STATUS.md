# ProjectMosire 当前状态

> **更新时间**：2026-10-17 21:15（Asia/Shanghai）
> **本文是实时状态入口。** `AGENTS.md` 只放常驻纪律并指向这里；`README.md` 的里程碑表、
> `开发计划.md` 的历史执行状态都不再充当实时台账。

## 1. 当前源码、版本与发布基线

| 项目 | 当前事实 | 证据/口径 |
|---|---|---|
| 最近验证的源码提交 | **`1ed2372c2ff7d0cc676624fcd564f72a9a7ca783`** | 状态编写时的源码基线 SHA（不是本文自身提交）；提交时间 `2026-10-17 21:09:47 +08:00`，提交 `feat(scripts): 最小检查闭环（run-bound gate / docs / skills lint）`。 |
| 当前发布版本 | **0.2.2** | 根 `pom.xml:9`；该源码基线在发布提交之后，但版本号仍为 0.2.2。 |
| 发布基线 commit | **`c46e777472bfc48c79d9dad0663c8a1c6c54574c`** | tag `v0.2.2` 指向该提交；0.2.2 发布历史见 §4。 |
| 发布 tag | `v0.2.2` | `git show -s v0.2.2`；提交信息为 `release: 0.2.2 — 上游三库安全与决策契约收口`。 |
| 模块数 | **6** | 根 `pom.xml:42-47`：AgentLib、Workspace、Docs、Brain、Main、BashPlugin。 |
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
| 开始时间 | `2026-10-17 21:09:49 +08:00`。 |
| `source_sha` | **`1ed2372c2ff7d0cc676624fcd564f72a9a7ca783`**。 |
| `run_id` | **`20261017T130949Z-218934-4482`**。 |
| 退出码 / 输出 | `rc=0`；输出 **`FULL VERIFY PASSED`**。 |
| 测试结果 | **1618 tests / 0 failures / 0 errors / 6 skipped**（AgentLib 674、Workspace 62、Docs 81、Brain 472、Bash 104 + 6 skipped、Main 225）。 |
| SpotBugs | 6 个模块均为 `BugInstance size is 0`。 |
| Maven / crosscheck | `MAVEN build=SUCCESS`；`CROSSCHECK log=1618 xml=1618 status=MATCH`。 |
| run-bound 证据 | manifest 记录 source SHA、开始时间、mode=full、scope=repo、命令、worktree=`dirty(tracked=0,untracked=5)`；`ADVISORY mtime_before_start=0`，mtime 仅辅助。 |
| 证据路径 | `.work/gate/20261017T130949Z-218934-4482/summary.txt`、`.work/task-10-evidence/gate-full-at-1ed2372.log`。 |

### 2.4 focused 判定样本

| 样本 | 结果 |
|---|---|
| `VersionTest`（历史 run `20261017T130336Z-211215-31313`，source SHA `996f1fb...`） | 2 tests / 0 failures / 0 errors / 0 skipped；输出 `FOCUSED TEST PASSED (NOT a full gate)`。 |
| `DefinitelyNoSuchTestXYZ`（历史 run `20261017T130338Z-211526-15162`，source SHA `996f1fb...`） | 无 Surefire 报告；输出 `EVIDENCE_INCOMPLETE`，不假绿。 |

> 以上 focused 是判定样本，**不是当前 `1ed2372` 的 full gate 证据**；当前 full gate 见 §2.3。

## 3. 工作树状态

本任务更新 `STATUS.md` 前，工作树只有 5 个预存未跟踪文件（tracked clean）：

```text
?? AgentLibMosire/.factorypath
?? BashPluginMosire/.factorypath
?? BrainMosire/.factorypath
?? MainMosire/.factorypath
?? configs/agents/bash-agent.json
```

本任务只修改本文 `docs/agents/STATUS.md`；不修改其他文件，不 commit、不 push、不跑 Maven。full gate 的 Maven 运行由控制方通过 `./scripts/gate.sh full` 完成，本文只记录其 run-bound 证据。

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
| 落地页 | `index.html` 当前版本显示 **0.2.2**，链接指向上述三库与父 pom。 |
| 核对方式 | `git ls-remote origin refs/heads/gh-pages` 后 `git fetch --depth=1 origin gh-pages`，再用 `git ls-tree` / `git show FETCH_HEAD:index.html` 核对；**未在浏览器里逐个下载 jar**。 |

### 4.3 发布工作流的已知边界

- `.github/workflows/publish-maven.yml` 在 tag `v*` 或手动 dispatch 时运行；先 `./mvnw -B verify`，再 `deploy` 到 Pages 暂存目录。
- 工作流拒绝 `-SNAPSHOT`、拒绝覆盖 gh-pages 上已存在的版本。
- 用户已裁定**不修改“deploy 再跑测试”的行为**；STATUS 只记录事实，不提出工作流改动。

## 5. 未验证 / 未做事项

- **`.github/workflows/docs-skills.yml` 尚未在 GitHub Actions 实跑**；当前只有本地/脚本证据，不能宣称 CI 已绿。
- **宿主 Skill 的发现/显式触发/隐式触发均未验证**；`.agents/skills/` 不存在，active 工程 Skill 仍为 0。
- 本文件记录的是控制方在 `1ed2372` 上完成的 `./scripts/gate.sh full`；**本 STATUS 更新者没有亲自重跑 Maven**，也没有新的独立 full gate 结果。
- 未在浏览器实际下载 Pages 上的 jar；Pages 事实只到 git 对象/路径级核对。
- 未核对 GitHub Actions 的每一次 run 状态；0.2.2 发布事实以 `gh-pages` 提交对象和发布日志为准。
- 未重新跑下游 `~/SimulatorMosire` 消费方测试；`japicmp/契约测试` 也未在本轮重跑。
- 未 commit、未 push；未修改 Java、测试、`scripts/`、`.github/`、`pom.xml`。
- 迁移台账中的 pending 规则尚未裁定，见 `docs/archive/2026-10-17-agents-rule-migration.md`。

## 6. 下一步

1. **Phase 3**：在 `.work/skill-candidates/mosire-verify/` 建立候选，按 pilot 做 RED-GREEN；候选不得放进 `.agents/skills/`，不得直接创建 active Skill。
2. 在 GitHub Actions 实跑 `docs-skills.yml`，并实测目标宿主对工程 Skill 的发现与显式/隐式触发。
3. pilot 通过、宿主验证通过、owner/复核日/维护预算齐全后，才按 `docs/agents/SKILLS.md` 的 active 条件升级；active 以本文为实时摘要。
4. 处理规则迁移台账中的 pending 条目；涉及 AGENTS/门禁/发布工作流的变化先走设计或用户裁定。
5. 下次发布时更新版本、tag、Pages 提交与发布证据；历史 0.2.2 数字只保留为历史证据。

## 7. 当前文档权威关系

| 文档 | 角色 |
|---|---|
| `AGENTS.md` | 常驻纪律、模块边界、红线；顶部指向本文。 |
| `docs/agents/STATUS.md`（本文） | **实时状态入口**；最近验证源码基线、Phase 2 gate、dirty、发布/Pages、未验证、下一步。 |
| `docs/agents/SKILLS.md` | 工程 Skill v2 方案；当前 **0 个 active 工程 Skill**。 |
| `开发计划.md` | 范围/顺序与验收判据；不重复实时记账。 |
| 根 `设计-*.md` + `docs/upstream/**` | 机制与上游契约的权威设计。 |
| `docs/archive/**` | 历史归档；不得当实时状态。 |
| `.superpowers/**` | 历史/本机工具路径；**不是权威实时状态**，本仓不依赖它记账。 |
