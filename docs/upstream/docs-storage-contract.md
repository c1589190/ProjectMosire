# Docs 存储上游契约（U0，2026-10-17）

> **状态**：已按用户 2026-10-17 裁定冻结方向。
> **范围**：`docs-mosire` 的存储端口、文件 IO 安全、限额与默认磁盘实现。领域可见性仍按既有 `DocVisibility`。

## 1. 目标

- Docs 的文件 IO 必须建立在 Workspace 的 `resolveForIo` 之上，不得自己拼 `baseDir.resolve(...).normalize()`。
- 默认磁盘实现关闭软链逃逸。
- 文件读取不得无条件整文件加载；按行区间读取必须真正只读到目标区间（或至少受 `maxReadBytes` 限制）。
- 存储后端可插拔：磁盘是默认适配器，不是唯一实现。
- 不让 Docs 认识 Agent 世界/宿主事务；它只接受 `WorkspaceRegistry` 与可选存储工厂。

## 2. 安全 IO 契约

### 2.1 解析

`DocsEngine` 的每一次 `LOG` / `FILE` 操作都先经：

```java
ResolvedPath target = registry.resolveForIo(
    virtualPath, caller, mode, SymlinkPolicy.REJECT);
```

- 读：`IoMode.READ`
- 创建：`IoMode.CREATE`
- 编辑：`IoMode.WRITE_EXISTING`

任何物理路径都不进入面向模型的文本、错误消息或事件载荷；只进内部日志。

### 2.2 文件打开

- 读：`NOFOLLOW_LINKS` 或等价的 fd 相对操作；
- 创建：同目录临时文件 + `CREATE_NEW` + `NOFOLLOW_LINKS` + `ATOMIC_MOVE`；
- 编辑：先读、内存中施加全部 `EditOp`、写临时文件、原子替换；
- 中途目录为软链 ⇒ 拒；
- 原子替换前再次确认目标真实路径仍在挂载根内；做不到 fd 相对操作时，明确记录 TOCTOU 残余窗口。

### 2.3 错误码

保持既有 `DocException` 稳定码：

- 路径越界/软链 ⇒ `RESOURCE_DENIED`（或既有 Docs 等价码）；
- 面内不存在/不可见 ⇒ `DOC_NOT_FOUND`；
- 撞名 ⇒ `DOC_EXISTS`；
- IO 失败 ⇒ `DOC_READ_FAILED` / `DOC_WRITE_FAILED`；
- 限额 ⇒ `INVALID_ARGUMENTS`（或新增具名 `DOC_LIMIT_EXCEEDED`，实现时二选一并入测试）。

## 3. 限额

新增：

```java
public record DocLimits(
    long maxFileBytes,
    long maxReadBytes,
    int maxEditOps
) {
  public static DocLimits defaults();
  public static DocLimits unlimited();
}
```

- `maxFileBytes`：create/edit 后文件最大字节数；超限零落盘。
- `maxReadBytes`：单次 read 返回内容最大字节数；超限具名拒，不截断后假装成功。
- `maxEditOps`：单次 edit 的 op 数上限。
- 默认值取保守值；宿主可显式 `unlimited()`，但必须自行承担风险。

## 4. 存储工厂

现有 `DocStore` / `DocFileStore` 接口保留。新增：

```java
public interface DocStoreFactory {
  DocStore open(Mount mount, ResolvedPath root);
}

public interface DocFileStoreFactory {
  DocFileStore open(Mount mount, ResolvedPath root);
}
```

`DocsEngine` 增加带工厂的构造器：

```java
DocsEngine(WorkspaceRegistry registry,
           DocLimits limits,
           DocStoreFactory logStores,
           DocFileStoreFactory fileStores);
```

旧构造器：

```java
DocsEngine(WorkspaceRegistry registry)
```

保持默认 `DocLogs.onDisk` + `DiskDocFiles` 行为。

## 5. 按行读取

`DocFileStore.read(path, lines)` 的既有语义不变：

- `lines` 为空 ⇒ 全文，但受 `maxReadBytes`；
- `lines="10,40"` ⇒ 闭区间；
- 不得为了取 30 行先把整个大文件读进内存再切。

实现建议：

- 用 `BufferedReader` 逐行读，读完目标区间即停；
- 统计总行数时如果需要全文扫描，另行明确成本；不要假装 O(1)；
- 读取上限按字节和行数双重保护。

## 6. LOG 存储

`DocLogs.onDisk` 保留：

- 每地址一个 NDJSON；
- 文件锁只包写入路径的读-改-写；
- 坏行进 `broken`，不静默；
- query/list 的排序与截断仍在存储层固定；
- 文件名只是索引，身份以记录内地址为准。

## 7. 兼容性

- `DocFileStore` / `DocStore` 方法签名不改；
- `DocsApi` / `DocsAccess` 旧构造器保留；
- 新增 `DocLimits` 与工厂是增量；
- `DocTarget` 内部改为持有 `ResolvedPath`，不是公开 API。

## 8. 验收判据

1. 软链读：挂载内软链指向外部文件 ⇒ 拒；外部内容不出现在结果/异常。
2. 软链写：挂载内软链父目录指向外部 ⇒ create/edit 拒；外部目录零新增/零修改。
3. 大文件按行读：构造超过 `maxReadBytes` 的文件，读 `1,10` 仍成功，且不触发整文件读取上限。
4. 超限写：create/edit 超 `maxFileBytes` ⇒ 具名拒，盘上原文件不变。
5. 工厂：注入内存 `DocFileStore` 后，DocsEngine 不碰磁盘。
6. 既有 `DocFileEngineTest` / `DocLogsEngineTest` / `DocVisibilityTest` 语义不变。
7. 所有物理路径不进入模型面错误消息。

## 9. 未决/后续

- `DOC_LIMIT_EXCEEDED` 是否新增独立错误码，实现时按现有错误码表决定；若新增，必须同时更新 DocsAccess 映射。
- `SecureDirectoryStream` 不可用时的跨平台语义另测；不能用“平台不支持”静默退回跟随软链。
- 大文件全文行数统计的成本另记，不在本批假装解决。
