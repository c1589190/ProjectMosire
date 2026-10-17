# AGENTS.md —— ProjectMosire 常驻内核 + 路由

> ★ **冲突顺序**：用户当次明确指令 > 本文件 > harness / Skill 模板。冲突先上报，不静默绕。
> ★ 本文件只放**始终成立**的红线、边界与路由；长流程、事故细节、命令手册放 `docs/agents/`，按需读。
> ★ 当前状态：`docs/agents/STATUS.md`。工程 Skill 方案：`docs/agents/SKILLS.md`（当前 0 个 active）。
> ★ 本文件是唯一 workspace instruction 文件；旧名 `CLAUDE.md` / `AGENT.md` 一律读作本文件。
> ★ 不要用 `@` 导入长文；按需打开对应文件，避免把几千行展开进上下文。

---

## 〇、项目、边界与红线

### 0.1 模块边界

```text
MainMosire ──→ BrainMosire ──→ AgentLibMosire
    │              ├──→ WorkspaceMosire ──→ AgentLibMosire
    │              └──→ DocsMosire ──→ WorkspaceMosire
    ├──→ AgentLibMosire / WorkspaceMosire / DocsMosire（直接组装）
    └── provided BashPluginMosire（仅构建顺序/插件拷贝；主代码不 import）

BashPluginMosire ──→ AgentLibMosire（PF4J provided；宿主不 import）
```

| 模块 | artifactId | 允许内部依赖 / 要点 |
|---|---|---|
| `AgentLibMosire` | `agentlib-mosire` | 无 `io.mosire.*` 内部依赖；含 Agent 描述符、上下文窗口结构、通用循环骨架与策略 SPI，**不含具体策略**；不得 import Brain/Main。 |
| `WorkspaceMosire` | `workspace-mosire` | `agentlib-mosire`；安全路径解析、挂载与能力面；不得反向依赖 Brain/Main。 |
| `DocsMosire` | `docs-mosire` | `agentlib-mosire` + `workspace-mosire`；文档/日志存储；不得反向依赖 Brain/Main。 |
| `BrainMosire` | `brain-mosire` | `agentlib-mosire` + `workspace-mosire` + `docs-mosire`（+ Jackson/SLF4J）；不得依赖 Main。 |
| `MainMosire` | `main-mosire` | `brain-mosire` + `agentlib-mosire` + `workspace-mosire` + `docs-mosire`；Bash 仅 provided，主代码不得 import。 |
| `BashPluginMosire` | `bash-plugin-mosire` | main 只依赖 `agentlib-mosire`（PF4J provided）；Brain 仅 test scope；宿主不 import。 |

- **groupId**：`io.mosire`。
- **Main 对 bash-plugin 的 provided 依赖**只服务构建顺序/插件拷贝，不是编译依赖；shade 必须排除它。
- 依赖边界由 **Maven enforcer + 源码 import 审计**两层检查；只用 `^import` 精确匹配，不用裸 grep 判断。
- AgentLib 两次边界改写（AgentConfig/ContextWindow/AgentLoop）的完整理由见 `设计-上下文窗口下沉.md`、`设计-通用循环骨架.md`。

### 0.2 红线（任何模块、任何批次）

1. **权限单调性**：Agent 不能授予自己/子 Agent 超过自身的权限；spawn/配置变更必须经 `PermissionMonotonicityGuard`。
2. **自升级禁令**：Agent 可管理配置/skills/子 Agent，不能替换自身二进制；发行走外部流程。
3. **MCP 新代码禁用**：Roots、Sampling、Logging、legacy HTTP+SSE（2026-07-28 弃用清单）。
4. **信任边界 = 进程边界**：子 Agent 是同 jar stdio 子进程；进程内逻辑隔离只放自己审过的插件。
5. **工具不能关进程**：任何工具不得结束进程；生命周期归 `SubprocessManager` / `Main`。
6. **密钥纪律**：密钥值绝不进日志/异常/事件/argv/env/stdio/会话输出；配置只打印路径 + 长度；脱敏按结构，不按字段名。
7. **架构决策先落文档**：新增/修改持久状态、运行时阶段、对外 API/工具面/载荷、数据流/所有权/生命周期、数值公式，先落设计文档再改代码。

