#!/usr/bin/env python3
"""Validate public agent documentation and its privacy boundary."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
GUIDE_VERSION = "Guide version 1.1"
PRIVATE_TOKEN = "." + "agents/"

REQUIRED_PATHS = (
    "AGENTS.md",
    "CLAUDE.md",
    "Package.swift",
    "ahakeyconfig-mac/Sources",
    "ahakeyconfig-mac/Sources/Agent",
    "ahakeyconfig-mac/scripts/package_app.sh",
    "ahakeyconfig-mac/scripts/pack-release.sh",
    "ahakeyconfig-win-java/pom.xml",
    "ahakeyconfig-win-java/build-exe.ps1",
    "ahakeyconfig-win-java/build-installer.ps1",
    "ahakeyconfig-win-java/src/main/java/com/example/ahakey/App.java",
    "ahakeyconfig-win-java/src/main/java/com/example/ahakey/platform/windows",
    "ahakeyconfig-win-python/main.py",
    "ahakeyconfig-ubuntu-java/pom.xml",
    "BLE_tcp_bridge/BLE_tcp_driver.csproj",
    "sdks/typescript/package.json",
    "vibebar",
    "assets",
    ".github/workflows/ci.yml",
    ".github/workflows/release.yml",
    "docs/installation.md",
    "docs/releases.md",
    "docs/supported-platforms.md",
    "docs/agents/workflow.md",
    "docs/agents/code-index.md",
    "docs/agents/conventions.md",
    "docs/agents/clarification.md",
    "scripts/check_agent_docs.py",
)

PUBLIC_GUIDES = (
    "AGENTS.md",
    "CLAUDE.md",
    "docs/agents/workflow.md",
    "docs/agents/code-index.md",
    "docs/agents/conventions.md",
    "docs/agents/clarification.md",
)

ALLOWED_PRIVATE_REFERENCES = {"AGENTS.md", ".gitignore"}
REQUIRED_IGNORES = {
    "/" + PRIVATE_TOKEN,
    "/.agent-task.yaml",
    "/.agent-claim-lock/",
    "/.worktree/",
    "/.tests/cache/agent-knowledge/",
}

USER_PATH_PATTERNS = (
    re.compile(r"(?i)[a-z]:[\\/]" + r"Users[\\/][^\\/\s`<>]+"),
    re.compile("/" + r"Users/[^/\s`<>]+/"),
    re.compile("/" + r"home/[^/\s`<>]+/"),
    re.compile("/" + r"volume\d+/"),
)


def git_paths(*args: str) -> list[str]:
    result = subprocess.run(
        ["git", *args, "-z"],
        cwd=ROOT,
        check=True,
        stdout=subprocess.PIPE,
    )
    return sorted({part.decode("utf-8") for part in result.stdout.split(b"\0") if part})


def candidate_paths() -> list[str]:
    paths = git_paths("ls-files", "--cached", "--others", "--exclude-standard")
    return [path for path in paths if not path.startswith(PRIVATE_TOKEN)]


def current_branch() -> str:
    result = subprocess.run(
        ["git", "branch", "--show-current"],
        cwd=ROOT,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    )
    return result.stdout.strip()


def read_text(path: Path) -> str | None:
    data = path.read_bytes()
    if b"\0" in data:
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return None


def main() -> int:
    errors: list[str] = []

    branch = current_branch()
    tracked_private = [
        path for path in git_paths("ls-files") if path.startswith(PRIVATE_TOKEN)
    ]
    if tracked_private and branch != "dev":
        errors.append(
            "private source files are tracked outside dev: "
            f"branch={branch or '<detached>'}, first={tracked_private[0]}"
        )

    for relative in REQUIRED_PATHS:
        if not (ROOT / relative).exists():
            errors.append(f"missing required code-index path: {relative}")

    claude = ROOT / "CLAUDE.md"
    if claude.exists() and claude.read_text(encoding="utf-8") != "@AGENTS.md\n":
        errors.append("CLAUDE.md must be exactly the one-line @AGENTS.md import")

    ignore_lines = {
        line.strip()
        for line in (ROOT / ".gitignore").read_text(encoding="utf-8").splitlines()
    }
    for required in sorted(REQUIRED_IGNORES - ignore_lines):
        errors.append(f"missing required ignore rule: {required}")

    for relative in PUBLIC_GUIDES:
        path = ROOT / relative
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        if relative not in {"AGENTS.md", "CLAUDE.md"} and GUIDE_VERSION not in text:
            errors.append(f"missing guide version in {relative}")
        for pattern in USER_PATH_PATTERNS:
            if pattern.search(text):
                errors.append(f"personal or machine-specific path in public guide: {relative}")
                break

    for relative in candidate_paths():
        path = ROOT / relative
        if not path.is_file() or path.stat().st_size > 2_000_000:
            continue
        text = read_text(path)
        if text is None:
            continue

        if PRIVATE_TOKEN in text and relative not in ALLOWED_PRIVATE_REFERENCES:
            errors.append(f"private source path referenced by public file: {relative}")

        for pattern in USER_PATH_PATTERNS:
            if pattern.search(text):
                errors.append(f"personal or machine-specific path in tracked text: {relative}")
                break

    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1

    print("agent documentation boundary: OK")
    print(f"required paths: {len(REQUIRED_PATHS)}")
    print(f"branch: {branch or '<detached>'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
