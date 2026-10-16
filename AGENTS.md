# AGENTS.md —— ProjectMosire 常驻上下文 + 多 Agent 共用工作树的规矩

> ★ **本文件是 ProjectMosire 唯一的 workspace instruction 文件。** 2026-10-07 实测仓内只有
> `./AGENTS.md`；若任何分支、工具或历史文档里出现 `CLAUDE.md` / `AGENT.md`，一律**读作本文件**：
> 其中仍有价值的内容**迁移进本文件**，旧文件只留删除记录或改成指向本文件的一行指针；
> 不要在多个文件名下各维护一份。`git` 历史与 `.omo/evidence/**` 里的旧名属于留痕，**不回头改**。
>
> ★ **冲突处置**：用户当次明确指令 > 本文件 > harness / skill 模板。若 harness 的通用流程、
> `AGENTS.md` 与用户当次原话冲突，先回头对用户指令，再执行，并如实回报冲突点。
>
> ★ **参考来源**：通用协作、验证与派单纪律参考隔壁 `~/SimulatorMosire/AGENTS.md`（当前版）；
> 只吸收对本仓成立的部分，**不照抄 SimulatorMosire 的业务规则**（模块名、经济线、世界数据等一概不适用）。
>
> ★ 下文每条规矩尽量附**为什么**——它们来自真实的返工、事故或用户裁定，不是洁癖。

---

## 〇、这是什么 / 模块边界 / 红线（改代码前先读）

**ProjectMosire** 是一个 Java 21 + Maven 多模块、轻量化、可自管理的 Agent 软件：以主 Agent（Brain）为核心，
可自由创建、销毁多个权限各异的子 Agent；对外用 **MCP**（工具/数据）、**A2A**（Agent 间互操作）、
**AG-UI**（用户界面）通信；对内用统一事件存储 + 权限/身份 + 配置管理状态；且**运行时自身**
（配置、skills、子 Agent）可被运行中的 Agent 管理。

### 0.1 模块边界（不可违反）

```text
MainMosire ──→ BrainMosire ──→ AgentLibMosire
（入口/网关）   （Agent 运行时）   （通用工具包）
                                     ▲
BashPluginMosire ────────────────────┘   （PF4J 插件：main 只依赖 AgentLib；宿主不 import 它）
```

| 模块 | artifactId | 包前缀 | 允许依赖 / 要点 |
|---|---|---|---|
| `AgentLibMosire` | `agentlib-mosire` | `io.mosire.agentlib.*` | 通用 LLM 工具包/接口/协议（llm client、AgentTool、MCP 适配、权限模型、事件存储、配置、进程管理）。★ **2026-10-17 边界改写（用户裁定）**：原口径「**无 Agent 假设**」已**不再成立**——按用户裁定「AgentConfig 整个下沉」与「窗口的结构编排和基础运行本身作为完全可复用的项目下沉到 AgentLib」，本库现含 **Agent 描述符**（`io.mosire.agentlib.agent.AgentConfig`，10 组件含 maxTurns/timeBudget/quotaMaxTokens/工具白黑名单）与**上下文窗口的结构与基础操作**（`io.mosire.agentlib.context.{ContextWindow,ContextComposer,LlmRoundTrip}`；📄 `设计-上下文窗口下沉.md`）。
新口径：**无运行流假设**——库**不含循环、编排、终止结算、压缩/技能/记忆的具体实现**（这些留在宿主 Brain）；可被其他程序单独依赖；**不得 import** `io.mosire.brain.*` / `io.mosire.main.*` |
| `BrainMosire` | `brain-mosire` | `io.mosire.brain.*` | 具体对话、工具载入、上下文构造/压缩、子 Agent 编排、skills、记忆。只依赖 `agentlib-mosire`（+ Jackson/SLF4J）；不得依赖 `MainMosire` |
| `MainMosire` | `main-mosire` | `io.mosire.main.*` | 进程组装、CLI、网关（AdminREST/A2A/AG-UI/MCP）、健康检查、发行。**不含 Agent 行为逻辑**；主代码不得 import `io.mosire.bash.*` |
| `BashPluginMosire` | `bash-plugin-mosire` | `io.mosire.bash.*` | PF4J 插件。**main 只依赖 `AgentLibMosire`**（PF4J 为 provided）；测试面可再依赖 Brain（test scope）。宿主 main 代码不得 import/使用它（Main 的 provided 依赖只服务构建顺序，见下） |

- **groupId**：`io.mosire`。
- `MainMosire/pom.xml` 对 `bash-plugin-mosire` 有一条 **provided** 依赖，但它**不是宿主对插件的编译依赖**：
  只服务 reactor 构建顺序 + `maven-dependency-plugin` 把插件 JAR 拷到 `target/plugins/`；宿主代码零 bash 类引用，
  shade 必须排除它。硬规则可验证：打包后 `unzip -l MainMosire/target/mosire.jar | grep -i bash` 必须 **0 命中**。
- 依赖方向由**两层**检查：Maven 层（pom 依赖 + enforcer）和 import 层（源码静态审计）。
  **注意用 `^import` 精确匹配**，不要用裸 `grep -rl`——设计注释里会出现 `io.mosire.brain.*` 这样的说明文字，
  会造成“假阳性 = 边界已破”的误判。建议命令：

  ```bash
  grep -rnE '^import io\.mosire\.(brain|main)' AgentLibMosire/src || echo "OK: AgentLib 无下游 import"
  grep -rnE '^import io\.mosire\.main' BrainMosire/src || echo "OK: Brain 无 Main import"
  grep -rnE '^import io\.mosire\.(brain|main)' BashPluginMosire/src/main || echo "OK: BashPlugin 无 Brain/Main import"
  grep -rnE '^import io\.mosire\.bash' BrainMosire/src/main MainMosire/src/main || echo "OK: 宿主 main 不 import bash 插件"
  ```