### 0.3 工程 Skill 与 Brain 运行时 Skill

- **工程 Skill**：`.agents/skills/<name>/SKILL.md`，维护本仓库的编码 Agent 使用；只有 `active` 才能放在该目录。
- **Brain 运行时 Skill**：产品能力，唯一执行面是 `io.mosire.brain.skills.SkillAccess` 与其 6 个工具；根目录是运行时 `<dataDir>/skills/`。
- 两套 Skill **不共享目录、加载器、权限面或生命周期**；不得软链、挂载、互相扫描或复制。
- 工程 Skill 使用**宿主提供的技能发现机制**；宿主没有时按目录元数据选择。**不硬编码 `skill_search`**，也不用 Brain 的 `skill_*` 工具找工程 Skill。
- 当前 active 工程 Skill = **0**；`mosire-verify` 已完成 pilot 评估，结论为不激活（`docs/agents/skills-eval/2026-10-17-mosire-verify.md`）。
- 运行时 Skill 的命名空间、三档 fail-closed、逐层只减不增、已知缺口以 `设计-Skill运行时能力.md` 为准；不要因文档压缩忘记这些缺口。

### 0.4 文档地图

| 文档 | 内容 |
|---|---|
| `docs/agents/STATUS.md` | 实时状态：源码基线、dirty、最近验证、发布/Pages、blocker、下一步。 |
| `docs/agents/SKILLS.md` | 工程 Skill 方案：五层分流、状态机、契约、宿主差异、gate/report、维护预算。 |
| `docs/agents/WORKFLOW.md` | 控制方、派单粒度、任务书/测试 Agent、设计文档门槛、评审与等待、收工自查。 |
| `docs/agents/VERIFICATION.md` | 并发、`scripts/gate.sh`、Surefire/SpotBugs、run-bound 证据、变异自证、时钟约束。 |
| `docs/agents/ENVIRONMENT.md` | JDK/Maven、内存、`~/.m2`、LF/CRLF、grep/find、真模型脚本、临时目录。 |
| `docs/upstream/*.md` | 三个上游库的公开契约（决策包 / Workspace 路径 / Docs 存储），契约正文不要复制到 AGENTS。 |
| 根 `设计-*.md` | 身份/子 Agent/权限/决策包/上下文窗口/通用循环等现行设计。 |
| `docs/archive/**`、`开发计划.md` | 历史与范围/顺序；不冒充实时状态。 |
| `.omo/**`、`.work/**`、`target/**` | 本机工具/证据/构建产物，默认不入库；跨会话结论回写仓内文档。 |

> `.superpowers/**` 若在本仓不存在，按历史留痕读，**不是权威实时状态**。

### 0.5 下游消费方

- `~/SimulatorMosire` 依赖本仓 `AgentLibMosire`；改公开 API/包结构后，按需在本机 install 并跑消费方检查。
- **公开 API 兼容基线从 0.2.2 起**；三库公开类集合只增不减，被钉住的类不得改名/挪包。
- 类数下限只是粗探针；方法签名/构造器/行为兼容以 japicmp/契约测试和下游实际验证为准。
- 不在本仓管理下游业务实现；只保证上游契约与兼容性说明。

---

## 一、并发与工作树（常驻核心）

