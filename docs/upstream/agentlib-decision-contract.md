# AgentLib 决策包上游契约（U0，2026-10-17）

> **状态**：已按用户 2026-10-17 裁定冻结。后续实现以本文为准；与旧稿冲突处追加标注，不删除旧条目。
> **范围**：`agentlib-mosire` 的 `io.mosire.agentlib.decision` 公开契约。Brain 如何迁移不在本文范围；宿主如何持久化、如何鉴权由宿主负责。

## 0. 用户裁定

2026-10-17 用户逐条裁定：

1. **允许条目 index 稳定但不连续**：append 只增，remove 留洞，GM 暴露后绝不重排。
2. **append 在第一条例裁决表态后冻结**：`PENDING` 不是锁，GM 可读；一旦开始逐条裁决，包内容冻结。
3. **AgentLib 不应自己拥有 HTTP 面**：现有 `ApprovalHttpEndpoint` / `DecisionPackagesHttpHandler` 不是 MCP，而是历史遗留的本机审批/决策控制面；核心协议只保留传输中立的端口与值对象。HTTP 适配器已移出 AgentLib（暂放 Main 应用层），server、TLS、token、租户都归宿主。
4. **MCP 工具是决策包的审核面**：审核/裁决/执行由 MCP 工具承载，不靠自定义 HTTP 端点。MCP 协议本身不定义“审批语义”，它只承载 tool call；审批语义由工具实现和宿主的身份/角色/资源判定提供。

## 1. 目标

让“决策包”成为一个**与宿主生命周期解耦**的通用协议：

- 宿主可以给包 id；
- 同一个包可以在裁决开始前追加/编辑条目；
- 包状态可以导出为完整快照、由宿主持久化并在重启后恢复；
- 裁决可带内容版本，避免“看旧包、批新包”；
- 时间由调用方注入，纯转移不读墙钟；
- 提交口可以拿到“被批 calls + 提交前快照”，由宿主把效果、outcome、最终包状态写进同一事务；
- 旧的 `DecisionPackages` 保留为 Brain 的默认内存适配器，行为不变。

## 2. 非目标

- AgentLib 不拥有世界状态、revision、tick、DM/GM、MERGED 等领域语义。
- AgentLib 不实现数据库、不替宿主决定事务边界。
- AgentLib 核心不启动 HTTP server、不做 token/TLS/租户。
- 不要求 Brain 在本批迁移。

## 3. 公开值对象（只增不减）

### 3.1 保留的冻结形状

以下既有类型不改 record 组件，不删方法：

- `DecisionPackage`
- `PackageItem`
- `ProposeRequest`
- `Decision`
- `PackageState`
- `CommitReceipt`
- `ResolvedCall`
- `DecisionPackages`

新增能力通过**新类型 + 重载方法**承载。

### 3.2 新增 `DecisionItemVerdict`

```java
public record DecisionItemVerdict(
    int index,
    boolean approved,
    String by,
    String note) {}
```

`index` 是包内稳定身份；允许不连续。`note` 可空，空白归一为 `null`/空串的既有口径由实现统一。

### 3.3 新增 `DecisionPackageSnapshot`

```java
public record DecisionPackageSnapshot(
    DecisionPackage pkg,
    long contentVersion,
    List<DecisionItemVerdict> verdicts,
    CommitReceipt receipt,       // null = 尚未提交
    String expiryReason          // null = 不是过期终局
) {}
```

`DecisionPackageSnapshot` 是宿主持久化的最小完整运行态。`pkg.decision()` 仍表达“整包裁决”；`verdicts` 表达逐条裁决的累计；`receipt` 表达提交回执；`expiryReason` 与既有 `DecisionPackages.expiryReason` 同语义。

### 3.4 新增 `DecisionCommitPlan`

```java
public record DecisionCommitPlan(
    DecisionPackageSnapshot before,
    List<ResolvedCall> calls) {}
```

`calls` 是被批子集，非空；`before` 是提交前快照。宿主可以用它把效果、outcome、最终包状态写入同一事务。

### 3.5 新增 `DecisionPackageReadPort`

```java
public interface DecisionPackageReadPort {

  Optional<DecisionPackage> find(String packageId);

  List<DecisionPackage> pending();

  List<DecisionPackage> all();

  List<DecisionItemVerdict> verdicts(String packageId);

  Optional<String> expiryReason(String packageId);
}
```

这是五个 MCP 决策工具的**读路径端口**（2026-10-17 用户裁定 B/A/A）：