### 0.2 红线（任何模块、任何批次不可违反）

1. **权限单调性**：Agent 不能授予自己/子 Agent 超过自身的权限；spawn 与配置变更必须经过
   `brain.subagent` 的 `PermissionMonotonicityGuard`。任何“先放宽再在别处补检查”的写法都不算合规。
2. **自升级禁令**：Agent 可以管理配置/skills/子 Agent，**不能替换自身二进制**（无自更新工具；发行走外部流程）。
3. **MCP 新代码禁用**（2026-07-28 弃用清单）：Roots、Sampling、Logging、legacy HTTP+SSE。
4. **信任边界 = 进程边界**：子 Agent = 同 jar stdio 子进程；进程内“逻辑隔离”只放自己审过的插件。
5. **工具不能关进程**：任何工具不得结束进程；进程生命周期归 `SubprocessManager` / `Main` 管理。
6. **密钥纪律**：密钥值绝不进日志/异常/事件/argv/env/stdio/会话输出；读配置只打印路径 + 长度。
7. **架构决策先落文档再改代码**：见 §五；文档没落盘就先别写实现代码。

### 0.3 Skill 运行时（2026-10-07 接入的新能力）

> 设计全文与已知取舍：`设计-Skill运行时能力.md`。改这一块前先读它；本文只记不可破坏的纪律。

- **唯一执行面**：`io.mosire.brain.skills.SkillAccess`。列表/检索/读正文/读资源/创建/修改都在这里收口；
  工具面是 6 个 `AgentTool`（`skill_list` / `skill_search` / `skill_load` / `skill_read_resource` /
  `skill_create` / `skill_edit`，实现在 `SkillTools`）。生产路径不要绕开 `SkillAccess` 直读写技能文件。
- **两个权限命名空间**：`skills` = 可见面；`skills-edit` = 可编辑面。
  前缀语义既能表达“一个技能文件夹子树”，也能表达“单个 `SKILL.md`”。
- **三档语义（fail-closed）**：未表态 = 什么都看不见/改不了；显式 `none()` = 表态为空；
  显式 `unlimited()` / 前缀集 = 可见/可编辑。**不存在“缺省静默放大”**。
- **逐层只减不增**：`effectiveVisible = callerVisible ∩ template ∩ request`；
  `effectiveEditable = (callerEditable ∩ template ∩ request) ∩ effectiveVisible`。
  模板越界静默求交；**请求越界响亮拒绝**（`SkillScopeDeniedException` / `SKILL_SCOPE_DENIED`）。
- **硬安全边界的两半**：
  1. **技能工具层**：没有可见面列不出/搜不到/读不到——★ **2026-10-13 更正（原文"不存在的技能与不可见的技能返回同一个 `SKILL_NOT_FOUND`"与代码不符）**：**范围外（含未表态）⇒ `RESOURCE_DENIED`**（`SkillAccess.requireRead` → `ToolCallAuthorizer` 转码），**面内不存在 ⇒ `SKILL_NOT_FOUND`**。安全性同效：**不可达的名字**与**隐藏技能**得到同一条 `RESOURCE_DENIED`，无存在性 oracle。没有可编辑面建不了/改不了。
  2. **bash / 文件工具一侧**：生产装配必须把技能根经 `ToolContext.config` 下发的
     `sandbox.maskedPaths` 交给 `SandboxMask`，再由 `BashSandbox.wrap(...)` 对可见者**只读 bind**、
     对不可见者用空目录**遮蔽**。没接线这条缝的装配（外部 MCP 暴露面、手工拼的 `ToolContext`、
     离线夹具）**不享受文件工具侧的保证**，行为与本能力引入前相同。
- **已知缺口（必须如实记，不许说成已解决）**：
  - 主 Agent 缺省不沙箱，可以绕过 frontmatter 校验直接改技能文件（它对技能有完整权限，不是越权，但绕过校验）；
  - 外部 MCP 面尚未接线，其 bash 也不带遮蔽面；
  - 非 root 部署下 L3 遮蔽的真跑未验证（受限调用者的 bash 本来就会 `SANDBOX_UNAVAILABLE`）；
  - 新增文件工具必须自行消费 `SandboxMask` / `ResourceAuthorizer` 的资源 SPI，否则不受这条边界保护。
- **技能目录是扁平的**：`<skillsDir>/<name>/SKILL.md`，不递归；不支持删除技能；不做 embedding/语义检索。
- **工程规矩**：技能范围变化、工具面变化、上下文预注入变化，都属于“改架构”——先改设计文档，再改代码；
  新测试要覆盖正向 + 负向（越权不可见、越界 spawn 被拒、无沙箱 fail-closed）。

### 0.4 文档地图（不要用 `@` 导入——会把文件展开进上下文，白载几千行）

