#!/bin/zsh

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
AGENT_PATH="${AHAKEY_AGENT_PATH:-$SCRIPT_DIR/ahakeyconfig-mac/dist/AhaKey Studio.app/Contents/MacOS/ahakeyconfig-agent}"

if [ ! -x "$AGENT_PATH" ]; then
  echo "AhaKey agent is missing or not executable: $AGENT_PATH" >&2
  echo "Build the macOS app first or set AHAKEY_AGENT_PATH." >&2
  exit 1
fi

# 修复 hooks.json 配置
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

echo "已修复 hooks.json 配置"
cat ~/.cursor/hooks.json
