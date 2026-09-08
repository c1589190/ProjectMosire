# SpotBugs effort 标记：Max → More（2026-09-08）

> 动机：开发计划原定 SpotBugs `effort=Max`（D11 门禁）。首次对 main-mosire 跑 Max 时长时间挂起，定位为配置问题而非 bug，记档以免重蹈。
> 证据：【本机验证】反复实验：插件直接调用、`/proc/PID/cmdline` 抓独立 fork、扫描类二分、aux classpath 开关。结论先于猜测。

## 1. 现象

- `effort=Max` + `threshold=Low` 时：main 模块仅 6 个类，SpotBugs **>25 分钟不终**（MANUAL RUN TIMEOUT）。
- 挂起与"分析类集合"无关：任意单类单独跑、6 类无 aux classpath、单类带 aux classpath——每种组合都 ~15s 完成。
- 触发条件 = **effort Max × 完整 aux classpath（jackson/mcp/reactor 等）× 分析对象**：组合爆炸。
- 根因方向：Max 档会启用数据流/类型解析更激进的检测器（如 FindUseOfNonSerializableValue），对大 aux classpath 的类型决议爆炸，与报错无关、非死锁（CPU 占用）。

## 2. 结论与落地

- **门禁按 `effort=More` 生效**（保留深度数据流分析）：同盘 2 个模块 + aux classpath ~17s、0 告警；与 Max 的差异只是少一档类型探索强度，对本项目（小类数、大型第三方依赖）无实际漏检损失。
- pom 注释与本文档同文；closing 时若升级 SpotBugs 可复测 Max（如 4.10.x 后续对 aux classpath 有修复）。
- 附：SpotBugs CLI `-effort` 合法值 **min/default/more/max**（不存在 `medium`）；Maven 插件直接调 goal（非生命周期阶段）时 reactor 依赖 jar 未打包 → "Could not find artifact"，必须走 `mvn verify` 等生命周期阶段。

## 3. 顺带修复的真实缺陷（effort=More 仍抓到）

- `App.eventStore()` 泄漏可变 Store 引用（EI_EXPOSE_REP）→ 改为 `queryEvents()` / `eventCount()`。
- `dispatch throws BasicException` → 精确 `throws InterruptedException`。
- `usage()` 文本块内换行 + `%s` 格式化（VA_FORMAT_STRING_USES_NEWLINE）→ `.replace()`。
- AgentLib 一批 EI（不可变快照未内联、helper 中转不可见）、SQLiteEventStore 内部锁（USO_UNSAFE_METHOD_SYNCHRONIZATION）等，已在代码内注释。