| 文档 | 内容 / 现状 |
|---|---|
| `开发计划.md` | **现行计划**（2026-09-14 重写版）；§五“纪律”逐条有效 |
| `设计-*.md`（仓库根） | **现行设计文档**：身份与血缘 / 子 Agent 终局 / 子 Agent 上下文 / Bash 围栏 / 审批与工具内权限 / 多 provider 路由 / 插件系统 / 配置引导 / MCP HTTP 传输 / Skill 运行时 / **决策包作为唯一决策单元** / **上下文窗口下沉**（`设计-上下文窗口下沉.md`：AgentLib 收 `ContextWindow`/`ContextComposer`/`LlmRoundTrip`/`AgentConfig`，分层机制与压缩线留 Brain） |
| `Research/调查报告.md` | 2026-09-08 调查结论（协议选型、架构参考、技术栈、风险）；**不是**开发计划 |
| `README.md` | 项目介绍；其“里程碑状态”是历史快照，数字可能陈旧，**不作实时状态依据** |
| `docs/archive/2026-09-14-旧计划/` | 已归档、**不再修改**的旧计划；Java 注释里对它们的文字引用不逐处改写（引的是历史决定出处） |
| `.omo/plans/`、`.omo/drafts/`、`.omo/evidence/` | 本机 SDD/计划/证据目录（在 `.gitignore` 内，不入库）。跨会话/跨机器要复用的结论必须回写进仓库内文档或设计稿，不能只留在聊天或 `.omo/` |
| `git log --oneline -20`、各模块 `target/surefire-reports/` | “当前真实状态”的最终依据；数字以本轮 `verify` 报告为准，不用旧表推 |

> 历史文档里的 `.superpowers/...` 路径若在本仓不存在，按历史留痕处理，不要据此判断当前状态。

### 0.5 外部跟踪项（改代码前先查）

- **MCP Java SDK 3.0**（2026-07-28 规范）发布即评估升级；当前锁 `2.0.1`，该版本无 server/discover/header 路由。
- **AG-UI 1.0 仍 Draft**（事件模型可能再变）；A2A server 用官方 spec + `jsonrpc-common` 纯模块自写绑定
  （不用 server-common/CDI）。
- **BOM 版本季度复核**；2026-09-08 快照见根 `pom.xml` properties。
- **下游消费方 `~/SimulatorMosire` 依赖本仓 `AgentLibMosire`**：改 AgentLib 的公开 API/包名/类集合后，
  必须在本机重建并在消费方侧验证：
  `./mvnw -pl AgentLibMosire -am install`，再到 `~/SimulatorMosire` 跑
  `./mvnw -pl simos-core -am -Dtest=AgentLibAvailabilityTest -Dsurefire.failIfNoSpecifiedTests=false test`
  （消费方钉住 13 个类和 JAR 类数下限；公开类集合只增不减，被钉住的类不得改名/挪包）。

---

## 一、并发与工作树：这是最容易造成返工的一类

1. ★ **同一个 checkout 一次只能跑一个 Maven。** 本机 1.6 GB 内存，Maven JVM（`.mvn/jvm.config` 256m）
   与 Surefire fork（288m 堆 + 160m 元空间）并行会互相抢内存；两轮 Maven 同抢 `target/` 会让测试计数失真。
   开工/收工前先确认本 checkout 没有 Maven 在跑：

   ```bash
   pgrep -af 'classworlds.*ProjectMosire|ProjectMosire.*classworlds|surefirebooter'
   ```

   ★ **作用域 = 本 checkout**：别的仓库/别的项目在跑 Maven 不阻塞本仓。判断时看进程命令行/cwd 是否落在
   `/home/cna/ProjectMosire`，**不要用裸 `pgrep` 把隔壁项目也算进来**。

   ★★ **"等 Maven 空闲"不能这样写**（2026-10-16 实测事故，两个 Agent 互堵）：
   `while pgrep -f "classworlds.*ProjectMosire|surefirebooter" >/dev/null; do sleep 20; done`
   **会匹配执行它的那个 shell 自己的命令行**（命令行里就含这串字面量）⇒ 条件永远为真、空转不退出；若它同时持着
   `flock`，等锁的其它 Agent 会被一起堵死。正确写法二选一：① 只匹配 java 进程 ——
   `pgrep -a java | grep -q classworlds`；② 括号技巧 —— `pgrep -f "[c]lassworlds.*ProjectMosire|[s]urefirebooter"`
   （自己命令行里不再含可匹配字面量）。串行化的标准写法：
   `flock -w <秒> .work/maven.lock -c '<命令段>'`；★ 注意 `flock -c` **没有 `set -e`**，外层 `EXIT=$?` 只反映
   **最后一条**命令——多命令段要么逐条留退出码，要么显式 `set -e`。
2. ★ **一个文件一个 owner。** 动手前先 `git status --porcelain`，再用
   `find <模块>/src -mmin -3`（或等价方式）看有没有人正在写；看到**半成品尾巴**（例如 import 了刚被删的类
   ⇒ 编译不过）**不要接手**——那通常不是坏尾，是另一个 Agent 的中间态。先报告控制方。
3. ★ **派单必须写清“谁拥有哪些文件”。** 跨 Agent 改同一文件 = 互相覆盖。
4. ★ **不要在同一个 checkout 跑 Maven 的窗口里改 Java。** 编译/测试的计数会不可信；增量编译还会出现
   “源文件比 class 新却不重编”的假象——改完生产代码若怀疑增量，先
   `rm -rf <模块>/target/classes <模块>/target/test-classes` 再跑测试。
5. ★ **产物纪律：别把正在跑的服务的 jar 覆盖掉。** Java classloader 是惰性加载：运行中被就地重写的 jar，
   之后用到某个类时可能 `ClassNotFoundException`。服务在跑时验代码用 `./mvnw test`（不经过
   `package`/shade、不动 jar）；必须打包时先停服务，或把新 jar 打到别处、用别的数据目录和端口起新实例。
   **收工用 PID/进程组，别用 `pkill -f`**（命令行里含 jar 路径/端口时，`pkill -f` 会连执行它的 shell 一起杀）。

---

## 二、派单与多 Agent 协作