- `find(id)` 是历史入口：旧 tick、已终局、已过期的包都必须能按 id 读到；未知 id ⇒ 空。
- `pending()` / `all()` 是列表投影：宿主可按当前 tick/领域状态裁剪，也可排除 `MERGED`；不要求世界全历史。
- `verdicts(id)` 返回顶层 `DecisionItemVerdict`，不新增对 `DecisionPackages.ItemVerdict` 的依赖。
- 新宿主实现必须纯只读：读不得改状态、发事件或写审计；`full=true` 审计仍归 `DecisionReadAudit`。
- `DecisionPackages` 是兼容例外：其既有五个读方法的惰性过期行为保留；`verdicts` 仍沿用旧 `itemVerdicts` 的 `DECIDING` 投影规则，新宿主应直接返回 `DecisionPackageSnapshot.verdicts()`。

`DecisionPackages implements DecisionPackageReadPort`；`DecisionPackageTools` 保留 7 个旧构造器/工厂入口，并为 `of(...)`、`all(...)`、`tools(...)` 增加端口重载（含 3 参与 6 参 `of/all`）。

## 4. 纯状态转移

新增 `DecisionPackageTransitions`。所有方法：

- 不读 `System.currentTimeMillis()`；
- 不启动线程、不落盘、不发事件；
- 输入非法或状态不允许时抛具名 `DecisionPackages.RejectionException`；
- 返回新的不可变快照。

```java
public final class DecisionPackageTransitions {

  DecisionPackageSnapshot propose(
      String packageId, ProposeRequest request, long nowEpochMs);

  DecisionPackageSnapshot append(
      DecisionPackageSnapshot current,
      List<PackageItem> items,
      long expectedContentVersion,
      long nowEpochMs);

  DecisionPackageSnapshot replaceItem(
      DecisionPackageSnapshot current,
      int index,
      PackageItem replacement,
      long expectedContentVersion,
      long nowEpochMs);

  DecisionPackageSnapshot removeItem(
      DecisionPackageSnapshot current,
      int index,
      long expectedContentVersion,
      long nowEpochMs);

  DecisionPackageSnapshot decide(
      DecisionPackageSnapshot current,
      long expectedContentVersion,
      Decision decision,
      long nowEpochMs);

  DecisionPackageSnapshot itemDecided(
      DecisionPackageSnapshot current,
      long expectedContentVersion,
      int index,
      boolean approve,
      String by,
      String note,
      long nowEpochMs);

  DecisionPackageSnapshot expire(
      DecisionPackageSnapshot current,
      long nowEpochMs);

  DecisionCommitPlan prepareCommit(
      DecisionPackageSnapshot current,
      long nowEpochMs);

  DecisionPackageSnapshot completeCommit(
      DecisionCommitPlan plan,
      CommitReceipt receipt);
}
```

方法名和参数顺序是实现建议；冻结的是语义与不变量，不是签名逐字。

## 5. 不变量

### 5.1 包 id

- 新的宿主 id 口接受非空白字符串；默认 `propose(request)` 仍发 `dp-N`。
- 同 id 已存在 ⇒ 具名拒，不覆盖。
- id 不得含明文参数/密钥。

### 5.2 内容版本

- `contentVersion` 从 1 开始。
- 任何 append/replace/remove/itemDecision/整包 decide 成功都递增。
- `decide` / `itemDecided` 必须带 `expectedContentVersion`；不匹配 ⇒ `PACKAGE_VERSION_STALE`，零状态变更。
- 旧 `DecisionPackages.decide(packageId, decision)` 不带版本的签名保留，内部按当前版本执行（Brain 兼容）。

### 5.3 append / 编辑窗口

- 只允许 `PackageState.PROPOSED` 且 `verdicts.isEmpty()`。
- 一旦第一条例裁决表态（进入 `DECIDING`），append/replace/remove 全部具名拒：`PACKAGE_CONTENT_FROZEN`。
- `PROPOSED` 下 append 不改变包状态；`PENDING` 是宿主投影，不进入通用状态机。
- append 的 index 必须大于当前最大 index；允许不连续。
- remove 只删除条目，不重排剩余 index。
- replace 必须保持同一 index；不得修改已裁决条目。

### 5.4 裁决

- 整包 `decide` 只允许 `PROPOSED`；首次裁决终局，不可覆盖。
- 逐条 `itemDecided` 只允许 `PROPOSED` / `DECIDING`；同一条只允许一次表态。
- 未表态条目不是“拒绝”，直到显式执行或到点。
- `Decision.approvedIndexes` 只引用当前存在的 index。

### 5.5 提交

