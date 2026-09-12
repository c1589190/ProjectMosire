#!/usr/bin/env bash
# S1-B 驱动脚本：起主 Agent → 送《测试要求书》→ 轮询 → 收结果 → 外部看门狗 → 跑完清理。
#
# 六步（本脚本的验收面，逐步可观测）：
#   · 轮次节奏 要求书 → 「继续」轮 ×N（默认 4，中性文案，不替模型排测试项） → 【出具报告】哨兵
#              （= 5 个工作轮 + 1 个报告轮；--continue-rounds <n> 可调，--no-continue-rounds 关闭）
#   1 起进程   java -Xmx<heap> -jar <jar> run --data-dir <dir> --templates-dir <dir> （独立进程组；stdin=/dev/null）
#   2 送要求书 POST /api/chat {"message":"<要求书全文>"}（debug 面，纯 JSON；无 SDK/无 SSE）
#   3 轮询     GET /api/chat/{runId} 每秒一次，直至终态（status != running）
#   4 收结果   result.text = 该轮回复正文 → 落盘 <data-dir>/s1b/round-N.txt（最后一轮 = 《发现报告》正文）
#   5 看门狗   存活子 JVM 数 > 阈值 / 墙钟 > 阈值 / 【主进程 + 全部后代】RSS 之和 > 阈值
#              → 杀【整个进程组】并如实报告（启动期端口等待段也在看门狗视野内）
#   6 清理     杀进程组 → 确认无残留 java → 跑后密钥扫描（$OUT_DIR 全部文件 + events.db）
#              → 打印事件链查询命令（数据目录留档供复现）
#
# 红线（脚本自身）：
#   * 不接触任何密钥：只传 `--data-dir <路径>`，密钥由主 Agent 自己从 <data-dir>/config.json 读（D23）。
#     argv / env / stdout 里都没有密钥值。
#   * 跑后密钥扫描只报【命中位置与条数】，绝不回显命中内容（避免把密钥二次写进日志）。
#   * 任何模式下都不删数据目录、不删 events.db（离线演练请显式给一个临时 --data-dir）。
#   * 真模型跑由控制者盯着执行；`--fake`/`--fake-script` 是离线演练（假 LLM 不会产出真报告）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# ---- 默认值 ----
JAR="${JAR:-$ROOT/MainMosire/target/mosire.jar}"
DATA_DIR=""
TEMPLATES_DIR="$ROOT/configs/agents"
REQUIREMENT="$ROOT/docs/s1b-requirement.md"
FINAL_PROMPT_DEFAULT='【出具报告】现在请把《发现报告》全文作为本条回复的正文输出。结构：①范围与方法（你测了什么、怎么测的）②每条发现（现象 / 复现步骤 / 期望 vs 实际 / 严重度 / 事件链证据位置）③若无发现就写"空报告"并说明你覆盖了什么 ④你没验成的部分与原因。不要在报告之后继续调用工具。'
FINAL_PROMPT="$FINAL_PROMPT_DEFAULT"
NO_FINAL_PROMPT=0
# 要求书之后、哨兵之前插入的【继续】工作轮条数（默认 4 ⇒ 1 条要求书 + 4 条继续 = 5 个工作轮，+1 报告轮）。
# 文案中性：只报"第几轮/共几轮"并催它推进【自己规划的】测试项，不替它排任何测试项。
CONTINUE_ROUNDS="${CONTINUE_ROUNDS:-4}"
HEAP="${HEAP:-256m}"
ADMIN_PORT=0
DEBUG_PORT=0
MAX_SECONDS="${MAX_SECONDS:-1800}"     # 墙钟上限（=30 分钟，与要求书 §六.6 的对外数字一致：那是本次会话的【外部上限】，模型已被告知并须在此预算内规划；轮数改变不自动改这个数，两处永远取同一值）
MAX_CHILDREN="${MAX_CHILDREN:-3}"      # 存活子 JVM 上限（要求书 §六 的"一次最多 1 个"是第一道，这是第二道）
RSS_LIMIT_MB="${RSS_LIMIT_MB:-1000}"   # 进程组 RSS 上限（**组总量 = 主进程 + 全部后代**；实测主进程 ~100MB、每个子体 ~94MB（-Xmx128m）——本脚本自测里 2 个子体时组总量 223MB ⇒ 3 子体最坏约 400MB；1000MB 是"明显不对"的线，且给 1.6GB 机器留余量）
LINGER="${LINGER:-0}"                  # 终态后保留进程 N 秒（便于人工 curl 观测）
KEEP=0                                 # 数据目录恒保留（CLEAN_DATA 仅在显式 --wipe 时生效）
WIPE=0
MODE_LABEL="真模型"
FAKE_ARGS=()
ROUND_FILES=()

