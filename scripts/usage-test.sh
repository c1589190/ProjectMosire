#!/usr/bin/env bash
# S2-1 使用模式测试台（D29）——真进程 + HTTP 对话 + 证据包。
#
# 与 scripts/smoke-live.sh（P3-4）的分工：那份是**固定断言序列**的冒烟；本台架**不做断言**，
# 只负责把程序跑起来、把交互口交出去、把你说的每句话和程序回的每个字**原样存证**。
# 判读是测试员（人/子代理）的事：HTTP 结果 + 日志 + 事件库三方互证（D29 §一.3）。
#
# 子命令：
#   start   起真进程（不传任何 --fake*；配置/密钥取自 <数据目录>/config.json，D20/D23）
#   say     发一条消息，等终态，存原始请求/快照，打印状态与回复原文
#   session 读当前会话 id（GET /api/chat/session）
#   reset   重置会话（POST /api/chat/session）
#   db      把事件库/会话库快照进证据包；--query 可跑任意只读 SQL
#   scan    密钥扫描（日志 + 事件库 + 子体库 + 证据包，0 命中才算干净；不打印密钥本身）
#   logs    看主进程日志尾部
#   status  本台架当前状态（进程活着吗、几个回合、包在哪）
#   stop    收工：杀进程组、补最终 dump、写证据包索引 PACK.md
#
# 布局（一个数据目录 = 一个长期实验台；每次 start 产出一个证据包）：
#   <数据目录>/config.json                    进程真正读的那份（600）
#   <数据目录>/events.db, subagents/…         运行产物
#   <数据目录>/usage-test/<时间戳>/            证据包（本台架产出）
#       state.env  main.log  turns/0001.*.json  db/*.tsv  scan.txt  PACK.md
#   <数据目录>/.usage/current                 指向当前证据包
#
# 硬约束：同一时刻**只起一个真进程**（本机 1.6 GB，start 全局自检）；进程组级清理（setsid）；
# 数据目录必须在 gitignore 内（否则拒绝——证据包里有 600 的配置副本，不能落在受控目录）。
#
# 联网红线：真调用、真花钱。不进默认门禁（D29），只有显式执行本脚本才会跑。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR_DEFAULT="$ROOT/MainMosire/target/mosire.jar"
CONFIG_DEFAULT="$ROOT/.work/mosire/config.json"
TEMPLATES_DEFAULT="$ROOT/configs/agents"
APP_SRC="$ROOT/MainMosire/src/main/java/io/mosire/main/app/App.java"
# 骨架占位回复（假模型探针）：与 App.DEFAULT_LLM_REPLY 逐字一致，start 时回源校验，防漂移
SKELETON='我是 Mosire 主 Agent（M1 骨架 LLM，离线占位回复）'

say() { printf '%s\n' "$*"; }
note() { printf '  · %s\n' "$*"; }
die() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
用法:
  scripts/usage-test.sh start [--name <n>] [--data-dir <p>] [--config <p>] [--jar <p>]
                              [--templates-dir <p>] [--no-templates] [--port <n>]
                              [--max-seconds <n>] [--xmx <m>] [--fresh]
  scripts/usage-test.sh say [--timeout <s>] <消息>      # 消息传 "-" 则从 stdin 读
  scripts/usage-test.sh session | reset
  scripts/usage-test.sh db [--query "<SQL>"]
  scripts/usage-test.sh scan | logs [--lines <n>] | status | stop

  非 start 命令默认作用于「**最近一次 start 的数据目录**」（`.work/usage-test/.current` 指针），
  用 `--data-dir <p>` 可指定别的台架。

  start 选项：
    --name <n>           本次实验名（进证据包目录名，便于事后辨认；默认 run）
    --data-dir <p>       数据目录（默认 .work/usage-test/<name>）；**每次 start 不擦除**，
                         跨重启场景（U3/U4）就靠它；--fresh 才清（保留 usage-test/ 证据包）
    --config <p>         真模型配置来源（默认 .work/mosire/config.json），只读复制成 600
    --jar <p>            主 jar（默认 MainMosire/target/mosire.jar）
    --templates-dir <p>  子 Agent 模板目录（默认 configs/agents）
    --no-templates       不挂模板目录（不测子 Agent 时省内存）
    --port <n>           admin 端口（默认 0=自动分配；其余端口一律自动分配并从启动行解析）
    --max-seconds <n>    外部墙钟看门狗（默认 1800）
    --xmx <m>            主进程堆上限（默认 256）
    --fresh              先清掉数据目录里的运行产物（config/events.db/subagents/日志），
                         但**保留 usage-test/ 已有证据包**

  say/db/scan 等命令针对「最近一次 start 的证据包」；换数据目录请重新 start。
