#!/usr/bin/env python3
"""ProjectMosire run-bound Surefire/Maven 测试汇总。

为什么存在：`./mvnw test` 的 rc=0 不等于“测试真的跑过且全绿”：
- `-DskipTests`、类名写错、目标模块没有匹配测试都会让 Maven 退出 0；
- `target/surefire-reports/` 不会被自动清理，旧报告会和新报告混在一起；
- 源码测试类数不等于用例数（嵌套/参数化/动态测试由 Surefire 自己计数）。

本脚本只解析“本轮 run 专属目录”里的证据：
    <run-dir>/manifest.txt
    <run-dir>/maven.log
    <run-dir>/surefire-reports/<Module>/TEST-*.xml

证据由 `scripts/gate.sh` 在运行前移走旧报告、运行后拷贝本轮报告来保证。
mtime 只作为辅助提示，不作为通过依据。

退出码：
    0 = GREEN（至少有测试，0 failures / 0 errors；skips 单独列出）
    1 = RED（0 tests / failures / errors / Maven BUILD FAILURE）
    2 = 用法错误
    3 = 证据不完整（manifest 缺失/绑定不符/报告缺失/XML 解析失败）
"""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

EXIT_GREEN = 0
EXIT_RED = 1
EXIT_USAGE = 2
EXIT_INCOMPLETE = 3

REQUIRED_MANIFEST_KEYS = ("run_id", "source_sha", "started_at", "mode", "command")
VALID_MODES = ("full", "focused")

TESTS_RUN_RE = re.compile(
    r"Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+),\s*Skipped:\s*(\d+)"
)
BUILD_SUCCESS_RE = re.compile(r"\bBUILD SUCCESS\b")
BUILD_FAILURE_RE = re.compile(r"\bBUILD FAILURE\b")


def parse_manifest(path: Path) -> tuple[dict[str, str], list[str]]:
    """解析 `key=value` / `key: value` 形式的 run manifest。"""
    data: dict[str, str] = {}
    problems: list[str] = []
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        return {}, [f"cannot read manifest {path}: {exc}"]
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" in line:
            key, value = line.split("=", 1)
        elif ":" in line:
            key, value = line.split(":", 1)
        else:
            problems.append(f"manifest line not key=value: {line!r}")
            continue
        key = key.strip()
        value = value.strip()
        if not key:
            problems.append(f"manifest line with empty key: {line!r}")
            continue
        data[key] = value
    return data, problems


@dataclass
class ReportModule:
    module: str
    files: int = 0
    tests: int = 0
    failures: int = 0
    errors: int = 0
    skipped: int = 0
    skipped_cases: list[str] = field(default_factory=list)
    failed_cases: list[str] = field(default_factory=list)
    parse_errors: list[str] = field(default_factory=list)
    mtime_before_start: int = 0

    def add(self, tests: int, failures: int, errors: int, skipped: int) -> None:
        self.tests += tests
        self.failures += failures
        self.errors += errors
        self.skipped += skipped

    @property
    def status(self) -> str:
        if self.parse_errors:
            return "PARSE_ERROR"
        if self.failures or self.errors:
            return "RED"
        if self.tests == 0:
            return "NO_TESTS"
        return "OK"


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def parse_surefire_xml(path: Path, module: str) -> ReportModule:
    report = ReportModule(module=module)
    report.files = 1
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        report.parse_errors.append(f"{path.name}: XML parse error: {exc}")
        return report
    suites = []
    if _local(root.tag) == "testsuite":
        suites = [root]
    elif _local(root.tag) == "testsuites":
        suites = [el for el in root if _local(el.tag) == "testsuite"]
    else:
        report.parse_errors.append(f"{path.name}: root tag <{_local(root.tag)}> is not testsuite")
        return report
    if not suites:
        report.parse_errors.append(f"{path.name}: no <testsuite> element")
        return report
    for suite in suites:
        def _int(name: str) -> int:
            raw = suite.get(name)
            if raw is None or raw == "":
                # 没有写 attributes 的 testsuite 不能当作 0 通过；只有 0 是明确值。
                return 0
            try:
                return int(raw)
            except ValueError:
                report.parse_errors.append(f"{path.name}: testsuite @{name}={raw!r} is not an integer")
                return 0

        report.add(_int("tests"), _int("failures"), _int("errors"), _int("skipped"))
        for case in suite.iter():
            if _local(case.tag) != "testcase":
                continue
            classname = case.get("classname") or "<no-class>"
            name = case.get("name") or "<no-name>"
            label = f"{classname}#{name}"
            children = {_local(child.tag) for child in case}
            if "skipped" in children or case.get("status") == "skipped":
                report.skipped_cases.append(label)
            if "failure" in children:
                report.failed_cases.append(f"{label} (failure)")
            if "error" in children:
                report.failed_cases.append(f"{label} (error)")
    return report


