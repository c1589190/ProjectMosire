#!/usr/bin/env python3
"""核对公开库签名、暂存 Maven 制品和发布页。"""

import argparse
import hashlib
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import BadZipFile, ZipFile


ROOT = Path(__file__).resolve().parent.parent
GROUP_PATH = Path("io/mosire")
POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}
COMPATIBLE_LIBRARIES = {
    "agentlib-mosire": "AgentLibMosire",
    "workspace-mosire": "WorkspaceMosire",
    "docs-mosire": "DocsMosire",
}
PUBLIC_LIBRARIES = (*COMPATIBLE_LIBRARIES, "brain-mosire")
PUBLIC_ARTIFACTS = ("mosire-parent", *PUBLIC_LIBRARIES)
BASELINE = "0.2.2"
PAGES_URL = "https://c1589190.github.io/ProjectMosire"
JAPICMP_VERSION = "0.27.0"
VERSION_PATTERN = re.compile(r"[0-9]+(?:\.[0-9]+){2}(?:[.-][0-9A-Za-z][0-9A-Za-z.-]*)?")


def project_version():
    version = ET.parse(ROOT / "pom.xml").findtext("m:version", "", POM_NS).strip()
    if not VERSION_PATTERN.fullmatch(version):
        raise ValueError("根 POM 需要形如 0.2.7 或 0.2.7-SNAPSHOT 的版本")
    return version


def release_version(version):
    if version.upper().endswith("SNAPSHOT") or re.search(r"-\d{8}\.\d{6}-\d+$", version):
        raise ValueError("发布需要固定版本，当前源码版本为 " + version)
    return version


def check_release_tag(version, create=False, require=False):
    tag_ref = f"refs/tags/v{version}"
    peeled_ref = tag_ref + "^{}"
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    query = ["git", "ls-remote", "--exit-code", "--tags", "origin", tag_ref, peeled_ref]
    remote = subprocess.run(query, cwd=ROOT, text=True, stdout=subprocess.PIPE)
    if remote.returncode == 2 and create:
        subprocess.run(["git", "push", "origin", f"{revision}:{tag_ref}"], cwd=ROOT, check=True)
        remote = subprocess.run(query, cwd=ROOT, text=True, stdout=subprocess.PIPE)
    if remote.returncode == 2:
        if require or create:
            raise ValueError(f"远端缺少发布标签 {tag_ref}")
        return
    remote.check_returncode()
    refs = {ref: sha for sha, ref in (line.split() for line in remote.stdout.splitlines())}
    if refs.get(peeled_ref, refs.get(tag_ref)) != revision:
        raise ValueError(f"发布标签 {tag_ref} 与本次 checkout 不一致")


def digest(path, algorithm):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def require_file(path):
    if not path.is_file() or not path.stat().st_size:
        raise ValueError(f"缺少制品 {path}")
    return path


def staged_file(repository, artifact, version, extension, classifier=""):
    directory = repository / GROUP_PATH / artifact / version
    resolved_version = version
    if version.endswith("-SNAPSHOT"):
        metadata = ET.parse(directory / "maven-metadata.xml")
        resolved_version = next(
            (node.findtext("value") for node in metadata.findall("versioning/snapshotVersions/snapshotVersion")
             if node.findtext("extension") == extension and node.findtext("classifier", "") == classifier),
            None,
        )
        prefix = re.escape(version.removesuffix("-SNAPSHOT"))
        if not resolved_version or not re.fullmatch(prefix + r"-\d{8}\.\d{6}-\d+", resolved_version):
            raise ValueError(f"缺少有效快照坐标 {artifact}:{version}:{extension}:{classifier}")
    stem = f"{artifact}-{resolved_version}"
    if classifier:
        stem += f"-{classifier}"
    return require_file(directory / f"{stem}.{extension}")


