# AGENTS.md 规则迁移台账（2026-10-17）

> **状态**：已落盘；本批只做文档与规则溯源，不删除历史、不改 Java/测试/scripts/.github/pom.xml，不 commit / push / 跑 Maven。
> **对象**：2026-10-17 开工时的 `AGENTS.md`（441 行 / 43715 字节）。行号是当时快照，后续会漂移；以章节号 + 本台账 ID 定位。
> **2026-10-17 后续压缩**：AGENTS 已压到 174 行 / 14127 字节；本台账中的旧行号/章节号只指压缩前快照。现行正文见 `AGENTS.md`、`docs/agents/WORKFLOW.md`、`VERIFICATION.md`、`ENVIRONMENT.md`。
> **分类**：
> - `keep`：规则继续留在 `AGENTS.md`，内容有效。
> - `migrate`：规则迁到另一个权威层（`docs/agents/STATUS.md`、`docs/agents/SKILLS.md`、设计文档、归档），`AGENTS.md` 只留指针。
> - `script`：规则应下沉为可执行门禁/脚本；本批不创建脚本，暂留人工兜底。
> - `invalid`：事实或规则已过期/错误；本次更新或删除，旧内容只在本台账留痕。
> - `pending`：无法安全判定，**不静默删除**；等待控制方/用户裁定。
>
> 判定标准：**能机械判定的不写成散文；只对特定任务有效的下沉 Skill；已过期的不能继续冒充现行。**

## 1. 用户原话与日期（逐字保留）

> 以下原话来自本批用户消息与 `AGENTS.md` 已记录的用户裁定；不改写、不缩写。上下文说明放在括号里，不属于原话。

| 日期 | 用户原话 | 关联规则 |
|---|---|---|
| 2026-10-07 | “本文件是 ProjectMosire 唯一的 workspace instruction 文件。” | `AG-00-01` |
| 2026-10-17 | “先提交并推送一下，然后按照B进行开发” | `AG-01-02`（B 方案：通用循环骨架下沉） |
| 2026-10-17 | “AgentConfig 整个下沉” | `AG-01-02` |
| 2026-10-17 | “窗口的结构编排和基础运行本身作为完全可复用的项目下沉到 AgentLib” | `AG-01-02` |
| 2026-10-16 | “先把手头上的代码修改和开发计划做完再刷新包” | `AG-60-07`（旧 force-track jar 刷新时机；该 jar 已于 2026-10-17 批准删除） |
| 2026-10-17 | “太变态了直接等30分钟，改！” | `AG-40-11`（禁止真实等待时钟/deadline） |
| 2026-10-17 | “工程 Skill 与 Brain 运行时 Skill 必须明确隔离。工程 Skill 根目录为 .agents/skills；当前不创建任何 active Skill。” | 新增 `AG-03-13` |
| 2026-10-17 | “首版只评估 mosire-verify。” | 新增 `AG-03-14` |
| 2026-10-17 | “使用宿主技能发现机制，不硬编码 skill_search。” | 新增 `AG-03-15` |
| 2026-10-17 | “公开 API 兼容基线从 0.2.2 起。” | `AG-05-04` |
| 2026-10-17 | “本批只改文档与删除一个旧 jar，不改 Java/测试/scripts/.github/pom.xml。” | 全台账 |
| 2026-10-17 | “不 commit、不 push、不跑 Maven。” | 全台账 |
| 2026-10-17 | “无法安全判定的规则写进 docs/archive/2026-10-17-agents-rule-migration.md 的 pending，并报告。” | 全台账 |
| 2026-10-17 | “不要动未跟踪的 .factorypath 和 configs/agents/bash-agent.json。” | 工作树边界，见 STATUS |
| 2026-10-17 | “不要创建 .agents/skills 目录，也不要创建任何 active Skill；本批只落 docs/agents/SKILLS.md 方案。” | 新增 `AG-03-14` |

### 1.1 已记录但没有逐字引号的历史裁定

| 日期 | 裁定摘要 | 关联规则 |
|---|---|---|
| 2026-07-28 | MCP 新代码禁用 Roots / Sampling / Logging / legacy HTTP+SSE。 | `AG-02-03` |
| 2026-09-13 | 验证一律先上真模型；假 LLM 只用于确定性例外。 | `AG-30-15` |
| 2026-09-14 | 评审按风险触发 + 并行；命中判定/权限、进程线程生命周期、共享件、结构性→判定性降级才派。 | `AG-20-15` |
| 2026-10-13 | 技能工具层的范围外/不可见与面内不存在必须返回不同具名错误（`RESOURCE_DENIED` vs `SKILL_NOT_FOUND`）。 | `AG-03-05` |
| 2026-10-16 | 变异脚本绝对路径 + 还原护栏 + md5 复核；不得把变异体留在源 checkout。 | `AG-30-06`、`AG-30-07` |
| 2026-10-16 | 不要逐批刷新 force-track jar；留到阶段边界/关账。该规则随 2026-10-17 删除 jar 一并失效。 | `AG-60-07`（invalid） |
| 2026-10-17 | 发布工作流“deploy 再跑测试”的行为不修改。 | `AG-05-04`、`AG-30-02`（本批不碰 workflow） |