def collect_reports(run_dir: Path, started_epoch: float | None) -> tuple[dict[str, ReportModule], list[str]]:
    """只从本轮 run 专属目录收集 TEST-*.xml；不读取任何模块的 target/。"""
    modules: dict[str, ReportModule] = {}
    problems: list[str] = []
    reports_root = run_dir / "surefire-reports"
    if not reports_root.is_dir():
        return modules, problems
    for module_dir in sorted(p for p in reports_root.iterdir() if p.is_dir()):
        report = modules.setdefault(module_dir.name, ReportModule(module=module_dir.name))
        for xml_path in sorted(module_dir.glob("TEST-*.xml")):
            parsed = parse_surefire_xml(xml_path, module_dir.name)
            report.files += parsed.files
            report.tests += parsed.tests
            report.failures += parsed.failures
            report.errors += parsed.errors
            report.skipped += parsed.skipped
            report.skipped_cases.extend(parsed.skipped_cases)
            report.failed_cases.extend(parsed.failed_cases)
            report.parse_errors.extend(parsed.parse_errors)
            if started_epoch is not None:
                try:
                    if xml_path.stat().st_mtime < started_epoch:
                        report.mtime_before_start += 1
                except OSError:
                    pass
    for report in modules.values():
        problems.extend(report.parse_errors)
    return modules, problems


def parse_maven_log(path: Path) -> dict[str, object]:
    """解析 Maven 日志里的 BUILD 结果与模块级 Tests run 汇总。

    注意：Surefire 对每个测试类也会打印 `Tests run: ...`，但行尾带 `-- in <class>`；
    只有不带 `-- in` 的行才是模块/全局汇总，避免重复计数。
    """
    info: dict[str, object] = {
        "present": False,
        "build": None,
        "log_tests": None,
        "log_failures": None,
        "log_errors": None,
        "log_skipped": None,
        "summary_lines": 0,
    }
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return info
    info["present"] = True
    last_success = False
    last_failure = False
    totals = [0, 0, 0, 0]
    summary_lines = 0
    for line in text.splitlines():
        if BUILD_SUCCESS_RE.search(line):
            last_success = True
            last_failure = False
        if BUILD_FAILURE_RE.search(line):
            last_failure = True
            last_success = False
        if "-- in " in line:
            continue
        match = TESTS_RUN_RE.search(line)
        if not match:
            continue
        summary_lines += 1
        for index, value in enumerate(match.groups()):
            totals[index] += int(value)
    if last_failure:
        info["build"] = "FAILURE"
    elif last_success:
        info["build"] = "SUCCESS"
    info["summary_lines"] = summary_lines
    if summary_lines:
        info["log_tests"] = totals[0]
        info["log_failures"] = totals[1]
        info["log_errors"] = totals[2]
        info["log_skipped"] = totals[3]
    return info


