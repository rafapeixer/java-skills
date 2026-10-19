#!/usr/bin/env bash
# Fails when the repository has started teaching outdated Java:
#   - the Java floor is not the newest LTS release;
#   - a skill's facts were last verified more than MAX_AGE_DAYS ago.
# Needs network access; CI runs it on a schedule.
set -euo pipefail

cd "$(dirname "$0")/.."

MAX_AGE_DAYS=183
floor=$(sed -n 's/^JAVA_FLOOR=//p' scripts/validate.sh)

errors=0
fail() {
  echo "FAIL $1"
  errors=$((errors + 1))
}

# Adoptium publishes the current LTS and feature releases of OpenJDK.
info=$(curl -fsSL https://api.adoptium.net/v3/info/available_releases)
lts=$(printf '%s' "$info" | grep -o '"most_recent_lts" *: *[0-9]*' | grep -o '[0-9]*$')
latest=$(printf '%s' "$info" | grep -o '"most_recent_feature_release" *: *[0-9]*' | grep -o '[0-9]*$')
echo "newest LTS: $lts, newest release: $latest, floor: $floor"
if [ "$floor" != "$lts" ]; then
  fail "Java floor $floor is not the newest LTS $lts: raise it (see java-version-policy)"
fi
tested=$(sed -n 's/^JDK_VERSIONS=${JDK_VERSIONS-"\(.*\)"}$/\1/p' scripts/test-examples.sh)
if ! [[ " $tested " == *" $latest "* ]]; then
  fail "examples are tested on JDK $tested, not on the newest release $latest: add it to JDK_VERSIONS"
fi

shopt -s nullglob
now=$(date -u +%s)
for file in skills/*/SKILL.md; do
  verified=$(sed -n 's/^  verified: *//p' "$file" | head -n 1)
  age=$(( (now - $(date -u -d "$verified" +%s)) / 86400 ))
  if [ "$age" -gt "$MAX_AGE_DAYS" ]; then
    fail "$file: facts last verified $verified ($age days ago); re-check them and update metadata.verified"
  fi
done

if [ "$errors" -gt 0 ]; then
  echo "$errors problem(s) found"
  exit 1
fi
echo "ok"
