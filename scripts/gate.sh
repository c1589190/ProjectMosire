#!/usr/bin/env bash
# ProjectMosire 门禁入口：只构造固定命令，不接受任意 Maven 透传参数。
#
# 为什么存在（docs/agents/SKILLS.md §8）：
#   - rc=0 不等于通过：-DskipTests / 类名写错 / 目标模块没有测试都会让 Maven 退出 0；
#   - target/surefire-reports 不会被自动清理：旧报告混进来会把两轮结果加在一起；
#   - full verify 与 focused test 必须输出不同结论，不能把 focused 标成完整门禁。
#
# 用法：
#   scripts/gate.sh pre
#   scripts/gate.sh full
#   scripts/gate.sh focused <module> <test-pattern>
#   scripts/gate.sh selftest
#
# 退出码：
#   0 = 通过
#   1 = 红（Maven 失败 / 0 tests / failures / errors）
#   2 = 门禁拒绝（参数/配置/服务冲突）
#   3 = 证据不完整（manifest/报告/SpotBugs 证据缺失或无法解析）
#   4 = 环境/进程冲突（本 checkout 有并发 Maven、JDK 不满足等）

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SUMMARY="$ROOT/scripts/test-summary.py"
WORK_ROOT="$ROOT/.work/gate"

EXIT_PASS=0
EXIT_RED=1
EXIT_REFUSED=2
EXIT_INCOMPLETE=3
EXIT_CONFLICT=4

# 本文件名用于错误信息；不要用 caller 的 $0（可能被 shell 包装）。
SCRIPT_NAME="scripts/gate.sh"

MODULES=()
RUN_ID=""
RUN_DIR=""

say() { printf '%s\n' "$*"; }
err() { printf '%s\n' "$*" >&2; }

usage() {
  cat <<'EOF'
用法:
  scripts/gate.sh pre
      只做门禁前置检查（配置/环境/本 checkout 并发 Maven/服务冲突），不跑 Maven。
  scripts/gate.sh full
      固定执行 ./mvnw -B -ntp verify（零额外参数）。
  scripts/gate.sh focused <module> <test-pattern>
      固定执行 ./mvnw -B -ntp -Dtest=<pattern> -Dsurefire.failIfNoSpecifiedTests=false test -pl <module> -am
  scripts/gate.sh selftest
      用夹具验证拒绝样本；不跑真实 Maven。

退出码: 0=通过 1=红 2=门禁拒绝 3=证据不完整 4=环境/进程冲突
EOF
}

now_iso() {
  date -Iseconds 2>/dev/null || date +%Y-%m-%dT%H:%M:%S%z
}

# ---------------------------------------------------------------------------
# 模块与参数校验
# ---------------------------------------------------------------------------

load_modules() {
  local output
  if ! output="$(python3 - "$ROOT/pom.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
try:
    root = ET.parse(path).getroot()
except Exception as exc:  # noqa: BLE001 - 这里只需要一个明确的拒绝理由
    print(f"cannot parse {path}: {exc}", file=sys.stderr)
    raise SystemExit(2)
modules = []
for node in root.findall("m:modules/m:module", ns):
    name = (node.text or "").strip()
    if name:
        modules.append(name)
if not modules:
    print(f"{path}: <modules> 为空", file=sys.stderr)
    raise SystemExit(2)
print("\n".join(modules))
PY
  )"; then
    err "GATE REFUSED: 无法从根 pom.xml 读取模块列表"
    return "$EXIT_REFUSED"
  fi
  mapfile -t MODULES <<<"$output"
  if [ "${#MODULES[@]}" -eq 0 ]; then
    err "GATE REFUSED: 根 pom.xml 模块列表为空"
    return "$EXIT_REFUSED"
  fi
  return 0
}

module_exists() {
  local wanted="$1" module
  for module in "${MODULES[@]}"; do
    [ "$module" = "$wanted" ] && return 0
  done
  return 1
}

valid_test_pattern() {
  local pattern="$1"
  [ -n "$pattern" ] || return 1
  [ "${#pattern}" -le 256 ] || return 1
  # 只允许 Surefire 类名/方法名/通配符/tag 表达式的常见字符；
  # 空格、分号、引号、反引号、= 等都不允许，避免把参数偷渡成第二个 Maven 参数。
  # 用单引号包住 ERE，避免 bash 把 $# 当成特殊参数展开。
  case "$pattern" in
    -*) return 1 ;;
  esac
  printf '%s' "$pattern" | grep -Eq '^[A-Za-z0-9_.$#,*?!+()-]+$'
}

validate_focused_args() {
  local module="${1:-}" pattern="${2:-}"
  if [ -z "$module" ] || [ -z "$pattern" ]; then
    err "GATE REFUSED: focused 需要且仅需要 <module> <test-pattern> 两个参数"
    return "$EXIT_REFUSED"
  fi
  if ! module_exists "$module"; then
    err "GATE REFUSED: 模块不在根 pom.xml 的 <modules> 中: $module（允许: ${MODULES[*]}）"
    return "$EXIT_REFUSED"
  fi
  if [ ! -d "$ROOT/$module" ] || [ ! -f "$ROOT/$module/pom.xml" ]; then
    err "GATE REFUSED: 指定目标缺失: $module/pom.xml 不存在（拒绝把不存在目标当通过）"
    return "$EXIT_REFUSED"
  fi
  if ! valid_test_pattern "$pattern"; then
    err "GATE REFUSED: test-pattern 含不允许的字符: $(printf '%q' "$pattern")"
    return "$EXIT_REFUSED"
  fi
  return 0
}

# 固定命令构造：这是唯一允许的 Maven 调用形状。
build_command() {
  local mode="${1:-}"
  shift || true
  case "$mode" in
    full)
      if [ "$#" -ne 0 ]; then
        err "GATE REFUSED: full 不接受任何额外参数（收到 $# 个）"
        return "$EXIT_REFUSED"
      fi
      printf '%s\n' './mvnw -B -ntp verify'
      ;;
    focused)
      if [ "$#" -ne 2 ]; then
        err "GATE REFUSED: focused 需要且仅需要 <module> <test-pattern>"
        return "$EXIT_REFUSED"
      fi
      printf '%s\n' "./mvnw -B -ntp -Dtest=$2 -Dsurefire.failIfNoSpecifiedTests=false test -pl $1 -am"
      ;;
    *)
      err "GATE REFUSED: 未知 mode: ${mode:-<empty>}"
      return "$EXIT_REFUSED"
      ;;
  esac
}

