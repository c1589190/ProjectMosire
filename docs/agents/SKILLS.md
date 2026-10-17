# ProjectMosire 工程 Skill v2 方案

> **状态（2026-10-17）**：v2 方案已落盘并完成 task-9 文档修正，**没有创建 `.agents/skills/`，也没有任何 active Skill**。
> 当前文档修正批只改文档；不改 Java、测试、脚本、`.github`、`pom.xml`，不 commit / push / 跑 Maven。
> 本文是“怎样分流规则、怎样评估第一个工程 Skill”的可执行方案，不是“Skill 已经上线”的状态声明。

## 0. 用户裁定、事实基线与本方案的边界

### 0.1 用户裁定（原话与日期）

| 日期 | 用户原话 | 对本方案的约束 |
|---|---|---|
| 2026-10-17 | “工程 Skill 与 Brain 运行时 Skill 必须明确隔离。工程 Skill 根目录为 .agents/skills；当前不创建任何 active Skill。” | 两套 Skill 不共享目录、不共享加载器、不共享权限面；本批只写方案。 |
| 2026-10-17 | “首版只评估 mosire-verify。” | 不批量建 Skill；候选只有 `mosire-verify`，且只进入评估，不进入 active。 |
| 2026-10-17 | “使用宿主技能发现机制，不硬编码 skill_search。” | 工程 Skill 的发现/加载交给宿主；仓库不把 `skill_search` 写成通用前置工具。Brain 的运行时 `SkillAccess` 是另一套面。 |
| 2026-10-17 | “公开 API 兼容基线从 0.2.2 起。” | 0.2.2 的公开类集合与行为是后续兼容比较的起点；旧 0.1.0/0.2.0/0.2.1 不再作为“基线”本身。 |
| 2026-10-17 | “本批只改文档与删除一个旧 jar，不改 Java/测试/scripts/.github/pom.xml。” | 本文只描述未来实现顺序；本批不落任何 Skill 文件、不改门禁脚本。 |
| 2026-10-17 | “不 commit、不 push、不跑 Maven。” | 本文中的命令样例是未来执行规则，不是本批已执行证据。 |

### 0.2 事实来源表（2026-10-17）

| 事实 | 证据/来源 |
|---|---|
| 源码基线为 `c46e777472bfc48c79d9dad0663c8a1c6c54574c`，对应 tag `v0.2.2`。 | `git rev-parse HEAD`、`git tag --points-at HEAD`。 |
| 根 `pom.xml` 的 `project.version` 是 `0.2.3-SNAPSHOT`（2026-10-17 决策包读取端口批次），模块顺序为 AgentLib / Workspace / Docs / Brain / Main / BashPlugin；已发布版本仍是 `0.2.2`。 | `pom.xml:9`、`pom.xml:42-47`；实时状态以 `docs/agents/STATUS.md` 为准。 |
| v0.2.2 发布验证证据为 1618 tests / 0 failures / 0 errors / 6 skipped；分模块为 AgentLib 674、Workspace 62、Docs 81、Brain 472、Bash 104（6 skipped）、Main 225；各模块 SpotBugs `BugInstance size is 0`。 | `.work/release-0.2.2-verify.log:275,373,485,765,873,2521,2585,2599`；这是**发布提交的历史证据**，不是当前实时测试数。 |
| Pages 仓库历史发布 0.2.2：父 pom + `agentlib-mosire` / `workspace-mosire` / `docs-mosire` 三库各含 jar、sources、pom 及校验文件；落地页历史版本为 0.2.2。 | `gh-pages` 最新提交 `ca9d83b4b10bcd9e7f11db8a40c39ce7a21b2289`（消息 `deploy: c46e777...`）；`git ls-tree` 与 `FETCH_HEAD:index.html` 已核对。 |
| 本文编写时工作树只有 5 个未跟踪文件：4 个 `.factorypath` 与 `configs/agents/bash-agent.json`；后续读取端口批次另有未提交改动，实时 dirty 以 `docs/agents/STATUS.md` 为准。 | 本方案编写时的 `git status --porcelain=v1 --untracked-files=all`。 |

### 0.3 本方案的非目标

- 不在本批创建 `.agents/skills/**`、不创建 active Skill、不建立宿主适配器。
- 不把 Brain 运行时 Skill 迁到仓库，也不把工程 Skill 注入运行时。
- 不改 `SkillAccess`、`SkillTools`、权限模型、Maven 门禁或发布工作流。
- 不把历史测试数字、旧目录结构或旧 `.superpowers` 账本当作实时状态。
- 不定义“所有宿主都必须支持”的 Skill 字段；宿主差异单独验证。

## 1. 目标与不变量

### 1.1 目标

1. **单一事实源**：实时状态只在 `docs/agents/STATUS.md`；常驻红线只在 `AGENTS.md`；可复用流程才进工程 Skill。
2. **可执行**：Skill 里的验收必须能落到命令、退出码、报告字段和证据，而不是“注意检查”。
3. **可迁移**：不绑定某一家宿主的工具名；宿主特有的字段只在宿主适配层出现。
4. **可退出**：任何 Skill 都能被降级、退役和删除，不形成只增不减的规则债。
5. **不混淆两套 Skill**：工程 Skill 面向编码代理；Brain 运行时 Skill 面向产品里的 Agent 运行时。

