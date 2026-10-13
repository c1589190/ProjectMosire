# 设计：工作区标准（workspace-mosire）与 Docs 系统（docs-mosire）

> **状态**：设计稿（2026-10-13 落盘），**待用户审**。本文审批通过前**不写实现代码**（本仓 §2.5 文档门槛）。
> **范围**：本期三项——① `agentlib-mosire` 收编成"只暴露通用权限接口"；② 新 artifact `workspace-mosire`（工作区标准：虚拟↔物理映射 + 能力 + 子工作区）；③ 新 artifact `docs-mosire`（Docs：`LOG` 追加型 + `FILE` 自实现 IO，**不挂 bash 入口**，N13）。**命令面 / 白名单 / `bash` 能力 / `shell-mosire` 抽核 → 后续独立模块**（§8 ⑤，草案在 §5.10）；技能重基（§8 ④）与其余默认工具重写（§8 ⑥）同样只登记。
> **口径优先级**：用户原话 > 本文 > 既有设计稿。本文与旧文档冲突时，以本文为准，并回头在旧文档加"已被本文取代"的标注（不删旧条目）。
> **姊妹文档**：`设计-Skill运行时能力.md`（技能面，本文期内**不改动**）、`设计-Bash工具与工作目录围栏.md`（bash 三层围栏，本文期内**不改动**；其代码归属在**后续批次 §8 ⑤** 迁入 `shell-mosire`）。
> **隔壁输入**：`~/SimulatorMosire/docs/superpowers/specs/2026-10-13-agentlib-docs-requirements.md`（Simos 侧需求书，263 行）。

---

## 0. 用户原话附录（逐条原样照录，不改写、不缩写）

### 0.1 本会话原话（2026-10-13）

| # | 原话 |
|---|---|
| N1 | 「Agentlib目前被编译为包给其他项目用，skill可以额外编译成另一个包和Agentlib在其他项目配合着用吗？」 |
| N2 | 「先别急，隔壁SimulatorMosire的Skill包我会让隔壁退役的，因为Skill/Docs查看系统必须由你这边维护。我的想法是，先继承Agentlilb，出一个标准的docs查看系统，允许设置专有目录、子Agent查看权限等等，然后额，Skills不就是特定的Docs吗？分析一下我的需求，隔壁的需求调查还在进行，你这边先进行进一步确认」 |
| N3 | 「2，但是目前先不做Skill的自动触发，只把Docs给做出来」（选项 2 = 一个引擎 + 两族工具名） |
| N4 | 「我想了一下，发现Agents.md还是别搞自动注入了，自动注入交给其他具体实现管，这是我的裁定，有问题我担着；你这边做一套通用的docs寻址/创建/修改系统就行，最好封装一个允许单个子Agent自己的文档工作区功能，子Agent不碰外部目录也能用这个Docs系统在自己的工作区里写东西；Docs权限系统也要做好，最好搞一个通用Docs工作区/Docs工作区赋权功能，允许创建自定义权限范围的工作区；算了，工作区功能额外写一个lib，作为Agentlib里工具的特殊鉴权标准，正好给一众工具维护的目录系统全部标准化托管了，然后再让Docs复写，怎么样？」 |
| N5 | 「Agentlib是Agentlib，只暴露一个通用权限接口，WorkspaceLib额外独立出来，提供一整套权限界定，Docs在前两者的基础上提供文件IO和文档读写工具！」 |
| N6 | 「停停停，bash插件为啥不能新增依赖？可以把老的bash插件给退役了，有了workspace之后，所有额外的默认工具都得重写，你想，纯bash和专为Docs设计的工具使用的文件IO流程能一样吗？」 |
| N7 | 「注意到Workspace的本质是物理目录-》虚拟目录的鉴权流程，因此应当拥有更大的可扩展自定义空间，懂我意思吗？」 |
| N8 | 「先别搞多个物理根吧……其它的如你所理解的，第一版先做的简单一些」 |
| N9 | 「目前就是这样，要求做到的是，父Agent为子Agent创建工作区并挂载（允许子Agent使用这个工作区），子Agent可以自由在工作区内进行文档编辑（注意，在工作区里干什么也应当是工作区模块要管的，给docs权限就允许docs操作，后续有bash权限就允许bash操作）」 |
| N10 | 「1、owner是什么 2、两个门是？ 3、直接删，管友军干啥 4、语义方面我觉得有点讲究，要不看看linux自己的文件编辑命令？听说这能提升效率」 |
| N11 | 「1、工作区归属给创建者得了 2、你管隔壁干什么？两个独立项目，你管好你自己就行，不过你的想法很好，代码和工具包装都需要有 3、能用linux命令规范默认用linux命令规范，允许Agent直接传一整行命令，你只做地址映射；3.1，要不顺便把bash包改了，然后让Docs系统依赖Bash包来实现这些？」 |
| N12 | 「子Agent在有工作区的时候，应当允许为其派的更小Agent指定其自身工作区内的更小工作区……命令的话，可以试试看做个命令白名单，因为Docs现在看来其实只是一个文档操作的封装，所以有linux既有对文档处理命令，就用既有的，没有就只能自己实现IO了——似乎例如doc_edict和doc_create？」 |
| N13 | 「我不建议第一阶段直接给Docs系统挂bash入口，因为完整的bash命令权限我认为后面要额外开模块搞，Docs只管维护docs」 |

### 0.2 选项裁定（选项文字是控制方措辞，用户按选项作答；非用户原话）

| 题 | 用户选择 |
|---|---|
| docs 面读写边界 | **B. 查看 + 写入**（含 create/edit，写工具标 `destructive=true`） |
| 包落点 | **A. 新 artifact `docs-mosire`** |
| 文档身份定义权（Q-I1） | **混合**：地址（docId）归调用方、条目身份归 Docs |
| 是否在世界 revision 内（Q-S1/R-S4） | **A. 不在 revision 内**（坐标靠调用方注入） |
| 重写后 `bash` 工具的形态 | **C. bash 重写排到 workspace 之后单独一批**（本文不实现） |
| 挂载 / Resolver 的可动范围 | **B. 运行期也可挂载**（需专用工具 + 守卫） |
| 执行面怎么复用 bash（N11-3.1 → **N13 推迟**） | 方案曾定为"乙. 抽执行核心成库 `shell-mosire`"；**N13 后整块移到后续命令面模块（§8 ⑤）**，v1 不做 |
| v1 的映射范围（N8） | **单物理根**：一个虚拟根 ↔ 一个物理目录；多物理根覆盖（overlay）与分片**推迟**，SPI 位置留出 |
| `SKILLS`/`SKILLS_EDIT` 常量迁移兼容期（N10） | **直接删，不留 `@Deprecated` 别名**（已核消费方零引用） |
| v1 的文档纪律（N10 之后的裁定） | **A. 两族都做**：`LOG`（追加/作废）+ `FILE`（行寻址编辑） |
| v1 是否给 Docs 挂 bash 入口（N13） | **不挂**：v1 的 Docs **只自实现 IO**；命令面（`bash` 挂载绑定 + 白名单）与完整 bash 权限**另开模块**，排到后续批次 |
| 设计文档落盘方式 | **一份文档两部分**（本文即该形态：§A 工作区标准 + §B Docs 系统） |

### 0.3 隔壁需求书转引的用户原话（二手转引，出处：Simos 需求书 §0）

| # | 原话（转引） | 说明 |
|---|---|---|
| U1 | 「现有项目的 Skill 等直接取缔，Skills/Docs 系统我想了想其实是一个，Skill 无非是有更多触发规则的 Docs」 | 与 N3 一致：Skill 是**将来**形态，本期不做 |
| U2 | 「我们暂时不准备做 Skill，只维护一个通用文档系统，最多加个自动注入的简单 Agents.md」 | ★ **其"最多加个自动注入的简单 Agents.md"已被 N4 取代**（用户裁定不做 Agents.md 自动注入），见 §1.2 与 §7 Q-B1 |
| U3 | 「GM 肯定啥都能看」 | → §B 可见性判据的 GM 短路分支 |
| U4 | 「单个世界内的决策人也许需要一个在世界内一同储存的文档，注意应当走文件夹而不是 db」 | → §B 存储形态（文件夹，非 db） |
| U5 | 「Docs 系统必须维护一个自定义目录，这个自定义程度甚至要允许 Simos 这边自动注入 worldid 字段，具体工具封装这边都可以谈」 | → §B 根可配 + 自定义字段区 |

---

## 1. 目标与非目标

### 1.1 目标

1. **三层职责分离**（N5）：`agentlib-mosire` 只暴露**通用权限接口**；`workspace-mosire` 提供**一整套工作区权限界定**；`docs-mosire` 在两者之上提供**文件 IO 与文档读写工具**。
2. **目录托管标准化**（N4）：把"哪个目录归谁、谁能碰、什么时候回收"从散落的拼路径收拢成一处登记（地籍 + 鉴权 + 生命周期）。
3. **子 Agent 自有文档工作区**（N4）：子 Agent 不碰任何外部目录，也能在自己的工作区里创建/修改/读取文档。
4. **Docs 寻址 / 创建 / 修改系统**（N4）：地址由调用方定义、条目身份由存储派生；追加式写入、作废留痕、按时刻查询、条目级可见性。
5. **服务隔壁（Simos）**：Simos 退役其 `SkillLibrary`，改由本系统承担文档存储与查询（N2 + U1）。
6. **工作区能力模型**（N9）：**父 Agent 为子 Agent 创建工作区并挂载**，声明它在这个工作区里能干什么（`docs`，以及 `FILE` 工作区要的 `bash`）；子体在自己的工作区里自由编辑文档，**不碰任何外部目录**。
7. **（N13 收缩后）Docs 自实现 IO**：v1 的 `FILE` 写入由 Docs **自己实现**（原子创建、行寻址/唯一锚点编辑、原子替换），**不给 Docs 挂任何 bash 入口**——完整的命令执行权限是另一个模块的事。
8. **子工作区**（N12）：有工作区的子 Agent 可以为**自己派的更小 Agent** 划一块更小的、**在自己工作区目录内**的工作区；能力只能更窄，父级失效则级联失效。

### 1.2 非目标（本文期内不做，逐条登记）

| 非目标 | 理由 / 去向 |
|---|---|
| Skill 系统与 Skill 自动触发 | N3 明令；将来形态（"Skill = 带触发规则的 Docs"）另开设计文档，对应 §8 批次 ⑤ |
| **Agents.md / 任何文档的自动注入** | N4 裁定；"自动注入交给其他具体实现管" |
| **bash 插件退役/合并** | N6 的方向成立；**执行核心抽出与命令面**按 N13 排到后续独立模块（§8 ⑤），v1 不动 bash 插件一行 |
| **命令面（`bash(mount=…)` + 白名单）** | **N13 裁定：v1 不做**。docs-mosire **不依赖 `shell-mosire`**、不挂 bash 入口；设计草案保留在 §5.10 作为后续模块的输入 |
| **`shell-mosire` 抽核** | 随命令面一起排到 §8 ⑤；v1 的 `BashPluginMosire` 逐字不动（104 条用例语义不受影响） |
| 技能重基（`SKILL.md` + frontmatter 校验 + 触发规则） | 技能面本期逐字不动；v1 已含 `FILE` 纪律（行寻址编辑），技能重基 = 注册一个能力 + 一个校验器，不改存储层 |
| 世界 revision 语义 / 文档回放 | Q-S1 裁定为"不在 revision 内" |
| embedding / 语义检索 | 沿用既有姿态（`EmbeddingProvider` 仍是未接线 SPI） |
| 删除文档 | 追加式语义下没有删除（作废 = 追加一条） |
| 外部 MCP 面暴露文档工具 | 另裁；本文只做库 API + 本进程 agent 工具面 |
| `Workspace`/`EditorTool` 两个占位接口的**实现** | 本文判其**删除**（§6.3），由 `workspace-mosire` 取代 |
| `MemoryStore`/`MemoryRetriever` 的接线 | 另一条线，不在本文 |
| **多物理根覆盖 / 分片 resolver / 嵌套挂载** | N8 裁定"第一版先做简单些"：v1 只有一对一 `direct()`；位置留在 SPI 后面，见 §4.4 与 §9.7 |

---

## 2. 现状事实（回代码核过）

### 2.1 通用权限接口：形状合格，但沾了两处业务语义

**合格的部分**（这就是"通用权限接口"）：

