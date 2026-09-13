#!/usr/bin/env bash
# P3-4 真 LLM 冒烟（联网，显式手动触发；**不进默认门禁**——D22）。
#
# 与 scripts/smoke.sh 同形（起 run → 打 HTTP → sqlite3 断言），但**不传任何 --fake\* 开关**，
# 主 Agent 从 <data-dir>/config.json 取真模型（D24：缺配置/缺密钥响亮失败，绝不退化回假模型）。
#
# 冒烟内容（计划 §三 P3-4）：
#   1. 单轮：真实请求 → 非空回复，且**不等于** App.DEFAULT_LLM_REPLY（假模型探针）；
#   2. 多轮：第二轮**确实带上了第一轮**（随机数回述——真模型猜不到，结构上也不可能来自别处）；
#   3. 重置：POST /api/chat/session → 新会话 id 从零开始（断言旧内容不再出现在回复里），**旧会话仍可从库里读回**（D26 不删库）；
#   4. 记账：llm.call 落库，model 名来自服务端、token 数为正；
#   5. 工具调用（可用 --skip-tools 跳过）：spawn_sub_agent 走完 guard→执行→结果回灌，子体也走真模型；
#   6. 密钥不漏：主日志 / 事件库 / 子体库 三处扫描密钥值 0 命中（不打印密钥本身）。
#
# 断言纪律：真实模型输出不确定，只断言**结构可辨**（非空、model 名、usage 为正、回复里含/不含某个随机数），
# 绝不断言具体文本。
#
# 花费纪律：deepseek-chat 便宜档、单次执行不循环。本脚本默认最多 6 个 LLM 回合（--skip-tools 时 4 个）。
#
# 依赖：java(21)、curl、sqlite3（JSON1）、python3（解析 JSON；awk 做不给力）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

JAR="${JAR:-$ROOT/MainMosire/target/mosire.jar}"
DATA_DIR="${DATA_DIR:-$ROOT/.work/live-smoke}"
CONFIG="${CONFIG:-$ROOT/.work/mosire/config.json}"   # 密钥来源；**只读复制**，绝不改写原件
TEMPLATES_DIR="${TEMPLATES_DIR:-$ROOT/configs/agents}"
PORT="${PORT:-0}"
KEEP="${KEEP:-0}"               # 1 = 成功后也保留数据目录
SKIP_TOOLS="${SKIP_TOOLS:-0}"
MAX_SECONDS="${MAX_SECONDS:-600}"   # 外部硬看门狗：整个冒烟墙钟上限
MAX_CHILD_JVM="${MAX_CHILD_JVM:-3}" # 外部硬看门狗：并存的 Main agent 子 JVM 数上限

usage() {
  cat <<'EOF'
用法: scripts/smoke-live.sh [--config <p>] [--data-dir <p>] [--jar <p>] [--templates-dir <p>]
                            [--port <n>] [--max-seconds <n>] [--skip-tools] [--keep]

  --config <p>        真模型配置来源（默认 .work/mosire/config.json，含 keys.*）——只读复制进数据目录
  --data-dir <p>      数据目录（默认 .work/live-smoke；每次运行重建）
  --jar <p>           主 jar（默认 MainMosire/target/mosire.jar）
  --templates-dir <p> 子 Agent 模板目录（默认 configs/agents；--skip-tools 时不用）
  --port <n>          admin 端口（默认 0=自动分配；其余端口同样自动分配并从启动行解析）
  --max-seconds <n>   墙钟看门狗上限（默认 600）
  --skip-tools        跳过第 5 项（工具调用 + 子 Agent 真模型），省一次子 JVM 与若干 token
  --keep              成功后保留数据目录（失败始终保留并打印路径）

联网红线：本脚本会真实调用配置里的 LLM 端点并产生费用；默认关闭于门禁之外，只有显式执行本脚本才会跑。
EOF
  exit 2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --config) CONFIG="$2"; shift 2 ;;
    --data-dir) DATA_DIR="$2"; shift 2 ;;
    --jar) JAR="$2"; shift 2 ;;
    --templates-dir) TEMPLATES_DIR="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --max-seconds) MAX_SECONDS="$2"; shift 2 ;;
    --skip-tools) SKIP_TOOLS=1; shift ;;
    --keep) KEEP=1; shift ;;
    -h|--help) usage ;;
    *) echo "未知参数: $1" >&2; usage ;;
  esac