usage() {
  cat <<'EOF'
用法: scripts/s1b-explore.sh [选项]

  --data-dir <p>       数据目录（= 配置根；默认 .work/s1b-<时间戳>；离线演练建议用临时目录）
  --jar <p>            主 jar（默认 MainMosire/target/mosire.jar）
  --templates-dir <p>  子 Agent 模板目录（默认 configs/agents；传 "-" 表示不启用编排）
  --requirement <p>    要求书文件（默认 docs/s1b-requirement.md）
  --round <p>          追加一轮追问（可重复；按给出的顺序发送；排在「继续」轮之后、哨兵之前）
  --continue-rounds <n> 要求书之后、哨兵之前插入的「继续」工作轮条数（默认 4）
                       ⇒ 默认节奏 = 1 条要求书 + 4 条「继续」 = 5 个工作轮，再加 1 个报告轮
                       文案中性（只报"第几轮工作轮/共几轮"，不替模型排测试项）
  --no-continue-rounds 不发「继续」轮（= 旧行为：要求书 → 追问 → 哨兵）
  --final-prompt <t>   结束哨兵消息（默认内建【出具报告】提示；最后一轮=报告正文）
  --no-final-prompt    不发结束哨兵（只发要求书 + 继续轮 + 追问）
  --fake               离线演练：加 --fake（假 LLM；不触网络）
  --fake-script <s>    离线演练：加 --fake-script <s>（脚本 LLM；可驱动 spawn 观察子体路径）
  --heap <x>           主进程堆（默认 256m）
  --port <n>           AdminREST 端口（默认 0=自动分配）
  --debug-port <n>     调试对话端口（默认 0=自动分配；恒绑 127.0.0.1）
  --max-seconds <n>    看门狗：墙钟上限秒（默认 1800 = 30 分钟，与要求书 §六.6 对外数字一致；
                       轮数改多/想让 6 轮都跑满时按需调大——但要求书 §六.6 的数字要同步改，两处必须一致）
  --max-children <n>   看门狗：存活子 JVM 上限（默认 3）
  --rss-limit-mb <n>   看门狗：【进程组 RSS 总量】上限 MB（默认 1000；**是组总量 = 主进程 + 全部后代**，
                       不是单看主进程——实测主进程 ~100MB、子体各 ~94MB，2 子体时组总量 223MB，
                       只盯主进程会漏掉子孙占的内存）
  --linger <n>         终态后保留进程 n 秒（默认 0）
  --wipe               跑之前清空 <data-dir> 下的 s1b/ 输出目录（仍不删 events.db / config.json）
  -h|--help            本帮助

退出码: 0=全部轮次 finished 且收到报告；1=预检失败；2=参数错误；3=看门狗触发；
        4=轮次未 finished/HTTP 异常；5=跑后密钥扫描命中（输出/事件库里出现密钥样式，位置见日志）

注: 真模型模式（不给 --fake/--fake-script）预检 <data-dir>/config.json 是否存在（只判存在性、不读内容）；
    缺则直接失败并提示用 --data-dir <已有的配置根>。缺省数据目录永远是【新】目录——不会默默写别人的数据目录。
注: 墙钟上限对【整次运行】计量（含 JVM 启动、端口等待、POST 开销）。默认节奏 5 个工作轮 + 1 个报告轮，
    而主 Agent 单回合自身预算是 10 分钟 —— 6 轮都跑满会远超 30 分钟，所以要求书 §六.6 明确告诉模型
    "总墙钟约 30 分钟，请在此预算内规划"，看门狗（默认 1800s）就是这条【外部上限】的执行者：两处数字
    取同一个值。想把 6 轮都跑满就 --max-seconds 调大，并同步把要求书 §六.6 的数字改成同一值。
EOF
  exit 2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --data-dir) DATA_DIR="$2"; shift 2 ;;
    --jar) JAR="$2"; shift 2 ;;
    --templates-dir) TEMPLATES_DIR="$2"; shift 2 ;;
    --requirement) REQUIREMENT="$2"; shift 2 ;;
    --round) ROUND_FILES+=("$2"); shift 2 ;;
    --continue-rounds) CONTINUE_ROUNDS="$2"; shift 2 ;;
    --no-continue-rounds) CONTINUE_ROUNDS=0; shift ;;
    --final-prompt) FINAL_PROMPT="$2"; shift 2 ;;
    --no-final-prompt) NO_FINAL_PROMPT=1; shift ;;
    --fake) FAKE_ARGS=(--fake); MODE_LABEL="离线演练 --fake"; shift ;;
    --fake-script) FAKE_ARGS=(--fake-script "$2"); MODE_LABEL="离线演练 --fake-script"; shift 2 ;;
    --heap) HEAP="$2"; shift 2 ;;
    --port) ADMIN_PORT="$2"; shift 2 ;;
    --debug-port) DEBUG_PORT="$2"; shift 2 ;;
    --max-seconds) MAX_SECONDS="$2"; shift 2 ;;
    --max-children) MAX_CHILDREN="$2"; shift 2 ;;
    --rss-limit-mb) RSS_LIMIT_MB="$2"; shift 2 ;;
    --linger) LINGER="$2"; shift 2 ;;
    --wipe) WIPE=1; shift ;;
    -h|--help) usage ;;
    *) printf '未知参数: %s\n' "$1" >&2; usage ;;
  esac
