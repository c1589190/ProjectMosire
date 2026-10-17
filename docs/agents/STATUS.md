# ProjectMosire 当前状态

> **更新时间**：2026-10-17 20:49（Asia/Shanghai）
> **本文是实时状态入口。** `AGENTS.md` 只放常驻纪律并指向这里；`README.md` 的里程碑表、
> `开发计划.md` 的历史执行状态都不再充当实时台账。

## 1. 当前版本与源码基线

| 项目 | 当前事实 | 证据/口径 |
|---|---|---|
| 当前发布版本 | **0.2.2** | 根 `pom.xml:9`；tag `v0.2.2`。 |
| 发布基线 commit | **`c46e777472bfc48c79d9dad0663c8a1c6c54574c`** | tag `v0.2.2` 指向该提交；固定记录 SHA，后续提交不会让本行漂移。 |
| 发布 tag | `v0.2.2` | `git show -s v0.2.2`；提交信息为 `release: 0.2.2 — 上游三库安全与决策契约收口`。 |
| 模块数 | **6** | 根 `pom.xml:42-47`：AgentLib、Workspace、Docs、Brain、Main、BashPlugin。 |
| 公开 API 兼容基线 | **0.2.2 起** | 用户 2026-10-17 裁定；后续公开 API 只增不减，变更时走下游消费方验证。 |
| 发布工作流 | **现有 `verify → deploy` 顺序与 `deploy` 继续跑测试的行为均不修改** | 用户 2026-10-17 裁定；本批未碰 `.github/workflows/**`。 |

## 2. 工作树状态

**dirty（未提交）**。本批开工时预存以下未跟踪文件（本批**没有**改动它们）：

```text
?? AgentLibMosire/.factorypath
?? BashPluginMosire/.factorypath
?? BrainMosire/.factorypath
?? MainMosire/.factorypath
?? configs/agents/bash-agent.json
```

本批自身的文件变化：

```text
 M AGENTS.md
 M README.md
 M 开发计划.md
 D AgentLibMosire/target/agentlib-mosire-0.1.0-SNAPSHOT.jar
?? docs/agents/STATUS.md
?? docs/agents/SKILLS.md
?? docs/archive/2026-10-17-agents-rule-migration.md
```

这是任务要求的“只改文档、不 commit”。**不要把预存的未跟踪文件当成本批交付物，也不要在本批清理它们。**

- `AgentLibMosire/target/agentlib-mosire-0.1.0-SNAPSHOT.jar`：按用户 2026-10-17 裁定删除；本批只删这一个旧产物，不触碰 `target/` 其他文件。
- 本批没有修改 Java、测试、`scripts/`、`.github/`、`pom.xml`；没有 commit / push / 跑 Maven。

### 2.1 本批文档修正（task-9）

- `docs/agents/SKILLS.md`：明确只有 active 放在 `.agents/skills/<name>/` 且可被宿主发现；候选放 `.work/skill-candidates/<name>/`，归档放 `docs/archive/skills/<name>/`。取消按 90/180 天自动退役，复核逾期只提醒。补 run-bound gate 证据（run manifest + 本轮 Surefire 报告）、full/focused 结论区分、零匹配测试说明与 Surefire XML 用例数口径；Maven focused 命令去掉 `-q`。
- `AGENTS.md` / `README.md`：DocsMosire 依赖改为 `agentlib-mosire` + `workspace-mosire`；AGENTS §0.5 明确类数下限只是粗探针，兼容性看 japicmp/契约测试和下游实际验证。
- 上述均为文档修正；**本批仍未跑 Maven，也未做宿主发现/触发验证**。`.agents/skills/` 仍不存在，active 工程 Skill 仍为 0。

## 3. 最近发布与 Pages 事实

### 3.1 v0.2.2 发布证据

| 项目 | 事实 |
|---|---|
| 发布 tag | `v0.2.2`，tagger date `2026-10-17 19:50:30 +08:00`；release commit `c46e777...` 的 author/commit date 为 `2026-10-17 19:50:27 +08:00`。 |
| 发布前门禁 | `.work/release-0.2.2-verify.log`，结束于 2026-10-17 19:50:16 +08:00，`BUILD SUCCESS`。 |
| 测试总数 | **1618 tests / 0 failures / 0 errors / 6 skipped**（发布提交证据，不是当前工作树实时测试数）。 |
| 分模块 | AgentLib 674、Workspace 62、Docs 81、Brain 472、Bash 104（6 skipped）、Main 225。 |
| SpotBugs | 6 个模块均为 `BugInstance size is 0`；见同一日志。 |
| 发布范围 | Pages 只发布父 pom + `agentlib-mosire` / `workspace-mosire` / `docs-mosire`；Brain/Main/Bash 不发 Pages。 |