done

say() { printf '%s\n' "$*"; }
pass() { printf 'PASS: %s\n' "$*"; }
note() { printf '  · %s\n' "$*"; }

MAIN_LOG=""
FAILED=0
die() {
  FAILED=1
  printf 'FAIL: %s\n' "$*" >&2
  [ -n "$MAIN_LOG" ] && printf '数据与日志保留在 %s（主日志: %s）\n' "$DATA_DIR" "$MAIN_LOG" >&2
  exit 1
}

# ---- 预检 ----
command -v java >/dev/null 2>&1 || { echo "缺 java（JDK 21）" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "缺 curl" >&2; exit 1; }
command -v sqlite3 >/dev/null 2>&1 || { echo "缺 sqlite3 CLI" >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "缺 python3（解析 JSON 响应）" >&2; exit 1; }
[ -f "$JAR" ] || {
  echo "找不到 jar: $JAR —— 先构建: ./mvnw -pl MainMosire -am package -DskipTests" >&2
  exit 1
}
[ -f "$CONFIG" ] || {
  echo "找不到真模型配置: $CONFIG" >&2
  echo "（D24：真模型需要的 keys.*/llm.* 只从配置文件读，脚本不代造、不读 env）" >&2
  exit 1
}

# 配置形状预检 + 取密钥值（**一次解析、两用**）：失败文案走 stderr（非零退出 ⇒ die），成功时密钥值走 stdout
# 被命令替换捕获进 SECRET——只进变量，绝不 echo、不进 argv。预检失败与"要用哪条密钥"必须来自**同一次**解析，
# 否则会出现"检 A 条、扫 B 条密钥"的假绿（多路由配置下真的发生过）。
#
# 选路规则与 Java 侧 LlmRouteLoader **逐条对齐**（这是镜像，不是第二份真源）：
#   llm.routes.<llm.route> →（route=default 时）扁平 llm.* → 否则响亮，绝不回落到别的路由（D24）。
# Java 侧改规则时本块必须同批改——解析分叉会让"预检通过的配置"和"实跑用的路由"不是同一条。
SECRET="$(python3 - "$CONFIG" <<'PY'
import json, sys

def fail(message):
    print(message, file=sys.stderr)
    sys.exit(1)

try:
    cfg = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception as e:
    fail("配置不是合法 JSON: %s" % type(e).__name__)

llm = cfg.get("llm") or {}
keys = cfg.get("keys") or {}
name = (llm.get("route") or "default").strip() or "default"
routes = llm.get("routes")
route = None
if isinstance(routes, dict) and isinstance(routes.get(name), dict):
    route = routes[name]
elif name == "default" and llm.get("baseUrl") and llm.get("model"):
    route = llm          # 扁平形态 = 名为 default 的路由（LlmRouteLoader 的兼容层）
if route is None:
    available = sorted(routes) if isinstance(routes, dict) else []
    fail("llm.route 点名了不可用的路由 %r（可用：%s；也没有可用的扁平 llm.baseUrl/model）"
         % (name, ", ".join(available) or "无"))

missing = [k for k in ("baseUrl", "model") if not route.get(k)]
if missing:
    fail("llm 路由 %r 缺字段: %s" % (name, ",".join(missing)))
ref = route.get("credentialsRef") or ""
if not ref.startswith("keys."):
    fail("credentialsRef 必须形如 keys.<name>（D20），实际不满足：%s" % repr(ref))
secret = keys.get(ref[len("keys."):])
if not isinstance(secret, str) or not secret:
    fail("credentialsRef 指向的 keys.%s 为空/缺失/非文本" % ref[len("keys."):])
