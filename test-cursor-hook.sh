#!/bin/zsh

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
AGENT_PATH="${AHAKEY_AGENT_PATH:-$SCRIPT_DIR/ahakeyconfig-mac/dist/AhaKey Studio.app/Contents/MacOS/ahakeyconfig-agent}"
REQUEST_FILE="$(mktemp "${TMPDIR:-/tmp}/ahakey-cursor-request.XXXXXX")" || exit 1
trap 'rm -f "$REQUEST_FILE"' EXIT

if [ ! -x "$AGENT_PATH" ]; then
  echo "AhaKey agent is missing or not executable: $AGENT_PATH" >&2
  echo "Build the macOS app first or set AHAKEY_AGENT_PATH." >&2
  exit 1
fi

# 模拟 Cursor 实际调用 hook 的方式
echo "模拟 Cursor 调用 beforeShellExecution hook:"

# 模拟 Cursor 实际发送的 JSON 格式
python3 - "$SCRIPT_DIR" > "$REQUEST_FILE" <<'PY'
import json
import sys

print(json.dumps({"command": "python3 --version", "cwd": sys.argv[1], "env": {}}))
PY

# 测试 hook 响应
"$AGENT_PATH" hook beforeShellExecution < "$REQUEST_FILE"

echo "\n测试完成"
