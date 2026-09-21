# 安装报告：把 AgentLib 装到 simos 那一侧

> 面向：SimulatorMosire（simos）侧对接的人。照着本文做完就能用。
> 需求来源：`~/SimulatorMosire/docs/superpowers/specs/2026-09-22-agentlib-llm-requirements.md`
> 实现说明（为什么这么做）：`汇报-AgentLib对接需求.md`

---

## 一、坐标

| 项 | 值 |
|---|---|
| groupId | `io.mosire` |
| artifactId | `agentlib-mosire` |
| version | `0.1.0-SNAPSHOT` |
| 父 POM | `io.mosire:mosire-parent:0.1.0-SNAPSHOT` |
| Java | 21 |

---

## 二、安装（一次性）

> ⚠ **先看这条**：`~/.m2` 里的 `agentlib-mosire-0.1.0-SNAPSHOT.jar` **换过一次**。2026-09-20 那份（126 个类）**不含本次新增的类**（`Sampling` / `LlmTransport` / `LlmProtocol` / `LlmRouteAssembler` / `LlmResponse$ReasoningDisposition` / `LlmException$Kind` / `tool/ToolDefs`）；**本次交付已经重新装上去了**（131 个类，核对记录见第五节）。若你手上是别的机器/别的副本，务必自己再跑一遍下面的命令。
> 顺带提醒：simos 现有的 `AgentLibAvailabilityTest`（门槛 `MIN_EXPECTED_CLASSES = 118`）**拦不住**这类缺失——装完建议把门槛提到 **131**。

```bash
cd ~/ProjectMosire
./mvnw -o install -DskipTests -pl AgentLibMosire -am      # 装 agentlib-mosire
```

- `-am` 会把父 POM（`mosire-parent`）一起装进 `~/.m2`——**simos 侧的 Maven 需要能解析到父 POM**，所以这一步不能省（只用 `-pl AgentLibMosire` 会漏掉父 POM）。
- 去掉 `-DskipTests` 就是完整验证（本次交付的验证结果见 `汇报-AgentLib对接需求.md` 第七节）。
- **不要用 `-N`**（本仓库的构建约定）。
- 产物落在 `~/.m2/repository/io/mosire/agentlib-mosire/0.1.0-SNAPSHOT/`。

**关于"装在哪一侧"**（用户裁定「哪一层包需要对接，就把 AgentLib 安装在哪一侧」）：AgentLib 是普通 Maven 依赖，**装一次进 `~/.m2`，谁需要谁声明**——不需要为不同模块装不同副本。所谓"哪一侧"实际是 simos 的 pom 决策：**若对接层只在 `simos-app`，就把依赖收敛到 app**（`simos-sd` 是否需要请复核）。

---

## 三、simos 侧依赖声明

**simos 已经声明好了，本次不需要改 pom**（普查结论）：

- 三个模块已依赖 `agentlib-mosire`（无 `<version>`、无 `<scope>`，即正常 compile 依赖）：`simos-core/pom.xml:22-24`、`simos-app/pom.xml:45-47`、`simos-sd/pom.xml:32-33`；版本由 `simos/pom.xml:45,102-105` 的 `dependencyManagement` 统一管（`agentlib-mosire.version = 0.1.0-SNAPSHOT`）。
- 另外四个模块被 enforcer **明令禁止**依赖它（`bannedDependencies` 的 `<exclude>io.mosire:agentlib-mosire</exclude>`）：`simos-map` / `simos-social` / `simos-unit` / `simos-util`。
- `~/.m2` 之外**没有** AgentLib 副本（无 `libs/`、无本地 jar、无 `system` scope）——所以**只要重新 install 一次，三个模块都拿得到新类**。

若将来要独立声明：

