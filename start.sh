#!/usr/bin/env bash
# Check the JDK, download dependencies, test, build, and start Nada in the foreground.
set -euo pipefail

source=${BASH_SOURCE[0]}
if [[ "${source}" != */* ]]; then
  root=$(pwd)
else
  root=$(CDPATH= cd -- "${source%/*}" && pwd)
fi
cd "$root"

log=
server_pid=

cleanup() {
  if [[ -n "${server_pid}" ]] && kill -0 "${server_pid}" 2>/dev/null; then
    kill -INT -- "-${server_pid}" 2>/dev/null || kill -INT "${server_pid}" 2>/dev/null || true
    wait "${server_pid}" 2>/dev/null || true
  fi
  if [[ -n "${log}" ]]; then
    rm -f "${log}"
  fi
}

trap cleanup EXIT
trap 'exit 130' INT TERM

if [[ $# -gt 1 ]]; then
  echo "Usage: ./start.sh [port]" >&2
  echo "Example: ./start.sh 9090" >&2
  exit 1
fi

port=${1:-8080}
if [[ ! "${port}" =~ ^[0-9]+$ ]]; then
  echo "Port must be an integer from 1 to 65535." >&2
  echo "Example: ./start.sh 9090" >&2
  exit 1
fi
port=$((10#${port}))
if (( port < 1 || port > 65535 )); then
  echo "Port must be an integer from 1 to 65535." >&2
  echo "Example: ./start.sh 9090" >&2
  exit 1
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  if [[ ! -x "${JAVA_HOME}/bin/java" || ! -x "${JAVA_HOME}/bin/javac" ]]; then
    echo "JAVA_HOME=${JAVA_HOME} is not a JDK." >&2
    echo "It needs both bin/java and bin/javac." >&2
    echo "Install JDK 17 or newer, point JAVA_HOME at that directory, and run ./start.sh again." >&2
    exit 1
  fi
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi

if ! command -v java >/dev/null 2>&1; then
  printf '%s\n' \
    "java was not found on PATH." \
    "" \
    "Install JDK 17 or newer. A JRE is not enough, because the build needs javac." \
    "  Ubuntu/Debian: sudo apt install openjdk-21-jdk-headless" \
    "" \
    "Open a new terminal and run:" \
    "  java -version" \
    "  javac -version" \
    "  ./start.sh" >&2
  exit 1
fi

if ! command -v javac >/dev/null 2>&1; then
  printf '%s\n' \
    "javac was not found on PATH." \
    "" \
    "java is available, but this looks like a JRE. Nada needs a JDK 17 or newer so it can compile and test." \
    "  Ubuntu/Debian: sudo apt install openjdk-21-jdk-headless" \
    "" \
    "If a JDK is already installed, add it to PATH or set JAVA_HOME, then run:" \
    "  ./start.sh" >&2
  exit 1
fi

major_version() {
  local line raw major
  line=$("$1" -version 2>&1 | head -n 1)
  if [[ "${line}" == *\"* ]]; then
    raw=${line#*\"}
    raw=${raw%%\"*}
  else
    raw=${line##* }
  fi
  if [[ -z "${raw}" ]]; then
    echo "Could not read a version from: $1 -version" >&2
    echo "${line}" >&2
    exit 1
  fi
  if [[ "${raw}" == 1.* ]]; then
    major=${raw#1.}
    major=${major%%.*}
  else
    major=${raw%%.*}
  fi
  if [[ ! "${major}" =~ ^[0-9]+$ ]]; then
    echo "Could not parse the Java version '${raw}' from $1." >&2
    exit 1
  fi
  printf '%s\n' "${major}"
}

java_major=$(major_version java)
javac_major=$(major_version javac)
if (( java_major < 17 || javac_major < 17 )); then
  printf '%s\n' \
    "Java is too old. Nada needs JDK 17 or newer." \
    "java reports major version ${java_major}; javac reports ${javac_major}." \
    "" \
    "Install a current JDK and run ./start.sh again." \
    "  Ubuntu/Debian: sudo apt install openjdk-21-jdk-headless" >&2
  exit 1
fi

if [[ ! -f ./mvnw ]]; then
  echo "Maven Wrapper ./mvnw is missing. Run ./start.sh from a full clone of this repository." >&2
  exit 1
fi
if [[ ! -x ./mvnw ]]; then
  echo "./mvnw is not executable. From the repository root, run: chmod +x mvnw" >&2
  echo "Then run ./start.sh again. This script does not change file modes for you." >&2
  exit 1
fi

port_in_use() {
  local candidate=$1
  if command -v ss >/dev/null 2>&1; then
    if ss -ltn "sport = :${candidate}" 2>/dev/null | awk 'NR > 1 { found = 1 } END { exit (found ? 0 : 1) }'; then
      return 0
    fi
    return 1
  fi
  if command -v lsof >/dev/null 2>&1; then
    if lsof -nP -iTCP:"${candidate}" -sTCP:LISTEN >/dev/null 2>&1; then
      return 0
    fi
    return 1
  fi
  if (echo >/dev/tcp/127.0.0.1/"${candidate}") >/dev/null 2>&1; then
    return 0
  fi
  return 1
}

refuse_busy_port() {
  echo "Port ${port} is already in use." >&2
  echo "Another process is listening there. This script will not stop it." >&2
  echo "Start Nada on a free port, for example:" >&2
  if (( port == 8080 )); then
    echo "  ./start.sh 9090" >&2
  else
    echo "  ./start.sh 8080" >&2
  fi
  exit 1
}

echo "==> Java $(java -version 2>&1 | head -n 1)"
echo "==> $(javac -version 2>&1)"

if port_in_use "${port}"; then
  refuse_busy_port
fi

echo "==> Resolving dependencies"
./mvnw -B dependency:resolve

echo "==> Running tests"
./mvnw -B test

echo "==> Building the application"
# The test run above already failed the script if anything failed.
# Packaging skips a second test run so startup is not delayed.
./mvnw -B -DskipTests package

if [[ ! -f target/nada.jar ]]; then
  echo "The build did not produce target/nada.jar." >&2
  exit 1
fi

if port_in_use "${port}"; then
  refuse_busy_port
fi

echo "==> Starting Nada on port ${port}"

log=$(mktemp)
set -m
java -jar target/nada.jar "${port}" > >(tee "${log}") 2>&1 &
server_pid=$!
set +m

for _ in $(seq 1 50); do
  if ! kill -0 "${server_pid}" 2>/dev/null; then
    if grep -q "Address already in use" "${log}"; then
      wait "${server_pid}" 2>/dev/null || true
      server_pid=
      refuse_busy_port
    fi
    echo "The server stopped during startup." >&2
    wait "${server_pid}" || exit $?
  fi
  if grep -q "UI:  http://localhost:${port}/$" "${log}" \
      && grep -q "API: http://localhost:${port}$" "${log}"; then
    echo "Press Ctrl+C to stop."
    set +e
    wait "${server_pid}"
    status=$?
    set -e
    server_pid=
    exit "${status}"
  fi
  sleep 0.1
done

echo "The server did not listen on port ${port}." >&2
exit 1
