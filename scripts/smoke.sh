#!/usr/bin/env bash
# M2 一键验收（离线）：启动 → health → A2A（agent-card + message/send + tasks/get + 流式）→ AG-UI SSE
#   → spawn（/api/agents 观测）→ 工具调用（主 Agent spawn/kill 的 tool.call/result；
#     子 Agent 经 MCP 回环调父侧编排工具被拒——permission.denied 落在子体事件库）
#   → kill（AG-UI 会话 2，$spawnedId 占位符驱动）→ sqlite3 事件链断言。
#
# 离线红线：LLM 一律 --fake-script（默认脚本见下），不触任何网络服务。
# 依赖：java（21）、curl、sqlite3（JSON1 内置——deb/brew 默认满足）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# ---- 参数 ----
JAR="${JAR:-$ROOT/MainMosire/target/mosire.jar}"
DATA_DIR="${DATA_DIR:-$ROOT/.work/smoke-m2}"
PORT="${PORT:-0}"            # admin 端口（0 = 让主进程自动分配，从启动行解析实际端口）
KEEP="${KEEP:-0}"            # 1 = 成功后也保留数据目录（默认失败保留、成功清理）
FAKE_SCRIPT="${FAKE_SCRIPT:-$(printf '%s' 'text:第一轮 A2A 回答;text:第二轮 A2A 流式回答;tool:spawn_sub_agent:{"templateId":"reader","goal":"问候主流程"};text:已派遣问候子 Agent;tool:kill_sub_agent:{"instanceId":"$spawnedId"};text:已终止问候子 Agent')}"

usage() {
  cat <<'EOF'
用法: scripts/smoke.sh [--port <n>] [--data-dir <p>] [--jar <p>] [--fake-script <s>] [--keep]
  --port <n>       AdminREST 端口（默认 0=自动分配；a2a/agui 恒为自动分配并从启动行解析）
  --data-dir <p>   数据目录（默认 .work/smoke-m2；每次运行重建）
  --jar <p>        主 jar（默认 MainMosire/target/mosire.jar）
  --fake-script <s> 覆盖默认 LLM 脚本（离线；格式见 Main run --fake-script）
  --keep           成功后保留数据目录（失败始终保留并打印路径）
EOF
  exit 2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    --data-dir) DATA_DIR="$2"; shift 2 ;;
    --jar) JAR="$2"; shift 2 ;;
    --fake-script) FAKE_SCRIPT="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    -h|--help) usage ;;
    *) echo "未知参数: $1" >&2; usage ;;
  esac
done

say() { printf '%s\n' "$*"; }
pass() { printf 'PASS: %s\n' "$*"; }
die() {
  printf 'FAIL: %s\n' "$*" >&2
  printf '数据与日志保留在 %s（主日志: %s）\n' "$DATA_DIR" "$MAIN_LOG" >&2
  exit 1
}

# ---- 预检 ----
command -v java >/dev/null 2>&1 || { echo "缺 java（JDK 21）" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "缺 curl" >&2; exit 1; }
command -v sqlite3 >/dev/null 2>&1 || { echo "缺 sqlite3 CLI（apt install sqlite3）——事件链断言用" >&2; exit 1; }
[ -f "$JAR" ] || {
  echo "找不到 jar: $JAR —— 先构建: ./mvnw -pl MainMosire -am package -DskipTests" >&2
  exit 1
}

say "== M2 smoke（离线）: data=$DATA_DIR jar=$JAR"
rm -rf "$DATA_DIR"
mkdir -p "$DATA_DIR/templates"
MAIN_LOG="$DATA_DIR/main.log"

# 子 Agent 模板（reader）：只读问候——允许 list_sub_agents（父侧 SYSTEM 工具，经 MCP 回环调用会被拒——
# 这本身是"子 Agent 经 MCP 调父侧暴露工具"的往返证据）；脚本 = 一次被拒调用 + 收尾文本。
cat > "$DATA_DIR/templates/reader.json" <<'JSON'
{
  "id": "reader",
  "description": "smoke 只读问候子 Agent",
  "systemPrompt": "你是子 Agent（smoke 模板 reader）。你尝试查询父级编排工具后如实报告。",
  "model": "fake",
  "token": "DEFAULT",
  "allowedTools": ["list_sub_agents"],
  "deniedTools": [],
  "destructiveAllowed": false,
  "sensitiveAllowed": false,
  "readOnly": false,
  "maxTurns": 10,
  "maxToolCallsPerTurn": 5,
  "timeBudgetSeconds": 120,
  "quotaMaxTokens": 0,
  "script": [
    {"toolCall": "list_sub_agents", "args": {}, "text": ""},
    {"toolCall": null, "args": {}, "text": "问候完成，等待关闭信号"}
  ]
}
JSON