### 3.2 Pages（GitHub Pages 只读 Maven 仓库）

| 项目 | 事实 |
|---|---|
| 仓库地址 | <https://c1589190.github.io/ProjectMosire/> |
| `gh-pages` 最新提交（本批核对） | `ca9d83b4b10bcd9e7f11db8a40c39ce7a21b2289`，提交时间 `2026-10-17 11:56:22 UTC`（`19:56:22 +08:00`），消息 `deploy: c46e777472bfc48c79d9dad0663c8a1c6c54574c`。 |
| 已发布版本目录 | `0.1.0`、`0.2.0`、`0.2.1`、`0.2.2`。 |
| 0.2.2 制品 | 父 pom + AgentLib/Workspace/Docs 三库的 `jar`、`sources.jar`、`pom` 及 `md5`/`sha1`。 |
| 落地页 | `index.html` 当前版本显示 **0.2.2**，链接指向上述三库与父 pom。 |
| 核对方式 | `git ls-remote origin refs/heads/gh-pages` 后 `git fetch --depth=1 origin gh-pages`，再用 `git ls-tree` / `git show FETCH_HEAD:index.html` 核对；**未在浏览器里逐个下载 jar**。 |

### 3.3 发布工作流的已知边界

- `.github/workflows/publish-maven.yml` 在 tag `v*` 或手动 dispatch 时运行；先 `./mvnw -B verify`，再 `deploy` 到 Pages 暂存目录。
- 工作流拒绝 `-SNAPSHOT`、拒绝覆盖 gh-pages 上已存在的版本。
- 用户已裁定**不修改“deploy 再跑测试”的行为**；STATUS 只记录事实，不提出工作流改动。

## 4. 当前文档权威关系

| 文档 | 角色 |
|---|---|
| `AGENTS.md` | 常驻纪律、模块边界、红线；顶部指向本文。 |
| `docs/agents/STATUS.md`（本文） | **实时状态入口**；版本、dirty、发布/Pages、未验证、下一步。 |
| `docs/agents/SKILLS.md` | 工程 Skill v2 方案；当前 **0 个 active 工程 Skill**。 |
| `开发计划.md` | 范围/顺序与验收判据；不重复实时记账。 |
| 根 `设计-*.md` + `docs/upstream/**` | 机制与上游契约的权威设计。 |
| `docs/archive/**` | 历史归档；不得当实时状态。 |
| `.superpowers/**` | 历史/本机工具路径；**不是权威实时状态**，本仓不依赖它记账。 |

## 5. 未验证 / 未做事项

- 本批**没有跑 Maven**；0.2.2 的 1618/0/0/6 只来自发布提交的 `.work/release-0.2.2-verify.log`，不能当作当前工作树验证结果。
- 本批**没有**在浏览器实际下载 Pages 上的 jar，也没有重新跑下游 `~/SimulatorMosire` 消费方测试；Pages 事实只到 git 对象/路径级核对。
- 本批**没有**核对 GitHub Actions 运行页的每一次 run 状态；Pages 发布事实以 `gh-pages` 提交对象为准。
- 本批**没有**创建 `.agents/skills/**`，没有 active 工程 Skill，也没有验证任何宿主对 Skill 的发现/隐式调用行为。
- 本批**没有**修改或验证发布工作流、Java、测试、`scripts/`、`pom.xml`。
- 迁移台账中的 pending 规则尚未裁定，见 `docs/archive/2026-10-17-agents-rule-migration.md`。

## 6. 下一步

1. 审阅并提交本批文档收口（由控制方按批次提交；本 Agent 不 commit/push）。
2. 处理规则迁移台账中的 **pending** 条目：先由用户/控制方裁定，再决定进 `AGENTS.md`、工程 Skill、脚本或归档。
3. 只有在用户另行授权后，才按 `docs/agents/SKILLS.md` §12 创建 `mosire-verify` 的 **drafted** 候选；不得直接建 active Skill。
4. pilot 通过、宿主发现实测通过、owner/复核日/维护预算齐全后，才把 `mosire-verify` 升为 active 并回写本文。
5. 下次发布时更新本文的版本、tag、Pages 提交与验证证据；历史数字只保留为历史证据，不冒充当前结果。
