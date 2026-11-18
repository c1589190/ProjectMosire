# 设计：Skill 运行时通用能力

> 范围：把既有的 `Skill` / `SkillCatalog` / `SkillLoader`（半成品）接成**主 Agent 与任意子 Agent 共用的通用能力**：
> 工具面、可见/可编辑权限、上下文预注入、子 Agent 接线、创建/编辑、硬边界。
> 状态：已实现并本地验证（`./mvnw -DskipTests compile` + 全量离线用例；见文末“验证记录”）。
> 决策口径一旦与代码冲突，以本文为准并回头修代码——本文是这套能力的契约说明点。

---

## 1. 组件地图（新增/改动）

### 1.1 新增

| 组件 | 模块 | 职责 |
| --- | --- | --- |
| `io.mosire.brain.skills.SkillAccess` | Brain | **技能能力的唯一执行面**：可见面内列表/检索/读正文/读资源，可编辑面内创建/修改；写前校验、原子替换、写完重扫 |
| `io.mosire.brain.skills.SkillTools` | Brain | 6 个 `AgentTool`（`skill_list` / `skill_search` / `skill_load` / `skill_read_resource` / `skill_create` / `skill_edit`）+ 资源声明 + 事件落账口径 |
| `io.mosire.brain.skills.SkillPermissions` | Brain | 两个技能面（`skills` / `skills-edit`）在权限集第 7 维上的读写、求交、argv 编解码、名称形态 |
| `io.mosire.brain.skills.SkillException` | Brain | 权限之外失败的稳定错误码（不存在/已存在/参数非法/校验失败/读写失败/重扫失败） |
| `io.mosire.brain.skills.SkillSettings` | Brain | 配置 `skills.*` → 装配参数（根目录 / 目录档预算 / 预载名） |
| `io.mosire.agentlib.tool.SandboxMask` | AgentLib | 宿主下发的“沙箱遮蔽面”读取 + **技能树围栏判定**（遮蔽 / 只读 / 是否必须沙箱） |
| `io.mosire.brain.subagent.SkillScopeDeniedException` | Brain | spawn 请求面技能范围越界（工具码 `SKILL_SCOPE_DENIED`） |

### 1.2 改动（要点）

| 文件 | 改动 |
| --- | --- |
| `SkillLoader` | 抽出 `parse(String, Path)`：**写入校验与读盘共用同一段解析**（`readMetadata`/`readBody` 改为经它） |
| `SkillCatalog` | `listing(Predicate)`（可见集上算预算/淘汰）、`lookup(name)`、`scanTolerant` + `broken()`（宽容扫描，坏技能只影响它自己且被披露） |
| `ContextSources` | 技能来源类型 `SkillCatalog` → `SkillAccess`（可见面成为**必需参数**，不再是隐含“全可见”） |
| `BasicContextAssembler` | 预载只在可见集上遍历（`visibleSkills` + `preloadBody`），目录档 `listing(filter)` |
| `AgentRuntime` | 新增两个公开构造（`AgentSpec`+自定义 `ContextAssembler`；全参含 store/审批），供装配层接线内容源 |
| `ResourceScopeMap` | 新增命名空间常量 `SKILLS` / `SKILLS_EDIT` |
| `AgentTemplate` | 新增 `allowedSkills` / `editableSkills`（模板**建议**）+ 16 参兼容构造 |
| `SubagentLaunchRequest` | 新增 `allowedSkills` / `editableSkills`（请求**要求**）+ 9 参兼容构造 |
| `SubagentManager` | `effectiveScopes` 物化两个技能面（调用者 ∩ 模板 ∩ 请求；可编辑面再夹进可见面）；请求越界 → `SkillScopeDeniedException` + `permission.denied` |
| `SubagentOrchestrationTools` | `spawn_sub_agent` 新增 `allowedSkills`/`editableSkills` 入参；`SKILL_SCOPE_DENIED` 独立错误码 |
| `AgentCommand` | 新增 `skillsDir` 与 `--skills-dir` / `--skill-visible` / `--skill-editable`（值从实例权限集现算） |
| `SubProcessExecutor` | 子体链接上下文补 `sandbox.maskedPaths`（技能根），使 bash 按**该子体的**可见面决定遮蔽/只读 |
| `ShellTool`（Bash 插件） | 技能树围栏接线：沙箱内遮蔽/只读；该遮蔽却没有沙箱 → `SKILL_SCOPE_UNFENCED`（fail-closed，不发命令） |
| `BashSandbox`（Bash 插件） | `wrap(...)` 支持 `readOnlyPaths`/`maskedPaths`（遮蔽项最后 bind，用空目录盖住） |
| `App` (Main) | 技能面装配：配置 → 快照 → 主 Agent 两个面 → 注册 6 个工具 → 上下文来源；子进程命令注入技能根；启动日志 |
| `Main` / `SubagentProcessMain` | `agent` 子命令解析/接线技能三项参数；子进程按 argv 恢复可见面并做目录档预注入 |