def check_file_checksums(path):
    for algorithm in ("sha1", "md5"):
        checksum = require_file(path.with_name(f"{path.name}.{algorithm}"))
        if checksum.read_text(encoding="ascii").strip().lower() != digest(path, algorithm):
            raise ValueError(f"校验和不匹配 {checksum}")


def staged_libraries(repository, version):
    paths = {}
    for artifact in PUBLIC_ARTIFACTS:
        pom = staged_file(repository, artifact, version, "pom")
        check_file_checksums(pom)
        if artifact == "mosire-parent":
            continue
        for classifier in ("", "sources", "javadoc"):
            jar = staged_file(repository, artifact, version, "jar", classifier)
            check_file_checksums(jar)
            if not classifier:
                paths[artifact] = jar
            elif classifier == "javadoc":
                with ZipFile(jar) as docs:
                    if "index.html" not in docs.namelist():
                        raise ValueError(f"Javadoc 缺少入口 {jar}")
    return paths


def historical_versions(ref):
    versions = {artifact: set() for artifact in PUBLIC_ARTIFACTS}
    if ref:
        paths = subprocess.check_output(
            ["git", "ls-tree", "-d", "-r", "--name-only", "-z", ref, "--", GROUP_PATH.as_posix()],
            cwd=ROOT, text=True,
        )
        for path in paths.split("\0"):
            parts = Path(path).parts
            if len(parts) == 4 and parts[2] in versions:
                versions[parts[2]].add(parts[3])
    return versions


def check_unused_version(version, history):
    for artifact, previous in history.items():
        if version in previous:
            raise ValueError(f"gh-pages 已存在 {artifact}:{version}，拒绝覆盖已发布制品")


def prepare_pages(repository, version, history):
    check_unused_version(version, history)
    staged_libraries(repository, version)
    template = (ROOT / "pages/index.html").read_text(encoding="utf-8")
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    tokens = {"__MOSIRE_VERSION__": version, "__MOSIRE_REVISION__": revision}
    for token, replacement in tokens.items():
        if token not in template:
            raise ValueError(f"发布页缺少 {token}")
        template = template.replace(token, replacement)
    links = set(re.findall(r'\bhref="([^"]+)"', template))
    for artifact in PUBLIC_ARTIFACTS:
        coordinate = repository / GROUP_PATH / artifact
        metadata = require_file(coordinate / "maven-metadata.xml")
        published = list((coordinate / version).glob("*.pom")) + list((coordinate / version).glob("*.jar"))
        expected_links = {path.relative_to(repository).as_posix() for path in [metadata, *published]}
        if missing := expected_links - links:
            raise ValueError(f"发布页缺少链接 {', '.join(sorted(missing))}")
        tree = ET.parse(metadata)
        if (tree.findtext("groupId"), tree.findtext("artifactId")) != ("io.mosire", artifact):
            raise ValueError(f"元数据坐标不匹配 {metadata}")
        versions = tree.find("versioning/versions")
        if versions is None or version not in {node.text for node in versions.findall("version")}:
            raise ValueError(f"元数据缺少暂存版本 {metadata}")
        versions.clear()
        # 以实际历史目录恢复可选版本，保留 Maven 本次生成的 release 与 lastUpdated。
        for value in sorted(history[artifact] | {version}):
            ET.SubElement(versions, "version").text = value
        ET.indent(tree, space="  ")
        tree.write(metadata, encoding="utf-8", xml_declaration=True)
        for algorithm in ("sha1", "md5"):
            metadata.with_name(f"{metadata.name}.{algorithm}").write_text(
                digest(metadata, algorithm), encoding="ascii"
            )
    (repository / "index.html").write_text(template, encoding="utf-8")
    print(f"PAGES STAGING READY {version} ({len(PUBLIC_LIBRARIES)} libraries)")


def download(url, destination):
    subprocess.run(
        ["curl", "--fail", "--location", "--silent", "--show-error", "--retry", "2",
         "--max-time", "60", "--output", str(destination), url],
        check=True,
    )


