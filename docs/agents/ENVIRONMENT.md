# ProjectMosire 环境与已知陷阱

> **来源**：2026-10-17 从根 `AGENTS.md` 的“本机环境与已知陷阱”迁移。
> **加载时机**：换机器、判断 EOL/工具差异、跑真进程/真模型脚本、清理构建产物前阅读。
> **规则溯源**：`docs/archive/2026-10-17-agents-rule-migration.md`。
> **编号说明**：标题保留原 `AGENTS.md` 章节号，便于与迁移台账逐条对照；本文件是当前操作正文。

## 七、本机环境与已知陷阱（换机器先核一遍）

- JDK 21 / Maven Wrapper 3.9.16；`java -version` 与 `./mvnw -v` 都应对。
- 本机内存约 1.6 GB：`.mvn/jvm.config` 固定 Maven 256m，Surefire fork 288m + 160m metaspace；
  **`clean verify` 必须前台/受管跑**，“被内存守卫杀掉”既不是红也不是绿，不能算过。
- `~/.m2` 是**每台机器各自的**：`agentlib-mosire` 要在本机重建一次才能被隔壁消费方用；
  判“重建成功与否”看类数/消费方测试，不看时间戳。
- 仓库用 `.gitattributes` 钉死 LF；**不要假定某个 checkout 的当前 EOL**。换设备/开工前先
  `git status`、`git ls-files --eol <path>` 和 `core.autocrlf` 一起看；CRLF 会让 `mvnw` shebang 或行锚定搜索失效。
  ★★ **EOL 会咬行锚定搜索**（2026-10-17 实测两次假阴性）：若工作树是 CRLF，`grep -rl "^import X;$"` 可能一条都匹配不到
  （`;\r` 不匹配 `;$`）。⇒ 行锚定写 `\r?$`、先 `tr -d '\r'`，或用 `git grep --cached`（索引是 LF）。
  **改文件时保持该文件既有 EOL**，别制造整文件重写的噪声；2026-10-17 抽查 `AGENTS.md`、`README.md`、`开发计划.md`、`pom.xml`
  为 `i/lf w/lf`，**旧的“本机现状是 CRLF”快照已过期**，不要据此判断当前工作树。
- 本机 `grep`/`find` 在当前 shell 是 GNU 工具；换 shell/外壳可能是 ugrep/bfs（默认尊重 `.gitignore`、
  跳过隐藏目录、`-newermt` 语义不同）。跨外壳搜索前先 `type -a grep find`；报 0 命中前先自证工具会命中。
- 真进程/真模型脚本（`scripts/usage-test.sh`、`scripts/smoke-live.sh`）默认**不进**离线门禁，会真联网、真花钱；
  用独立 `--data-dir`，不要指向真实配置/事件库；端口用 0（自动分配）或明确的演示端口。
- `scripts/smoke.sh` 是离线冒烟，默认用假脚本；改动主流程后可作为回归入口之一。
- 所有临时数据、日志、SQLite 库放 `.work/` 或任务指定目录（已在 `.gitignore` 内），不要落到仓库受控路径。

---