| 组件 | 位置 | 职责 |
|---|---|---|
| `ResourceManifest` | `AgentLibMosire/.../permission/ResourceManifest.java:20` | 工具**声明**自己涉及哪些命名空间与缺省策略 |
| `ResourceScopeMap` | `.../permission/ResourceScopeMap.java:25` | 调用者的**可达面**（命名空间 → `ResourceScope`），命名空间是**自由字符串** |
| `ResourceAuthorizer` | `.../permission/ResourceAuthorizer.java:35` | **判定**：`of(permissions, manifest)`（`:59`）、`require`（`:82`）、`denial`（`:97`）、判定表 `:126-159` |
| `ToolCallAuthorizer` | `.../tool/ToolCallAuthorizer.java:122` | **唯一入口**：声明式前置闸 `:128-140`；`ResourceDeniedException` → `RESOURCE_DENIED`（`:67`、`:172-178`） |

**两处业务语义污染**（与 N5"只暴露一个通用权限接口"冲突）：

1. `ResourceScopeMap.SKILLS`（`:41`）与 `SKILLS_EDIT`（`:54`）——"技能"是本仓业务面，常量却住在通用层；
2. `SandboxMask.skillsVisible(...)`（`AgentLibMosire/.../tool/SandboxMask.java:156-166`）**硬编码**读 `ResourceScopeMap.SKILLS`（`:160`）。

对照：`ResourceScopeMap.FS`（`:28`）与 `AgentPermissionSet.fsScope()`（`.../AgentPermissionSet.java:103`）我判为**通用**（"操作系统文件系统"这一命名空间任何工具都可能涉及），保留。

### 2.2 目录托管散在五处

| # | 目录 | 拼路径/判定点 |
|---|---|---|
| ① | 工作目录围栏 `agents.workingDirs` → `fs` 面 | `BrainMosire/.../tools/WorkingDirsLoader.java:54-80` → `MainMosire/.../app/App.java:379-382` |
| ② | 技能根 `<dataDir>/skills` | `App.java:387`（缺省根）、`SkillSettings.java:55-68` |
| ③ | 子 Agent 数据根 `<dataDir>/subagents/<实例 id>` | `App.java:487`、`:706`、`:730`；`SubagentManager.java:1559`（`normalizedRoot.resolve(instanceId)`） |
| ④ | bash 基准工作目录 + 沙箱可写面 | `ShellTool`（`fenceOf`、`wrapped(...)`） |
| ⑤ | 插件目录 | `App.java:895`（`codeParent.resolve("plugins")`）；插件侧**无数据目录**——`AgentLibMosire/.../plugin/HostServices.java` 只有一个 `ConfigStore` |

⇒ 每处各自拼路径、各自判边界。"第二处拼写点"的温床。

### 2.3 遮蔽面的现状（bash 侧文件围栏的唯一数据入口）

| 端 | 位置 | 现状 |
|---|---|---|
| 键 | `SandboxMask.CONFIG_KEY = "sandbox.maskedPaths"`（`:45`） | 值 = **扁平路径清单**，条目不带命名空间、不带策略 |
| 判定 | `SandboxMask.fence(permissions, config)`（`:134-149`） | 逐路径：`skillsVisible` ⇒ 只读；否则 ⇒ 遮蔽；`sandboxRequired = 遮蔽非空` |
| 写端（生产恰好两处） | `App.java:496`（主 Agent）、`SubProcessExecutor.java:200`（子体链路） | 都只写**一个**技能根 |
| 读端 | `BashPluginMosire/.../ShellTool.java:501` | 唯一消费点；"该遮蔽却没沙箱" ⇒ `SKILL_SCOPE_UNFENCED` 且**不发命令**（`:502-513`） |

**能力边界（实证）**：清单**能**容纳多个路径并逐路径分类，但**无法表达分面策略**（"docs 面可见、skills 面遮蔽"），因为判据里只有 `SKILLS` 一个命名空间；`BashSandbox.wrap` 也只有 `ro:` / `mask:` 两桶（`BashSandbox.java:226-231`、挂载顺序 `:250-287`）。

### 2.4 子 Agent spawn 的权限物化链路（工作区必须挂进这条链）

| 环节 | 位置 | 要点 |
|---|---|---|
| 物化 | `SubagentManager.effectiveScopes`（`:775-846`） | `调用者 ∩ 模板 ∩ 请求`；`:840-844` 逐命名空间 `withNamespace` **手写**；`fs`/`skills` 各一段 |
| 越界拒 | `SubagentManager.skillDenied`（`:892-925`）/ `dirDenied` | 抛出前落 `permission.denied` |
| argv | `AgentCommand.java:200-222` | `--skills-dir` + 两行**硬编码**的 `appendScope`（`:206-207`） |
| 子进程解析 | `Main.java:303-305`、`:327-329` | `switch` 字面量匹配；未知参数 **return 2** |
| 子进程恢复 | `SubagentProcessMain.java:135-139` | 从 `template.toPermissionSet()` 起手（**不含任何命名空间**）+ argv decode |
| 单调性 | `PermissionChecker.isSubset`（`:115-118`）→ `ResourceScopeMap.covers`（`:141-157`） | 只遍历**父级表态过的**命名空间；父级没表态的键，子级怎么表态都放行 |

★ **`:687-693` 的既有教训**：新命名空间若不在物化点落成数据，运行期会回落到"未表态"，而单调性守卫**看不见**那一层。

### 2.5 权限语义的两个硬事实（决定本文所有形状）

1. **`covers` 的 fail-closed 方向**（`ResourceScopeMap.java:141-157`）：父级表过态且非 `unrestricted` 的命名空间，子级**没表态** ⇒ 判否；父级**没表态**的命名空间 ⇒ 子级随意（`:143` 的循环集合里没有这个键）。
2. **"一目录一命名空间"会造洞**：每新增一个目录就造出一个父级没意见的新键，子级的 `unlimited()` 会被静默放行。
   ⇒ 本文写死：**专有目录一律落在"一个命名空间 + 前缀集合"里**，新增目录 = 新增前缀，不新增键。

### 2.6 隔壁（Simos）侧现状（转引需求书 §1，二手；其"未核"项照抄于 §10.3）

- 两层 info：世界级 `SimulationState.info`（`InfoSystem` 接口）**生产零写入点**；文档实际住在模块级 `GaState.info`（`Map<String, List<SdInfoEntry>>`），走 `Command → ChangeSet → Revision`。
- 一条文档现有字段（`SdInfoEntry`）：`id / tick / tags / affiliations / key / value / note / at(RevisionId) / sourceDirective / adjudicationStatus`。
- 身份规则：`SdInfoId.synthesize = canonicalAddress + "#" + ordinal`，序号靠 `List` 现算，纯函数、可重放。
- 读口：`RedactingQueryService.docs(actor, target, window)`；可见性判据 = `tags` 命中 ∨ `affiliations` 命中；两支都不命中 ⇒ 不可见；actor 解不出 ⇒ 只走 tags；排序 = `tick` 降序 + 同 tick 按 `id` 升序，**先排后截**；空结果不抛；`value` 原样。
- 地址约定：`ga:doc.<docId>`（docId 必须裸词）；报告地址 `sd:doc.report-<sender>-<tick>-<seq>`；读侧用**解析出的 kind** 判"是不是文档"。
- 两个活依赖：`at(RevisionId)`（写入时注入）与"作废 = 再追加一条 VOIDED"。
- 工具面：决策人 43 条、GM 161 条；写任意文本的口是 `ga.PutInfo`（GM）。

### 2.7 其余相关现状

- `io.mosire.brain.workspace.Workspace`（`Workspace.java:12-18`）与 `EditorTool`（`EditorTool.java:17`）：**零实现、零引用、不在装配里**；类注自述"读/找/改都经同一处解析路径，于是『哪些路径可见』这条规则只有一个归属地"；`ShellTool` javadoc 亦记"目录围栏归三期的 Workspace"。⇒ 本文判其**删除**（§6.3）。
- `BashPluginMosire` 体量：main **7 文件 / 3386 行**，test **10 文件 / 104 条**（6 条需 root 跳过）；`MainMosire/src/main` 对 `io.mosire.bash` **0 命中**（边界确实干净）；打包链 = `MainMosire/pom.xml:40-45`（provided + shade 排除）+ `:132-147`（拷到 `target/plugins/`）。
- 上下文：`ContextLayer` 8 层顺序**冻结**、新增只能追加末尾（`ContextLayer.java:4-5`）；`ContextSources` 记录 `(skills, preloadedSkills, memory, identity, memoryK)`（`:32-37`）。**本文不新增上下文层**（N4 裁定不做自动注入）。
- `AgentTool` 实现者全仓 4 处：`SkillTools`(6)、`SubagentOrchestrationTools`(5)、`ShellTool`(1)、`McpToolAdapter`(桥)。工作区/Docs 的工具是新的第 5 处。

---

## 3. 分层与依赖

```text
MainMosire ────┐
               ├──→ BrainMosire ──→ AgentLibMosire
               │                        ▲   ▲
               ├──→ DocsMosire ─────────┘   │
               └──→ WorkspaceMosire ────────┘
BashPluginMosire ──→ AgentLibMosire（v1 逐字不动，N13）

（后续批次，不在本文：ShellMosire 抽核 + bash 薄壳化 + 命令面/白名单，见 §8 ⑤）
```

| 模块 | artifactId | 包前缀 | 依赖 | 职责（一句话） |
|---|---|---|---|---|
| `AgentLibMosire` | `agentlib-mosire` | `io.mosire.agentlib.*` | — | **通用权限接口**（声明/可达面/判定/唯一入口）+ 工具、MCP、事件、配置等通用件；**不含任何业务命名空间常量** |
| `WorkspaceMosire`（新） | `workspace-mosire` | `io.mosire.workspace.*` | `agentlib-mosire` | **工作区标准**：虚拟↔物理映射（Mount/Resolver）+ 唯一解析点鉴权 + 运行期挂载工具（`mount_workspace`/`unmount_workspace`/`list_workspaces`）+ 子工作区递归 + 可写/只读/遮蔽三面 + 生命周期与审计 |
| `DocsMosire`（新） | `docs-mosire` | `io.mosire.docs.*` | `agentlib-mosire` + `workspace-mosire` | **Docs**：`LOG` 追加日志与条目模型（含可见性判据）+ `FILE` 的**自实现 IO**（原子创建/行寻址编辑/区间读）+ 库 API + `doc_*` 工具面。**不依赖执行核心、不挂 bash 入口（N13）** |
| `BashPluginMosire` | `bash-plugin-mosire` | `io.mosire.bash.*` | `agentlib-mosire`（PF4J provided） | v1 **逐字不动**（104 条用例语义不变）；抽核与薄壳化见 §8 ⑤ |
| `ShellMosire`（**后续**） | `shell-mosire` | `io.mosire.shell.*` | `agentlib-mosire` | **执行核心**（进程 + 三层围栏 + `BashSandbox` + 超时/截断）——随命令面模块一起落地（§8 ⑤、§5.10 草案） |
| `BrainMosire` | `brain-mosire` | `io.mosire.brain.*` | `agentlib-mosire` + **`workspace-mosire` + `docs-mosire`**（R3b 起；控制方裁定：物化子体 `docs`/`docs-edit` 面、挂子体工作区、取 `DocsCapability` 常量都在 spawn 侧，**不许在 Brain 里另抄命名空间字符串**） | Agent 运行时；技能面本期**逐字不动**（其命名空间常量迁到本模块，见 §6.1） |
| `MainMosire` | `main-mosire` | `io.mosire.main.*` | `brain-mosire` + **`workspace-mosire` + `docs-mosire`**（R3b 起：装配点注册两族工具、注册能力、挂装配期挂载） | 进程组装与网关；**不含 Agent 行为逻辑** |

**依赖方向纪律**（新增）：`workspace-mosire` / `docs-mosire` **不得** import `io.mosire.brain.*` / `io.mosire.main.*`；`agentlib-mosire` 不得 import 任何下游；`docs-mosire` **不依赖任何插件**。

---

## 4. §A 工作区标准（`workspace-mosire`）

### 4.1 本体：物理 ↔ 虚拟的映射 + 解析点鉴权（N7）

**工作区的本体不是"一块具名物理目录"，而是「虚拟路径空间 ↔ 物理落点」的映射表 + 在解析点上的鉴权。物理目录只是映射的一种落点。**

| 术语 | 含义 |
|---|---|
| **虚拟路径** | `<挂载名>/<相对路径>`——**面向调用方（模型/工具）的唯一路径形态** |
| **Mount（挂载）** | 一个虚拟根名 + **一个物理目录（v1 一对一）** + 一组能力 id + **`holder`（创建者：归属与生命周期权限，N11）** + **`user`（使用者：解析按它索引）** |
| **物理目录** | 该挂载指向的物理目录（**v1 一对一**；多根/分片见 §4.4"推迟项"） |
| **Resolver（映射策略）** | 虚拟相对路径 → 物理位置（**扩展点 1**；v1 只交付 `direct()` 一对一，见 §4.4） |
| **Capability（能力）** | 在**这个工作区里**允许做的一类操作（`docs` / `bash` / …）：**能力决定放行哪一族工具**，并在授予时落成"命名空间 + 前缀"（§4.8，N9） |
| **Kind（类别/纪律）** | `LOG`（追加型文档）/ `FILE`（文件型文档）/ `SCRATCH`（通用暂存）/ `REFERENCE`（只读引用）——决定 shell 在该挂载上的地位（§4.8） |