## 2. 逐条分类：文件头与模块边界

| ID | 旧规则摘要 | 分类 | 理由 / 新落点 |
|---|---|---|---|
| `AG-00-01` | `AGENTS.md` 是唯一 workspace instruction；`CLAUDE.md` / `AGENT.md` 一律读作本文件。 | keep | 用户 2026-10-07 原话；本批保留。 |
| `AG-00-02` | 冲突顺序：用户当次明确指令 > AGENTS > harness/skill 模板；冲突要回报。 | keep | 仍是最高层冲突规则；Skill 方案也引用。 |
| `AG-00-03` | 参考 `~/SimulatorMosire/AGENTS.md`，只吸收对本仓成立的部分。 | keep | 外部参考，不照抄业务规则。 |
| `AG-00-04` | 每条规矩尽量附“为什么”，来自真实返工/事故/裁定。 | keep | 本批迁移文档也按此保留理由。 |
| `AG-01-01` | 模块边界图只有 Main/Brain/AgentLib + Bash 插件（**4 模块口径**）。 | invalid | 当前根 pom 有 6 模块（AgentLib/Workspace/Docs/Brain/Main/BashPlugin）；已在 AGENTS 更新。 |
| `AG-01-02` | AgentLib 是通用工具包；原先“无 Agent 假设”已按两次 2026-10-17 裁定改写，含 AgentConfig、ContextWindow、AgentLoop 等。 | keep | 用户裁定仍需保留；改为“含骨架/描述符，不含具体策略”。 |
| `AG-01-03` | Brain 只依赖 `agentlib-mosire` + Jackson/SLF4J，不得依赖 Main。 | invalid | 依赖事实已过时：现还依赖 `workspace-mosire`、`docs-mosire`；已在 AGENTS 更新。 |
| `AG-01-04` | Main 负责组装/网关/CLI/健康检查/发行，不含 Agent 行为逻辑；不得 import bash。 | keep | 职责与红线保留；原依赖事实已过时（缺 Workspace/Docs + provided Bash），本轮在 AGENTS 更正。 |
| `AG-01-05` | BashPlugin 是 PF4J 插件，main 只依赖 AgentLib，测试面可依赖 Brain。 | keep | 与当前 `BashPluginMosire/pom.xml` 一致；本批不改。 |
| `AG-01-06` | groupId 为 `io.mosire`。 | keep | 仍有效。 |
| `AG-01-07` | Main 对 bash-plugin 的 provided 依赖只服务构建顺序与插件拷贝，不是编译依赖；shade 必须排除。 | keep | 当前 pom 与 AGENTS 一致；本批不改。 |
| `AG-01-08` | 依赖方向由 Maven 层 + import 层双检查；用 `^import` 而不是裸 grep。 | script | 适合下沉为脚本/CI 门禁；本批不创建脚本，AGENTS 暂留人工规则。 |
| `AG-01-09` | 四条 `grep -rnE '^import ...'` 检查命令。 | script | 同上；需带正样本自证，避免假阴性。 |
| `AG-01-10` | README/AGENTS 模块表必须与 6 模块一致。 | migrate（本文 + STATUS 验收） | 本批在 README/AGENTS 更新；STATUS 记录当前模块数。 |

## 3. 逐条分类：红线（0.2）

| ID | 旧规则摘要 | 分类 | 理由 / 新落点 |
|---|---|---|---|
| `AG-02-01` | 权限单调性；spawn/配置变更必须经 `PermissionMonotonicityGuard`。 | keep | 红线，不能因文档整理弱化。 |
| `AG-02-02` | 自升级禁令；Agent 不能替换自身二进制。 | keep | 红线。 |
| `AG-02-03` | MCP 新代码禁用 Roots/Sampling/Logging/legacy HTTP+SSE（2026-07-28 清单）。 | keep | 用户裁定；仍有效。 |
| `AG-02-04` | 信任边界 = 进程边界；子 Agent = 同 jar stdio 子进程。 | keep | 架构红线。 |
| `AG-02-05` | 工具不能关进程；生命周期归 `SubprocessManager` / Main。 | keep | 红线。 |
| `AG-02-06` | 密钥值不进日志/异常/事件/argv/env/stdio/会话输出；配置只打印路径+长度。 | keep | 红线；脱敏按结构的细则见 `AG-60-06`。 |
| `AG-02-07` | 架构决策先落文档再改代码。 | keep | 与本批文档门槛一致。 |