### 1.2 不变量

- 一条规则只有一个**主层**；其他位置只能放指针，不能复制第二份正文。
- **没有执行过的门禁不得写成通过**；`rc=0` 不是充分证据。
- 工程 Skill 不得携带密钥、绝对本机路径、真实配置或事件库内容。
- 工程 Skill 不得要求或假设存在运行时工具 `skill_search` / `skill_load`。
- Brain 运行时只能通过 `io.mosire.brain.skills.SkillAccess` 及其 6 个工具访问运行时 Skill；不得扫描 `.agents/skills/`。
- 任何 active Skill 都必须有 owner、最近复核日期、失败/停用条件和维护预算。

## 2. 五层分流

| 层 | 落点 | 放什么 | 不放什么 | 权威性 |
|---|---|---|---|---|
| **L0 常驻政策** | `AGENTS.md` | 红线、模块边界、跨任务纪律、用户裁定、当前状态入口 | 长流程、逐步施工图、实时测试数字、可按命令机械判定的细节 | 仓库内最高层行为约束；与用户当次指令冲突时让位 |
| **L1 工程 Skill** | active 才放 `.agents/skills/<name>/SKILL.md`；候选/归档见 §3.2 | 可按需复用的工程流程、检查清单、gate/report 模板 | 仓库级红线、Brain 运行时技能、宿主专属策略正文 | 仅 active 可被宿主发现并触发 |
| **L2 可执行门禁** | 现有 `scripts/` 或 Skill 中引用的仓库命令 | 可重复、可退出码判定的检查；格式/链接/测试/静态检查 | 需要人判断的架构决策、历史叙述 | 以实际输出和退出码为准 |
| **L3 状态与证据** | `docs/agents/STATUS.md`、`docs/agents/SKILLS.md`、设计文档、`.work/**`（本地） | 当前快照、Skill 目录、发布证据、未验证项、任务级报告 | 长期不变的红线、运行时 Skill 内容 | STATUS 是实时状态入口；设计文档是机制权威；`.work` 只是本机证据 |
| **L4 历史归档** | `docs/archive/**`、旧报告/旧账本 | 旧计划、旧规则、失效事实、事故留痕 | 当前操作指令、实时状态 | 只作历史留痕，引用时必须标注已被取代 |

### 2.1 分流判定顺序

对每条新规矩依次问：

1. **它是否是任何任务都不能违反的红线？** 是 → L0 `AGENTS.md`。
2. **它是否只在某类重复任务中才需要、且有明确触发条件？** 是 → L1 工程 Skill。
3. **它能否由命令/脚本/测试机械判定？** 是 → L2；L0/L1 只保留“必须执行”的指针和失败语义。
4. **它是否是当前状态、环境快照或发布证据？** 是 → L3；跨机器复用前回写仓库内文档。
5. **它是否已经被新事实取代？** 是 → L4 归档，并在 STATUS 或替代文档写明取代关系。

> 判定原则：**能机械判定的，不写成散文；只在一个任务里有用的，不升格为常驻规则；已经过期的，不留在 L0 冒充现行。**

## 3. 活跃 Skill 的定义与状态机

### 3.1 定义

**活跃 Skill（active）** 是同时满足以下条件的工程 Skill：

1. 位于 `.agents/skills/<name>/SKILL.md`，目录名与 `name` 一致；
2. 通过第 4 节的 Skill 契约检查；
3. 已在第 7 节的首版评估或后续 pilot 中完成至少一次真实任务，并有 gate + report 证据；
4. 在 `docs/agents/STATUS.md` 登记 owner、最近复核日期、适用范围和停用条件；
5. 至少被一个目标宿主的**实际发现机制**验证可发现、可显式调用；
6. 有维护预算与回退路径。

**不设 active 数量上下限。** 数量由质量问题、维护成本和真实复用频率决定，不由配额决定。

### 3.2 进入/退出状态机

```text
proposed ──> drafted ──> pilot ──> active ──> deprecated ──> retired
    │           │           │          │
    │           │           └──────────┴──(回归/宿主失配/越界)──> drafted 或 blocked
    │           └──────────────────────────────────────────────> retired（放弃）
    └──────────────────────────────────────────────────────────> retired（不适用）

位置：
  proposed / drafted / pilot / blocked = .work/skill-candidates/<name>/（宿主不可发现）
  active                               = .agents/skills/<name>/（唯一可被宿主发现的位置）
  deprecated / retired                 = docs/archive/skills/<name>/（宿主不可发现）
```

