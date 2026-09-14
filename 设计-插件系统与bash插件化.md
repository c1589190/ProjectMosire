# 设计：插件系统与 bash 插件化（2026-09-14）

> 用户指令：「确保子Agent系统和bash模块能用就可以进入下一个阶段了，我会正式要求你写一个必须从外部接入的官方插件，
> 所以做完手头上两项后你必须开始维护插件系统的各项API，为了方便调试，你可以直接在当前目录插件化bash系统，
> 把bash系统做成一个**打包时默认打包、调试时作为插件**的模块。」
>
> 本文定这一工作束的**结构、边界与判据**；实现拆解与记账见 `开发计划.md` §六 与 SDD 账本。

---

## 一 目标（可判定的三条）

1. **打包时默认打包**：`mvn package` 的产物里**自带** bash 插件，宿主启动即装载 ⇒ 开箱有 `bash`，行为与
   `progress.md` §⑮ 的基线**逐条一致**（三档分类、审批闭环、落账脱敏、跨面对账）。
2. **调试时作为插件**：改 bash 只需 `mvn -pl BashPluginMosire package` + 把新 JAR 放进插件目录 + 重启宿主；
   宿主二进制（`mosire.jar`）**零改动**。
3. **插件 API 可用**：一个"从外部接入"的插件作者，只凭本文 §四 的配方（JAR 形态 + 扩展点 + 宿主服务面）
   就能写出来；bash 插件是**第一方参考实现**。

---

## 二 现状清点（2026-09-14 亲读源码）

| 件 | 现状 |
|---|---|
| `io.mosire.agentlib.plugin` 四件套 | `ToolSource`（PF4J ExtensionPoint，L2 供给源）、`BuiltinToolSource`、`PluginListener`（窄 SPI，事件词汇 `plugin.lifecycle{pluginId,version,state}`）、`PluginToolSource`（`plugins/*.jar` → PF4J 装载 → 按 sourceId 登记；归属校验、disable 整组摘除、聚合响亮失败） |
| `PluginToolSource` 的调用者 | **零生产调用者**（只有 `PluginToolSourceTest`）⇒ **宿主从没装载过插件** |
| bash 归属 | `io.mosire.brain.tools.{ShellTool, ShellOutputTruncator, BashSandbox, BashCommandClassifier, BashToolConfig, BashToolConfigLoader}`（8 个主文件 ≈2.9k 行 + 8 个测试文件 ≈2.3k 行） |
| bash 的依赖面 | **只依赖 agentlib**（`AgentTool`/`ToolContext`/`ToolGate`/`AskKind`/`CommandMode`/`AccessToken`/`ResourceScope`/`Digest`/`ConfigStore`…）——`ShellTool.OutputSink` 是内嵌接口，无 Brain 类型 |
| bash 的装配 | `App.java:267` 直接 `tools.register(new ShellTool())`（**焊死**，非供给源）；配置经 `App.java:309` 读、`:415` 塞进 `ToolContext.config` |
| 配置喂法（既有缝） | `ToolContext.config` = `Map<String,Object>`，键是**完整点分路径**（`tools.bash.*`）；`CommandModeHolder.CONFIG_KEY` 已开"传对象"的先例 |
| 发行 | `MainMosire` 的 shade 出 `target/mosire.jar`（`MainMosire/pom.xml:94-107`，`ServicesResourceTransformer` + 主类 `io.mosire.main.Main`） |

**结论**：插件系统（L2 那一层）**已建成、未接线**；bash 的类**已经具备"插件材料"的全部性质**（无 Brain 依赖），
差的只是搬家 + 接线。

---

## 三 关键决策

### D1 载入路径唯一：PF4J `plugins/`，不 shade、不留双份

bash **必须搬出 BrainMosire**。理由不是洁癖：若插件 JAR 内也含 `io.mosire.brain.tools.ShellTool`，而宿主 classpath
上又有一份（`MainMosire → BrainMosire`），PF4J 的 `PluginClassLoader` 是 child-first ⇒ **同名类在进程里存在两份**，
`instanceof`/类型转换按加载器分叉（本仓"陈旧产物"家族的第 4 个变体）。⇒ 一条路径：宿主**不认识** bash 的任何类，
只通过 `ToolSource`/`AgentTool` 接口与它对话。

**推论**：`BrainMosire` 搬完之后**零 bash 引用**；`MainMosire` 对 bash 插件模块**只在发行期**有依赖（见 D8）。

### D2 模块与坐标