def _parse_started_epoch(raw: str) -> float | None:
    text = raw.strip()
    if not text:
        return None
    try:
        return datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def evaluate(
    run_dir: Path,
    mode_arg: str | None = None,
    target_module_arg: str | None = None,
) -> tuple[int, list[str], dict[str, object]]:
    """评估一次 run，返回 (exit_code, 输出行, 结构化结果)。"""
    lines: list[str] = []
    result: dict[str, object] = {"run_dir": str(run_dir), "result": "EVIDENCE_INCOMPLETE", "exit_code": EXIT_INCOMPLETE}
    manifest_path = run_dir / "manifest.txt"
    if not manifest_path.is_file():
        lines.append(f"EVIDENCE_INCOMPLETE manifest missing: {manifest_path}")
        lines.append("RESULT EVIDENCE_INCOMPLETE")
        return EXIT_INCOMPLETE, lines, result

    manifest, manifest_problems = parse_manifest(manifest_path)
    missing = [key for key in REQUIRED_MANIFEST_KEYS if not manifest.get(key)]
    if missing:
        lines.append(f"EVIDENCE_INCOMPLETE manifest missing keys: {', '.join(missing)}")
        lines.append("RESULT EVIDENCE_INCOMPLETE")
        result["manifest_problems"] = manifest_problems + [f"missing key {k}" for k in missing]
        return EXIT_INCOMPLETE, lines, result

    run_id = manifest["run_id"]
    source_sha = manifest["source_sha"]
    started_at = manifest["started_at"]
    manifest_mode = manifest["mode"]
    scope = manifest.get("scope", "repo")
    command = manifest["command"]
    worktree = manifest.get("worktree", "unknown")
    lines.append(
        f"RUN run_id={run_id} source_sha={source_sha} mode={manifest_mode} scope={scope}"
    )
    lines.append(f"MANIFEST started_at={started_at} worktree={worktree} command={command}")
    for problem in manifest_problems:
        lines.append(f"ADVISORY manifest_problem={problem}")

    if manifest_mode not in VALID_MODES:
        lines.append(f"EVIDENCE_INCOMPLETE manifest mode={manifest_mode!r} is not full/focused")
        lines.append("RESULT EVIDENCE_INCOMPLETE")
        return EXIT_INCOMPLETE, lines, result
    if mode_arg is not None and mode_arg != manifest_mode:
        lines.append(
            f"EVIDENCE_INCOMPLETE mode mismatch: requested={mode_arg} manifest={manifest_mode}"
        )
        lines.append("RESULT EVIDENCE_INCOMPLETE")
        return EXIT_INCOMPLETE, lines, result

    target_module = manifest.get("target_module", "")
    if target_module_arg is not None:
        if not target_module:
            lines.append(
                f"EVIDENCE_INCOMPLETE --target-module={target_module_arg} but manifest has no target_module"
            )
            lines.append("RESULT EVIDENCE_INCOMPLETE")
            return EXIT_INCOMPLETE, lines, result
        if target_module_arg != target_module:
            lines.append(
                f"EVIDENCE_INCOMPLETE target mismatch: requested={target_module_arg} manifest={target_module}"
            )
            lines.append("RESULT EVIDENCE_INCOMPLETE")
            return EXIT_INCOMPLETE, lines, result
    if manifest_mode == "focused":
        if not target_module:
            lines.append("EVIDENCE_INCOMPLETE focused manifest missing target_module")
            lines.append("RESULT EVIDENCE_INCOMPLETE")
            return EXIT_INCOMPLETE, lines, result

    started_epoch = _parse_started_epoch(started_at)
    modules, report_problems = collect_reports(run_dir, started_epoch)
    log_info = parse_maven_log(run_dir / "maven.log")

    declared_modules: list[str] = []
    for raw in manifest.get("modules", "").split(","):
        name = raw.strip()
        if name and name not in declared_modules:
            declared_modules.append(name)
    report_modules = sorted(modules)
    # 依赖模块在 focused 下零匹配测试是正常现象：Surefire 可能根本不生成报告目录。
    # 因此 NO_TESTS 同时覆盖「没有本轮报告」与「有报告但 tests=0」两类模块；
    # 只有 focused 的目标模块缺报告才是证据不完整（见下面的判定）。
    no_tests_modules = [
        m for m in declared_modules if m not in modules or modules[m].tests == 0
    ]
    undeclared_report_modules = [m for m in report_modules if declared_modules and m not in declared_modules]

    for module in report_modules:
        report = modules[module]
        lines.append(
            "MODULE {module} files={files} tests={tests} failures={failures} errors={errors} "
            "skipped={skipped} status={status} mtime_before_start={mtime}".format(
                module=module,
                files=report.files,
                tests=report.tests,
                failures=report.failures,
                errors=report.errors,
                skipped=report.skipped,
                status=report.status,
                mtime=report.mtime_before_start,
            )
        )
    if no_tests_modules:
        lines.append(f"NO_TESTS {','.join(no_tests_modules)}")
    if undeclared_report_modules:
        lines.append(f"UNDECLARED_REPORT {','.join(undeclared_report_modules)}")
    for module in report_modules:
        for case in modules[module].skipped_cases:
            lines.append(f"SKIPS {module}: {case}")
    for module in report_modules:
        for case in modules[module].failed_cases[:50]:
            lines.append(f"FAILED_CASE {module}: {case}")

    total_tests = sum(report.tests for report in modules.values())
    total_failures = sum(report.failures for report in modules.values())
    total_errors = sum(report.errors for report in modules.values())
    total_skipped = sum(report.skipped for report in modules.values())
    report_files = sum(report.files for report in modules.values())
    lines.append(
        f"TOTAL tests={total_tests} failures={total_failures} errors={total_errors} "
        f"skipped={total_skipped} report_files={report_files} modules_with_reports={len(report_modules)}"
    )
    build = log_info.get("build")
    if log_info.get("present"):
        lines.append(
            f"MAVEN build={build or 'UNKNOWN'} tests_run_summary={log_info.get('log_tests')} "
            f"summary_lines={log_info.get('summary_lines')}"
        )
    if log_info.get("log_tests") is not None:
        if int(log_info["log_tests"]) == total_tests:  # type: ignore[arg-type]
            lines.append(f"CROSSCHECK log={log_info['log_tests']} xml={total_tests} status=MATCH")
        else:
            lines.append(
                f"CROSSCHECK log={log_info['log_tests']} xml={total_tests} status=MISMATCH "
                "(Maven 汇总与 run-dir XML 不一致；以本轮 Maven 汇总为准并人工复核)"
            )
    mtime_before = sum(report.mtime_before_start for report in modules.values())
    lines.append(f"ADVISORY mtime_before_start={mtime_before} (mtime 只作辅助，不作通过依据)")

    result.update(
        {
            "run_id": run_id,
            "source_sha": source_sha,
            "mode": manifest_mode,
            "scope": scope,
            "target_module": target_module or None,
            "modules": {
                name: {
                    "files": report.files,
                    "tests": report.tests,
                    "failures": report.failures,
                    "errors": report.errors,
                    "skipped": report.skipped,
                    "status": report.status,
                }
                for name, report in modules.items()
            },
            "no_tests_modules": no_tests_modules,
            "undeclared_report_modules": undeclared_report_modules,
            "totals": {
                "tests": total_tests,
                "failures": total_failures,
                "errors": total_errors,
                "skipped": total_skipped,
            },
            "maven": log_info,
        }
    )

    # ---- 判定顺序：证据完整性 → 构建结果 → 测试结果 ----
    if report_problems:
        for problem in report_problems:
            lines.append(f"EVIDENCE_INCOMPLETE {problem}")
        lines.append("RESULT EVIDENCE_INCOMPLETE")
        result["result"] = "EVIDENCE_INCOMPLETE"
        result["exit_code"] = EXIT_INCOMPLETE
        return EXIT_INCOMPLETE, lines, result
    if build is None:
        lines.append("EVIDENCE_INCOMPLETE maven.log missing BUILD SUCCESS/FAILURE")
        lines.append("RESULT EVIDENCE_INCOMPLETE")
        result["result"] = "EVIDENCE_INCOMPLETE"
        result["exit_code"] = EXIT_INCOMPLETE
        return EXIT_INCOMPLETE, lines, result
    manifest_rc_raw = manifest.get("maven_exit_code")
    manifest_rc: int | None = None
    if manifest_rc_raw is not None:
        try:
            manifest_rc = int(manifest_rc_raw)
        except ValueError:
            lines.append(f"EVIDENCE_INCOMPLETE manifest maven_exit_code={manifest_rc_raw!r} not an integer")
            lines.append("RESULT EVIDENCE_INCOMPLETE")
            result["result"] = "EVIDENCE_INCOMPLETE"
            result["exit_code"] = EXIT_INCOMPLETE
            return EXIT_INCOMPLETE, lines, result
    if build == "FAILURE" or (manifest_rc is not None and manifest_rc != 0):
        lines.append(f"RESULT RED_BUILD (build={build} maven_exit_code={manifest_rc_raw})")
        result["result"] = "RED_BUILD"
        result["exit_code"] = EXIT_RED
        return EXIT_RED, lines, result
    if report_files == 0:
        lines.append("NO_REPORT no TEST-*.xml in run-dir/surefire-reports")
        lines.append("RESULT EVIDENCE_INCOMPLETE")
        result["result"] = "EVIDENCE_INCOMPLETE"
        result["exit_code"] = EXIT_INCOMPLETE
        return EXIT_INCOMPLETE, lines, result
    if total_tests == 0:
        lines.append("RESULT RED_NO_TESTS (报告存在但 tests=0)")
        result["result"] = "RED_NO_TESTS"
        result["exit_code"] = EXIT_RED
        return EXIT_RED, lines, result
    if total_failures or total_errors:
        label = "RED_FAILURES" if total_failures else "RED_ERRORS"
        lines.append(f"RESULT {label} failures={total_failures} errors={total_errors}")
        result["result"] = label
        result["exit_code"] = EXIT_RED
        return EXIT_RED, lines, result
    if manifest_mode == "focused":
        if target_module not in modules:
            lines.append(f"NO_REPORT focused target module {target_module} has no run-bound report")
            lines.append("RESULT EVIDENCE_INCOMPLETE")
            result["result"] = "EVIDENCE_INCOMPLETE"
            result["exit_code"] = EXIT_INCOMPLETE
            return EXIT_INCOMPLETE, lines, result
        if modules[target_module].tests == 0:
            lines.append(
                f"RESULT RED_NO_TESTS focused target {target_module} matched 0 tests "
                "(test pattern probably wrong; -Dsurefire.failIfNoSpecifiedTests=false hides it)"
            )
            result["result"] = "RED_NO_TESTS"
            result["exit_code"] = EXIT_RED
            return EXIT_RED, lines, result
    lines.append("RESULT GREEN")
    result["result"] = "GREEN"
    result["exit_code"] = EXIT_GREEN
    return EXIT_GREEN, lines, result


