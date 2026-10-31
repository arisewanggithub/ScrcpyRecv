#!/usr/bin/env bash

stop_ci_emulator() {
  local pid="${1:-}" result=0
  [[ "$pid" =~ ^[1-9][0-9]*$ && "$pid" != 1 ]] || {
    echo "::error::Invalid owned emulator PID" >&2
    return 1
  }
  if kill -0 "$pid" 2>/dev/null; then
    case " $(jobs -p | tr '\n' ' ') " in
      *" $pid "*) ;;
      *) echo "::error::Refusing to stop a process outside this shell's jobs" >&2; return 1 ;;
    esac
    kill -TERM "$pid" 2>/dev/null || echo "::warning::Emulator TERM raced with process exit" >&2
    for attempt in {1..10}; do
      if ! kill -0 "$pid" 2>/dev/null; then break; fi
      sleep 1
    done
    if kill -0 "$pid" 2>/dev/null; then
      echo "::warning::Emulator did not exit after TERM; killing the owned PID" >&2
      kill -KILL "$pid" 2>/dev/null || echo "::warning::Emulator KILL raced with process exit" >&2
      for attempt in {1..5}; do
        if ! kill -0 "$pid" 2>/dev/null; then break; fi
        sleep 1
      done
    fi
  fi
  if kill -0 "$pid" 2>/dev/null; then
    echo "::error::Owned emulator still running after bounded shutdown; refusing an unbounded wait" >&2
    return 1
  fi
  wait "$pid" || result=$?
  case "$result" in
    0|137|143) return 0 ;;
    *) echo "::error::Unexpected owned emulator exit status: $result" >&2; return 1 ;;
  esac
}