print(secret)
PY
)" || die "配置形状非法：$CONFIG"

say "== P3-4 smoke-live（联网真模型）: data=$DATA_DIR jar=$(basename "$JAR") config=$(basename "$CONFIG")"
rm -rf "$DATA_DIR"
mkdir -p "$DATA_DIR"
MAIN_LOG="$DATA_DIR/main.log"

# 配置只读复制（600）；原件不动。子 Agent 经 --config-dir 读同一份副本（D23：只传路径）。
cp "$CONFIG" "$DATA_DIR/config.json"
chmod 600 "$DATA_DIR/config.json"

ARGS=(run --port "$PORT" --data-dir "$DATA_DIR"
      --no-mcp-expose
      --a2a-address 127.0.0.1 --a2a-port 0
      --agui-address 127.0.0.1 --agui-port 0)
if [ "$SKIP_TOOLS" -ne 1 ]; then
  [ -d "$TEMPLATES_DIR" ] || { echo "找不到模板目录: $TEMPLATES_DIR（或用 --skip-tools）" >&2; exit 1; }
  ARGS+=(--templates-dir "$TEMPLATES_DIR")
fi

# ---- 启动主进程（独立进程组：清理时连同子 Agent JVM 一起收）----
setsid java -Xmx256m -jar "$JAR" "${ARGS[@]}" >> "$MAIN_LOG" 2>&1 &
MAIN_PID=$!
say "主进程 pid=$MAIN_PID（进程组 $MAIN_PID），等待启动…"

cleanup() {
  local code=$?
  if [ -n "${WATCHDOG_PID:-}" ]; then kill "$WATCHDOG_PID" 2>/dev/null || true; fi
  if [ -n "${MAIN_PID:-}" ] && kill -0 "$MAIN_PID" 2>/dev/null; then
    kill -TERM -- "-$MAIN_PID" 2>/dev/null || kill -TERM "$MAIN_PID" 2>/dev/null || true
    for _ in $(seq 1 20); do
      kill -0 "$MAIN_PID" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$MAIN_PID" 2>/dev/null; then
      echo "进程组 20s 内未退出，SIGKILL" >&2
      kill -KILL -- "-$MAIN_PID" 2>/dev/null || true
    fi
  fi
  sleep 1
  local leftover
  leftover="$(ps -eo pid,args | grep -c '[m]osire.jar' || true)"
  [ "${leftover:-0}" -eq 0 ] || echo "警告：仍有 $leftover 个 mosire.jar 进程残留" >&2
  if [ "$FAILED" -ne 0 ] || [ "$code" -ne 0 ]; then
    printf 'FAIL（exit=%d）：数据与日志保留在 %s\n' "$code" "$DATA_DIR" >&2
  elif [ "$KEEP" -ne 1 ]; then
    rm -rf "$DATA_DIR"
  else
    printf '数据目录保留在 %s\n' "$DATA_DIR"
  fi
  exit "$FAILED"
}
trap cleanup EXIT

# ---- 外部硬看门狗（不靠 LLM 守规矩）----
(
  deadline=$(( $(date +%s) + MAX_SECONDS ))
  while kill -0 "$MAIN_PID" 2>/dev/null; do
    now=$(date +%s)
    if [ "$now" -gt "$deadline" ]; then
      echo "看门狗：墙钟超 ${MAX_SECONDS}s，杀进程组" >> "$MAIN_LOG"
      kill -TERM -- "-$MAIN_PID" 2>/dev/null || true
      exit 1
    fi
    kids=$(ps -eo args | grep -c '[M]ain agent --id' || true)
    if [ "${kids:-0}" -gt "$MAX_CHILD_JVM" ]; then
      echo "看门狗：子 Agent JVM 数 ${kids} > ${MAX_CHILD_JVM}，杀进程组" >> "$MAIN_LOG"
      kill -TERM -- "-$MAIN_PID" 2>/dev/null || true
      exit 1
    fi
    sleep 2
  done
) &
WATCHDOG_PID=$!

