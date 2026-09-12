# 设计：Bash 工具接线与工作目录围栏（**已归 S5**）

> **归属变更（2026-09-12 晚）**：用户改指令 ⇒ bash 先只给**主 Agent**（全功能 + 审批），
> 本文件描述的"子体下放 + 三层目录围栏"整体**移到 `开发计划-三期-第五小段.md`**；
> bash-for-main 与审批的设计见 `设计-审批与工具内权限.md`。
> **本文件 §一（调查，含 §1.4 锚点缺陷、§1.7 密钥可达性、附录 A 实机探针）一字未改、全部有效**，仍作 S5 底稿。

> 制定：2026-09-12（HEAD `dd9a6ec`）。上级计划：`开发计划-三期.md`；落地计划：`开发计划-三期-第四小段.md`。
> 需求原文（用户）：「接下来可以做 bash 工具了；我希望父 Agent 可以在限制子 agent 能用什么工具的基础上，允许进一步限制
> 子 Agent 只能在什么目录下工作，**子 Agent 的子 Agent 所允许的工作目录不能大于子 Agent 本身被允许的工作目录**；
> 未来我可能会写权限确认系统，但是**目前先搞硬性拒绝框架**；进行调查规划吧」
> **一句话**：bash 工具**早就写好且从没注册**；目录围栏是**全仓真空区**；而"孙代 ⊆ 子代"这条要求**撞上一个已存在的结构缺陷**
> ——逐跳检查锚在构造期常量上（见 §1.4），所以本设计把它作为前提一并处理。

---

## 〇、先更正我自己（此前的口头结论是错的）

我在给用户的口头预告里说过："**工作目录围栏不需要 D27 血缘**——'子 ⊆ 父'的检查在派发进程本地完成，形状与既有的白名单 ⊆ 检查一样"。
**这句话错了一半**：检查的形状确实一样，但今天那个检查**不是锚在调用者身上的**。

`SubagentManager` 把 `parentPermissions` / `parentDepth` 存成**构造期常量**（`SubagentManager.java:71-72,92-109`），
`spawn(request)` 直接拿它对照（`:123-134`），而 `spawn_sub_agent` 工具**不把调用者身份传下去**（`SubagentOrchestrationTools.java:80-113`）。
⇒ 今天这两条检查只对"主 Agent 派第一层子体"成立；**两跳以上会把孙代拿去和主 Agent 对照**。

我当时的错误来源是把"存在一个检查"当成了"这个检查锚对了对象"，没有把数据流从派发口追到判定点。
这正是账本里"闭合类断言必须先证伪"的同族错误（第 ④ 例，已记账）：**结构性结论必须追到数据的来处，不能从函数存在与否推断**。

---

## 一、调查底数（2026-09-12 亲查代码 + 实机探针；非推测）

### 1.1 bash 工具**早已存在**，从未注册

| 事实 | 出处 |
|---|---|
| 完整实现：`bash -c` 执行、退出码/stdout/stderr、三档输出裁剪、相对 cwd 解析 | `BrainMosire/src/main/java/io/mosire/brain/tools/ShellTool.java:87`（工具名 `bash` 在 `:92`） |
| 参数面：`command`（必填）/`cwd`/`env`/`timeout`（缺省 60s）/`mode`（normal|error-only） | `:256-276`、schema `:418-452` |
| 工具层错误码：`INVALID_ARGUMENTS`/`LAUNCH_FAILED`/`SHELL_TIMEOUT`/`SHELL_INTERRUPTED`；**非零退出是成功结果** | `:98-107`、`:344-368` |
| 只终止**自己创建的直接子进程**（不涉进程组/树），并按类注释明说不接 `SubprocessManager` | `:29-32`、`:381-388` |
| 权限声明：`ToolSpec.level(AccessToken.DEFAULT, sensitive=true, destructive=true)`，**`noExport` 未置位** | `:248-251` |
| 类注释自陈：**"本阶段不做目录围栏/沙箱；`cwd` 可指向任意可访问路径……目录围栏归三期的 `Workspace`"** | `:68-71`；`resolveWorkingDirectory` `:410-416` 绝对路径**原样放行** |
| 生产代码里**零 `new ShellTool(`**；注册点只有 `App.java:211`（`extraTools`，测试用）与 `:414-418`（编排工具） | 全仓 grep |