# ---------------------------------------------------------------------------
# 配置/环境检查
# ---------------------------------------------------------------------------

# .mvn/maven.config 采用允许列表：只放行不会削弱门禁的固定参数。
maven_config_token_allowed() {
  local token="$1"
  case "$token" in
    -B|--batch-mode|-ntp|--no-transfer-progress|-o|--offline)
      return 0
      ;;
    -Dstyle.color=*|--color=*|-Dmaven.repo.local=*)
      return 0
      ;;
  esac
  return 1
}

check_maven_config() {
  local root="${1:-$ROOT}"
  local config="$root/.mvn/maven.config"
  local bad=0 line token line_no=0
  local -a tokens
  [ -f "$config" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    line_no=$((line_no + 1))
    line="${line%$'\r'}"
    [ -z "${line//[[:space:]]/}" ] && continue
    case "$line" in
      \#*) continue ;;
    esac
    tokens=()
    read -r -a tokens <<<"$line"
    for token in "${tokens[@]}"; do
      if ! maven_config_token_allowed "$token"; then
        err "GATE REFUSED: .mvn/maven.config:$line_no 含非允许列表参数: $(printf '%q' "$token")"
        bad=1
      fi
    done
  done <"$config"
  if [ "$bad" -ne 0 ]; then
    err "GATE REFUSED: .mvn/maven.config 只允许 -B/-ntp/-o 等固定参数；不得放行 skip/削弱门禁开关"
    return "$EXIT_REFUSED"
  fi
  return 0
}

env_weakening_pattern() {
  local lower="$1"
  if [[ "$lower" =~ (skip|fail-never|failnever|failure\.ignore|testfailureignore|maven\.test\.skip|-dtest=|-dfailifnotests=false|-dspotless.*skip|-dcheckstyle\.skip|-dspotbugs\.skip|-denforcer\.skip|-djacoco\.skip) ]]; then
    # 只回显命中的类别，不回显变量值：MAVEN_OPTS/JAVA_TOOL_OPTIONS 可能包含凭据类 -D。
    case "$lower" in
      *skip*) printf '%s' 'skip*' ;;
      *fail-never*|*failnever*) printf '%s' 'fail-never' ;;
      *failure.ignore*|*testfailureignore*) printf '%s' 'failure.ignore' ;;
      *maven.test.skip*) printf '%s' 'maven.test.skip' ;;
      *-dtest=*) printf '%s' '-Dtest=' ;;
      *-dfailifnotests=false*) printf '%s' '-DfailIfNoTests=false' ;;
      *) printf '%s' 'weakening-option' ;;
    esac
    return 0
  fi
  return 1
}

check_env_weakening() {
  local var value lower pattern rc
  for var in MAVEN_ARGS MAVEN_OPTS JAVA_TOOL_OPTIONS _JAVA_OPTIONS; do
    value="${!var:-}"
    [ -z "$value" ] && continue
    lower="$(printf '%s' "$value" | tr '[:upper:]' '[:lower:]')"
    if pattern="$(env_weakening_pattern "$lower")"; then
      err "GATE REFUSED: $var 含削弱门禁的开关类别: $pattern（不打印原值）"
      rc=1
    fi
  done
  [ "${rc:-0}" -eq 0 ] || return "$EXIT_REFUSED"
  return 0
}

check_toolchain() {
  local java_line major
  command -v python3 >/dev/null 2>&1 || {
    err "GATE CONFLICT: 缺少 python3（test-summary.py 需要）"
    return "$EXIT_CONFLICT"
  }
  command -v timeout >/dev/null 2>&1 || {
    err "GATE CONFLICT: 缺少 timeout（门禁必须有超时上限）"
    return "$EXIT_CONFLICT"
  }
  command -v pgrep >/dev/null 2>&1 || {
    err "GATE CONFLICT: 缺少 pgrep（无法检查本 checkout 的并发 Maven）"
    return "$EXIT_CONFLICT"
  }
  if [ ! -x "$ROOT/mvnw" ]; then
    err "GATE CONFLICT: 仓库内 ./mvnw 不存在或不可执行"
    return "$EXIT_CONFLICT"
  fi
  command -v java >/dev/null 2>&1 || {
    err "GATE CONFLICT: 找不到 java"
    return "$EXIT_CONFLICT"
  }
  java_line="$(java -version 2>&1 | sed -n '1p')"
  major="$(printf '%s' "$java_line" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p')"
  if [ -z "$major" ]; then
    err "GATE CONFLICT: 无法解析 java 版本: $java_line"
    return "$EXIT_CONFLICT"
  fi
  if [ "$major" -lt 21 ]; then
    err "GATE CONFLICT: 需要 JDK 21+，当前 java 主版本=$major"
    return "$EXIT_CONFLICT"
  fi
  return 0
}

