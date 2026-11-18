# 运行 Mosire 服务

需要 JDK 21。打开发行包内的目录，在当前目录保留 `mosire.jar` 和 `plugins/`。Bash 插件与主程序版本一致。

## 离线验证

```bash
java -jar mosire.jar run --fake --no-mcp-expose --port 0 --data-dir .work/mosire-demo
```

启动信息会给出本次的 Admin 和 Debug 地址。用 Admin 地址访问 `/health`，返回 `status=ok` 后，通过 Debug 地址提交一条对话。

```bash
curl -fsS "$MOSIRE_ADMIN_URL/health"
curl -fsS -H 'Content-Type: application/json' \
  -d '{"message":"你好"}' "$MOSIRE_DEBUG_URL/api/chat"
```

将两个变量设为启动信息中的对应 URL。对话返回 `runId`，通过 `GET /api/chat/<runId>` 查看结果。

## 配置模型

使用独立数据目录存放模型配置和 SQLite 状态。首次启动可在交互终端运行下述命令，按提示录入模型标识和凭据。

```bash
java -jar mosire.jar run --no-mcp-expose --data-dir .work/mosire
```

也可加 `--setup` 启动回环配置入口。配置后按启动信息定位网关。启用子 Agent 时通过 `--templates-dir` 选择已有的模板目录，显式审阅模板的工具白名单和资源权限。

## 从源码构建

在仓库根执行 `scripts/gate.sh full`。服务发行包生成在 `MainMosire/target/`，文件名为 `mosire-server-<version>.zip`。