EOF
  exit 2
}

# ---------- 状态读写 ----------
CUR_PACK=""
load_state() {
  local ptr="$1/.usage/current"
  [ -f "$ptr" ] || die "本数据目录还没有 start 过（缺 $ptr）——先跑 usage-test.sh start"
  CUR_PACK="$(cat "$ptr")"
  [ -f "$CUR_PACK/state.env" ] || die "证据包状态文件缺失: $CUR_PACK/state.env"
  # shellcheck disable=SC1090
  . "$CUR_PACK/state.env"
}
save_state() {
  {
    printf 'RUN_NAME=%q\n' "$RUN_NAME"
    printf 'DATA_DIR=%q\n' "$DATA_DIR"
    printf 'PACK_DIR=%q\n' "$PACK_DIR"
    printf 'MAIN_LOG=%q\n' "$MAIN_LOG"
    printf 'DB=%q\n' "$DB"
    printf 'JAR=%q\n' "$JAR"
    printf 'CONFIG_SRC=%q\n' "$CONFIG_SRC"
    printf 'MAIN_PID=%q\n' "$MAIN_PID"
    printf 'ADMIN_PORT=%q\n' "$ADMIN_PORT"
    printf 'DEBUG_PORT=%q\n' "$DEBUG_PORT"
    printf 'A2A_PORT=%q\n' "$A2A_PORT"
    printf 'AGUI_PORT=%q\n' "$AGUI_PORT"
    printf 'TURN=%q\n' "$TURN"
    printf 'STARTED_AT=%q\n' "$STARTED_AT"
    printf 'MAX_SECONDS=%q\n' "$MAX_SECONDS"
    printf 'WATCHDOG_PID=%q\n' "${WATCHDOG_PID:-}"
  } > "$PACK_DIR/state.env"
}

proc_alive() { [ -n "${MAIN_PID:-}" ] && kill -0 "$MAIN_PID" 2>/dev/null; }
running_mosire_pids() { pgrep -f 'mosire\.jar' 2>/dev/null || true; }

# ---------- 密钥（只进变量；绝不 echo / 不进 argv） ----------
secret_of() { # $1=配置文件 → stdout 密钥值（没有则空）
  python3 - "$1" <<'PY'
import json, sys
try:
    cfg = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception:
    print(""); sys.exit(0)
ref = (cfg.get("llm") or {}).get("credentialsRef") or ""
name = ref[len("keys."):] if ref.startswith("keys.") else ""
print((cfg.get("keys") or {}).get(name, "") or "")
PY
}

# ---------- HTTP ----------
http_json() { # $1=方法 $2=url [$3=body] → stdout 原样；**POST 一律真 POST**（body 为空也不例外）
  local method="$1" url="$2" body="${3:-}"
  if [ "$method" = "POST" ]; then
    if [ -n "$body" ]; then
      curl -sS --max-time 30 -X POST "$url" -H 'Content-Type: application/json' -d "$body"
    else
      curl -sS --max-time 30 -X POST "$url"
    fi
  else
    curl -sS --max-time 30 "$url"
  fi
}
chat_url() { printf 'http://127.0.0.1:%s/api/chat' "$DEBUG_PORT"; }