## 4. 逐条分类：Skill 运行时（0.3）与工程 Skill 新增

| ID | 旧规则摘要 | 分类 | 理由 / 新落点 |
|---|---|---|---|
| `AG-03-01` | Brain 运行时唯一执行面是 `SkillAccess`；6 个工具收口。 | keep | 运行时 Skill 面，不得与工程 Skill 混用。 |
| `AG-03-02` | 两个权限命名空间 `skills` / `skills-edit`；前缀语义。 | keep | 运行时权限规则。 |
| `AG-03-03` | 三档 fail-closed：未表态/显式 none/显式 unlimited 或前缀集。 | keep | 不得缺省放大。 |
| `AG-03-04` | 逐层只减不增：可见面与可编辑面求交；模板越界静默求交，请求越界响亮拒绝。 | keep | 权限不变量。 |
| `AG-03-05` | 技能工具层：范围外（含未表态）→ `RESOURCE_DENIED`；面内不存在 → `SKILL_NOT_FOUND`；无存在性 oracle。 | keep | 2026-10-13 更正；以代码/设计为准。 |
| `AG-03-06` | bash/文件工具侧：生产装配必须把 `sandbox.maskedPaths` 交给 `SandboxMask` / `BashSandbox.wrap`。 | keep | 安全边界另一半；已知缺口不能忘。 |
| `AG-03-07` | 主 Agent 缺省不沙箱，可绕过 frontmatter 校验直接改技能文件（已有权限，非越权）。 | keep | 已知缺口必须如实保留。 |
| `AG-03-08` | 外部 MCP 面尚未接线，其 bash 不带遮蔽面。 | keep | 已知缺口。 |
| `AG-03-09` | 非 root 部署下 L3 遮蔽真跑未验证。 | keep | 已知缺口。 |
| `AG-03-10` | 新文件工具必须自行消费 `SandboxMask` / `ResourceAuthorizer` 资源 SPI。 | keep | 防新增旁路。 |
| `AG-03-11` | 技能目录扁平：`<skillsDir>/<name>/SKILL.md`，不递归；不支持删除；不做 embedding/语义检索。 | keep | 运行时 Skill 结构。 |
| `AG-03-12` | 技能范围/工具面/上下文预注入变化属于架构变更，先设计文档后代码；测试要正负向。 | keep | 运行时技能工程规矩。 |
| `AG-03-13` | **新增**：工程 Skill 与 Brain 运行时 Skill 必须明确隔离；工程 Skill 根为 `.agents/skills`。 | migrate（新规则进入 AGENTS + SKILLS.md） | 用户 2026-10-17 原话；本批不创建目录，只落方案。 |
| `AG-03-14` | **新增**：首版只评估 `mosire-verify`；当前不创建 active Skill。 | migrate（`docs/agents/SKILLS.md` §7/§13） | 用户 2026-10-17 原话；STATUS 记录 0 active。 |
| `AG-03-15` | **新增**：使用宿主技能发现机制，不硬编码 `skill_search`。 | migrate（`docs/agents/SKILLS.md` §6） | 用户 2026-10-17 原话；运行时 `skill_search` 仍只属于 Brain 工具面。 |

## 5. 逐条分类：文档地图与外部跟踪（0.4 / 0.5）

| ID | 旧规则摘要 | 分类 | 理由 / 新落点 |
|---|---|---|---|
| `AG-04-01` | `开发计划.md` 是现行计划（2026-09-14 重写）；§五纪律逐条有效。 | keep | 仍有效，但已限定为范围/顺序文档；实时状态入口迁到 STATUS，见 `开发计划.md` 顶部。 |
| `AG-04-02` | 根 `设计-*.md` 是现行设计文档，列出多份设计。 | keep | 仍有效；`SKILLS.md` 只加工程 Skill 方案，不替代设计文档。 |
| `AG-04-03` | `Research/调查报告.md` 是 2026-09-08 调查结论，不是开发计划。 | keep | 仍有效。 |
| `AG-04-04` | `README.md` 的里程碑是历史快照，数字可能陈旧，不作实时状态依据。 | keep | README 更新后仍保留历史表，但明确 STATUS 是入口。 |
| `AG-04-05` | `docs/archive/2026-09-14-旧计划/` 已归档不再修改；Java 注释不逐处改写。 | keep | 历史留痕规则。 |
| `AG-04-06` | `.omo/plans/`、`.omo/drafts/`、`.omo/evidence/` 是本机 SDD/证据目录，不入库；跨会话结论要回写仓库文档。 | keep | 本批也把结论落仓库。 |
| `AG-04-07` | `git log` 与 surefire 报告是当前真实状态最终依据；数字以本轮 verify 为准。 | keep | 规则有效；实时摘要统一回写 STATUS。 |
| `AG-04-08` | 历史文档里的 `.superpowers/...` 路径若不存在，按历史留痕处理，不作为当前状态依据。 | keep（历史说明） | 保留为历史；本批从 README/开发计划移除其“权威实时状态”口径。 |
| `AG-05-01` | MCP Java SDK 3.0 发布即评估；当前锁 2.0.1。 | keep | 外部跟踪项。 |
| `AG-05-02` | AG-UI 1.0 仍 Draft；A2A 用官方 spec + `jsonrpc-common` 自写绑定。 | keep | 外部跟踪项。 |
| `AG-05-03` | BOM 版本季度复核；2026-09-08 快照见根 pom properties。 | keep | 仍有效；快照数字以 pom 为准。 |
| `AG-05-04` | 下游 `~/SimulatorMosire` 依赖 AgentLib；公开类集合只增不减；消费方测试钉 13 个类与 JAR 类数下限。 | migrate（基线改为 0.2.2） | 用户 2026-10-17 裁定“公开 API 兼容基线从 0.2.2 起”；下游验证方式保留。 |
| `AG-05-05` | 公开 API 变更后必须本机 install + 消费方测试。 | keep | 规则有效；与 `AG-05-04` 一起使用。 |