**含义**：本段不是"写一个 bash 工具"，而是**接线 + 补围栏 + 定策略**。同时注意 `noExport=false`：
**一旦注册进主 Agent 的 Registry，它就会出现在父侧 MCP 桥的外发面里**（是否真到子体还受模板白名单与三要素布尔两道拦）。

### 1.2 谁在谁的进程里执行（决定围栏落点）

父侧为**每个**子 Agent 起一个 `AgentToMcpServer`，并把 `ToolContext` 的调用者身份设为**该子体自身的权限集**：

```java
AgentToMcpServer.start(parentTools, serverName, serverVersion,
    ToolContext.of(instance.permissions().grantedToken(), instance.permissions()),  // SubProcessExecutor.java:110-113
    managed.stdout()…, managed.stdin()…);
```

子进程侧 Registry 是**空**的（`SubagentProcessMain.java:107`），工具全经 MCP 拉取（`:108-116`）。

⇒ **任意层的子体的工具调用，实际都在主进程里执行**，且执行现场拿得到"这是哪个实例的权限集"（`ToolContext.permissions()`）。
⇒ 围栏**只需要一个落点**（执行侧），不必逐进程实现。

### 1.3 权限模型与"逐跳单调"的现状

- `AgentPermissionSet` 六字段：`grantedToken/allowedTools/deniedTools/destructiveAllowed/sensitiveAllowed/readOnly`（`:22-28`）。
  **没有任何工作目录/ cwd 字段**（`:22-41` 归一化、`:44-52` 工厂 `system()/unrestricted()`、`:55-57` `isToolAllowed`）。
- **逐跳单调的唯一真值点**：`PermissionChecker.isSubset:79-114`（白名单 `*` 语义 `:88-97`、denied 反向包含 `:99-101`、三布尔 `:103-108`、readOnly `:110-112`）。
- **硬拒绝的唯一落点**：`SubagentManager.checkMonotonicity:306-319`——同时发 `permission.denied` 事件并抛 `SubagentRejectedException`，
  工具层映射成 `PERMISSION_DENIED`（`SubagentOrchestrationTools.java:106-107`）。这是"响亮拒绝"的既有范式，目录围栏应当沿用。
- 参数面**两种语义并存**：权限维度=响亮拒绝；数值 cap=**静默取 min**（`deriveConfig:374-401`、`minCap:403-411`）。
  ⇒ 目录集属于"权限维度"，必须走响亮拒绝那条（否则与第一小段"超限响亮拒绝"的裁决冲突）。

### 1.4 ★ 逐跳检查锚在构造期常量（本段要修的前提）

| 事实 | 出处 |
|---|---|
| `SubagentManager` 持有 `parentPermissions` / `parentDepth` 常量 | `SubagentManager.java:71-72`、构造 `:92-109` |
| `spawn(request)` 用它们对照，无调用者入参 | `:123-134`（`:132` 单调性、`:133-134` 深度） |
| `spawn_sub_agent` 工具**不传调用者身份** | `SubagentOrchestrationTools.java:80-113`（只 `manager.spawn(request)`） |
| 派发口另有一道 `systemOnly`：`context.caller() == AccessToken.SYSTEM` | `:308-313` |
| 出厂模板 token 全是 `DEFAULT` | 实测 `configs/agents/general-assistant.json`、`orch-probe.json` |

**今天的事实**：子体的 `spawn_sub_agent` 调用被 `systemOnly` **直接拒掉**（"身份级别不足"），所以**今天子体派不了孙代**——
"孙代继承主 Agent 权限"是**潜在洞，不是现行洞**。

**但这个洞是真的**：`isSubset` 允许"子 token == 父 token"（等值即合法），主 Agent 恒为 `SYSTEM`（`App.java:236`）⇒
**只要某个子模板把 token 写成 `SYSTEM`**，该子体就能派发，而它派出的孙代按**主 Agent 的 SYSTEM + `*`** 对照检查 ⇒ 可放宽；
深度也按主 Agent 的 `depth+1` 记 ⇒ D28 的深度预算被绕过。