# =============================== start ===============================
cmd_start() {
  local NAME="run" DATA_DIR="" CONFIG="$CONFIG_DEFAULT" JAR="$JAR_DEFAULT"
  local TEMPLATES="$TEMPLATES_DEFAULT" USE_TEMPLATES=1 PORT=0 MAX_SECONDS=1800 XMX=256 FRESH=0
  while [ $# -gt 0 ]; do
    case "$1" in
      --name) NAME="$2"; shift 2 ;;
      --data-dir) DATA_DIR="$2"; shift 2 ;;
      --config) CONFIG="$2"; shift 2 ;;
      --jar) JAR="$2"; shift 2 ;;
      --templates-dir) TEMPLATES="$2"; shift 2 ;;
      --no-templates) USE_TEMPLATES=0; shift ;;
      --port) PORT="$2"; shift 2 ;;
      --max-seconds) MAX_SECONDS="$2"; shift 2 ;;
      --xmx) XMX="$2"; shift 2 ;;
      --fresh) FRESH=1; shift ;;
      -h|--help) usage ;;
      *) die "未知参数: $1" ;;
    esac
  done

  command -v java >/dev/null || die "缺 java（JDK 21）"
  command -v curl >/dev/null || die "缺 curl"
  command -v sqlite3 >/dev/null || die "缺 sqlite3 CLI"
  command -v python3 >/dev/null || die "缺 python3"
  [ -f "$JAR" ] || die "找不到 jar: $JAR —— 先构建: ./mvnw -pl MainMosire -am package -DskipTests"
  [ -f "$CONFIG" ] || die "找不到真模型配置: $CONFIG"
  # 陈旧 jar 陷阱（2026-09-12 U7/U9 实跑踩到：jar 早于修复 9 分钟，跑出来的是旧产物，差点把旧症状当现状）
  local JAR_MTIME SRC_COMMIT
  JAR_MTIME="$(stat -c %Y "$JAR")"
  SRC_COMMIT="$(git -C "$ROOT" log -1 --format=%ct -- MainMosire/src AgentLibMosire/src BrainMosire/src 2>/dev/null || echo 0)"
  if [ "${SRC_COMMIT:-0}" -gt "$JAR_MTIME" ]; then
    die "jar 比最近一次源码提交旧（jar: $(date -d @"$JAR_MTIME" '+%F %T')，源码提交: $(date -d @"$SRC_COMMIT" '+%F %T')）——跑的是旧产物，先重打: ./mvnw -pl MainMosire -am package -DskipTests"
  fi
  if find MainMosire/src/main AgentLibMosire/src/main BrainMosire/src/main -name '*.java' -newer "$JAR" -print -quit 2>/dev/null | grep -q .; then
    say "⚠ 有未提交的源码比 jar 新 —— 本次跑的不是 HEAD，结论要按此打折"
  fi
  grep -qF "$SKELETON" "$APP_SRC" 2>/dev/null ||
    die "骨架占位串与 $APP_SRC 里的 DEFAULT_LLM_REPLY 不一致（脚本常量已漂移，先核对再跑）"
  [ -z "$(running_mosire_pids)" ] ||
    die "已有 mosire 进程在跑（$(running_mosire_pids | tr '\n' ' ')）——本机只许一个真进程，先 stop"

  [ -n "$DATA_DIR" ] || DATA_DIR="$ROOT/.work/usage-test/$NAME"
  git -C "$ROOT" check-ignore -q "$DATA_DIR" ||
    die "数据目录不在 gitignore 内: $DATA_DIR（证据包含 600 配置副本，不许落在受控目录）"

  mkdir -p "$DATA_DIR"
  if [ "$FRESH" -eq 1 ]; then
    find "$DATA_DIR" -mindepth 1 -maxdepth 1 ! -name usage-test -exec rm -rf {} + 2>/dev/null || true
    say "--fresh：已清运行产物（证据包保留）"
  fi
  cp "$CONFIG" "$DATA_DIR/config.json"; chmod 600 "$DATA_DIR/config.json"

  local TS
  TS="$(date +%Y%m%d-%H%M%S)"
  PACK_DIR="$DATA_DIR/usage-test/$TS"          # 全局：save_state/后续子命令都读它
  mkdir -p "$PACK_DIR/turns" "$PACK_DIR/db" "$DATA_DIR/.usage"
  printf '%s' "$PACK_DIR" > "$DATA_DIR/.usage/current"
  # 「最近一次 start 的数据目录」指针：后续 say/stop 不传 --data-dir 就用它（本机同时只许一个真进程）
  mkdir -p "$ROOT/.work/usage-test"
  printf '%s' "$DATA_DIR" > "$ROOT/.work/usage-test/.current"

  local ARGS=(run --port "$PORT" --data-dir "$DATA_DIR"
              --no-mcp-expose --a2a-address 127.0.0.1 --a2a-port 0
              --agui-address 127.0.0.1 --agui-port 0)
  [ "$USE_TEMPLATES" -eq 1 ] && ARGS+=(--templates-dir "$TEMPLATES")

  MAIN_LOG="$PACK_DIR/main.log"

  setsid java "-Xmx${XMX}m" -jar "$JAR" "${ARGS[@]}" >> "$MAIN_LOG" 2>&1 &
  MAIN_PID=$!
  say "== start name=$NAME pid=$MAIN_PID（进程组 $MAIN_PID）"
  note "数据目录 $DATA_DIR"
  note "证据包 $PACK_DIR"
  note "配置来源 $(basename "$CONFIG")（复制为 600；原件不动）"

  # 外部硬看门狗：不靠 LLM 守规矩
  (
    deadline=$(( $(date +%s) + MAX_SECONDS ))
    while kill -0 "$MAIN_PID" 2>/dev/null; do
      now=$(date +%s)
      if [ "$now" -gt "$deadline" ]; then
        echo "看门狗：墙钟超 ${MAX_SECONDS}s，杀进程组" >> "$MAIN_LOG"
        kill -TERM -- "-$MAIN_PID" 2>/dev/null || true; exit 1
      fi
      sleep 2
    done
  ) &
  WATCHDOG_PID=$!
  disown "$WATCHDOG_PID" 2>/dev/null || true

  # 解析监听端口（启动行格式与 smoke-live.sh 同源）
  local PORTS="" i
  for i in $(seq 1 60); do
    PORTS="$(sed -n 's/.*admin=http:\/\/127\.0\.0\.1:\([0-9]*\) a2a=http:\/\/[^:]*:\([0-9]*\) agui=http:\/\/[^:]*:\([0-9]*\) debug=http:\/\/127\.0\.0\.1:\([0-9]*\).*/\1 \2 \3 \4/p' "$MAIN_LOG" | head -1 || true)"
    [ -n "$PORTS" ] && break
    kill -0 "$MAIN_PID" 2>/dev/null || { tail -20 "$MAIN_LOG" >&2; die "主进程启动失败（日志: $MAIN_LOG）"; }
    sleep 1
  done
  [ -n "$PORTS" ] || die "60s 内未解析到监听端口（日志: $MAIN_LOG）"
  read -r ADMIN_PORT A2A_PORT AGUI_PORT DEBUG_PORT <<< "$PORTS"
  say "端口: admin=$ADMIN_PORT debug=$DEBUG_PORT a2a=$A2A_PORT agui=$AGUI_PORT"

  http_json GET "http://127.0.0.1:$ADMIN_PORT/health" > "$PACK_DIR/health.json" || true
  grep -q '"status":"ok"' "$PACK_DIR/health.json" || die "health 不 ok: $(cat "$PACK_DIR/health.json")"
  say "PASS: health"

  RUN_NAME="$NAME"; JAR="$JAR"; CONFIG_SRC="$CONFIG"; DB="$DATA_DIR/events.db"
  TURN=0; STARTED_AT="$(date -Is)"; MAX_SECONDS="$MAX_SECONDS"
  save_state
  say "就绪：本数据目录已绑定到证据包，后续 say/session/reset/db/scan 都写进它"
}

