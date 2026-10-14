# 工作区标准与 Docs 系统 —— 实施计划

> **执行形态**：本仓 [`AGENTS.md`](AGENTS.md) §2 的**责任区制度**（用户 2026-09-27 裁定，原文见 [`设计-工作区标准与Docs系统.md`](设计-工作区标准与Docs系统.md) §8 开头）**覆盖** superpowers `writing-plans` 模板里的 TDD/逐任务 commit 步：
> **写代码 Agent 只写生产代码、写到编译过、不写测试、不 commit**；测试由最后的**测试责任区 R4** 统一做。
> 派单书模板与三条铁律见设计文档 §8.0 / §8.2。

**目标**：把 `agentlib-mosire` 收成"只暴露通用权限接口"，新增 `workspace-mosire`（工作区标准）与 `docs-mosire`（Docs：`LOG` 追加型 + `FILE` 自实现 IO），并接进真实 spawn 链路。

**架构**：依赖方向 `agentlib ← workspace ← docs`（设计文档 §3）；权限仍走既有 `ResourceScopeMap`（**一个命名空间 + 前缀集合**）；**能力在挂载/授予时落成数据，运行期不产生第二处判定**（A8）。

**技术栈**：Java 21 + 仓库内 `./mvnw`（**勿用系统 `mvn`**，apt 3.8.7 不满足 SpotBugs 要求）；JUnit 6 + AssertJ；Spotless(google-java-format) + Checkstyle + SpotBugs(More/Low)；SQLite（既有）。

**规格**：本计划只写"怎么落地"；**接口形状、不变量、判据一律引用** [`设计-工作区标准与Docs系统.md`](设计-工作区标准与Docs系统.md) 的条目号。

---

## 全局约束（每个责任区都适用）

- **模块边界**：`workspace-mosire` / `docs-mosire` 不得 import `io.mosire.brain.*` / `io.mosire.main.*`；`agentlib-mosire` 不得 import 任何下游；`docs-mosire` **不依赖任何插件**。
- **权限形状**：不得出现"一目录一命名空间"；工作区权限一律落成"某命名空间的一条前缀"；单调性走既有 `PermissionChecker.isSubset`，**不新造守卫**。
- **失败语义**：fail-closed；不静默（坏条目进 `broken` 并披露）；错误码口径见设计 §5.6（**范围外 → `RESOURCE_DENIED`；面内不存在 → `DOC_NOT_FOUND` / `WORKSPACE_NOT_FOUND`**）。
- **v1 范围（N13）**：**不给 Docs 挂 bash 入口**；命令面/白名单/`bash` 能力/`shell-mosire` 抽核全部不在本计划（设计 §5.10 仅为后续草案）。
- **写代码 Agent 的门禁**：只跑 `./mvnw -DskipTests compile`（R1 例外见其 Step 4）；**不跑 `test` / `verify` / `package`**；**不 commit**。
- **文档纪律**：中文 Javadoc，描述"为什么"优先于"是什么"；折行交给 Spotless，不手工调行宽。

## Review Focus（设计里有、判据最容易漏的五处）

1. **不可见 vs 不存在**：一律"范围外 → `RESOURCE_DENIED`，面内不存在 → `DOC_NOT_FOUND`/`WORKSPACE_NOT_FOUND`"；两者混码或不泄露存在性写反，都是错。
2. **子工作区必须严格更小**：落点等于自己的工作区根 ⇒ **拒**（W1）——最容易被实现成"允许"的一条。
3. **能力只在授予时落格**：任何"运行期再判一次能不能用"的实现都是第二处判据（A8）。
4. **v1 所有挂载在 shell 侧遮蔽**：没有 `bash` 能力 ⇒ 任何挂载都不得出现在 shell 可见/可写面（A3/A8/N13）。
5. **既有行为零变化**：R1 只动"常量的家"与遮蔽面形状，**语义逐字不变**；R3b 若让既有断言失效，必须在报告里点名。

---

## 责任区 R1：AgentLib 收编

**目标**：`agentlib-mosire` 只保留**通用权限接口**，不含任何业务命名空间常量；遮蔽面清单条目自带命名空间。

