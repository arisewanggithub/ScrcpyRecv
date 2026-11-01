#!/usr/bin/env bash
set -euo pipefail
file="${1:-}"
expected="${2:-}"
fail() { echo "Instrumentation result rejected: $*" >&2; exit 1; }
[[ "$expected" =~ ^[1-9][0-9]*$ && -f "$file" ]] || fail "provide a transcript file and positive expected count"
text="$(tr -d '\r' < "$file")"
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|^INSTRUMENTATION_STATUS_CODE: -[234]$|^INSTRUMENTATION_CODE: 0$' <<< "$text"; then
  fail "failure, crash, ignored test or assumption skip was reported"
fi
# grep -c returns 1 on no matches. Only that controlled empty result is accepted
# here; the explicit count comparison below still rejects an incomplete run.
successes="$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0$' <<< "$text" || true)"
[[ "$successes" == "$expected" ]] || fail "expected $expected per-test success statuses (0), observed $successes"
summaries="$(grep -cE "^OK \\($expected tests?\\)$" <<< "$text" || true)"
[[ "$summaries" == 1 ]] || fail "expected one exact OK ($expected tests) summary, observed $summaries"
finals="$(grep -c '^INSTRUMENTATION_CODE:' <<< "$text" || true)"
final_successes="$(grep -c '^INSTRUMENTATION_CODE: -1$' <<< "$text" || true)"
[[ "$finals" == 1 && "$final_successes" == 1 ]] || fail "expected one final INSTRUMENTATION_CODE: -1"
awk -v expected="$expected" '
function reject(message) {
  print "Instrumentation result rejected: " message > "/dev/stderr"
  bad = 1
}
/^INSTRUMENTATION_STATUS: current=/ { current = substr($0, index($0, "=") + 1) }
/^INSTRUMENTATION_STATUS: numtests=/ { total = substr($0, index($0, "=") + 1) }
/^INSTRUMENTATION_STATUS: class=/ { class = substr($0, index($0, "=") + 1) }
/^INSTRUMENTATION_STATUS: test=/ { test = substr($0, index($0, "=") + 1) }
/^INSTRUMENTATION_STATUS_CODE: / {
  code = substr($0, index($0, ":") + 2)
  key = class SUBSEP test
  # substr metadata is string-valued; numeric limits matter for cohorts >= 10.
  if (current !~ /^[1-9][0-9]*$/ || total !~ /^[1-9][0-9]*$/ || (current + 0) > (expected + 0) || (total + 0) != (expected + 0) || class == "" || test == "") {
    reject("missing or inconsistent per-test metadata")
  } else if (code == "1") {
    if (started[current] || names[key]) reject("duplicate test start/index")
    started[current] = key
    names[key] = 1
  } else if (code == "0") {
    if (!started[current] || started[current] != key || finished[current]) reject("duplicate/unmatched test success/index")
    finished[current] = 1
  } else {
    reject("unexpected per-test status " code)
  }
  current = total = class = test = ""
}
END {
  for (i = 1; i <= expected; i++) if (!started[i] || !finished[i]) reject("missing completed test index " i)
  if (bad) exit 1
}' <<< "$text"
echo "PASS instrumentation: $expected distinct successful tests; final code -1"