| 状态 | 位置 / 是否可发现 | 进入条件 | 退出条件 | 允许的副作用 |
|---|---|---|---|---|
| **proposed** | `.work/skill-candidates/<name>/`；不可发现 | 出现重复任务、重复返工或明确用户需求；先记录候选名与触发场景 | 进入 drafted，或直接 retired | 只写候选条目；**不得放进 `.agents/skills/`** |
| **drafted** | `.work/skill-candidates/<name>/`；不可发现 | 已写 `SKILL.md` 草案、契约自检通过、owner 明确 | pilot 通过，或退回 drafted，或 retired | 文件可存在，但**不得对用户宣称 active**，也不得放进 `.agents/skills/` |
| **pilot** | `.work/skill-candidates/<name>/`；不可发现 | 已选一个真实任务；有基线（不用 Skill 的做法）与比较口径；仅显式触发 | gate 全过且 report 完整 → active；失败 → drafted/blocked | 可显式调用；不做隐式触发承诺；不占用 `.agents/skills/` |
| **active** | `.agents/skills/<name>/`；**唯一可被宿主发现的位置** | pilot 通过；在 STATUS 登记 owner/复核日/停用条件；维护预算已分配 | 失真、被替代、无 owner、维护成本大于收益，或有适用任务时表现不好 → deprecated；复核逾期只提醒，不自动降级 | 可被宿主发现；默认仍应可显式调用 |
| **blocked** | `.work/skill-candidates/<name>/`；不可发现 | 宿主行为、权限或事实无法安全判定 | 阻塞原因消除 → drafted/pilot；否则 retired | 不进入 active；在 STATUS 的 pending 中列明；不放进 `.agents/skills/` |
| **deprecated** | `docs/archive/skills/<name>/`；不可发现 | 有替代方案、已失真、无 owner、维护成本大于收益，或有适用任务时表现不好 | 过渡期结束或确认无保留价值 → retired | 仅作历史/迁移参考；必须提示替代路径与截止日期；不得继续被宿主发现 |
| **retired** | `docs/archive/skills/<name>/`；不可发现 | 已归档/删除实现文件，保留历史说明 | —（可重新 proposed） | 不再被宿主发现或调用 |

### 3.3 状态转换的硬规则

- **不得跳过 pilot 直接 active**；用户明确要求“先上线再评估”时，也必须把“未评估”写进 STATUS，不能写成已验证。
- **只有 active 可以放在 `.agents/skills/<name>/` 并被宿主发现**；proposed/drafted/pilot/blocked 只能在 `.work/skill-candidates/<name>/`，deprecated/retired 只能在 `docs/archive/skills/<name>/`。
- **复核逾期只触发复核提醒，不自动改变状态**；active → deprecated 的理由限于失真、被替代、无 owner、维护成本大于收益，或有适用任务时表现不好。**低频率本身不是退出理由。**
- 任何导致“会误导执行”的 Skill 必须立即停用；宁可回到 drafted，也不靠补一句“仅供参考”续命。
- 状态变化必须同时更新：`docs/agents/STATUS.md` 的当前摘要 + 本文的候选表；**实时 active 只以 STATUS.md 为准；本文只维护候选表，不维护实时 active 清单**。
- 不设“最多 N 个 Skill”或“至少 N 个 Skill”的规则。

## 4. Skill 契约

### 4.1 目录与文件

以下结构只适用于 **active** Skill；候选与归档位置见 §3.2：

```text
.agents/skills/<name>/
  SKILL.md              # 必需；active Skill 的唯一入口
  references/           # 可选；长资料按主题拆文件
  assets/               # 可选；模板、样例输入
  scripts/              # 可选；仅该 Skill 特有的辅助脚本（不得含密钥/机器路径）
```

- `<name>` 使用小写 kebab-case：`^[a-z0-9]+(?:-[a-z0-9]+)*$`，建议不超过 64 字符。
- `SKILL.md` 的 `name` 必须等于目录名。
- 一个 Skill 只解决一个可命名的任务族；不要做“万能工程助手”。
- **Skill 内 `scripts/` 只放该 Skill 特有的辅助脚本**；仓库级门禁/检查必须放仓库根 `scripts/` 或既有命令入口，**不得在 Skill 内复制一套 gate**。
- 宿主专属文件（例如 Codex 的 `agents/openai.yaml`）是可选适配层，不是所有 Skill 的必需文件；加入时必须单独验证。

### 4.2 可移植 frontmatter

最小可移植字段：

```yaml
---
name: mosire-verify
description: 在 ProjectMosire 中按门禁执行验证并给出可核对的报告。
---
```

- `name`、`description` 是本仓选择的最小可移植集合；目标宿主是否能读取，必须逐项实测，不构成对所有宿主的承诺。
- 其他字段（包括 `allowed-tools`、`disable-model-invocation`、宿主策略）一律视为**宿主扩展**：
  - 不得把扩展字段当成跨宿主安全边界；
  - 不得因为某宿主支持某字段，就在文档里宣称所有宿主遵守；
  - 扩展字段的缺失必须能优雅退化到“显式调用 + 正文规则”。

### 4.3 `SKILL.md` 必含章节

每个 active/pilot Skill 必须有：