def check_api(version, repository):
    with tempfile.TemporaryDirectory(prefix="mosire-abi-") as temporary:
        scratch = Path(temporary)
        japicmp = scratch / "japicmp.jar"
        download(
            "https://repo.maven.apache.org/maven2/com/github/siom79/japicmp/japicmp/"
            f"{JAPICMP_VERSION}/japicmp-{JAPICMP_VERSION}-jar-with-dependencies.jar",
            japicmp,
        )
        old_jars = {}
        new_jars = {}
        for artifact, module in COMPATIBLE_LIBRARIES.items():
            baseline = f"{artifact}-{BASELINE}.jar"
            old_jars[artifact] = scratch / baseline
            download(f"{PAGES_URL}/{GROUP_PATH.as_posix()}/{artifact}/{BASELINE}/{baseline}", old_jars[artifact])
            if repository:
                new_jars[artifact] = staged_file(repository, artifact, version, "jar")
            else:
                new_jars[artifact] = require_file(ROOT / module / f"target/{artifact}-{version}.jar")
        for artifact in COMPATIBLE_LIBRARIES:
            print(f"JAPICMP {artifact} {BASELINE} -> {version}", flush=True)
            subprocess.run(
                ["java", "-jar", str(japicmp), "--old", str(old_jars[artifact]),
                 "--new", str(new_jars[artifact]),
                 "--old-classpath", os.pathsep.join(map(str, old_jars.values())),
                 "--new-classpath", os.pathsep.join(map(str, new_jars.values())),
                 "-a", "public", "--only-modified", "--error-on-binary-incompatibility",
                 "--error-on-source-incompatibility", "--ignore-missing-classes-by-regex", r"^(?!io\.mosire\.).*"],
                check=True,
            )
    print(f"PUBLIC API COMPATIBLE ({len(COMPATIBLE_LIBRARIES)} libraries, baseline {BASELINE})")


def consumer_pom(repository, version, artifact):
    deploy_mode = "snapshots" if version.endswith("-SNAPSHOT") else "releases"
    other_mode = "releases" if deploy_mode == "snapshots" else "snapshots"
    dependency_version = f"<version>{version}</version>" if artifact == "agentlib-mosire" else ""
    bom = "" if dependency_version else f"""
  <dependencyManagement><dependencies><dependency>
    <groupId>io.mosire</groupId><artifactId>mosire-parent</artifactId><version>{version}</version>
    <type>pom</type><scope>import</scope>
  </dependency></dependencies></dependencyManagement>"""
    return f"""<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>mosire.release.probe</groupId><artifactId>consumer</artifactId><version>1</version>
  <properties><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
  <repositories><repository>
    <id>mosire-stage</id><url>{repository.as_uri()}</url>
    <{deploy_mode}><enabled>true</enabled><checksumPolicy>fail</checksumPolicy></{deploy_mode}>
    <{other_mode}><enabled>false</enabled></{other_mode}>
  </repository></repositories>
  {bom}
  <dependencies><dependency>
    <groupId>io.mosire</groupId><artifactId>{artifact}</artifactId>{dependency_version}
  </dependency></dependencies>
  <build><plugins><plugin>
    <groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
    <version>3.16.0</version><configuration><release>21</release><proc>none</proc></configuration>
  </plugin></plugins></build>
</project>
"""


