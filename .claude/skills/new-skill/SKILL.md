---
name: new-skill
description: Scaffold a new java-skills skill from the template, with good and bad examples, then validate it. Use when adding a skill to this repository.
argument-hint: <skill-name>
disable-model-invocation: true
---

# New skill

Create the skill `$ARGUMENTS` in this repository.

1. Stop if `skills/$ARGUMENTS/` already exists, or if the name is not lowercase kebab-case.
2. Read `CLAUDE.md` and `templates/SKILL.template.md`. Check that the skill is in the catalog in `CLAUDE.md`; if not, say so and stop.
3. Copy the template to `skills/$ARGUMENTS/SKILL.md` and set `name: $ARGUMENTS`.
4. Create `skills/$ARGUMENTS/examples/good/` and `skills/$ARGUMENTS/examples/bad/`, each either a `Main.java` that uses only the JDK, or a minimal Maven project with `<maven.compiler.release>25</maven.compiler.release>`.
5. Write the skill following every rule in `CLAUDE.md`: one decision, a position, a reason for every rule, real mistakes in the bad example with `// MISTAKE:` comments, and exceptions in "When not to apply". Check every fact against openjdk.org, docs.oracle.com, docs.spring.io or the library's own docs.
6. Add the skill to the index in both `README.md` and `README.pt-BR.md`.
7. Run `bash scripts/validate.sh` and `ONLY=skills/$ARGUMENTS bash scripts/test-examples.sh` and fix everything they report.