## 6. 逐条分类：并发与工作树（一）

| ID | 旧规则摘要 | 分类 | 理由 / 新落点 |
|---|---|---|---|
| `AG-10-01` | 同一 checkout 一次只能跑一个 Maven；开工/收工前检查。 | keep | 1.6 GB 内存与 target 冲突的真实事故规则。 |
| `AG-10-02` | 作用域 = 本 checkout；不要用裸 pgrep 把隔壁项目算进来。 | keep | 仍有效。 |
| `AG-10-03` | “等 Maven 空闲”不能用会匹配自身的 `pgrep -f`；用 java 过滤或括号技巧；`flock -c` 没有 `set -e`。 | keep | 2026-10-16 互堵事故。 |
| `AG-10-04` | 一个文件一个 owner；动手前 `git status` / 看 mtime；半成品不要接手。 | keep | 多 Agent 工作树纪律。 |
| `AG-10-05` | 不要在 Maven 窗口改 Java；增量编译可能假象，必要时删 classes/test-classes。 | keep | 仍有效。 |
| `AG-10-06` | 产物纪律：别覆盖正在运行服务的 jar；收工用 PID/进程组，不 `pkill -f`。 | keep | 仍有效。 |

## 7. 逐条分类：派单与多 Agent（二）

| ID | 旧规则摘要 | 分类 | 理由 / 新落点 |
|---|---|---|---|
| `AG-20-01` | 调查子 Agent 只读、带方向与问题清单；禁止无方向侦察。 | keep | 协作规则。 |
| `AG-20-02` | 办事子 Agent 只给目标与边界，一个责任区一个 Agent。 | keep | 协作规则。 |
| `AG-20-03` | 责任区 = 完整、可编译、可独立验收的问题域，不是计划编号/文件数。 | keep | 仍有效。 |
| `AG-20-04` | 粒度以开发计划一节为单位；阶段内不逐任务拆单。 | keep | 2026-10-13 补回条款。 |
| `AG-20-05` | 阶段过大时按“可独立编译的层”拆，不按计划编号。 | keep | 例外条款。 |
| `AG-20-06` | 同一时刻只允许一个写代码办事 Agent；只读调查可并行。 | keep | 并发纪律。 |
| `AG-20-07` | compile 只是进入下一责任区门槛，不是完成标准；最终按行为证据验收。 | keep | 仍有效。 |
| `AG-20-08` | 写代码 Agent：只写生产代码到编译过；不写/改测试；不跑 test/verify/package；不 commit；报告行为变化/硬编码/断言影响。 | keep | 任务书三规则。 |
| `AG-20-09` | 测试 Agent：按验收判据、含负向；关键项变异自证；报告“没做/没验证”。 | keep | 任务书三规则。 |
| `AG-20-10` | 架构实现文档门槛：持久状态/运行时阶段/API/数据流/数值变更先落设计。 | keep | §2.5 门槛。 |
| `AG-20-11` | 设计文档必须含需求来源/用户原话/目标/非目标/现状事实/接口/数据流/不变量/验收/负向/所有权。 | keep | 文档门槛细则。 |
| `AG-20-12` | 派单书自包含；实现派单必须有用户原话附录。 | keep | 防只给链接。 |
| `AG-20-13` | 子 Agent 可挑战设计；冲突/歧义立即停手上报；纯技术分歧控制方裁定；纯实现困难 fail-closed 且 BLOCKED。 | keep | 设计纪律。 |
| `AG-20-14` | 控制方不得代替用户裁定设计；区分用户裁定与实现建议；引用用户话必须有日期/原话。 | keep | 本台账按此执行。 |
| `AG-20-15` | 评审按风险触发 + 并行；单模块不超过 3 轮；代码量小时控制方读 diff 即可。 | keep | 2026-09-14 用户裁决。 |
| `AG-20-16` | 后台子代理等待（仅 DeepseekHarness 适用）：每 2 分钟醒一次，不一次睡几分钟。 | pending | 当前 harness 是否仍适用未核实；不能当通用规则。保留原文，标记待控制方确认。 |
| `AG-20-17` | 只有一个子代理要跑时优先前台派单。 | keep | 仍有效。 |

