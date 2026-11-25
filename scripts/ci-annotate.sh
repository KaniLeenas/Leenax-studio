#!/usr/bin/env bash
# Turns the important lines of a failed CI log into one GitHub annotation, so the reason for a
# failure shows on the run's summary page (annotations on a public repository are readable
# without signing in; full logs aren't).
#   bash scripts/ci-annotate.sh <log file> <title> [extended regex of lines to keep]
set -uo pipefail
log="$1"; title="$2"; pattern="${3:-ERROR|Error|error:|FAILED|FAIL |Exception|Caused by|expected|cannot find symbol|No such file|denied}"
[ -f "$log" ] || { echo "::error title=$title::no log was written"; exit 0; }
lines="$(grep -E "$pattern" "$log" | grep -vE '^\[ERROR\] *$|^\[ERROR\] -> \[Help|Re-run Maven|full stack trace|For more information|^\[ERROR\] *To see|^\[ERROR\] *\[Help' | head -60)"
[ -n "$lines" ] || lines="$(tail -40 "$log")"
# annotation messages are one line: encode %, CR and LF as GitHub asks
msg="$(printf '%s' "$lines" | cut -c1-400 | sed -e 's/%/%25/g' | sed -e ':a;N;$!ba;s/\r/%0D/g;s/\n/%0A/g')"
echo "::error title=$title::$msg"