# =============================== say ===============================
cmd_say() {
  local TIMEOUT=300 MSG=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --timeout) TIMEOUT="$2"; shift 2 ;;
      -h|--help) usage ;;
      *) MSG="${MSG:+$MSG }$1"; shift ;;
    esac
  done
  [ "$MSG" = "-" ] && MSG="$(cat)"
  [ -n "$MSG" ] || die "say 需要一条消息（或 \"-\" 从 stdin 读）"
  load_state "$CUR_DATA_DIR"
  proc_alive || { tail -30 "$MAIN_LOG" >&2; die "主进程已不在（pid=$MAIN_PID）——看上面日志"; }

  TURN=$((TURN + 1))
  local N RID SNAP BODY STATUS TEXT
  N="$(printf '%04d' "$TURN")"
  BODY="$(python3 -c 'import json,sys; print(json.dumps({"message": sys.argv[1]}))' "$MSG")"
  printf '%s' "$BODY" > "$PACK_DIR/turns/$N.request.json"
  RID="$(http_json POST "$(chat_url)" "$BODY" | python3 -c 'import json,sys; print(json.load(sys.stdin)["runId"])' || true)"
  [ -n "$RID" ] || die "提交未返回 runId（$(chat_url)）——进程还活着吗？看 $(basename "$MAIN_LOG")"

  local deadline=$(( $(date +%s) + TIMEOUT ))
  while :; do
    SNAP="$(http_json GET "$(chat_url)/$RID" || true)"
    [ -n "$SNAP" ] && ! printf '%s' "$SNAP" | grep -q '"status":"running"' && break
    if [ "$(date +%s)" -gt "$deadline" ]; then
      printf '%s' "${SNAP:-}" > "$PACK_DIR/turns/$N.snapshot.json"
      die "回合 $N（runId=$RID）${TIMEOUT}s 内未终态——原始快照已存 $PACK_DIR/turns/$N.snapshot.json"
    fi
    sleep 1
  done
  printf '%s' "$SNAP" > "$PACK_DIR/turns/$N.snapshot.json"
  STATUS="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["status"])' "$SNAP")"
  TEXT="$(python3 -c 'import json,sys; print((json.loads(sys.argv[1]).get("result") or {}).get("text",""))' "$SNAP")"
  {
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$N" "$RID" "$STATUS" \
      "$(python3 -c 'import json,sys; r=(json.loads(sys.argv[1]).get("result") or {}); print(r.get("stopReason",""))' "$SNAP")" \
      "$(python3 -c 'import json,sys; r=(json.loads(sys.argv[1]).get("result") or {}); print(r.get("turns",""))' "$SNAP")" \
      "$(python3 -c 'import json,sys; r=(json.loads(sys.argv[1]).get("result") or {}); print(r.get("toolCalls",""))' "$SNAP")"
  } >> "$PACK_DIR/turns/index.tsv"
  save_state

  say "--- 回合 $N runId=$RID status=$STATUS"
  say "$TEXT"
  case "$TEXT" in
    *"$SKELETON"*) note "⚠ 回复命中骨架占位串 App.DEFAULT_LLM_REPLY —— 假模型探针命中（D24 意义上的静默退化）" ;;
  esac
  [ "$STATUS" = "finished" ] || note "⚠ 非 finished（status=$STATUS）——原始快照: $PACK_DIR/turns/$N.snapshot.json"
}