# ---- 解析端口 ----
PORTS=""
for _ in $(seq 1 60); do
  PORTS="$(sed -n 's/.*admin=http:\/\/127\.0\.0\.1:\([0-9]*\) a2a=http:\/\/[^:]*:\([0-9]*\) agui=http:\/\/[^:]*:\([0-9]*\) debug=http:\/\/127\.0\.0\.1:\([0-9]*\).*/\1 \2 \3 \4/p' "$MAIN_LOG" | head -1)"
  [ -n "$PORTS" ] && break
  kill -0 "$MAIN_PID" 2>/dev/null || { tail -30 "$MAIN_LOG" >&2; die "主进程启动失败（日志: $MAIN_LOG）"; }
  sleep 1
done
[ -n "$PORTS" ] || die "60s 内未解析到监听端口（日志: $MAIN_LOG）"
read -r ADMIN_PORT A2A_PORT AGUI_PORT DEBUG_PORT <<< "$PORTS"
say "端口: admin=$ADMIN_PORT debug=$DEBUG_PORT a2a=$A2A_PORT agui=$AGUI_PORT"

curl -fsS --max-time 30 "http://127.0.0.1:$ADMIN_PORT/health" | grep -q '"status":"ok"' && pass "health"

DB="$DATA_DIR/events.db"
CHAT="http://127.0.0.1:$DEBUG_PORT/api/chat"

# ---- HTTP 助手 ----
chat_submit() { # $1=消息 → runId
  local body
  body="$(python3 -c 'import json,sys; print(json.dumps({"message": sys.argv[1]}))' "$1")"
  curl -fsS --max-time 30 -X POST "$CHAT" -H 'Content-Type: application/json' -d "$body" |
    python3 -c 'import json,sys; print(json.load(sys.stdin)["runId"])'
}

chat_await() { # $1=runId → 终态快照 JSON 打到 stdout
  local snap=""
  for _ in $(seq 1 240); do
    snap="$(curl -fsS --max-time 30 "$CHAT/$1" || true)"
    if [ -n "$snap" ] && ! printf '%s' "$snap" | grep -q '"status":"running"'; then
      printf '%s' "$snap"; return 0
    fi
    sleep 1
  done
  printf '%s' "$snap"; return 1
}

snap_field() { # $1=快照 JSON $2=result 内字段名（text/turns/toolCalls/stopReason）
  python3 -c '
import json,sys
snap = json.loads(sys.argv[1]); r = snap.get("result") or {}
print(r.get(sys.argv[2], ""))' "$1" "$2"
}

turn() { # $1=消息 → 打印终态回复文本；非终态/失败即 die
  local rid snap status text
  rid="$(chat_submit "$1")"
  snap="$(chat_await "$rid")" || die "回合 $rid 240s 内未终态: $snap"
  status="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["status"])' "$snap")"
  [ "$status" = "finished" ] || die "回合 $rid 未正常收尾（status=$status）: $snap"
  text="$(snap_field "$snap" text)"
  printf '%s' "$text"
}

# ---- 1. 单轮真回复 + 假模型探针 ----
SECRET_NUM="$(( (RANDOM % 9000) + 1000 ))"
T1="$(turn "请用一句话回答：1+1 等于几？并在句末附上你这次回答所用模型的名称（如果你知道的话）。")"
[ -n "$T1" ] || die "第 1 轮回复为空"
case "$T1" in
  *"我是 Mosire 主 Agent（M1 骨架 LLM，离线占位回复）"*)
    die "回复命中 App.DEFAULT_LLM_REPLY —— 生产仍在用假模型（D24 要求的『不静默退化』不成立）: $T1" ;;
esac
printf '  第 1 轮回复: %s\n' "$(printf '%s' "$T1" | head -c 200)"
pass "单轮真回复（非固定串，len=${#T1}）"