done

if [ -z "$DATA_DIR" ]; then
  if [ "${#FAKE_ARGS[@]}" -gt 0 ]; then
    DATA_DIR="$ROOT/.work/s1b-fake-$(date +%Y%m%d-%H%M%S)"
  else
    DATA_DIR="$ROOT/.work/s1b-$(date +%Y%m%d-%H%M%S)"
  fi
fi
DATA_DIR="$(cd "$(dirname "$DATA_DIR")" 2>/dev/null && pwd)/$(basename "$DATA_DIR")" 2>/dev/null || DATA_DIR="$DATA_DIR"
OUT_DIR="$DATA_DIR/s1b"
MAIN_LOG="$OUT_DIR/main.log"
WATCHDOG_LOG="$OUT_DIR/watchdog.log"

say() { printf '%s\n' "$*"; }
die() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

# ---- 预检 ----
command -v java >/dev/null 2>&1 || die "缺 java（JDK 21）"
command -v curl >/dev/null 2>&1 || die "缺 curl"
command -v python3 >/dev/null 2>&1 || die "缺 python3（JSON 组包/解包用）"
[ -f "$JAR" ] || die "找不到 jar: $JAR —— 先构建: ./mvnw -pl MainMosire -am package -DskipTests"
[ -f "$REQUIREMENT" ] || die "找不到要求书: $REQUIREMENT"
TEMPLATES_ARG=()
if [ "$TEMPLATES_DIR" != "-" ]; then
  [ -d "$TEMPLATES_DIR" ] || die "找不到模板目录: $TEMPLATES_DIR（传 --templates-dir - 可禁用编排）"
  TEMPLATES_ARG=(--templates-dir "$TEMPLATES_DIR")
fi
# 真模型模式的硬前置：<data-dir>/config.json（否则主进程"缺 llm.* 响亮失败"，而默认数据目录是刚建的空目录、
# 必然没有它——预检把这种必然失败提前拦下并给出可照做的下一步）。
# 纪律（D23）：只判存在性，绝不打开 / 读取 / 把内容打进任何输出。
if [ "${#FAKE_ARGS[@]}" -eq 0 ] && [ ! -f "$DATA_DIR/config.json" ]; then
  die "真模型模式需要 <data-dir>/config.json（含 llm.baseUrl/llm.model 与 keys.<name>），当前 $DATA_DIR 下没有。
     用 --data-dir <已有的配置根> 指定（例如 --data-dir $ROOT/.work/mosire），或加 --fake / --fake-script 走离线演练。"
fi

# --wipe 只清我们自己的输出目录（$DATA_DIR/s1b）；config.json / events.db 永不动
[ "$WIPE" -eq 1 ] && rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# ---- 事件链基线（报告定位用：本次运行的事件 = seq > SEQ_BASE）----
SEQ_BASE=0
if command -v sqlite3 >/dev/null 2>&1 && [ -f "$DATA_DIR/events.db" ]; then
  SEQ_BASE="$(sqlite3 "$DATA_DIR/events.db" 'select coalesce(max(seq),0) from events' 2>/dev/null || printf '0')"