**Files**

- Modify: `AgentLibMosire/src/main/java/io/mosire/agentlib/permission/ResourceScopeMap.java`（删 `SKILLS`/`SKILLS_EDIT` 常量及其 javadoc；**保留 `FS`**）
- Create: `BrainMosire/src/main/java/io/mosire/brain/skills/SkillNamespaces.java`（`SKILLS = "skills"`、`SKILLS_EDIT = "skills-edit"`，值**逐字不变**）
- Modify: `AgentLibMosire/src/main/java/io/mosire/agentlib/tool/SandboxMask.java`（清单条目由裸路径 → **带命名空间**；`fence` 按条目声明的命名空间取 `declaredScope(ns)` 再 `covers`）
- Modify: `BrainMosire/.../brain/skills/{SkillPermissions,SkillAccess,SkillTools,SkillCatalog}.java`、`BrainMosire/.../brain/subagent/{SubagentManager,AgentCommand}.java`（常量的 import 改到 `SkillNamespaces`）
- Modify: `MainMosire/.../main/app/App.java:496`、`BrainMosire/.../subagent/SubProcessExecutor.java:200`（遮蔽面写端改为产出带命名空间的条目）
- Delete: `BrainMosire/.../brain/workspace/Workspace.java`、`BrainMosire/.../brain/workspace/EditorTool.java`（零实现零引用；已在 `设计-Bash工具与工作目录围栏.md` 留指针）
- **不改**：`BashPluginMosire/.../ShellTool.java:501`（`SandboxMask.fence` 签名不变 ⇒ 插件零改动，这是 R1 刻意保住的一条）

**Interfaces**

- Consumes：既有 `ResourceScopeMap.declaredScope(String)` / `ResourceScope.covers(...)`（语义不变）。
- Produces：
  - `SandboxMask` 清单条目形状 = `{namespace: String, path: Path}`（旧裸路径**不再兼容**，两个写端同期改完）；
  - `BrainMosire` 侧 `SkillNamespaces.SKILLS` / `.SKILLS_EDIT`（供 R6 技能重基与既有技能代码使用）。

**Steps**

1. 建 `SkillNamespaces`，把两个常量的值逐字搬过去（含原 javadoc 里"为什么两个命名空间"的理由，避免理由丢失）。
2. 把 `AgentLib` 里 `SKILLS`/`SKILLS_EDIT` 的引用点（仅 `SandboxMask:160`）改为"按条目命名空间取值"；删两个常量。**不保留 `@Deprecated` 别名**（用户 N10 裁定）。
3. 改 `SandboxMask` 的清单解析：元素支持 `Map`（`namespace`+`path`）与 `Path`/`String`（迁移期不作为，但**必须响亮拒**而不是猜默认——猜默认就是第二处语义）。
4. 改两个写端产出带命名空间的条目：主 Agent 用 `("skills", <技能根>)`（技能面本期仍未迁到 docs）；子体链路同。
5. `BrainMosire` 侧技能代码与 spawn 链路的常量引用改到 `SkillNamespaces`。
6. 删两个占位接口文件（`brain/workspace/**`）。
7. **编译门槛（R1 的例外）**：`./mvnw -DskipTests compile` **再跑** `./mvnw -DskipTests test-compile`——后者**只编译不执行**，用来证明常量搬家没把既有测试的引用改坏。
8. **报告**（§8.2-8 格式）：改了哪些文件、行为变更点（应为"无"）、硬编码字面量、**既有断言失效清单**（应为"无"，除非常量引用导致编译错误——见下方例外）、未决问题。

**★ 制度例外（必须记录）**：常量搬家会让**既有测试文件**里对 `ResourceScopeMap.SKILLS` 的 import 编译不过。按 §8.0-3①"写代码 Agent 不写/改任何测试文件"，本责任区**唯一被允许的测试面改动是"仅编译所需的机械改引用"**（改 import / 常量名），**不得新增、删除或修改任何断言**；这部分改动必须在报告里单列。若发现需要改断言才能编译，**停手上报**（说明该断言与"常量只是换了家"这一前提冲突）。