# ========================= session / reset =========================
cmd_session() {
  load_state "$CUR_DATA_DIR"
  local OUT; OUT="$(http_json GET "$(chat_url)/session")"
  printf '%s' "$OUT" > "$PACK_DIR/session-get.json"
  say "$OUT"
}
cmd_reset() {
  load_state "$CUR_DATA_DIR"
  local OUT N; OUT="$(http_json POST "$(chat_url)/session")"
  [ -n "$OUT" ] || die "重置请求无响应（$(chat_url)/session）"
  # 计数用 find 不用 `ls 通配 | wc`：后者在 set -o pipefail 下无匹配即整脚本静默退出（2026-09-12 当场踩到）
  N="$(find "$PACK_DIR" -maxdepth 1 -name 'reset-*.json' | wc -l)"; N="$(printf '%02d' $((N + 1)))"
  printf '%s' "$OUT" > "$PACK_DIR/reset-$N.json"
  say "$OUT"
}

# =============================== db ===============================
cmd_db() {
  local SQL=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --query) SQL="$2"; shift 2 ;;
      -h|--help) usage ;;
      *) die "未知参数: $1" ;;
    esac
  done
  load_state "$CUR_DATA_DIR"
  if [ ! -f "$DB" ]; then
    printf '事件库还不存在: %s\n' "$DB" >&2
    return 1
  fi
  if [ -n "$SQL" ]; then
    sqlite3 -header "$DB" "$SQL"
    return
  fi
  sqlite3 -header "$DB" \
    "select seq, type, agent, json_extract(payload,'\$.model') as model, json_extract(payload,'\$.action') as action, json_extract(payload,'\$.tool') as tool from events order by seq;" \
    > "$PACK_DIR/db/events-dump.tsv" || true
  sqlite3 -header "$DB" "select name from sqlite_master where type='table' order by name;" > "$PACK_DIR/db/tables.txt" || true
  sqlite3 -header "$DB" "select id, conversation_id, role, length(content) as len, substr(content,1,80) as head from messages order by id;" \
    > "$PACK_DIR/db/messages-dump.tsv" 2>/dev/null || true
  local CDB
  for CDB in $(find "$DATA_DIR/subagents" -name 'events.db' 2>/dev/null | sort || true); do
    local ID; ID="$(basename "$(dirname "$CDB")")"
    sqlite3 -header "$CDB" \
      "select seq, type, agent, json_extract(payload,'\$.model') as model, json_extract(payload,'\$.action') as action, json_extract(payload,'\$.tool') as tool from events order by seq;" \
      > "$PACK_DIR/db/child-$ID-events.tsv" 2>/dev/null || true
  done
  say "已落库快照："
  ls -1 "$PACK_DIR/db/"
}