三条推论：

1. 物理布局（`<dataDir>/workspaces/...`、分片、store 覆盖目录）对模型**不可见**；换 resolver 不改调用方。
2. **映射策略就是那个"扩展点"**（N7）——但 **N8 裁定：第一版只做一对一**（一个虚拟根 ↔ 一个物理目录）。多物理根的覆盖语义（隔壁"仓种子 + store 覆盖、同 id 后者优先"）**推迟**，位置留在 resolver 接口后面，见 §4.4 与 §9 的推迟清单。
3. **鉴权发生在"解析"这一个点**：`resolve(虚拟路径, caller) → 物理落点 | 拒绝`。与 `io.mosire.brain.workspace.Workspace` 占位接口的自述（"读/找/改都经同一处解析路径"）逐字同构。
4. ★ **"在工作区里能干什么"由工作区模块定**（N9）：能力是**唯一决定点**——给 `docs` 能力就允许 `doc_*` 操作，将来给 `bash` 能力就允许 shell 操作；任何工具族**不得自行放宽准入**，也不得在工作区之外另立一套"能不能用"。

★ **归属修正**：布局 SPI 从 Docs **上移到 Workspace**（布局 = resolver）；Docs 只保留"条目语义 + 文件 IO + 可见性判据"。本文 §5.3 随之改写。

### 4.2 虚拟路径与挂载命名

- 形态 `<挂载名>/<相对路径>`；挂载名 `[a-z0-9][a-z0-9-]{0,63}`；相对路径**不得**以 `/` 开头、不得含 `.` / `..` 段（与 `ResourceScope` 的前缀口径同源）。
- **工具面不接受裸物理路径**：要访问某个外部目录，必须先在装配期或运行期**挂载**它。
- **挂载名冲突 ⇒ 具名拒**（M4），不静默替换。
- **嵌套挂载**（如 `/docs/team-a` 叠在 `/docs` 之上）本期**不做**——"部分覆盖"会让可见性判定出现两个来源（`/docs` 的过滤 + 子挂载的过滤），登记为将来的扩展点。
- ★★ **物理嵌套 ≠ 嵌套挂载**（R5 真模型验收后裁定，2026-10-13）：**W1 要求子工作区落在父工作区目录内 ⇒ 物理目录嵌套是正常形态，不是歧义**。解析只按**虚拟挂载名 + 使用者切片**（`Mount.user`）定位，**不得**因"解析出的物理路径同时落在另一个挂载的目录内"而判歧义并拒绝——那会把默认装配的主工作区全链阻断（`workspaces` ⊃ `workspace`）。歧义的唯一判据是**同一使用者下虚拟挂载名冲突**（M4 已管）。

### 4.3 接口形状（草案；冻结前允许改，冻结后 Docs 批次不得改）

```java
package io.mosire.workspace;

record MountName(String value) {}                 // [a-z0-9][a-z0-9-]{0,63}

enum Kind { LOG, FILE, SCRATCH, REFERENCE }      // 文档纪律 / 目录类别（§4.8）

/**
 * 工作区能力：**在这个工作区里**允许做的一类操作（N9）。
 *
 * @param id             能力 id（`docs` / `bash` / …）——工具族用它向工作区模块申请准入
 * @param namespaces     授予时落格的命名空间与操作（如 docs 能力 = {docs: {READ}, docs-edit: {WRITE}}）
 * @param shellVisible   是否允许 shell 看见该挂载（docs = false；bash = true）
 * @param allowedCommands 仅 `bash` 能力用：**命令白名单**（N12；空集 = 一条命令都不许，fail-closed）
 */
record Capability(
    String id,
    Map<String, Set<Operation>> namespaces,
    boolean shellVisible,
    Set<String> allowedCommands) {}

/** 能力目录：装配期注册（docs-mosire 注册 "docs"；`bash` 工具族注册 "bash"）。 */
interface CapabilityCatalog {
  void register(Capability capability);
  Optional<Capability> get(String id);
}

/**
 * 映射策略（扩展点 1）。**v1 只交付 {@link Resolvers#direct()}：一对一。**
 * 实现必须 fail-closed：越界一律抛，不猜。
 *
 * <p>★ 推迟项（N8）：多物理根覆盖（overlay）与分片（sharded）留在这个接口后面，
 * 等真有第三个消费者要时再实现——第一版不放进来。
 */
interface Resolver {
  Path resolve(Path dir, String relativePath);
  List<String> list(Path dir, String relativePrefix);
}

final class Resolvers {
  static Resolver direct();    // 一对一（v1 唯一实现）
}

/** 挂载来源：决定它进不进 bash 遮蔽面（M6）。 */
enum Origin { ASSEMBLY, RUNTIME }

record Mount(
    MountName name, String holder, String user, Path dir,
    Resolver resolver, Kind kind, Set<String> capabilities, Origin origin,
    Optional<MountName> derivedFrom) {}   // ★ 派生关系（R5 后补）：由哪个挂载派生而来；装配期挂载 = empty

/** 调用者 = 身份（**身份键**）+ 权限集；解析与鉴权都以它为准。
 *  身份键在解析时与 `Mount.user` 比对（"哪些挂载是我的"），在 M2/M5 检查里与 `Mount.holder` 比对（"谁创建了它"）——
 *  同一个键扮演两个角色（R2 实现时澄清，控制方确认）。 */
record WorkspaceCaller(String holder, AgentPermissionSet permissions) {}

/** 喂 bash 侧的**物理落点**清单（虚拟层不下发给插件；静态装配期清单，见 M6）。 */
record MaskEntry(String namespace, Path path, Kind kind) {}

interface WorkspaceRegistry {
  // ---- 挂载表（装配期 + 运行期，均需过守卫）----
  Mount mount(Mount spec, WorkspaceCaller caller);              // 守卫 M1–M4
  void unmount(MountName name, WorkspaceCaller caller);         // 守卫 M5
  List<Mount> mounts(String holder);

  // ---- 唯一解析点（G1–G6）----
  Path resolve(String virtualPath, WorkspaceCaller caller);
  List<String> list(String virtualPrefix, WorkspaceCaller caller);

  // ---- 与 AgentLib 通用权限接口的落格 ----
  AgentPermissionSet grant(String holder, AgentPermissionSet permissions);  // 把挂载的物理前缀并进对应命名空间

  // ---- bash 侧遮蔽面（静态）----
  List<MaskEntry> maskEntries();

  // ---- 审计 ----
  List<WorkspaceEvent> events();   // mounted / unmounted / denied
}

/** 挂载管理的工具面（workspace-mosire 提供的 AgentTool）。 */
final class WorkspaceTools {
  // mount_workspace / unmount_workspace / list_workspaces
  // ResourceManifest.of("workspaces", ResourcePolicy.DENY)
}
```

### 4.4 Resolver SPI 与护栏（G 组）

| # | 护栏 |
|---|---|
| **G1** | **解析结果必须落在该挂载被授权的物理范围内**（resolve 后做 containment 校验）——否则 resolver 就是越权后门（`/docs` 能被指到 `/etc`） |
| **G2** | 挂载**只能缩小、不能放大**：新挂载的物理范围与能力必须 ⊆ 挂载者已有权利（M1/M3）；工具与模型**不能**制造自己权限之外的映射 |
| **G3** | **虚拟路径拼法由标准定死**，resolver 只映射、不得改拼法（防"第二处拼写点"） |
| **G4** | 面向模型的输出**只出现虚拟路径**；物理路径只进装配日志与审计事件 |
| **G5** | 解析失败 / 结果越界一律 **fail-closed** |
| **G7** | **同一物理路径被多条条目声明**（多个命名空间）⇒ **逐条目求与**：只要有一条判成不可见就**遮蔽**；"遮蔽压过只读"在执行侧本来就是事实（先只读 bind、再用空目录遮蔽），在 `Fence` 里显式求与只是不让桶的顺序替判定说话（R1 实现时补的规则，**控制方确认**） |

★ **推迟项（N8）**：多物理根覆盖、分片、嵌套挂载（§4.2）—— 位置留在 `Resolver` 接口与挂载表后面，**v1 不实现**。因此 v1 不存在"多义"这一失败形态；`direct()` 的解析结果唯一。
| **G6** | 子体的挂载集 ⊆ 调用者的挂载集，**且每个挂载的物理范围也 ⊆ 调用者可达面**（红线 1 在这层的落法） |

### 4.5 解析与鉴权流程（唯一解析点）

```
resolve(虚拟路径, caller):
  ① 拆成 (挂载名, 相对路径)                       —— 拆不出 ⇒ INVALID_ARGUMENTS
  ② 在 caller.holder 的挂载表里找该挂载           —— 找不到 ⇒ WORKSPACE_NOT_FOUND（与"无权"同码，不泄露存在性）
  ③ 校验准入：调用者是否持有该挂载的能力（能力已在授予时落成命名空间前缀，这里按调用者面判）
                                                —— 不满足 ⇒ RESOURCE_DENIED
  ④ resolver.resolve(dir, 相对路径)                 —— 越界 ⇒ 拒（G1/G5）
  ⑤ containment：结果必须落在 targets 的物理范围内 —— 不满足 ⇒ 拒
  ⇒ 返回物理落点（面向模型的那一侧只回虚拟路径）
```

★ **"不可见"与"不存在"同码**：与技能面同族的教训——判据必须先于存在性，否则工具面变成"挂载存在性探测器"。（旧文档 `设计-Skill运行时能力.md` §3 把这条写反了，见 §10.2 D1。）

### 4.6 投影（物理 → 虚拟）

- `list(虚拟前缀, caller)` 把物理树投影成虚拟条目；**过滤不在这里重复判**——条目级可见性归 Docs 的判据（§5.4），本层只做"挂载/能力"级别的裁剪（避免两处判同一件事）。
- 投影输出**只含虚拟路径**（G4）；错误消息同样只含虚拟路径。

### 4.7 运行期挂载（选项裁定 B）与**子工作区**（N12）

**运行期可挂载**，但必须走**专用工具 + 守卫**：

| 元素 | 设计 |
|---|---|
| 工具 | `mount_workspace` / `unmount_workspace` / `list_workspaces`（`workspace-mosire` 提供；供给源 id `"workspaces"`） |
| **工具面的落点形态**（★ R6 修订，2026-10-13） | **工具面不再接受绝对物理路径**——它只接受**派生形态**：`mount_workspace(name, parent=<自己已有的挂载名>, subdir=<相对子目录>, kind, capabilities)` ⇒ 落点 = 解析 `parent` 得到的物理目录 + `subdir`（必须是**真子目录**，复用 W1/W2）。理由：① A7/G4 要求"面向模型只出虚拟路径"，而旧形态**要模型输入绝对物理路径**，自相矛盾且实测模型无从得知（R5/R5c：只有人把路径喂给它才跑得通）；② 派生形态天然满足"只能在自己的地界里划更小的工作区"。**绝对 `dir` 形态只保留在注册表 API（装配期宿主代码用）**，不进工具面 |
| 权限面 | **新命名空间 `workspaces`**：前缀 = **允许在该物理范围内新建挂载**；缺省 `DENY`（未表态 = 一个挂载都建不了） |
| 守卫 | M 组（§4.10）：物理范围 ⊆ 可达面 ∩ `workspaces` 面；holder 只能是自己或后代；能力不放大；名字冲突拒；卸载限自己/后代 |
| 审计 | 每次成功/拒绝都落事件：`workspace.mounted` / `workspace.unmounted` / `workspace.denied`（+ `permission.denied` 的既有口径） |
| 落地位置 | 挂载表是**父进程运行期状态**，按 holder 键；子体的工具调用本来就经父侧 MCP 执行（`SubProcessExecutor:225-245`），因此**解析在父侧按子体身份进行，不需要 argv 传递运行期挂载** |

**子工作区（N12）**：有工作区的子 Agent 可以为**自己派的更小 Agent** 划一块**更小的**工作区——物理落点 = **自己工作区目录内的真子目录**。

```text
main            /workspace            → <dataDir>/workspaces/main
  └─ 子 A       /workspace            → <dataDir>/workspaces/main/A        （真子目录，user=A）
       └─ 孙 B  /workspace            → <dataDir>/workspaces/main/A/B      （再小一层，user=B）
```