> 目标：控制方（主 Agent）负责调查综合、约束设计、派单、审阅、验收、提交；实现交给子 Agent。
> 这是默认路径；控制方亲自写生产代码只应是**用户明确要求**或**单点小修**，且要在报告里说明为什么没有派单。

### 2.1 两类子 Agent

1. **调查子 Agent（只读）**：可以读全项目，但针对特定功能时必须带**方向与问题清单**；
   禁止“把全项目查一遍”这种无方向侦察。
2. **办事子 Agent**：只给目标与边界，不给逐文件施工图；一个 Agent 负责**一个责任区**的全部生产代码。

### 2.2 责任区与派单粒度

- **责任区** = 一个完整、可编译、可独立验收的目标/问题域（例：“Skill 可见面接线”“审批 HTTP 面提炼”），
  **不是计划编号、不是文件数、不是 Task 3.2**。切法按“可独立编译的层”，不按计划编号逐条拆。
- ★ **粒度基准（2026-10-13 从隔壁 `docs/agents/02-一-并发.md` §一.5 补回——本条在本仓此前被漏抄）**：
  以**开发计划里的一节**为单位；**阶段内部不再逐任务派单**——同一个代理在一个上下文里写完，
  才看得见各任务之间的耦合（跨任务的边界、唯一写口、分工都是**跨任务**才看得清）。
- ★ **例外条款（同上补回）**：阶段**明显过大**（一个代理一轮写不完）时可以拆，但**按“可独立编译的层”拆**
  （如“钱这一层”“关系这一层”），**不是**按计划编号逐条拆；且**每层仍然只写代码、不写测试**。
- **一个责任区 = 一个写代码 Agent**；**测什么最后统一派测试 Agent**，不要让每个代码 Agent 写一段测试就验一段。
- **同一时刻只允许一个写代码的办事 Agent**；只读调查可以并行，但必须互不共享写目标。
- **compile 只是进入下一个责任区的门槛，不是完成标准**：最终验收按 §四（真实行为证据 + 负向验证）。

### 2.3 写代码 Agent 的任务书必须写死的三条

1. **只写生产代码，写到“编译过”**：`./mvnw -DskipTests compile`；
   **不要写/改任何测试文件**；**不要跑 `test` / `verify` / `package`**（测试阶段不归它）。
2. **不 `git commit`**（控制方审后提交）。
3. 报告里必须点名：哪些改动会**改变数值/行为**、受影响的**硬编码字面量清单**、以及会让**既有断言失效**的清单
   （给测试 Agent 当输入）。

### 2.4 测试 Agent 的任务书必须写死的三条

1. 按**计划的验收判据清单**逐条落实，**不是**按代码反推该测什么；必须含负向用例
   （权限不能放大、拒绝语义成立、数据往返成立、绕过路径确实被挡住）。
2. 关键项要**变异自证**：改坏 ⇒ 当场红 ⇒ 还原 ⇒ 比 md5；做不到的如实说“等价存活”，不许编红点。
3. 报告必须有**“我没做 / 没验证的”**一节。

### 2.5 架构实现的文档门槛（先落盘，再派单）

- 凡涉及以下任一项，派实现子 Agent 前必须有一份**可阅读、可验证、逻辑完全的架构设计文档**落盘：
  新增/修改持久状态组件；新增/修改运行时阶段、结算器、时间参与者；新增/修改对外 API、
  GM/工具面、载荷/事件形状；改数据流/所有权/生命周期；改数值公式或硬编码标定值。
  ——纯重命名、单点 bugfix、纯文案/格式化可以只写简短说明。
- 设计文档必须包含：需求来源 / **用户原话** / 目标 / 非目标；现状事实（`file:line` 已确认）；
  外部接口与载荷形状；数据流与所有权边界；不变量清单；验收判据 + 负向用例清单；文件所有权。
- **派单书必须自包含**：引用设计文档路径与具体小节/条目号，并把关键形状**原样复述**给子 Agent；
  不能只给链接、不能只说“读计划”。凡用于派实现子 Agent 的文档，必须有「用户原话附录」，
  逐条**原样照录**该主题下用户的全部相关原话（含日期），不改写、不缩写、不混入自己的总结。
- **子 Agent 可以挑战设计**：认为设计与用户原话/红线冲突、或有歧义 ⇒ **立即停手上报**，
  控制方必须转问用户，得到裁定后才继续；纯技术分歧 ⇒ 控制方当场裁定或更新设计文档（更新要落盘）；
  纯实现困难 ⇒ **不停手**：给最强 fail-closed 方案并实现，任务保持 **BLOCKED（不是 DONE）**。
- **控制方不得代替用户裁定设计**：文档里要分清哪些是**用户裁定**、哪些是**控制方的实现建议**；
  后者随时可被用户推翻。引用“用户说过”的规矩必须能指出原话与日期，**自造的规矩不许冒充用户裁定**。

### 2.6 评审与等待

- **评审按风险触发 + 并行**（本仓 2026-09-14 用户裁决）：命中判定/权限、进程线程生命周期、共享件、
  结构性 → 判定性降级才派独立评审；命中即**先提交后评审**，与下一块并行。
- **评审体量不得压过代码本身**：单模块开发任务的评审不超过 3 轮（数“发现问题 / 判是否可关账”的独立派发）；
  第 3 轮仍不收敛，由控制方当场裁定，未决项记成带裁定的遗留条目。代码量小时，控制方自己读 diff 就是评审；
  不要为评审自建重型装置（评审包 / 限域重审 / 多轮取证表格）。