# 只把落在本 checkout 的 Maven/surefire 进程算并发；别的仓库不阻塞。
# 输出格式（内部使用，包含 cmdline，调用方不得直接原样打印）：
#   MAVEN_CONFLICT|<pid>|<cwd>|<cmd>
#   SERVICE_CONFLICT|<pid>|<cwd>|<cmd>
detect_checkout_processes() {
  local root="${1:-$ROOT}"
  local pid cwd cmd in_root
  while IFS= read -r pid; do
    [ -n "$pid" ] || continue
    [ "$pid" = "$$" ] && continue
    [ -d "/proc/$pid" ] || continue
    cwd="$(readlink -f "/proc/$pid/cwd" 2>/dev/null || true)"
    cmd="$(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null || true)"
    [ -n "$cmd" ] || cmd="$(ps -o args= -p "$pid" 2>/dev/null || true)"
    [ -n "$cmd" ] || continue
    in_root=0
    if [ -n "$cwd" ] && { [ "$cwd" = "$root" ] || [[ "$cwd" == "$root"/* ]]; }; then
      in_root=1
    fi
    if [ "$in_root" -eq 0 ]; then
      # Maven 可能从别处启动但用 -f/绝对路径指向本 checkout；命令行含本 checkout 也视为本仓。
      case "$cmd" in
        *"$root"*) in_root=1 ;;
      esac
    fi
    [ "$in_root" -eq 1 ] || continue
    case "$cmd" in
      *classworlds*|*surefirebooter*|*maven-surefire*)
        printf 'MAVEN_CONFLICT|%s|%s|%s\n' "$pid" "${cwd:-unknown}" "$cmd"
        ;;
      *mosire.jar*|*io.mosire.main.Main*|*MainMosire*)
        printf 'SERVICE_CONFLICT|%s|%s|%s\n' "$pid" "${cwd:-unknown}" "$cmd"
        ;;
    esac
  done < <(pgrep -x java 2>/dev/null || true)
  return 0
}

# 把检测结果转成“可安全打印”的摘要：绝不回显完整 cmdline（可能含密钥类 -D）。
summarize_conflicts() {
  local raw="$1"
  local kind pid cwd rest
  while IFS='|' read -r kind pid cwd rest; do
    [ -n "${kind:-}" ] || continue
    printf '%s pid=%s cwd=%s\n' "$kind" "$pid" "$cwd"
  done <<<"$raw"
}

refuse_if_busy() {
  local root="${1:-$ROOT}"
  local raw maven_count service_count
  raw="$(detect_checkout_processes "$root")"
  maven_count="$(printf '%s\n' "$raw" | grep -c '^MAVEN_CONFLICT|' || true)"
  service_count="$(printf '%s\n' "$raw" | grep -c '^SERVICE_CONFLICT|' || true)"
  if [ "${maven_count:-0}" -gt 0 ]; then
    err "GATE CONFLICT: 本 checkout 已有并发 Maven/surefire 进程（同一 checkout 一次只允许一个 Maven）："
    summarize_conflicts "$(printf '%s\n' "$raw" | grep '^MAVEN_CONFLICT|' || true)" >&2
    return "$EXIT_CONFLICT"
  fi
  if [ "${service_count:-0}" -gt 0 ]; then
    err "GATE REFUSED: 本 checkout 有服务进程在跑（服务冲突；先停服务再跑门禁）："
    summarize_conflicts "$(printf '%s\n' "$raw" | grep '^SERVICE_CONFLICT|' || true)" >&2
    return "$EXIT_REFUSED"
  fi
  return 0
}

validate_gate_timeout() {
  GATE_TIMEOUT="${GATE_TIMEOUT:-3600}"
  if ! [[ "$GATE_TIMEOUT" =~ ^[0-9]+$ ]]; then
    err "GATE REFUSED: GATE_TIMEOUT 必须是秒数: $GATE_TIMEOUT"
    return "$EXIT_REFUSED"
  fi
  if [ "$GATE_TIMEOUT" -lt 60 ] || [ "$GATE_TIMEOUT" -gt 7200 ]; then
    err "GATE REFUSED: GATE_TIMEOUT 必须在 60..7200 秒之间（当前 $GATE_TIMEOUT）"
    return "$EXIT_REFUSED"
  fi
  return 0
}

preflight_checks() {
  local rc
  load_modules || return $?
  check_maven_config "$ROOT" || return $?
  check_env_weakening || return $?
  check_toolchain || return $?
  refuse_if_busy "$ROOT" || return $?
  validate_gate_timeout || return $?
  return 0
}

# ---------------------------------------------------------------------------
# run manifest / 旧报告隔离
# ---------------------------------------------------------------------------

worktree_summary() {
  local total tracked untracked
  total="$(git -C "$ROOT" status --porcelain=v1 --untracked-files=all 2>/dev/null | wc -l | tr -d ' ')"
  untracked="$(git -C "$ROOT" status --porcelain=v1 --untracked-files=all 2>/dev/null | grep -c '^??' || true)"
  tracked=$((total - untracked))
  if [ "$total" -eq 0 ]; then
    printf 'clean'
  else
    printf 'dirty(tracked=%s,untracked=%s)' "$tracked" "$untracked"
  fi
}

append_manifest() {
  local run_dir="$1"; shift
  printf '%s\n' "$*" >>"$run_dir/manifest.txt"
}

archive_old_reports() {
  local root="$1" run_dir="$2"
  local dir rel dest count=0 rc=0
  while IFS= read -r dir; do
    [ -n "$dir" ] || continue
    rel="${dir#"$root"/}"
    dest="$run_dir/old-surefire-reports/$rel"
    mkdir -p "$(dirname "$dest")" || {
      rc=$EXIT_INCOMPLETE
      break
    }
    if ! mv "$dir" "$dest"; then
      err "GATE INCOMPLETE: 无法移走旧报告目录: $dir"
      rc=$EXIT_INCOMPLETE
      break
    fi
    count=$((count + 1))
  done < <(find "$root" -mindepth 3 -maxdepth 3 -type d -path '*/target/surefire-reports' -print 2>/dev/null | sort)
  if [ "$rc" -ne 0 ]; then
    return "$rc"
  fi
  printf '%s\n' "$count"
  return 0
}

collect_run_reports() {
  local root="$1" run_dir="$2"
  local module src dest count=0 rc=0
  for module in "${MODULES[@]}"; do
    src="$root/$module/target/surefire-reports"
    [ -d "$src" ] || continue
    dest="$run_dir/surefire-reports/$module"
    mkdir -p "$dest" || {
      rc=$EXIT_INCOMPLETE
      break
    }
    if ! cp -a "$src"/. "$dest"/; then
      err "GATE INCOMPLETE: 无法拷贝本轮 Surefire 报告: $src"
      rc=$EXIT_INCOMPLETE
      break
    fi
    count=$((count + 1))
  done
  if [ "$rc" -ne 0 ]; then
    return "$rc"
  fi
  printf '%s\n' "$count"
  return 0
}

start_run() {
  local mode="$1" scope="$2" target_module="$3" test_pattern="$4" command="$5"
  local source_sha tag archived
  RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$-$RANDOM"
  RUN_DIR="$WORK_ROOT/$RUN_ID"
  mkdir -p "$RUN_DIR" || {
    err "GATE INCOMPLETE: 无法创建 run 目录: $RUN_DIR"
    return "$EXIT_INCOMPLETE"
  }
  source_sha="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || printf 'UNKNOWN')"
  tag="$(git -C "$ROOT" describe --tags --exact-match 2>/dev/null || printf 'none')"
  {
    printf 'run_id=%s\n' "$RUN_ID"
    printf 'source_sha=%s\n' "$source_sha"
    printf 'tag=%s\n' "$tag"
    printf 'started_at=%s\n' "$(now_iso)"
    printf 'mode=%s\n' "$mode"
    printf 'scope=%s\n' "$scope"
    if [ -n "$target_module" ]; then
      printf 'target_module=%s\n' "$target_module"
    fi
    if [ -n "$test_pattern" ]; then
      printf 'test_pattern=%s\n' "$test_pattern"
    fi
    printf 'command=%s\n' "$command"
    printf 'wrapper=timeout --signal=TERM\n'
    printf 'timeout_s=%s\n' "${GATE_TIMEOUT:-unset}"
    printf 'cwd=%s\n' "$ROOT"
    printf 'modules=%s\n' "$(IFS=,; printf '%s' "${MODULES[*]}")"
    printf 'worktree=%s\n' "$(worktree_summary)"
  } >"$RUN_DIR/manifest.txt" || {
    err "GATE INCOMPLETE: 无法写 manifest: $RUN_DIR/manifest.txt"
    return "$EXIT_INCOMPLETE"
  }

  if ! archived="$(archive_old_reports "$ROOT" "$RUN_DIR")"; then
    return "$EXIT_INCOMPLETE"
  fi
  append_manifest "$RUN_DIR" "archived_report_dirs=$archived"
  say "RUN $RUN_ID"
  say "  manifest: $RUN_DIR/manifest.txt"
  say "  archived old surefire-reports dirs: $archived"
  return 0
}

append_finished_manifest() {
  local run_dir="$1" maven_rc="$2" build_result="$3" summary_result="$4"
  append_manifest "$run_dir" "finished_at=$(now_iso)"
  append_manifest "$run_dir" "maven_exit_code=$maven_rc"
  append_manifest "$run_dir" "build_result=$build_result"
  append_manifest "$run_dir" "summary_result=$summary_result"
}

# ---------------------------------------------------------------------------
# 执行与评估
# ---------------------------------------------------------------------------

execute_command() {
  local run_dir="$1" timeout_s="$2"
  shift 2
  local -a command=("$@")
  local rc
  (cd "$ROOT" && timeout --signal=TERM "${timeout_s}s" "${command[@]}") 2>&1 | tee "$run_dir/maven.log"
  rc=${PIPESTATUS[0]}
  printf '\n[gate] command exit code: %s (timeout=%ss)\n' "$rc" "$timeout_s" | tee -a "$run_dir/maven.log" >/dev/null
  return "$rc"
}

evaluate_run() {
  local run_dir="$1" mode="$2" target_module="$3" maven_rc="$4"
  local -a args=(--run-dir "$run_dir" --mode "$mode")
  if [ "$mode" = "focused" ]; then
    args+=(--target-module "$target_module")
  fi
  local summary_rc
  python3 "$SUMMARY" "${args[@]}" 2>&1 | tee "$run_dir/summary.txt"
  summary_rc=${PIPESTATUS[0]}

  if [ "$maven_rc" -eq 124 ]; then
    err "GATE RED: Maven 超时（GATE_TIMEOUT=${GATE_TIMEOUT}s），不构成通过"
    return "$EXIT_RED"
  fi
  if [ "$maven_rc" -ne 0 ]; then
    err "GATE RED: Maven 退出码=$maven_rc（logs: $run_dir/maven.log）"
    return "$EXIT_RED"
  fi
  case "$summary_rc" in
    "$EXIT_PASS")
      if [ "$mode" = "full" ]; then
        local bug_lines
        bug_lines="$(grep -c 'BugInstance size is 0' "$run_dir/maven.log" 2>/dev/null || true)"
        if [ "${bug_lines:-0}" -lt "${#MODULES[@]}" ]; then
          err "GATE INCOMPLETE: full verify 日志里 SpotBugs 'BugInstance size is 0' 只有 ${bug_lines:-0} 次，""少于模块数 ${#MODULES[@]}（可能 SpotBugs 被跳过；不把缺少静态分析证据的 verify 标成通过）"
          return "$EXIT_INCOMPLETE"
        fi
        say "FULL VERIFY PASSED"
        say "  run: $(basename "$run_dir")"
        say "  manifest: $run_dir/manifest.txt"
        say "  summary: $run_dir/summary.txt"
        return "$EXIT_PASS"
      fi
      say "FOCUSED TEST PASSED (NOT a full gate)"
      say "  run: $(basename "$run_dir")"
      say "  manifest: $run_dir/manifest.txt"
      say "  summary: $run_dir/summary.txt"
      return "$EXIT_PASS"
      ;;
    "$EXIT_RED") return "$EXIT_RED" ;;
    "$EXIT_INCOMPLETE") return "$EXIT_INCOMPLETE" ;;
    *)
      err "GATE INCOMPLETE: test-summary.py 退出码=$summary_rc（无法解析/内部错误）"
      return "$EXIT_INCOMPLETE"
      ;;
  esac
}