# ---- 启动主进程（heap 克制：-Xmx256m；子进程 -Xmx128m 由装配层固定）----
java -Xmx256m -jar "$JAR" run \
  --port "$PORT" --data-dir "$DATA_DIR" \
  --templates-dir "$DATA_DIR/templates" \
  --no-mcp-expose \
  --a2a-address 127.0.0.1 --a2a-port 0 \
  --agui-address 127.0.0.1 --agui-port 0 \
  --fake-script "$FAKE_SCRIPT" >> "$MAIN_LOG" 2>&1 &
MAIN_PID=$!
say "主进程 pid=$MAIN_PID，等待启动…"

cleanup() {
  local code=$?
  if [ -n "${MAIN_PID:-}" ] && kill -0 "$MAIN_PID" 2>/dev/null; then
    kill -TERM "$MAIN_PID" 2>/dev/null || true
    for _ in $(seq 1 20); do
      kill -0 "$MAIN_PID" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$MAIN_PID" 2>/dev/null; then
      echo "主进程 20s 内未退出，SIGKILL" >&2
      kill -KILL "$MAIN_PID" 2>/dev/null || true
    fi
  fi
  if [ "$code" -ne 0 ]; then
    printf 'FAIL（exit=%d）：数据与日志保留在 %s\n' "$code" "$DATA_DIR" >&2
  elif [ "$KEEP" -ne 1 ]; then
    rm -rf "$DATA_DIR"
  fi
  exit "$code"
}
trap cleanup EXIT

# ---- 解析实际端口（A2A/AG-UI 为自动分配）----
PORTS=""
for _ in $(seq 1 60); do
  PORTS="$(sed -n 's/.*admin=http:\/\/127\.0\.0\.1:\([0-9]*\) a2a=http:\/\/[^:]*:\([0-9]*\) agui=http:\/\/[^:]*:\([0-9]*\).*/\1 \2 \3/p' "$MAIN_LOG" | head -1)"
  [ -n "$PORTS" ] && break
  kill -0 "$MAIN_PID" 2>/dev/null || die "主进程启动失败（日志: $MAIN_LOG）"
  sleep 1
done
[ -n "$PORTS" ] || die "60s 内未解析到监听端口（日志: $MAIN_LOG）"
read -r ADMIN_PORT A2A_PORT AGUI_PORT <<< "$PORTS"
say "端口: admin=$ADMIN_PORT a2a=$A2A_PORT agui=$AGUI_PORT"

curl -fsS --max-time 30 "http://127.0.0.1:$ADMIN_PORT/health" | grep -q '"status":"ok"' &&
  pass "health"

# ---- A2A 黑盒 ----
curl -fsS --max-time 30 "http://127.0.0.1:$A2A_PORT/.well-known/agent-card.json" | grep -q 'mosire-main' &&
  pass "A2A agent-card"

RPC_BASE="http://127.0.0.1:$A2A_PORT/a2a"
a2a_post() { # $1=json 体
  curl -fsS --max-time 60 -X POST "$RPC_BASE" -H 'Content-Type: application/json' \
    -H 'A2A-Version: 1.0' -d "$1"
}
RESP="$(a2a_post '{"jsonrpc":"2.0","id":1,"method":"SendMessage","params":{"message":{"messageId":"smoke-a2a-1","role":"ROLE_USER","parts":[{"text":"你好，A2A 一回合"}]}}}')"
echo "$RESP" | grep -q 'TASK_STATE_SUBMITTED' || die "SendMessage 未回 SUBMITTED: $RESP"
TASK_ID="$(echo "$RESP" | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//; s/"$//')"
[ -n "$TASK_ID" ] || die "SendMessage 未解析到任务 id: $RESP"
pass "A2A message/send（task=$TASK_ID）"

STATE=""
for _ in $(seq 1 30); do
  STATE="$(a2a_post "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"GetTask\",\"params\":{\"id\":\"$TASK_ID\"}}")"
  echo "$STATE" | grep -q 'TASK_STATE_COMPLETED' && break
  sleep 1
done
echo "$STATE" | grep -q 'TASK_STATE_COMPLETED' || die "tasks/get 未达 COMPLETED: $STATE"
pass "A2A tasks/get（COMPLETED）"

STREAM="$(a2a_post '{"jsonrpc":"2.0","id":3,"method":"SendStreamingMessage","params":{"message":{"messageId":"smoke-a2a-2","role":"ROLE_USER","parts":[{"text":"流式一回合"}]}}}')"
echo "$STREAM" | grep -q '^data: ' || die "SendStreamingMessage 无 SSE 帧: $STREAM"
echo "$STREAM" | grep -q 'TASK_STATE_COMPLETED' || die "A2A 流式未包含终态: $STREAM"
pass "A2A 流式（SSE 帧含 COMPLETED）"

