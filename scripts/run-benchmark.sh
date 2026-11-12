#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${STOCKLINE_ADMIN_USERNAME:-}" || -z "${STOCKLINE_ADMIN_PASSWORD:-}" ]]; then
  echo "Set STOCKLINE_ADMIN_USERNAME and STOCKLINE_ADMIN_PASSWORD for the isolated benchmark admin." >&2
  exit 2
fi

app_port="${SERVER_PORT:-18080}"
export SERVER_PORT="$app_port"
export JWT_SECRET="${JWT_SECRET:-$(openssl rand -base64 32)}"
log_file="${BENCHMARK_LOG:-${TMPDIR:-/tmp}/stockline-benchmark-app.log}"

mvn -q -DskipTests package
java -jar target/stockline-1.0.0.jar --spring.profiles.active=benchmark >"$log_file" 2>&1 &
app_pid=$!
cleanup() { kill "$app_pid" 2>/dev/null || true; wait "$app_pid" 2>/dev/null || true; }
trap cleanup EXIT INT TERM

ready=0
for _ in $(seq 1 90); do
  if curl --fail --silent "http://127.0.0.1:${app_port}/actuator/health/liveness" >/dev/null; then ready=1; break; fi
  if ! kill -0 "$app_pid" 2>/dev/null; then break; fi
  sleep 1
done
if [[ "$ready" != 1 ]]; then
  echo "Isolated benchmark application did not become live. See $log_file" >&2
  exit 1
fi
node scripts/benchmark.mjs "$@"