## 8. 逐条分类：构建与门禁（三）

| ID | 旧规则摘要 | 分类 | 理由 / 新落点 |
|---|---|---|---|
| `AG-30-01` | JDK 21 + 仓库 `./mvnw` 3.9.16；勿用系统 mvn。 | keep | 工具链事实。 |
| `AG-30-02` | `./mvnw verify` = Spotless → Checkstyle（effort More/threshold Low）→ SpotBugs → Surefire。 | keep | 门禁定义。 |
| `AG-30-03` | 2026-10-07 全仓真数：AgentLib 518/Brain 334/Bash 104/Main 176 = 1132 tests，4 模块 SpotBugs 0。 | invalid | 旧数字与旧模块数；已换成 0.2.2 发布证据（1618/0/0/6，6 模块 SpotBugs 0），并标注历史口径。 |
| `AG-30-04` | 改完代码先 `spotless:apply` 再 `verify`；之后列全被改文件。 | keep | 仍有效。 |
| `AG-30-05` | 判 SpotBugs 前先删 classes 重编，只跑 `spotbugs:check` 会读旧快照。 | keep | 2026-10-16 幽灵告警事故。 |
| `AG-30-06` | 变异自证必须独占/副本，不能与全量 verify 并发。 | keep | 防假红。 |
| `AG-30-07` | 变异脚本绝对路径 + 还原护栏 + md5 首尾 + `git status` 自证 + 变异表。 | keep | 2026-10-16 事故。 |
| `AG-30-08` | `cp`/`copy2` 还原会带旧 mtime；还原后 touch/utime，确认 `Compiling`。 | keep | 同源陷阱。 |
| `AG-30-09` | 插件目录只许一个版本；升版本后清 `MainMosire/target/plugins/` 或 clean；真进程前先 `ls`。 | keep | 2026-10-17 PF4J 双插件事故；注意这里指插件 jar，不是已被删除的旧 0.1.0 force-track jar。 |
| `AG-30-10` | 勿加 `-N`；`-N` 只构建聚合器，spotless 假绿。 | keep | 实测规则。 |
| `AG-30-11` | 全仓 verify 才是完整门禁；`-pl` 必须带 `-am`；spotless 后用 `git diff --name-only` 列全。 | keep | 仍有效。 |
| `AG-30-12` | `./mvnw test` 不跑 SpotBugs；关账前 verify 或单独 spotbugs:check 并重核计数。 | keep | 仍有效。 |
| `AG-30-13` | 聚焦单测命令；`-Dtest='A+B'` 是 tag 表达式不是“或”，要逗号。 | keep | 语法陷阱。 |
| `AG-30-14` | 别动辄全量测试（D29）；存量离线用例是回归钉；不得为变绿弱化断言/删无关测试。 | keep | 测试纪律。 |
| `AG-30-15` | 验证一律先上真模型（2026-09-13 裁决）；假 LLM 只用于确定性例外；不新增为进离线门禁的假装置。 | keep | 用户裁决；仍有效。 |
| `AG-30-16` | 改 AgentLib 公开 API/包结构后跑下游消费方检查。 | keep | 与 `AG-05-04/05` 一致。 |

## 9. 逐条分类：验证与证据（四）

| ID | 旧规则摘要 | 分类 | 理由 / 新落点 |
|---|---|---|---|
| `AG-40-01` | 不信 `rc=0`；看 Surefire 报告/汇总和 mtime。 | keep | 最贵的假绿教训。 |
| `AG-40-02` | 管道取 `${PIPESTATUS[0]}`，不拿 tail 的退出码。 | keep | 仍有效。 |
| `AG-40-03` | 新护栏/判据要有判别力；变异后当场红再还原；做不到如实说“等价存活”。 | keep | 仍有效。 |
| `AG-40-04` | 计划 Expected 不是事实；不符时先查公式/被测物/夹具，不许改 Expected 迎合实际。 | keep | 防同义反复。 |
| `AG-40-05` | 报 0 命中前先自证工具会命中；禁 `2>/dev/null` 吞错；优先 `git grep`/`--untracked`。 | keep | 假阴性教训。 |
| `AG-40-06` | 拒收/接收判据都要真样本；红要红在被保护行；没红要问为什么没红。 | keep | 仍有效。 |
| `AG-40-07` | 证据 = 真实命令 + 真实输出 + 退出码；报告写命令/结果/改动/行为变化/未验/blocker。 | keep | 报告纪律。 |
| `AG-40-08` | “验过”与“记得”分开；Expected/事实/出处要有当场痕迹；不把推导风险当既成事实。 | keep | 仍有效。 |
| `AG-40-09` | 证据落任务指定目录或 `.omo/evidence/<topic>/`；聊天不算留痕；跨会话结论回写仓库。 | keep | 规则有效；目录约定未统一已列入 `P-03`，本批使用 `.work` 与仓库文档。 |
| `AG-40-10` | 按 surefire 报告求和在改名/删除后会虚高；先清旧报告或与当轮 Maven 汇总交叉核对。 | script | 适合未来脚本核对；本批不建脚本，当前保留人工兜底。 |
| `AG-40-11` | 时钟/deadline/超时用例禁止真实等待；时钟可注入，毫秒窗口或显式 expire；`atMost` ≤ 10s；报告写驱动方式。 | keep | 2026-10-17 用户原话“太变态了直接等30分钟，改！”。 |

