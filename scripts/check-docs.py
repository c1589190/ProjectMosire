#!/usr/bin/env python3
"""ProjectMosire 文档一致性检查（L2 可执行门禁）。

检查项（任务 task-10）：
1. 根 pom.xml 的 <modules> 与 README.md / AGENTS.md 的模块清单一致；
2. README.md / AGENTS.md / docs/agents/*.md 的相对 Markdown 链接存在；
3. pages/index.html 仍保留 __MOSIRE_VERSION__ 发布占位符；
4. docs/agents/STATUS.md 存在；
5. .superpowers 不再被当作“权威实时状态”（允许“不是权威 / 历史留痕”这类否定句）。

不禁止历史测试数字、发布示例版本或归档文档里的旧路径；只抓会误导当前操作的
“当前状态漂移”（例如当前版本号与根 pom 不一致）。

退出码：0=通过，1=发现不一致，2=用法/IO 错误。
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
import urllib.parse
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

EXIT_PASS = 0
EXIT_FINDINGS = 1
EXIT_USAGE = 2

POM_NS = "{http://maven.apache.org/POM/4.0.0}"

# Markdown 行内链接 / 图片：[text](target "title")；以及引用式定义 [ref]: target
INLINE_LINK_RE = re.compile(
    r"!?\[[^\]\n]*\]\(\s*(<[^>\n]+>|[^)\s]+)(?:\s+[\"'][^\"']*[\"'])?\s*\)"
)
REFERENCE_LINK_RE = re.compile(r"^\[[^\]\n]+\]:\s*(<[^>\n]+>|\S+)", re.MULTILINE)
FENCE_RE = re.compile(
    r"^[ \t]*(?P<fence>`{3,}|~{3,})[^\n]*\n.*?^[ \t]*(?P=fence)[ \t]*$",
    re.MULTILINE | re.DOTALL,
)
INLINE_CODE_RE = re.compile(r"`[^`\n]*`")
CURRENT_VERSION_RE = re.compile(r"(?:当前(?:发布)?版本|current\s+version)", re.IGNORECASE)
SEMVER_RE = re.compile(r"\b\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?\b")
HISTORICAL_MARKERS = ("历史", "当时", "快照", "发布提交", "证据", "示例", "例如", "迁移", "归档")
SUPERPOWERS_NEGATION_MARKERS = (
    "不是",
    "不得",
    "不把",
    "不依赖",
    "不再",
    "不作",
    "不要",
    "不许",
    "非权威",
    "历史",
    "留痕",
    "清掉",
    "移除",
    "归档",
    "取代",
    "not authoritative",
    "historical",
    "deprecated",
)


@dataclass(frozen=True)
class Finding:
    code: str
    path: str
    line: int | None
    message: str

    def render(self) -> str:
        location = self.path if self.line is None else f"{self.path}:{self.line}"
        return f"FAIL [{self.code}] {location}: {self.message}"


def strip_code_keep_lines(text: str) -> str:
    """移除围栏代码块与行内代码，但保留行号/列号，避免示例里的假链接被当真。"""

    def fence_repl(match: re.Match[str]) -> str:
        return "\n" * match.group(0).count("\n")

    def inline_repl(match: re.Match[str]) -> str:
        return " " * len(match.group(0))

    return INLINE_CODE_RE.sub(inline_repl, FENCE_RE.sub(fence_repl, text))


def load_pom(root: Path) -> tuple[list[str], str | None, list[Finding]]:
    findings: list[Finding] = []
    pom = root / "pom.xml"
    if not pom.is_file():
        findings.append(Finding("POM_MISSING", "pom.xml", None, "根 pom.xml 不存在"))
        return [], None, findings
    try:
        tree = ET.parse(pom)
    except ET.ParseError as exc:
        findings.append(Finding("POM_PARSE", "pom.xml", None, f"XML 解析失败: {exc}"))
        return [], None, findings
    project = tree.getroot()
    modules: list[str] = []
    for node in project.findall(f"{POM_NS}modules/{POM_NS}module"):
        name = (node.text or "").strip()
        if name:
            modules.append(name)
    if not modules:
        findings.append(Finding("POM_MODULES", "pom.xml", None, "<modules> 为空或缺失"))
    version_node = project.find(f"{POM_NS}version")
    version = (version_node.text or "").strip() if version_node is not None else None
    if not version:
        findings.append(Finding("POM_VERSION", "pom.xml", None, "project/version 缺失"))
    return modules, version, findings


def check_module_mentions(root: Path, modules: list[str]) -> list[Finding]:
    findings: list[Finding] = []
    for rel in ("README.md", "AGENTS.md"):
        path = root / rel
        if not path.is_file():
            findings.append(Finding("FILE_MISSING", rel, None, "文件不存在"))
            continue
        text = path.read_text(encoding="utf-8")
        for module in modules:
            if module not in text:
                findings.append(
                    Finding("MODULE_MISSING", rel, None, f"缺少根 pom.xml 模块: {module}")
                )
    return findings


def check_links(root: Path, files: list[Path]) -> tuple[list[Finding], int]:
    findings: list[Finding] = []
    checked = 0
    root_resolved = root.resolve()
    for path in files:
        rel = path.relative_to(root).as_posix()
        if not path.is_file():
            findings.append(Finding("FILE_MISSING", rel, None, "文件不存在"))
            continue
        raw = path.read_text(encoding="utf-8")
        stripped = strip_code_keep_lines(raw)
        matches: list[tuple[int, str]] = []
        for match in INLINE_LINK_RE.finditer(stripped):
            matches.append((match.start(), match.group(1)))
        for match in REFERENCE_LINK_RE.finditer(stripped):
            matches.append((match.start(), match.group(1)))
        for position, raw_target in matches:
            target = raw_target.strip()
            if target.startswith("<") and target.endswith(">"):
                target = target[1:-1].strip()
            lower = target.lower()
            if (
                not target
                or target.startswith("#")
                or lower.startswith(("http://", "https://", "mailto:", "tel:", "data:", "javascript:"))
            ):
                continue
            if "{" in target or "}" in target:
                # 模板占位链接无法静态判定，跳过（不是“已验证存在”）。
                continue
            target_path = target.split("#", 1)[0].split("?", 1)[0]
            target_path = urllib.parse.unquote(target_path).strip()
            if not target_path:
                continue
            line = stripped.count("\n", 0, position) + 1
            checked += 1
            if target_path.startswith("/"):
                findings.append(
                    Finding("LINK_ABSOLUTE", rel, line, f"只允许仓库内相对链接: {target}")
                )
                continue
            resolved = (path.parent / target_path).resolve()
            try:
                resolved.relative_to(root_resolved)
            except ValueError:
                findings.append(
                    Finding("LINK_ESCAPE", rel, line, f"链接逃出仓库根: {target}")
                )
                continue
            if not resolved.exists():
                findings.append(Finding("DEAD_LINK", rel, line, f"相对链接目标不存在: {target}"))
    return findings, checked


def check_pages(root: Path) -> list[Finding]:
    pages = root / "pages" / "index.html"
    if not pages.is_file():
        return [Finding("PAGES_MISSING", "pages/index.html", None, "文件不存在")]
    text = pages.read_text(encoding="utf-8")
    if "__MOSIRE_VERSION__" not in text:
        return [
            Finding(
                "PAGES_PLACEHOLDER",
                "pages/index.html",
                None,
                "缺少 __MOSIRE_VERSION__ 占位符（发布工作流靠它替换版本）",
            )
        ]
    return []


def check_status(root: Path) -> list[Finding]:
    status = root / "docs" / "agents" / "STATUS.md"
    if not status.is_file():
        return [Finding("STATUS_MISSING", "docs/agents/STATUS.md", None, "实时状态入口不存在")]
    return []


def check_superpowers_authority(root: Path, files: list[Path]) -> list[Finding]:
    findings: list[Finding] = []
    for path in files:
        if not path.is_file():
            continue
        rel = path.relative_to(root).as_posix()
        for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if ".superpowers" not in line:
                continue
            lower = line.lower()
            if any(marker.lower() in lower for marker in SUPERPOWERS_NEGATION_MARKERS):
                continue
            findings.append(
                Finding(
                    "SUPERPOWERS_AUTHORITY",
                    rel,
                    line_no,
                    "把 .superpowers 当权威/实时状态；只允许“历史留痕/不是权威”这类否定句",
                )
            )
    return findings


def check_current_version_drift(root: Path, version: str | None, files: list[Path]) -> list[Finding]:
    if not version:
        return []
    findings: list[Finding] = []
    for path in files:
        if not path.is_file():
            continue
        rel = path.relative_to(root).as_posix()
        for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if not CURRENT_VERSION_RE.search(line):
                continue
            if any(marker in line for marker in HISTORICAL_MARKERS):
                continue
            claimed = SEMVER_RE.findall(line)
            if claimed and all(item != version for item in claimed):
                findings.append(
                    Finding(
                        "CURRENT_VERSION_DRIFT",
                        rel,
                        line_no,
                        f"“当前版本”声明 {', '.join(claimed)} 与根 pom version {version} 不一致",
                    )
                )
    return findings


def checked_files(root: Path) -> list[Path]:
    files = [root / "README.md", root / "AGENTS.md"]
    agents_dir = root / "docs" / "agents"
    if agents_dir.is_dir():
        files.extend(sorted(agents_dir.rglob("*.md")))
    return files


def run_checks(root: Path) -> tuple[list[Finding], dict[str, int]]:
    findings: list[Finding] = []
    modules, version, pom_findings = load_pom(root)
    findings.extend(pom_findings)
    findings.extend(check_module_mentions(root, modules))
    files = checked_files(root)
    link_findings, links_checked = check_links(root, files)
    findings.extend(link_findings)
    findings.extend(check_pages(root))
    findings.extend(check_status(root))
    findings.extend(check_superpowers_authority(root, files))
    findings.extend(check_current_version_drift(root, version, files))
    stats = {
        "modules": len(modules),
        "files": sum(1 for path in files if path.is_file()),
        "links": links_checked,
    }
    return findings, stats


# ---------------------------------------------------------------------------
# selftest：临时 fixture，验证正样本通过 + 各类坏样本会红。
# ---------------------------------------------------------------------------

SELFTEST_MODULES = [
    "AgentLibMosire",
    "WorkspaceMosire",
    "DocsMosire",
    "BrainMosire",
    "MainMosire",
    "BashPluginMosire",
]


def make_fixture(root: Path, version: str = "0.0.0") -> None:
    root.mkdir(parents=True, exist_ok=True)
    modules_xml = "\n".join(f"    <module>{name}</module>" for name in SELFTEST_MODULES)
    (root / "pom.xml").write_text(
        f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.mosire.selftest</groupId>
  <artifactId>selftest-parent</artifactId>
  <version>{version}</version>
  <packaging>pom</packaging>
  <modules>
{modules_xml}
  </modules>
</project>
""",
        encoding="utf-8",
    )
    module_lines = "\n".join(f"| `{name}` | selftest | — |" for name in SELFTEST_MODULES)
    (root / "README.md").write_text(
        f"""# Selftest README

| 模块 | 职责 |
|---|---|
{module_lines}

见 [`AGENTS.md`](AGENTS.md) 与 [`STATUS`](docs/agents/STATUS.md)。

> `.superpowers/**` 是历史/本机工具路径，**不是权威实时状态**。
""",
        encoding="utf-8",
    )
    (root / "AGENTS.md").write_text(
        f"""# Selftest AGENTS

模块：{', '.join(SELFTEST_MODULES)}

当前状态见 [`docs/agents/STATUS.md`](docs/agents/STATUS.md)。

历史文档里的 `.superpowers/...` 路径按历史留痕处理，**不是权威实时状态**。
""",
        encoding="utf-8",
    )
    agents_dir = root / "docs" / "agents"
    agents_dir.mkdir(parents=True, exist_ok=True)
    (agents_dir / "STATUS.md").write_text(
        f"""# Selftest STATUS

| 项目 | 当前事实 |
|---|---|
| 当前发布版本 | **{version}** |

返回 [`README`](../../README.md)。

> `.superpowers/**` 不是权威实时状态，仅历史留痕。
""",
        encoding="utf-8",
    )
    pages_dir = root / "pages"
    pages_dir.mkdir(parents=True, exist_ok=True)
    (pages_dir / "index.html").write_text(
        "<html><body>当前版本：<code>__MOSIRE_VERSION__</code></body></html>\n",
        encoding="utf-8",
    )


