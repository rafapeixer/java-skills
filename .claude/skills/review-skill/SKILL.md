---
name: review-skill
description: Review one java-skills skill against the repository rules and current Java facts. Use before opening a PR for a new or changed skill.
argument-hint: <skill-name>
disable-model-invocation: true
---

# Review skill

Review `skills/$ARGUMENTS/` against `CLAUDE.md`. Report findings as a list, most severe first; do not edit unless asked.

Check:

1. **Format.** `bash scripts/validate.sh` and `ONLY=skills/$ARGUMENTS bash scripts/test-examples.sh` pass.
2. **One decision.** The skill covers a single decision; if it covers two, say where to split.
3. **Position and reasons.** Every rule is imperative, one sentence, and the "Why" section says what breaks without it. Flag any "it depends" that does not say on what.
4. **Facts are current.** Every API, version and default named in the skill exists and behaves as described on the floor JDK (25). Rules that need a newer JDK carry an inline version gate; preview features are named as preview. Verify doubtful claims against openjdk.org, docs.oracle.com and docs.spring.io.
5. **Examples.** The good example is the smallest code that shows the rules. The bad example contains mistakes people actually make, each marked `// MISTAKE:`. The inline excerpts match the files.
6. **Checklist.** 5 to 10 yes/no items that a reviewer can answer by reading code.
7. **Exceptions.** "When not to apply" lists real cases and what to do instead.
8. **Defaults.** The skill does not tell readers to switch off a good default (container support, TLS 1.3, graceful shutdown) without a stated reason.
