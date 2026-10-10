#!/usr/bin/env bash
# Starts an isolated server, checks the assignment calls, then stops only that process.
set -euo pipefail

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"

if [[ ! -f target/nada.jar ]]; then
  echo "target/nada.jar is missing. Run: ./mvnw -B -DskipTests package" >&2
  exit 1
fi

port=${PORT:-18080}
if command -v ss >/dev/null 2>&1 && ss -ltn | grep -q ":${port} "; then
  echo "Port ${port} is in use. Run: PORT=<free-port> ./scripts/smoke.sh" >&2
  exit 1
fi

log=$(mktemp)
body=$(mktemp)
java -jar target/nada.jar "$port" >"$log" 2>&1 &
pid=$!

cleanup() {
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
  rm -f "$log" "$body"
}
trap cleanup EXIT

ready=0
for _ in $(seq 1 50); do
  if grep -q "Nada server listening" "$log"; then
    ready=1
    break
  fi
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "Server exited before it was ready:" >&2
    cat "$log" >&2
    exit 1
  fi
  sleep 0.1
done
if [[ "$ready" -ne 1 ]]; then
  echo "Server did not print a listening line:" >&2
  cat "$log" >&2
  exit 1
fi

base="http://127.0.0.1:${port}"

expect_code() {
  local expected=$1
  shift
  local actual
  actual=$(curl -sS -o "$body" -w "%{http_code}" "$@")
  if [[ "$actual" != "$expected" ]]; then
    echo "Expected HTTP ${expected}, got ${actual}: $*" >&2
    cat "$body" >&2
    exit 1
  fi
}

expect_code 201 -X POST "$base/people" -H 'Content-Type: application/json' -d '{"name":"Asha"}'
grep -q '"id":1' "$body"
grep -q '"name":"Asha"' "$body"

expect_code 201 -X POST "$base/people" -H 'Content-Type: application/json' -d '{"name":"B"}'
expect_code 201 -X POST "$base/people" -H 'Content-Type: application/json' -d '{"name":"C"}'
expect_code 201 -X POST "$base/people" -H 'Content-Type: application/json' -d '{"name":"D"}'
expect_code 201 -X POST "$base/people" -H 'Content-Type: application/json' -d '{"name":"E"}'

expect_code 400 -X POST "$base/people" -H 'Content-Type: application/json' -d '{"name":"Asha"}{"name":"Other"}'
expect_code 201 -X POST "$base/people" -H 'Content-Type: application/json' --data-binary $'{"name":"आशा"}\n'
grep -q 'आशा' "$body"

expect_code 204 -X POST "$base/knows" -H 'Content-Type: application/json' -d '{"a":1,"b":2}'
expect_code 204 -X POST "$base/knows" -H 'Content-Type: application/json' -d '{"a":2,"b":3}'
expect_code 204 -X POST "$base/knows" -H 'Content-Type: application/json' -d '{"a":3,"b":4}'
expect_code 204 -X POST "$base/knows" -H 'Content-Type: application/json' -d '{"a":4,"b":5}'
expect_code 400 -X POST "$base/knows" -H 'Content-Type: application/json' -d '{"a":1,"b":1}'

expect_code 200 "$base/people/1/contacts"
grep -q '"name":"B"' "$body"

expect_code 200 "$base/path?from=1&to=4"
grep -q '"hops":3' "$body"
expect_code 200 "$base/path?from=1&to=5"
grep -q '"connected":false' "$body"
expect_code 200 "$base/path?from=1&to=1"
grep -q '"hops":0' "$body"
expect_code 404 "$base/people/9/contacts"
grep -q 'no person 9' "$body"
expect_code 400 "$base/path?from=abc&to=1"

echo "Smoke test passed on ${base}"