- **后台子代理等待（仅 DeepseekHarness 适用）**：若 harness 会在后台子代理运行期间持续注入 goal 续轮、
  且长 `sleep` 会被工具拒，则控制方每次 `sleep 120`（2 分钟）醒来看一次，不要一次睡几分钟；
  例外是等待期间有别的子 Agent 已落定的报告要审。换 harness 前先核实这套机制是否存在，别照抄。
- **只有一个子代理要跑时，优先前台派单**（一次调用等到落定），不要后台 + 轮询。

---

## 三、构建与门禁

- **工具链**：JDK 21（`--release 21`）+ 仓库内 Maven Wrapper `./mvnw`（锁定 3.9.16）。
  **勿用系统 `mvn`**：apt 的 maven 3.8.7 不满足 SpotBugs 4.10.4.1 的 Maven ≥3.8.9 要求。
- **门禁三件套 + 测试**：`./mvnw verify`
  = Spotless（google-java-format）→ Checkstyle（`config/checkstyle.xml`）→
  SpotBugs（**实际配置 `effort=More` + `threshold=Low`**；计划曾写 Max，但 Max 与庞大 aux classpath
  组合会爆炸到 >25 分钟不终，More 保留深度数据流分析，见根 pom 注释）→ Surefire（JUnit 6）。
- **本轮真数（2026-10-07，`./mvnw verify`，全仓）**：AgentLib **518** / Brain **334** /
  Bash **104**（6 skipped，为需 root 的沙箱真跑）/ Main **176** ⇒
  **1132 tests, 0 failures, 0 errors, 6 skipped**；4 个模块 SpotBugs 均 `BugInstance size is 0`。
  ★ 后续以新一轮 `verify` 为准，旧数字自动降为历史。
- **改完代码先 `./mvnw spotless:apply` 再 `./mvnw verify`**，避免格式门禁来回。
- ★★ **判定 SpotBugs 结论前，先确证编译产物是新的**（2026-10-16 实测，吃掉四轮返工）：
  增量编译留下的旧 class 会让 SpotBugs 反复报**同一个已经不存在的**告警（当晚 `CommitReceipt` 连报三次幽灵
  `EI_EXPOSE_REP`，中途还反过来报"抑制多余"，差点把真正需要的那条抑制删掉）。
  正确姿势：`rm -rf <模块>/target/classes` → `./mvnw -o -q -pl <模块> -am -DskipTests compile` →
  `./mvnw -o -pl <模块> -am spotbugs:check`。**只跑 `spotbugs:check` 不重编 = 在读旧快照**。
- ★★ **变异自证必须在"不与他人并发"的窗口里做**（同日晚实测）：测试 Agent 做变异（改坏生产文件→跑→还原）时，
  控制方若同时跑全量 `verify`，编译会把**未还原的变异体**编进去 ⇒ 得到一条**假红**（当晚 `PendingDecisionQueueTest`
  的 `[本轮只建了一个包] Expected size: 1 but was: 0` 就是变异体，不是回归）。
  规矩：变异期要么独占 checkout（别人的 Maven 一律让路），要么在**副本**里做；看到"莫名其妙的红"先查
  `git diff` 与文件 mtime，**先怀疑在自己脚下的变异体**，别急着改代码。
  ★★ **变异脚本必须用绝对路径 + 还原护栏**（2026-10-16 P3e 实测事故）：一个变异脚本因**相对路径 + 函数内
  `cd` 漂移**，把变异体写进了**源 checkout**（`CrossProcessDecisionGateway` 的 fail-closed 回退被摘掉）
  并驻留约 2 分钟——期间任何编译/取证都会读到变异体。规矩：① 变异文件一律**绝对路径**寻址；
  ② 每个变异体**先记 md5**，还原后**复核 md5**；③ 脚本用 `trap`/`finally` 保证还原；
  ④ 全部做完用 `git status --porcelain` + `git diff --stat` **自证源树与开工前一致**；
  ⑤ 报告给一张**变异表**（目标类 / 改了什么 / 哪条断言红 / md5 首尾值）。
  ★ **同源陷阱：用 `cp`/`shutil.copy2` 还原会带回旧 mtime** ⇒ Maven 增量判"没变"不重编 ⇒
  你以为在测变异体，其实跑的还是上一轮的 class（P3c 实测：M19 红在了错的断言上）。还原后必须
  `touch`/`utime` 更新时间戳，并在每轮日志里确认出现 `Compiling`。
- **勿加 `-N`**：`-N` 只构建根聚合器，对模块源码是空操作，`spotless:check` 会假绿；
  实测：模块里放明显违规文件，`./mvnw -N spotless:check` 退出 0，`./mvnw spotless:check` 正确报违规。
- **全仓 verify 才是完整门禁**；用 `-pl <模块>` 时必须带 `-am`（否则可能用 `~/.m2` 的旧 SNAPSHOT，
  报“找不到包”的**假红**；也可能只跑了部分模块的 Spotless/Checkstyle/SpotBugs）。
  `spotless:apply` 跑完后，用 `git diff --name-only` **列全**所有被改文件，不要只按一个模块路径 `git add`。
- **`./mvnw test` 不跑 SpotBugs**：关账前必须跑 `verify`（或单独 `spotbugs:check`），并重新核对测试计数。
- **聚焦单测**：

  ```bash
  ./mvnw -q -Dtest='ClassA,ClassB' -Dsurefire.failIfNoSpecifiedTests=false test -pl BrainMosire -am
  ```

  ★ `-Dtest='A+B'` 是 JUnit 5 的 tag 表达式、**不是“或”**：不匹配任何类时 Surefire 一条不跑且退出 0。
  要“或”用**逗号**。