```xml
<dependency>
  <groupId>io.mosire</groupId>
  <artifactId>agentlib-mosire</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

传递依赖（`mcp-core`、`jackson-databind`、`json-schema-validator`、`sqlite-jdbc`、`slf4j-api`、`pf4j`）会跟着进来；**不引入任何 `io.mosire.brain.*` / `io.mosire.main.*`**（模块边界单向，AgentLib 不反引上层）。

**定版决策点**：simos 侧已写明「待 ProjectMosire 在途工作落地后升为固定版本 `0.2.0`」（`simos/pom.xml:42-45`）。本次交付是否顺带定版 `0.2.0`，需要用户拍板；在此之前继续用 `0.1.0-SNAPSHOT` 覆盖安装即可。

---

## 四、官方示例（照抄）

### 4.1 从配置拿到一个可用的 `LlmClient`（需求 A4 要的 3~5 行）

```java
ConfigStore store = new FileConfigStore(configRoot);          // configRoot 下是 config.json / agents/
LlmClient llm = LlmRouteAssembler.client(store, "deepseek", AccessToken.SYSTEM);
String answer = llm.text(new LlmRequest(messages).withTemperature(0));
```

对应的 `config.json`：

```json
{
  "llm": {
    "routes": {
      "deepseek": {
        "baseUrl": "https://api.deepseek.com/v1",
        "model": "deepseek-chat",
        "credentialsRef": "keys.deepseek",
        "timeoutMs": 120000,
        "capabilities": { "toolCalling": true, "reasoning": true }
      }
    }
  },
  "keys": { "deepseek": "sk-……" }
}
```

> `baseUrl` **不含** `/chat/completions`（那是客户端补的），但**含** `/v1`——`/v1` 要不要写、由谁写是调用方的事，本库只拼 `/chat/completions`。

### 4.2 配置页：列出全部 provider（含写坏的）

```java
for (String name : LlmRouteLoader.availableNames(store)) {   // 权威枚举入口：只列名字，不校验内容
    try {
        ModelRoute route = LlmRouteLoader.load(store, name);
        ModelCapabilities caps = LlmRouteLoader.capabilities(store, name);
        // 渲染一行
    } catch (ConfigException e) {
        // 这一条坏了：把 e.code() 显示出来让用户修——枚举给全，是为了让写坏的那条也看得见
    }
}
```

### 4.3 配置页：增 / 改 / 删

```java
AgentPermissionSet sys = AgentPermissionSet.system();   // llm.* / keys.* / runtime.* 仅 SYSTEM 可写
store.put("llm.routes", "deepseek", routeJson, sys, null, schemaNode);    // 增 / 改（同一条路）
store.remove("llm.routes", "deepseek", sys, null, schemaNode);            // 删（幂等：不存在 = 无操作）
```

- 两个方法都要传 **schema**：校验的是**合并后的整个目标文件**（删一个键也可能因"删完不满足 schema"被拒——有意为之）。
- `store.remove(...)` **删不掉根**（空键/空段/`.`/`..` → `E_PREFIX_DENIED`）；**沿途被删空的对象会一并摘掉**。
- 变更监听 `ConfigListener.onChanged(key, oldValue, newValue)`：**写入与删除共用**，删除时 `newValue` 是 JSON 空节点。

### 4.4 全部路由 → `ModelProvider`

```java
ModelProvider provider = LlmRouteAssembler.provider(store);   // 全有或全无：任何一条坏了整次装配响亮失败
```

### 4.5 测试用假客户端

```java
FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text("第一轮"), LlmResponse.text("第二轮"));
// 按序出队；脚本耗尽即抛，不会悄悄返回上一轮
```

### 4.6 密钥：simos 要 `ENV|FILE` 怎么办

**不要改 `ConfigApiKeySource`**，实现 SPI 即可：

```java
public final class FileApiKeySource implements OpenAICompatibleLlmClient.ApiKeySource {
  @Override
  public Optional<String> apiKey() {
    // 自己读文件/环境变量。约束（不可协商）：
    // 密钥值绝不进日志/异常/事件/argv/stdio；读配置只打印路径 + 长度
  }
}
```

`new OpenAICompatibleLlmClient(route, yourKeySource)` 即可注入。

---

## 五、产物校验

本次交付**已装好**（2026-09-22 01:38）：

| 核对项 | 实测 |
|---|---|
| 产物路径 | `~/.m2/repository/io/mosire/agentlib-mosire/0.1.0-SNAPSHOT/agentlib-mosire-0.1.0-SNAPSHOT.jar` |
| 大小 | 272,054 字节 |
| `.class` 总数 | **131**（上一份是 126） |
| 本次新增的类 | `llm/Sampling`、`llm/LlmTransport`、`llm/LlmProtocol`、`llm/LlmRouteAssembler`、`llm/LlmResponse$ReasoningDisposition`、`llm/LlmException$Kind`、`tool/ToolDefs` —— **逐一 `unzip -l` 核对，全部在场** |

复现方式：

```bash
unzip -l ~/.m2/repository/io/mosire/agentlib-mosire/0.1.0-SNAPSHOT/agentlib-mosire-0.1.0-SNAPSHOT.jar | grep -c '\.class$'
```

> 类数净增 5 而不是 7：工作区里 `llm` 包在途改动较多，同时不再产出 2 个匿名内部类（`$N.class`）。我核对过 `unzip -l`——不是新增的类没打进去。

---

## 六、对接必须知道的约定（精简版）

1. **`baseUrl` 是 API 根**：请求打到 `POST {baseUrl}/chat/completions`；**`/v1` 自己写进去**。写错是 404，看起来像密钥不对。
2. **`baseUrl` 别带端点**（`.../chat/completions`）：构造期直接拒。
3. **不跟随重定向**：3xx → `LlmException(CONFIG)`（跟随会把 `Authorization` 带去别的主机）。
4. **`temperature = 0` 是合法值**，会真的写进请求体（不会被当成"未设置"）。
5. **`extraBody` 不是后门**：`model`/`messages`/`stream`/`stream_options`/`tools`/`temperature`/`max_tokens` 写进去会在构造期被拒。
6. **推理模型（`reasoning_content`）**：`content` 空且无工具调用时，reasoning **并入正文**（`textPart()` 能读到），同时 `reasoning()` 独立可读、`reasoningDisposition() == FOLDED`；其它情况 reasoning 只在 `reasoning()` 里（`SEPARATE`）。**任何情况下都不会静默返回空文本**——要文本却没拿到会抛 `NO_TEXT`。
7. **失败降级判据**：`LlmException.degradable()` 为真才降级；`CONFIG` / `CANCELLED` / `INTERNAL` **该炸**（配置错降级 = 把故障藏起来）。别再用 `catch (RuntimeException)` 一刀切。
8. **`keys.*` 只存引用、只由 SYSTEM 身份写**；Agent 身份写不了 `llm.*` / `keys.*` / `runtime.*`。

---

## 七、升级 / 回滚

- 升级：在 `~/ProjectMosire` 重新 `./mvnw -o install -DskipTests -pl AgentLibMosire -am`，simos 重新构建即可（SNAPSHOT 覆盖同名版本）。
- simos 若已缓存旧 SNAPSHOT：`mvn -U` 强制更新，或删掉 `~/.m2/repository/io/mosire/agentlib-mosire/0.1.0-SNAPSHOT/` 后重装。