---

## 2. 权限模型（写死的语义）

### 2.1 两个命名空间，粒度两档

技能范围落在**既有第 7 维** `AgentPermissionSet.resourceScopes`（不新增权限字段）：

- `skills` = **可见面**（能列出/检索/加载谁）；
- `skills-edit` = **可编辑面**（能创建/修改谁）。

前缀语义就是 `ResourceScope` 的通用前缀语义，落格的是技能文件系统路径，于是两种粒度共用一套判定：

| 粒度 | 写法 | 判定 |
| --- | --- | --- |
| 一个 Skill 文件夹（子树） | 该文件夹的绝对路径 | `ResourceScope.contains`：候选等于前缀或其下 |
| 单个 Skill 文档 | `<...>/<name>/SKILL.md` 的绝对路径 | 同上（候选恰好等于前缀；同目录的 `references/` 不再命中） |

### 2.2 三档取值（`null` / 空集 / 通配）

| 取值 | 含义 | 依据 |
| --- | --- | --- |
| **未表态**（权限集里没有这个命名空间） | **一个技能都看不见/改不了**（fail-closed） | 技能工具声明的缺省策略是 `ResourcePolicy.DENY`；`ResourceScopeMap` 的“未声明 = 本层不表态 ⇒ 回落下一层”落到的下一层正是 DENY |
| **显式 `none()`**（空集） | 同上，但它是**表态**：单调性守卫看得见它，子级不能借“没表态”洗掉 | `ResourceScope.none()` 与 `unlimited()` 是两种取值，不互相退化 |
| **显式 `unlimited()` / 前缀集** | 全可见（或给定前缀内可见） | 必须显式写；主 Agent 的装配就是写“配置的技能根” |

> **不存在“缺省静默放大”**：没配技能 = 没技能；要全可见必须有人明确写 `*` 或写根路径。
> **不变式：`skills-edit ⊆ skills`**（`SkillPermissions.withScopes` 在写入时求交、`SubagentManager` 物化时再求交）。
> “改得动但看不见”在结构上造不出来。

### 2.3 逐层传播（多级 spawn）

spawn 时子体的两个面 = **调用者 ∩ 模板建议 ∩ 请求**，然后可编辑面再与最终可见面求交：

```
effectiveVisible  = callerVisible  ∩ template.skillVisibleScope() ∩ requestVisible
effectiveEditable = (callerEditable ∩ template.skillEditScope() ∩ requestEditable) ∩ effectiveVisible
```

- **模板**（`AgentTemplate.allowedSkills` / `editableSkills`）= **建议**：`null` = 不额外收窄；`[]` = 显式“一个都不许”；超出调用者 ⇒ 静默求交（通用配置被上级收紧是常态）。
- **请求**（`SubagentLaunchRequest` / `spawn_sub_agent` 的 `allowedSkills` / `editableSkills`）= **要求**：`null` = 不指定；`[]` = 显式“一个都不许”；**超出调用者可达面 ⇒ 响亮拒**（`SkillScopeDeniedException` + `permission.denied` 事件，工具码 `SKILL_SCOPE_DENIED`），任一方都不静默截断。
- **物化而非“运行时往上问”**：子体的两个面逐条落进它自己的权限集数据；`PermissionChecker.isSubset` 因此能逐命名空间比对（红线 1 复用既有实现，没有第二套守卫）。孙代再 spawn 时对照的是**子体自己的**权限集（它经 MCP 链接带过来），所以“逐层只减不增”在任意深度成立。
- **未表态的调用者**：即使模板写了技能范围，交集为空 ⇒ 子体 `none`（模板不能把父级没有的东西“要”过来）。