| 项 | 值 |
|---|---|
| Maven 模块 | `BashPluginMosire`（artifactId `bash-plugin-mosire`，parent `mosire-parent`） |
| 包 | `io.mosire.bash`（**新命名空间**：既不冒充 `brain`，也不与宿主包混） |
| 依赖 | `agentlib-mosire`（compile）+ `slf4j-api` + `spotbugs-annotations`；**不带任何宿主模块**（`brain`/`main` 都不许） |
| PF4J 插件 id（`Plugin-Id`） | `bash` |
| 供给源 id（`ToolSource.id()`） | `bash`（≠ 保留名 `builtin`，见 `PluginToolSource.RESERVED_SOURCE_IDS`） |
| 插件版本（`Plugin-Version`） | `${project.version}`（与宿主同源，`mosire-parent` 的 `<version>`） |

搬过去的是 §二 那 8 个主文件 + 8 个测试文件；`WorkingDirsLoader`（fs 围栏，宿主侧）**留在 Brain**。

### D3 插件 JAR 的形态（外部插件作者照此办理）

1. `META-INF/MANIFEST.MF` 含 `Plugin-Id` / `Plugin-Version`（缺则 PF4J **静默跳过** ⇒ `PluginToolSource` 自己把它变响亮）；
2. 每个扩展类标 `@Extension`（`org.pf4j.Extension`），由 pf4j jar 自带的
   `org.pf4j.processor.ExtensionAnnotationProcessor` 生成 `META-INF/extensions.idx`——**PF4J 3.x 唯一的发现路径**
   （实测：只放 `META-INF/services/*` 不会被发现）；
3. **不打包任何宿主 API 的类**（`agentlib` 放 `provided`）：运行时由宿主 classloader 解析，实例才能通过接口互相调用；
4. 一个插件 JAR **恰好一个** `ToolSource` 扩展（多份/零份都被 `PluginToolSource` 响亮拒绝）；
5. Maven 侧用 `maven-jar-plugin` 写 manifest（`manifestEntries`）+ 普通 `jar` 打包（**不用** shade）。

### D4 宿主怎么找到 `plugins/`

解析顺序（**纯函数**，可单测）：

1. 配置键 `plugins.enabled=false` ⇒ 不装载（记一行日志，默认 `true`）；
2. 配置键 `plugins.dir`（配置文件/环境层；同 `commands.mode` 那套 `ConfigStore`）⇒ 用它；
3. 否则 `<jar 所在目录>/plugins`（宿主在启动时算一次并**打印解析结果**）；
4. 目录不存在 ⇒ **不报错**，记一行 `插件面: 未启用（目录不存在: …）`——"没装插件"是正常部署态；
5. 目录存在 ⇒ `loadAll()`；**任一个 JAR 装载失败 ⇒ 启动响亮失败**（聚合 `PluginLoadException` 冒泡）。
   与既有口径同族：「配置项存在但值非法 ⇒ 响亮失败」（`SubagentLimitsLoader` / `BashToolConfigLoader` 的先例）。
   —— 明确不做"坏插件静默跳过"：那正是本仓反复踩过的"失败形态不可辨"。

启动行（可观测锚点，照 `审批面已装配：…` 的范式）：

```
插件面已装配：dir=<绝对路径> 装载=<n> 个 [<pluginId>@<version>…] 工具 <m> 个 [<toolName>…]
插件面：未启用（目录不存在: <路径>）
```

### D5 宿主服务面（"最小"的定义）

插件能拿到的宿主能力**只有两条**，都经既有缝，不新增全局单例：

| 服务 | 通道 | 说明 |
|---|---|---|
| 工具调用上下文 | `ToolContext`（`arguments`/`resources`/`identity`/`config`） | 既有 API；**不可自报身份**是既有红线 |
| 宿主服务（**新增**） | `ToolSource.init(HostServices)`：装载时**一次**，早于 `listTools()` | `HostServices` = agentlib 新类型，当前唯一字段 = `ConfigStore`（宿主 `FileConfigStore` 实现）；插件据此解析自己的配置节 |
| 日志 | slf4j（由宿主 classpath 提供） | 无需新增 |
| 事件 | ❌ 不开放 | 插件状态由宿主落 `plugin.lifecycle`（D6）；插件**不自造事件类型**（事件词汇表归宿主） |

**为什么是 `init(HostServices)` 而不是把 store 塞进 `ToolContext.config`**（本设计里唯一被否掉的方案，理由记在案）：

1. **装载期校验**：既有红线是「配置项存在但值非法 ⇒ **启动**响亮失败」（`BashToolConfigLoader` / `SubagentLimitsLoader`
   同一口径）。塞进 `ToolContext.config` 就只能在**第一次调用**时解析 ⇒ 坏配置变成"跑到那一步才炸"，
   是一次**静默降级**；
2. 配置是**插件级**的事实，不是**调用级**的——塞进调用上下文会让每个工具每次调用都重解析一遍，
   且"谁在什么时候读配置"变得不可判。