# ---- AG-UI SSE（会话 1 = spawn 回合：tool 调用 + 文本 + 终态）----
GUI_BASE="http://127.0.0.1:$AGUI_PORT"
curl -fsS --max-time 30 -X POST "$GUI_BASE/sessions" -H 'Content-Type: application/json' \
  -d '{"threadId":"s-1","messages":[{"role":"user","content":"派一个问候子 Agent"}]}' | grep -q '"id":"s-1"' ||
  die "AG-UI POST /sessions 失败"
S1="$(curl -fsS --max-time 120 "$GUI_BASE/sessions/s-1/events")"
for frame in RUN_STARTED TOOL_CALL_START TOOL_CALL_RESULT TEXT_MESSAGE_CONTENT RUN_FINISHED; do
  echo "$S1" | grep -q "$frame" || die "AG-UI s-1 流缺 $frame"
done
pass "AG-UI SSE s-1（RUN_STARTED→TOOL_CALL_*→TEXT→RUN_FINISHED）"

# ---- spawn 观测（/api/agents 轮询至 RUNNING）+ /api/tools 名单 ----
TOOLS="$(curl -fsS --max-time 30 "http://127.0.0.1:$ADMIN_PORT/api/tools")"
for tool in spawn_sub_agent kill_sub_agent list_sub_agents; do
  echo "$TOOLS" | grep -q "$tool" || die "/api/tools 缺 $tool: $TOOLS"
done
pass "/api/tools（registry 名单含三个编排工具）"

AGENTS=""
for _ in $(seq 1 30); do
  AGENTS="$(curl -fsS --max-time 30 "http://127.0.0.1:$ADMIN_PORT/api/agents")"
  echo "$AGENTS" | grep -q '"templateId":"reader"' || { sleep 1; continue; }
  echo "$AGENTS" | grep -q '"status":"RUNNING"' && break
  sleep 1
