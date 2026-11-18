#!/usr/bin/env python3
"""核对六模块的直接 POM 依赖和生产源码 import。"""

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}
PROJECT_GROUPS = {
    "io.mosire", "${groupId}", "${project.groupId}", "${pom.groupId}", "${project.parent.groupId}",
}
IMPORT = re.compile(r"^import\s+(?:static\s+)?io\.mosire\.([\w$]+)(?=[.;])", re.MULTILINE)
MODULES = {
    "AgentLibMosire": ("agentlib", {}),
    "WorkspaceMosire": ("workspace", {"agentlib": "compile"}),
    "DocsMosire": ("docs", {"agentlib": "compile", "workspace": "compile"}),
    "BrainMosire": ("brain", {"agentlib": "compile", "workspace": "compile", "docs": "compile"}),
    "MainMosire": (
        "main",
        {
            "agentlib": "compile", "workspace": "compile", "docs": "compile", "brain": "compile",
            "bash-plugin": "provided",
        },
    ),
    "BashPluginMosire": ("bash-plugin", {"agentlib": "compile", "brain": "test"}),
}


def dependencies(project):
    for path in ("m:dependencies/m:dependency", "m:profiles/m:profile/m:dependencies/m:dependency"):
        for node in project.findall(path, POM_NS):
            if node.findtext("m:groupId", "", POM_NS).strip() in PROJECT_GROUPS:
                yield (
                    node.findtext("m:artifactId", "", POM_NS).strip(),
                    node.findtext("m:scope", "compile", POM_NS).strip(),
                )


def check(root):
    problems = []
    project = ET.parse(root / "pom.xml").getroot()
    reactor = [node.text for node in project.findall("m:modules/m:module", POM_NS)]
    if sorted(reactor) != sorted(MODULES):
        problems.append(f"pom.xml: reactor 模块与边界清单不一致 {reactor}")
    for artifact, scope in dependencies(project):
        problems.append(f"pom.xml: 父 POM 会向全部模块引入 {artifact}:{scope}")

    source_count = 0
    import_count = 0
    for module, (namespace, allowed) in MODULES.items():
        pom_path = root / module / "pom.xml"
        pom = ET.parse(pom_path).getroot()
        allowed_artifacts = {f"{dependency}-mosire": scope for dependency, scope in allowed.items()}
        for artifact, scope in dependencies(pom):
            if allowed_artifacts.get(artifact) != scope:
                problems.append(f"{module}/pom.xml: 越界直接依赖 {artifact}:{scope}")

        allowed_imports = {"bash" if namespace == "bash-plugin" else namespace}
        allowed_imports.update(key for key, scope in allowed.items() if scope == "compile")
        sources = sorted((root / module / "src/main/java").rglob("*.java"))
        if not sources:
            problems.append(f"{module}: 缺少生产 Java 源文件")
        source_count += len(sources)
        for source in sources:
            java = source.read_text(encoding="utf-8")
            for match in IMPORT.finditer(java):
                import_count += 1
                if match.group(1) not in allowed_imports:
                    line = java.count("\n", 0, match.start()) + 1
                    problems.append(f"{source.relative_to(root)}:{line}: 越界 import {match.group(1)}")
    if not import_count:
        problems.append("全仓没有读取到 io.mosire import")
    return problems, source_count, import_count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    try:
        problems, sources, imports = check(args.root.resolve())
    except (OSError, ET.ParseError, UnicodeError) as exc:
        print(f"MODULE BOUNDARIES ERROR {exc}", file=sys.stderr)
        return 1
    if problems:
        for problem in problems:
            print(f"MODULE BOUNDARIES FAILED {problem}", file=sys.stderr)
        return 1
    print(f"MODULE BOUNDARIES PASSED ({len(MODULES)} modules, {sources} sources, {imports} imports)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