### 2.4 判定链（工具调用侧）

```
ToolExecutionGuard（工具名级：白名单/拒绝名单/等级/敏感破坏位）
  → ResourceAuthorizer 声明式前置闸（工具 manifest：读 = skills/DENY，写 = skills-edit/DENY）
  → 逐路径 require（SkillAccess 在真正读写前调 context.resources().require）
  → 执行
```

三段都在 `ToolCallAuthorizer`（工具调用唯一入口）内，MCP 桥与管线共用；工具的 `spec()` 对写工具标 `destructive=true`（模板要显式 `destructiveAllowed` 才给子体编辑权）。

---

## 3. 工具面（公开入口）

| 工具 | 入参 | 返回（JSON 文本） | 面 |
| --- | --- | --- | --- |
| `skill_list` | 无 | `{count, skills:[{name,description,allowedTools,editable,directory}], broken?}` | 可见 |
| `skill_search` | `query`（必填）、`limit`（缺省 10，上限 50） | `{query,scanned,hits:[{name,description,matchedIn,snippet}],unreadable?,broken?}` | 可见 |
| `skill_load` | `name`（必填） | `{name,description,allowedTools,directory,body}` | 可见 |
| `skill_read_resource` | `name`、`path`（技能目录内相对路径，均必填） | `{name,path,content}` | 可见 |
| `skill_create` | `name`、`description`（必填）、`body?`、`allowedTools?` | 新技能元数据 | 可编辑 |
| `skill_edit` | `name`（必填）+ `description?`/`body?`/`allowedTools?` 至少一项 | 改后元数据 | 可编辑 |

- **错误码**：`RESOURCE_DENIED`（权限面，唯一入口转码）、`SKILL_NOT_FOUND`（**仅"面内不存在"**——★ **2026-10-13 更正**：原文写"不存在**或不可见**——两者刻意同码"，与代码不符：**范围外（含未表态）走 `RESOURCE_DENIED`**（`SkillAccess.requireRead` `:427-435` → `ToolCallAuthorizer` `:172-178`），**面内不存在**才是 `SKILL_NOT_FOUND`（`SkillAccess:404-405`）。安全性同效：不可达的名字与隐藏技能都得同一条 `RESOURCE_DENIED`，无存在性 oracle。原文保留为历史留痕）、`SKILL_EXISTS`、`INVALID_ARGUMENTS`、`SKILL_INVALID`（frontmatter 校验不过）、`SKILL_READ_FAILED`、`SKILL_WRITE_FAILED`、`SKILL_RELOAD_FAILED`。
- **判定顺序固定为“先范围后存在”**：越权者拿不到“这个技能存不存在”这一个 bit。
- **事件落账**：写工具的 `ledgerArgs` 只落 `name/description/bodyChars/allowedTools`，不把整篇正文复制进事件库。
- **技能名形态**：`[a-z0-9][a-z0-9-]{0,63}`（≡ 目录名；形态校验在路径拼接之前，`..`/分隔符在这一关就死掉）。
- 技能是**扁平**的：`<skillsDir>/<name>/SKILL.md`（与既有 `SkillCatalog` 一致，不递归）。

### 3.1 关键词检索匹配的字段（本期口径）

`name` + `description` + **正文** 三处拼接文本；**大小写不敏感**；查询按空白切词、**全部命中**才算命中（AND）；
返回 `matchedIn`（`name`/`description`/`body`）与 `snippet`（正文命中处附近窗口；正文没命中时给 description）。
**只在可见集内检索**——隐藏技能的名字都不会出现在结果里。本期不做 embedding / 向量检索 / 语义重排。
读不动的技能（文件被删/权限）进 `unreadable`；扫描期坏掉的技能进 `broken`（都不静默）。

---

## 4. 上下文接入