`init` 抛异常 ⇒ 该插件装载失败（`PluginToolSource` 已有的失败语义：回滚 + `FAILED` 事件 + 聚合响亮抛出）。
未装配 `HostServices`（3 参构造器 / 离线单测）时 `config()` 为空 ⇒ 插件必须要么响亮失败、要么退化到缺省（bash 选后者，见下）。

搬家的连带改动：`BashToolConfigLoader` 随 bash 搬走；**bash 插件在 `init` 里加载一次 `BashToolConfig`**
（坏值在此响亮失败），`ShellTool` 用注入的配置；**保留"从 `context.config()` 现取"的路径**作为
直接构造（既有单测）的缺省口径——两条路的优先级写死在 `ShellTool` 的私有取配置方法里，一处实现。
`App` 删掉 `ShellTool` / `BashToolConfig` / `BashToolConfigLoader` 三个 import 与 `toolConfig.putAll(bashConfig…)`
（`toolConfig` 其它键不动），改建 `HostServices` 并把 `configStore` 交给 `PluginToolSource`。**行为不变**由 §五.1 钉死。

**为什么把 `ConfigStore` 本体给插件**：插件与宿主**同进程同权限**（能读文件、起子进程），多给一个接口句柄
不增加任何实际能力；而"宿主替插件解析它自己的配置节"会让宿主认识插件私有 schema（`tools.bash.*`），
与 D1"宿主不认识 bash"直接冲突。**接口本身不是"只读件"**（`put` 是权限校验的，见其 javadoc），但插件拿到的
是它**已经拥有**的能力：`put` 的授权按传入的 `AgentPermissionSet` 判，且插件能直接写配置文件——
**代价照实记**：插件因此读得到配置里的全部键（含 `llm.routes.*.apiKey`），也能尝试写配置。
这是"进程内插件 = 可信代码"（红线 4）的推论，不是新泄漏；插件目录只放自审代码这条运维前提**不因此放松**。

### D6 插件生命周期事件 + `ToolSource.close()` 补全

1. 宿主装配 `PluginListener` → 落 `plugin.lifecycle`（type + payload `{pluginId,version,state}`，照 `agent.lifecycle`
   范式；AG-UI 翻译层对未映射类型已是"记 debug 并忽略"，**不新增映射**）。
2. **`ToolSource.close()` 在 `disable`/`close` 时被调用**——本项是 API 补全：现状 `PluginToolSource` 只做
   `unregisterAllBySource → stopPlugin → unloadPlugin`，**从不调 `close()`**（类 javadoc 已如实记为"本阶段边界"）；
   而 `disable` 之后类加载器就被卸载，插件若持有线程/句柄**没有第二次机会**回收。改法：在
   `unregisterAllBySource` 之后、`stopPlugin` 之前调 `close()`，异常只记日志（不回滚已生效的摘除），
   并把 javadoc 里那句"不调用其 close()"改掉（**注释与代码必须同真**）。

### D7 包装配（"默认打包"的落点）

```
MainMosire/pom.xml
  ├─ <dependency> bash-plugin-mosire, scope=provided   ← 只为了让 reactor 有序 + dependency-plugin 能解析
  └─ maven-dependency-plugin: copy ${...}/bash-plugin-mosire-<v>.jar → ${project.build.directory}/plugins/
```

- `scope=provided` ⇒ **shade 不会把它吸进 fat jar**（D1 的硬规则因此**可验证**：`unzip -l target/mosire.jar | grep bash`
  应为 0 命中）；
- 产物布局：`MainMosire/target/{mosire.jar, plugins/bash-plugin-mosire-<v>.jar}` ⇒ 从仓库根
  `java -jar MainMosire/target/mosire.jar` 时 `<jar 目录>/plugins` 正好命中（D3.3）——**发行包就是它俩一起拷走**。

### D8 与子 Agent 的关系

插件只装在**宿主进程**（`App.start()`）；子 Agent 走 `io.mosire.main.Main agent`（`SubagentProcessMain`，无网关、
不装载插件）。⇒ 插件工具对子体的可见性**仍然只由 L3 白名单决定**（`ToolSource` 契约原话：全局可用 ≠ 每个 Agent 可用），
本工作束**不**把 `bash` 加进任何模板白名单（那是 S5 的裁决面，不在本轮）。

---

## 四 插件 API 配方（外部作者视角，本文即规范）

