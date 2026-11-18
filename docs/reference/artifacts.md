# Maven 制品

坐标使用 `io.mosire`。独立 Java 项目直接声明其源码使用的库。

| 制品 | 对外能力 | Mosire 传递依赖 |
|---|---|---|
| `agentlib-mosire` | 模型、工具封装、权限与轻量会话 | 无 |
| `workspace-mosire` | 工作区挂载和路径授权 | AgentLib |
| `docs-mosire` | 文档与日志能力 | Workspace、AgentLib |
| `brain-mosire` | Agent 运行时与持久化会话，自 0.2.7 起 | Docs、Workspace、AgentLib |
| `mosire-parent` | Maven 版本约束，可从 0.2.7 起按 BOM 导入 | POM |

独立服务交付为 `mosire-server-<version>.zip`，内含主程序和与其同版本的 Bash 插件。服务包用法见 [服务指南](../guides/run-server.md)。

## 固定版本

在消费项目 POM 中配置 [Pages 仓库](https://c1589190.github.io/ProjectMosire/)，从该页选定已发行版本。

试用预发行时将 `mosire.version` 固定为 `0.2.7-early`。该版本的四库、BOM 和服务下载入口见 [0.2.7-early 制品页](https://c1589190.github.io/ProjectMosire/release-0.2.7-early.html)。

```xml
<repositories>
  <repository>
    <id>mosire-pages</id>
    <url>https://c1589190.github.io/ProjectMosire/</url>
    <snapshots><enabled>false</enabled></snapshots>
  </repository>
</repositories>
```

```xml
<dependency>
  <groupId>io.mosire</groupId>
  <artifactId>agentlib-mosire</artifactId>
  <version>${mosire.version}</version>
</dependency>
```

将 `mosire.version` 设为选定版本。代码直接导入 Workspace、Docs 或 Brain 接口时，再声明对应制品。

## 版本对齐

从 0.2.7 起，已有 `mosire-parent` 可以在宿主的 `dependencyManagement` 内导入，让四库和已管理的第三方依赖使用同一组版本。导入版本约束之后，在 `dependencies` 中列出本应用实际使用的制品。

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.mosire</groupId>
      <artifactId>mosire-parent</artifactId>
      <version>${mosire.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

依赖 PF4J 的旧插件 JVM 仍可使用原依赖方式。应用如需限定注解处理器，在自己的 Maven Compiler Plugin 配置 `annotationProcessorPaths`。没有编译期处理器时可用 `<proc>none</proc>`。

## 开发版与兼容性

开发版对应仓库根 POM 的 `project.version`。检出目标提交，在空闲 checkout 里用 `./mvnw -B -ntp install` 构建并安装到本机 Maven 缓存。依赖示例中的 `mosire.version` 取该 POM 的版本。开发版通过本地安装供验证，固定版本通过 Pages 发行。

AgentLib、Workspace 和 Docs 的兼容基线为 0.2.2。升级时保持坐标固定并复跑调用方验收。遇到行为差异可以恢复上一固定版。 [公开契约](../upstream/)