| # | 规则 |
|---|---|
| **W1** | **严格更小**：子工作区必须是自己工作区内的**真子目录**（不得等于自己的根——那等于把权限整份复制，"逐层只减不增"就名存实亡） |
| **W2** | **只能在自己的工作区目录内划**（containment ⊆ 调用者可达面，A2 的加强版；不许拿父的其它目录开刀） |
| **W3** | **深度复用既有子 Agent 深度限制**（`subagents.maxDepth`），不新增第二个深度旋钮 |
| **W4** | **级联失效**：父的工作区挂载被卸载/回收 ⇒ 其下所有子工作区挂载一并失效（fail-closed，不回落、不静默） |
| **W5** | 虚拟命名自洽：子体看到的仍是自己的 `/workspace`（不继承父的路径拼法），物理落点是父目录的子目录；父的其它内容对子体**不可见** |
| **W6** | **能力只能更窄**：子工作区声明的能力 ⊆ 父在自己工作区上的能力（含**命令白名单只能更窄或相等**，M12） |

### 4.8 能力 → 落格与 shell 待遇（N9 的落点）

**能力是"在这个工作区里能干什么"的唯一决定点**；授予时按能力表落成数据，运行期不再有第二处判定。

| 能力 id | v1 | 落格（授予时写进权限集） | shell 可见 | 适用纪律 |
|---|---|---|---|---|
| `docs` | ✅ **v1 唯一注册的能力** | `docs` ← dir（READ）；`docs-edit` ← dir（WRITE；`LOG` 的追加/作废、`FILE` 的 create/edit 都用它） | 否（遮蔽 / 只读） | `LOG` / `FILE` |
| `bash` | ⏳ **后续模块**（N13） | `fs` ← dir（READ + WRITE） | 是（不遮蔽） | 仅 `FILE` / `SCRATCH` |
| 未注册 / 未授予 | —— | **不落格** | 否（遮蔽） | —— |

★ **N13 的直接后果**：v1 只注册 `docs` 一个能力 ⇒ **所有挂载在 shell 侧一律遮蔽**（fail-closed）。`FILE` 挂载的"可写"是**经 Docs 工具可写**，**不是 shell 可写**——命令面随 `bash` 能力一起到后续模块（§8 ⑤）。

**纪律决定 shell 地位（N11-3 的落点）**：

| `Kind` | 语义 | shell 待遇 |
|---|---|---|
| `LOG` | **追加型文档**（条目不可变：追加 / 作废 / 留痕 / 可重放） | **只读 bind 或遮蔽**——shell 永远不是追加型文档的写入路径（`sed -i` 会毁掉不可变语义） |
| `FILE` | **文件型文档**（语义 = Linux 文件语义本身，没有额外不变量） | v1：无 `bash` 能力 ⇒ **遮蔽**（写只经 Docs 工具）；后续注册 `bash` 能力后 ⇒ 可写 |
| `SCRATCH` | 通用暂存区 | 同上（有 `bash` 能力才可写） |
| `REFERENCE` | 只读引用（外部文档根、技能根） | **只读 bind** |
| —— | 能力集为空 / 调用者盖不住该物理路径 | **遮蔽**（空目录 bind） |

**判定口径与既有 `SandboxMask` 一致：清单静态、判定逐调用者算**；"覆盖整条路径"取 `ResourceScope.covers` 语义（不是"有交集"）。

★★ **不重复判**：能力在**授予时**落格；运行期的准入仍由既有 `ResourceAuthorizer`（工具 manifest × 调用者面）判——工作区模块**不产出第二条运行期判据**（否则就是第二处拼写点）。

### 4.9 生命周期、并发与子进程

- **装配期**：主 Agent 启动时挂载自己的根（`main` 工作区、技能根、项目文档根等）。
- **运行期**：有权者经 `mount_workspace` 新建；spawn 子体时父侧为其挂 `holder=<实例 id>` 的挂载。
- **回收**：`unmount` 只注销挂载、**保留物理目录**（文档是数据）；物理清理是独立动作（本期不做）。
- **并发**：挂载表在父进程内存（单写者：工具入口串行）；物理目录为真值。
- **子进程**：子体的挂载集 = 父挂载集 ∩ 模板 ∩ 请求，物化在**父侧运行期状态**里，按子体 holder 键索引；子体本地不做挂载解析（它的本地文件访问只有只读的上下文装配）。
- **M6（保守规则）**：**运行期新建的挂载默认不进 bash 遮蔽面**（shell 看不见它）；要 shell 可见必须**装配期声明**。这样避免"运行期改静态 config"这类竞态，也让 `AgentLib` 与 bash 插件零改动。

### 4.10 不变量

**A 组（结构）**

| # | 不变量 |
|---|---|
| **A1** | 工作区**不新造权限机制**：权限落格一律是"某命名空间的一条前缀"，单调性走既有 `isSubset` |
| **A2** | 挂载的物理范围**必须在挂载者可达面内**，否则拒（防"父级没表态也能开地"，§2.5 的洞） |
| **A3** | `LOG` / `REFERENCE` 在 bash 侧**只读或遮蔽**——**`LOG` 上永远不给 shell 写**；`FILE` / `SCRATCH` 在本挂载有 `bash` 能力时才可写（v1 无该能力 ⇒ 一律遮蔽，A8/N13） |
| **A4** | 同一虚拟挂载名在同一 holder 下唯一；冲突具名拒 |
| **A5** | `grant` 只做"**补进未表态**"：目标权限集在该命名空间**未表态**（`declaredScope == null`）⇒ 并进落点；**已显式表态且不含落点**（含显式 `none()`）⇒ **具名拒（`RESOURCE_DENIED`）+ `workspace.denied` 事件**，绝不静默放大（控制方 2026-10-13 裁定，R2 实现后修订） |
| **A6** | 物理落点必须绝对化且归一（`..` 拒）；解析结果必须 containment（G1） |
| **A7** | 面向模型只出虚拟路径（G4） |
| **A8** | **能力是"工作区里能干什么"的唯一决定点**（N9）：能力**只在挂载/授予时**落成"命名空间 + 前缀"与 shell 可见位；运行期不产生第二处判定；工具族不得自行放宽准入，也不得绕过工作区另开一套"能不能用" |

**M 组（运行期挂载守卫）**

| # | 不变量 |
|---|---|
| **M1** | 目标物理范围 ⊆ 挂载者在 `workspaces` 命名空间的表态范围，**且** ⊆ 挂载者在目标区命名空间（如 `docs`）的可达面 |
| **M2** | **`user` 只能是自己或自己的后代**（血缘子树口径，照 `ContextAccessJudge` 的 subtree 判法）；`holder` 恒为**创建者本人**（不许代他人署名，N11） |
| **M3** | 挂载声明的能力 id **不得超出**挂载者在同一物理范围上的既有权利（能力落格后必须仍 ⊆ 挂载者面） |
| **M4** | 虚拟挂载名冲突 ⇒ 具名拒（不静默替换、不覆盖他人挂载） |
| **M5** | 卸载只允许 **`holder`（创建者）或其祖先**执行；卸载后该名字**解析 fail-closed**（绝不回落成物理路径或"就当没挂载"） |
| **M6** | 运行期新建的挂载（`Origin.RUNTIME`）**不进 bash 遮蔽面**（shell 看不见）；要 shell 可见必须装配期声明（`Origin.ASSEMBLY`）——`maskEntries()` **只返回装配期条目** |
| **M7** | 子体挂载集 = 父挂载集 ∩ 模板 ∩ 请求，物化在父侧运行期状态（不留"运行期往上问父级"） |
| **M8** | 每次挂载/卸载/拒绝都落审计事件（M8 与 §4.7 的审计面同源） |
| **M9** | **子工作区必须严格更小**：`user` 是自己或后代，落点是自己工作区目录下的**真子目录**（W1/W2）；深度受 `subagents.maxDepth` 约束（W3） |
| **M10** | **级联失效按登记时记录的派生关系**（`Mount.derivedFrom`，R5 后修订）：父挂载卸载 ⇒ 由它**派生**的子工作区挂载一并失效（沿 `derivedFrom` 链传递）。**不得按物理包含判级联**——W1 让子工作区必然物理嵌套在父目录内，而装配期独立挂载也可能物理嵌套（`workspaces` ⊃ `workspace`），按包含判会把无关挂载一起带走（R5 实测） |
| **M11** | 子工作区声明的能力必须 ⊆ 父在自己工作区上的能力（W6） |
| **M12** | **命令白名单只能更窄**：子能力携带的 `allowedCommands` ⊆ 父的（W6）；白名单为空 = 一条命令都不许 |

### 4.11 判据

**正向**：装配期挂载后 `resolve` 能取到物理落点；`list` 投影出虚拟条目；`grant` 后子体权限集里确有该前缀；带 `docs` 能力的挂载授予后，子体能在自己的 `/docs` 里 `doc_append` 并读回；**子体在自己的工作区目录内为孙代划更小工作区成功，孙代再划一层成功**；`unmount` 后名字彻底不可解析而目录仍在；运行期 `mount_workspace` 成功并落事件。
**负向**：**越界创建被拒**（物理范围不在可达面/`workspaces` 面内）；**给非后代挂载被拒**（M2：`user` 只能是自己或后代，`holder` 恒为创建者）；**子工作区 = 自己的工作区根被拒**（W1：必须真子目录）；**在自己工作区之外划子工作区被拒**（W2）；**超过 `subagents.maxDepth` 被拒**（W3）；**父挂载卸载后子挂载一并失效**（M10，不回落）；**子能力比父宽被拒**（M11；（白名单继承见 M12，随命令面模块生效））；**能力放大被拒**（M3）；**未注册的能力 id 被拒**；**未授予 `docs` 能力的挂载上调用 `doc_*` ⇒ `RESOURCE_DENIED`**；**v1 所有挂载在 shell 侧都不可见（遮蔽）**（A3/A8）；**名字冲突被拒**（M4）；**卸他人的挂载被拒**（M5）；**卸后解析失败**（M5，不回落）；**resolver 指向授权范围之外被拒**（G1）；**面向模型的错误里不出现物理路径**（A7）；**不可见挂载与不存在挂载同码**（§4.5）。

---

## 5. §B Docs 系统（`docs-mosire`）

### 5.1 两个门与两种纪律

**两个门**（访问路径；N11：两个独立项目，本项目只管自己——但**代码与工具包装都需要有**）：

| 门 | 消费者 | 说明 |
|---|---|---|
| **库 API** | 本项目进程内代码（装配面、管理面、将来的 HTTP/GUI 面） | 直接调 `DocStore` + 可见性判据；**判据只此一处** |
| **AgentTool 面** | Agent（主 Agent 本进程 / 子 Agent 经父侧 MCP 链路） | `doc_*` 工具 + `docs`/`docs-edit` 资源面 |

**两种纪律**（v1 两族都做，用户裁定 A；对齐 Linux 的两族编辑语义）：

| 纪律 | Linux 对应 | 写入语义 | 身份 | 用在哪 |
|---|---|---|---|---|
| **`LOG`** | `>>` / `tee -a` | **append-only**：只追加、不覆盖、不删除；作废 = 再追加一条 `VOIDED` | 地址 + 序号（§5.2） | 世界文档、审计、决策记录（隔壁 R-W1/W2） |
| **`FILE`** | `ed` / `sed -i` | **行寻址就地编辑**（区间/精确锚点）+ 原子写 | **虚拟路径即身份**（§5.2） | Markdown 守则、Agents.md、将来的技能文档 |

★ **纪律由文档类型/挂载声明**（与 N9 的能力一样是声明式）；**越纪律的操作响亮拒**，不静默降级：
- `LOG` 挂载上跑命令 ⇒ 具名拒（"这是追加型文档，改 = 追加一条"）；
- `FILE` 挂载上 `doc_append`/`doc_void` ⇒ 具名拒（"这是文件型文档，用 Linux 命令改"，§5.10）。
- **`LOG` 绝不能上行寻址编辑**——那会毁掉"永不删除 + 留痕 + 可重放"（隔壁 R-W1/W2/R-S6）。

### 5.2 地址与身份（按纪律分叉）

**`LOG` 纪律**（Q-I1 已裁：混合）：
- **地址由调用方定义**（不透明字符串）：Docs 只校验合法性（非空、无空白、无分隔歧义——按调用方给的规则表），**非法在写入侧就拒**（不留给读侧表现为"文档静默消失"）。★ R3a 落地的规则表（**实现自定，改它=改数据形状**）：长度 ≤ 200、禁 `#`、禁 `//`、禁空白。
- **条目身份由 Docs 派生**：`id = <地址>#<序号>`，序号由存储按地址维护（从 0 起、单调、日志行序 = 序号）；**同 base + 同载荷 ⇒ 同 id**（可重放）；撞 id 具名拒。
- **有序列** = `(地址, 序号数值)`，**按数值不按字符串**（字符串序在 `#10` vs `#2` 处会错；隔壁现状是字符串序，属其既有实现，本文按数值定并显式登记为差异）。