def selftest_case(name: str, root: Path, expected_rc: int, code: str | None) -> bool:
    findings, stats = run_checks(root)
    rc = EXIT_PASS if not findings else EXIT_FINDINGS
    codes = {finding.code for finding in findings}
    if rc == expected_rc and (code is None or code in codes):
        print(f"SELFTEST PASS check-docs.py {name} (rc={rc}, findings={len(findings)})")
        return True
    print(
        f"SELFTEST FAIL check-docs.py {name}: rc={rc} expected={expected_rc} "
        f"codes={sorted(codes)} expected_code={code}"
    )
    for finding in findings:
        print("  " + finding.render())
    return False


def run_selftest() -> int:
    cases: list[bool] = []
    with tempfile.TemporaryDirectory(prefix="mosire-check-docs-selftest-") as tmp:
        base = Path(tmp)

        root = base / "valid"
        make_fixture(root)
        cases.append(selftest_case("valid-fixture-green", root, EXIT_PASS, None))

        root = base / "module-missing"
        make_fixture(root)
        readme = root / "README.md"
        readme.write_text(
            readme.read_text(encoding="utf-8").replace("`BashPluginMosire`", "`REMOVED-MODULE`"),
            encoding="utf-8",
        )
        cases.append(selftest_case("module-missing-red", root, EXIT_FINDINGS, "MODULE_MISSING"))

        root = base / "dead-link"
        make_fixture(root)
        status = root / "docs" / "agents" / "STATUS.md"
        status.write_text(
            status.read_text(encoding="utf-8") + "\n[dead](docs/agents/does-not-exist.md)\n",
            encoding="utf-8",
        )
        cases.append(selftest_case("dead-link-red", root, EXIT_FINDINGS, "DEAD_LINK"))

        root = base / "pages-placeholder"
        make_fixture(root)
        (root / "pages" / "index.html").write_text("<html>no placeholder</html>\n", encoding="utf-8")
        cases.append(
            selftest_case("pages-placeholder-missing-red", root, EXIT_FINDINGS, "PAGES_PLACEHOLDER")
        )

        root = base / "status-missing"
        make_fixture(root)
        (root / "docs" / "agents" / "STATUS.md").unlink()
        cases.append(selftest_case("status-missing-red", root, EXIT_FINDINGS, "STATUS_MISSING"))

        root = base / "superpowers-authority"
        make_fixture(root)
        status = root / "docs" / "agents" / "STATUS.md"
        status.write_text(
            status.read_text(encoding="utf-8") + "\n实时状态以 `.superpowers/sdd/progress.md` 为准。\n",
            encoding="utf-8",
        )
        cases.append(
            selftest_case(
                "superpowers-authority-red", root, EXIT_FINDINGS, "SUPERPOWERS_AUTHORITY"
            )
        )

        root = base / "version-drift"
        make_fixture(root)
        status = root / "docs" / "agents" / "STATUS.md"
        status.write_text(
            status.read_text(encoding="utf-8") + "\n当前发布版本：**9.9.9**\n",
            encoding="utf-8",
        )
        cases.append(
            selftest_case("current-version-drift-red", root, EXIT_FINDINGS, "CURRENT_VERSION_DRIFT")
        )

        # 代码块里的假链接不得被当成死链（不然示例文档无法写）。
        root = base / "fenced-example"
        make_fixture(root)
        status = root / "docs" / "agents" / "STATUS.md"
        status.write_text(
            status.read_text(encoding="utf-8")
            + "\n```markdown\n[example](does/not/exist.md)\n```\n",
            encoding="utf-8",
        )
        cases.append(selftest_case("fenced-code-link-ignored", root, EXIT_PASS, None))

    passed = sum(1 for case in cases if case)
    total = len(cases)
    if passed == total:
        print(f"SELFTEST PASSED check-docs.py ({passed}/{total} cases)")
        return 0
    print(f"SELFTEST FAILED check-docs.py ({passed}/{total} cases)")
    return 1


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="ProjectMosire docs consistency check")
    parser.add_argument("--root", type=Path, help="repository root (default: script's parent repo)")
    parser.add_argument("--selftest", action="store_true", help="run temporary-fixture self-tests")
    args = parser.parse_args(argv)

    if args.selftest:
        return run_selftest()
    root = (args.root if args.root is not None else Path(__file__).resolve().parent.parent).resolve()
    if not root.is_dir():
        print(f"check-docs.py: root does not exist: {root}", file=sys.stderr)
        return EXIT_USAGE
    findings, stats = run_checks(root)
    if findings:
        for finding in findings:
            print(finding.render())
        print(
            f"DOCS CHECK FAILED ({len(findings)} findings; "
            f"modules={stats['modules']} files={stats['files']} links={stats['links']})"
        )
        return EXIT_FINDINGS
    print(
        f"DOCS CHECK PASSED (modules={stats['modules']} files={stats['files']} "
        f"links={stats['links']})"
    )
    return EXIT_PASS


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
