#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/stop-ci-emulator.sh"
scratch="$(mktemp -d)"
trap 'rm -f "$scratch/errors"; rmdir "$scratch"' EXIT

sleep 30 &
real_pid=$!
stop_ci_emulator "$real_pid"

signals=() sleeps=0 waits=0 alive=true owned=true mode=graceful wait_result=143
kill() {
  case "$1" in
    -0) [[ "$alive" == true ]] ;;
    -TERM|-KILL)
      signals+=("$1")
      if [[ "$mode" == graceful || "$mode" == forced && "$1" == -KILL ]]; then alive=false; fi
      ;;
    *) echo "Unexpected kill operation" >&2; return 1 ;;
  esac
}
jobs() { if [[ "$owned" == true ]]; then printf '4242\n'; else printf '9999\n'; fi; }
sleep() { sleeps=$((sleeps + 1)); }
wait() { waits=$((waits + 1)); return "$wait_result"; }
reset() {
  signals=() sleeps=0 waits=0 alive=true owned=true mode="$1" wait_result=143
}

reset graceful
stop_ci_emulator 4242
[[ "${signals[*]}" == -TERM && "$waits" == 1 && "$sleeps" == 0 ]]
reset forced
wait_result=137
stop_ci_emulator 4242 2>"$scratch/errors"
[[ "${signals[*]}" == '-TERM -KILL' && "$waits" == 1 && "$sleeps" == 10 && -s "$scratch/errors" ]]
reset unkillable
if stop_ci_emulator 4242 2>"$scratch/errors"; then echo "Unkillable emulator falsely passed"; exit 1; fi
[[ "$waits" == 0 && "$sleeps" == 15 && -s "$scratch/errors" ]]
reset unkillable
status=23
if ! stop_ci_emulator 4242 2>"$scratch/errors" && [[ "$status" == 0 ]]; then status=1; fi
[[ "$status" == 23 ]]
reset unkillable
status=0
if ! stop_ci_emulator 4242 2>"$scratch/errors" && [[ "$status" == 0 ]]; then status=1; fi
[[ "$status" == 1 ]]
reset graceful
owned=false
if stop_ci_emulator 4242 2>"$scratch/errors"; then echo "Foreign PID accepted"; exit 1; fi
[[ "${#signals[@]}" == 0 && "$waits" == 0 ]]
for invalid in '' 0 1 -1 abc; do
  reset graceful
  if stop_ci_emulator "$invalid" 2>"$scratch/errors"; then echo "Invalid PID accepted"; exit 1; fi
  [[ "${#signals[@]}" == 0 && "$waits" == 0 ]]
done
reset graceful
alive=false wait_result=0
stop_ci_emulator 4242
[[ "${#signals[@]}" == 0 && "$waits" == 1 ]]
reset graceful
alive=false wait_result=7
if stop_ci_emulator 4242 2>"$scratch/errors"; then echo "Unexpected exit falsely passed"; exit 1; fi
[[ "$waits" == 1 && -s "$scratch/errors" ]]
echo "PASS emulator cleanup: owned-child integration, TERM/KILL bounds, no unbounded wait, foreign/invalid PID rejection, exit status"
