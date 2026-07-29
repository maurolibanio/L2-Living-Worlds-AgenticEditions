#!/bin/bash
# kill_game.sh - Kill GameServer by PID file + orphan cleanup
# Uses symlink path for safety

PID_FILE="/tmp/l2-game.pid"

# Phase 1: Kill by PID file
if [ -f "$PID_FILE" ]; then
  OLD_PID=$(cat "$PID_FILE" 2>/dev/null)
  if [ -n "$OLD_PID" ] && [ -d "/proc/$OLD_PID" ]; then
    kill "$OLD_PID" 2>/dev/null
    for i in 1 2 3 4 5; do
      if ! kill -0 "$OLD_PID" 2>/dev/null; then break; fi
      sleep 1
    done
    if kill -0 "$OLD_PID" 2>/dev/null; then
      kill -9 "$OLD_PID" 2>/dev/null
    fi
  fi
fi

# Phase 2: Orphan cleanup - kill any remaining GameServer processes
# Safe: we are a script file, not bash -c inline
ORPHANS=$(pgrep -f "GameServer.jar" 2>/dev/null || true)
if [ -n "$ORPHANS" ]; then
  echo "$ORPHANS" | while read pid; do
    kill -9 "$pid" 2>/dev/null || true
  done
fi

rm -f "$PID_FILE"
echo "GameServer processes killed"