**对本次功能的意义**：用户要求的"孙代 ⊆ 子代"**必须把检查锚到调用者**。这不是"顺手优化"，
而是"不锚就做出一个结构性错的东西"——形状对、对象错，且**测不出来**（一跳场景全绿）。⇒ 本段的第一块就是最小身份穿透。

### 1.5 目录围栏是真空区

| 事实 | 出处 |
|---|---|
| 全仓**唯一**的路径包含判定：`normalize → resolve → normalize → startsWith → ISE("越出子库根目录")` | `SubagentOrchestrationTools.java:290-296`（D30） |
| 主代码**零 `toRealPath()`**（符号链接逃逸无防线；`root` 本身是软链时 `startsWith` 会给**假安全**） | 全仓 grep（仅测试用 realpath） |
| **无 always-deny 路径清单**：`keys.*` 只是**键空间**级（`FileConfigStore:143,541`、`ConfigAuth:14,94,111`），没有"这些文件谁都不许读" | 全仓 grep |
| `Workspace.java:20-58` / `EditorTool.java` 是**纯占位**（无实现、无引用），javadoc 明写"路径围栏是三期第一等交付" | `BrainMosire/.../workspace/` |
| `AgentSpec.java:27` 有单值 `Path workingDirectory`（"不接线"） | 同上 |

### 1.6 进程 / cwd / env 管道

| 事实 | 出处 |
|---|---|
| `SpawnSpec.workingDir` 字段存在（null = 继承父进程），但**生产恒传 null** | `SpawnSpec.java:50`、`SubProcessExecutor.java:88-95` |
| ⇒ 子进程 cwd = 继承主进程 cwd；`AgentCommand.argv` **无 cwd 参数** | `AgentCommand.java:115-144` |
| `SubprocessManager` 的 env 是**合并**（`putAll`）不是替换；**无并发上限**；`setsid`/进程组语义**不存在** | `SubprocessManager.java:44,63-65` |
| `ManagedProcess` 无 exit code 访问器，只有 100 行尾缓冲 `outputTail()`（零消费者） | `ManagedProcess.java:124` |

### 1.7 密钥可达性（D24 的新暴露面）

三点事实（agent 3 逐行核过）：
① 子体 `--config-dir <父数据目录>`（`Main.java:158-161` → `App.java:438` → `AgentCommand.java:135-139`）**就是含明文 `config.json` 的那个目录**，路径明写在 argv；
② 即使不给 `--config-dir`，子体自己的 `--data-dir` 是 `<dataDir>/subagents/<id>`，`../../config.json` 可解析；
③ 该文件正是 `SubagentProcessMain.realLlm:180-182` 读密钥的那份，密钥是**明文文本**。

**今天的唯一缓解是"全仓没有任何命令执行工具"**（`new ShellTool` 只出现在测试里）。
⇒ 一旦 bash 下放给子体，密钥就能被 `cat` 出来写进工具结果 → 落进 `events.db`/`messages`。**这是本段必须正面处理的事**，见 §2.6。

### 1.8 实机探针：OS 级围栏在本机能不能用（原文见附录 A）

| 路线 | 结论 |
|---|---|
| mount namespace + chroot + **清空 capability 边界集**（`setpriv --bounding-set=-all --no-new-privs`） | ✅ **实测有效**：外层文件隐藏、`mknod`/`mount`/`chroot` 三个逃逸口全部 EPERM、允许目录可写 |
| 同款沙箱**不降权**（对照组） | ❌ 三个逃逸口**全开**（rc=0）⇒ **降权是承重的**，不是可选装饰 |
| 非 root 走 user namespace | ❌ **本机不可用**：`unshare -U` 报 `uid_map: Operation not permitted`；`kernel.apparmor_restrict_unprivileged_userns = 1` |
| `bwrap` | ❌ 未安装（`unshare`/`chroot`/`setpriv` 在，`firejail`/`nsjail` 不在） |

**诚实边界**：我只证了"隐藏生效 + 三个逃逸口被关 + 允许目录可写"。**没有**穷举逃生面（`io_uring`、`ptrace`、
设备节点、绑定 `/proc` 的信息泄漏等都未测）。所以计划里它是**经实测的候选配方**，落地时必须用判别性用例把两侧都钉住
（"该隐藏的确实读不到" + "该拒绝的确实被拒"），并且**必须响亮失败**（见 §2.5）。