---

## 责任区 R2：`workspace-mosire`

**目标**：工作区标准——虚拟路径 ↔ 物理目录的映射 + **唯一解析点鉴权** + 能力模型 + 子工作区递归 + 三个管理工具。

**Files**：Create `WorkspaceMosire/**`（新模块，包 `io.mosire.workspace.*`）；Modify 根 `pom.xml`（`<modules>` 加一行）。

**Interfaces（Produced，供 R3 消费，签名以设计 §4.3 为准）**

- `MountName` / `Kind{LOG,FILE,SCRATCH,REFERENCE}` / `Capability{id, namespaces, shellVisible, allowedCommands}` / `CapabilityCatalog`
- `Resolver{resolve,list}` + `Resolvers.direct()`（**v1 唯一实现**）
- `Mount{name, holder, user, dir, resolver, kind, capabilities}`
- `WorkspaceCaller{holder, permissions}`
- `WorkspaceRegistry{mount, unmount, mounts, resolve, list, grant, maskEntries, events}`
- `WorkspaceTools`：`mount_workspace` / `unmount_workspace` / `list_workspaces`（`ResourceManifest.of("workspaces", DENY)`）

**Steps**

1. 按 §4.3 落接口与不可变记录；按 §4.5 落解析五步（**找不到 = `WORKSPACE_NOT_FOUND`，与"无权"同码**；解析后 containment 校验）。
2. 按 §4.8 落能力表与 shell 待遇（**v1 只注册 `docs` 能力** ⇒ 所有挂载遮蔽）。
3. 按 §4.4 六条护栏（G1–G6）落 mount 校验；按 §4.10 **M1–M12** 落守卫（含 M9 严格更小、M10 级联失效、M11/M12 能力与白名单只能更窄）。
4. 按 §4.9 落生命周期与审计事件（`workspace.mounted` / `workspace.unmounted` / `workspace.denied`）。
5. 落三个工具（`destructive` 标记、参数 schema、错误码）。
6. 门槛：`./mvnw -DskipTests compile -pl WorkspaceMosire -am` 过。

---

## 责任区 R3a：`docs-mosire` 库与工具面

**目标**：Docs 引擎（`LOG` + `FILE`），**自实现 IO**，不挂任何命令面。

**Files**：Create `DocsMosire/**`（包 `io.mosire.docs.*`）。

**Interfaces（Produced，供 R3b 消费）**

- `DocStore`（`append` / `voidEntry` / `find` / `read` / `query`）+ `DocEntry`（设计 §5.4 字段）+ `DocViewer` + `canSee`（**唯一可见性判据**）
- `FILE` 侧：`DocFileStore{read(path, lines), create(path, text), edit(path, ops[])}`（`ops` 的 `addr` = 行区间或**唯一精确锚点**）
- `DocsTools`：`doc_list` / `doc_read` / `doc_query` / `doc_append` / `doc_void` / `doc_create` / `doc_edit`
- 命名空间常量：`docs` / `docs-edit`（**定义在本模块**，不放 AgentLib）

**Steps**

1. `LOG`：每地址一个 NDJSON 追加日志 + 文件锁（§5.3）；序号 = 行序；`id = <地址>#<序号>`；撞 id 具名拒。
2. `FILE`：普通文件 + 临时文件 + `ATOMIC_MOVE`；`doc_edit` 的多操作**全成或全不成**；锚点不唯一/不命中 ⇒ 拒且**盘上不动**。
3. 可见性判据（§5.4）：`seesAll` 短路在判据之前、`owner`/`tags`/`affiliations` 三支、身份解不出只走 tags、fail-closed。
4. 工具面（§5.6）：读工具 `ResourceManifest.of("docs", DENY)`，写工具 `docs-edit` + `destructive=true`；`DocsAccess` 逐路径 `require`。
5. 纪律检查（B9）：`LOG` 挂载上 `doc_create`/`doc_edit` ⇒ 具名拒；`FILE` 挂载上 `doc_append`/`doc_void` ⇒ 具名拒。
6. 门槛：`./mvnw -DskipTests compile -pl DocsMosire -am` 过。