- `prepareCommit` 只在 `DECIDED` 或可终结的 `DECIDING` 上成功。
- 被批子集为空 ⇒ 终局 `REJECTED`，不产生 `DecisionCommitPlan`。
- 宿主提交口一次调用 = 一个包 = 一条事务/revision。
- `completeCommit` 校验回执与 calls 对齐；回执非法时快照不得进入 `COMMITTED`。
- 重复 `completeCommit` 或重复提交必须返回/接受同一份回执，不重复落盘。

### 5.6 时间

- 纯转移只接受 `nowEpochMs`。
- 到点不是读路径的副作用；宿主应在 tick/窗口边界显式调用 `expire`。
- 旧的 `DecisionPackages` 适配器仍可用系统时钟做惰性过期，但该行为不进入纯核心。

## 6. 兼容适配器

`DecisionPackages` 保留：

- 旧构造器：系统时钟、内存表、`dp-N`、一实例一未决包；
- 旧方法签名；
- 旧行为：Brain 不迁移也能运行。

新增重载：

```java
DecisionPackage propose(String packageId, ProposeRequest request);
DecisionPackage append(String packageId, List<PackageItem> items);
DecisionPackage replaceItem(String packageId, int index, PackageItem replacement);
DecisionPackage removeItem(String packageId, int index);
Optional<DecisionPackageSnapshot> snapshot(String packageId);
DecisionPackage decide(String packageId, long expectedContentVersion, Decision decision);
```

新增方法全部走纯转移核心；旧方法逐步委托，但不得改变旧可见行为。

## 7. 传输边界：MCP 工具优先，HTTP 移出核心

### 7.1 MCP 工具是决策面

MCP 可以审核决策包：MCP 的 `tools/list` / `tools/call` 能承载“列包、看包、逐条表态、执行”的全部动作。但 MCP 本身只是传输和工具调用协议，**审批语义必须在工具实现里**。

宿主要提供的决策工具族（名称可宿主定，语义应齐）：

- `decision_package_list`：列出待决/已批未执行包；
- `decision_package_get`：看包概览；显式 `full=true` 才给参数明文，并落审计；
- `decision_package_decide`：整包批准/拒绝/逐条例外；
- `decision_package_decide_item`：逐条表态；
- `decision_package_commit`：执行已批子集。

安全要求：

- 工具必须从 `ToolContext.identity()` 取调用者，模型自报的身份不采信；
- 明文读取必须显式 `full=true` 并落审计；
- 谁可见、谁能裁、谁能执行由宿主的角色/权限/资源面决定；
- `noExport` 不应作为“决策面永远不可外发”的默认；是否外发由宿主决定，外发时必须同时有身份与角色闸。

### 7.2 HTTP

- 核心协议没有 HTTP 类型，不 import `com.sun.net.httpserver`。
- `ApprovalHttpEndpoint` / `DecisionPackagesHttpHandler` 已从 AgentLib 移出，暂放 Main 应用层；它们不是决策协议的一部分。
- AgentLib 不再新增自定义 HTTP server/endpoint。MCP 的 HTTP transport（`AgentToMcpServer` / `JdkHttpStreamableServerTransportProvider`）是 MCP 协议实现，不属于本节的“自定义审批 HTTP 面”。

## 8. 验收判据

1. 宿主 id：`propose("pkt-dm-1-360", ...)` 得到该 id，旧 `propose` 仍得 `dp-N`。
2. 同包追加：连续 4 次 append 后一个包 4 条，index `[0,1,2,3]`。
3. 删除留洞：删除 index=1 后剩余 `[0,2]`；后续 append 得 index=3。
4. 裁决冻结：首次 `itemDecided` 后 append/replace/remove 全部 `PACKAGE_CONTENT_FROZEN`。
5. 版本冲突：基于旧 `contentVersion` 的 decide/itemDecided 被 `PACKAGE_VERSION_STALE` 拒绝，零变更。
6. 快照恢复：同一快照经 JSON/宿主存储往返后，状态机继续工作。
7. 提交计划：`prepareCommit` 的 calls 非空、与 `before` 的裁决一致；非法回执不得变 `COMMITTED`。
8. 时间：纯转移在相同输入 + 相同 `nowEpochMs` 下结果完全相同，不读系统时钟。
9. 旧回归：现有 `DecisionPackagesTest` / `PendingDecisionQueueTest` / `DecisionTurnScopeTest` 语义不变。

## 9. 未决/后续

- 快照 JSON codec 是否放进 AgentLib，待实现时评估；宿主可先用自己的 codec。
- `DecisionPackages` 内部完全委托纯核心的时机，待 Brain 迁移时再定。
- HTTP transport 模块的 artifactId/包名另立设计，不在本批。
