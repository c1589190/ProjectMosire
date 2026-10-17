# ProjectMosire 验证手册：并发、门禁、证据与变异

> **来源**：2026-10-17 从根 `AGENTS.md` 的并发/门禁/验证细节迁移并按需整理。
> **加载时机**：执行 `scripts/gate.sh`、解释 Surefire/SpotBugs、做变异自证、复核证据时阅读。
> **第一入口**：`scripts/gate.sh pre|full|focused|selftest`；脚本规则以实际输出和 manifest 为准。
> **规则溯源**：`docs/archive/2026-10-17-agents-rule-migration.md`。
> **编号说明**：标题保留原 `AGENTS.md` 章节号，便于与迁移台账逐条对照；本文件是当前操作正文。

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


## 三、构建与门禁

- **工具链**：JDK 21（`--release 21`）+ 仓库内 Maven Wrapper `./mvnw`（锁定 3.9.16）。
  **勿用系统 `mvn`**：apt 的 maven 3.8.7 不满足 SpotBugs 4.10.4.1 的 Maven ≥3.8.9 要求。
- **门禁三件套 + 测试**：`./mvnw verify`
  = Spotless（google-java-format）→ Checkstyle（`config/checkstyle.xml`）→
  SpotBugs（**实际配置 `effort=More` + `threshold=Low`**；计划曾写 Max，但 Max 与庞大 aux classpath
  组合会爆炸到 >25 分钟不终，More 保留深度数据流分析，见根 pom 注释）→ Surefire（JUnit 6）。
- **最近发布证据（0.2.2，2026-10-17）**：发布 commit `c46e777472bfc48c79d9dad0663c8a1c6c54574c`；
  发布前 `./mvnw verify` 记录 AgentLib **674** / Workspace **62** / Docs **81** / Brain **472** /
  Bash **104**（6 skipped）/ Main **225** ⇒ **1618 tests, 0 failures, 0 errors, 6 skipped**；
  6 个模块 SpotBugs 均 `BugInstance size is 0`（来源 `.work/release-0.2.2-verify.log`）。
  ★ 这是**发布提交的历史证据**，不是当前工作树实时测试数；当前版本、最新验证与 Pages 事实一律看
  `docs/agents/STATUS.md`，旧数字不得冒充现状。
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
- ★★ **版本升号后 `package` 不带 `clean` 会在 `MainMosire/target/plugins/` 留下两版插件 jar** ⇒ PF4J 以同一 `PluginId=bash`
  拒绝加载第二个，**主进程启动即失败**（2026-10-17 真模型台架实测：`bash-plugin-mosire-0.1.0-SNAPSHOT.jar` 与
  `0.2.0-SNAPSHOT.jar` 并存 ⇒ `There is an already loaded plugin … with the same id (bash)`，`App.wirePlugins` 抛
  `PluginLoadException`）。⇒ 规则：**插件目录只许一个版本**；升版本号后先清 `MainMosire/target/plugins/`（或 `clean`），
  真进程/真模型验证前先 `ls` 一眼这个目录。
- **勿加 `-N`**：`-N` 只构建根聚合器，对模块源码是空操作，`spotless:check` 会假绿；
  实测：模块里放明显违规文件，`./mvnw -N spotless:check` 退出 0，`./mvnw spotless:check` 正确报违规。
- **全仓 verify 才是完整门禁**；用 `-pl <模块>` 时必须带 `-am`（否则可能用 `~/.m2` 的旧 SNAPSHOT，
  报“找不到包”的**假红**；也可能只跑了部分模块的 Spotless/Checkstyle/SpotBugs）。
  `spotless:apply` 跑完后，用 `git diff --name-only` **列全**所有被改文件，不要只按一个模块路径 `git add`。
- **`./mvnw test` 不跑 SpotBugs**：关账前必须跑 `verify`（或单独 `spotbugs:check`），并重新核对测试计数。
- **验证优先入口**：需要验证时先用 `scripts/gate.sh pre|full|focused|selftest`，不要先手拼会吞汇总的 Maven 命令。
  focused 的结论只能写 `FOCUSED TEST PASSED (NOT a full gate)`；必须引用 `.work/gate/<run_id>/summary.txt` 的 run-bound 计数。
  没有 run-bound summary、或报告不能绑定本轮运行时，写 `EVIDENCE_INCOMPLETE`，不写 PASS。gate 不能表达的场景才退回手动 Maven，并说明原因。
- **聚焦单测**：

  ```bash
  ./mvnw -B -ntp -Dtest='ClassA,ClassB' -Dsurefire.failIfNoSpecifiedTests=false test -pl BrainMosire -am
  ```

  ★ `-Dtest='A+B'` 是 JUnit 5 的 tag 表达式、**不是“或”**：不匹配任何类时 Surefire 一条不跑且退出 0。
  要“或”用**逗号**。`-q` 会吞掉 `Tests run:` 汇总；报告必须看 Maven 汇总或本轮 Surefire 报告，不靠 `-q`。
- **别动辄全量测试（D29）**：改动只跑相关单条用例；全量 `clean verify` 留给阶段边界/关账。
  存量离线用例是**回归钉**，默认保留；“老架构老测试，新架构新测试”；被改架构相关的旧测试可以迁移/删除，
  但必须说明原因，**不得为了变绿弱化断言、删除无关测试**。
- **验证一律先上真模型**（2026-09-13 用户裁决）：功能的“能不能用 / 对不对”默认在使用模式里判——
  真进程 + 真模型 + HTTP 对话/审批，以 HTTP 结果 + 日志 + 事件库**三方互证**。
  可用 `scripts/usage-test.sh`（真模型使用模式测试台，不进默认门禁）与 `scripts/smoke-live.sh`（真 LLM 冒烟，
  联网/花钱）作为操作范例；离线验收可用 `scripts/smoke.sh`（假脚本，不触网）。
  假 LLM 装置是**例外**：只有真模型造不出的确定性（精确帧序列、超时/重试、离线复现）才写，且写明理由；
  **不要新增“为了进离线门禁”而造的假 LLM 装置**。
- **下游消费方验证**：改 `AgentLibMosire` 公开 API/包结构后，按 `AGENTS.md` §0.5 跑消费方检查，不能只在本仓绿。

---

## 四、验证与证据：不许把“没跑”写成“通过”

1. ★ **不信“rc=0 就是过了”。** `-q` 会吞掉 Surefire 汇总；类名写错、测试被跳过也会 rc=0。
   要**看 Surefire 报告**（`*/target/surefire-reports/*.txt` 或 `[INFO] Tests run:` 汇总），并做 **run-bound 证据**：
   写 run manifest（source SHA、开始时间、mode、命令），旧报告先移走或使用 run 专属报告后缀/目录，只解析本轮产生的报告；
   **mtime 仅辅助，不能单独证明报告属于本轮**。否则会读到两轮并集。
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
