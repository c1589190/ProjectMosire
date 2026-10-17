# Workspace 路径安全上游契约（U0，2026-10-17）

> **状态**：已按用户 2026-10-17 裁定冻结方向。实现可以分步，但所有文件 IO 必须逐步收敛到 `resolveForIo`。
> **范围**：`workspace-mosire` 的虚拟路径 → 物理路径解析与鉴权。Docs / Bash / 其他工具都不得自造第二条解析链。

## 0. 背景与用户裁定

审核报告 P0-2 已用真实探针证明：`Resolvers.direct()` 只做词法 `normalize()`，`Docs` 直接跟随软链接，可读写挂载外文件。

用户 2026-10-17 同意：

- 条目 index 可稳定但不连续（与 Workspace 路径契约无直接冲突，但同样贯彻“稳定身份优先于压缩重排”）；
- 安全边界优先；
- 上游应提供通用协议，而不是把某个宿主的路径习惯写死。

## 1. 两类解析必须分开

| 口 | 承诺 | 用途 |
|---|---|---|
| `resolve(virtualPath, caller)` | **词法映射 + 挂载鉴权**；不承诺软链安全、不要求目标存在 | 兼容旧调用；装配/诊断；不得直接喂文件 IO |
| `resolveForIo(virtualPath, caller, mode, policy)` | 在词法映射之上做真实路径 containment、软链策略、存在性语义 | 所有文件读写、Docs、Bash、工具 |

`resolve` 的旧行为保留，避免破坏已有消费方；但 Javadoc 必须明确它**不是** IO 安全边界。

## 2. 新类型

```java
public enum IoMode {
  READ,
  WRITE_EXISTING,
  CREATE
}

public enum SymlinkPolicy {
  REJECT,                 // 默认：路径中任何软链段都拒
  RESOLVE_WITHIN_ROOT     // 只读：解析软链后仍必须在真实挂载根内
}

public record ResolvedPath(
    Mount mount,
    String relativePath,
    Path physicalPath,
    Path realPath,
    boolean exists
) {}
```

语义：

- `physicalPath`：按挂载 + 相对路径拼出的目标路径（绝对、归一）；
- `realPath`：`READ/WRITE_EXISTING` 为 `toRealPath()` 的结果；`CREATE` 为“最近存在父目录 realpath + 尚未存在的尾段”；
- `exists`：目标当前是否存在；
- `mount`：解析归位到的挂载，供上层取 `Kind` / 能力。

## 3. 接口

```java
public interface WorkspaceRegistry {

  Path resolve(String virtualPath, WorkspaceCaller caller);

  ResolvedPath resolveForIo(
      String virtualPath,
      WorkspaceCaller caller,
      IoMode mode,
      SymlinkPolicy policy);
}
```

默认策略必须是 `SymlinkPolicy.REJECT`。需要软链的宿主必须显式选择 `RESOLVE_WITHIN_ROOT`，且该策略只对 `READ` 开放；写路径永远拒绝软链。

## 4. 解析流程（唯一解析点）

```text
resolveForIo(虚拟路径, caller, mode, policy):
  ① 拆 (挂载名, 相对路径) —— 重用 VirtualPath 的拼法
  ② 在 caller 的挂载表找挂载 —— 找不到与无权同码
  ③ 判调用者能力/资源面
  ④ 解析真实挂载根：
      - 根存在 ⇒ toRealPath()
      - 根不存在 ⇒ 最近存在父目录 toRealPath() + 尾段
      - 真实根也必须落在调用者权限面内
  ⑤ 拼 physicalPath
  ⑥ 按 mode/policy 做真实路径检查
  ⑦ 返回 ResolvedPath
```

### 4.1 READ

- 目标必须存在；
- `physicalPath.toRealPath()`；
- `realPath` 必须仍在真实挂载根内；
- `policy=REJECT` 时，路径中任何软链段直接拒；
- `policy=RESOLVE_WITHIN_ROOT` 时，允许软链，但真实目标必须仍在根内。

### 4.2 WRITE_EXISTING

- 目标必须存在；
- 永远拒绝软链（含最终文件）；
- `realPath` containment；
- 不跟随软链。

### 4.3 CREATE

- 目标可以不存在；存在则按 `WRITE_EXISTING` 检查，是否允许覆盖由上层决定；
- 找最近存在的父目录，取 `toRealPath()`；
- 父目录真实路径必须在真实挂载根内；
- 尾段逐段检查：不得是软链、不得越界；
- 返回目标 `physicalPath` 与 `realPath`（父真实路径 + 尾段）。

## 5. 挂载根

- `Mount.dir` 仍必须绝对、已归一；
- `DefaultWorkspaceRegistry.mount` 增加真实根检查：
  - 根存在 ⇒ `toRealPath()`；
  - 根不存在 ⇒ 最近存在父目录 `toRealPath()`；
  - 真实根必须在调用者 `workspaces` 及能力命名空间的可达面内；
  - 不通过 ⇒ 具名拒，不登记。
- 不创建物理目录（保持既有纪律）。

## 6. TOCTOU

检查与打开之间存在竞争窗口。实现顺序：

1. 优先 `SecureDirectoryStream`：目录 fd + 相对操作；
2. 不可用时用 `toRealPath + NOFOLLOW_LINKS`，并在实现 Javadoc 与测试里明确残余窗口；
3. 绝不静默降级成“跟随软链的普通 IO”。

## 7. ResourceScope 顺序

`ResourceScope` 的 canonical 构造器不再用 `Set.copyOf`：

```java
prefixes = Collections.unmodifiableSet(new LinkedHashSet<>(normalized));
```

保证：

- `prefixList()` / `dirList()` / `summary()` 的迭代顺序 = 输入顺序；
- 去重仍成立；
- 不改 record 组件类型，不破坏调用方。

## 8. 兼容性

- 旧 `Mount` / `Resolver` / `WorkspaceRegistry.resolve` 形状保留；
- 新增 `ResolvedPath` / `IoMode` / `SymlinkPolicy` / `resolveForIo`；
- `DefaultWorkspaceRegistry` 增加实现，不改变 `mount/mounts/unmount` 旧签名；
- 新增 `Mount` 组件不允许；需要真实根信息时由 registry 在 `resolveForIo` 内现算。

## 9. 验收判据

1. 挂载外软链文件：`READ` 拒；错误码为 `RESOURCE_DENIED` 或既有路径错误码，不返回内容。
2. 挂载外软链父目录：`CREATE` / `WRITE_EXISTING` 拒，外部目录不出现文件。
3. 挂载根是软链且真实目标在权限外：`mount` 拒。
4. 挂载根是软链且真实目标在权限内：按真实根工作，虚拟路径不泄露物理路径。
5. `SymlinkPolicy.REJECT` 下，链内软链 READ 拒；`RESOLVE_WITHIN_ROOT` 下链内软链可读，链外软链仍拒。
6. `ResourceScope.ofDirs([z,a,m])` 的 `dirList()` 顺序恒为 `[z,a,m]`，跨 JVM 不随 hash 变化。
7. 既有 Workspace 测试语义不变；新增测试覆盖读、写、父目录、挂载根四类。

## 10. 未决/后续

- 多物理根 overlay / 分片 resolver 仍推迟；
- `SecureDirectoryStream` 的跨平台回退策略在实现时按 JDK 21/Linux 实测决定；
- BashPlugin 的 L3 沙箱不在本契约内，但其文件入口也必须最终走 `resolveForIo` 或等价安全句柄。
