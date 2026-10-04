#!/usr/bin/env bash
# Benchmarks one server mode with redis-benchmark (macOS). Usage: scripts/bench.sh <threads|virtual|eventloop|redis> [port]
set -euo pipefail
mode=${1:?usage: scripts/bench.sh <threads|virtual|eventloop|redis> [port]}
port=${2:-6390}
cd "$(dirname "$0")/.."

# macOS has ~16k client ports and each closed connection holds one in TIME_WAIT for 30s. Back-to-back
# runs with thousands of clients use them all up, and redis-benchmark then hangs trying to connect.
drain() { while [ "$(netstat -an -p tcp | grep -c TIME_WAIT)" -gt 500 ]; do sleep 2; done; }

if [ "$mode" = redis ]; then
  redis-server --port "$port" --save '' --appendonly no >/dev/null &
else
  ./gradlew -q classes
  "${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp build/classes/java/main io.github.jackfurton.suitandtie.Main "$port" "$mode" >/dev/null 2>&1 &
fi
pid=$!
# SIGTERM has twice failed to stop a threads-mode server after the 8000-client run, see #15.
trap 'kill $pid 2>/dev/null; sleep 2; kill -9 $pid 2>/dev/null || true' EXIT
sleep 1

bench() { redis-benchmark -p "$port" --threads 4 -q "$@" 2>&1 | tr '\r' '\n' | grep -E "per second|ERR" | sort -u | sed 's/^ */  /'; }
usage() { echo "  server: $(ps -o pcpu= -p $pid | tr -d ' ')% CPU, $(ps -M -p $pid | tail -n +2 | wc -l | tr -d ' ') threads, $(( $(ps -o rss= -p $pid) / 1024 ))MB"; }

redis-benchmark -p "$port" -c 50 -n 200000 -t get -q >/dev/null 2>&1  # JIT warmup
for clients in 50 3000 8000; do
  drain
  echo "[$mode] $clients clients"
  bench -c "$clients" -n 1000000 -t get &
  sleep 4
  if ps -p $pid >/dev/null; then usage; else echo "  server died"; fi
  wait $!
done