1. **When to use / When not to use**：触发条件、反例、与相邻 Skill 的边界。
2. **Preconditions**：工作目录、JDK/工具、是否有并发 Maven、是否需要网络/密钥、用户授权边界。
3. **Inputs / Outputs**：输入是什么；产出是代码、文档、报告还是证据。
4. **Steps**：有序、可执行；每步写工作目录和预期结果。
5. **Gate**：精确命令、超时/不跑条件、退出码、必须核对的输出、负向检查。
6. **Report**：固定字段模板（见 §8）。
7. **Failure / Stop conditions**：什么时候停手、什么时候上报、什么算 BLOCKED。
8. **Ownership / Maintenance**：owner、最近复核、下次复核、替代/退役条件。
9. **Boundaries**：不碰的文件、密钥纪律、禁止 commit/push 等任务级限制。

### 4.4 内容与安全规则

- 只写从仓库根目录可复现的相对路径；不写 `/home/<user>`、本机临时目录或密钥路径。
- 不放密钥、token、cookie、真实配置值、事件库内容；需要配置时只写“由进程从 `<dataDir>/config.json` 读取”。
- 不把历史测试数字写成当前事实；需要引用时标注“发布提交证据 + 日期”。
- 不在工程 Skill 中调用 Brain 的 `skill_*` 工具；不把 `skill_search` 当作工程 Skill 的发现入口。
- 默认不 commit、不 push；只有任务明确授权才允许，并把实际命令写进报告。
- 不在 Skill 中要求 `git add -A`、`pkill -f`、无路径限定的删除、或吞掉工具错误。
- 命令示例必须考虑管道退出码（取 `${PIPESTATUS[0]}`）和 `-q` 吞汇总的问题。

### 4.5 体量与维护预算（实现建议，非用户裁定）

- `SKILL.md` 建议 ≤ 200 行；单个 `references/*.md` 建议 ≤ 300 行。
- 一个 Skill 的正文 + 引用总量建议 ≤ 600 行；超过时拆成 references 或降级为 docs。
- 每个 Skill 必须有 owner；建议每 90 天或每 3 次相关回归后复核一次。
- 建议每个 active Skill 的日常维护预算 ≤ 20 分钟/月；季度全量复核 ≤ 60 分钟/Skill。
- 超预算不是自动删 Skill 的理由，但必须触发“拆分/降级/退役”的决定，并记录在 STATUS。

## 5. 工程 Skill 与 Brain 运行时 Skill 的隔离

| 维度 | 工程 Skill（本方案） | Brain 运行时 Skill |
|---|---|---|
| 面向对象 | 修改本仓库的编码代理/维护者 | 产品运行中的 Brain Agent |
| 根目录 | active 才放仓库内 `.agents/skills/`；候选 `.work/skill-candidates/`，归档 `docs/archive/skills/` | 运行时数据目录下的 `<dataDir>/skills/`（不是仓库路径） |
| 发现机制 | 宿主技能发现机制；具体宿主各自验证 | `io.mosire.brain.skills.SkillAccess` |
| 工具面 | 宿主自带文件/命令工具；不假设 `skill_search` | `skill_list` / `skill_search` / `skill_load` / `skill_read_resource` / `skill_create` / `skill_edit` |
| 权限模型 | 由编码宿主的工具权限与任务书写明 | 可见面 `skills` / 可编辑面 `skills-edit`，逐层只减不增 |
| 生命周期 | proposed → drafted → pilot → active → deprecated → retired | 运行时可创建/修改/加载；由产品配置管理 |
| 内容 | 工程流程、检查清单、gate/report 模板 | 面向运行时 Agent 的领域知识/操作说明 |
| 提交策略 | 可以随仓库代码审查提交（本批不创建） | 不进仓库；不随代码发布 |
| 互相引用 | **禁止**扫描运行时技能目录 | **禁止**扫描 `.agents/skills/` 或把工程 Skill 当运行时技能 |
| 同名处理 | 与运行时同名也视为两套对象 | 不能因同名而回退到工程目录 |
| 失败语义 | 找不到/不适用 → 不激活，回落人工流程 | 范围外 `RESOURCE_DENIED`；面内不存在 `SKILL_NOT_FOUND`（按既有技能语义） |

**硬隔离规则：**

- 两边目录不得是软链、挂载或父子目录。
- 不给工程 Skill 注入 Brain 的 `ToolContext`、事件库、审批面或运行时权限。
- 不把工程 Skill 复制进发布 JAR、数据目录或 `docs/` 运行时资源。
- 任何“让 Brain 直接读 `.agents/skills`”的接线都按架构变更处理：先设计文档，再实现。

## 6. 宿主差异与可移植性

### 6.1 共同核心

目标宿主的最小共同语义（逐项实测，不构成“所有宿主都支持”的承诺）：

- 能发现 `.agents/skills/<name>/SKILL.md`（具体发现路径由宿主验证）；
- 能读取 `name` / `description`；
- 允许用户**显式**指定 Skill（例如点名 `mosire-verify`）；
- 在字段不认识时安全忽略，而不是把 Skill 当成更高权限。

### 6.2 Codex 适配层