---

## 二、设计

### 2.1 三层防线，各层能承诺什么（诚实分级）

| 层 | 机制 | 真实效果 | **不是**什么 |
|---|---|---|---|
| **L1 参数面硬拒** | 目录集进权限模型；`cwd`/路径参数越界 ⇒ 响亮错误码 + `permission.denied` | 契约明确、误操作围栏、可审计 | **不是安全边界** |
| **L2 起点固定** | bash 的 `cwd` 缺省/相对解析 = scope 首根；子进程 `SpawnSpec.workingDir` 同根 | 相对路径默认落在允许区；子 JVM 出生在工作目录 | 拦不住 `cd /`、绝对路径、`bash -c 'cat /etc/shadow'` |
| **L3 OS 沙箱** | mount-ns + chroot + 清 caps，只 bind 允许目录与系统只读目录（+ 私有 `/tmp`、最小 `/dev`） | **结构性**：没 bind 的路径根本不存在；逃逸口关闭 | 不是"零成本"：要 root/CAP_SYS_ADMIN；本机可用、非 root 部署不可用 |

> **明说结论**：**任意 shell 在进程内不可能被围住**。L1/L2 只防"手滑"与"越权命名"，只有 L3 是安全意义上的围栏。
> 任何把 L1/L2 说成"隔离"的文档措辞都是假的——本设计里所有对外措辞都按这张表写。

### 2.2 目录集进权限模型（显式取值语义）

新增值类型（放在 `io.mosire.agentlib.permission`）：

```java
/** 工作目录范围：unrestricted=true 表示"不限"（与空集不同——空集是"哪里都不许"，必须显式区分）。 */
public record WorkingDirScope(boolean unrestricted, Set<Path> roots) {
  boolean allows(Path candidate);     // unrestricted → true；否则 ∃r: candidate 在 r 之内（含等于）
  WorkingDirScope narrowTo(Set<Path> requested);  // 交集（containment）；unrestricted ∩ X = X
}
```

- 归一化：`roots` 一律 `toAbsolutePath().normalize()`，构造期对**已存在**的根再取 `toRealPath()`（化解"根本身是软链"的假安全，§1.5）；
- `AgentPermissionSet` 增第 7 维 `workingDirs`（归一化：null → `unrestricted=true`），两个工厂与 Builder 同步；
- **为什么不用裸 `Set<Path>`**：白名单的"空集 = 全拒"与目录的"空集 = ?"会直接打架。显式 `unrestricted` 位避免这个语义陷阱，
  也与 `ToolSpec.noExport` 那种"结构性显式位"的既有风格一致。

### 2.3 逐跳单调 + 最小身份穿透（本段前提）

1. `ToolContext` 增"调用者身份"最小件：`(instanceId, depth)`（**不是**完整 D27 血缘；完整版留 S3）。
   构造现场就有 `SubagentInstance`（`SubProcessExecutor.java:86-115`），填进去是零成本；
   成本是 `ToolContext` 是 record，**4 个构造点要同改**（`AgentPipeline:779`、`AgentToMcpServer:252`、`SubProcessExecutor:110`、`Skill:105`）。
2. `spawn_sub_agent` 把调用者传给 `SubagentManager.spawn(request, caller)`；
   `checkMonotonicity` / `checkDepth` 改用 **caller 的权限集与 depth**（构造期常量退化为"主 Agent 的默认"，不再参与判定）。
3. `isSubset` 增第 7 维：`caller.workingDirs` 涵盖 `child.workingDirs`（受限方逐根被涵盖；不限方必须涵住一切）。
4. **派发口是否对非 SYSTEM 开放** ⇒ 开口项 ④（今天全拒；不开放则"孙代"要求空转）。

### 2.4 目录收窄的判定与拒绝（硬性拒绝框架）

- 请求面：`spawn_sub_agent` 新增 `allowedDirs`（字符串数组，可选）。
- 判定：`∀d ∈ 请求: ∃p ∈ caller.workingDirs.roots: d ⊆ p` 且（若 caller 受限）请求不得为空×不限；
  不满足 ⇒ `permission.denied` + `SubagentRejectedException`（复用 `:306-319` 的响亮范式），**父子都不静默截断**。