build_result_from_log() {
  local log="$1"
  if grep -q 'BUILD SUCCESS' "$log" 2>/dev/null; then
    printf 'SUCCESS'
  elif grep -q 'BUILD FAILURE' "$log" 2>/dev/null; then
    printf 'FAILURE'
  else
    printf 'UNKNOWN'
  fi
}

summary_result_from_run() {
  local run_dir="$1"
  local result
  result="$(grep '^RESULT ' "$run_dir/summary.txt" 2>/dev/null | tail -1 | awk '{print $2}')"
  printf '%s' "${result:-UNKNOWN}"
}

post_run_conflict_check() {
  local raw maven_count
  raw="$(detect_checkout_processes "$ROOT")"
  maven_count="$(printf '%s\n' "$raw" | grep -c '^MAVEN_CONFLICT|' || true)"
  if [ "${maven_count:-0}" -gt 0 ]; then
    err "GATE CONFLICT: 收工前复核发现本 checkout 出现并发 Maven/surefire；本轮结果不作为独占证据："
    summarize_conflicts "$(printf '%s\n' "$raw" | grep '^MAVEN_CONFLICT|' || true)" >&2
    return "$EXIT_CONFLICT"
  fi
  return 0
}

# ---------------------------------------------------------------------------
# 子命令
# ---------------------------------------------------------------------------