## 10. 逐条分类：文档与设计权威（五）

| ID | 旧规则摘要 | 分类 | 理由 / 新落点 |
|---|---|---|---|
| `AG-50-01` | 用户给定的设计 = 最终版本；不得以实现方便/更优设计/时间不够改口径；需用户明示再更新，新增条目不删旧条目。 | keep | 设计权威红线。 |
| `AG-50-02` | 文档时效：冲突以最新已落盘文档为准；旧 spec/plan/status 降为历史；旧文档不回头改，用新文档追加取代。 | keep | 本台账按此原则处理。 |
| `AG-50-03` | 机制性描述回代码核；发现不符要报告，不照措辞硬做。 | keep | 文档漂移教训。 |
| `AG-50-04` | 调查可提出设计修改；推翻旧边界须给依赖/影响/迁移/回退/红线关系，交用户裁定。 | keep | 仍有效。 |
| `AG-50-05` | 架构决策先写文档再改代码；新设计放 `设计-*.md` / `docs/`。 | keep | 与 `AG-02-07` 一致。 |
| `AG-50-06` | 协议消息/数据用 record + sealed interface，穷尽 switch。 | keep | 编码规范。 |
| `AG-50-07` | 公开 API 必须中文 Javadoc；注释中文；折行交给 Spotless。 | keep | 编码规范。 |
| `AG-50-08` | 冻结决策表在 `开发计划.md §1`；变更决策按第 5 条先落文档再改代码。 | pending | 位置已过期（`开发计划.md §1` 现在是“三期收尾”，不是冻结决策表）；意图可保留，但新落点未定，列入 `P-02`，不静默删除。 |

## 11. 逐条分类：提交与协作（六）

| ID | 旧规则摘要 | 分类 | 理由 / 新落点 |
|---|---|---|---|
| `AG-60-01` | 不 `git add -A`；按批次提交；提交前扫 status 与暂存 diff。 | keep | 仍有效。 |
| `AG-60-02` | 提交信息中文，写清改了什么/为什么/验收数字/未验部分。 | keep | 仍有效。 |
| `AG-60-03` | 不篡改历史留痕；归档/旧台账/旧设计不回头改；更正用追加标注。 | keep | 本台账遵守。 |
| `AG-60-04` | 默认不擅自推送；push 以用户当次指令为准；不 force push。 | keep | 本批不 push。 |
| `AG-60-05` | 已确认发现若修复比描述短，当场修；涉及架构/红线先走文档门槛。 | keep | 仍有效。 |
| `AG-60-06` | 密钥纪律；脱敏按结构而非字段名；配置只白名单打印；泄露当场报告并建议轮换。 | keep | 2026-10-17 事故规则。 |
| `AG-60-07` | **唯一例外**：`AgentLibMosire/target/agentlib-mosire-0.1.0-SNAPSHOT.jar` 刻意 force-track；刷新时机；`clean` 后必须 `git checkout HEAD -- ...` 恢复。 | invalid | 用户 2026-10-17 已批准删除该 jar；旧的 force-track 例外、刷新时机、clean 恢复规则全部失效。不得静默重建；如未来需要仓库内分发产物，另立明确用户裁定。 |
| `AG-60-08` | `.omo/`、`.work/`、`target/`、含密钥配置不提交；提交前确认 `.gitignore` 覆盖。 | keep | 仍然有效；与 `AG-60-07` 的旧例外删除一致。 |

## 12. 逐条分类：本机环境与陷阱（七）