若目标 Codex 版本读取 Skill 根下的 `agents/openai.yaml`，可用它声明该宿主的隐式调用策略：

```yaml
# 示例仅说明字段位置；本批不创建此文件，也不宣称所有 Codex 版本都支持
policy:
  allow_implicit_invocation: false
```

- 该字段的语义、默认值和作用域必须在目标 Codex 版本上实测；不能只凭文档推断。
- `allow_implicit_invocation` 只约束**读取该配置的宿主**；它不影响其他宿主。
- 若设为 `false`，用户仍应能通过显式点名/宿主命令调用；显式路径必须写进 Skill 或宿主说明。

### 6.3 不得宣称的跨宿主保证

- **不得宣称所有宿主都遵守 `disable-model-invocation`。** 该字段可能是某些宿主的扩展，甚至可能被忽略；本仓只把它当作“宿主可选、需实测”的字段。
- 不得宣称所有宿主都支持相同的 frontmatter、工具名、隐式触发、权限模型或脚本执行方式。
- 不得把 `skill_search` 作为工程 Skill 的通用发现工具；它属于 Brain 运行时工具面。
- 宿主差异表的每一行都必须有“已验证版本/验证方式”；未验证的行标记为 **pending**，不进入 active 依据。

### 6.4 宿主差异矩阵

| 宿主/形态 | 发现方式 | 显式调用 | 隐式调用 | 备注 |
|---|---|---|---|---|
| Codex（目标版本待验证） | `.agents/skills/<name>/SKILL.md`；可选 `agents/openai.yaml` | 用户点名 + 宿主命令 | 由 `policy.allow_implicit_invocation` 控制（若版本支持） | 本批不创建配置；具体发现/触发必须实测 |
| 其他支持 Agent Skills 的宿主 | 以宿主文档/实测为准 | 至少保留点名路径 | 不承诺 | 只保证最小 frontmatter |
| 人工维护/无 Skill 发现 | 直接阅读文件 | 人工打开 | 无 | 作为回退路径 |
| Brain 运行时 | `SkillAccess` | `skill_load` | 由运行时策略决定 | **不属于工程 Skill 宿主** |

> 表中的“发现方式/显式调用/隐式调用”都是待验证声明；具体发现与触发必须在目标宿主版本上实测，未实测的行保持 pending。

## 7. 首版评估记录：`mosire-verify`（结论：不激活）

### 7.1 定位

`mosire-verify` 是**候选工程 Skill**，不是 active Skill，也不是 Brain 运行时 Skill。它只做一件事：

> 在 ProjectMosire 仓库中，根据任务类型选择正确的验证门禁，执行命令，核对真实输出，并按固定报告模板如实交代未做/未验证项。

它不负责架构决策、不自动修 bug、不 commit/push、不替代 `AGENTS.md` 的红线。

### 7.2 首版必须覆盖的路径

| 任务类型 | 最小 gate | 不允许做的事 |
|---|---|---|
| 纯文档 | `git diff --check`；相对链接存在性检查；`git status --porcelain` 复核改动范围；模块表与 `pom.xml` 一致性检查（如触及模块表） | 声称跑过 Maven；用旧测试数字充当当前证据 |
| 单模块代码小改 | 先确认本 checkout 无并发 Maven；`./scripts/gate.sh focused <模块> <测试模式>`；报告 gate 的 `summary.txt`、`Tests run` 汇总与 mode | 在并发窗口跑全量；用 `-N` 假绿；只看 `rc=0`；把 focused 写成 full |
| 阶段边界/关账 | `./scripts/gate.sh full`；核对各模块 `Tests run`、failures/errors/skipped、SpotBugs `BugInstance size`；必要时 `spotless:apply` 后再 verify | 把 `test` 当 `verify`；把旧 run 的报告当本轮 |
| 发布/Pages 变更 | 版本与根 pom 一致；`./scripts/gate.sh full`；按发布工作流核对 gh-pages 版本路径与落地页 | 修改发布工作流顺序（用户已裁定不改）；无证据宣称已发布 |

### 7.3 首版评估方法

1. 选一个**真实的小任务**作为 pilot，不使用 Skill 先跑基线；记录耗时、遗漏和返工。
2. 用候选 Skill 的显式调用跑同类任务；记录命令、退出码、报告字段和失败处理。
3. 对照基线，检查：
   - 是否少了必须的 gate；
   - 是否出现“没跑却写通过”；
   - 是否把历史数字当当前事实；
   - 是否在错误的目录/模块执行；
   - 是否误碰任务未授权的文件；
   - 报告是否包含未做/未验证项。
4. 2026-10-17 已完成 pilot：baseline 与 skill 两组在上述正向/负向场景都正确；**没有出现 RED 基线**。
   因此当前不进入 active，只保留评估记录与候选；完整对照见 `docs/agents/skills-eval/2026-10-17-mosire-verify.md`。

### 7.4 首版不评估的内容