**`FILE` 纪律**：
- **虚拟路径即身份**（`/workspace/notes/2026-10-13.md`）；文件名就是身份（与 `LOG` 相反，不适用 B5）。
- Docs 只校验路径合法性（不含 `..`/绝对路径、段名合法）与 containment（落在挂载范围内）；**撞名 = 已存在** ⇒ 具名拒（不静默覆盖）。

### 5.3 存储形态（布局归 Workspace 的 resolver，不在这里）

★ **归属修正（N7）**：上一版把"布局 SPI"（`DocLayout`）放在 Docs 里——错了。**布局 = 虚拟↔物理的映射 = Workspace 的 resolver**（§4.4）。Docs 只声明"我用哪一个挂载做我的根"，不再拥有布局。

- **默认挂载形态（v1 一对一）**：`/workspace` ← `direct(<dataDir>/workspaces/<user>)`。★ 多物理根覆盖（种子 + store）**推迟**（N8）。
- **`LOG` 纪律的存储实现**：在解析出来的物理目录里，**每地址一个追加日志**（NDJSON，一行一条），文件名为地址的**安全编码** + 分片；★ **文件名只是索引，不是身份**——读回时以**记录内的地址**为准（答隔壁 §1.6 的"文档静默消失"，也是防"第二处拼写点"）。
  - **写入**：`append` 取该文件的排他文件锁 → 追加一行 → 释放；**同地址并发追加排队、不撕裂**；跨地址并发无冲突。
  - ★ **并发纪律（R3a 实测教训，R4 的回归钉）**：JVM 内按路径先排队（`FileChannel.lock()` 是整 JVM 级，重复获取抛 `OverlappingFileLockException`）+ 跨进程文件锁；**锁内的一切读写必须走同一个被锁住的通道**——最初实现"锁只包住写、锁内另开描述符 `Files.readAllLines` 读"，跨进程真跑（4 JVM × 40 次追加）**实测出现重复序号**；改成"读-改-写同在被锁通道内"后 160 行 / 160 个唯一序号、连续 `0..159`。
  - **读取**：顺序读 + 尾部读；坏行**不静默**（进 `broken` 披露并继续）。★ **`read`/`query` 不走文件锁**（只有写入路径的读-改-写在锁内）⇒ 跨进程写到一半时理论上可能读到坏行；已按"进 `broken`、不静默、下次读自愈"处理，登记为诚实边界（§9）。
  - **原子性**：追加是"一次写入一行 + `flush`"。
- **`FILE` 纪律的存储实现**：**普通文件**（虚拟路径 → 物理文件，一对一）。
  - **读取**：按行区间读（`lines="10,40"`）——省 token 的第一半。
  - **写入**：**临时文件 + `ATOMIC_MOVE`**（同目录），盘上永不出现"写一半的文档"；这是 Linux `sed -i` 的等价做法。
  - **重扫**：写完立刻对同一挂载内的后续读可见；坏文件**不静默**（进 `broken` 披露）。
- **IO 归属**（N5）：文件 IO 在 `docs-mosire`，不下沉到 `workspace-mosire`、更不在 `agentlib-mosire`。

### 5.4 条目模型与可见性（`LOG` 纪律）

```
DocEntry(
  id,                  // <地址>#<序号>（Docs 派生）
  address,             // 调用方定义（不透明）
  tick,                // 时刻（核心）
  ordinal,             // 有序列（存储维护）
  tags,                // 可见性轴 1：显式指派
  affiliations,        // 可见性轴 2：按归属
  owner,               // 可见性轴 3：单决策人私有（Optional）
  key, value,          // 正文（value 不透明：不解析、不改写）
  note,                // 备注（Optional）
  status,              // EFFECTIVE / VOIDED（核心，作废 = 追加一条 VOIDED）
  fields,              // 自定义字段区（Map<String,Object>：标量 + JSON 可表达的小对象）
  at                   // 世界坐标等注入值（Optional，走 fields 亦可；读回原样）
)
```

**可见性判据（唯一一处）**：

```
canSee(viewer, entry):
  viewer.seesAll == true                        → true    ← GM 无条件（U3）：在判据之前短路，不落 tags、不进判据
  viewer.actorId != null && entry.owner == viewer.actorId → true   ← owner-only
  entry.tags.contains(viewer.actorId)           → true
  viewer.affiliations ∩ entry.affiliations ≠ ∅  → true
  其余                                          → false   ← fail-closed（"无主 ≠ 大家都能看"）
  ★ viewer.actorId 解不出（空/未知）⇒ 跳过 affiliations 支，只走 tags（不许把"不知道你是谁"读成"按归属放行"）
  ★★ **控制方裁定（2026-10-13，R4 验证后）**：v1 实现比上式**更严**——身份解不出时 **tags 支也不匹配**（`DocVisibility` 用 `resolvable()` 一并挡住）。
     接受该更严语义（fail-closed；对外无实际差别：身份解不出本就是宿主未绑定身份），本节按**更严**口径读；R4 的用例已按实际行为钉住。
```

`fields` **不参与**身份、可见性、排序（隔壁 R-C4）。

★ **`FILE` 纪律没有条目级可见性**：文件能不能被看见 = **挂载 + 资源面**（`docs` 前缀）决定，不在文件内部再搞一套 tags/affiliations（避免两处判）。要"只给某些人看某份文档"，就挂不同的挂载/目录前缀。

### 5.5 读写契约（按纪律分叉）

**`LOG` 纪律**

| 面 | 契约 |
|---|---|
| 按时刻读 | `tick` 精确 与 `[fromTick, toTick]` 闭区间**互斥**；构造期具名拒 |
| 限流 | `limit` 必填 > 0；**先排后截** |
| 顺序 | `tick` 降序；同 tick 按 `(地址, 序号数值)` 升序 |
| 空结果 | **不抛**，返回空列表 |
| 精确取 | 按 `id` 取；**"取不到"与"不可见"返回同一个回答**（不泄露存在性） |
| 正文 | `value` 原样进出 |
| 写 | **append-only**：同 `(地址, key)` 再写是追加，不是覆盖；**永不删除**；作废 = 追加一条 `VOIDED`（留痕） |
| 并发 | 同地址并发追加不撕裂 |

**`FILE` 纪律**（N11-3：**能用 Linux 命令规范就用 Linux 命令规范，Agent 直接传整行命令，我们只做地址映射**）

| 面 | 契约 |
|---|---|
| 读（结构化，省 token） | `doc_read(mount, path, lines?)`：`lines` 缺省返回全文；给 `"10,40"` 只回该区间 |
| 写（**自实现 IO**，N13） | `doc_create`（原子创建 + 撞名拒）、`doc_edit`（行区间 / **唯一精确锚点**；一次多个操作**全成或全不成**）——同目录临时文件 + `ATOMIC_MOVE`，盘上永不出现半成品 |
| 路径 | 一律**挂载内相对路径**（经 Workspace 解析为物理路径；面向模型只出虚拟路径，A7） |
| 失败语义 | 具名拒且**盘上不动一个字**（锚点不命中/不唯一、撞名、越界、纪律不符） |
| 后续（**不在 v1**） | 命令面（Linux 命令 + 白名单）随 §8 ⑤ 的独立模块到来——草案见 §5.10 |

### 5.6 工具面与权限面

| 工具 | 纪律 | 面 | 说明 |
|---|---|---|---|
| `doc_list` | 两者 | `docs` | `LOG`：列地址 + 摘要；`FILE`：列路径 + 大小/mtime |
| `doc_read` | 两者 | `docs` | `LOG`：按 id / 地址读正文；`FILE`：按路径读，可带 `lines` 区间 |
| `doc_query` | `LOG` | `docs` | 按 tick / 区间查询（带 limit，先排后截） |
| `doc_append` | `LOG` | `docs-edit` | 追加一条（`destructive=true`） |
| `doc_void` | `LOG` | `docs-edit` | 作废一条（追加 VOIDED，`destructive=true`） |
| `doc_create` | `FILE` | `docs-edit` | **原子创建**（撞名拒、内容一次落定，`destructive=true`）——Linux 侧要 `printf > f` 或 `tee` 拼，我们自己做 IO 更可靠 |
| `doc_edit` | `FILE` | `docs-edit` | **结构化行寻址编辑**（区间 / **唯一精确锚点**；一次调用多个操作全成或全不成，`destructive=true`）——`sed` 在多行替换、中文与特殊字符转义、锚点唯一性上都不好用，这一段自己实现 IO |
| ⏳ **`bash`（挂载绑定，后续模块）** | `FILE` / `SCRATCH` | `bash` 能力（落 `fs` 面） | **N13：不在 v1**。后续模块给 `bash` 工具加 `mount` 参数 + 命令白名单（§5.10 草案）；不传 `mount` 时沿用今天的通用 shell 行为 |

★ **命名口径（N11-3 + N12 + N13）**：**v1 的 Docs 只做"文档操作封装"**——有 Linux 既有命令也先不接（N13：命令执行权限另开模块），因此 `FILE` 的读写全部由 Docs **自实现 IO**；`LOG` 全族本就没有 Linux 等价物。命令面（`bash(mount=…)` + 白名单）草案保留在 §5.10，作为后续模块的输入。

- 资源声明：读工具 `ResourceManifest.of("docs", DENY)`；写工具 `ResourceManifest.of("docs-edit", DENY)`；写工具 `ToolSpec` 标 `destructive=true`。
- 逐路径 `require`：`DocsAccess` 在真正读写前调 `context.resources().require(READ/WRITE, "docs"/"docs-edit", <路径>)`。
- 错误码：`RESOURCE_DENIED`（范围外/未表态，由唯一入口转码）、`DOC_NOT_FOUND`（**面内**不存在；与"不可见"**不**同码——注意旧文档 `设计-Skill运行时能力.md` §3 与 `SkillException` javadoc 把这条写反了，见 §10.2）、`DOC_EXISTS`、`INVALID_ARGUMENTS`、`DOC_INVALID`、`DOC_READ_FAILED`、`DOC_WRITE_FAILED`、`DOC_SCOPE_DENIED`（spawn 越界）。
- 权限形状：`docs` / `docs-edit` 两个命名空间，**一个命名空间 + 前缀集合（根 = 前缀）**；三档 fail-closed；`docs-edit ⊆ docs`。
- ★★ **工具族与工作区解耦（A8）**：`doc_*` 工具**不认识工作区**——它只按既有 `ResourceManifest` 判 `docs` / `docs-edit`。"这个 Agent 能不能用 docs 能力"是**工作区模块在挂载/授予时落格**的结果（§4.8）；工具侧**不再判一次**，也不允许自行放宽。将来 `bash` 能力同理：能力落 `fs` 前缀 + shell 可见位，**`bash` 工具**照旧按 `fs` 面判。

### 5.7 子 Agent 文档工作区（N4 / N9 的核心场景）

```text
spawn 时：父侧（holder=父）为子体（user=<实例id>）挂两个挂载 ——
  ① 子体自己的可编辑工作区（FILE 纪律，N9 主场景）：
     Mount(name="/workspace", holder=<父>, user=<实例id>,
           dir=<dataDir>/workspaces/<实例id>, resolver=direct(),
           kind=FILE, capabilities={"docs","bash"})
  ② （可选）只读参考文档（LOG 纪律）：
     Mount(name="/docs", holder=<父>, user=<实例id>, dir=<文档目录>,
           resolver=direct(), kind=LOG, capabilities={"docs"})
  → grant：`docs` 落 (docs ← dir, READ)[+ (docs-edit ← dir, WRITE)，仅 LOG 用]；
           `bash` 落 (fs ← dir, READ+WRITE)
  → 子体侧只认虚拟路径（/workspace/notes/x.md），物理布局不可见
  → ①里：`bash` 能力 + FILE 纪律 ⇒ 可写 ⇒ 用 Linux 命令自由编辑（`sed -i`/`tee`，§5.10）
  → ②里：LOG 纪律 ⇒ shell 只读/遮蔽；要追加只能走 `doc_append`（不可变语义）
```

### 5.8 不变量（B 组）