- 生效值 = `caller.workingDirs ∩ 模板建议 ∩ 请求`（按 containment 求交；不限 ∩ X = X）。
- 模板新增**可选**字段 `allowedWorkingDirs`。
  ⚠ Jackson 默认 `FAIL_ON_UNKNOWN_PROPERTIES=true`（`AgentTemplateStore.java:22` 裸 `ObjectMapper`）⇒
  **record 组件与模板字段必须同批改**，否则老模板直接装载失败（这条"响亮"是好事，但要写进实施步序）。
- 主 Agent 自己的 scope 来自配置项 `agents.workingDirs`（缺省 = 不限），此后逐跳只减不增。

### 2.5 bash 的围栏落点（代码级）

| 点 | 改动 |
|---|---|
| `resolveWorkingDirectory`（`ShellTool.java:410-416`） | 受限调用者：缺省 `cwd` = **scope 首根**（不再是 JVM cwd）；相对路径按 scope 首根解析；绝对/越界 ⇒ `DIR_NOT_ALLOWED`（新码） |
| 执行入口（`ShellTool.execute:278-288`） | 受限调用者**只经 L3 执行**：命令由沙箱包装器包一层；配置 `tools.bash.sandbox = off \| require`；`require` 而沙箱不可用 ⇒ **`SANDBOX_UNAVAILABLE` 硬失败**，**绝不静默退回无围栏** |
| 沙箱包装器（新类，Brain 侧） | 组装：`unshare -m` → bind（系统只读目录 + 允许目录 rw + 私有 `/tmp` + 最小 `/dev`）→ `chroot` → `setpriv --bounding-set=-all --no-new-privs` → `bash -c <cmd>`；只读目录清单**可配置**（`tools.bash.sandboxReadOnly`，缺省 `/usr /bin /lib /lib64 /sbin /etc /run`） |
| 事件面 | `tool.call` 落 scope 摘要（允许根列表），审计可辨"谁在哪个 scope 下跑的" |

### 2.6 与密钥 / 数据目录的关系（D24）

- **L3 下 `.work/mosire/`（含明文密钥的 `config.json`）不在 bind 列表 ⇒ 结构性不可读**，比任何字符串过滤都硬；
- **明确不做**：命令文本的禁词/路径字符串扫描。理由：可以被引号、变量、`$(...)`、编码轻松绕过，却给人"已防护"的假象
  （账本 C 档"父 Agent 自行编机制"那类错误的变体）。**这条写进"不算数"清单**；
- L3 未启用时，受限调用者的 bash 使用要在工具结果里带一行**披露**（照 `[输出可能不完整]` 的先例），措辞必须与 §2.1 分级表一致。

### 2.7 子进程起点（小件，可选）

`SubProcessExecutor.launch` 填 `SpawnSpec.workingDir = 实例 scope 首根`（`instance.permissions()` 里就有；**不改 argv**）。
诚实说明：这只改**起点**，子 JVM 自身代码不受限——不是围栏。

---

## 三、不算数 / 明确不做

1. **进程内围住任意 shell**——不可能，不要往这个方向写代码；
2. **命令字符串扫描 / 禁词表**——假防护（§2.6）；
3. **审批流**（`ApprovalGate` 今天零实现零接线）——用户明说以后做，本段只做硬性拒绝；
4. **符号链接完备防线**（L1 只做词法 + 根 realpath；完备性归 L3）；
5. **A2A / MCP 对外暴露面**、D30 读工具语义、A1 能力集自定义（属 S3）——不在本段。

---

## 四、开口项（等你裁决；不裁决则按"推荐"实施并在账本标注为默认）