fi
[ -n "$SEQ_BASE" ] || SEQ_BASE=0

say "== S1-B 驱动（$MODE_LABEL）: data=$DATA_DIR templates=${TEMPLATES_DIR} jar=$JAR"
if [ "${#FAKE_ARGS[@]}" -gt 0 ]; then
  say "   ⚠ 离线演练模式：假 LLM 不产出真报告——本模式只验证【管道】（起进程/送要求书/轮询/收结果/看门狗/清理）"
fi
say "   事件链基线 seq=$SEQ_BASE；输出目录 $OUT_DIR"

# ---- 1 起进程（独立进程组；stdin=/dev/null 免得 MCP stdio 面抢输入）----
# main.log 是追加写的：先记住已有行数，端口解析只看【本次运行新增的行】——
# 否则复用同一数据目录重跑时会解析到上一轮早已关闭的旧端口（现象：POST /api/chat 返回 000）。
LOG_BASE_LINES="$(wc -l 2>/dev/null < "$MAIN_LOG" || printf '0')"
LOG_BASE_LINES="${LOG_BASE_LINES// /}"
[ -n "$LOG_BASE_LINES" ] || LOG_BASE_LINES=0
setsid java -Xmx"$HEAP" -jar "$JAR" run \
  --port "$ADMIN_PORT" --debug-port "$DEBUG_PORT" \
  --data-dir "$DATA_DIR" "${TEMPLATES_ARG[@]}" \
  --a2a-address 127.0.0.1 --a2a-port 0 \
  --agui-address 127.0.0.1 --agui-port 0 \
  "${FAKE_ARGS[@]}" \
  >> "$MAIN_LOG" 2>&1 < /dev/null &
MAIN_PID=$!
PGID="$(ps -o pgid= -p "$MAIN_PID" 2>/dev/null | tr -d ' ' || true)"
[ -n "$PGID" ] || PGID="$MAIN_PID"
say "1) 起进程: pid=$MAIN_PID pgid=$PGID 日志=$MAIN_LOG"

# ---- 看门狗（硬性；不靠 LLM 守规矩）----
WATCHDOG_TRIP=""
WATCHDOG_DETAIL=""

descendant_pids() { # 主进程的【全部后代】pid（ps 拿拓扑；多轮传播以免漏掉孙辈）
  ps -eo pid=,ppid= 2>/dev/null | awk -v root="$MAIN_PID" '
      { pid[NR]=$1; par[NR]=$2 }
      END {
        desc[root]=1
        for (pass=0; pass<12; pass++)
          for (i in pid) if (par[i] in desc) desc[pid[i]]=1
        for (i in pid) if (pid[i] != root && (pid[i] in desc)) print pid[i]
      }'
}

live_child_jvms() { # 后代里的 [子 Agent JVM] 个数（/proc/<pid>/cmdline 拿完整命令行——ps 的 args 会被终端宽度截断）
  local pid cmd n=0
  for pid in $(descendant_pids); do
    cmd="$(tr '\0' ' ' 2>/dev/null < "/proc/$pid/cmdline" || true)"
    case "$cmd" in *"io.mosire.main.Main agent"*) n=$((n + 1)) ;; esac
  done
  printf '%s' "$n"
}

rss_mb_of() { ps -o rss= -p "$1" 2>/dev/null | awk '{printf "%d", $1/1024}'; }

# 【进程组 RSS 总量】= 主进程 + 全部后代（不只子 Agent JVM——本机 1.6GB 才是真稀缺资源）
group_rss_mb() {
  local pid total=0 rss
  for pid in $(descendant_pids) "$MAIN_PID"; do
    rss="$(rss_mb_of "$pid")"
    [ -n "$rss" ] && total=$((total + rss))
  done
  printf '%s' "$total"
}

kill_group() { # $1 = 信号
  kill -"$1" -- "-$PGID" 2>/dev/null || kill -"$1" "$MAIN_PID" 2>/dev/null || true
}