| # | 不变量 |
|---|---|
| **B1** | 身份拼法各只有一处：`LOG` 的地址归调用方、条目身份归 Docs；`FILE` 的虚拟路径即身份（调用方给路径，Docs 校验合法性与 containment） |
| **B2** | 可见性判据**只有一处**（`canSee`，`LOG` 用），GUI / 工具 / 库 API 全调它；`FILE` 的可见性 = 挂载 + 资源面，**不在文件内再判一次** |
| **B3** | 判据 fail-closed；GM 短路在判据**之前**且不落 `tags` |
| **B4** | `LOG`：追加式，无覆盖、无删除，作废是追加；`FILE`：行寻址编辑 + 原子写，**无删除**（v1） |
| **B5** | `LOG` 的文件名/分片**不是身份**（身份在记录内）；`FILE` 的**路径就是身份**（两者不混用） |
| **B6** | 非法地址 / 撞 id / 撞路径名 **在写入侧拒**（不留到读侧表现为"静默消失"） |
| **B7** | `LOG` 挂载不经 shell 写（与 A3 同一条，两侧各判一次） |
| **B8** | 写入必过校验与原子性；盘上不留半成品 |
| **B9** | **纪律是声明式的**：`LOG` 挂载上不得执行 `FILE` 操作，反之亦然——越纪律**具名拒**，不静默降级 |
| **B10** | `FILE` 的写**只经挂载绑定的命令面**：命令在解析出的物理目录里执行（cwd），**我们不解析命令文本**；`LOG` 挂载上执行命令一律拒（A3/B9） |
| **B11** | **（后续，随命令面模块）** 命令面复用 `shell-mosire` 的执行核心（围栏 / L3 沙箱 / 超时 / 输出截断只有一处实现）；`FILE` 挂载在沙箱内必须可写（否则 `sed -i` 会 `EROFS`）——**v1 无命令面，本条不生效** |

### 5.9 判据（B 组）

**正向（`LOG`）**：追加一条 → 按 id/地址读回；按 tick 与区间查询且**先排后截**；同 tick 顺序确定（含 `#2` vs `#10` 的数值序）；自定义字段（`worldid`、`{branch,revision}`）原样进原样出；GM 看见全部；可见者看见自己的 + tags/affiliations 命中的。
**正向（`FILE`）**：`doc_read(lines="10,40")` 只回该区间；`doc_create` 原子建文件并撞名拒；`doc_edit` 用唯一精确锚点改对位置且多项全成或全不成；写失败时**盘上不动一个字**（原子替换）；子 Agent 在自己的工作区里建/改/读成功。
**（后续，不在 v1）** 命令面的正向：在 `/workspace` 里执行 `sed -i 's/a/b/' notes/x.md` 后读到新内容；白名单内命令可跑、白名单外程序具名拒；cwd 生效且物理布局不可见（§5.10）。
**负向**：**不可见 ≡ 不存在**（同码、不泄露存在性）；身份解不出**不许**按归属放行；**无主文档对无标签者不可见**；非法地址/撞 id / 撞路径名 **写入侧就拒**；同地址并发追加**不撕裂**；范围外调用 ⇒ `RESOURCE_DENIED`；`value` 原样（不解析/不改写）；坏行/坏文件不静默（进 `broken` 披露）；**`LOG` 挂载上 `doc_append` 之外的文件写操作被拒**、**`FILE` 挂载上 `doc_append` 被拒**（B9）；**`doc_edit` 锚点不命中/不唯一 ⇒ 拒且盘上不动**（B10）；**v1 所有挂载在 shell 侧遮蔽**（A3/A8）；**`FILE` 删除（`rm`）v1 工具面里不存在**。
**（后续，不在 v1）** 命令面的负向：白名单外程序被拒、`;`/`&&`/`$()`/反引号/子 shell 被拒、重定向指向 cwd 之外被拒（§5.10.1）。

---

### 5.10 （**后续模块草案，不在 v1**）执行面：Linux 命令 + 白名单怎么落地

> ★ **N13 裁定**：v1 **不给 Docs 挂 bash 入口**——完整的 bash 命令权限"后面额外开模块搞，Docs 只管维护 docs"。本节因此**不进 v1 批次**（§8 ③ 明确排除 §5.10），保留为后续"命令面模块"（§8 ⑤）的设计输入：`bash` 能力、`bash(mount=…)`、命令白名单、`shell-mosire` 抽核都在那边一次做完。

**术语澄清（三层，别混用）**：

| 层 | 指什么 | 名字 |
|---|---|---|
| ① 工具（模型看到的那层） | Agent 调用面 | **`bash`**（现状：`ShellTool.NAME = "bash"`，schema = `command`/`cwd`/`env`/`timeout`；`BashPluginMosire/.../ShellTool.java:126`、`:806-832`）。**本期只加一个 `mount` 参数** |
| ② 执行核心（Java 库） | 进程 + 三层围栏 + `BashSandbox` + 超时/截断 | **`shell-mosire`**（**后续批次**从插件抽出，无工具 schema） |
| ③ OS 层解释器 | 真正跑命令的东西 | `/bin/bash -c`（现状不变） |

★ 本文其余处写的"shell"一律指**命令执行面**（落在 `bash` 工具 + `shell-mosire` 上），不是某个叫 `shell` 的工具——`shell` 这个名字在代码里**不存在**。

★ 本文早前草稿里写的 `shell(mount=…)` 是笔误，**正确写法 `bash(mount=…)`**——复用既有工具名，不新造工具。

**结构**（N11-3.1 裁定乙：抽执行核心成库）：

```text
shell-mosire（新库，从 BashPluginMosire 抽出）
  ├─ 进程启动 / 生命周期（复用 agentlib.proc 的 SubprocessManager）
  ├─ 三层围栏：L1 cwd 判定 + 符号链接解析、L2 路径越界拒、L3 BashSandbox（unshare/bind/chroot/setpriv）
  ├─ 超时 + 输出截断（ShellOutputTruncator）
  └─ executeIn(dir, command, env, timeout, fence)
        ▲                                    ▲
        │                                    │
BashPluginMosire（薄壳：配置 + schema + 注册）   DocsMosire（文件命令面：挂载 → cwd 映射）
```

**Docs 侧的一次命令调用**：

```text
bash(mount="/workspace", command="sed -i 's|old|new|' notes/x.md")
  ① 解析挂载名 → WorkspaceRegistry.resolve（能力/纪律/权限面判定，§4.5）
  ② 纪律检查：kind 必须 ∈ {FILE, SCRATCH}；`LOG` ⇒ 具名拒
  ③ 命令白名单检查（§5.10.1）：程序名与构造合法性
  ④ cwd := 解析出的物理目录；注入 $WS = 该物理根
  ⑤ shell-mosire.executeIn(cwd, command, env, timeout, fence)   ← 围栏/沙箱/截断全在这里
  ⑥ 退出码 + stdout/stderr 原样回灌（非零不吞）
```

三条纪律：
1. **地址映射只发生在"选挂载 → 定 cwd（+`$WS`）"**：命令内使用相对路径。
2. **`LOG` 挂载上永远不给命令面**（A3/B9）：追加型文档的不可变语义会被 `sed -i` 毁掉。
3. **执行核心只有一处实现**（B11）：`bash` 工具与命令面模块共用 `shell-mosire`，沙箱行为不会分叉。

#### 5.10.1 命令白名单（N12）—— 对上一轮"完全不解析命令"的修正

我上一轮写的是"**我们不解析命令文本**"。要加白名单，这条必须修正为**最小解析**：**解析到"能判定程序名"就够，判不定的构造一律拒**（fail-closed）。

| 项 | 规则 |
|---|---|
| 声明位置 | **随 `bash` 能力**（`Capability.allowedCommands`）——"给 bash 权限"天然包含"允许哪些命令" |
| 默认档 | **文档处理命令族**（`cat head tail sed awk grep sort uniq tr cut wc tee printf mkdir ls stat diff`…）；**没有"任意"这一档**，要更宽就显式列 |
| 空集 | **一条命令都不许**（fail-closed），不是"不限" |
| 解析范围 | 只做**词法切分**：取每个管道段的**程序名**；`|` 允许但**每段都要过白名单** |
| 一律拒 | `;`、`&&`、`||`、`&`、`$(...)`、反引号、子 shell `( )`、`eval`/`exec`/`sh -c`/`env`、以及任何**程序名不足以判定**的构造 |
| 重定向 | 允许写向 **cwd 内的相对路径**；绝对路径、`>` 指向挂载外、`/dev/*` 之外的设备文件 ⇒ 拒 |
| 上限 | 白名单**只能更窄**（M12/W6）：子工作区继承时取交集 |
| 配置 | 允许**收窄**（配一个更小的集合），**不允许**放宽到白名单之外；配置里出现"任意"取值 ⇒ 启动期响亮失败 |

★ 诚实声明：白名单是**降低误伤面**，不是"命令执行的安全边界"——真正的边界仍是 L3 沙箱（受限调用者一律沙箱）。白名单挡的是"能力被当成通用 shell 用"，不是挡恶意构造；两者都要在（§9.4）。

★ **适用范围**：白名单只管**挂载绑定的命令面**（`shell(mount=…)`）；**不绑挂载的既有 shell 工具行为本期不变**（它的收窄属于 §8 批次 ⑤ 的"默认工具面重写"），以免本期就动到既有 104 条 bash 用例的语义。

★ **工具形状（已定）**：v1 的唯一命令面 = **既有 `bash` 工具 + 新增 `mount` 参数**——不新造第二个跑命令的工具，也让"给 `bash` 权限就允许 bash 操作"（N9）在工具名上直接可读。**不传 `mount` ⇒ 行为与今天逐字不变**（通用 shell、走 `fs` 面），所以既有 104 条 bash 用例的语义本期不动。
   ★ **备选（若你要）**：把挂载绑定的执行收进 Docs 命名空间叫 `doc_exec`——同一实现换个工具名；但它会打乱"能力 ↔ 工具族"的一一对应（`doc_exec` 由哪个能力门控？`docs` 还是 `bash`？），我建议不做。

---

## 6. 前置批次：AgentLib 权限接口收编

### 6.1 收编内容

| 动作 | 位置 | 说明 |
|---|---|---|
| `SKILLS` / `SKILLS_EDIT` 常量**迁出** AgentLib | `ResourceScopeMap.java:41` / `:54` → `BrainMosire`（技能面自己的常量类） | AgentLib 只留通用（`FS` 保留）。★ **用户裁定：直接删，不留 `@Deprecated` 别名**（"管友军干啥"）——已核消费方 `~/SimulatorMosire` 对这两个常量**零引用**，删除安全；**类集合不减**，只减常量 |
| `SandboxMask` **命名空间无关化** | `SandboxMask.java:94-126`（读清单）、`:134-149`（判定）、`:156-166`（`skillsVisible`） | 清单条目由"裸路径"变为 `{namespace, path}`；判定改为按条目声明的命名空间取 `declaredScope(ns)` 再 `covers`。**旧裸路径条目不再兼容**（两个生产写端本期一起改，不留过渡分支） |
| 两个写端改形状 | `App.java:496`、`SubProcessExecutor.java:200` | 改由 `WorkspaceRegistry.maskEntries()`（**装配期静态清单**）产出条目；运行期新建的挂载按 M6 **不进**该清单 |
| bash 插件**本批次零改动** | `ShellTool.java:501` | 签名不变、调用不变（批次 ① 刻意保住；bash 的改造在批次 ② 抽核 + 薄壳化） |

### 6.2 下游与门禁

- 动 AgentLib ⇒ 按 §0.5 重建并到 `~/SimulatorMosire` 跑 `AgentLibAvailabilityTest`（钉 16 个 FQN + 类数下限 ≥118）；**常量级删除不影响类集合**（N10 裁定：直接删，不留 deprecated 别名）。
- 打包硬规则暂不变：`unzip -l MainMosire/target/mosire.jar | grep -i bash` 仍须 0 命中。

### 6.3 `io.mosire.brain.workspace` 两个占位接口

**判死并删除**（`Workspace.java` / `EditorTool.java`）：零实现、零引用；其实质内容（"读/找/改经同一处解析路径、围栏是第一等交付"）由 `workspace-mosire` + Docs 工具承担。删除时在 `设计-Bash工具与工作目录围栏.md` 与归档处留指针，不静默消失。

---

## 7. 附：对隔壁需求书的答复（**参考件，不是本项目的契约**）

> ★ **N11 裁定**：两个独立项目，本项目管好自己——**不按隔壁的需求裁剪本项目设计**。本节保留为"若隔壁来问，直接给这份"的答复稿；本项目的验收判据在 §4.11 / §5.9，**不以本节为依据**。隔壁需求书（`2026-10-13-agentlib-docs-requirements.md`）降级为**参考输入**。