# ---------------------------------------------------------------------------
# selftest：用临时 manifest/XML/日志验证七类结果，不接触真实仓库与 Maven。
# ---------------------------------------------------------------------------


def _write_manifest(run_dir: Path, **overrides: str) -> None:
    values = {
        "run_id": "selftest-run",
        "source_sha": "0123456789abcdef0123456789abcdef01234567",
        "started_at": "2026-10-17T20:00:00+08:00",
        "mode": "full",
        "scope": "repo",
        "command": "./mvnw -B -ntp verify",
        "worktree": "dirty(untracked:selftest)",
        "modules": "ModuleA,ModuleB",
        "maven_exit_code": "0",
    }
    values.update(overrides)
    body = "\n".join(f"{key}={value}" for key, value in values.items() if value is not None)
    (run_dir / "manifest.txt").write_text(body + "\n", encoding="utf-8")


def _write_log(run_dir: Path, text: str) -> None:
    (run_dir / "maven.log").write_text(text, encoding="utf-8")


def _write_xml(
    run_dir: Path,
    module: str,
    cases: list[tuple[str, str]],
    filename: str = "TEST-Selftest.xml",
) -> Path:
    """cases: [(testcase_name, "pass"|"failure"|"error"|"skip")]"""
    tests = len(cases)
    failures = sum(1 for _, status in cases if status == "failure")
    errors = sum(1 for _, status in cases if status == "error")
    skipped = sum(1 for _, status in cases if status == "skip")
    body = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        f'<testsuite name="selftest.{module}" tests="{tests}" failures="{failures}" '
        f'errors="{errors}" skipped="{skipped}" time="0.01">',
    ]
    for name, status in cases:
        if status == "pass":
            body.append(f'  <testcase classname="selftest.{module}" name="{name}" time="0.001"/>')
        elif status == "failure":
            body.append(
                f'  <testcase classname="selftest.{module}" name="{name}" time="0.001">'
                '<failure message="assertion failed">selftest</failure></testcase>'
            )
        elif status == "error":
            body.append(
                f'  <testcase classname="selftest.{module}" name="{name}" time="0.001">'
                '<error message="boom">selftest</error></testcase>'
            )
        elif status == "skip":
            body.append(
                f'  <testcase classname="selftest.{module}" name="{name}" time="0">'
                '<skipped message="selftest skip"/></testcase>'
            )
        else:
            raise ValueError(status)
    body.append("</testsuite>")
    module_dir = run_dir / "surefire-reports" / module
    module_dir.mkdir(parents=True, exist_ok=True)
    path = module_dir / filename
    path.write_text("\n".join(body) + "\n", encoding="utf-8")
    return path