watchdog_check() { # 命中则设置 WATCHDOG_TRIP 并返回 0
  local kids rss
  if [ "$SECONDS" -gt "$MAX_SECONDS" ]; then
    WATCHDOG_TRIP="wall"
    WATCHDOG_DETAIL="墙钟 ${SECONDS}s > 上限 ${MAX_SECONDS}s"
    return 0
  fi
  kids="$(live_child_jvms)"
  if [ "${kids:-0}" -gt "$MAX_CHILDREN" ]; then
    WATCHDOG_TRIP="children"
    WATCHDOG_DETAIL="存活子 JVM ${kids} > 上限 ${MAX_CHILDREN}"
    return 0
  fi
  rss="$(group_rss_mb)"
  if [ -n "$rss" ] && [ "$rss" -gt "$RSS_LIMIT_MB" ]; then
    WATCHDOG_TRIP="rss"
    WATCHDOG_DETAIL="进程组 RSS ${rss}MB（主进程 $(rss_mb_of "$MAIN_PID")MB + 全部后代）> 组总量上限 ${RSS_LIMIT_MB}MB"
    return 0
  fi
  return 1
}

# 兜底（看门狗之外的安全网）：脚本因任何原因异常退出时也杀掉整个进程组，绝不留孤儿 JVM。
# 正常路径的 6) 清理 已先杀过一遍，重复 kill 无害（kill_group 自带 || true）。
trap 'if [ -n "${MAIN_PID:-}" ]; then kill_group TERM; sleep 1; kill_group KILL; fi' EXIT

watchdog_trip() { # $1=round 标签 $2=runId 标签：杀整个进程组 + 落 watchdog.log（退出码由调用方决定）
  printf '\n!! 看门狗触发：%s\n' "$WATCHDOG_DETAIL" >&2
  kill_group TERM
  for _ in $(seq 1 10); do kill -0 "$MAIN_PID" 2>/dev/null || break; sleep 1; done
  kill_group KILL
  {
    printf 'watchdog=%s\n' "$WATCHDOG_TRIP"
    printf 'detail=%s\n' "$WATCHDOG_DETAIL"
    printf 'round=%s runId=%s elapsed=%ss\n' "$1" "$2" "$SECONDS"
  } >> "$WATCHDOG_LOG"
}

# ---- 解析实际端口（admin/debug；a2a/agui 本轮不用）----
# 启动段也在看门狗视野内（此前这里的墙钟是盲区）
RC=0
ADMIN_PORT_ACTUAL=""
DEBUG_PORT_ACTUAL=""
for _ in $(seq 1 60); do
  if watchdog_check; then
    watchdog_trip "启动期" "-"
    RC=3
    break
  fi
  PORTS="$(tail -n +"$((LOG_BASE_LINES + 1))" "$MAIN_LOG" 2>/dev/null | sed -n 's/.*admin=http:\/\/127\.0\.0\.1:\([0-9]*\) .*debug=http:\/\/127\.0\.0\.1:\([0-9]*\).*/\1 \2/p' | head -1 || true)"
  if [ -n "$PORTS" ]; then
    read -r ADMIN_PORT_ACTUAL DEBUG_PORT_ACTUAL <<< "$PORTS"
    break
  fi
  kill -0 "$MAIN_PID" 2>/dev/null || die "主进程启动失败（日志: $MAIN_LOG）"
  sleep 1
done
if [ "$RC" -eq 3 ]; then
  say "!! 看门狗在【启动期】触发（$WATCHDOG_DETAIL）——进程组已杀；本次不跑任何轮次，直接收尾落档"
fi
if [ -z "$ADMIN_PORT_ACTUAL" ] && [ "$RC" -ne 3 ]; then
  die "60s 内未解析到监听端口（日志: $MAIN_LOG）"
fi
say "   端口: admin=$ADMIN_PORT_ACTUAL debug=$DEBUG_PORT_ACTUAL"

CURL_BASE="http://127.0.0.1:$DEBUG_PORT_ACTUAL/api/chat"

# ---- JSON 小工具（python3；不经 argv 传正文，避免 ps 泄漏与长度限制）----
jget() { # $1=json 文件  $2=点分路径
  python3 - "$1" "$2" <<'PY'
import json, sys
doc = json.load(open(sys.argv[1], encoding="utf-8"))
cur = doc
for key in sys.argv[2].split("."):
    if cur is None:
        break
    cur = cur[int(key)] if isinstance(cur, list) else cur.get(key)
if cur is None:
    sys.stdout.write("")
elif isinstance(cur, str):
    sys.stdout.write(cur)
else:
    sys.stdout.write(json.dumps(cur, ensure_ascii=False))
PY
}