---

## 责任区 R3b：装配与 spawn 接线

**目标**：把工作区与 Docs 接进真实链路（主 Agent 装配 + 子体权限物化 + argv + 子进程恢复 + 子体工作区挂载）。

**Files**

- Modify: `MainMosire/.../main/app/App.java`（注册 `doc_*` 与 `workspace_*` 工具；下发遮蔽面；**不新增上下文层**）
- Modify: `BrainMosire/.../subagent/SubagentManager.java`（物化 `docs`/`docs-edit` 面 + 创建子体工作区挂载，M9 严格更小 / M11 能力不放大）
- Modify: `BrainMosire/.../subagent/AgentCommand.java`（argv 追加 `--doc-visible` / `--doc-editable`，值从实例权限集现算）
- Modify: `BrainMosire/.../subagent/SubagentOrchestrationTools.java`（`spawn_sub_agent` 入参 + `DOC_SCOPE_DENIED` 错误码）
- Modify: `MainMosire/.../main/Main.java`（`agent` 子命令参数解析）
- Modify: `MainMosire/.../agent/SubagentProcessMain.java`（argv → 权限集恢复；**不新增**自动注入）

**Steps**

1. 主 Agent 装配：工具注册 + 能力注册（`docs`）+ 遮蔽面清单 + 子体工作区根挂载（`holder=main`）。
2. spawn 链路：`effectiveDocs = 调用者 ∩ 模板 ∩ 请求`，越界 ⇒ `DOC_SCOPE_DENIED` + `permission.denied`；**必须物化**（不留"未表态"）。
3. 子体工作区：`user=子体`、`holder=创建者`、落点 = 创建者工作区内的**真子目录**（W1）。
4. 子进程恢复：argv decode → 权限集（缺失 ⇒ `none()`，fail-closed）。
5. 门槛：`./mvnw -DskipTests compile` 过；**报告必须点名既有断言失效清单**（`AppMcpLinkTest` 一类会因工具面扩大而需要更新期望集合）。

---

## 责任区 R4：统一测试（测试 Agent）

**输入**：设计文档 §4.11 + §5.9 **全量**判据 + R1–R3b 的行为变更/失效断言清单。

**要求**（§8.0-4 原文）：按判据清单逐条落实、**必须含负向用例**；关键项**变异自证**（改坏 ⇒ 当场红 ⇒ 还原 ⇒ 比 md5），做不到的如实说"等价存活"；报告必须有**"我没做/没验证的"**一节。

**必须覆盖的负向组**（与 Review Focus 对齐）：

1. 范围外 ⇒ `RESOURCE_DENIED`；面内不存在 ⇒ `DOC_NOT_FOUND`/`WORKSPACE_NOT_FOUND`；**不泄露存在性**。
2. 子工作区 = 自己的工作区根 ⇒ 拒（W1）；在自己工作区之外划 ⇒ 拒（W2）；超 `subagents.maxDepth` ⇒ 拒（W3）；父卸载后子挂载**级联失效**（M10）。
3. 未注册能力 id ⇒ 拒；未授予 `docs` 的挂载上 `doc_*` ⇒ `RESOURCE_DENIED`；能力/白名单比父宽 ⇒ 拒（M11/M12）。
4. **v1 所有挂载不出现在 shell 可见/可写面**（A3/A8）。
5. `LOG` 上文件写操作被拒 / `FILE` 上 `doc_append` 被拒（B9）；`doc_edit` 锚点不唯一 ⇒ 拒且**盘上不动**（B10）。
6. 遮蔽面：**该遮蔽却没沙箱 ⇒ 拒发命令**（既有 `SKILL_SCOPE_UNFENCED` 语义不变）。

**门槛**：全仓 `./mvnw verify` 全绿，并报**本轮真数**（Spotless/Checkstyle/SpotBugs/Surefire 计数与跳过数）；动 AgentLib 后额外跑下游可用性检查（AGENTS.md §0.5）。

---