cmd_pre() {
  if [ "$#" -ne 0 ]; then
    err "GATE REFUSED: pre 不接受参数"
    return "$EXIT_REFUSED"
  fi
  preflight_checks || return $?
  say "GATE PRE PASSED (no Maven run)"
  say "  cwd: $ROOT"
  say "  HEAD: $(git -C "$ROOT" rev-parse HEAD 2>/dev/null || printf 'UNKNOWN')"
  say "  tag: $(git -C "$ROOT" describe --tags --exact-match 2>/dev/null || printf 'none')"
  say "  worktree: $(worktree_summary)"
  say "  modules: ${MODULES[*]}"
  say "  .mvn/maven.config: allowlist OK"
  say "  env weakening: none"
  say "  checkout Maven/surefire conflicts: none"
  say "  GATE_TIMEOUT: ${GATE_TIMEOUT}s"
  return "$EXIT_PASS"
}

cmd_full() {
  if [ "$#" -ne 0 ]; then
    err "GATE REFUSED: full 不接受任何额外参数（收到: $*）；不得透传 -DskipTests 等参数"
    return "$EXIT_REFUSED"
  fi
  if ! build_command full >/dev/null; then
    return "$EXIT_REFUSED"
  fi
  preflight_checks || return $?
  local command maven_rc archived reports summary_result eval_rc
  command="$(build_command full)"
  start_run full repo "" "" "$command" || return $?
  local -a full_cmd=(./mvnw -B -ntp verify)
  execute_command "$RUN_DIR" "$GATE_TIMEOUT" "${full_cmd[@]}"
  maven_rc=$?
  if ! reports="$(collect_run_reports "$ROOT" "$RUN_DIR")"; then
    append_finished_manifest "$RUN_DIR" "$maven_rc" "$(build_result_from_log "$RUN_DIR/maven.log")" "REPORT_COLLECT_FAILED"
    return "$EXIT_INCOMPLETE"
  fi
  append_manifest "$RUN_DIR" "report_modules=$reports"
  # 先把构建结果写进 manifest，再让 test-summary 评估：评估读取到的 run 证据是完整的。
  append_manifest "$RUN_DIR" "maven_exit_code=$maven_rc"
  append_manifest "$RUN_DIR" "build_result=$(build_result_from_log "$RUN_DIR/maven.log")"
  evaluate_run "$RUN_DIR" full "" "$maven_rc"
  eval_rc=$?
  append_manifest "$RUN_DIR" "finished_at=$(now_iso)"
  append_manifest "$RUN_DIR" "summary_result=$(summary_result_from_run "$RUN_DIR")"
  if [ "$eval_rc" -eq 0 ]; then
    post_run_conflict_check || return $?
  fi
  return "$eval_rc"
}

cmd_focused() {
  if [ "$#" -ne 2 ]; then
    err "GATE REFUSED: focused 需要且仅需要 <module> <test-pattern> 两个参数（收到 $# 个）"
    return "$EXIT_REFUSED"
  fi
  local module="$1" pattern="$2" command maven_rc reports eval_rc
  load_modules || return $?
  validate_focused_args "$module" "$pattern" || return $?
  preflight_checks || return $?
  command="$(build_command focused "$module" "$pattern")"
  start_run focused "module:$module:$pattern" "$module" "$pattern" "$command" || return $?
  local -a focused_cmd=(./mvnw -B -ntp "-Dtest=$pattern" -Dsurefire.failIfNoSpecifiedTests=false test -pl "$module" -am)
  execute_command "$RUN_DIR" "$GATE_TIMEOUT" "${focused_cmd[@]}"
  maven_rc=$?
  if ! reports="$(collect_run_reports "$ROOT" "$RUN_DIR")"; then
    append_finished_manifest "$RUN_DIR" "$maven_rc" "$(build_result_from_log "$RUN_DIR/maven.log")" "REPORT_COLLECT_FAILED"
    return "$EXIT_INCOMPLETE"
  fi
  append_manifest "$RUN_DIR" "report_modules=$reports"
  # 先把构建结果写进 manifest，再让 test-summary 评估：评估读取到的 run 证据是完整的。
  append_manifest "$RUN_DIR" "maven_exit_code=$maven_rc"
  append_manifest "$RUN_DIR" "build_result=$(build_result_from_log "$RUN_DIR/maven.log")"
  evaluate_run "$RUN_DIR" focused "$module" "$maven_rc"
  eval_rc=$?
  append_manifest "$RUN_DIR" "finished_at=$(now_iso)"
  append_manifest "$RUN_DIR" "summary_result=$(summary_result_from_run "$RUN_DIR")"
  if [ "$eval_rc" -eq 0 ]; then
    post_run_conflict_check || return $?
  fi
  return "$eval_rc"
}

# ---------------------------------------------------------------------------
# selftest：只调用上面的判定函数与真实进程分类函数，不执行 ./mvnw。
# ---------------------------------------------------------------------------

SELFTEST_TMP=""
SELFTEST_PIDS=()

selftest_cleanup() {
  local pid
  for pid in "${SELFTEST_PIDS[@]:-}"; do
    [ -n "$pid" ] || continue
    kill "$pid" 2>/dev/null || true
  done
  if [ -n "$SELFTEST_TMP" ] && [ -d "$SELFTEST_TMP" ]; then
    rm -rf "$SELFTEST_TMP"
  fi
}

selftest_pass() { printf 'SELFTEST PASS gate.sh %s\n' "$1"; }
selftest_fail() {
  printf 'SELFTEST FAIL gate.sh %s: %s\n' "$1" "$2"
  SELFTEST_FAILURES=$((SELFTEST_FAILURES + 1))
}

