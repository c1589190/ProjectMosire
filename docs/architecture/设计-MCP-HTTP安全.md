# 设计：MCP HTTP 安全（TLS + 显式 opt-in，A1，2026-11-09）

> **基线**：`ProjectMosire` `main @ c252c5f`；MCP SDK 2.1.0；JDK 21。
> **输入**：BMA3 A1；用户 2026-11-09 裁定「可以上 TLS 了，允许 0.0.0.0 等，口径直接推翻」「必须显式」。
> **被取代**：`设计-MCP-HTTP传输与审批渠道.md` §四「鉴权 / TLS = 无（本轮）」与「非回环只告警」被本文取代；旧文保留并追加取代标注。

---

## 〇 安全矩阵（冻结）

| 绑定 | TLS | Bearer 认证 | 允许 | 备注 |
|---|---|---|---|---|
| 回环 | 否 | 否 | ✅ | 开发默认（旧行为） |
| 回环 | 是 | 可选 | ✅ | |
| 非回环 | 是 | 是 | ✅ | 推荐生产 |
| 非回环 | 否 | 是 | ✅ + WARN | Bearer 走明文；建议反代/TLS |
| 非回环 | 是 | 否 | ⚠️ 仅 `allowInsecure=true` | 传输加密≠调用者认证 |
| 非回环 | 否 | 否 | ⚠️ 仅 `allowInsecure=true` | 显式承担公开无鉴权风险 |

- `allowInsecure` 是**显式布尔**，没有隐式默认；非回环 + 不满足条件时**启动即失败**，不是运行时 401。
- 回环判定沿用现有 `isLoopback`（`localhost` / 解析出的回环地址；解析不了按非回环）。
- `0.0.0.0` 允许，但按上表走：要么有 Bearer（最好还有 TLS），要么 `allowInsecure=true`。

---

## 一 API

### 1.1 `McpHttpAuth`（传输无关）

```java
@FunctionalInterface
public interface McpHttpAuth {
  boolean authorize(String method, String path, Map<String, List<String>> headers);

  static McpHttpAuth none() { ... }                    // 显式无认证
  static McpHttpAuth bearer(BearerTokenVerifier v) { ... }
}

@FunctionalInterface
public interface BearerTokenVerifier {
  boolean verify(String token);
  static BearerTokenVerifier constantTime(String expected) { ... }
}
```

- 只给 method/path/headers，不泄露 `HttpExchange` / 具体 HTTP 框架类型。
- `bearer` 解析 `Authorization: Bearer <token>`（scheme 大小写不敏感；多值取第一个）。
- `constantTime` 用 `MessageDigest.isEqual`；token **绝不进日志/异常/事件**。
- 默认渲染的 401 带 `WWW-Authenticate: Bearer realm="mosire-mcp"`。

### 1.2 `McpHttpTlsConfig`

```java
public record McpHttpTlsConfig(
    Path keystore,
    String keystorePassword,
    String keyPassword,
    String keystoreType) {}
```

- 默认 `PKCS12`；`keyPassword` 缺省同 keystore 密码。
- 密码只用于 `KeyStore.load` / `KeyManagerFactory.init`；**不出现在日志、异常消息、事件**。
- 不引第三方 PEM 解析：PKCS12/JKS 是 JDK 原生能力；PEM 由调用方先转。
- `SSLContext` 用 `TLS`（JDK 默认版本协商，1.2/1.3）；不做 client auth。

### 1.3 `McpHttpSecurity`

```java
public record McpHttpSecurity(
    McpHttpTlsConfig tls,   // null = 不启用 TLS
    McpHttpAuth auth,       // null = 无认证
    boolean allowInsecure) {}
```

- 工厂：`none()`（回环开发默认）、`bearer(verifier)`、`tls(tlsConfig)`、`tls(tlsConfig, auth)`、`insecure()`。
- 启动时按 §〇 校验；失败抛可读 `IllegalArgumentException`（不回显 token/密码）。
- `startHttp` 新增带 `McpHttpSecurity` 的 owned / caller-owned 重载；旧重载委托 `McpHttpSecurity.none()`。
  - 旧重载在回环上行为逐字不变；
  - 旧重载在非回环上**现在启动失败**（除非调用方改用带 security 的重载）——这是用户裁定的行为变更，STATUS 记录，Simos 默认 `127.0.0.1` 不受影响，`0.0.0.0` 需显式配置。