- **别动辄全量测试（D29）**：改动只跑相关单条用例；全量 `clean verify` 留给阶段边界/关账。
  存量离线用例是**回归钉**，默认保留；“老架构老测试，新架构新测试”；被改架构相关的旧测试可以迁移/删除，
  但必须说明原因，**不得为了变绿弱化断言、删除无关测试**。
- **验证一律先上真模型**（2026-09-13 用户裁决）：功能的“能不能用 / 对不对”默认在使用模式里判——
  真进程 + 真模型 + HTTP 对话/审批，以 HTTP 结果 + 日志 + 事件库**三方互证**。
  可用 `scripts/usage-test.sh`（真模型使用模式测试台，不进默认门禁）与 `scripts/smoke-live.sh`（真 LLM 冒烟，
  联网/花钱）作为操作范例；离线验收可用 `scripts/smoke.sh`（假脚本，不触网）。
  假 LLM 装置是**例外**：只有真模型造不出的确定性（精确帧序列、超时/重试、离线复现）才写，且写明理由；
  **不要新增“为了进离线门禁”而造的假 LLM 装置**。
- **下游消费方验证**：改 `AgentLibMosire` 公开 API/包结构后，按 §0.5 跑消费方检查，不能只在本仓绿。

---

## 四、验证与证据：不许把“没跑”写成“通过”

1. ★ **不信“rc=0 就是过了”。** `-q` 会吞掉 Surefire 汇总；类名写错、测试被跳过也会 rc=0。
   要**看 Surefire 报告**（`*/target/surefire-reports/*.txt` 或 `[INFO] Tests run:` 汇总）并核对
   **报告 mtime 落在本轮**。报“测试数”之前先清掉旧报告（或在 `clean verify` 里跑），否则会读到两轮并集。
2. ★ **管道退出码陷阱**：`cmd | tail; echo "[exit=$?]"` 里 `$?` 是 `tail` 的退出码，不是 `cmd` 的。
   要么不接管道，要么显式取 `${PIPESTATUS[0]}`。
3. ★ **新护栏/新判据要有判别力**：临时把被测逻辑改坏（变异体）⇒ 必须**当场红**，然后还原。
   变异体按**目标类名**推入，不要靠“按变异文件名拷贝”把红变成编译错误；还原后比 md5。
   做不到结构性表达的，如实说“等价存活”，不许编红点。
4. ★ **计划里的 Expected / 预测数字不是事实**：实测不符时，先核公式、先查被测物、先看夹具，
   **不许把 Expected 改成实际值**（那会让用例退化成同义反复）。
5. ★ **报“0 命中 / 零活动”之前，先自证工具在该写法下会命中**：用已知会命中的样本跑同一命令；
   禁止用 `2>/dev/null` 吞工具错误。搜全仓优先 `git grep`，含未入库文件用 `git grep --untracked`。
   ★★ **实例（2026-10-17 “上下文窗口下沉”批，控制方在派单书里写错的）**：`git grep -n "…" -- '*/src/main'`
   这种 pathspec **恒不命中**（`*/src/main` 匹配不到 `src/main/java/...`，要写 `*/src/main/**` 或直接给目录）——
   命令 exit 1、零输出，看上去像“已核实为空”。控制方据此断言“生产侧只有 8 个文件引用 `AgentConfig`”，实测 **11 个**；
   子 Agent 用正样本拆穿后重做，差额来自**同包隐式引用**（`AgentPipeline` / `AgentRuntime` / `AgentSpec` 与它同包，
   压根不写 import）。⇒ 两条纪律：① **pathspec 与正则都要带正样本**（探针文件命中一次再报 0）；
   ② **“改 import 的文件数” ≠ “受影响文件数”**——**同包隐式引用**、**通配 import**（`import io.mosire.x.*;`）、
   **反射/字符串类名**、`META-INF/services` 都不在 import 扫描的覆盖范围内，**搬类/改包前必须逐类核查这四处**。
   ★ **第三个同类实例（2026-10-17 当场又踩一次）**：`git ls-files | grep 中文文件名` 也恒不命中——git 默认给非 ASCII 路径加引号
   （`core.quotePath=true`，输出形如 `"\350\256\276\350\256\241-…"`）。核实跟踪状态请用 `git ls-files -z | tr '\0' '\n'`、
   `git -c core.quotePath=false ls-files`，或直接看 `git status --porcelain`（只有未跟踪才出 `??`）。
   ⇒ 与上面两条合并成一句总纪律：**凡「没搜到」，先自问工具到底在搜什么**（pathspec 对不对、行尾锚定有没有被 CRLF 吃掉、路径有没有被引号转义）。
6. ★ **拒收判据与接收判据都要有真样本**；红要红在被保护的那行上；没红也要问为什么没红
   （可能测试根本没跑到：类名、tag、profile、`--details=none` 都会让汇总缺失）。
7. ★ **证据 = 真实命令 + 真实输出 + 退出码**，不是“应该能过”。报告至少写清：
   实际运行的命令、关键结果、改了哪些文件、哪些行为会变、哪些没验、遗留 blocker。
   凡没跑过的，不许写成通过；如果验收判据依赖它，任务就是 **BLOCKED，不是 DONE**。
8. ★ **“我验过了”与“我记得是这样”必须分开**：写给别人当依据的每个 Expected / 事实 / 出处都要有
   当场跑过的痕迹；不写没实测过的期望输出；不把工具的静默假阴性当作“不存在”；不把推导出来的风险当既成事实。
9. ★ 证据落点：任务指定的证据目录（本机常约定 `.omo/evidence/<topic>/`）；聊天记录**不算**留痕。
   跨会话要复用的结论回写进仓库内文档/设计稿。