## 责任区 R8：能力自述工具（查看工作区 / 查看 doc）

> **编号说明**：尾部"后续"列表里的 R5（命令面）/ R6（技能重基）/ R7（其余工具）**仍未开工**；
> 本责任区编号接续为 **R8**，由用户 2026-10-14 裁定**插到最前**做。
> 设计出处：**设计 §5.11**（含用户原话、隔壁真机事实、两个工具的形状与判据）。

**目标**：补上"管道全通、模型不知道"那条缺口——**能力自述由能力自己提供**，
让模型**不必事先知道任何名字**就能问出：工作区是什么、我有哪些挂载（真名）、`doc_*` 怎么用。

**交付物（两个工具，各住自己的模块，不引入反向依赖）**：

| 工具 | 模块 | 内容 |
|---|---|---|
| `workspace_info` | `workspace-mosire` | 工作区是什么 + **调用者自己的**挂载清单（虚拟名/纪律/能力 id/来源/能否作 parent）+ 谁来开地；**不列举 `doc_*`** |
| `doc_guide` | `docs-mosire` | `doc_*` 工具清单与用法（**从真实工具对象生成，不写死**）+ 两种纪律与工具对应 + 在哪些挂载上可读/可写 + 路径示例 + 错误码对照 + 指向 `workspace_info` |

**关键口径（设计 §5.11 已写死）**：
1. 两个工具都 **`ResourceManifest.NONE`**（不声明资源面）——它们不读盘、只输出调用者自己的东西；
   若声明面，"最需要帮助的调用者（面空/未表态）"反而会被前置闸拒，与目的相悖。
2. **绝不输出物理路径**（A7/G4）；**只输出调用者自己的挂载**（换身份 ⇒ 看不到别人的）。
3. **不漂移**：`doc_guide` 的工具清单从工具对象现读 ⇒ 家族新增工具后自动包含；用"集合全等"断言钉住。
4. **nudge 放工具描述里**：`doc_guide` 描述写明"不知道有哪些挂载 / 不确定怎么用 / 刚被 `WORKSPACE_NOT_FOUND`、`DOC_NOT_FOUND` 拒了 ⇒ 先调我"——这是模型发现该工具的渠道，**隔壁零改动**。

**文件所有权**：`WorkspaceMosire/src/main/java/io/mosire/workspace/{WorkspaceTools,WorkspaceInfo}.java`；
`DocsMosire/src/main/java/io/mosire/docs/{DocsTools,DocGuide}.java`。**不动 AgentLib / Brain / Main**（装配处 `App` 若按 `all(registry)` 注册则自动生效）。

**判据**：设计 §5.11 判据段（正/负 + 使用模式）。**负向必须有真样本**：别人的挂载、物理路径、未注册能力 id。

**门槛**：`./mvnw -o -DskipTests compile` + 相关模块 `spotbugs:check`（写代码 Agent）；全仓 `./mvnw verify` 全绿并报本轮真数（测试 Agent / 控制方）。

**预告既有断言失效（测试 Agent 的输入）**：`MainMosire/.../AppMcpLinkTest`（工具面 21 → 23，**仍用全等**）、
`WorkspaceMosire/.../WorkspaceToolsTest`（家族 3 → 4）、`DocsMosire/.../DocsToolsDisciplineTest`（家族 7 → 8）、
以及任何"工具总数/家族集合"的断言。

**验收（缺一不算完成）**：① 判据正/负全绿 + 变异自证；② 全仓 `verify` 真数；③ **真模型使用模式**（AGENTS §三）：
真进程 + `gemma4-local`，**复现隔壁 DM 场景**——模型先调 `doc_guide` → 拿到真挂载名 → `doc_create(workspace/notes/x.md)` **一次成功**（不再猜名字）。

---

## 后续（不在本计划）

R5 命令面模块（N13：`shell-mosire` 抽核 + `bash(mount=…)` + 白名单 + `bash` 能力，草案见设计 §5.10）；R6 技能重基（注册 `docs` 能力 + frontmatter 校验器）；R7 其余默认工具按 workspace 重写、插件/子体目录迁入登记。