selftest_run_dir() {
  local name="$1" mode="$2" target_module="$3" modules="$4"
  local run="$SELFTEST_TMP/$name"
  mkdir -p "$run"
  {
    printf 'run_id=selftest-%s\n' "$name"
    printf 'source_sha=0123456789abcdef0123456789abcdef01234567\n'
    printf 'tag=none\n'
    printf 'started_at=%s\n' "$(now_iso)"
    printf 'mode=%s\n' "$mode"
    printf 'scope=selftest\n'
    if [ -n "$target_module" ]; then
      printf 'target_module=%s\n' "$target_module"
    fi
    printf 'command=selftest-fixed-command\n'
    printf 'cwd=%s\n' "$SELFTEST_TMP"
    printf 'modules=%s\n' "$modules"
    printf 'worktree=clean\n'
    printf 'maven_exit_code=0\n'
  } >"$run/manifest.txt"
  printf '%s\n' "$run"
}

selftest_write_xml() {
  local run="$1" module="$2" tests="$3" failures="$4" errors="$5" skipped="$6"
  local dir="$run/surefire-reports/$module"
  mkdir -p "$dir"
  cat >"$dir/TEST-Selftest.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="selftest.$module" tests="$tests" failures="$failures" errors="$errors" skipped="$skipped" time="0.01">
EOF
  local i
  for ((i = 1; i <= tests; i++)); do
    printf '  <testcase classname="selftest.%s" name="case%s" time="0.001"/>\n' "$module" "$i" >>"$dir/TEST-Selftest.xml"
  done
  printf '</testsuite>\n' >>"$dir/TEST-Selftest.xml"
}

selftest_write_log() {
  local run="$1" tests="$2" failures="$3" errors="$4" skipped="$5"
  local bug_lines="${6:-1}" i
  {
    printf '[INFO] Tests run: %s, Failures: %s, Errors: %s, Skipped: %s\n' "$tests" "$failures" "$errors" "$skipped"
    for ((i = 0; i < bug_lines; i++)); do
      printf '[INFO] BugInstance size is 0\n'
    done
    printf '[INFO] BUILD SUCCESS\n'
  } >"$run/maven.log"
}

# 检查 evaluate_run 的退出码 + 必须出现/不得出现的字符串。
selftest_check_eval() {
  local name="$1" expected_rc="$2" run="$3" mode="$4" target="$5" require="$6" forbid="$7"
  local out rc ok=1 needle
  out="$(evaluate_run "$run" "$mode" "$target" 0 2>&1)"
  rc=$?
  if [ "$rc" -ne "$expected_rc" ]; then
    selftest_fail "$name" "rc=$rc expected=$expected_rc"
    printf '%s\n' "$out"
    return 1
  fi
  local IFS='|'
  for needle in $require; do
    [ -z "$needle" ] && continue
    if ! grep -Fq -- "$needle" <<<"$out"; then
      ok=0
      printf '  missing required: %s\n' "$needle"
    fi
  done
  for needle in $forbid; do
    [ -z "$needle" ] && continue
    if grep -Fq -- "$needle" <<<"$out"; then
      ok=0
      printf '  found forbidden: %s\n' "$needle"
    fi
  done
  unset IFS
  if [ "$ok" -eq 1 ]; then
    selftest_pass "$name (rc=$rc)"
    return 0
  fi
  selftest_fail "$name" "output assertions failed"
  printf '%s\n' "$out"
  return 1
}

# 注意：必须由父 shell 调用（不能放进 $(...) 命令替换）。命令替换会等 stdout 管道关闭，
# 子 shell 退出时后台进程也会随之消失，导致夹具假绿/假红。
SPAWNED_PID=""
spawn_fake_java() {
  local cwd="$1" argv0="$2"
  local bin="$SELFTEST_TMP/fakejava"
  mkdir -p "$bin" "$cwd"
  if [ ! -x "$bin/java" ]; then
    cp "$(command -v sleep)" "$bin/java" || return 1
    chmod +x "$bin/java"
  fi
  (cd "$cwd" && exec -a "$argv0" "$bin/java" 60) &
  SPAWNED_PID=$!
  SELFTEST_PIDS+=("$SPAWNED_PID")
  local i
  for i in {1..40}; do
    [ -d "/proc/$SPAWNED_PID" ] && [ -s "/proc/$SPAWNED_PID/cmdline" ] && break
    sleep 0.05
  done
  return 0
}

selftest_process_cases() {
  local root="$SELFTEST_TMP/repo-proc" other="$SELFTEST_TMP/other-repo"
  mkdir -p "$root" "$other"

  # 别的仓库的 Maven 不算本 checkout 并发。
  local outside_pid outside_raw
  spawn_fake_java "$other" 'java -Dclassworlds.conf=/tmp/x org.codehaus.plexus.classworlds.launcher.Launcher'
  outside_pid="$SPAWNED_PID"
  sleep 0.2
  outside_raw="$(detect_checkout_processes "$root")"
  if printf '%s\n' "$outside_raw" | grep -Fq "|$outside_pid|"; then
    selftest_fail "other-repo-not-blocking" "别的仓库进程被错误计入本 checkout"
  else
    selftest_pass "other-repo-not-blocking"
  fi
  kill "$outside_pid" 2>/dev/null || true

  # 本 checkout 的 Maven 并发 => exit 4。
  local maven_pid maven_raw rc
  spawn_fake_java "$root" 'java -Dclassworlds.conf=/tmp/x org.codehaus.plexus.classworlds.launcher.Launcher'
  maven_pid="$SPAWNED_PID"
  sleep 0.2
  maven_raw="$(detect_checkout_processes "$root")"
  if printf '%s\n' "$maven_raw" | grep -Fq "MAVEN_CONFLICT|$maven_pid|"; then
    selftest_pass "maven-concurrency-detected"
  else
    selftest_fail "maven-concurrency-detected" "未识别本 checkout Maven 进程"
  fi
  if refuse_if_busy "$root" >/dev/null 2>&1; then
    selftest_fail "maven-concurrency-rejected" "refuse_if_busy 未拒绝"
  else
    rc=$?
    if [ "$rc" -eq 4 ]; then
      selftest_pass "maven-concurrency-rejected (rc=4)"
    else
      selftest_fail "maven-concurrency-rejected" "rc=$rc expected=4"
    fi
  fi
  kill "$maven_pid" 2>/dev/null || true

  # 本 checkout 的服务进程 => exit 2。
  local service_pid
  spawn_fake_java "$root" 'java -jar MainMosire/target/mosire.jar run --data-dir /tmp/x'
  service_pid="$SPAWNED_PID"
  sleep 0.2
  if refuse_if_busy "$root" >/dev/null 2>&1; then
    selftest_fail "service-conflict-rejected" "refuse_if_busy 未拒绝服务进程"
  else
    rc=$?
    if [ "$rc" -eq 2 ]; then
      selftest_pass "service-conflict-rejected (rc=2)"
    else
      selftest_fail "service-conflict-rejected" "rc=$rc expected=2"
    fi
  fi
  kill "$service_pid" 2>/dev/null || true
}