10. ★★ **"按 surefire 报告求和"在测试改名/删除之后会虚高**（2026-10-16 P3d 轮实测）：
   `target/surefire-reports/` **不会被自动清理**——测试类改名后**旧报告仍留在盘上**，逐份求和会把新旧两份都算进去。
   当晚 `ChildCrossProcessDecisionGapE2ETest` 改名为 `ChildCrossProcessDecisionE2ETest`，旧报告（2 条）未清 ⇒
   控制方求和得 Main **203**、合计 **1470**，而当轮 Maven 自己的汇总与源码实际都是 Main **201**、合计 **1468**。
   ⇒ 规矩：**求和前先确认报告目录里没有"源码中已不存在的测试类"**（或先 `rm -rf <模块>/target/surefire-reports` 再跑），
   并与**当轮 Maven 的 `Tests run:` 汇总**交叉核对；**两法不一致时以当轮 Maven 汇总为准**——
   不要反过来拿求和值去"更正"子 Agent 报的数字（同一晚此前还有一次 1 条的同类差额，机制未逐一取证）。
11. ★★ **涉及时钟 / deadline / 超时的用例，禁止真实等待**（2026-10-17 用户指令「太变态了直接等30分钟，改！」）：
   决策包的 **deadline 同时是"投递后等人答复"的等待上限**（裁定 10：等待窗口 = 包自己的 `deadlineEpochMs`，
   不取 `approval.timeout()` 的较小者），而它一度是编译期常量
   （`DecisionPackages.DEFAULT_DEADLINE_MILLIS = 30 * 60 * 1000L`）⇒ **一条"没人答复"的用例会真的挂满 30 分钟**。
   规矩：① 被测物的时钟/窗口**必须可注入**（先例：`PendingDecisionQueue(proposerKey, caller, deadlineMillis)`，
   缺省仍是 30 分钟，行为不变）；② 用例一律注入**毫秒级窗口**，或调显式的
   `DecisionPackages.expire(long nowEpochMs)` 驱动到点；③ **禁止 `Thread.sleep` 等真实到点**
   （`atMost(...)` 的上限也不许超过 10s）；④ 报告里凡"验了到点路径"必须写明**怎么驱动的时钟**。
   ⇒ 反面教材：让一个用例等 30 分钟，既不是判据也不是耐心，而且会把子 Agent 卡死、让整批看起来"挺住了"。

---

## 五、文档与设计权威

1. ★ **用户给定的设计 = 最终版本，不得被颠覆**：任何 Agent 不得以“实现方便 / 更优设计 / 时间不够”为由
   改口径或静默绕过；需要改动时**必须先由用户在会话里明示**，再更新设计文档/架构原稿，新增条目而不是删除旧条目。
   用户原话若被写进派单/设计文档，必须原样照录。
2. ★ **文档时效权威：冲突时以最新、已落盘的文档为准。** 更早的 spec/plan/status 自动降为历史留痕；
   旧文档不回头改，由新文档追加“已被 `YYYY-MM-DD-xxx` 取代”。代码与最新文档冲突时，仍按第 3 条先核代码，
   再判定是实现未跟上、文档写错，还是口径已变。
3. ★ **机制性描述一律回代码核**：计划/台账里说“写口在 X”“读口必经 Y”“某字段 = 某 revision”这类机制描述，
   使用前先去代码确认；发现不符**报出来**，不要照着措辞硬做。
4. ★ **调查默认可以提出设计修改**：调查不是只读背诵旧边界，旧 enforcer、旧 AGENTS 禁令、旧“唯一写者”
   都可以被提出推翻；但推翻必须给出现有依赖/调用图证据、影响面、迁移/回退路径、与红线的关系，
   由控制方整理后交用户裁定，**不得由实现 Agent 自行静默改边界**。
5. ★ **架构决策先写文档再改代码**（本仓既有规矩）：每模块遵循 ARCHITECTURE.md 惯例时同步；
   新设计放 `设计-*.md` / `docs/`，不要只写在聊天、代码注释或子 Agent 任务书里。
6. **协议消息/数据用 record + sealed interface（封闭代数类型），穷尽 switch。**
7. **中文 Javadoc**：公开 API 必须；描述“为什么”优先于“是什么”。注释与文档用中文，与既有风格一致；
   折行交给 Spotless（google-java-format），不要手工调行宽。
8. **冻结决策表在 `开发计划.md §1`**；变更决策按第 5 条先落文档再改代码。

---

## 六、提交与协作

1. ★ **不 `git add -A`**；按**批次**提交（不同关注点分开，便于回退与审计），提交前扫 `git status` 与暂存 diff。
   跑了全仓工具（`spotless:apply` 等）之后，用 `git diff --name-only` **列全**再确认。
2. **提交信息用中文**，写清“改了什么 + 为什么 + 验收的实际数字 + 未验的部分”；本仓惯例是把诚实边界写进提交信息。
3. **不篡改历史留痕**：`docs/archive/**`、`.omo/**` 的历史台账行、git 历史中的旧设计不回头改；
   要更正就追加标注。代码注释里对归档文件名的引用不逐处改写。
4. **默认不擅自推送**（本仓既有纪律）：是否 push、推到哪个远端以用户当次指令为准；不 force push。
5. **已确认的发现，若修复比描述还短，当场修**，别 park 成 issue；但如果修复涉及架构/红线，先走 §2.5 文档门槛。
6. **密钥纪律**（同红线 6）：密钥值绝不进日志/异常/事件/argv/env/stdio/会话输出；读配置只打印路径 + 长度；
   真模型脚本只传数据目录，密钥由进程从 `<dataDir>/config.json` 自己读，**不复制进证据包/不打印**。