| 通道 | 内容 | 预算/去向 |
| --- | --- | --- |
| **每回合预注入** `ContextLayer.SKILL_INDEX` | 可见技能的目录档（`- 名字: 用途 (allowed-tools: …)`） | `SkillCatalog` 的字符预算（`skills.listingBudget`，缺省 4096 字符 ≈1024 token；`0` = 不披露）；**只在可见集上**算预算与淘汰 |
| **预载正文** `ContextLayer.LOADED_SKILLS` | `skills.preload`（对应 `AgentSpec.skills`）∩ **可见集** 的正文 | 属既有“预载”机制；不可见的名字连读都不读 |
| **运行中主动激活** | `skill_load` 的正文作为**工具结果**进入当前会话历史，此后每回合随历史原样回灌 | 单次正文上限 `SkillAccess.MAX_BODY_CHARS = 20000` 字符，超出截断并附披露行 |

**激活语义的选择（二者选一，写死）**：选**“工具结果留在当前会话”**。理由：主 Agent 与子 Agent 一致可用（子体的技能工具由父侧 MCP 链路服务，工具结果回到子体自己的会话历史），不需要为每个子进程维护第二份“已激活”状态，也不引入“管线写槽 / 装配器读槽失配”那类静默失败面。预载通道（`AgentSpec.skills`）仍走 `LOADED_SKILLS` 层，两者互不替代。

**无权限技能不泄入上下文**：目录档与预载都经 `SkillAccess.visibleSkills()` / `catalog.listing(filter)`；机器判据见 `BasicContextAssemblerSkillScopeTest`。

---

## 5. 创建与编辑

1. **范围**：目标 `SKILL.md` 必须落在可编辑面内（`requireWrite`）；技能名形态在拼接路径之前校验；`create` 不覆盖既有目录（`SKILL_EXISTS`）。
   ★ **2026-10-16（D2，两次修订；第二版才成立）**：`create` 的**抢名**改用 **mkdir 原子占位** ——
   `Files.createDirectory(<root>/<name>)`：`mkdir(2)` 撞名必 `EEXIST` ⇒ `FileAlreadyExistsException` ⇒ `SKILL_EXISTS`，
   **"恰好一个赢家"由内核保证**（本机探针 `.work/probe-d2/ProbeMkdir.java`：双线程同时 `createDirectory` × 200 轮，非唯一赢家 **0** 轮）。
   ⚠️ **第一版修法是错的，留痕**：曾改成" `Files.exists` 预检 + 不带 `REPLACE_EXISTING` 的 `ATOMIC_MOVE`"，以为"撞名由文件系统兜住"——
   **探针实测 ATOMIC_MOVE 撞名在本平台静默替换**（JDK21/ext4：ATOMIC_MOVE 落到 `rename(2)`，而 POSIX `rename` 覆盖已存在目标且不抛；
   普通 `move` 才抛）。实测约 **1/3 轮双方都返回成功**、盘上只剩一方正文 ⇒ 工具回执说谎。
   判别用例：`SkillWriteContractTest#concurrentCreateOfTheSameNameYieldsExactlyOneWinner`（第一版下红、第二版下绿）。
   删掉 `writeAtomically` 的 `replace` 位：目录已归我所有，改名只可能覆盖自己的半成品。
2. **校验**：内容先经 `SkillLoader.parse`（**与读盘同一段解析**）——frontmatter 结构、`name` ≡ 目录名、`description` 1–1024、单行约束；失败即 `SKILL_INVALID`/`INVALID_ARGUMENTS`，**盘上不留半成品**（连目录都不建）。
3. **原子写**：同目录临时文件 + `ATOMIC_MOVE`（不支持时 `REPLACE_EXISTING`），不会出现“写一半的 SKILL.md”被下一次扫描读到。
   ★ **但 ATOMIC_MOVE 不负责"不覆盖"**（探针见第 1 条）：它保证的是"读者永远看到旧全文或新全文"；"撞名即拒"是 `create` 的 mkdir 抢名的职责。两件事别混（D2 第一版就是混了）。