| ID | 旧规则摘要 | 分类 | 理由 / 新落点 |
|---|---|---|---|
| `AG-70-01` | JDK 21 / Maven Wrapper 3.9.16；`java -version` 与 `./mvnw -v` 都应对。 | keep | 工具链检查。 |
| `AG-70-02` | 本机内存约 1.6 GB；Maven 256m，Surefire 288m+160m；clean verify 必须前台/受管。 | keep | 具体内存数值是环境快照；换机器先核，不当永久事实（`P-04`）。 |
| `AG-70-03` | `~/.m2` 每台机器各自；AgentLib 要本机重建；判成功看类数/消费方测试，不看时间戳。 | keep | 仍有效。 |
| `AG-70-04` | `.gitattributes` 钉 LF；换设备先看 `core.autocrlf`；行锚定用 `\r?$` 或 `git grep --cached`。 | keep | 行尾陷阱规则仍有效。 |
| `AG-70-05` | **本机现状是 CRLF**；工作树 `.java` 全 CRLF、索引 LF；改文件保持 CRLF。 | invalid | 当前 checkout 实测 `git ls-files --eol` 的受控 Markdown/pom 均为 `i/lf w/lf`，`.gitattributes` 已统一 LF；旧 CRLF 快照过期。已改为“不要假定 EOL，先核对，保持该文件既有 EOL”。 |
| `AG-70-06` | 当前 shell 是 GNU grep/find；换 shell 先 `type -a`；报 0 前自证。 | keep | 工具类型是环境快照，规则本身有效；换机/换 shell 先核。 |
| `AG-70-07` | `usage-test.sh` / `smoke-live.sh` 真联网花钱；独立 data-dir；端口 0；`smoke.sh` 离线。 | keep | 仍有效；本批不运行。 |
| `AG-70-08` | 临时数据/日志/SQLite 放 `.work/` 或任务目录，不落受控路径。 | keep | 仍有效。 |

## 13. 逐条分类：常见错误与外部跟踪（八 / 九）

| ID | 旧规则摘要 | 分类 | 理由 / 新落点 |
|---|---|---|---|
| `AG-80-01` | 幻影判别力：声称 X 测试守着，必须核实 X 存在且会红。 | keep | 仍有效。 |
| `AG-80-02` | 不放宽断言；能收紧就收紧，收不紧写理由。 | keep | 仍有效。 |
| `AG-80-03` | 不把计划数字当事实；Expected 不符先核公式/夹具。 | keep | 与 `AG-40-04` 一致。 |
| `AG-80-04` | 0 命中先自证工具；正则/alias/吞错。 | keep | 与 `AG-40-05` 一致。 |
| `AG-80-05` | 夹具违反新口径先判护栏/夹具，不先放宽护栏。 | keep | 仍有效。 |
| `AG-80-06` | 统计窗口看错：时点/本期/历史累计不可并排。 | keep | 仍有效。 |
| `AG-80-07` | doc drift：计划/台账机制描述过期，使用前回代码核。 | keep | 本批正是处理 doc drift。 |
| `AG-80-08` | 不把“没跑”写成“通过”；报告必须有“我没做/没验证”。 | keep | 本批报告必须遵守。 |
| `AG-90-01` | 外部跟踪项见 §0.5。 | keep（指针） | 指向 `AG-05-*`；不改。 |

## 14. 明确过期的旧事实

以下不是“措辞问题”，而是已核实会误导执行的旧事实；本批已在 `AGENTS.md` / README / 计划中更正，旧值只保留在本台账或历史归档：

| 旧事实 | 旧位置 | 当前事实 | 处置 |
|---|---|---|---|
| “4 模块” / 只有 Main/Brain/AgentLib/Bash 的边界图 | `AGENTS.md §0.1`；README 模块表 | 6 模块：AgentLib、Workspace、Docs、Brain、Main、BashPlugin | `invalid`；更新表/图，README 补全 6 行。 |
| AgentLib “无 Agent 假设” | README 旧文案 | 含 AgentConfig、ContextWindow/ContextComposer/LlmRoundTrip、AgentLoop 等骨架/描述符，不含具体策略 | `invalid`；README/AGENTS 改为新口径。 |
| Brain 只依赖 AgentLib | `AGENTS.md §0.1` | 还依赖 Workspace、Docs | `invalid`；依赖列已更新。 |
| Main 依赖只写 Brain | `AGENTS.md §0.1` / README | 还依赖 AgentLib/Workspace/Docs，且 Bash 为 provided（只构建/插件拷贝） | `invalid`；依赖列已更新。 |
| “1132 tests，0 failures，0 errors，6 skipped；4 模块 SpotBugs 0” | `AGENTS.md §三` | 0.2.2 发布证据是 1618/0/0/6，6 模块 SpotBugs 0；**且不是当前工作树实时结果** | `invalid`；AGENTS 改为 0.2.2 历史证据，实时状态进 STATUS。 |
| “本机现状是 CRLF，工作树 .java 全 CRLF，改文件保持 CRLF” | `AGENTS.md §七` | 2026-10-17 抽查的 AGENTS/README/开发计划/pom 实测 LF；EOL 不能假定 | `invalid`；改为“先核 `git ls-files --eol`，保持既有 EOL，行锚定用 `\r?$`”。 |
| 0.1.0 force-track jar 的例外/刷新/clean 恢复规则 | `AGENTS.md §6.7` | 用户 2026-10-17 批准删除该 jar；target 默认不入库 | `invalid`；删除旧规则，本批只删除该 jar，不重建。 |
| `.superpowers/sdd/2026-09-12-三期/` 是权威实时状态 | `README.md` / `开发计划.md` 顶部 | 实时状态入口改为 `docs/agents/STATUS.md`；`.superpowers` 仅历史/本机路径 | `invalid`（作为权威状态）；已替换。 |
| 设计文档“仓库根 6 份”硬编码 | `README.md` | 设计文档数量会变，应给索引/目录，不写死数量 | `invalid`；README 改为目录索引。 |
| 开发计划冻结决策表在 `§1` | `AGENTS.md §5.8` | 当前 `开发计划.md §1` 不是冻结决策表；位置未定 | `invalid`（位置）+ `pending`（迁移落点）。 |