done
echo "$AGENTS" | grep -q '"status":"RUNNING"' || die "子 Agent 未达 RUNNING: $AGENTS"
CHILD_ID="$(echo "$AGENTS" | grep -o '"instanceId":"[^"]*"' | head -1 | sed 's/"instanceId":"//; s/"$//')"
[ -n "$CHILD_ID" ] || die "未解析到子 Agent 实例 id: $AGENTS"
pass "spawn（/api/agents 观测到 $CHILD_ID RUNNING）"

# ---- MCP 回环往返（先证后杀：子体事件库出现 list_sub_agents 的 permission.denied/tool.result 才往下走）----
# 注：RUNNING 仅是管理器状态，不保证子体 JVM 已就绪——省掉这一步会在子体还启动时就把 SIGTERM 杀下去（首跑失败的真实教训）。
CHILD_DB="$DATA_DIR/subagents/$CHILD_ID/events.db"
child_roundtrip() {
  [ -f "$CHILD_DB" ] || return 1
  local found
  found="$(sqlite3 "$CHILD_DB" "select count(*) from events where type='permission.denied' and json_extract(payload,'\$.tool')='list_sub_agents' and json_extract(payload,'\$.reason') like '%仅限主 Agent%';" 2>/dev/null || echo 0)"
  [ "$found" -ge 1 ] || return 1
  found="$(sqlite3 "$CHILD_DB" "select count(*) from events where type='tool.result' and json_extract(payload,'\$.tool')='list_sub_agents' and json_extract(payload,'\$.ok')=0;" 2>/dev/null || echo 0)"
  [ "$found" -ge 1 ]
}
ROUNDTRIP=""
for _ in $(seq 1 60); do
  if child_roundtrip; then ROUNDTRIP=1; break; fi
  if ! pgrep -f "Main agent --id $CHILD_ID" >/dev/null 2>&1; then
    [ -f "$CHILD_DB" ] && sqlite3 -header "$CHILD_DB" "select seq,type,substr(payload,1,120) as payload from events order by seq;" >&2 || true
    die "子 Agent 进程已退出且未见 list_sub_agents 往返（MCP 回环失败）"
  fi
  sleep 1
done
[ -n "$ROUNDTRIP" ] || die "60s 内未见子体 list_sub_agents 往返（permission.denied/tool.result）——MCP 回环失败"
pass "子 Agent MCP 回环（list_sub_agents 被父侧 SYSTEM 闸拒——往返已落子体事件库）"

# ---- kill（AG-UI 会话 2：$spawnedId 占位符 → kill_sub_agent）----
curl -fsS --max-time 30 -X POST "$GUI_BASE/sessions" -H 'Content-Type: application/json' \
  -d '{"threadId":"s-2","messages":[{"role":"user","content":"终止刚派出的问候子 Agent"}]}' | grep -q '"id":"s-2"' ||
  die "AG-UI POST /sessions(s-2) 失败"
S2="$(curl -fsS --max-time 120 "$GUI_BASE/sessions/s-2/events")"
echo "$S2" | grep -q 'RUN_FINISHED' || die "AG-UI s-2 流缺终态: $S2"
echo "$S2" | grep -q 'TOOL_CALL_RESULT' || die "AG-UI s-2 流缺 kill 工具结果: $S2"
pass "kill（AG-UI s-2：kill_sub_agent 回合完成）"

# ---- 事件链断言（sqlite3；主进程仍在跑，WAL 并发读安全）----
DB="$DATA_DIR/events.db"
CHILD_DB="$DATA_DIR/subagents/$CHILD_ID/events.db"

SPAWN_CALLS="$(sqlite3 "$DB" "select count(*) from events where agent='main' and type='tool.call' and json_extract(payload,'\$.tool')='spawn_sub_agent';")"
[ "$SPAWN_CALLS" -ge 1 ] || die "主事件库缺 spawn_sub_agent tool.call"
SPAWN_OK="$(sqlite3 "$DB" "select count(*) from events where agent='main' and type='tool.result' and json_extract(payload,'\$.tool')='spawn_sub_agent' and json_extract(payload,'\$.ok')=1;")"
[ "$SPAWN_OK" -ge 1 ] || die "主事件库缺 spawn_sub_agent tool.result(ok)"
KILL_CALLS="$(sqlite3 "$DB" "select count(*) from events where agent='main' and type='tool.call' and json_extract(payload,'\$.tool')='kill_sub_agent' and json_extract(payload,'\$.args.instanceId')='$CHILD_ID';")"
[ "$KILL_CALLS" -ge 1 ] || die "主事件库缺 kill_sub_agent tool.call（$CHILD_ID）"
KILL_OK="$(sqlite3 "$DB" "select count(*) from events where agent='main' and type='tool.result' and json_extract(payload,'\$.tool')='kill_sub_agent' and json_extract(payload,'\$.ok')=1;")"
[ "$KILL_OK" -ge 1 ] || die "主事件库缺 kill_sub_agent tool.result(ok)"

# kill 执行是异步的：RUN_FINISHED 后 lifecycle 链的 killed 落库可能有延迟——轮询至链完整
CHAIN=""
for _ in $(seq 1 30); do
  CHAIN="$(sqlite3 "$DB" "select group_concat(json_extract(payload,'\$.action'), ',') from (select payload from events where agent='$CHILD_ID' and type='agent.lifecycle' order by seq);")"
  [ "$CHAIN" = "configured,spawning,running,terminating,killed" ] && break
  sleep 1
done
[ "$CHAIN" = "configured,spawning,running,terminating,killed" ] || die "生命周期链异常: [$CHAIN]"
pass "事件链（spawn/kill 工具调用结果 + 生命周期 configured→…→killed）"

# 子体事件库：MCP 回环调用父侧编排工具 → 父侧 SYSTEM 闸 → permission.denied 落在子体（往返证据）
CHILD_TOOL_CALLS="0"; CHILD_DENIED="0"
for _ in $(seq 1 20); do
  if [ -f "$CHILD_DB" ]; then
    CHILD_TOOL_CALLS="$(sqlite3 "$CHILD_DB" "select count(*) from events where type='tool.call' and json_extract(payload,'\$.tool')='list_sub_agents';")"
    CHILD_DENIED="$(sqlite3 "$CHILD_DB" "select count(*) from events where type='permission.denied' and json_extract(payload,'\$.tool')='list_sub_agents';")"
    [ "$CHILD_TOOL_CALLS" -ge 1 ] && [ "$CHILD_DENIED" -ge 1 ] && break
  fi
  sleep 1
done
[ "$CHILD_TOOL_CALLS" -ge 1 ] || die "子体事件库缺 list_sub_agents tool.call（MCP 往返）"
[ "$CHILD_DENIED" -ge 1 ] || die "子体事件库缺 permission.denied（list_sub_agents 系统级工具对子 Agent 拒绝）"
pass "子 Agent MCP 回环工具调用（被父侧 SYSTEM 闸拒——permission.denied 落子体事件库）"

# 事件面快照（供记录/审计；select * 全量 + 关键列投影）
sqlite3 "$DB" "select * from events;" > "$DATA_DIR/events-all.tsv" || true
sqlite3 -header "$DB" "select seq, type, agent, json_extract(payload,'\$.action') as action, json_extract(payload,'\$.tool') as tool from events order by seq;" > "$DATA_DIR/events-dump.tsv" || true

say "== M2 smoke 全链完成（child=$CHILD_ID 已 killed；事件链断言全绿）"