4. ~~**写完即重扫**：`refresh()` 失败 → `SKILL_RELOAD_FAILED`（“写成功但列表看不见”必须响亮）。~~
   ★ **2026-10-16 取代（用户裁定"乙"）：写后不抛** —— 写成功之后的重扫是 **best-effort**：`refresh()` 失败**不改结论**，
   只降级为写结果里的一条**披露**（`SkillAccess.WriteResult.disclosure()`；工具面 JSON 的 `disclosure` 字段）。
   理由：文件已原子改名落盘、工具却回 `SKILL_RELOAD_FAILED`，模型会重试并撞 `SKILL_EXISTS` —— “写失败 + 已存在”
   两条互相矛盾的账同时进事件库（隔壁 #2 的同一形状：`~/SimulatorMosire/docs/superpowers/plans/2026-10-15-batch-S-write-then-throw.md`）。
   同时删除写后的 `catalog.lookup(...).orElseThrow(...)`：写前 `SkillLoader.parse` 的返回值**就是**“写成功后目录里那份”的元数据（同包测试 `SkillAccessTest` 守着）。
   可见性不受影响：`search`/`loadBody`/`readResource` 入口内部重扫、`skill_list` 也先重扫，那几处失败依旧响亮。
5. **宽容扫描但绝不静默**：扫描期坏掉的技能从快照消失（列不出、加载不到），但进 `SkillCatalog.broken()`，`skill_list`/`skill_search` 的结果里点名披露；其余健康技能照常可用。
6. **可见性**：同一进程内写入立刻可见（写路径 **best-effort** 重扫，见第 4 条）；**跨进程**写入在下一次技能工具读（`skill_list`/`skill_search`/`skill_load`/`skill_read_resource` 内部都会重扫）后可见。

---

## 6. 硬安全边界

### 6.1 成立的部分（代码强制）

1. **技能工具层**：没有可见面 ⇒ 列不出、搜不到、读不到（含正文与 `references/`）；没有可编辑面 ⇒ 建不了、改不了。三段判定都在唯一入口内（见 §2.4），机器判据：`SkillToolsTest`、`SkillAccessTest`。
2. **bash 的 L3 沙箱内**（**这是文件工具一侧的边界落点**）：宿主经 `ToolContext.config` 下发 `sandbox.maskedPaths`（生产 = 技能根）。当调用会走沙箱时（= 调用者 fs 可达面受限，**受限调用者一律沙箱**是既有语义）：
   - 调用者技能可见面**覆盖整条路径** ⇒ 沙箱内**只读** bind（可读正文/资源，写操作 `EROFS`）；
   - **盖不住（含未表态）** ⇒ 用空目录**遮蔽**整棵树（沙箱内 `ENOENT`）。
   判定规则只有一处（`SandboxMask.fence`），落点只有一处（`BashSandbox.wrap` 的 `mask:` 项，最后 bind 以免被后续 bind 掀掉）。
3. **“该遮蔽却没沙箱” fail-closed**：调用者看不见整棵树 + 不限 fs 可达面 + `tools.bash.sandbox=off` 时，`ShellTool` 直接返回 `SKILL_SCOPE_UNFENCED` **且不发命令**——这条组合下不存在任何结构性围栏，宁可让 bash 不可用，也不让技能树被 shell 看见/改写。

### 6.2 边界成立的前提（诚实声明）

- 生产装配必须把技能根经 `ToolContext.config` 下发（`App` 对主 Agent、`SubProcessExecutor.linkToolConfig` 对每个子体链接都做了）。**没接线这条缝的装配（外部 MCP 暴露面、手工拼的 `ToolContext`、离线夹具）不享受文件工具侧的保证**，行为与本能力引入前逐字相同。
- 沙箱需要 root/CAP_SYS_ADMIN。非 root 部署下，**受限调用者的 bash 本来就会 `SANDBOX_UNAVAILABLE`**（既有语义）；本能力只保证：这类调用者不会因此拿到“无围栏但能读技能树”的执行。

### 6.3 仍然可能被绕过的路径（已知缺口）

