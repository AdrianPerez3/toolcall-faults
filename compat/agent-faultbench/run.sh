#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
if [ "${1:-}" != "--inside" ]; then
    vendor=.local/vendor/agent-cutoff-bench
    pin=6a575c59fae1bf593554d78b88b22986e72064fa
    if [ ! -d "$vendor/.git" ]; then
        git clone https://github.com/simin-yuan/agent-cutoff-bench "$vendor"
        git -C "$vendor" checkout "$pin"
    fi
    [ "$(git -C "$vendor" rev-parse HEAD)" = "$pin" ]
    git -C "$vendor" diff --exit-code "$pin" -- server.py LICENSE NOTICE
    ./tcf --container 21 compat
    mkdir -p compat/agent-faultbench/results
    cp .local/compat/latest-runs.jsonl compat/agent-faultbench/results/runs.jsonl
    cp .local/compat/latest-rows.md compat/agent-faultbench/rows.md
    echo 'runs: 48'
    exit
fi
[ -f /.dockerenv ] || { echo 'Compatibility execution requires the offline container' >&2; exit 1; }
mkdir -p .local/compat
run_dir=$(mktemp -d "$PWD/.local/compat/run-XXXXXXXX")
BENCH_RESULT="$run_dir/runs.jsonl"
export BENCH_RESULT
server_pid=
cleanup() { if [ -n "$server_pid" ]; then kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true; server_pid=; fi; }
trap cleanup EXIT HUP INT TERM
for target in spring-ai@2.0.2 langchain4j@1.23.0; do
  for surface in loop-stream loop-call; do
  for mode in complete length drop reset; do
    for run in 1 2 3; do
      BENCH_RUN_ID="$target-$surface-$mode-$run"
      BENCH_LOG="$run_dir/$BENCH_RUN_ID.requests.jsonl"
      export BENCH_RUN_ID BENCH_LOG
      python3 -I /vendor/agent-cutoff-bench/server.py 18765 >"$run_dir/$BENCH_RUN_ID.server.log" 2>&1 &
      server_pid=$!
      python3 -I - <<'READY'
import socket,time
for attempt in range(100):
    try:
        with socket.create_connection(('127.0.0.1',18765),timeout=.1): pass
        break
    except OSError: time.sleep(.05)
else: raise SystemExit('Neighbour server did not start')
READY
      java -cp "runner/target/classes:server/target/classes:kit/target/classes:$(cat runner/target/cp.txt)" toolcallfaults.runner.compat.FaultbenchHarness "$target" "$surface" "$mode" "$run" "http://127.0.0.1:18765/$mode"
      cleanup
    done
  done
  done
done
python3 -I compat/agent-faultbench/summarize.py "$BENCH_RESULT" "$run_dir/rows.md"
cp "$BENCH_RESULT" .local/compat/latest-runs.jsonl
cp "$run_dir/rows.md" .local/compat/latest-rows.md
