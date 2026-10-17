#!/usr/bin/env python3
"""ProjectMosire 工程 Skill 契约检查（active 目录：.agents/skills/<name>/SKILL.md）。

当前仓库可以没有 `.agents/skills/`：0 个 active Skill 时 exit 0。
若目录存在，只扫描一层 `.agents/skills/<name>/SKILL.md`，并校验：
- frontmatter 的 name/description 必填，name 是 kebab-case 且等于目录名；
- 体量上限（8 KiB / 120 行）；
- 相对 Markdown 链接存在；
- 无 `@` 导入、无密钥模式、无 `-Dskip` 指令；
- 深层 SKILL.md、active root 里的候选/非 active 目录、软链目录、意外文件都会报错。

退出码：0=通过，1=发现契约问题，2=用法/IO 错误。
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

EXIT_PASS = 0
EXIT_FINDINGS = 1
EXIT_USAGE = 2

MAX_SKILL_BYTES = 8 * 1024
MAX_SKILL_LINES = 120
MAX_NAME_LENGTH = 64

NAME_RE = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
INLINE_LINK_RE = re.compile(
    r"!?\[[^\]\n]*\]\(\s*(<[^>\n]+>|[^)\s]+)(?:\s+[\"'][^\"']*[\"'])?\s*\)"
)
REFERENCE_LINK_RE = re.compile(r"^\[[^\]\n]+\]:\s*(<[^>\n]+>|\S+)", re.MULTILINE)
FENCE_RE = re.compile(
    r"^[ \t]*(?P<fence>`{3,}|~{3,})[^\n]*\n.*?^[ \t]*(?P=fence)[ \t]*$",
    re.MULTILINE | re.DOTALL,
)
INLINE_CODE_RE = re.compile(r"`[^`\n]*`")
AT_IMPORT_RE = re.compile(r"^\s*@[A-Za-z0-9_.\-/]+")
AT_IMPORT_WORD_RE = re.compile(r"@import\b")
SKIP_DIRECTIVE_RE = re.compile(
    r"(?:-D\s*(?:maven\.test\.)?skip(?:Tests|Executions)?"
    r"|-Dmaven\.test\.skip"
    r"|-Dspotless\.[A-Za-z0-9_.-]*skip"
    r"|-Dcheckstyle\.skip"
    r"|-Dspotbugs\.skip"
    r"|-Denforcer\.skip"
    r"|-Djacoco\.skip)",
    re.IGNORECASE,
)
SKIP_NEGATION_MARKERS = (
    "禁止",
    "不得",
    "不要",
    "不许",
    "拒绝",
    "不可",
    "never",
    "forbidden",
    "do not",
    "don't",
)

SECRET_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("AWS access key", re.compile(r"AKIA[0-9A-Z]{16}")),
    ("GitHub token", re.compile(r"gh[pousr]_[A-Za-z0-9]{36,}")),
    ("OpenAI-style key", re.compile(r"\bsk-[A-Za-z0-9]{20,}\b")),
    (
        "private key block",
        re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    ),
    (
        "generic secret assignment",
        re.compile(
            r"(?i)\b(?:api[_-]?key|secret|token|password|passwd)\b\s*[:=]\s*['\"]?[A-Za-z0-9_\-./+]{16,}"
        ),
    ),
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
    def fence_repl(match: re.Match[str]) -> str:
        return "\n" * match.group(0).count("\n")

    def inline_repl(match: re.Match[str]) -> str:
        return " " * len(match.group(0))

    return INLINE_CODE_RE.sub(inline_repl, FENCE_RE.sub(fence_repl, text))


def parse_frontmatter(text: str) -> tuple[dict[str, str], list[Finding], int | None]:
    """返回 (fields, findings, body_start_line)。只支持最小 YAML 子集 key: value。"""
    findings: list[Finding] = []
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        findings.append(
            Finding("FRONTMATTER_MISSING", "", 1, "SKILL.md 必须以 frontmatter 的 --- 开头")
        )
        return {}, findings, None
    closing = None
    for index in range(1, len(lines)):
        if lines[index].strip() == "---":
            closing = index
            break
    if closing is None:
        findings.append(Finding("FRONTMATTER_UNCLOSED", "", 1, "frontmatter 缺少结束的 ---"))
        return {}, findings, None
    fields: dict[str, str] = {}
    for index in range(1, closing):
        raw = lines[index].strip()
        if not raw or raw.startswith("#"):
            continue
        if ":" not in raw:
            findings.append(
                Finding(
                    "FRONTMATTER_LINE",
                    "",
                    index + 1,
                    f"frontmatter 行不是 key: value: {raw[:40]!r}",
                )
            )
            continue
        key, value = raw.split(":", 1)
        key = key.strip().lower()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        if key in fields:
            findings.append(
                Finding("FRONTMATTER_DUPLICATE", "", index + 1, f"frontmatter 字段重复: {key}")
            )
        fields[key] = value
    return fields, findings, closing + 2


def check_links(skill_dir: Path, skill_md: Path, text: str) -> tuple[list[Finding], int]:
    findings: list[Finding] = []
    checked = 0
    stripped = strip_code_keep_lines(text)
    matches: list[tuple[int, str]] = []
    for match in INLINE_LINK_RE.finditer(stripped):
        matches.append((match.start(), match.group(1)))
    for match in REFERENCE_LINK_RE.finditer(stripped):
        matches.append((match.start(), match.group(1)))
    skill_dir_resolved = skill_dir.resolve()
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
            continue
        path_part = target.split("#", 1)[0].split("?", 1)[0]
        if not path_part:
            continue
        line = stripped.count("\n", 0, position) + 1
        checked += 1
        rel_path = skill_md.relative_to(skill_dir.parent.parent.parent).as_posix()
        if path_part.startswith("/"):
            findings.append(
                Finding("SKILL_LINK_ABSOLUTE", rel_path, line, f"只允许 Skill 内相对链接: {target}")
            )
            continue
        resolved = (skill_md.parent / path_part).resolve()
        try:
            resolved.relative_to(skill_dir_resolved)
        except ValueError:
            findings.append(
                Finding("SKILL_LINK_ESCAPE", rel_path, line, f"链接逃出 Skill 目录: {target}")
            )
            continue
        if not resolved.exists():
            findings.append(Finding("SKILL_DEAD_LINK", rel_path, line, f"链接目标不存在: {target}"))
    return findings, checked


def check_at_imports(skill_md: Path, text: str, base: Path) -> list[Finding]:
    findings: list[Finding] = []
    rel = skill_md.relative_to(base).as_posix()
    for line_no, line in enumerate(text.splitlines(), start=1):
        if AT_IMPORT_RE.search(line) or AT_IMPORT_WORD_RE.search(line):
            findings.append(
                Finding(
                    "SKILL_AT_IMPORT",
                    rel,
                    line_no,
                    "不得使用 @ 导入（工程 Skill 必须自包含，不依赖宿主展开语法）",
                )
            )
    return findings


def check_secrets(skill_md: Path, text: str, base: Path) -> list[Finding]:
    findings: list[Finding] = []
    rel = skill_md.relative_to(base).as_posix()
    for line_no, line in enumerate(text.splitlines(), start=1):
        for label, pattern in SECRET_PATTERNS:
            if pattern.search(line):
                # 不打印命中值，避免把密钥二次写进日志/会话。
                findings.append(
                    Finding(
                        "SKILL_SECRET_PATTERN",
                        rel,
                        line_no,
                        f"发现可能的密钥模式: {label}（值已脱敏，未回显）",
                    )
                )
                break
    return findings


def check_skip_directives(skill_md: Path, text: str, base: Path) -> list[Finding]:
    findings: list[Finding] = []
    rel = skill_md.relative_to(base).as_posix()
    for line_no, line in enumerate(text.splitlines(), start=1):
        if not SKIP_DIRECTIVE_RE.search(line):
            continue
        lower = line.lower()
        if any(marker in lower for marker in SKIP_NEGATION_MARKERS):
            continue
        findings.append(
            Finding(
                "SKIP_DIRECTIVE",
                rel,
                line_no,
                "Skill 不得指示使用 -DskipTests / -Dmaven.test.skip 等削弱门禁的参数",
            )
        )
    return findings


def validate_skill(base: Path, skill_dir: Path) -> tuple[list[Finding], int]:
    findings: list[Finding] = []
    links_checked = 0
    rel_dir = skill_dir.relative_to(base).as_posix()
    if skill_dir.is_symlink():
        findings.append(Finding("SKILL_SYMLINK", rel_dir, None, "Skill 目录不得是软链/挂载"))
        return findings, links_checked
    skill_md = skill_dir / "SKILL.md"
    rel_md = skill_md.relative_to(base).as_posix()
    if not skill_md.is_file():
        findings.append(
            Finding(
                "MISSING_SKILL_MD",
                rel_dir,
                None,
                "active root 下的目录必须有 SKILL.md；候选 Skill 应放 .work/skill-candidates/",
            )
        )
        return findings, links_checked
    try:
        raw_bytes = skill_md.read_bytes()
        text = raw_bytes.decode("utf-8")
    except (OSError, UnicodeDecodeError) as exc:
        findings.append(Finding("SKILL_UNREADABLE", rel_md, None, f"无法按 UTF-8 读取: {exc}"))
        return findings, links_checked

    if len(raw_bytes) > MAX_SKILL_BYTES:
        findings.append(
            Finding(
                "SKILL_TOO_LARGE",
                rel_md,
                None,
                f"{len(raw_bytes)} bytes 超过上限 {MAX_SKILL_BYTES}",
            )
        )
    line_count = len(text.splitlines())
    if line_count > MAX_SKILL_LINES:
        findings.append(
            Finding(
                "SKILL_TOO_MANY_LINES",
                rel_md,
                None,
                f"{line_count} 行超过上限 {MAX_SKILL_LINES}",
            )
        )

    fields, frontmatter_findings, _ = parse_frontmatter(text)
    for finding in frontmatter_findings:
        findings.append(Finding(finding.code, rel_md, finding.line, finding.message))

    name = fields.get("name", "").strip()
    description = fields.get("description", "").strip()
    if not name:
        findings.append(Finding("SKILL_NAME_MISSING", rel_md, None, "frontmatter 缺少 name"))
    else:
        if len(name) > MAX_NAME_LENGTH or not NAME_RE.match(name):
            findings.append(
                Finding(
                    "SKILL_NAME_INVALID",
                    rel_md,
                    None,
                    f"name 必须是小写 kebab-case 且 ≤{MAX_NAME_LENGTH} 字符: {name!r}",
                )
            )
        if name != skill_dir.name:
            findings.append(
                Finding(
                    "SKILL_NAME_MISMATCH",
                    rel_md,
                    None,
                    f"frontmatter name={name!r} 与目录名 {skill_dir.name!r} 不一致",
                )
            )
    if not description:
        findings.append(
            Finding("SKILL_DESCRIPTION_MISSING", rel_md, None, "frontmatter 缺少 description")
        )
    status = fields.get("status", "").strip().lower()
    if status and status != "active":
        findings.append(
            Finding(
                "SKILL_NOT_ACTIVE",
                rel_md,
                None,
                f"frontmatter status={status!r}；只有 active Skill 才能放在 .agents/skills/",
            )
        )

    link_findings, links_checked = check_links(skill_dir, skill_md, text)
    findings.extend(link_findings)
    findings.extend(check_at_imports(skill_md, text, base))
    findings.extend(check_secrets(skill_md, text, base))
    findings.extend(check_skip_directives(skill_md, text, base))
    return findings, links_checked


def lint_root(root: Path) -> tuple[list[Finding], dict[str, object]]:
    """返回 (findings, stats)。root 不存在 .agents/skills 时是合法的 0 active。"""
    active_root = root / ".agents" / "skills"
    stats: dict[str, object] = {
        "active_root": active_root,
        "root_exists": active_root.exists(),
        "skills": 0,
        "links": 0,
        "skills_ok": [],
    }
    findings: list[Finding] = []
    if not active_root.exists():
        return findings, stats
    if active_root.is_symlink():
        findings.append(
            Finding("SKILLS_ROOT_SYMLINK", ".agents/skills", None, "active Skill 根目录不得是软链")
        )
        return findings, stats
    if not active_root.is_dir():
        findings.append(
            Finding("SKILLS_ROOT_NOT_DIR", ".agents/skills", None, "active Skill 根必须是目录")
        )
        return findings, stats

    skills_ok: list[str] = []
    for entry in sorted(active_root.iterdir(), key=lambda path: path.name):
        rel = entry.relative_to(root).as_posix()
        if entry.is_symlink():
            findings.append(Finding("SKILL_ENTRY_SYMLINK", rel, None, "不得使用软链"))
            continue
        if not entry.is_dir():
            findings.append(
                Finding(
                    "UNEXPECTED_ROOT_ENTRY",
                    rel,
                    None,
                    "active root 只允许 <name>/SKILL.md 目录；候选/临时文件不得混入",
                )
            )
            continue
        skill_findings, links = validate_skill(root, entry)
        findings.extend(skill_findings)
        stats["links"] = int(stats["links"]) + links
        if not skill_findings:
            skills_ok.append(entry.name)
        stats["skills"] = int(stats["skills"]) + 1

    # 深层 SKILL.md：只允许 .agents/skills/<name>/SKILL.md
    for skill_md in sorted(active_root.rglob("SKILL.md")):
        rel = skill_md.relative_to(root).as_posix()
        if len(skill_md.relative_to(active_root).parts) != 2:
            findings.append(
                Finding(
                    "DEEP_SKILL_MD",
                    rel,
                    None,
                    "SKILL.md 只能位于 .agents/skills/<name>/SKILL.md（不得更深）",
                )
            )
    stats["skills_ok"] = skills_ok
    return findings, stats


# ---------------------------------------------------------------------------
# selftest
# ---------------------------------------------------------------------------


def make_skill(
    root: Path,
    dirname: str,
    *,
    name: str | None = None,
    description: str = "Selftest skill for skills-lint.",
    frontmatter: bool = True,
    status: str | None = None,
    body_extra: str = "",
    reference: bool = False,
) -> Path:
    skill_dir = root / ".agents" / "skills" / dirname
    skill_dir.mkdir(parents=True, exist_ok=True)
    front = ""
    if frontmatter:
        front = f"---\nname: {name if name is not None else dirname}\n"
        if description:
            front += f"description: {description}\n"
        if status:
            front += f"status: {status}\n"
        front += "---\n"
    body = "# Selftest Skill\n\n使用说明。\n"
    if reference:
        body += "见 [note](references/note.md)。\n"
        ref_dir = skill_dir / "references"
        ref_dir.mkdir(parents=True, exist_ok=True)
        (ref_dir / "note.md").write_text("# note\n", encoding="utf-8")
    (skill_dir / "SKILL.md").write_text(front + body + body_extra, encoding="utf-8")
    return skill_dir


def selftest_case(
    name: str, root: Path, expected_rc: int, code: str | None, forbidden_text: str | None = None
) -> bool:
    findings, stats = lint_root(root)
    rc = EXIT_PASS if not findings else EXIT_FINDINGS
    codes = {finding.code for finding in findings}
    rendered = "\n".join(finding.render() for finding in findings)
    ok = rc == expected_rc and (code is None or code in codes)
    if forbidden_text is not None and forbidden_text in rendered:
        ok = False
    if ok:
        print(f"SELFTEST PASS skills-lint.py {name} (rc={rc}, findings={len(findings)})")
        return True
    print(
        f"SELFTEST FAIL skills-lint.py {name}: rc={rc} expected={expected_rc} "
        f"codes={sorted(codes)} expected_code={code} "
        f"secret_leaked={forbidden_text in rendered if forbidden_text else False}"
    )
    if rendered:
        print(rendered)
    return False


def run_selftest() -> int:
    cases: list[bool] = []
    with tempfile.TemporaryDirectory(prefix="mosire-skills-lint-selftest-") as tmp:
        base = Path(tmp)

        root = base / "absent-root"
        root.mkdir()
        cases.append(selftest_case("root-absent-zero-active", root, EXIT_PASS, None))

        root = base / "valid"
        root.mkdir()
        make_skill(root, "good-one", reference=True)
        cases.append(selftest_case("valid-skill-green", root, EXIT_PASS, None))

        root = base / "missing-frontmatter"
        root.mkdir()
        make_skill(root, "no-front", frontmatter=False)
        cases.append(
            selftest_case("missing-frontmatter-red", root, EXIT_FINDINGS, "FRONTMATTER_MISSING")
        )

        root = base / "name-mismatch"
        root.mkdir()
        make_skill(root, "dir-name", name="different-name")
        cases.append(
            selftest_case("name-mismatch-red", root, EXIT_FINDINGS, "SKILL_NAME_MISMATCH")
        )

        root = base / "too-large"
        root.mkdir()
        make_skill(root, "big-one", body_extra="x" * (MAX_SKILL_BYTES + 10))
        cases.append(selftest_case("too-large-red", root, EXIT_FINDINGS, "SKILL_TOO_LARGE"))

        root = base / "too-many-lines"
        root.mkdir()
        make_skill(root, "long-one", body_extra="\n".join(f"line {i}" for i in range(140)) + "\n")
        cases.append(
            selftest_case("too-many-lines-red", root, EXIT_FINDINGS, "SKILL_TOO_MANY_LINES")
        )

        root = base / "dead-link"
        root.mkdir()
        make_skill(root, "dead-link", body_extra="\n见 [missing](references/missing.md)。\n")
        cases.append(selftest_case("dead-link-red", root, EXIT_FINDINGS, "SKILL_DEAD_LINK"))

        secret_value = "AKIAIOSFODNN7EXAMPLE"
        root = base / "secret"
        root.mkdir()
        make_skill(root, "secret-one", body_extra=f"\nAWS key: {secret_value}\n")
        cases.append(
            selftest_case(
                "secret-pattern-red-redacted",
                root,
                EXIT_FINDINGS,
                "SKILL_SECRET_PATTERN",
                forbidden_text=secret_value,
            )
        )

        root = base / "deep-skill"
        root.mkdir()
        deep = root / ".agents" / "skills" / "outer" / "inner"
        deep.mkdir(parents=True)
        (deep / "SKILL.md").write_text(
            "---\nname: inner\ndescription: deep selftest\n---\n# deep\n", encoding="utf-8"
        )
        cases.append(selftest_case("deep-skill-md-red", root, EXIT_FINDINGS, "DEEP_SKILL_MD"))

        root = base / "at-import"
        root.mkdir()
        make_skill(root, "at-import", body_extra="\n@./references/other.md\n")
        cases.append(selftest_case("at-import-red", root, EXIT_FINDINGS, "SKILL_AT_IMPORT"))

        root = base / "skip-directive"
        root.mkdir()
        make_skill(root, "skip-one", body_extra="\n运行 `./mvnw test -DskipTests`。\n")
        cases.append(selftest_case("skip-directive-red", root, EXIT_FINDINGS, "SKIP_DIRECTIVE"))

        # 明确写“不得使用 -DskipTests”的禁止句不能被误报（否则 Skill 无法写红线）。
        root = base / "skip-negation"
        root.mkdir()
        make_skill(root, "no-skip", body_extra="\n不得使用 `-DskipTests` 作为门禁。\n")
        cases.append(selftest_case("skip-negation-allowed", root, EXIT_PASS, None))

        root = base / "non-active"
        root.mkdir()
        make_skill(root, "draft-one", status="drafted")
        cases.append(selftest_case("non-active-status-red", root, EXIT_FINDINGS, "SKILL_NOT_ACTIVE"))

        root = base / "candidate-dir"
        root.mkdir()
        (root / ".agents" / "skills" / "candidate-without-skill-md").mkdir(parents=True)
        cases.append(
            selftest_case("candidate-dir-red", root, EXIT_FINDINGS, "MISSING_SKILL_MD")
        )

    passed = sum(1 for case in cases if case)
    total = len(cases)
    if passed == total:
        print(f"SELFTEST PASSED skills-lint.py ({passed}/{total} cases)")
        return 0
    print(f"SELFTEST FAILED skills-lint.py ({passed}/{total} cases)")
    return 1


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="ProjectMosire engineering Skill contract lint")
    parser.add_argument("--root", type=Path, help="repository root (default: script's parent repo)")
    parser.add_argument("--selftest", action="store_true", help="run temporary-fixture self-tests")
    args = parser.parse_args(argv)

    if args.selftest:
        return run_selftest()
    root = (args.root if args.root is not None else Path(__file__).resolve().parent.parent).resolve()
    if not root.is_dir():
        print(f"skills-lint.py: root does not exist: {root}", file=sys.stderr)
        return EXIT_USAGE
    findings, stats = lint_root(root)
    active_root = stats["active_root"]
    if not stats["root_exists"]:
        print(f"SKILLS LINT PASSED (0 active skills; {active_root} does not exist)")
        return EXIT_PASS
    for name in stats["skills_ok"]:
        print(f"SKILL {name}: OK")
    if findings:
        for finding in findings:
            print(finding.render())
        print(
            f"SKILLS LINT FAILED ({len(findings)} findings; "
            f"active skills={stats['skills']} links={stats['links']})"
        )
        return EXIT_FINDINGS
    print(
        f"SKILLS LINT PASSED (active skills={stats['skills']} "
        f"links={stats['links']} root={active_root})"
    )
    return EXIT_PASS


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