1. **同一个 checkout 一次只跑一个 Maven**；别的仓库/工作树不阻塞本仓。开工/收工用 `scripts/gate.sh pre` 或等价检查。
2. **一个文件一个 owner**；动手前 `git status --porcelain`，看到半成品尾巴先报告，不接手。
3. **同一时刻只允许一个写代码 Agent**；只读调查可并行，但不得共享写目标。
4. **不要在 Maven 运行窗口里改 Java**；不要把“被内存守卫杀掉”当成红或绿。
5. **不要覆盖正在运行的 jar**；服务在跑时只做 `test`/`compile`，打包前先停服务或打快照。
6. 串行化用 `flock -w <秒> .work/maven.lock -c '<命令>'`；`flock -c` 没有 `set -e`，多命令段要显式处理退出码。
7. 细节（pgrep 写法、变异并发、增量编译、插件 jar 两版本）见 `docs/agents/VERIFICATION.md`。

---

## 二、工作方式（常驻核心）

- **控制方**负责调查综合、约束设计、派单、审阅、验收、提交；实现默认交给子 Agent。用户明确要求或单点小修时控制方可直接做，但报告要说明。
- **责任区**按“可独立编译、可独立验收的目标/问题域”切，不按计划编号或文件数切；一个责任区一个写代码 Agent。
- **任务书必须写清**：目标/非目标、文件所有权、验收判据、停止条件、是否允许跑测试/提交；做不到就是 `BLOCKED`，不是 `DONE`。
- **测试与变异自证最后统一做**；实现 Agent 默认只写生产代码到编译过，不写/不改测试、不 commit。
- **架构文档门槛**：改持久状态、运行时阶段、对外 API/工具面/载荷、数据流/所有权/生命周期、数值公式，先落设计文档；纯重命名/单点 bugfix 可简短说明。
- **评审按风险触发**：权限/判定、进程线程生命周期、共享件、结构性变更才派独立评审；命中即先提交后评审，与下一块并行；单模块评审不超过 3 轮。
- **只有一个子 Agent 时优先前台派单**；具体模板、等待策略与收工自查见 `docs/agents/WORKFLOW.md`。

---

## 三、构建与验证（常驻核心）

- JDK 21 + 仓库内 `./mvnw`（Maven 3.9.16）；**勿用系统 `mvn`**。
- **验证第一入口**：`scripts/gate.sh pre|full|focused|selftest`。不要先手拼会吞汇总的 Maven 命令。
- `full` = 全仓 `./mvnw -B -ntp verify`（Spotless → Checkstyle → SpotBugs → Surefire）；只在阶段边界/关账或任务明确要求时跑。
- `focused` = 指定模块/测试 + `-am`；结论只能写 `FOCUSED TEST PASSED (NOT a full gate)`，不得冒充 full。
- **禁止**用 `-Dskip*`、`-q`、`-N`、`-pl` 不带 `-am` 等方式制造假绿；gate 会拒绝削弱门禁的参数/配置。
- `./mvnw test` 不跑 SpotBugs；关账前必须 `verify` 或单独 `spotbugs:check`，并重新核对测试计数。
- **run-bound 证据**：必须引用 `.work/gate/<run_id>/summary.txt` 与 manifest；没有 summary、报告不能绑定本轮，或出现 0 tests/failures/errors，写 `EVIDENCE_INCOMPLETE`/`RED_*`，不写 PASS。
- **mtime 只辅助**；以本轮 Surefire XML + Maven `Tests run:` 汇总交叉核对为准；源码测试类数不等于用例数。
- **SpotBugs**：怀疑增量产物不一致时再强制重编（删 `target/classes` / `target/test-classes`，不默认 `clean`）；确认日志有 6 个模块的 `BugInstance size is 0` 才算 full 通过。
- **变异自证**：绝对路径、记 md5、trap/finally 还原、结束 `git status` 自证；变异窗口独占 checkout 或副本。
- **真模型优先**：行为验收默认真进程/真模型/HTTP/日志/事件库三方互证；假 LLM 只用于真模型造不出的确定性场景。
- 详细命令、历史事故、变异/时钟陷阱见 `docs/agents/VERIFICATION.md`。

---

## 四、文档与设计权威