- 不评估自动修复、自动提交、自动发版。
- 不评估 Brain 运行时 Skill 的创建/编辑。
- 不评估多宿主同时隐式触发。
- 不评估“所有宿主都遵守某个字段”的跨宿主承诺。

## 8. Gate / Report 精确规则

### 8.1 Gate 的必填字段

每个 Skill 的 gate 必须写成表格或结构化清单，至少包含：

| 字段 | 要求 |
|---|---|
| `id` | 稳定短名，例如 `docs-diff-check`、`maven-focused-test` |
| `applies_to` | 任务类型/模块/触发条件；不适用的任务必须明确写“跳过原因” |
| `cwd` | 必须写明从仓库根执行，或明确子目录 |
| `preconditions` | JDK/工具、无并发 Maven、需要的权限、是否允许联网/密钥 |
| `command` | 精确命令；禁止“跑一下测试”这种无法复现的描述 |
| `timeout` | 超时或“不设超时”的理由；长任务不得无限挂起 |
| `pass_criteria` | 退出码 + 必须核对的输出/报告字段 + 负向检查 |
| `evidence` | 输出保存位置或报告字段；不得只在聊天里留痕 |
| `run_manifest` | 至少 source SHA、开始时间、mode/scope、完整命令；报告与报告文件必须能关联到同一 run |
| `skip_policy` | 什么情况下允许跳过；跳过必须写成 `NOT_RUN` + 原因 |

### 8.2 Gate 判定规则

1. **PASS = 命令实际执行 + 退出码符合预期 + 关键输出/报告已核对。** 仅 `rc=0` 不构成 PASS。
2. **run-bound 证据**：每次 gate 运行前写 run manifest（至少 source SHA、开始时间、mode/scope、完整命令）；运行前移走旧报告，或使用 run 专属报告后缀/目录；只解析本轮 run 产生的报告。**mtime 只能辅助判断，不能单独证明报告属于本轮。**
3. 管道命令必须取 `${PIPESTATUS[0]}`；不得用 `tail` 的退出码冒充被测命令退出码。
4. Maven 聚焦测试必须带 `-pl <模块> -am`；禁止用 `-N` 做代码门禁。
5. 同一 checkout 同时只允许一个 Maven；开工前检查，收工前复核。
6. 全量 `verify` 只在阶段边界/关账或任务明确要求时跑；不得为了“看起来稳”在并发窗口跑。
7. 没跑、被跳过、被环境阻塞 → `NOT_RUN` / `BLOCKED`，绝不写 PASS。
8. 文档任务可以完全没有 Maven gate；此时报告必须明确“未运行 Maven，因为本任务为纯文档”，不能留空让人误解。
9. 发布/Pages 的 gate 必须核对版本号、制品路径、落地页版本，不得只看 workflow 文件存在。
10. 密钥相关 gate 只检查“路径 + 长度/结构脱敏”，不打印值；发现泄露立即停手并报告。
11. **full 与 focused 必须输出不同结论**：full 指完整 `verify`/全仓门禁，focused 指命名测试 + `-pl <模块> -am`；不得把 focused 标成 full，也不得把 full 降级成 focused 后宣称完整门禁通过。
12. **不要求每个依赖模块都有 tests**：focused 上游模块零匹配测试是正常现象（`-Dsurefire.failIfNoSpecifiedTests=false`），但报告必须记录哪些模块零匹配，不能把“没有匹配测试”写成“测试通过”。
13. **源码测试类数不等于测试用例数**：嵌套测试、参数化测试、动态测试以 Surefire XML 的 `tests` 为准；报告不得用源码类文件数替代实际用例数。

### 8.3 Report 的固定字段

```markdown
## 任务
<一句话目标 + 任务编号>

## 基线
- HEAD: <sha>
- tag: <tag 或 none>
- 工作树: <clean / dirty + 未跟踪文件；不要写自引用 HEAD>
- run manifest: <路径/run_id；含 source SHA、开始时间、mode/scope、完整命令>

## 实际执行
| # | 命令（cwd） | 退出码 | 结果摘要 | 证据 |
|---|---|---|---|---|
| 1 | `<command>`（仓库根） | 0 / 非 0 / NOT_RUN | `Tests run: ...` 或关键输出 | `<路径/报告>` |

## 改动
- 文件：...
- 行为/数值变化：...
- 既有断言影响：...

## 未做 / 未验证
- ...

## 证据与事实边界
- 已实测：...
- 仅推断/未实测：...

## 规则判定（如本任务涉及规则迁移）
- keep / migrate / script / invalid / pending：...
```

### 8.4 Report 的硬规则

- **不得声称跑过没有实际运行的 Maven。** 报告中的每个命令都要有真实退出码和输出摘要。
- 必须区分“已验证”“未验证”“仅推断”；禁止把计划中的 Expected 写成实际结果。
- 必须列出改了哪些文件；不得用“若干文档”代替。
- 必须列出 invalid / pending 规则（涉及规则迁移时）。
- 必须写“未做/未验证事项”，不能留空伪装完整。
- 证据优先放任务指定目录或 `.work/`；聊天记录不算留痕，跨会话要复用的结论回写仓库内文档。
- 报告中的测试数字必须带模块、日期、命令和“本轮/发布提交”口径；旧数字必须标历史。
- 测试证据必须能关联到 run manifest 与本轮 Surefire XML；不得只用旧报告或 mtime 证明本轮结果。