1. **不限 fs 可达面 + `tools.bash.sandbox=off` 的调用者**：技能树遮蔽面下发的仍是这一份，但该组合不会走沙箱 ⇒ 见 §6.1.3，`bash` 被拒（`SKILL_SCOPE_UNFENCED`）。**但如果该部署根本没接线遮蔽面**（没配技能/旧装配），则退回“无围栏执行”，技能树可被 shell 读写——这与“没配技能能力”等价，不构成本能力的缺口。
2. **主 Agent 自己**：它的可见面覆盖整棵树（配置的根），且缺省不限 fs ⇒ bash 不沙箱，可以 `cat`/改写技能树。它不是“越权”（它对技能有完整可见/可编辑权），但**绕过了 frontmatter 校验**（手写坏文件只能靠启动期严格扫描或下一次工具读的 `broken` 披露发现）。
3. **外部 MCP 暴露面**：按既有 GUEST + `unrestricted` 装配，没有技能表态 ⇒ 技能工具一律 `RESOURCE_DENIED`（不泄漏）；但它的 `bash` 也不带遮蔽面 ⇒ 外部客户端可经 bash 读技能文件。这是既有“外部面 = 全功能 GUEST”姿态的延续。
4. **非 `bash` 的未来文件工具**：遮蔽面是 `ToolContext.config` 里的通用数据，但只有 `ShellTool` 读了它。新文件工具必须自行消费 `SandboxMask`（或走 `ResourceAuthorizer` 的资源 SPI），否则不受这条边界保护。
5. **同 uid 的其它进程**：进程边界之外（另一个 JVM、编辑器、`cron`）不受任何约束——本能力的边界是“Agent 的工具面”，不是操作系统安全。

### 6.4 建议的后续加固

1. **给 `AgentTool` 加声明位**（如 `fileSystemAccess()`），让 `SubagentManager` 在 spawn 时静态拒绝“文件工具 + 技能面部分可见 + fs 不限”的组合（把 §6.1.3 的运行时拒绝前移到派发期）。
2. **外部 MCP 面收窄**：给暴露面显式技能表态（`none()`）+ 下发遮蔽面；或在 `AgentToMcpServer` 侧按“链接调用者是否声明技能面”过滤技能工具。
3. **沙箱内按“可见前缀”精细暴露**：当前是“可见面盖不住整棵树就整棵遮蔽”；后续可以把可见前缀逐个只读 bind 回沙箱，让“部分可见”的 Agent 也能用 shell 读它看得见的 `references/`（现在这类 Agent 只能用 `skill_read_resource`）。
4. **写入审计事件**：`skill.create`/`skill.edit` 目前只落 `tool.call`/`tool.result`；可以补一条带技能名与变更摘要的专用事件（不含正文）。
5. **`sandbox=require` 的部署建议**：把主 Agent 也纳入沙箱（配 `tools.bash.sandbox=require`），让 §6.3.2 的“无围栏主 Agent”消失。

---

## 7. 装配（生产路径）

```
configStore.skills.{dir,listingBudget,preload}
  → SkillSettings（缺省根 <dataDir>/skills；坏值启动期响亮失败 E_SKILLS）
  → SkillCatalog（宽容 scan + WARN：坏技能只影响它自己，且在每个读口披露）+ 主 Agent 权限集补 skills/skills-edit = 配置根
  → 注册 6 个工具（供给源 id "skills"）→ SkillAccess（装配面）→ BasicContextAssembler(ContextSources.skills(...))
  → AgentRuntime（全参构造）
子 Agent：SubagentManager 物化两个面 → AgentCommand.argv 追加 --skills-dir/--skill-visible/--skill-editable
        → 子进程按 argv 恢复可见面（decode 后与模板求交）+ 目录档预注入（坏技能只影响预注入，stderr 留痕）
        → 技能**工具**仍由父侧 MCP 链路服务（以子体自己的权限集判定）
```

---

## 8. 测试口径

- **老架构老测试**：基线 1076 条离线用例全部保留、全部通过（`./mvnw verify`）。
  - 被迁移的 3 个旧用例文件（架构相关）：`BasicContextAssemblerSourcesTest`、`BasicContextAssemblerCompactSummaryTest`、`AgentPipelineCompactionTest`——技能来源从裸 `SkillCatalog` 改为 `SkillAccess` 后，构造点必须显式给出可见面。迁移方式是**把过去隐式的那一层写成明文**（测试夹具 `SkillTestSupport.unrestricted(...)` = 不限面），判据逐条保留，未删任何断言。原因：`ContextSources.skills(SkillCatalog)` 这个重载正是“隐式全可见”的载体，本能力把它删掉（换成必需参数）是有意为之。
  - 另有 1 个旧用例文件因**装配面变化**更新断言：`AppMcpLinkTest`（主 Agent 工具面现在含 6 个内置技能工具；“桥接工具整组下架后 registry 为空”改为“只剩内置技能工具”）。断言强度不变（仍是全等），只是期望集合随新能力扩大。