---

## 一·补 配置归属（2026-11-09 用户修正）

**AgentLib 不做配置文件/环境变量解析。** 不提供 `McpHttpSecurityConfig` 一类“读 JSON 再构造”的入口；`McpHttpSecurity` /
`McpHttpTlsConfig` / `McpHttpAuth` 都是纯实例 API。

- **本项目（ProjectMosire）**：由 core（`Main`/`BootConfig`/`App`/`ConfigStore` 等统一配置路径）读配置、取密钥、构造实例，再传给
  `AgentToMcpServer.startHttp`。
- **BMA3 等其他项目**：用它们自己的配置初始化方式；读完同样构造 `McpHttpSecurity` 实例传入即可。
- **为什么**：文件格式、密钥来源（env / 文件 / secret manager）、热更新、优先级都是宿主策略；AgentLib 绑死一种格式会让每个消费方被迫适配，
  也会把“读进程环境/读文件”这类副作用塞进库。

宿主 core 的典型装配（示意，密钥值由 core 从自己的配置/secret 源取，绝不硬编码）：

```java
McpHttpTlsConfig tls =
    new McpHttpTlsConfig(keystorePath, storePassword, keyPassword, "PKCS12");
McpHttpSecurity security =
    McpHttpSecurity.tls(tls, McpHttpAuth.bearer(BearerTokenVerifier.constantTime(token)));
AgentToMcpServer.startHttp(
    host, port, path, registry, serverName, serverVersion, caller, authorizer, assetResolver, security);
```

`McpHttpTlsConfig.toString()` 已脱敏；AgentLib 不打印密码/token。本项目 Main 目前只走 stdio MCP，尚未接 HTTP MCP；
等 core 需要对外 HTTP 时，按上面的实例装配接。

## 二 实现落点

- `JdkHttpStreamableServerTransportProvider` 增加 `McpHttpAuth` 字段；`handle()` 顺序改为：
  **路径精确 → 认证（401）→ closing（503）→ 方法分派**。
  - 401 在 closing 之前：未认证请求不应探测服务状态。
  - 认证覆盖 `POST`（含 `initialize`）、`GET` 监听流、`DELETE`。
- owned `startHttp`：
  - `tls == null`：`HttpServer.create`；
  - `tls != null`：`HttpsServer.create` + `setHttpsConfigurator(new HttpsConfigurator(sslContext(tls)))`；
  - 其余装配/关闭顺序、executor、provider 生命周期不变。
- caller-owned `startHttp(HttpServer, ...)`：TLS 由调用方负责；`McpHttpSecurity.tls != null` 时要求传入的是 `HttpsServer`（否则可读失败）。回环/认证校验用 `server.getAddress()`。
- 现有 `ToolAssetResolver` 重载保留；新增 security 参数的重载组合，不逼旧调用方改。

---

## 三 兼容与安全

- 公开 API 只增不减；旧 `startHttp` 回环调用行为不变。
- `0.0.0.0` + 无认证/TLS 从“WARN 放行”改为“启动失败，需 `allowInsecure` 显式”——这是安全收紧，不是回归。
- Bearer 明文（非回环 + 无 TLS）只 WARN，不阻止：用户已裁定允许 0.0.0.0；文档明确建议反代/TLS。
- 密钥纪律：token/密码不进日志、异常、事件、argv/env；只打印“是否启用 TLS/认证 + 绑定地址”。
- 不做 OAuth / mTLS / token 轮换 / 多 token 角色映射；这些是后续独立需求。

---

## 四 判据

1. 安全矩阵六格：允许/失败与 WARN 逐格有用例；非回环无认证无 `allowInsecure` 启动失败。
2. Bearer：无 token/错 token → 401 + `WWW-Authenticate`；正确 token → 进入 MCP 路由。
3. 认证覆盖 `initialize`、普通 POST、GET、DELETE。
4. TLS：PKCS12 自签证书启动 HTTPS，客户端握手成功（测试可用 JDK `keytool` 生成临时 keystore）；密码不出现在日志。
5. 旧回环重载行为不变；caller-owned 非 TLS + `tls` 配置可读失败。
6. full gate 绿；SpotBugs 0。
