# CLAUDE.md

This file guides Claude Code (and any other coding agent) when working inside this repository.

## What this repository is

`java-skills` is a collection of **opinionated skills** that teach coding agents to write current, production-ready Java for backend services. Each skill covers **one design decision** a Java developer faces (how to size the connection pool, where a timeout goes, when to use virtual threads) and gives a clear position on it, with a good example, a bad example, and a review checklist.

It is **not** a style guide, not a tutorial, and not a collection of generic best practices. Every skill must say "do it this way, because of this" and show the cost of the alternative.

Primary audience: developers using Claude Code, Cursor, Codex or similar agents to write Java backend services. Secondary audience: Java developers reading the skills directly.

Sister repositories: [go-skills](https://github.com/rafapeixer/go-skills) (same format, Go) and [db-skills](https://github.com/rafapeixer/db-skills) (what the database sees: schema, SQL, migrations, connections). Database rules that do not depend on the language belong to db-skills; this repository covers the Java side (JDBC, HikariCP, transactions in Spring).

## Repository structure

```
java-skills/
├── CLAUDE.md                    # this file
├── README.md / README.pt-BR.md  # what the repo is, install, index of skills (English / Portuguese, kept in sync)
├── CONTRIBUTING.md / .pt-BR.md  # how to propose or change a skill
├── LICENSE                      # MIT
├── .claude-plugin/              # plugin.json and marketplace.json; bump "version" on every release
├── .claude/                     # contributor tooling, not shipped to users (/new-skill, /review-skill)
├── hooks/                       # session-start note shipped with the plugin
│
├── skills/
│   └── <skill>/
│       ├── SKILL.md
│       └── examples/
│           ├── good/            # runnable: Main.java (JDK only) or a Maven project (pom.xml)
│           └── bad/             # the common mistake, compiled but not run, with // MISTAKE: comments
│
├── templates/SKILL.template.md
├── scripts/
│   ├── validate.sh              # format, Java floor, excerpts match examples, README indexes
│   ├── test-examples.sh         # runs every example on the floor JDK and the latest JDK in Docker
│   └── check-freshness.sh       # fails when the floor is not the newest LTS or a skill is unverified for 6 months
├── evals/                       # claude plugin eval cases
└── .github/workflows/           # ci.yml on every push and PR, freshness.yml monthly
```

### Guardrails, not prose

| Mistake | Caught by |
|---|---|
| Missing or misordered section, wrong counts, bad frontmatter, CRLF line endings | `validate.sh` |
| `SKILL.md` excerpt that is not in the tested examples | `validate.sh` (every excerpt line must exist in `examples/good` or `examples/bad`) |
| Example compiled for a release other than the floor | `validate.sh` (`JAVA_FLOOR`) |
| Good example that does not compile, fails its checks or its tests | `test-examples.sh` |
| Bad example that does not compile | `test-examples.sh` |
| Floor no longer the newest LTS | `check-freshness.sh`, monthly |
| Facts not re-verified for more than six months | `check-freshness.sh`, via `metadata.verified` |

When you add a rule a script could enforce, add the check in the same PR.

## Supported versions

- **Floor: JDK 25**, the newest LTS. Every example compiles with `--release 25` (`<maven.compiler.release>25</maven.compiler.release>` or `java --source 25`). CI runs every example on JDK 25 and on the latest GA release (today JDK 27).
- Raise the floor when a new LTS ships (the next is planned for September 2027): change `JAVA_FLOOR` in `scripts/validate.sh`, every example, this file and both READMEs in one commit.
- **Version-gate newer features.** A rule that needs a release newer than the floor says so inline, for example "(JDK 27+)". Never teach a preview feature as the default; name it as preview with the release.
- **Dependencies have floors too.** State the minimum version whenever a rule depends on a library: Spring Boot 4.1, HikariCP 7.1, PostgreSQL JDBC 42.7.14 (security release), JUnit 6, Testcontainers 2, Jackson 3, Maven 3.10 (Maven 4 is not GA).

## The catalog

Released in waves; each skill is added in its own commit.

- **v1, core:** `project-layout`, `exceptions`, `timeouts-and-deadlines`, `virtual-threads`, `http-server`, `postgres-hikari-pool`, `secure-defaults`, `testing`.
- **v1.1, production:** `sql-and-transactions`, `db-migrations`, `logging-structured`, `ci-guardrails`, `container-runtime-tuning`, `overload-protection`, `outbound-http-client`, `java-version-policy`.
- **v2, ahead of the mainstream:** `observability-otel`, `profiling-jfr`, `fast-startup-aot-cache`, `background-jobs-outbox`, `json-jackson3`, `scoped-values`, `null-safety-jspecify`, `mcp-server-java`.

The guiding idea: in current Java, production readiness is mostly about **using what the runtime already gives you** (virtual threads, container-aware ergonomics, the AOT cache, post-quantum TLS on JDK 27) and about **bounding everything** (timeouts, pool sizes, queue sizes, concurrency, request bodies).

## Skill format

YAML frontmatter, then Markdown. The `description` is always in the agent's context and decides when the skill loads: start it with the task in the user's words, then the key rule.

```markdown
---
name: postgres-hikari-pool
description: Use when configuring a database connection pool in Java (HikariCP, Spring Boot datasource) ... Key rule: ...
metadata:
  verified: 2026-10-18
---
```

Required sections, in this order: **The decision**, **The rule** (4–8 numbered one-sentence rules), **Why**, **Good example**, **Bad example**, **Checklist** (5–10 `- [ ]` items), **When not to apply**. 100–400 lines. Never pad to reach the minimum; split a skill that passes the maximum.

## Rules for writing skills

- **One decision per skill.** If it needs "and", it is two skills.
- **Take a position.** "It depends" only when followed by on what, and what to do in each case.
- **Every claim needs a reason and, when possible, a number.** Say what breaks without the rule.
- **Examples run.** A good example is either a `Main.java` (plus other `.java` files) run with the JDK source launcher, which must exit 0 and checks its own outcome by throwing when it is wrong, or a minimal Maven project whose `mvn verify` passes. A bad example only has to compile. Keep examples small and about one point: under about 300 lines of Java including tests.
- **Prefer the JDK.** Use a library only when the JDK has no reasonable answer (HikariCP for pooling, pgJDBC, Spring Boot for the web layer, Testcontainers for real databases in tests). Say why.
- **Bad examples are real mistakes**, the ones agents and junior developers actually make. Comment each with `// MISTAKE: <what is wrong and what it costs>`.
- **Official sources only:** openjdk.org, docs.oracle.com, dev.java, docs.spring.io, and each library's own documentation. No blog posts.
- **No filler.** Start at the decision.

### Language and tone

- Skills, templates and code comments are in **English**.
- `README.md` / `README.pt-BR.md` and `CONTRIBUTING.md` / `CONTRIBUTING.pt-BR.md` change together: same sections, same order, same commands, same index.
- Direct, plain, second person. No marketing words.

### Changing a rule

Open an issue first, explain what breaks, and update the rule, examples and checklist in the same PR. Never weaken a rule to fit a bad example.

## How agents should use this repo as a consumer

1. Load the skill whose `description` matches the task.
2. Apply **The rule** while writing.
3. Run the **Checklist** against the result before presenting it.
4. If the task falls under **When not to apply**, say so instead of silently ignoring the rule.

## Non-goals

- Not a Java tutorial.
- Not a linter or formatter configuration.
- Not a framework comparison. Spring Boot is the web baseline because it is what most Java backends run; plain JDK code wherever the JDK is enough.
