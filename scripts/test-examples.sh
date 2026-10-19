#!/usr/bin/env bash
# Runs every example on each JDK in JDK_VERSIONS, inside the official Maven + Temurin image.
#   good, Main.java: compiled for the floor and run with the source launcher; must exit 0.
#   good, pom.xml:   mvn verify (tests included) must pass.
#   bad:             must compile; the mistakes are deliberate, so nothing runs.
#
#   bash scripts/test-examples.sh                    # every example, JDK 25 and 27
#   ONLY=skills/virtual-threads bash scripts/test-examples.sh
#   JDK_VERSIONS=25 bash scripts/test-examples.sh
#   M2_VOLUME=my-cache bash scripts/test-examples.sh   # separate Maven cache for parallel runs
set -euo pipefail
# Git Bash on Windows rewrites arguments that look like paths (/work, /root/.m2).
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."

JDK_VERSIONS=${JDK_VERSIONS-"25 27"}
FLOOR=$(sed -n 's/^JAVA_FLOOR=//p' scripts/validate.sh)
ONLY=${ONLY:-}

errors=0
fail() {
  echo "FAIL $1"
  errors=$((errors + 1))
}

shopt -s nullglob
dirs=()
for d in skills/*/examples/good/ skills/*/examples/bad/; do
  [ -z "$ONLY" ] || [[ "$d" == "$ONLY"/* ]] || continue
  dirs+=("$d")
done
if [ ${#dirs[@]} -eq 0 ]; then
  echo "no examples yet, nothing to test"
  exit 0
fi

# Docker needs a host path it understands: D:/x on Windows, /x elsewhere.
host_path() { cygpath -m "$1" 2>/dev/null || printf '%s' "$1"; }

for v in $JDK_VERSIONS; do
  image=maven:3.10-eclipse-temurin-$v
  for dir in "${dirs[@]}"; do
    kind=$(basename "$dir")
    if [ -f "$dir/pom.xml" ]; then
      if [ "$kind" = good ]; then
        cmd="mvn -B -q -ntp verify"
      else
        cmd="mvn -B -q -ntp -DskipTests test-compile"
      fi
    else
      if [ "$kind" = good ]; then
        cmd="java -ea --source $FLOOR Main.java"
      else
        cmd="javac --release $FLOOR -d /tmp/out \$(find . -name '*.java')"
      fi
    fi
    # Testcontainers in an example reaches the host's Docker through the socket. target/ is
    # removed inside the container, which owns it.
    if out=$(docker run --rm \
      -v "$(host_path "$PWD/$dir")":/work -w /work \
      -v "${M2_VOLUME:-javaskills-m2}":/root/.m2 \
      -v /var/run/docker.sock:/var/run/docker.sock \
      --add-host=host.docker.internal:host-gateway \
      -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
      "$image" bash -c "$cmd; rc=\$?; rm -rf target; exit \$rc" 2>&1); then
      echo "ok   jdk $v  $dir"
    else
      fail "jdk $v  $dir"
      printf '%s\n' "$out" | tail -25
    fi
  done
done

if [ "$errors" -gt 0 ]; then
  echo "$errors problem(s) found"
  exit 1
fi
echo "ok"