| # | 问题 | 推荐 |
|---|---|---|
| ① | **主 Agent 自己的 scope 缺省**：不限 vs 仅工作区 | 配置项 `agents.workingDirs`，**缺省不限**（由部署声明；不改变今天的行为） |
| ② | **子体默认 scope**（模板与请求都没写）：继承父级 vs 收窄到实例数据目录 | **继承父级**（兼容、单调成立）；"更严"留给模板显式声明 |
| ③ | **沙箱默认值**：`off` vs `require` | **受限调用者一律 `require`**（本机是 root，可行）；`off` 只作用于不限 scope 的调用者 |
| ④ | **是否开放"子体派发"**（`systemOnly`） | **开放并锚到调用者**——否则用户的"孙代"要求空转；并把深度/实例数上限一并落地（S3 B 块） |
| ⑤ | **bash 是否外发给子体** | **可下放**（注册进主 Agent 面，经模板白名单 + 三要素布尔 + scope 三重限制）；出厂模板**默认不给** |
| ⑥ | **越界错误码**：复用 `INVALID_ARGUMENTS` vs 新码 | **新码** `DIR_NOT_ALLOWED` / `SANDBOX_UNAVAILABLE`（呼应 UX 轮 C1：审计面需要机器可辨的码） |

---

## 五、验收（使用模式为主证据 D29 + 判别性离线用例）

| # | 场景 | 判据 |
|---|---|---|
| V1 | 注册与暴露面 | `/api/tools` 含 `bash`；父侧 MCP 桥启动行逐名报外发面（诚实口径）；模板未白名单的子体拿不到 |
| V2 | 真跑（正向） | 主 Agent 经 bash 在仓库跑 `uname -a` / 列目录，退出码与输出三方互证（HTTP + 日志 + 事件库） |
| V3 | scope 硬拒 | 受限子体传越界 `cwd` / 绝对路径 ⇒ 响亮 `DIR_NOT_ALLOWED`，`tool.call/result` 成对，**命令未执行**（无副作用证据） |
| V4 | 单调（派发即拒） | `spawn_sub_agent(allowedDirs=父之外)` ⇒ `permission.denied` + `PERMISSION_DENIED`，且**子体没起**（`agent.lifecycle` 无新行） |
| V5 | 逐跳（需开口项④=开放） | a1 派 a1-1：`a1-1.scope ⊆ a1.scope`；a1-1 的 bash 越界同样被拒；孙代 scope 无法放大 |
| V6 | 沙箱（require 生效时） | 受限子体的 bash **读不到** `.work/mosire/config.json`；主 Agent 不受影响；构造"沙箱不可用"⇒ **响亮失败**（不是静默退化） |
| V7 | 密钥零泄漏 | 台架 `scan` 0 命中，且**在跑过 bash 回合之后**重扫 |
| V8 | 判别性离线用例 | 把 scope 检查改成恒真 ⇒ V3/V4 转红；把 `require` 改成静默 `off` ⇒ V6 转红；把"锚到 caller"改回构造期常量 ⇒ V5 转红 |

---

## 附录 A：实机探针原文（2026-09-12 22:0x，本机 `Linux 6.8.0-63-generic`，uid=0）

**A 组：沙箱 + 清空 capability 边界集**

```
  外层文件: 已隐藏
  bash 可用: 是
  mknod 块设备 rc=1  [mknod: /tmp/blk: Operation not permitted]
  mount tmpfs rc=32  [mount: /mnt: permission denied.]
  chroot 再逃逸 rc=125  [chroot: cannot change root directory to '/': Operation not permitted]
  写允许目录: ok
  uid=0
```

**B 组：同款沙箱不降权（对照组）**

```
  外层文件: 已隐藏
  bash 可用: 是
  mknod 块设备 rc=0  []
  mount tmpfs rc=0  []
  chroot 再逃逸 rc=0  []
  写允许目录: ok
  uid=0
```

⇒ 隐藏效果两组相同，**逃逸面只有降权组关上**。

**C 组：非 root / userns 路线**

```
unshare: cannot change root filesystem propagation: Permission denied     # setpriv --reuid=65534 … unshare -U -m
kernel.apparmor_restrict_unprivileged_userns = 1                          # Ubuntu 默认限制
```

> **本节的自我更正（记账）**：第一次探针我把"能否 mount 逃逸"判成"被拒"，其实是 **chroot 内没有 `/dev/null`** 导致
> `2>/dev/null` 重定向自身失败——**零判别力的伪证据**（B 组不降权却也报"被拒"是当场暴露的伪影）。改打印**裸退出码**后才有上表。
> 同族第二次：漏 bind `lib/lib64`（Ubuntu 的 symlink 布局）⇒ `/bin/bash` 报"不存在"，也不是围栏证据。