def run_consumer(scratch, repository, version, artifacts, library, fixture):
    project = scratch / fixture
    sources = project / "src/main/java"
    sources.mkdir(parents=True)
    source = ROOT / "scripts/fixtures" / f"{fixture}.java"
    (sources / source.name).write_bytes(source.read_bytes())
    (project / "pom.xml").write_text(consumer_pom(repository, version, library), encoding="utf-8")
    settings = scratch / "settings.xml"
    settings.write_text("<settings/>\n", encoding="utf-8")
    cache = scratch / "m2"
    classpath_file = project / "classpath.txt"
    subprocess.run(
        [str(ROOT / "mvnw"), "-B", "-ntp", "-s", str(settings), f"-Dmaven.repo.local={cache}",
         "compile", "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath",
         f"-Dmdep.outputFile={classpath_file}"],
        cwd=project, check=True,
    )
    classpath = classpath_file.read_text(encoding="utf-8").strip()
    mosire_cache = cache / GROUP_PATH
    actual = {}
    for entry in classpath.split(os.pathsep):
        path = Path(entry)
        if path.is_relative_to(mosire_cache):
            artifact, resolved_version, _ = path.relative_to(mosire_cache).parts
            if resolved_version != version or artifact in actual:
                raise ValueError(f"消费方解析出意外版本或重复制品 {path}")
            actual[artifact] = path
    expected = {library} if library == "agentlib-mosire" else set(PUBLIC_LIBRARIES)
    if set(actual) != expected:
        raise ValueError(f"{library} 依赖闭包 {sorted(actual)}，期望 {sorted(expected)}")
    for artifact, resolved in actual.items():
        if digest(resolved, "sha256") != digest(artifacts[artifact], "sha256"):
            raise ValueError(f"消费方未使用暂存制品 {artifact}")
    subprocess.run(
        ["java", "-cp", os.pathsep.join([str(project / "target/classes"), classpath]), fixture],
        cwd=project, check=True,
    )


def check_sdk(repository, version):
    artifacts = staged_libraries(repository, version)
    with tempfile.TemporaryDirectory(prefix="mosire-sdk-") as temporary:
        scratch = Path(temporary)
        run_consumer(scratch, repository, version, artifacts, "agentlib-mosire", "AgentLibProbe")
        run_consumer(scratch, repository, version, artifacts, "brain-mosire", "BrainProbe")
    print(f"STAGED SDK PASSED {version} (AgentLib-only, Brain with BOM)")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    version_command = commands.add_parser("version", help="读取根 POM 版本")
    version_command.add_argument("--release", action="store_true")
    unused = commands.add_parser("unused-version", help="检查未发布过的固定版本")
    unused.add_argument("--history-ref")
    tag = commands.add_parser("release-tag", help="核对正式发布的远端标签")
    tag_mode = tag.add_mutually_exclusive_group()
    tag_mode.add_argument("--create", action="store_true", help="手动正式发布时绑定缺失的标签")
    tag_mode.add_argument("--require", action="store_true", help="要求对应标签已经存在")
    prepare = commands.add_parser("prepare-pages", help="核对暂存仓库，生成发布页与历史元数据")
    prepare.add_argument("repository", type=Path)
    prepare.add_argument("--history-ref")
    compatible = commands.add_parser("compat", help="比较 0.2.2 和本次构建的公开签名")
    compatible.add_argument("--repository", type=Path)
    sdk = commands.add_parser("sdk", help="从独立 Maven 工程安装并运行暂存 SDK")
    sdk.add_argument("repository", type=Path)
    args = parser.parse_args()
    try:
        version = project_version()
        if args.command == "version":
            print(release_version(version) if args.release else version)
        elif args.command == "unused-version":
            check_unused_version(release_version(version), historical_versions(args.history_ref))
        elif args.command == "release-tag":
            check_release_tag(release_version(version), create=args.create, require=args.require)
        elif args.command == "prepare-pages":
            prepare_pages(args.repository.resolve(), release_version(version), historical_versions(args.history_ref))
        elif args.command == "compat":
            check_api(version, args.repository.resolve() if args.repository else None)
        else:
            check_sdk(args.repository.resolve(), version)
        return 0
    except (OSError, ValueError, ET.ParseError, BadZipFile, subprocess.CalledProcessError) as exc:
        print(f"RELEASE ARTIFACTS FAILED {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
