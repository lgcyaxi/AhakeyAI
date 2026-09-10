#!/bin/zsh

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
AGENT_PATH="${AHAKEY_AGENT_PATH:-$SCRIPT_DIR/ahakeyconfig-mac/dist/AhaKey Studio.app/Contents/MacOS/ahakeyconfig-agent}"

if [ ! -x "$AGENT_PATH" ]; then
  echo "AhaKey agent is missing or not executable: $AGENT_PATH" >&2
  echo "Build the macOS app first or set AHAKEY_AGENT_PATH." >&2
  exit 1
fi

# 修复 hooks.json 格式为正确的对象格式
mkdir -p "$HOME/.cursor"
python3 - "$AGENT_PATH" > "$HOME/.cursor/hooks.json" <<'PY'
import json
import sys

agent = sys.argv[1]
events = (
    "preToolUse",
    "beforeShellExecution",
    "beforeMCPExecution",
    "postToolUse",
    "sessionStart",
    "sessionEnd",
    "stop",
)
hooks = {
    event: {
        "command": agent,
        "args": ["hook", event],
        "enableOnStartup": True,
    }
    for event in events
}
print(json.dumps({"version": 1, "hooks": hooks}))
PY

echo "已修复 hooks.json 格式"
cat ~/.cursor/hooks.json

# 创建诊断目录
mkdir -p "$HOME/Library/Application Support/AhaKeyConfig/diagnostics"

echo "\n测试 hook 执行："
echo '{"command":"python3 --version"}' | "$AGENT_PATH" hook beforeShellExecution

# 检查日志文件
echo "\n检查诊断日志："
ls -la "$HOME/Library/Application Support/AhaKeyConfig/diagnostics/"