1. **用户给定设计 = 最终版本**；实现困难/更优设计/时间不够都不能静默改口径，需用户明示后更新文档。
2. 冲突时以**最新已落盘文档**为准；更早的 plan/status 自动降为历史；旧文档不回头改，用追加“已被 X 取代”标注。
3. **机制性描述回代码核**：计划里说“唯一写口/某字段=某 revision”等，使用前先确认代码；发现不符先报告。
4. 允许调查提出设计修改，但必须给依赖/调用图、影响面、迁移/回退、与红线关系，由控制方交用户裁定。
5. 新设计放 `设计-*.md` 或 `docs/`；不要只写在聊天、代码注释或子 Agent 任务书里。
6. 协议消息/数据优先 `record` + `sealed interface` + 穷尽 switch；公开 API 用中文 Javadoc，解释“为什么”。
7. 实时版本/测试/Pages 一律看 `docs/agents/STATUS.md`；AGENTS 不抄当前数字。
8. 冻结决策表在 `开发计划.md §1`；变更决策按第 5 条先落文档。

---

## 五、提交、发布与密钥

1. **不 `git add -A`**；按关注点分批提交，提交前扫 `git status` 与暂存 diff；提交信息用中文，写清改动、原因、实际验证数字和未验证项。
2. **不擅自 push / force push**；是否推送以用户当次指令为准。
3. 不提交 `.omo/`、`.work/`、`target/`、含密钥的配置；`target/` 默认不入库，**不再 force-add 旧 0.1.0 jar**。
4. 密钥值绝不进日志/异常/事件/argv/env/stdio/会话输出；脱敏按结构（`keys`/`credentials`/`secrets` 子树整体替换），先脱敏再打印；发现泄露立即报告并建议轮换。
5. 发布前看 `docs/agents/STATUS.md` 与 `.github/workflows/publish-maven.yml`；当前 `verify → deploy` 顺序和 deploy 继续跑测试的行为按用户 2026-10-17 裁定**不修改**。
6. 公开 API 兼容基线 0.2.2；改 AgentLib 公开 API/包结构后按 §0.5 做消费方检查，类数下限不能替代签名/行为验证。
7. 不篡改历史留痕（`docs/archive/**`、`.omo/**` 历史台账、git 历史）；更正用追加标注。已确认的小修若比描述短且不触架构/红线，当场修；否则先走设计文档。

---

## 六、本机环境（常驻核心）

- JDK 21 / Maven Wrapper 3.9.16；`java -version` 与 `./mvnw -v` 都应对。
- 本机约 1.6 GB 内存；Maven/Surefire 串行，`clean verify` 前台/受管跑，被内存守卫杀掉不算通过。
- `~/.m2` 每台机器各自；判重建看类数/消费方测试，不看时间戳。
- 不要假定 EOL；先看 `git status`、`git ls-files --eol`、`core.autocrlf`。EOL 错误会让行锚定搜索假阴性。
- 本机 GNU `grep/find`；换 shell 可能是 ugrep/bfs。报 0 命中前先造正样本自证工具会命中。
- 真进程/真模型脚本会联网/花钱，用独立 `--data-dir`；临时数据、日志、SQLite 放 `.work/` 或任务目录。
- 详细陷阱见 `docs/agents/ENVIRONMENT.md`。

---

## 七、结束前自查与停止条件

- 没跑过的，**不许写成通过**；报告必须有真实命令、退出码、关键输出、改了哪些文件、未验证/blocker。
- 变异体必须还原并自证 `git status` 与开工一致；旧 Surefire 报告不得混入本轮。
- 计划里的 Expected/数字不是事实；与实测不符先核公式/夹具，不改 Expected。
- “0 命中/不存在”前先自证工具/pathspec/行尾/引号；检查文档机制描述是否过期。
- 幻影判别力、放宽断言、把历史数字当现状，都是假绿；发现后先停手报告。
- 无法完成验收判据时，任务状态是 **BLOCKED，不是 DONE**。

---

## 八、外部跟踪

见 §0.5：MCP Java SDK 3.0 评估、AG-UI 1.0 Draft、BOM 季度复核、`~/SimulatorMosire` 消费契约。