write_body() { # $1=消息文件 → 写 {"message":"..."} 到 stdout
  python3 - "$1" <<'PY'
import json, sys
sys.stdout.write(json.dumps({"message": open(sys.argv[1], encoding="utf-8").read()}, ensure_ascii=False))
PY
}

# ---- 2+3+4 送一轮 + 轮询 + 收结果 ----
ROUND_STATUSES=()
run_round() { # $1=轮次号  $2=消息文件
  local n="$1" msgfile="$2" body resp http status runid tries
  body="$(write_body "$msgfile")"
  printf '%s' "$body" > "$TMP_DIR/body.json"
  resp="$(curl -sS --max-time 60 -o "$TMP_DIR/submit.json" -w '%{http_code}' \
        -X POST "$CURL_BASE" -H 'Content-Type: application/json; charset=utf-8' \
        --data-binary @"$TMP_DIR/body.json" || true)"
  if [ "$resp" != "200" ]; then
    printf 'FAIL: 第 %s 轮 POST /api/chat 返回 %s：%s\n' "$n" "$resp" "$(cat "$TMP_DIR/submit.json" 2>/dev/null || true)" >&2
    return 4
  fi
  runid="$(jget "$TMP_DIR/submit.json" runId)"
  [ -n "$runid" ] || { printf 'FAIL: 第 %s 轮未解析到 runId\n' "$n" >&2; return 4; }
  say "2) 送第 $n 轮（消息 $(wc -c < "$msgfile") 字节）→ runId=$runid"

  tries=0
  while :; do
    if watchdog_check; then
      printf '\n!! 看门狗触发：%s\n' "$WATCHDOG_DETAIL" >&2
      kill_group TERM
      for _ in $(seq 1 10); do kill -0 "$MAIN_PID" 2>/dev/null || break; sleep 1; done
      kill_group KILL
      {
        printf 'watchdog=%s\n' "$WATCHDOG_TRIP"
        printf 'detail=%s\n' "$WATCHDOG_DETAIL"
        printf 'round=%s runId=%s elapsed=%ss\n' "$n" "$runid" "$SECONDS"
      } >> "$WATCHDOG_LOG"
      return 3
    fi
    http="$(curl -sS --max-time 30 -o "$TMP_DIR/status.json" -w '%{http_code}' \
            "$CURL_BASE/$runid" || true)"
    if [ "$http" != "200" ]; then
      printf 'WARN: 第 %s 轮 GET /api/chat/%s 返回 %s（重试）\n' "$n" "$runid" "$http" >&2
      sleep 1
      continue
    fi
    status="$(jget "$TMP_DIR/status.json" status)"
    if [ "$status" != "running" ]; then
      break
    fi
    tries=$((tries + 1))
    [ $((tries % 15)) -eq 0 ] && say "   … 第 $n 轮仍在跑（${SECONDS}s，子 JVM $(live_child_jvms) 个，RSS 组 $(group_rss_mb)MB（主 $(rss_mb_of "$MAIN_PID")MB））"
    sleep 1
  done

  cp "$TMP_DIR/status.json" "$OUT_DIR/round-$n.json"
  jget "$TMP_DIR/status.json" result.text > "$OUT_DIR/round-$n.txt"
  ROUND_STATUSES+=("$n:$status:$(jget "$TMP_DIR/status.json" result.stopReason):$(jget "$TMP_DIR/status.json" result.turns):$(jget "$TMP_DIR/status.json" result.toolCalls)")
  say "4) 第 $n 轮终态: status=$status stopReason=$(jget "$TMP_DIR/status.json" result.stopReason) turns=$(jget "$TMP_DIR/status.json" result.turns) toolCalls=$(jget "$TMP_DIR/status.json" result.toolCalls) → $OUT_DIR/round-$n.txt（$(wc -c < "$OUT_DIR/round-$n.txt") 字节）"
  [ "$status" = "finished" ] || return 4
  return 0
}