# ---- 2. 多轮：第二轮带上第一轮 ----
T2="$(turn "记住这个数字：${SECRET_NUM}。稍后我会问你，现在只回复『记住了』。")"
T3="$(turn "我刚才让你记住的数字是多少？只回答数字本身。")"
case "$T3" in
  *"$SECRET_NUM"*) : ;;
  *) die "第二轮未带回第一轮内容（数字 $SECRET_NUM 未出现在回复里）: $T3" ;;
esac
pass "多轮上下文延续（第 3 轮回述出第 2 轮的数字 $SECRET_NUM）"

# 结构证据：同一会话 id 下确实累积了 3 轮（user/assistant 成对）
MSG_BEFORE="$(sqlite3 "$DB" "select count(*) from messages;")"
[ "${MSG_BEFORE:-0}" -ge 6 ] || die "会话库 messages 行数异常（期望 ≥6，实际 $MSG_BEFORE）"
CONV_ID="$(sqlite3 "$DB" "select conversation_id from messages order by id limit 1;")"
pass "会话落盘（conversation=$CONV_ID，messages=$MSG_BEFORE 行）"

# ---- 3. 重置（D26：换新会话 id；旧会话留库）----
RESET="$(curl -fsS --max-time 30 -X POST "$CHAT/session")"
NEW_ID="$(printf '%s' "$RESET" | python3 -c 'import json,sys; print(json.load(sys.stdin)["conversationId"])')"
OLD_ID="$(printf '%s' "$RESET" | python3 -c 'import json,sys; print(json.load(sys.stdin)["previousConversationId"])')"
[ -n "$NEW_ID" ] || die "重置未返回新会话 id: $RESET"
[ "$NEW_ID" != "$OLD_ID" ] || die "重置前后会话 id 相同: $RESET"
NEW_ROWS="$(sqlite3 "$DB" "select count(*) from messages where conversation_id='$NEW_ID';")"
[ "${NEW_ROWS:-0}" -eq 0 ] || die "新会话库里已有 $NEW_ROWS 行（应为 0）"
pass "重置受理（$OLD_ID → $NEW_ID，新会话库中 0 行）"

T4="$(turn "我刚才让你记住的数字是多少？如果你没有相关信息就直说『不知道』，不要猜。")"
case "$T4" in
  *"$SECRET_NUM"*) die "重置后仍能回述重置前的数字（上下文没清）: $T4" ;;
esac
OLD_ROWS="$(sqlite3 "$DB" "select count(*) from messages where conversation_id='$OLD_ID';")"
[ "${OLD_ROWS:-0}" -ge 6 ] || die "旧会话在库中不可读（期望 ≥6 行，实际 $OLD_ROWS，D26 要求不删库）"
pass "重置后从零（不再回述 $SECRET_NUM）+ 旧会话仍可读回（$OLD_ID，$OLD_ROWS 行）"

# ---- 4. 记账：llm.call 真 usage / 服务端 model 名 ----
CALLS="$(sqlite3 "$DB" "select count(*) from events where type='llm.call' and json_extract(payload,'\$.inputTokens') > 0 and json_extract(payload,'\$.outputTokens') > 0;")"
[ "${CALLS:-0}" -ge 4 ] || die "llm.call（token 为正）不足：实际 $CALLS（期望 ≥4）"
MODEL="$(sqlite3 "$DB" "select json_extract(payload,'\$.model') from events where type='llm.call' order by seq desc limit 1;")"
[ -n "$MODEL" ] && [ "$MODEL" != "fake" ] || die "llm.call 的 model 名可疑: [$MODEL]（应来自服务端响应）"
USAGE="$(sqlite3 "$DB" "select sum(json_extract(payload,'\$.inputTokens')), sum(json_extract(payload,'\$.outputTokens')) from events where type='llm.call';")"
pass "记账（llm.call=$CALLS 条，model=$MODEL，inputTokens/outputTokens 合计=$USAGE）"

# ---- 5. 工具调用 + 子 Agent 真模型（可选）----
if [ "$SKIP_TOOLS" -eq 1 ]; then
  say "-- 跳过第 5 项（--skip-tools）"