# =============================== scan ==============================
cmd_scan() {
  load_state "$CUR_DATA_DIR"
  local SECRET HITS=0 F OUT="$PACK_DIR/scan.txt"
  SECRET="$(secret_of "$DATA_DIR/config.json")"
  : > "$OUT"
  if [ -z "$SECRET" ]; then
    printf '配置副本里没有可扫的密钥值（credentialsRef 指向的键为空/缺失——U7 类场景属正常）\n' | tee -a "$OUT"
    return 0
  fi
  # 扫数据目录下所有文件，**排除** config.json 本身（它合法地持有密钥）
  while IFS= read -r F; do
    [ -f "$F" ] || continue
    case "$(basename "$F")" in config.json) continue ;; esac
    if grep -qa -- "$SECRET" "$F"; then
      HITS=$((HITS + 1)); printf '泄露点: %s\n' "${F#"$DATA_DIR"/}" | tee -a "$OUT" >&2
    fi
  done < <(find "$DATA_DIR" -type f ! -path "*/usage-test/*/config.json" 2>/dev/null)
  printf '扫描文件数=%s 命中=%s（排除各 config.json 本身；不打印密钥值）\n' \
    "$(find "$DATA_DIR" -type f ! -path "*/usage-test/*/config.json" | wc -l)" "$HITS" | tee -a "$OUT"
  if [ "$HITS" -ne 0 ]; then
    printf '密钥值出现在 %s 个文件里（详见 %s）\n' "$HITS" "$OUT" >&2
    return 1
  fi
  say "PASS: 密钥不漏"
}

# ========================= logs / status ===========================
cmd_logs() {
  local N=40
  while [ $# -gt 0 ]; do
    case "$1" in
      --lines) N="$2"; shift 2 ;;
      *) die "未知参数: $1" ;;
    esac
  done
  load_state "$CUR_DATA_DIR"
  tail -"$N" "$MAIN_LOG"
}

cmd_status() {
  local PTR="$CUR_DATA_DIR/.usage/current"
  [ -f "$PTR" ] || die "本数据目录还没有 start 过"
  load_state "$CUR_DATA_DIR"
  say "实验名     $RUN_NAME"
  say "证据包     $PACK_DIR"
  say "主进程     pid=$MAIN_PID $(proc_alive && echo 活着 || echo '已退出')"
  say "端口       admin=$ADMIN_PORT debug=$DEBUG_PORT a2a=$A2A_PORT agui=$AGUI_PORT"
  say "回合数     $TURN"
  say "启动于     $STARTED_AT（看门狗上限 ${MAX_SECONDS}s）"
  say "其他 mosire 进程: $(running_mosire_pids | tr '\n' ' ')"
}

# =============================== stop ==============================
cmd_stop() {
  load_state "$CUR_DATA_DIR"
  if proc_alive; then
    say "杀进程组 $MAIN_PID…"
    kill -TERM -- "-$MAIN_PID" 2>/dev/null || kill -TERM "$MAIN_PID" 2>/dev/null || true
    for _ in $(seq 1 20); do kill -0 "$MAIN_PID" 2>/dev/null || break; sleep 1; done
    if kill -0 "$MAIN_PID" 2>/dev/null; then
      say "20s 未退出，SIGKILL"
      kill -KILL -- "-$MAIN_PID" 2>/dev/null || true
    fi
  fi
  # 收掉看门狗子壳：**按记下的 pid 杀**，不用 pkill -f 模式匹配——
  # 模式会匹配到调用者自己的命令行/别的台架（2026-09-12 当场把自己的 shell 杀了）
  if [ -n "${WATCHDOG_PID:-}" ] && kill -0 "$WATCHDOG_PID" 2>/dev/null; then
    kill "$WATCHDOG_PID" 2>/dev/null || true
  fi
  sleep 1
  MAIN_PID="" ; save_state            # 标记已停（pid 清空）

  cmd_db >/dev/null || say "（事件库快照未生成——见上面报错；库可能还不存在）"
  cmd_scan || say "⚠ 密钥扫描有命中（见上）——收工前请处置"
  local LEFT; LEFT="$(running_mosire_pids | tr '\n' ' ')"
  [ -z "$LEFT" ] || say "警告：仍有 mosire 进程残留: $LEFT"

  {
    printf '# 使用模式测试证据包\n\n'
    printf -- '- 实验名：`%s`\n- 启动于：%s\n- 数据目录：`%s`\n- jar：`%s`\n- 配置来源：`%s`（复制为 600，原件未动）\n' \
      "$RUN_NAME" "$STARTED_AT" "$DATA_DIR" "$JAR" "$CONFIG_SRC"
    printf -- '- 端口：admin=%s debug=%s a2a=%s agui=%s\n- 消息回合数：%s\n- 进程残留：%s\n\n' \
      "$ADMIN_PORT" "$DEBUG_PORT" "$A2A_PORT" "$AGUI_PORT" "$TURN" "${LEFT:-无}"
    printf '## 怎么看\n\n'
    printf '1. `turns/NNNN.request.json` = 我说的原话；`turns/NNNN.snapshot.json` = 程序回的原始快照（含 status/result）。\n'
    printf '2. `main.log` = 进程 stdout/stderr（启动行、告警、异常栈、看门狗）。\n'
    printf '3. `db/` = 事件库与会话库快照（`llm.call` 记账、`tool.call/result`、`messages`）。\n'
    printf '4. `session-get.json` / `reset-NN.json` = 会话读写原文；`scan.txt` = 密钥扫描结果。\n\n'
    printf '## 回合索引\n\n```\n'
    [ -f "$PACK_DIR/turns/index.tsv" ] && cat "$PACK_DIR/turns/index.tsv" || printf '（无回合）\n'
    printf '```\n\n> 列：序号 runId status stopReason turns toolCalls\n'
  } > "$PACK_DIR/PACK.md"

  say "== stop 完成"
  say "证据包: $PACK_DIR（索引 PACK.md）"
  note "日志 $(wc -l < "$MAIN_LOG" 2>/dev/null || echo 0) 行；$(find "$PACK_DIR/turns" -name '*.snapshot.json' | wc -l) 个回合快照"
}

# =============================== main ==============================
CUR_DATA_DIR="$ROOT/.work/usage-test/run"
CMD="${1:-}"; [ -n "$CMD" ] || usage; shift
case "$CMD" in
  start|say|session|reset|db|scan|logs|status|stop) ;;
  -h|--help|help) usage ;;
  *) die "未知子命令: $CMD（-h 看用法）" ;;
esac
# 非 start 命令：默认跟随「最近一次 start 的数据目录」（.current 指针）；--data-dir 可覆盖
ARGS=(); DATA_DIR_GIVEN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --data-dir) CUR_DATA_DIR="$2"; DATA_DIR_GIVEN=1; shift 2 ;;
    *) ARGS+=("$1"); shift ;;
  esac
done
if [ "$DATA_DIR_GIVEN" -eq 0 ] && [ -f "$ROOT/.work/usage-test/.current" ]; then
  CUR_DATA_DIR="$(cat "$ROOT/.work/usage-test/.current")"
fi
"cmd_$CMD" ${ARGS[@]+"${ARGS[@]}"}
