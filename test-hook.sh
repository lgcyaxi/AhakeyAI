#!/bin/zsh

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
AGENT_PATH="${AHAKEY_AGENT_PATH:-$SCRIPT_DIR/ahakeyconfig-mac/dist/AhaKey Studio.app/Contents/MacOS/ahakeyconfig-agent}"

if [ ! -x "$AGENT_PATH" ]; then
  echo "AhaKey agent is missing or not executable: $AGENT_PATH" >&2
  echo "Build the macOS app first or set AHAKEY_AGENT_PATH." >&2
  exit 1
fi

# 测试 hook 执行
echo "测试 preToolUse hook:"
echo '{"tool_name":"Read","name":"read"}' | "$AGENT_PATH" hook preToolUse

echo "\n测试 beforeShellExecution hook:"
echo '{"command":"python3 --version"}' | "$AGENT_PATH" hook beforeShellExecution

echo "\n测试 beforeMCPExecution hook:"
echo '{"command":"test command"}' | "$AGENT_PATH" hook beforeMCPExecution