## 9. 边界矩阵

| 路径/对象 | 所有者 | 允许内容 | 禁止内容 | 权威/生命周期 |
|---|---|---|---|---|
| `AGENTS.md` | 仓库维护者 | 红线、模块边界、跨任务纪律、状态入口指针 | 实时长报告、可机械执行的完整流程、过期数字 | L0 常驻；用户当次指令优先 |
| `.agents/skills/<name>/SKILL.md` | Skill owner | **仅 active** 的工程流程、gate/report、边界、维护信息 | 候选/归档 Skill、Brain 运行时技能、密钥、机器绝对路径、仓库红线复制 | L1；本批不创建 |
| `docs/agents/SKILLS.md` | 文档 owner | Skill v2 方案、状态机、契约、宿主差异、评估规则 | active 状态谎报、未验证的宿主承诺 | L3 设计/索引；本批创建 |
| `docs/agents/STATUS.md` | 文档 owner | 当前版本、SHA、dirty、发布/Pages、未验证项、下一步 | 历史测试数字冒充实时、自引用 HEAD | L3 实时状态入口 |
| `scripts/` | 仓库维护者 | 可执行门禁、辅助检查 | 架构决策、长期状态 | L2；本批不改 |
| `docs/archive/**` | 归档 owner | 历史计划、旧规则、旧事故 | 当前指令、实时状态 | L4 只读历史 |
| `.work/**` | 本机任务 | 命令输出、临时证据、日志 | 入库、密钥、长期唯一副本 | 本机证据；跨会话结论回写仓库 |
| `.omo/**` | 本机工具 | 本地 SDD/计划/证据 | 入库、充当仓库内权威状态 | 本机工作数据 |
| `AgentLibMosire/target/**` | 构建/发行 | 本机构建产物 | 未获用户批准的 force-track 文件 | target 默认不入库；旧 0.1.0 jar 已按 2026-10-17 裁定删除 |
| `.github/workflows/**` | 发布 owner | 发布/验证流水线 | 本批任何修改；擅自改变现有 `verify → deploy` 顺序或让 `deploy` 跳过测试 | 按用户裁定保持现状 |

## 10. 发布证据链与 0.2.2 基线

### 10.1 当前发布链

```text
源码改动
  → 本地/CI 门禁（./mvnw verify，含 Spotless/Checkstyle/SpotBugs/Surefire）
  → 根 pom 固定版本（0.2.2）
  → git tag v0.2.2（指向 c46e777）
  → publish-maven workflow（先 verify，再 deploy 到 Pages 暂存目录）
  → gh-pages 分支（ca9d83b，deploy: c46e777）
  → 消费方从 https://c1589190.github.io/ProjectMosire/ 取父 pom + 三个库
```

### 10.2 0.2.2 事实

- **tag**：`v0.2.2`；**commit**：`c46e777472bfc48c79d9dad0663c8a1c6c54574c`；**根 pom**：`0.2.2`。
- **发布前门禁证据**：`.work/release-0.2.2-verify.log`，结束于 2026-10-17T19:50:16+08:00，`BUILD SUCCESS`。
- **测试**：1618 tests，0 failures，0 errors，6 skipped；分模块数字见 §0.2。**这是 0.2.2 发布提交的证据，不是当前工作树的实时测试数。**
- **SpotBugs**：每个模块 `BugInstance size is 0`。
- **Pages**：`gh-pages` 当前提交 `ca9d83b4b10bcd9e7f11db8a40c39ce7a21b2289`，提交消息 `deploy: c46e777...`；包含 0.1.0/0.2.0/0.2.1/0.2.2；0.2.2 路径含父 pom 与三库的 jar/sources/pom 及校验文件；落地页历史版本 0.2.2（历史快照）。
- **发布范围**：发布工作流只发布父 pom + AgentLib/Workspace/Docs 三库；Brain/Main/Bash 不发 Pages（与 workflow 注释一致）。
- **发布工作流行为**：现有顺序是先完整 `verify`，再 `deploy`；且 `deploy` 生命周期继续真实执行 Surefire，
  不跳过测试。用户 2026-10-17 裁定不修改这两点。

### 10.3 0.2.2 API 兼容基线

- 从 0.2.2 起，AgentLib 的公开类集合和已钉住行为作为兼容比较基线。
- 后续涉及公开 API/包结构变更时，按 `AGENTS.md §0.5` 走本机 install + 下游消费方验证；不要把 0.1.0/0.2.0/0.2.1 当基线。
- 0.2.2 之前的历史兼容约束保留在归档/迁移文档中，不回写成当前规则。

### 10.4 发布证据的写法