7. 不要把本机工具目录（`.omo/`、`.work/`、`target/`、含密钥的配置）提交进库；提交前确认 `.gitignore` 仍覆盖它们。
   ★ **唯一例外（2026-10-16 核实）**：`AgentLibMosire/target/agentlib-mosire-0.1.0-SNAPSHOT.jar` 是**刻意 force-track**
   的（`git add -f`；先例 `246f326` / `ca604f3` / `3ba02c4` / `52b58c0`）——给消费方一个仓内可取用的产物。
   ★ **刷新它的时机（2026-10-16 用户裁定更正）**：**不要逐批刷**。分两种情况——
   ① **同一台机器上的消费方**（本机 `~/SimulatorMosire`）：走 §0.5 的 `./mvnw -pl AgentLibMosire -am install`
   即可，**根本不需要这个 jar**，也就不必为它单独提交；
   ② **要刷新时**：留到**阶段边界 / 关账**（一批改动做完、`verify` 绿之后），或确有跨机器取用需求时。
   ⇒ 目的：不让"刷产物"变成每次改 AgentLib 都要多一笔提交与一次全量构建（用户 2026-10-16：
   「先把手头上的代码修改和开发计划做完再刷新包」）。**只改 javadoc 永不刷**（字节码不变）。除这一个文件外，`target/` 仍不入库。

---

## 七、本机环境与已知陷阱（换机器先核一遍）

- JDK 21 / Maven Wrapper 3.9.16；`java -version` 与 `./mvnw -v` 都应对。
- 本机内存约 1.6 GB：`.mvn/jvm.config` 固定 Maven 256m，Surefire fork 288m + 160m metaspace；
  **`clean verify` 必须前台/受管跑**，“被内存守卫杀掉”既不是红也不是绿，不能算过。
- `~/.m2` 是**每台机器各自的**：`agentlib-mosire` 要在本机重建一次才能被隔壁消费方用；
  判“重建成功与否”看类数/消费方测试，不看时间戳。
- 仓库用 `.gitattributes` 钉死 LF；换设备后先 `git status` 看 `core.autocrlf` 是否把工作树 checkout 成 CRLF
  （会导致 `mvnw` shebang 失效）。
  ★★ **本机现状是 CRLF，且它会咬行锚定的搜索**（2026-10-17 实测两次假阴性）：`core.autocrlf=true` + `.gitattributes` 只对
  `mvnw`/`*.sh`/`*.cmd` 钉 eol ⇒ **工作树里全部 `.java` 都是 CRLF、索引里是 LF**（`git ls-files --eol` = `i/lf w/crlf`）。
  后果：`grep -rl "^import X;$"` 这类**行尾锚定**一行都匹配不到（`;\r` 不匹配 `;$`）——当晚实测同一命令加 `$` 返回 **0 个文件**、
  去掉 `$` 返回 **26 个**，差点把「26 个文件待改」误判成「无事可做」。⇒ 行锚定一律写 `\r?$`、或先 `tr -d '\r'`、或用
  `git grep --cached`（索引是 LF）；**改文件时保持 CRLF**（别制造整文件重写的噪声），`spotless:apply` 不会转行尾。
- 本机 `grep`/`find` 在当前 shell 是 GNU 工具；换 shell/外壳可能是 ugrep/bfs（默认尊重 `.gitignore`、
  跳过隐藏目录、`-newermt` 语义不同）。跨外壳搜索前先 `type -a grep find`；报 0 命中前先自证工具会命中。
- 真进程/真模型脚本（`scripts/usage-test.sh`、`scripts/smoke-live.sh`）默认**不进**离线门禁，会真联网、真花钱；
  用独立 `--data-dir`，不要指向真实配置/事件库；端口用 0（自动分配）或明确的演示端口。
- `scripts/smoke.sh` 是离线冒烟，默认用假脚本；改动主流程后可作为回归入口之一。
- 所有临时数据、日志、SQLite 库放 `.work/` 或任务指定目录（已在 `.gitignore` 内），不要落到仓库受控路径。

---

## 八、最容易犯的错（结束时逐条自查）

1. **幻影判别力**：注释声称“X 测试守着这条”，而 X 根本不存在，或存在但改坏不会红。
   ⇒ 写“判别力由 X 承担”后，当场核实 X 存在且真的会红。
2. **放宽断言**：把精确断言降级成 `isNotNull` / 区间 / “不相等”。⇒ 能收紧就收紧；收不紧要写明理由。
3. **把计划数字当事实**：Expected 与实际不符时改 Expected。⇒ 先核公式与夹具；不动 Expected。
4. **0 命中直接下“不存在”结论**：正则写错、工具被 alias、被 `2>/dev/null` 吞错。⇒ 先造正样本自证工具。
5. **夹具违反新口径却去放宽护栏**：新护栏打红既有用例时，先判“护栏错”还是“夹具错”；多数是后者。
6. **统计窗口看错**：时点快照 / 本期发生额 / 历史累计不可并排比；报“某指标 = 0”前先核窗口。
7. **doc drift**：计划/台账的机制描述过期。⇒ 使用前回代码核；发现不符先报告。
8. **把“没跑”写成“通过”**：最贵的假绿。⇒ 报告必须有“我没做 / 没验证的”一节。

---

## 九、外部跟踪项

见 §0.5：MCP SDK 3.0 评估、AG-UI 1.0 Draft、根 pom BOM 季度复核、`AgentLibMosire` 的
`~/SimulatorMosire` 消费契约。改动相关面之前先复查那一节即可。
