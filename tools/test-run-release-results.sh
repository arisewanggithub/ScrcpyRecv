#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
checker="$root/tools/check-instrumentation-result.sh"
scratch="$root/publication-output/result-parser-fixtures-$$"
mkdir -p "$scratch"
trap 'rm -rf "$scratch"' EXIT
cases=0
emit_success() {
  local expected="$1" index status
  for ((index=1; index<=expected; index++)); do
    for status in 1 0; do
      printf 'INSTRUMENTATION_STATUS: class=fixture.NativeCheck\nINSTRUMENTATION_STATUS: test=case%s\nINSTRUMENTATION_STATUS: current=%s\nINSTRUMENTATION_STATUS: numtests=%s\nINSTRUMENTATION_STATUS_CODE: %s\n' "$index" "$index" "$expected" "$status"
    done
  done
  printf 'OK (%s tests)\nINSTRUMENTATION_CODE: -1\n' "$expected"
}
reject() {
  local name="$1" file="$2" expected="$3"
  if bash "$checker" "$file" "$expected" > "$scratch/diagnostic.txt" 2>&1; then
    echo "FAIL parser fixture unexpectedly accepted: $name" >&2
    exit 1
  fi
  [[ -s "$scratch/diagnostic.txt" ]] || { echo "FAIL parser gave no diagnostic: $name" >&2; exit 1; }
  cases=$((cases+1))
}
for expected in 8 6; do
  emit_success "$expected" > "$scratch/valid.txt"
  bash "$checker" "$scratch/valid.txt" "$expected" > /dev/null
  cases=$((cases+1))
  sed 's/$/\r/' "$scratch/valid.txt" > "$scratch/crlf.txt"
  bash "$checker" "$scratch/crlf.txt" "$expected" > /dev/null
  cases=$((cases+1))
  for status in -2 -3 -4; do
    sed "0,/INSTRUMENTATION_STATUS_CODE: 0/s/INSTRUMENTATION_STATUS_CODE: 0/INSTRUMENTATION_STATUS_CODE: $status/" "$scratch/valid.txt" > "$scratch/bad.txt"
    reject "failure/ignore/assumption $status" "$scratch/bad.txt" "$expected"
  done
  printf 'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: -2\n' > "$scratch/bad.txt"
  reject crash "$scratch/bad.txt" "$expected"
  reject wrong-count "$scratch/valid.txt" "$((expected+1))"
  printf 'OK (%s tests)\nINSTRUMENTATION_CODE: -1\n' "$expected" > "$scratch/bad.txt"
  reject fake-ok-without-tests "$scratch/bad.txt" "$expected"
  sed '0,/INSTRUMENTATION_STATUS_CODE: 0/{/INSTRUMENTATION_STATUS_CODE: 0/d;}' "$scratch/valid.txt" > "$scratch/bad.txt"
  reject missing-success "$scratch/bad.txt" "$expected"
  sed 's/INSTRUMENTATION_STATUS: current=2$/INSTRUMENTATION_STATUS: current=1/' "$scratch/valid.txt" > "$scratch/bad.txt"
  reject balanced-duplicate-and-missing-index "$scratch/bad.txt" "$expected"
  cp "$scratch/valid.txt" "$scratch/bad.txt"
  printf 'INSTRUMENTATION_CODE: -1\n' >> "$scratch/bad.txt"
  reject duplicated-final "$scratch/bad.txt" "$expected"
  sed 's/INSTRUMENTATION_CODE: -1$/INSTRUMENTATION_CODE: 0/' "$scratch/valid.txt" > "$scratch/bad.txt"
  reject final-abort "$scratch/bad.txt" "$expected"
done
echo "PASS instrumentation result parser: $cases fixtures (8/6 counts, CRLF, skips/failures/crash/incomplete/duplicate rejection)"