| 问 | 答复 | 哪一方定 |
|---|---|---|
| **Q-S1** 存储根 / 在不在 revision | 根 = 调用方声明的目录（R-S2）；**不在世界 revision 内**（用户裁定）；世界坐标由调用方写入时注入、读回原样返回 | 用户已裁 |
| **Q-S2** 布局怎么声明 | 由 `workspace-mosire` 的 **Mount + Resolver** 承担（§4.4）：调用方声明"虚拟根 + 物理目录 + 映射策略"。**v1 只交付 `direct()`（一对一）**，自定义 resolver 由调用方实现并注册；多物理根覆盖/分片按 N8 推迟 | 我方 |
| **Q-I1** 身份定义权 | **混合**：地址（docId）归调用方、Docs 当不透明字符串并校验合法性；条目身份 `<地址>#<序号>` 归 Docs（存储维护序号、单调、可重放） | 用户已裁 |
| **Q-I2** 有序列 | 同 tick 内 `(地址, 序号数值)` 升序；序号 = 日志行序（确定、可重放） | 我方 |
| **Q-Q1** 按 tick 读 | 保留：`tick` 与 `[from,to]` 互斥、闭区间、构造期具名拒 | 我方 |
| **Q-Q2** 顺序与限流 | `limit` 必填 > 0；tick 降序 + 有序列升序；**先排后截**；空不抛 | 我方 |
| **Q-V1** 可见性维度 / owner-only | 三维：`tags` / `affiliations` / `owner`（owner-only 独立成轴，**不塞 tags**；该轴只属 `LOG` 纪律） | 我方 |
| **Q-V2** GM 无条件可见 | 身份档位**短路**：在判据之前 `viewer.seesAll` 返回 true；不落 `tags`、不进判据 | 我方 |
| **Q-V3** 身份解不出 | 跳过 affiliations 支，只走 tags；整体 fail-closed | 我方 |
| **Q-W1** 重复写 | 追加，不覆盖；无删除；作废 = 追加 VOIDED | 我方 |
| **Q-W2** 写入通道 | **两个门**：①库 API（隔壁工具面调，角色/审批归隔壁）②`doc_*` 工具面（走 `docs-edit` 资源面 + destructive + 既有审批链） | 我方（待确认） |
| **Q-W3** 世界坐标 | 走自定义字段（`at{branch,revision}` / `tick`）：注入即存、读回原样；我们**不解释**其语义 | 我方 |
| **Q-C1** 自定义字段 | `fields: Map<String,Object>`（标量 + 结构化小对象）；**不参与**身份/可见性/排序 | 我方（= 隔壁 R-C4） |
| **Q-B1** Agents.md 自动注入 | **本系统不做**（用户 N4 裁定）：自动注入归具体实现。本系统只保证"文档能被读到"，不碰任何人的 prompt 装配 | 用户已裁 |

**对隔壁 §4 归属表的唯一修正**：把"身份规则"整块划给 Docs 会让他们失去地址拼法的主导权；正确切法是——**地址拼法归 Simos（正文语义的一部分），条目身份的派生与唯一性归 Docs**（§5.2）。

---

## 8. 责任区、派单与实施顺序

> **制度来源**：本仓 [`AGENTS.md`](AGENTS.md) §2 = 隔壁 `~/SimulatorMosire/docs/agents/02-一-并发.md` §一.5（**用户 2026-09-27 裁定**）的搬运版。
> 原话：「**不是，为啥一个 M1 能拆这么多子任务，不是先写代码和过编译，等最后再搞测试吗**」「**也没让你自己写，是让你派子代理写代码、过编译，然后最后你搞测试，具体也是派测试 Agent 盯着**」。

### 8.0 本次实施照此执行（写死，不逐条解释）

1. **责任区 = 一个完整、可编译、可独立验收的目标/问题域**；切法按"可独立编译的层"，**不按计划编号逐条拆**；阶段内部**不再逐任务派单**（同一个代理在一个上下文里写完，才看得见各任务之间的耦合）。
2. **一个责任区 = 一个写代码 Agent**，一次把该责任区的**全部生产代码**写完；**同一时刻只允许一个写代码 Agent**（只读调查可并行）。
3. **写代码 Agent 三条**：① 只写生产代码，写到 `./mvnw -DskipTests compile` 过；**不写/不改任何测试文件**、**不跑 `test`/`verify`/`package`**；② **不 `git commit`**（控制方审后提交）；③ 报告必须点名：**哪些改动改变行为**、受影响的**硬编码字面量清单**、会让**既有断言失效**的清单。
4. **测试最后统一派测试 Agent**（责任区 R4）：按**判据清单**逐条落实（§4.11 / §5.9），**必须含负向用例**；关键项**变异自证**（改坏⇒当场红⇒还原⇒比 md5），做不到的如实说"等价存活"；报告必须有**"我没做/没验证的"**一节。
5. **compile 只是进入下一个责任区的门槛**，不是完成标准；最终验收 = 真实行为证据 + 负向验证（§4.11 / §5.9）。
6. **评审按风险触发**：命中"判定/权限、进程/线程生命周期、共享件、结构性→判定性降级"才派独立评审；命中即**先提交后评审**、与下一块并行；单模块评审 **≤3 轮**，不收敛由控制方当场裁定并把未决项记成带裁定的遗留条目。
7. **控制方不写生产代码**（除用户明确要求或单点小修，且须在报告里说明为什么没派单）。
8. **子 Agent 可挑战设计**：与用户原话/红线冲突或有歧义 ⇒ **停手上报**；纯技术分歧 ⇒ 控制方当场裁定并落文档；纯实现困难 ⇒ 给最强 fail-closed 方案并实现，任务保持 **BLOCKED（不是 DONE）**。

### 8.1 责任区表

| 责任区 | 目标/问题域（可独立编译的层） | 交付物 | 门槛（进下一区） | 判据来源 | 文件所有权 |
|---|---|---|---|---|---|
| **R1 AgentLib 收编** | 让 `agentlib-mosire` 只暴露**通用权限接口** | `SKILLS`/`SKILLS_EDIT` 常量迁 `BrainMosire` 并**直接删别名**；`SandboxMask` 命名空间无关化（清单条目带 namespace）；两个生产写端改形状；删两个占位接口 | 全仓 `-DskipTests compile` 过；**行为逐字不变**（既有用例语义不动） | §6.1 / §6.3 | `AgentLibMosire/.../agentlib/{permission,tool}/**`、`BrainMosire/.../brain/skills/**`（常量）、`MainMosire/.../main/app/App.java`（:496）、`BrainMosire/.../subagent/SubProcessExecutor.java`（:200）、`BrainMosire/.../brain/workspace/**`（删） |
| **R2 workspace-mosire** | 工作区标准：虚拟↔物理映射 + 解析点鉴权 + 能力 + 子工作区 | 新模块与三个工具（`mount_workspace`/`unmount_workspace`/`list_workspaces`） | `compile` 过；模块自洽（§4.11 判据在 R4 统一验） | §4 全部（§4.11） | 新模块 `WorkspaceMosire/**` + 根 `pom.xml` `<modules>`（**与 R3 不同文件，无重叠**） |
| **R3a docs-mosire 库与工具面** | Docs 引擎：`LOG` 全链 + `FILE` 自实现 IO + 库 API + `doc_*` 工具面 | 新模块 + 7 个工具 | `compile` 过 | §5.1–§5.6、§5.8、§5.9（**除 §5.10**） | 新模块 `DocsMosire/**` |
| **R3b 装配与 spawn 接线** | 把 Docs/Workspace 接进真实链路 | 主 Agent 装配（工具注册、遮蔽面、`ContextSources` 不新增层）+ 子体权限物化 + argv + 子进程恢复 + 子体工作区挂载 | `compile` 过；**不新增上下文层**（N4） | §5.7、§4.7、§4.9 | `BrainMosire/.../subagent/{SubagentManager,AgentCommand,SubagentOrchestrationTools}.java`、`MainMosire/.../{Main,app/App,agent/SubagentProcessMain}.java` |
| **R4 统一测试**（测试 Agent） | 按判据清单一次性验收 R1–R3b | 用例 + 变异自证记录 + "我没做的"报告 | 全仓 `./mvnw verify` **本轮真数**（Spotless/Checkstyle/SpotBugs/Surefire 全绿） | §4.11 + §5.9 全量 | 各模块 `src/test/**` |
| R5（后续，**独立模块**） | 命令面模块（N13）：`shell-mosire` 抽核 + bash 薄壳化 + `bash(mount=…)` + 白名单 + `bash` 能力 | — | — | §5.10 草案 | — |
| R6（后续） | 技能重基（注册 `docs` 能力 + frontmatter 校验器） | — | — | §5.1 | — |
| R7（后续） | 其余默认工具按 workspace 重写；插件/子体目录迁入工作区登记 | — | — | — | — |

**依赖顺序**：R1 → R2 → R3a → R3b → R4（→ R5/R6/R7）。R2 与 R3a **不并行**——同一时刻只允许一个写代码 Agent（§8.0-2）；且 R3a 要用 R2 冻结的接口。

### 8.2 派单书模板（自包含，缺一不可）

每份派单书必须含：
1. **责任区编号 + 目标一句话 + 交付物清单**；
2. **设计文档路径 + 具体小节号**（`设计-工作区标准与Docs系统.md` §X.Y），并把**关键形状原样复述**（接口签名、不变量编号、错误码）——不能只给链接；
3. **文件所有权**（可写范围）与**禁止触碰**清单；
4. **三条铁律**（§8.0-3 原文）；
5. **门槛命令**：`./mvnw -DskipTests compile`（**不要**跑测试/`verify`）；
6. **判据来源**（供其自检与给 R4 当输入）：§4.11 / §5.9 的条目号；
7. **停手上报口径**（§8.0-8）；
8. **报告格式**：改了哪些文件 / 行为变更点 / 硬编码字面量 / 会让既有断言失效的清单 / 未决问题。

### 8.3 勾稽

- 判据不写在派单书里另造——**一律引用设计文档的条目号**（§4.11 / §5.9），保证"测试 Agent 按判据清单验"时与设计同源。
- R4 的输入 = §4.11 + §5.9 **全量** + R1–R3b 各写代码 Agent 报告的"行为变更/失效断言"清单。
- 动 AgentLib 的责任区（R1）与最终验收（R4）额外跑下游可用性检查（§0.5）。
- **R1 → R3b 的交接项**：R1 落地时遮蔽面两个写端先写**字面** `("skills", <技能根>)`（技能面本期不迁 docs）；**R3b 必须改为由 `WorkspaceRegistry.maskEntries()` 产出**（§4.8 的能力落格）。
- **R2 → R3b 的交接项**（R2 实现时发现，控制方裁定）：
  1. **M1 对装配期挂载同样生效** ⇒ 装配方（`App`）必须给主 Agent 的权限集**显式加上 `workspaces` 前缀**——★ **R3b 实测修正**：前缀口径不是"`<dataDir>/workspaces`"一条，而是**装配期挂载的落点根集合**（R3b 落成 `{workspacesRoot, skillsRoot}`）；只写工作区根会让技能根的装配期挂载被 M1 拒。
  1b. ★ **文档面必须先表态再挂载**（R3b 实测补充）：主 Agent 的 `docs`/`docs-edit` 面在装配期没有来源（不是继承来的），必须由装配方**显式声明**，否则挂主工作区会被 M1/M3 拒（"能力落格后必须仍 ⊆ 调用者面"）。
  2. **无能力的挂载不产出遮蔽面条目、也无法被 `resolve`**（谁都不持有该能力 ⇒ `RESOURCE_DENIED`）。要让技能根保持 R1 之前的遮蔽语义，装配方需注册一个**只含 `skills: READ`** 的能力并声明在技能根的 `REFERENCE` 挂载上；**不要**做成 `{skills, skills-edit}` 双命名空间，否则 G7 逐条目求与会把"可读不可改"的调用者一并遮蔽。
  3. **W3 深度闸不在工作区模块**：它复用 `subagents.maxDepth`，落在派生链（R3b 的 spawn 侧）；工作区模块只保证"真子目录 + 血缘子树"。
  4. **`resolve` 不检查物理目录是否存在**（解析是纯映射；IO 由消费方 fail-closed 报错），`list` 对缺失/非目录返回 `WORKSPACE_NOT_FOUND`。
  5. **`unmount(name, caller)` 只有名字一个定位键**：实现取"先自己切片；再'由自己创建'的跨切片挂载（须唯一）"；**祖先直接点名卸子体挂载**走不到——用 M10 级联（卸父自己的挂载即带走子体的）表达。
- **R3a → R3b 的交接项**（R3a 实现时发现，控制方裁定）：
  1. **`doc_list` 在 `FILE` 挂载上经 `WorkspaceRegistry.list`**（列直接子项是投影的职责，Docs 不重写）：R3b 装配时**必须实测一次**该方法的准入判定与 `docs` 声明面是否一致；若冲突 ⇒ **改 `WorkspaceMosire`（停手上报后由控制方裁定）**，不要在 Docs 侧放宽。
  2. ~~**虚拟路径同时落在两个挂载范围内 ⇒ 具名拒 `INVALID_ARGUMENTS`**~~ **【已被 2026-10-13 R5 裁定取代，见 §4.2】**：物理目录嵌套（`workspaces` ⊃ `workspace`）是 W1 的正常形态，**不构成歧义**；归位只按虚拟挂载名 + 使用者切片。原文保留为历史留痕（R3a 当时的实现选择）。
  3. `DocsApi` 与 `DocsEngine` 有方法重叠：**`DocsApi` 是"库 API 门"的稳定出口，`DocsEngine` 是内部引擎**；R3b 接线用 `DocsApi`（工具面内部走 `DocsEngine`，两者同一实现）。
  4. 运行时 `docs` 能力由 **`DocsCapability.docs()`** 定义、经 `CapabilityCatalog.register(...)` 注册（R3b 装配时做）；`DocsCapability.Manifests.READ/WRITE` 是工具声明面的唯一出处。