selftest_old_report_case() {
  local repo="$SELFTEST_TMP/repo-old" run="$SELFTEST_TMP/old-report-run"
  mkdir -p "$repo/ModuleA/target/surefire-reports" "$run"
  cat >"$repo/ModuleA/target/surefire-reports/TEST-OldFailing.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="old" tests="1" failures="1" errors="0" skipped="0">
  <testcase classname="old" name="oldFailure"><failure message="old"/></testcase>
</testsuite>
EOF
  MODULES=(ModuleA)
  local archived
  if ! archived="$(archive_old_reports "$repo" "$run")"; then
    selftest_fail "old-report-archived" "archive_old_reports 失败"
    return 1
  fi
  if [ "$archived" != "1" ] || [ -d "$repo/ModuleA/target/surefire-reports" ] ||
    [ ! -f "$run/old-surefire-reports/ModuleA/target/surefire-reports/TEST-OldFailing.xml" ]; then
    selftest_fail "old-report-archived" "旧报告没有被移入 run 目录"
    return 1
  fi
  selftest_pass "old-report-archived"

  # 本轮只产生一份绿色报告；旧 failure 如果被当成本轮会红。
  mkdir -p "$repo/ModuleA/target/surefire-reports"
  cat >"$repo/ModuleA/target/surefire-reports/TEST-NewGreen.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="new" tests="1" failures="0" errors="0" skipped="0">
  <testcase classname="new" name="freshGreen"/>
</testsuite>
EOF
  local copied
  if ! copied="$(collect_run_reports "$repo" "$run")"; then
    selftest_fail "old-report-not-counted" "collect_run_reports 失败"
    return 1
  fi
  {
    printf 'run_id=selftest-old-report\n'
    printf 'source_sha=0123456789abcdef0123456789abcdef01234567\n'
    printf 'started_at=%s\n' "$(now_iso)"
    printf 'mode=full\nscope=repo\ncommand=selftest-fixed-command\n'
    printf 'modules=ModuleA\nworktree=clean\nmaven_exit_code=0\n'
  } >"$run/manifest.txt"
  selftest_write_log "$run" 1 0 0 0
  local out rc
  out="$(evaluate_run "$run" full "" 0 2>&1)"
  rc=$?
  if [ "$rc" -eq 0 ] && grep -Fq 'TOTAL tests=1 failures=0 errors=0' <<<"$out"; then
    selftest_pass "old-report-not-counted (total tests=1)"
  else
    selftest_fail "old-report-not-counted" "rc=$rc；旧 failure 可能混入本轮"
    printf '%s\n' "$out"
  fi
}