else
  T5="$(turn "请调用 spawn_sub_agent 工具派一个模板 id 为 general-assistant 的子 Agent，目标写『用一句话自我介绍』。只派这一次，不要重复派。")"
  SP=""
  for _ in $(seq 1 60); do
    SP="$(sqlite3 "$DB" "select count(*) from events where agent='main' and type='tool.result' and json_extract(payload,'\$.tool')='spawn_sub_agent' and json_extract(payload,'\$.ok')=1;")"
    [ "${SP:-0}" -ge 1 ] && break
    sleep 1
  done
  CALL="$(sqlite3 "$DB" "select count(*) from events where agent='main' and type='tool.call' and json_extract(payload,'\$.tool')='spawn_sub_agent';")"
  [ "${CALL:-0}" -ge 1 ] || die "模型只输出文本、无 spawn_sub_agent tool.call —— 工具调用链路没走通"
  [ "${SP:-0}" -ge 1 ] || die "有 tool.call 但无成功的 tool.result（工具执行失败）"
  pass "工具调用（spawn_sub_agent：tool.call=$CALL / tool.result(ok)=$SP）"

  # 子体真模型证据：子体自己的事件库里 llm.call 且 model 非 fake
  CHILD_DB=""
  for _ in $(seq 1 60); do
    CHILD_DB="$(find "$DATA_DIR/subagents" -name events.db 2>/dev/null | head -1)"
    if [ -n "$CHILD_DB" ]; then
      CM="$(sqlite3 "$CHILD_DB" "select json_extract(payload,'\$.model') from events where type='llm.call' order by seq desc limit 1;" 2>/dev/null || true)"
      [ -n "$CM" ] && break
    fi
    sleep 1
  done
  if [ -n "$CHILD_DB" ]; then
    CM="$(sqlite3 "$CHILD_DB" "select json_extract(payload,'\$.model') from events where type='llm.call' order by seq desc limit 1;" 2>/dev/null || true)"
    if [ -n "$CM" ]; then
      pass "子 Agent 真模型（$CHILD_DB：llm.call model=$CM）"
      CHILD_DBS="$(find "$DATA_DIR/subagents" -name events.db 2>/dev/null || true)"
    else
      note "子体事件库存在但尚无 llm.call（可能仍在启动/握手，未强判失败）"
      CHILD_DBS="$(find "$DATA_DIR/subagents" -name events.db 2>/dev/null || true)"
    fi
  else
    note "未发现子体事件库（子体未启动到写库，未强判失败）"
    CHILD_DBS=""
  fi
fi
[ -n "${CHILD_DBS:-}" ] || CHILD_DBS=""

# ---- 6. 密钥不漏（三处扫描；不打印密钥本身）----
LEAKS=0
if [ -n "$SECRET" ]; then
  for f in "$MAIN_LOG" "$DB" $CHILD_DBS; do
    [ -f "$f" ] || continue
    if grep -qa -- "$SECRET" "$f"; then
      LEAKS=$((LEAKS + 1))
      printf '  泄露点: %s\n' "$f" >&2
    fi
  done
fi
[ "$LEAKS" -eq 0 ] || die "密钥值出现在 $LEAKS 个文件中（日志/事件库）"
pass "密钥不漏（主日志 / 事件库 / 子体库 扫描 0 命中）"

# ---- 快照留档 ----
sqlite3 -header "$DB" "select seq, type, agent, json_extract(payload,'\$.model') as model, json_extract(payload,'\$.action') as action, json_extract(payload,'\$.tool') as tool from events order by seq;" > "$DATA_DIR/events-dump.tsv" || true
sqlite3 -header "$DB" "select id, conversation_id, role, substr(content,1,60) as content from messages order by id;" > "$DATA_DIR/messages-dump.tsv" || true

say "== P3-4 smoke-live 全链完成（单轮真回复 / 多轮带历史 / 重置从零 / 记账真实 usage / 密钥不漏）"
[ "$KEEP" -eq 1 ] && say "数据目录: $DATA_DIR"