- **R3b → R4 的交接项**（R3b 实现时发现，控制方裁定）：
  1. **两条必红（读码取证，未跑）**：`MainMosire/.../app/AppMcpLinkTest.java:59-61` 与 `:96-98` 的"工具面全等"断言——主 Agent registry 从 11 → **21**（+7 docs +3 workspace），期望集合必须由 R4 扩到新集合（**收紧而不是放宽**：仍是全等）。
  2. **SpotBugs 首轮盯一条**：`SubagentManager` 新增 12 参终态构造上有 `@SuppressFBWarnings("EI_EXPOSE_REP2")`；若 verify 报 `US_USELESS_SUPPRESSION_ON_METHOD` ⇒ 删该注解（一行）。
  3. **真子进程 E2E 未跑**：`W3SubagentE2ETest`、`SubagentSkillE2ETest`、`SubagentConfigDirWiringTest` 是否仍绿，由 R4 跑。
  4. **注意事件库新增三类事件**：主事件库会多出 `workspace.mounted/unmounted/denied`——任何"遍历事件库并假定类型集合"的用例要一并核。
  5. **R3b 的冒烟在仓外**（`/tmp/r3b-harness`，非测试文件）：它当场抓到两处实现缺陷（workspaces 前缀口径、文档面须先表态），可作为 R4 写用例的输入。
- **R1 的已知红（判给 R4）**：`AgentLibMosire/src/test/java/io/mosire/agentlib/tool/SandboxMaskTest.java` 的 fixture/断言按**旧形状**写就（`pathsOf` / `skillsVisible` / 裸路径条目），而设计**有意**改了形状并删了这两个 API ⇒ 该文件**整套按新形状重写**属 R4（统一测试责任区）的活；R1 的门槛判据是 `-DskipTests compile` 绿 + 其余测试源 `test-compile` 绿（R1 用 `-Dmaven.compiler.failOnError=false` 诊断跑证明），**该红登记为带裁定的遗留项**。

---

## 9. 风险与已知缺口

1. **两个新模块 + AgentLib 收编 = 三次公开面变化**：下游 `~/SimulatorMosire` 每次都要重建并跑可用性测试；`~/.m2` 是每台机器各自的。
2. **"可扩展自定义空间"是双刃**：resolver 一放开，越权后门与"第二处拼写点"同时进场——防线是 G1（解析后 containment）、G2（只能缩小不能放大）、G3（虚拟拼法标准定死）、A7（模型只见虚拟路径）与 B5（文件名不是身份）。
3. **并发只在同主机、可加文件锁的文件系统上成立**（NFS/多机不在承诺内）。
4. **非 root 部署下 L3 沙箱遮蔽的真跑仍未验证**（既有缺口，`AGENTS.md §0.3` 已知项）；本文不改变该状态。
5. **`LOG` 永远不给 shell 写**：需要 shell 写就往 `FILE`/`SCRATCH` 挂载里放（而 v1 连这条也没有——见 7）。
6. **技能面本期不动**：`skills` 命名空间常量迁出后，行为必须逐字不变（104 条 Bash + Brain 技能用例作回归钉）。
7. **v1 推迟项清单**（登记，不丢）：多物理根覆盖（overlay）、分片 resolver（sharded）、嵌套挂载（§4.2）、运行期挂载进 bash 遮蔽面（M6）、挂载的物理清理与配额/用量钩子、**`FILE` 的删除（`rm`）与 `doc_patch`**；跨批次推迟：**命令面模块（§5.10 草案 + `bash` 能力 + 白名单 + `shell-mosire` 抽核，§8 ⑤）**、技能重基（§8 ④）、其余默认工具重写（§8 ⑥）。
8. **（后续批次的风险，先记）抽 `shell-mosire` 的账**：要动 `BashPluginMosire` 的 7 个源文件 / 3386 行与 104 条用例的归属，两份 bash 设计文档（围栏 22KB + 插件化 17KB）需要同步指向新库；`unzip -l mosire.jar | grep -i bash` 的硬规则继续有效，但"宿主 main 零 bash 类引用"要改成"零 **bash 插件**类引用"。
9. **（后续批次的风险，先记）命令白名单不是安全边界**：最小解析（§5.10.1）挡的是"把 `docs` 能力当通用 shell 用"，**不是**挡恶意构造；真正的边界仍是 L3 沙箱（受限调用者一律沙箱），而非 root 部署下这类调用者本来就会 `SANDBOX_UNAVAILABLE`。
10. **登记的挂载表与磁盘可能不一致**：目录被外部进程删掉时，解析会 fail-closed，但登记不会自动清理（需要一次显式核对）；v1 只保证"不回落、不静默"。
11. **递归工作区的回收**：父工作区卸载后子挂载级联失效（M10）；但已写在子工作区里的**数据仍在盘上**（不随卸载删除）——这是刻意的（文档是数据），也是需要使用者知道的行为。
12. **N13 收缩的代价**：v1 的 `FILE` 文档只能经 `doc_create`/`doc_edit`/`doc_read` 操作，**没有 `grep`/`sort`/`sed` 这类便利**（命令面在后续模块）；且 `FILE` 挂载在 shell 侧遮蔽 ⇒ 想用 shell 处理文档，v1 做不到。**这是有意的取舍**（命令权限要独立模块评审），不是遗漏。
13. ★ **用户可见的行为变化（R3b 装配后，必须对账）**：主 Agent 的 `bash` 现在对 `<dataDir>/workspaces{,/main}` 拿到**只读 bind**（此前这些目录根本不在遮蔽面清单里 ⇒ 未绑定、可写），对技能根仍是只读 bind。这是 §4.8/A3 的既定落点（v1 无 `bash` 能力 ⇒ 文档只经 Docs 工具写），但它**确实收窄了主 Agent 用 shell 写自己工作区的能力**——要不要开这个口子（把工作区根从遮蔽清单里挪出，或等命令面模块给 `bash` 能力）由用户裁定。
14. **子工作区装配失败时的处置**（R3b 自定，控制方接受）：**不让 spawn 失败**——把子体的 `docs`/`docs-edit`/`workspaces` 收成显式 `none()`，落 `permission.denied` + `workspace.denied` 两条事件 + WARN。判据是"fail-closed 且在模型面可见（工具直接 `RESOURCE_DENIED`）"；若要"挂不上就拒 spawn"，改一处即可。
15. **M6 的落地口径**（R3b 确认）：子体的**运行期**挂载（它自己的工作区，`Origin.RUNTIME`）**不进它的链路遮蔽面** ⇒ 子在 shell 里看不见自己的工作区（实测 `child fence = masked:[workspaces, workspaces/main], readOnly:[skills]`）。这与 M6/A3 一致：v1 的文档写只经 Docs 工具。
16. ★ **坏数据披露未按观看者收口**（R4b 发现；**R6 已修**）：`broken` 收口到 `DocViewer`——披露只列调用者**看得见**的地址（与 B2/§5.5 的"不可见 ≡ 不存在"一致）；`DocsTools` 传它自己那一份 viewer。
17. **FILE 挂载的坏文件不在引擎层归集**（R4b 裁定维持现状）：坏文件只在"读这一个文件"时观察得到（`DOC_READ_FAILED` 响亮 + `DocReadResult.broken()`）；引擎层要归集就得每次 `doc_*` 读全工作区，代价不可接受。
18. **`broken()` 的"扫不动 ⇒ 跳过"**：跳过 + `LOG.debug`（主操作照常返回，不把披露问题升级成操作失败）；要 fail-closed 传播是一行的事。
19. **R4 未覆盖的判据**（逐条见 R4 报告 §7）：M12 白名单（v1 退化）、§5.10 命令面整段、`DOC_SCOPE_DENIED`（全仓测试 0 命中）、App/事件库层的三类 `workspace.*` 事件落库、W2 的"装配方把面配大"、孙代派曾孙、**真子进程 E2E**、resolver 扩展点（N8 推迟项）、`doc_query` 未走工具面。
20. ★ **A7 的既有缺口（R6a 发现，登记待修）**：**资源前置闸**判否时，`AgentLibMosire/.../permission/ResourceAuthorizer.java:107` 的文案会把**物理路径**带到模型面。工作区/Docs 工具**自己**的拒绝文案不含物理路径（已验），但这条 AgentLib 文案仍会出模型面 ⇒ 归 AgentLib 的收口项（本次未动 AgentLib）。
21. **`list_workspaces` 会把"零能力的挂载"也列为 parent 候选**（R6a 登记）：按 §4.8/A8，无 `docs` 能力的挂载 `resolve` 会被拒 ⇒ 列出来但用不了。要精确标注得在工具里试解析（可能产生 denied 事件），v1 不做。

---

## 10. 待用户裁定 / 已发现的旧文档错误

### 10.1 裁定台账

| # | 事项 | 状态 |
|---|---|---|
| 1 | `owner` 命名撞车 | **已裁（N11）**：挂载侧 = `holder`（**创建者**，归属 + 生命周期）+ `user`（使用者，解析按它索引）；条目侧保留 `owner`（单决策人私有轴） |
| 2 | 两个门是否都给 | **已裁（N11）**：**都给**——库 API + AgentTool 面；理由是本项目自己就需要两种形态（代码调用 + Agent 工具），与隔壁无关 |
| 3 | 执行面怎么复用 bash 包 | **已被 N13 覆盖**：v1 不做（命令面另开模块）；抽核方案（乙：`shell-mosire`）保留为 §8 ⑤ 的输入 |
| 4 | 结构化写工具要不要 | **已裁（N12 语义 + N13 范围）**：**v1 的 `FILE` 全靠自实现 IO**——`doc_create` / `doc_edit` / `doc_read`（§5.6） |
| 5 | 命令面形状 | **推迟（N13）**：候选是"既有 `bash` 工具 + `mount` 参数"（推荐）或"Docs 自有 `doc_exec`"；随 §8 ⑤ 模块一起定 |
| 6 | ~~`bash` 能力默认档白名单~~ | **推迟（N13）**：白名单随命令面模块一起定（草案在 §5.10.1） |

**已裁（原话/选项）**：`SKILLS` 常量直接删（N10）；v1 文档纪律两族都做（§5.1）；v1 单物理根（N8）；运行期可挂载（§4.7）；子工作区递归（N12）；不在 revision 内；身份混合；不做 Agents.md 自动注入（N4）；**v1 不挂 bash 入口、Docs 只自实现 IO（N13）**；命令面/白名单/`bash` 能力/`shell-mosire` 抽核 → 后续独立模块（§8 ⑤）。

### 10.2 已发现的旧文档错误（本文回头修，不静默）

**D1（机制级）**：`设计-Skill运行时能力.md` §3 与 `BrainMosire/.../skills/SkillException.java:18` 均写"不存在与不可见同码 `SKILL_NOT_FOUND`"——**与代码不符**。实际：范围外（含未表态）⇒ `RESOURCE_DENIED`（`SkillAccess.requireRead` `:427-435` → `ToolCallAuthorizer:172-178`）；**面内不存在** ⇒ `SKILL_NOT_FOUND`（`SkillAccess:404-405`）。安全性质（无存在性 oracle）成立，但机制描述错了，且 `AGENTS.md §0.3` 同句照抄。**本文 §5.6 按代码口径写**；旧文与 AGENTS.md 的那两句需另行更正。

**D2**：`SubagentProcessMain.java:279` 用 `new SkillCatalog()`（恒 4096）做子体目录档预算，argv 不带 `skills.listingBudget` ⇒ 该项配置在子体内失效。**R6 修**（新增 `--skills-listing-budget` 旗标，值与 `--skills-dir` 同源从装配层下发；子进程缺省仍回落 4096 以保持兼容）。

### 10.3 转引自隔壁需求书的"未核"项（二手，尚未在 Simos 侧核实）

`sd:adjudication.*` 是否走同一 docId 判据；`simos.sd.report` 的收件可见性与 `docs()` 是否同一处；GUI 文档子页对 `at{branch,revision}` 的实际消费点；一个 store 内多份文档库的情形。**这些由 Simos 侧核，本文不据此下结论。**