run_selftest() {
  SELFTEST_FAILURES=0
  SELFTEST_TMP="$(mktemp -d "${TMPDIR:-/tmp}/mosire-gate-selftest.XXXXXX")" || {
    err "SELFTEST FAIL gate.sh: 无法创建临时目录"
    return 1
  }
  trap selftest_cleanup EXIT
  local case_count=0

  # ---- 固定命令形状 ----
  local got
  got="$(build_command full)"
  if [ "$got" = "./mvnw -B -ntp verify" ]; then
    selftest_pass "full-command-fixed"
  else
    selftest_fail "full-command-fixed" "got=$got"
  fi
  case_count=$((case_count + 1))

  got="$(build_command focused AgentLibMosire 'VersionTest,HealthTest#ok')"
  if [ "$got" = "./mvnw -B -ntp -Dtest=VersionTest,HealthTest#ok -Dsurefire.failIfNoSpecifiedTests=false test -pl AgentLibMosire -am" ]; then
    selftest_pass "focused-command-fixed"
  else
    selftest_fail "focused-command-fixed" "got=$got"
  fi
  case_count=$((case_count + 1))

  # ---- 拒绝样本：-DskipTests / env / maven.config ----
  if build_command full -DskipTests >/dev/null 2>&1; then
    selftest_fail "skipTests-arg-rejected" "full 接受额外参数"
  else
    selftest_pass "skipTests-arg-rejected (rc=$?)"
  fi
  case_count=$((case_count + 1))

  if (MAVEN_ARGS='-DskipTests' check_env_weakening) >/dev/null 2>&1; then
    selftest_fail "env-skipTests-rejected" "MAVEN_ARGS=-DskipTests 未被拒"
  else
    selftest_pass "env-skipTests-rejected"
  fi
  case_count=$((case_count + 1))

  if (MAVEN_OPTS='-Dmaven.test.skip=true' check_env_weakening) >/dev/null 2>&1; then
    selftest_fail "env-maven-test-skip-rejected" "MAVEN_OPTS=-Dmaven.test.skip=true 未被拒"
  else
    selftest_pass "env-maven-test-skip-rejected"
  fi
  case_count=$((case_count + 1))

  mkdir -p "$SELFTEST_TMP/config-skip/.mvn"
  printf '%s\n' '-DskipTests' >"$SELFTEST_TMP/config-skip/.mvn/maven.config"
  if (check_maven_config "$SELFTEST_TMP/config-skip") >/dev/null 2>&1; then
    selftest_fail "maven-config-skip-rejected" ".mvn/maven.config -DskipTests 未被拒"
  else
    selftest_pass "maven-config-skip-rejected"
  fi
  case_count=$((case_count + 1))

  mkdir -p "$SELFTEST_TMP/config-ok/.mvn"
  printf '%s\n' '# allowlisted flags' '-B' '-ntp' >"$SELFTEST_TMP/config-ok/.mvn/maven.config"
  if (check_maven_config "$SELFTEST_TMP/config-ok") >/dev/null 2>&1; then
    selftest_pass "maven-config-allowlist-positive"
  else
    selftest_fail "maven-config-allowlist-positive" "允许列表正样本被误拒"
  fi
  case_count=$((case_count + 1))

  # ---- 0 tests / failures / errors ----
  local run
  run="$(selftest_run_dir zero-tests full "" ModuleA)"
  selftest_write_xml "$run" ModuleA 0 0 0 0
  selftest_write_log "$run" 0 0 0 0
  selftest_check_eval "0-tests-rejected" 1 "$run" full "" "RESULT RED_NO_TESTS" "FULL VERIFY PASSED"
  case_count=$((case_count + 1))

  run="$(selftest_run_dir failures full "" ModuleA)"
  selftest_write_xml "$run" ModuleA 2 1 0 0
  selftest_write_log "$run" 2 1 0 0
  selftest_check_eval "failures-rejected" 1 "$run" full "" "RESULT RED_FAILURES" "FULL VERIFY PASSED"
  case_count=$((case_count + 1))

  run="$(selftest_run_dir errors full "" ModuleA)"
  selftest_write_xml "$run" ModuleA 1 0 1 0
  selftest_write_log "$run" 1 0 1 0
  selftest_check_eval "errors-rejected" 1 "$run" full "" "RESULT RED_ERRORS" "FULL VERIFY PASSED"
  case_count=$((case_count + 1))

  # ---- full 正样本 / focused 不能被标成 full ----
  MODULES=(ModuleA)
  run="$(selftest_run_dir full-green full "" ModuleA)"
  selftest_write_xml "$run" ModuleA 2 0 0 0
  selftest_write_log "$run" 2 0 0 0 1
  selftest_check_eval "full-green-banner" 0 "$run" full "" "FULL VERIFY PASSED" ""
  case_count=$((case_count + 1))

  run="$(selftest_run_dir full-missing-spotbugs full "" ModuleA)"
  selftest_write_xml "$run" ModuleA 2 0 0 0
  selftest_write_log "$run" 2 0 0 0 0
  selftest_check_eval \
    "full-missing-spotbugs-rejected" 3 "$run" full "" \
    "SpotBugs" "FULL VERIFY PASSED"
  case_count=$((case_count + 1))

  run="$(selftest_run_dir focused-green focused ModuleA ModuleA,UpstreamModule)"
  selftest_write_xml "$run" ModuleA 1 0 0 0
  selftest_write_log "$run" 1 0 0 0
  selftest_check_eval \
    "focused-not-full" 0 "$run" focused ModuleA \
    "FOCUSED TEST PASSED (NOT a full gate)|NO_TESTS UpstreamModule" \
    "FULL VERIFY PASSED"
  case_count=$((case_count + 1))

  # ---- focused 指定目标缺失 / 目标 0 tests ----
  run="$(selftest_run_dir focused-target-missing focused ModuleA ModuleA,UpstreamModule)"
  selftest_write_xml "$run" UpstreamModule 1 0 0 0
  selftest_write_log "$run" 1 0 0 0
  selftest_check_eval \
    "focused-target-missing-evidence" 3 "$run" focused ModuleA \
    "NO_REPORT focused target module ModuleA" "FOCUSED TEST PASSED"
  case_count=$((case_count + 1))

  run="$(selftest_run_dir focused-target-zero focused ModuleA ModuleA)"
  selftest_write_xml "$run" ModuleA 0 0 0 0
  selftest_write_log "$run" 0 0 0 0
  selftest_check_eval \
    "focused-target-zero-tests-rejected" 1 "$run" focused ModuleA \
    "RESULT RED_NO_TESTS" "FOCUSED TEST PASSED"
  case_count=$((case_count + 1))

  # 指定模块不存在（参数层拒绝，不能把它当通过）。
  MODULES=(AgentLibMosire WorkspaceMosire DocsMosire BrainMosire MainMosire BashPluginMosire)
  if validate_focused_args "NoSuchModule" "SomeTest" >/dev/null 2>&1; then
    selftest_fail "focused-missing-module-rejected" "不存在的模块未被拒"
  else
    selftest_pass "focused-missing-module-rejected"
  fi
  case_count=$((case_count + 1))

  if validate_focused_args "AgentLibMosire" "VersionTest -DskipTests" >/dev/null 2>&1; then
    selftest_fail "focused-arg-injection-rejected" "带空格的 pattern 未被拒"
  else
    selftest_pass "focused-arg-injection-rejected"
  fi
  case_count=$((case_count + 1))

  # ---- 旧报告不会混入本轮 ----
  selftest_old_report_case
  case_count=$((case_count + 1))

  # ---- 本 checkout 进程冲突（真实 /proc 分类，不跑 Maven） ----
  selftest_process_cases
  case_count=$((case_count + 1))

  trap - EXIT
  selftest_cleanup
  if [ "$SELFTEST_FAILURES" -eq 0 ]; then
    say "SELFTEST PASSED gate.sh (no real Maven was invoked)"
    return 0
  fi
  say "SELFTEST FAILED gate.sh ($SELFTEST_FAILURES failing checks)"
  return 1
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

main() {
  local subcommand="${1:-}"
  [ "$#" -gt 0 ] && shift
  case "$subcommand" in
    pre) cmd_pre "$@" ;;
    full) cmd_full "$@" ;;
    focused) cmd_focused "$@" ;;
    selftest) run_selftest "$@" ;;
    -h|--help|help)
      usage
      return 0
      ;;
    "")
      usage >&2
      return "$EXIT_REFUSED"
      ;;
    *)
      err "GATE REFUSED: 未知子命令: $subcommand"
      usage >&2
      return "$EXIT_REFUSED"
      ;;
  esac
}

main "$@"
exit $?
