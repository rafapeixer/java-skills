#!/usr/bin/env bash
# Checks that every skill follows the format described in CLAUDE.md:
# frontmatter, required sections in order, length, rule and checklist counts,
# example folders, and presence in both README indexes.
set -euo pipefail

cd "$(dirname "$0")/.."

SECTIONS=("The decision" "The rule" "Why" "Good example" "Bad example" "Checklist" "When not to apply")
# The newest Java LTS. Raise it here, in CLAUDE.md, both READMEs and every
# example in the same commit.
JAVA_FLOOR=25
MIN_LINES=100
MAX_LINES=400

errors=0
fail() {
  echo "FAIL $1: $2"
  errors=$((errors + 1))
}

# section_body FILE TITLE prints the lines between "## TITLE" and the next "## ".
section_body() {
  awk -v title="## $2" '
    $0 == title { inside = 1; next }
    /^## / && inside { exit }
    inside { print }
  ' "$1"
}

shopt -s nullglob
skills=(skills/*/)

if [ ${#skills[@]} -eq 0 ]; then
  echo "no skills yet, nothing to validate"
fi

for dir in "${skills[@]}"; do
  skill=$(basename "$dir")
  file="$dir/SKILL.md"

  if [ ! -f "$file" ]; then
    fail "$skill" "missing SKILL.md"
    continue
  fi

  # Frontmatter: first line is ---, then name and description, then ---.
  if [ "$(head -n 1 "$file")" != "---" ]; then
    fail "$skill" "SKILL.md must start with YAML frontmatter (---)"
  fi
  frontmatter=$(awk 'NR == 1 { next } /^---$/ { exit } { print }' "$file")
  name=$(printf '%s\n' "$frontmatter" | sed -n 's/^name: *//p')
  description=$(printf '%s\n' "$frontmatter" | sed -n 's/^description: *//p')

  if [ "$name" != "$skill" ]; then
    fail "$skill" "frontmatter name '$name' does not match folder name"
  fi
  if [[ "$description" != "Use when "* ]]; then
    fail "$skill" "description must start with 'Use when '"
  fi
  if [ ${#description} -gt 1024 ]; then
    fail "$skill" "description is longer than 1024 characters"
  fi

  # Required sections, exactly these, in this order.
  actual=$(grep '^## ' "$file" | sed 's/^## //')
  expected=$(printf '%s\n' "${SECTIONS[@]}")
  if [ "$actual" != "$expected" ]; then
    fail "$skill" "sections must be, in order: $(printf '%s, ' "${SECTIONS[@]}" | sed 's/, $//')"
  fi

  lines=$(wc -l < "$file" | tr -d ' ')
  if [ "$lines" -lt "$MIN_LINES" ] || [ "$lines" -gt "$MAX_LINES" ]; then
    fail "$skill" "SKILL.md has $lines lines, expected $MIN_LINES to $MAX_LINES"
  fi

  rules=$(section_body "$file" "The rule" | grep -cE '^[0-9]+\. ' || true)
  if [ "$rules" -lt 4 ] || [ "$rules" -gt 8 ]; then
    fail "$skill" "The rule has $rules numbered rules, expected 4 to 8"
  fi

  items=$(section_body "$file" "Checklist" | grep -c '^- \[ \] ' || true)
  if [ "$items" -lt 5 ] || [ "$items" -gt 10 ]; then
    fail "$skill" "Checklist has $items items, expected 5 to 10"
  fi

  exceptions=$(section_body "$file" "When not to apply" | grep -c '^- ' || true)
  if [ "$exceptions" -lt 1 ]; then
    fail "$skill" "When not to apply has no cases"
  fi

  verified=$(printf '%s\n' "$frontmatter" | sed -n 's/^  verified: *//p')
  if ! [[ "$verified" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
    fail "$skill" "frontmatter needs metadata.verified: YYYY-MM-DD (the date its facts were last checked)"
  fi

  # Claude Code fails to parse frontmatter with CRLF and loads the skill with no description.
  if [ "$(tr -d '' <"$file" | wc -c)" -ne "$(wc -c <"$file")" ]; then
    fail "$skill" "SKILL.md has CRLF line endings; use LF"
  fi

  for kind in good bad; do
    ex="$dir/examples/$kind"
    if [ -f "$ex/pom.xml" ]; then
      release=$(sed -n 's:.*<maven.compiler.release>\([0-9]*\)</maven.compiler.release>.*:\1:p' "$ex/pom.xml" | head -n 1)
      if [ "$release" != "$JAVA_FLOOR" ]; then
        fail "$skill" "examples/$kind/pom.xml must set <maven.compiler.release>$JAVA_FLOOR</maven.compiler.release> (found '$release')"
      fi
    elif [ ! -f "$ex/Main.java" ]; then
      fail "$skill" "examples/$kind needs a Main.java (JDK only) or a pom.xml"
      continue
    fi

    # Every line of an inline excerpt must exist in the example files, so the
    # SKILL.md can never show code that the tested examples do not contain.
    [ "$kind" = good ] && section="Good example" || section="Bad example"
    corpus=$(find "$ex" -type f ! -path '*/target/*' -exec cat {} + | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
    while IFS= read -r line; do
      if ! grep -Fxq -- "$line" <<<"$corpus"; then
        fail "$skill" "$section excerpt line not found in examples/$kind: $line"
      fi
    done < <(section_body "$file" "$section" | awk '
      /^```(java|xml|yaml|properties|dockerfile|sql|bash)$/ { inside = 1; next }
      /^```/ { inside = 0; next }
      inside { gsub(/^[ 	]+|[ 	]+$/, ""); if ($0 != "" && $0 != "...") print }
    ')
  done

  for readme in README.md README.pt-BR.md; do
    if ! grep -q "(skills/$skill/SKILL.md)" "$readme"; then
      fail "$skill" "not listed in $readme"
    fi
  done
done

# The two READMEs must keep the same structure.
en=$(grep -c '^## ' README.md || true)
pt=$(grep -c '^## ' README.pt-BR.md || true)
if [ "$en" != "$pt" ]; then
  fail "README" "README.md has $en sections, README.pt-BR.md has $pt"
fi

if [ "$errors" -gt 0 ]; then
  echo "$errors problem(s) found"
  exit 1
fi
echo "ok"