- **新架构新测试**（新增 9 个用例类，56 条）：
  - `AgentLib`：`SandboxMaskTest`（遮蔽判定表）；
  - `Brain`：`SkillPermissionsTest`（三档语义 + 编码 + 单调性）、`SkillAccessTest`（可见面/检索/创建/修改/资源/原子性）、`SkillToolsTest`（经唯一入口的端到端工具行为）、`SkillSettingsTest`（配置闭环）、`BasicContextAssemblerSkillScopeTest`（上下文不得泄入隐藏技能）、`SubagentSkillScopeTest`（逐层求交/越界拒绝/argv 传递/编排工具码）；
  - `Bash`：`ShellToolSkillFenceTest`（没接线不变、未围栏 fail-closed 且不发命令、全可见不需要遮蔽、受限调用者仍走既有 `SANDBOX_UNAVAILABLE`）；
  - `Main`：`AppSkillWiringTest`（**生产装配**的端到端：`<dataDir>/skills` → 主 Agent 工具面 → 脚本回合 → `skill_list` 工具结果里看得见技能名——本能力要修的第一个缺陷就是“有 SkillCatalog 但没接进真实调用链”）+ `SubagentSkillE2ETest`（**真实子进程**：两个模板各给不同 `allowedSkills`，子体经父侧 MCP 链接调 `skill_list`，各自只看得见自己那一个——技能范围确实随 spawn 传到了链上，且没泄漏、没错伤）。
- 全部离线、确定性；**没有**新增假 LLM 装置（本能力的判据不需要模型）。

---

## 9. 未完成项 / 已知取舍

1. **L3 遮蔽的“真跑”验证**：本机非 root（`unshare -m` 不可用），`BashSandbox` 的遮蔽/只读 bind 只验证到判定与失败方向；真机（root/CAP_SYS_ADMIN）上的端到端验证未做（见 §6.4 的加固 1/3）。
2. **外部 MCP 面的技能能力**：按既有姿态未接线（工具调用一律 `RESOURCE_DENIED`），也未见 §6.3.3 的 bash 缺口。
3. **技能目录扁平**：不支持嵌套技能（`<skillsDir>/<a>/<b>/SKILL.md` 不被扫描）——与既有 `SkillCatalog` 一致。
4. **无显式“删除技能”工具**：本期只做创建/修改（删除是不可逆动作，且没有回收站语义；需要时应作为独立裁决加入，而不是塞进 `skill_edit`）。
5. **无 embedding / 语义检索 / 重排**（非目标）；检索是子串 AND，不做分词（中文按字符子串命中）。
6. **主 Agent 的 bash 不沙箱**：见 §6.3.2；`sandbox=require` 可解，但会改变既有 bash 行为，未默认开启。
7. **`skills.preload` 与 `AgentSpec.skills` 尚未打通**：`App` 目前用配置项 `skills.preload` 直连 `ContextSources`，`AgentSpec.skills` 仍是数据承载（其“预载集合”语义未变，权限语义不经过它）。

---

## 10. 验证记录（真实命令与输出摘要）

| 命令 | 结果 |
| --- | --- |
| `./mvnw -q -DskipTests compile` | 退出码 0（改动前后都通过） |
| `./mvnw test`（改动前基线） | 1076 tests, 0 failures, 0 errors, 6 skipped |
| `./mvnw spotless:apply` | 退出码 0 |
| 受影响旧用例定向回归 | `-Dtest=...` Brain 163 条 + Bash 57 条全绿 |
| **`./mvnw verify`（终态，全门禁）** | **BUILD SUCCESS**：Spotless ✅ / Checkstyle ✅ / SpotBugs(More/Low，根 pom 实配) ✅ / Surefire **1132 tests, 0 failures, 0 errors, 6 skipped**（= 基线 1076 + 新增 56） |

模块分布：AgentLib 518 / Brain 334 / Bash 104（6 skipped=需 root 的沙箱真跑）/ Main 176，全部 0 失败。