def _run_eval(run_dir: Path, mode: str | None = None, target: str | None = None) -> tuple[int, str]:
    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer):
        rc, lines, _ = evaluate(run_dir, mode, target)
    text = buffer.getvalue() + "\n".join(lines)
    # evaluate() 只返回 lines；为了同时支持直接打印和 selftest，这里统一成文本。
    return rc, text


def _selftest_case(name: str, run_dir: Path, expected_rc: int, needles: list[str], **kwargs: str) -> bool:
    rc, text = _run_eval(run_dir, kwargs.get("mode"), kwargs.get("target"))  # type: ignore[arg-type]
    missing = [needle for needle in needles if needle not in text]
    if rc == expected_rc and not missing:
        print(f"SELFTEST PASS test-summary.py {name} (rc={rc})")
        return True
    print(f"SELFTEST FAIL test-summary.py {name}: rc={rc} expected={expected_rc} missing={missing}")
    print("---- captured output ----")
    print(text)
    print("-------------------------")
    return False


def run_selftest() -> int:
    cases: list[bool] = []
    with tempfile.TemporaryDirectory(prefix="mosire-test-summary-selftest-") as tmp:
        root = Path(tmp)
        green_log = "[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0\n[INFO] BUILD SUCCESS\n"

        run = root / "green"
        run.mkdir()
        _write_manifest(run)
        _write_log(run, green_log)
        _write_xml(run, "ModuleA", [("alpha", "pass"), ("beta", "pass")])
        _write_xml(run, "ModuleB", [("gamma", "pass")])
        cases.append(_selftest_case("all-green", run, 0, ["RESULT GREEN", "TOTAL tests=3", "source_sha=0123456"]))

        run = root / "zero-tests"
        run.mkdir()
        _write_manifest(run, modules="ModuleA")
        _write_log(run, "[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0\n[INFO] BUILD SUCCESS\n")
        _write_xml(run, "ModuleA", [])
        cases.append(_selftest_case("zero-tests-red", run, 1, ["RESULT RED_NO_TESTS"]))

        run = root / "failure"
        run.mkdir()
        _write_manifest(run, modules="ModuleA")
        _write_log(run, "[INFO] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0\n[INFO] BUILD SUCCESS\n")
        _write_xml(run, "ModuleA", [("good", "pass"), ("bad", "failure")])
        cases.append(_selftest_case("failure-red", run, 1, ["RESULT RED_FAILURES", "FAILED_CASE"]))

        run = root / "error"
        run.mkdir()
        _write_manifest(run, modules="ModuleA")
        _write_log(run, "[INFO] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0\n[INFO] BUILD SUCCESS\n")
        _write_xml(run, "ModuleA", [("boom", "error")])
        cases.append(_selftest_case("error-red", run, 1, ["RESULT RED_ERRORS"]))

        run = root / "skip"
        run.mkdir()
        _write_manifest(run, modules="ModuleA")
        _write_log(run, "[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 1\n[INFO] BUILD SUCCESS\n")
        _write_xml(run, "ModuleA", [("ran", "pass"), ("skippedOne", "skip")])
        cases.append(
            _selftest_case(
                "skip-listed-green",
                run,
                0,
                ["RESULT GREEN", "SKIPS ModuleA: selftest.ModuleA#skippedOne", "skipped=1"],
            )
        )

        run = root / "focused-upstream-zero"
        run.mkdir()
        _write_manifest(
            run,
            mode="focused",
            scope="module:ModuleA:AlphaTest",
            target_module="ModuleA",
            modules="UpstreamModule,ModuleA,DownstreamModule",
            command="./mvnw -B -ntp -Dtest=AlphaTest -Dsurefire.failIfNoSpecifiedTests=false test -pl ModuleA -am",
        )
        _write_log(run, "[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\n[INFO] BUILD SUCCESS\n")
        _write_xml(run, "ModuleA", [("alpha", "pass")])
        cases.append(
            _selftest_case(
                "focused-upstream-zero-ok",
                run,
                0,
                ["RESULT GREEN", "NO_TESTS UpstreamModule,DownstreamModule"],
                mode="focused",
                target="ModuleA",
            )
        )

        run = root / "focused-target-missing"
        run.mkdir()
        _write_manifest(
            run,
            mode="focused",
            scope="module:ModuleA:AlphaTest",
            target_module="ModuleA",
            modules="UpstreamModule,ModuleA",
            command="./mvnw -B -ntp -Dtest=AlphaTest -Dsurefire.failIfNoSpecifiedTests=false test -pl ModuleA -am",
        )
        _write_log(run, "[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\n[INFO] BUILD SUCCESS\n")
        _write_xml(run, "UpstreamModule", [("upstream", "pass")])
        cases.append(
            _selftest_case(
                "focused-target-missing-evidence",
                run,
                3,
                ["NO_REPORT focused target module ModuleA"],
                mode="focused",
                target="ModuleA",
            )
        )

        run = root / "no-report"
        run.mkdir()
        _write_manifest(run)
        _write_log(run, "[INFO] BUILD SUCCESS\n")
        cases.append(_selftest_case("no-report-evidence-incomplete", run, 3, ["NO_REPORT", "RESULT EVIDENCE_INCOMPLETE"]))

        run = root / "missing-manifest"
        run.mkdir()
        _write_log(run, "[INFO] BUILD SUCCESS\n")
        cases.append(_selftest_case("missing-manifest-incomplete", run, 3, ["manifest missing"]))

        run = root / "mode-mismatch"
        run.mkdir()
        _write_manifest(run, mode="focused", target_module="ModuleA", modules="ModuleA")
        _write_log(run, "[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\n[INFO] BUILD SUCCESS\n")
        _write_xml(run, "ModuleA", [("alpha", "pass")])
        cases.append(_selftest_case("mode-mismatch-incomplete", run, 3, ["mode mismatch"], mode="full"))

    passed = sum(1 for case in cases if case)
    total = len(cases)
    if passed == total:
        print(f"SELFTEST PASSED test-summary.py ({passed}/{total} cases)")
        return 0
    print(f"SELFTEST FAILED test-summary.py ({passed}/{total} cases)")
    return 1


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="ProjectMosire run-bound Surefire/Maven summary")
    parser.add_argument("--run-dir", type=Path, help="gate.sh run directory (.work/gate/<run_id>)")
    parser.add_argument("--mode", choices=VALID_MODES, help="must match manifest mode")
    parser.add_argument("--target-module", help="focused mode: module that must have run-bound tests")
    parser.add_argument("--json", action="store_true", help="emit JSON instead of text")
    parser.add_argument("--selftest", action="store_true", help="run fixture self-tests (no Maven)")
    args = parser.parse_args(argv)

    if args.selftest:
        return run_selftest()
    if args.run_dir is None:
        parser.print_usage(sys.stderr)
        print("test-summary.py: --run-dir is required (or use --selftest)", file=sys.stderr)
        return EXIT_USAGE
    if not args.run_dir.is_dir():
        print(f"EVIDENCE_INCOMPLETE run dir missing: {args.run_dir}", file=sys.stderr)
        return EXIT_INCOMPLETE
    rc, lines, result = evaluate(args.run_dir, args.mode, args.target_module)
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        for line in lines:
            print(line)
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