- 引用测试数字必须同时写：**版本/提交 + 日期 + 命令 + 来源文件**。
- `README` 的旧里程碑数字只作历史快照，不能作为当前状态。
- GitHub Actions 的运行页、Pages 的实时可访问性若未当场核对，写“未验证”，不写“应该已发布”。
- 发布证据链的权威入口是 `docs/agents/STATUS.md`；本文只记录证据结构。

## 11. 维护预算与治理

| 项目 | 建议预算 | 触发动作 |
|---|---|---|
| 单个 active Skill 日常维护 | ≤ 20 分钟/月 | 超预算 → 评估拆分/降级 |
| 单个 active Skill 季度复核 | ≤ 60 分钟/季度 | 过期未复核 → 复核提醒；不自动 deprecated/retired |
| 新增 Skill 的评审 | 一次 pilot + 一次 gate/report 复核 | 不通过 → 保持 drafted/blocked |
| 规则迁移 | 每条规则必须分类并给理由 | 无法安全判定 → pending，不静默删除 |
| 文档状态更新 | 每次发布/重要事实变化 | 更新 STATUS；AGENTS 只留指针 |

治理规则：

1. active Skill 的 owner 必须在条目中可见；owner 离职/换手必须显式转交。
2. Skill 与对应代码/命令同变更时，必须在同一个变更批次更新 Skill；不能只改代码留下旧步骤。
3. **复核逾期只触发复核提醒，不按时间自动退役。** 低频率不是退出理由；是否 deprecated/retired 看是否失真、被替代、无 owner、维护成本大于收益，或有适用任务时表现不好。
4. 任何一次“Skill 指导导致错误操作”的事故，先停用再修；修复要有回归证据。
5. 不设 active 数量上下限；不以“Skill 多”作为成熟度指标；也不以“用得少”作为自动删除理由。

## 12. 执行顺序

### 阶段 0：本批（文档收口）

1. 落盘 `docs/agents/SKILLS.md`（本文）。
2. 落盘 `docs/agents/STATUS.md`，记录 0.2.2、dirty、Pages、未验证项。
3. 落盘规则迁移台账，逐条分类 AGENTS 旧规则。
4. 保守更新 `AGENTS.md` / `README.md` / `开发计划.md`，清掉旧事实与 `.superpowers` 权威状态。
5. 删除已批准的旧 jar；只删除该文件，不碰 `target/` 其他内容。
6. 做链接、diff、状态检查；**不跑 Maven**。

### 阶段 1：候选 Skill 草案（2026-10-17 已完成 pilot 评估）

1. 已创建 `.work/skill-candidates/mosire-verify/SKILL.md` 草案（不创建宿主适配文件，不放进 `.agents/skills/`）。
2. 按 §4 契约自检；在本文候选表登记 `evaluated-not-active`。
3. 已完成 2 个真实场景的 baseline / skill 对照：正向 `VersionTest`、负向 `NoSuchPilotTestXYZ`。
4. 结论：**baseline 两次都能正确拒绝假绿并通过正向验证**；skill 的主要收益是把流程收敛到 `scripts/gate.sh` 的 run-bound 证据，而不是修复一个 baseline 失败。按“没有 RED 不建 Skill”原则，**不激活**；把“验证优先用 `scripts/gate.sh`”写进 AGENTS，候选仅留在 `.work`。
5. 详细记录见 `docs/agents/skills-eval/2026-10-17-mosire-verify.md`。

### 阶段 2：宿主验证（仅在未来重新提议 active 时执行）

1. 对目标宿主实际执行发现 + 显式调用测试。
2. 逐一验证宿主差异矩阵中的行；未验证行保持 pending。
3. 如需 `agents/openai.yaml`，只作为 Codex 适配层加入，并实测 `policy.allow_implicit_invocation`。
4. 不宣称其他宿主遵守同一字段。

### 阶段 3：active 与维护

1. 更新 `STATUS.md` 的实时 active 摘要与本文候选表；本文不维护实时 active 清单。
2. 建立 90 天复核提醒；每次发布/流程变化触发同步更新，但提醒不等于自动退役。
3. 只有出现失真、被替代、无 owner、维护成本大于收益，或有适用任务时表现不好，才进入 deprecated → retired；保留历史说明，不静默删除。

## 13. 首版候选登记

| 名称 | 状态 | 范围 | 评估入口 | 是否 active |
|---|---|---|---|---|
| `mosire-verify` | **evaluated-not-active（2026-10-17）** | 文档/代码/发布三类验证任务的 gate + report | 本文 §7、§8；评估记录 `docs/agents/skills-eval/2026-10-17-mosire-verify.md` | 否 |

> 2026-10-17 pilot 结论：baseline 与 skill 都能正确识别 focused/full、0 tests 和 EVIDENCE_INCOMPLETE；
> skill 只带来“更一致地使用 `scripts/gate.sh` 与 run-bound 证据”的收益，没有真实 RED 基线。
> 因此按“没有 RED 不建 Skill”原则不激活；验证入口写入 `AGENTS.md`，候选留在 `.work`，不进入 `.agents/skills/`。
> 本批结束时仍为空 active 集合；这是有意为之，不是遗漏。
