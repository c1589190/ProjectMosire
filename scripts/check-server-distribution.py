#!/usr/bin/env python3
"""解包并启动服务，核对 HTTP 对话、SQLite 事件和 Bash 插件。"""

import argparse
import json
import re
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.request
from contextlib import closing
from pathlib import Path
from zipfile import BadZipFile, ZipFile


STARTUP = re.compile(r"admin=(http://127\.0\.0\.1:\d+) .* debug=(http://127\.0\.0\.1:\d+)")
LOOPBACK_HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
MESSAGE = "Check the packaged server"
REPLY = "mosire-distribution-ready"


def read_json(url, body=None):
    if body is not None:
        body = json.dumps(body).encode("utf-8")
        url = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    with LOOPBACK_HTTP.open(url, timeout=5) as response:
        return json.load(response)


def await_start(process, log):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise ValueError(f"服务提前退出，退出码 {process.returncode}")
        match = STARTUP.search(log.read_text(encoding="utf-8", errors="replace"))
        if match:
            return match.groups()
        time.sleep(0.2)
    raise ValueError("45 秒内未读到服务监听地址")


def check_dialog(debug):
    run_id = read_json(debug + "/api/chat", {"message": MESSAGE})["runId"]
    if not isinstance(run_id, int) or run_id <= 0:
        raise ValueError("调试对话没有返回有效 runId")
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        result = read_json(f"{debug}/api/chat/{run_id}")
        if result.get("status") != "running":
            if result.get("status") != "finished" or (result.get("result") or {}).get("text") != REPLY:
                raise ValueError("发行包未返回预期对话结果")
            return
        time.sleep(0.2)
    raise ValueError("25 秒内未完成发行包对话")


def check_events(database, version):
    with closing(sqlite3.connect(f"{database.as_uri()}?mode=ro", uri=True)) as connection:
        rows = connection.execute(
            "SELECT type, payload FROM events WHERE type IN ('conversation.turn', 'plugin.lifecycle')"
        )
        events = [(kind, json.loads(payload)) for kind, payload in rows]
    plugin = {"pluginId": "bash", "version": version, "state": "STARTED"}
    if ("plugin.lifecycle", plugin) not in events:
        raise ValueError("SQLite 缺少同版本 Bash 插件 STARTED 事件")
    if not any(
        kind == "conversation.turn" and payload.get("input") == MESSAGE and payload.get("output") == REPLY
        for kind, payload in events
    ):
        raise ValueError("SQLite 缺少对应对话事件")


def check_distribution(archive):
    match = re.fullmatch(r"mosire-server-([0-9]+(?:\.[0-9]+){2}(?:[.-][0-9A-Za-z][0-9A-Za-z.-]*)?)\.zip", archive.name)
    if not match:
        raise ValueError("发行包文件名应为 mosire-server-<version>.zip")
    version = match.group(1)
    expected = {
        f"{archive.stem}/{entry}"
        for entry in ("mosire.jar", f"plugins/bash-plugin-mosire-{version}.jar",
                      "configs/agents/general-assistant.json", "README.md", "LICENSE", "NOTICE")
    }
    with tempfile.TemporaryDirectory(prefix="mosire-server-") as temporary:
        scratch = Path(temporary)
        with ZipFile(archive) as distribution:
            members = [entry.filename for entry in distribution.infolist() if not entry.is_dir()]
            if sorted(members) != sorted(expected):
                raise ValueError("发行包文件清单与服务发行描述符不符")
            distribution.extractall(scratch, sorted(expected))
        service = scratch / archive.stem
        data = scratch / "data"
        log = scratch / "server.log"
        with log.open("wb") as output:
            process = subprocess.Popen(
                ["java", "-Xmx256m", "-jar", "mosire.jar", "run", "--data-dir", str(data),
                 "--port", "0", "--debug-port", "0", "--a2a-address", "127.0.0.1", "--a2a-port", "0",
                 "--agui-address", "127.0.0.1", "--agui-port", "0",
                 "--no-mcp-expose", "--fake-script", f"text:{REPLY}"],
                cwd=service, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT,
            )
            try:
                admin, debug = await_start(process, log)
                health = read_json(admin + "/health")
                if health.get("status") != "ok" or health.get("version") != version:
                    raise ValueError("发行包健康状态或版本不匹配")
                if "bash" not in read_json(admin + "/api/tools").get("tools", []):
                    raise ValueError("发行包运行时工具列表缺少 Bash")
                check_dialog(debug)
                check_events(data / "events.db", version)
            except Exception:
                print(log.read_text(encoding="utf-8", errors="replace")[-4000:], file=sys.stderr)
                raise
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
                        raise ValueError("发行包未能在 15 秒内完成关闭")
    print(f"SERVER DISTRIBUTION PASSED {version} (health, dialog, SQLite, Bash)")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    try:
        check_distribution(args.archive.resolve())
        return 0
    except (OSError, ValueError, sqlite3.Error, KeyError, BadZipFile) as exc:
        print(f"SERVER DISTRIBUTION FAILED {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