# ---- 轮次编排：要求书 → 「继续」×N → （追问…） → 结束哨兵 ----
# 第一小段的真模型跑暴露的布置错误：模型把唯一的工作轮用来做规划，第 2 条就是【出具报告】，于是只能交空报告。
# 修法 = 补中间工作轮（默认 4 条「继续」）；轮次数字只写在这里，要求书本身不写死轮数（两边不会各自漂移）。
printf '%s' "$FINAL_PROMPT" > "$TMP_DIR/final.txt"
ROUNDS=("$REQUIREMENT")
CUSTOM_ROUNDS=0
for f in "${ROUND_FILES[@]}"; do [ -n "$f" ] && CUSTOM_ROUNDS=$((CUSTOM_ROUNDS + 1)); done
# 工作轮总数 = 1（要求书）+ N（继续）+ M（自定义追问）；哨兵是【报告轮】，不在此数内
WORK_TOTAL=$((1 + CONTINUE_ROUNDS + CUSTOM_ROUNDS))
if [ "$CONTINUE_ROUNDS" -gt 0 ]; then
  # k 从 2 起：第 1 个工作轮就是要求书本身（模型据此知道"还剩几轮"）
  for k in $(seq 2 $((1 + CONTINUE_ROUNDS))); do
    cf="$TMP_DIR/continue-$k.txt"
    printf '继续（第 %s 轮工作轮，共 %s 轮；之后是报告轮）：推进你规划中尚未执行的测试项，把每项的真实结果（含原始返回文本）记下来。\n' \
      "$k" "$WORK_TOTAL" > "$cf"
    ROUNDS+=("$cf")
  done
fi
for f in "${ROUND_FILES[@]}"; do [ -n "$f" ] && ROUNDS+=("$f"); done
[ "$NO_FINAL_PROMPT" -eq 1 ] || ROUNDS+=("$TMP_DIR/final.txt")