## 15. Pending 清单（不静默删除，等待裁定）

| ID | 待定问题 | 当前处置 | 需要谁裁定 |
|---|---|---|---|
| `P-01` | `AG-20-16` 的 DeepseekHarness 后台等待规则是否仍适用于当前 harness？ | 保留原文，标记 pending；不据此阻塞本批。 | 控制方/用户 |
| `P-02` | 冻结决策表应落在哪份文档（`开发计划.md` 还是最新 `设计-*.md` 的“结论先行/冻结决策”节）？ | 旧位置标 invalid；`AGENTS.md §5.8` 暂保留意图，待迁移。 | 控制方/用户 |
| `P-03` | 证据目录统一用 `.omo/evidence/<topic>/` 还是任务指定目录/`.work`？ | 两者都保留；本批用 `.work` + 仓库文档，跨会话结论已回写。 | 控制方 |
| `P-04` | “本机内存约 1.6 GB”等环境快照是否继续写入常驻 AGENTS？ | 保留但标注“条件事实，换机先核”。 | 控制方 |
| `P-05` | 工程 Skill 的宿主差异字段（Codex `agents/openai.yaml` / `policy.allow_implicit_invocation`、`disable-model-invocation`）在目标版本的真实行为尚未实测。 | 只写方案与待验证矩阵，不宣称跨宿主保证。 | 未来 Skill 实现批次 + 宿主实测 |
| `P-06` | `AG-05-04` 下游“13 个类 / JAR 类数下限”的具体数值是否仍以 0.2.2 基线重钉？ | 保留验证方法，基线改为 0.2.2；数值未在本批重跑。 | 下游消费方验证批次 |
| `P-07` | 旧 `.factorypath` 与 `configs/agents/bash-agent.json` 未跟踪文件是否长期保留？ | 本批不动、不清理、不提交。 | 用户/控制方 |
| `P-08` | `AG-01-08/09` 的 import 边界检查是否正式脚本化？ | 本批不建脚本；AGENTS 暂留人工命令。 | 后续门禁批次 |

## 16. 本批实际执行的迁移动作

1. `AGENTS.md`：顶部加 `docs/agents/STATUS.md` 入口；模块边界表补 Workspace/Docs 并修正 Brain/Main 依赖；加入工程 Skill / Brain 运行时 Skill 隔离与宿主发现规则；删除 4 模块旧口径、1132 tests 旧数字、CRLF 当前快照、0.1.0 force-track jar 规则；保留所有红线和事故规则。
2. `README.md`：模块表补全 6 模块；AgentLib 描述改为“含通用循环骨架/Agent 描述符，不含具体策略”；文档区以 STATUS 为当前状态入口，移除 `.superpowers` 权威状态；设计文档不写死数量；保留快速开始与许可证。
3. `开发计划.md`：顶部改为范围/顺序文档 + STATUS 实时状态入口；不重写历史内容；保留历史执行状态和归档引用。
4. 新建 `docs/agents/SKILLS.md`、`docs/agents/STATUS.md`、本台账。
5. 删除 `AgentLibMosire/target/agentlib-mosire-0.1.0-SNAPSHOT.jar`（用户 2026-10-17 批准）；不触碰 target 其他文件。
6. 未改 Java/测试/scripts/.github/pom.xml；未 commit / push / 跑 Maven。

## 17. 保留边界

- 历史归档 `docs/archive/2026-09-14-旧计划/**` 不修改；旧事实只在本台账与历史原文中留痕。
- 本台账中的用户原话逐字保留；新增解释都标明为“迁移说明/建议”，不冒充用户裁定。
- 规则若后续被推翻，追加新条目，不删除本台账旧行。