```text
插件作者要做的四件事：
1) 写一个 @Extension 的 ToolSource（id() 全局唯一；listTools() 返回你的 AgentTool）
2) 配置与校验：覆写 init(HostServices)（缺省 no-op）——用 services.config() 读你自己的配置节，
   **在这里做装载期校验**（值非法就抛 ⇒ 装载失败、启动响亮失败，别拖到第一次调用）
3) pom：依赖 io.mosire:agentlib-mosire（provided）+ org.pf4j:pf4j（provided），
   maven-jar-plugin 写 manifestEntries { Plugin-Id, Plugin-Version }
4) 把 JAR 放进宿主的 plugins/ → 重启宿主（不热重载）

宿主保证：① 你的工具进全局目录（L2），能否被某 Agent 调用由 L3 白名单决定，与本插件无关；
         ② 启动日志逐名报出装载结果；失败响亮（不会静默跳过你的 JAR）；
         ③ 装载时调一次 init(HostServices)（早于 listTools()，抛异常 = 该插件装载失败并回滚）；
         ④ disable 时整组摘除你的工具 + 调你的 ToolSource.close() + 卸载类加载器
不保证：① 隔离——同进程同权限，插件目录只放自审代码（红线 4）；
       ② 热重载 / 版本并存；③ 两个插件声明同一个 sourceId（第二个被拒）；
       ④ 带 `init` 的插件与旧宿主（无 HostServices 的 3 参构造）二进制兼容——用 `ToolSource` 的缺省实现即可，
          但**不要在插件里假设 config() 一定非空**。
```

---

## 五 判据（怎么知道成了）

1. **行为零变化**（对照组 = `progress.md` §⑮ 基线表）：同一配置下，bash 仍报
   `tool.call{len,digest,class:"ask"}` → `approval.requested`（同 digest）→ `approval.decided{scope}` → `tool.result`，
   三档分类（直放/问/硬拒）与 `limited` 档"直接到人"**逐条一致**。
2. **装载期校验不回退**（D5 的存在理由，必须有用例钉住）：配置 `tools.bash.*` 有值非法时 ⇒ **启动**响亮失败
   （不是"跑到第一次 bash 调用才报错"）；变异 = 把 `init` 里的加载删掉 ⇒ 该用例转红。
3. **单装载路径**：`unzip -l MainMosire/target/mosire.jar | grep -i bash` = 0；`MainMosire/target/plugins/` 有且只有
   一个 bash 插件 JAR；启动行 `插件面已装配：dir=… 装载=1 [bash@…] 工具 … [bash]`。
4. **可插拔（调试形态）**：把 `plugins/` 改名 ⇒ 启动行变 `未启用（目录不存在: …）`、工具面**无** `bash`
   （`外发工具 N 个 [...]` 里没有它）、`bash` 调用得到"未知工具"；改回 ⇒ 恢复。**两个方向都要有日志证据**。
5. **插件事件**：`plugin.lifecycle` 在事件库里可查（`state=STARTED`），与启动行同源互证。
6. **API 补全**：`ToolSource.close()` 的调用有**判别性用例**（变异：删掉调用 ⇒ 转红）。
7. **真模型使用模式**（本仓默认口径）：真进程 + 真 deepseek/GLM + HTTP 审批跑一条 `bash` 命令，三方互证。

---

## 六 不做（边界）

- **P2-7 的自管理工具**（`source_list`/`enable`/`disable` 作为 Agent 工具暴露）——仍是欠账，本轮不顺手做。
- **热重载 / 插件市场 / 签名校验 / 进程外插件**（后者 = MCP 那条路，已在）。
- **`PluginToolSource` 订阅 `onChange`**（工具集跟随插件内部变化）——维持"装载时快照"的既有边界。
- **把插件机制下放到子 Agent 进程**（D8）。

## 七 风险与诚实边界

1. **PF4J 的 `PluginClassLoader` 不是安全边界**（既有红线 4）：插件与宿主同 JVM 同权限。
2. **`plugins.dir` 的默认值只在"真 jar 启动"时推导**（2026-09-14 实现修正，取代本节初版的预言）：缺省 =
   代码位置（**文件**形态，即 jar）同目录下的 `plugins/`；`-cp MainMosire/target/classes` 这类 classes 形态
   **不推导缺省目录**——初版预言"解析到 `target/classes/plugins`"算错了父目录（从 classes 出发是 `target/plugins`，
   即打包产物），会让测试子进程随打包状态意外装载插件（实测踩坑：`AppMcpLinkTest`）。⇒ classes 形态要启用插件请显式配
   `plugins.dir`。另：**显式 `plugins.dir` 指向缺失目录 = 响亮失败**（硬要求落空），与缺省目录不存在（软跳过）不同——
   两读在 `App.resolvePluginsDir` 的 javadoc 定死。
3. **装载失败 = 启动失败**（D4.5）：运维上"放错一个 JAR 就起不来"是**有意的响亮**；排障靠启动行与异常文本
   （含 JAR 文件名与原因）。
4. **搬迁的机械风险**：8+8 个文件的包名/import 改动是"编译期可见"的，但**测试夹具里的相对路径与临时目录假设**
   需逐个过一遍（尤其 `BashSandboxTest` 的探针与 `ShellToolFenceTest` 的围栏夹具）。