N=0
TOTAL=${#ROUNDS[@]}
if [ "$RC" -eq 0 ]; then # 启动期看门狗触发时 RC=3：跳过全部轮次，直接收尾落档
for f in "${ROUNDS[@]}"; do
  [ -f "$f" ] || die "找不到轮次消息文件: $f"
  N=$((N + 1))
  ROUND_RC=0
  run_round "$N" "$f" || ROUND_RC=$?
  if [ "$ROUND_RC" -eq 3 ]; then # 看门狗已杀进程组——没有后续轮次可言
    RC=3
    break
  fi
  if [ "$ROUND_RC" -ne 0 ]; then
    if [ "$N" -lt "$TOTAL" ]; then
      # 中间轮降级可继续：回合是独立的 chat 调用（历史已存），下一轮/报告轮仍可能成功
      say "!! 第 $N 轮未正常收尾（rc=$ROUND_RC）——中间轮按降级继续（只有末轮失败才算整体失败）"
      continue
    fi
    RC="$ROUND_RC"
    say "!! 末轮（第 $N 轮）未正常收尾（rc=$RC）"
    break
  fi
done
fi

# 最后一轮的正文 = 《发现报告》正文
if [ "$RC" -eq 0 ] && [ "$N" -gt 0 ]; then
  cp "$OUT_DIR/round-$N.txt" "$OUT_DIR/report-final.txt"
  say "5) 报告正文: $OUT_DIR/report-final.txt"
fi

# ---- 终态后可选驻留（便于人工 curl 观测）----
if [ "$LINGER" -gt 0 ] && kill -0 "$MAIN_PID" 2>/dev/null; then
  say "   --linger $LINGER：进程保留 ${LINGER}s（admin=http://127.0.0.1:$ADMIN_PORT_ACTUAL debug=$CURL_BASE）"
  sleep "$LINGER"
fi

# ---- 6 跑完清理：杀整个进程组 → 确认无残留 ----
kill_group TERM
for _ in $(seq 1 15); do
  kill -0 "$MAIN_PID" 2>/dev/null || break
  sleep 1
done
if kill -0 "$MAIN_PID" 2>/dev/null; then
  say "   主进程 15s 内未退出 → SIGKILL 整组"
  kill_group KILL
  sleep 1
fi
LEFT_KIDS="$(live_child_jvms)"
LEFT_MAIN=0
kill -0 "$MAIN_PID" 2>/dev/null && LEFT_MAIN=1
say "6) 清理: 主进程存活=$LEFT_MAIN 残留子 JVM=$LEFT_KIDS（本次运行范围内的）"

# ---- 6b 跑后密钥扫描（验收第 5 条的被测项：它自己有没有把密钥漏进输出/事件库）----
# 只报【位置 + 条数】，绝不回显命中内容——否则等于把密钥二次写进日志。
SECRET_HITS=()
scan_secrets() {
  local f n
  for f in "$@"; do
    [ -f "$f" ] || continue
    n="$(grep -acE 'sk-[A-Za-z0-9_-]{8,}' "$f" 2>/dev/null || true)"
    [ "${n:-0}" -gt 0 ] && SECRET_HITS+=("$f（$n 行）")
  done
  return 0 # 显式归零：末次迭代未命中时函数不得以非零收尾（set -e）
}
mapfile -t OUT_FILES < <(find "$OUT_DIR" -type f 2>/dev/null || true)
[ "${#OUT_FILES[@]}" -gt 0 ] && scan_secrets "${OUT_FILES[@]}"
scan_secrets "$DATA_DIR/events.db"
SECRET_LINE="无命中（已扫 $OUT_DIR 全部文件 + $DATA_DIR/events.db）"
if [ "${#SECRET_HITS[@]}" -gt 0 ]; then
  SECRET_LINE="命中 ${#SECRET_HITS[@]} 处（只报位置/条数）：${SECRET_HITS[*]}"
  say "!! 密钥扫描: $SECRET_LINE"
  [ "$RC" -eq 0 ] && RC=5
else
  say "密钥扫描: $SECRET_LINE"
fi

# ---- 落档 manifest（不含任何密钥；只有路径/端口/阈值/ID/扫描结论）----
{
  printf 'mode=%s\n' "$MODE_LABEL"
  printf 'jar=%s\n' "$JAR"
  printf 'data_dir=%s\n' "$DATA_DIR"
  printf 'templates_dir=%s\n' "$TEMPLATES_DIR"
  printf 'requirement=%s\n' "$REQUIREMENT"
  printf 'final_prompt=%s\n' "$([ "$NO_FINAL_PROMPT" -eq 1 ] && printf '(disabled)' || printf '%s' "$FINAL_PROMPT")"
  printf 'work_rounds=%s（要求书 1 + 继续 %s + 追问 %s；报告轮 %s）\n' \
    "$WORK_TOTAL" "$CONTINUE_ROUNDS" "$CUSTOM_ROUNDS" "$([ "$NO_FINAL_PROMPT" -eq 1 ] && printf '0（--no-final-prompt）' || printf '1')"
  printf 'pid=%s\npgid=%s\n' "$MAIN_PID" "$PGID"
  printf 'admin_port=%s\ndebug_port=%s\n' "$ADMIN_PORT_ACTUAL" "$DEBUG_PORT_ACTUAL"
  printf 'seq_base=%s\n' "$SEQ_BASE"
  printf 'watchdog: max_seconds=%s max_children=%s rss_limit_mb=%s（组总量=主进程+全部后代）\n' "$MAX_SECONDS" "$MAX_CHILDREN" "$RSS_LIMIT_MB"
  printf 'rounds=%s\n' "${ROUND_STATUSES[*]:-}"
  printf 'secret_scan=%s\n' "$SECRET_LINE"
  printf 'main_log=%s\n' "$MAIN_LOG"
} > "$OUT_DIR/manifest.txt"

say ""
say "== 事件链定位（数据目录留档，可直接查库）："
say "   sqlite3 $DATA_DIR/events.db \"select seq,type,agent,substr(payload,1,300) from events where seq > $SEQ_BASE order by seq\""
say "   报告正文 = conversation.turn（agent=main）最后一条的 payload.output；"
say "   等价物 = $OUT_DIR/report-final.txt（本脚本已经 /api/chat 收下的 result.text）"
say "   运行期只读面（进程还活着时）: curl 'http://127.0.0.1:$ADMIN_PORT_ACTUAL/api/events?type=conversation.turn&limit=20'"

if [ "$RC" -eq 3 ]; then
  say ""
  say "结果: 看门狗触发（$WATCHDOG_DETAIL）——已杀整个进程组并留档，见 $WATCHDOG_LOG"
elif [ "$RC" -eq 5 ]; then
  say ""
  say "结果: 跑后密钥扫描命中（验收第 5 条失败信号）——位置见上，绝不回显内容"
elif [ "$RC" -ne 0 ]; then
  say ""
  say "结果: 未正常收尾（rc=$RC）——日志见 $MAIN_LOG"
fi
if [ "${#SECRET_HITS[@]}" -gt 0 ] && [ "$RC" -ne 5 ]; then
  say "!! 另注: 跑后密钥扫描也命中 ${#SECRET_HITS[@]} 处（rc=$RC 为先前的失败码，密钥命中的优先级更高，请一并处理）"
fi
exit "$RC"
