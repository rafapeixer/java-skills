---
name: ci-guardrails
description: Use when writing or reviewing a CI pipeline (GitHub Actions workflow), a pom.xml build section, the Maven or Gradle wrapper, a pre-commit or coding-agent hook, or a dependency vulnerability scan for a Java project. Key rule: every check must be able to fail the build; run the committed ./mvnw (Maven 3.10, distributionSha256Sum) with -B --strict-checksums verify; maven-enforcer-plugin with requireMavenVersion, requireJavaVersion [25,), requirePluginVersions and dependencyConvergence; javac -Xlint:all with failOnWarning; OWASP dependency-check with failBuildOnCVSS set (the default 11 never fails); keep reproducible builds on (Maven 3.10 default, outputTimestamp) and check plugins with artifact:check-buildplan; pin Actions to commit SHAs with read-only permissions; never -DskipTests, testFailureIgnore, continue-on-error or || true.
metadata:
  verified: 2026-10-18
---

# CI guardrails

## The decision

Every Java repository needs a pipeline that decides what may merge and what may ship. When code comes from coding agents as well as people, the pipeline is the only reviewer that reads every line, and agents get a red build green the quickest way they know: `-DskipTests`, `testFailureIgnore`, `|| true`, a plugin without a version, a suppression. The decision is which checks gate a merge, how each one is made to actually fail, and how Maven, the plugins and the Actions are pinned. The common failure is not a missing tool but a tool that runs and can never fail: OWASP dependency-check with its default threshold, a test step with `|| true`, a warning that scrolls by. This skill fixes the minimum Maven pipeline (Gradle equivalents are in "When not to apply"). Which JDKs the matrix runs and the `--release` floor belong to `java-version-policy`.

## The rule

1. Commit the Maven Wrapper pinned to Maven 3.10 with `distributionSha256Sum`, and run only `./mvnw -B -ntp --strict-checksums verify` in CI, never the runner's `mvn`.
2. Bind `maven-enforcer-plugin` with `requireMavenVersion`, `requireJavaVersion [25,)`, `requirePluginVersions`, `banDuplicatePomDependencyVersions` and `dependencyConvergence`, and pin every plugin the lifecycle uses.
3. Compile with `-Xlint:all` and `failOnWarning` so every compiler warning fails the build, and fix the warning instead of suppressing the whole category.
4. Run the tests in the same `verify` that builds the artifact, and never ship from a run with `-DskipTests`, `-Dmaven.test.skip`, `testFailureIgnore`, `continue-on-error: true` or `|| true`.
5. Gate on a dependency vulnerability scan that can fail: OWASP `dependency-check-maven` with `failBuildOnCVSS` set to 7 (its default, 11, never fails), an NVD API key from a secret, and a suppression file where every entry names one CVE and a reason.
6. Keep reproducible builds on (the default since Maven 3.10), set `project.build.outputTimestamp`, and fail on plugins with known reproducibility issues with `artifact:check-buildplan`.
7. Pin every GitHub Action to a full commit SHA with the version in a comment, and set `permissions: contents: read` at the top of the workflow.

## Why

**Rule 1.** Without the wrapper, CI builds with whatever Maven the runner image ships this month, and a developer's laptop with another. The Maven Wrapper (maven.apache.org/tools/wrapper, version 3.3.4) pins the version in `.mvn/wrapper/maven-wrapper.properties`; `distributionSha256Sum` makes `mvnw` refuse a download whose SHA-256 does not match, so a compromised mirror or proxy cannot hand you a different Maven. Get the hash by downloading the zip and running `sha256sum`, and check it against the `.sha512` published next to it on Maven Central. Trap: the default `only-script` wrapper downloads the `.tar.gz` instead of the `.zip` when `unzip` is missing (slim build images, the official `maven` images), and then the zip's checksum fails; install `unzip` in the image or use the tarball's hash there. `-B` (batch mode) stops Maven from waiting on a prompt, `-ntp` drops thousands of download-progress lines, and `--strict-checksums` (`-C`) makes the build "fail ... if checksums don't match" for downloaded artifacts instead of warning. Maven 3.10.0 is the current release; Maven 4 is still a release candidate and not for production builds.

**Rule 2.** Every rule here is a build failure, not advice. `requireJavaVersion [25,)` stops a build on a JDK below the floor with a clear message instead of a cryptic "release version 25 not supported". `requireMavenVersion [3.10.0,)` stops an old Maven that ignores the wrapper. `requirePluginVersions` "enforces that all plugins have a specified version": an unpinned plugin resolves from the super POM of whichever Maven runs, so the same commit builds with different compiler or surefire versions on two machines. It checks the lifecycle up to `deploy` and `site`, which is why the good POM pins clean, resources, jar, install, deploy and site too. `dependencyConvergence` fails when two paths bring different versions of the same library, the root cause of `NoSuchMethodError` at run time; resolve it with `<dependencyManagement>`, not exclusions everywhere.

**Rule 3.** Raw types, unchecked casts, deprecated-for-removal APIs, `this` escaping a constructor and fall-through in `switch` compile with at most a one-line note. The javac reference says `-Xlint` "enables all recommended warnings" and `-Werror` "terminates compilation when warnings occur"; the compiler plugin's `failOnWarning` passes `-Werror`. In the bad example, `new Long(s)` is deprecated for removal: today a warning, on the JDK that removes it a compile error during an urgent upgrade. When one warning is a deliberate choice, suppress that key on that declaration (`@SuppressWarnings("unchecked")`) with a comment, never `-Xlint:none` or `@SuppressWarnings("all")`.

**Rule 4.** A test step that cannot fail is worse than none because it looks like coverage. `testFailureIgnore` and `|| true` turn failures into log lines, `continue-on-error: true` turns them into a yellow badge, and `mvn install -DskipTests` followed by a separate test job means the jar you published was never tested. One `verify` per JDK in the matrix compiles, tests and packages the same bytes. Run the same command in the coding agent's post-edit hook or a pre-commit hook so the agent sees the failure before it opens the pull request.

**Rule 5.** The dependency-check documentation lists `failBuildOnCVSS` with a default of `11`; CVSS scores stop at 10, so out of the box the plugin writes a report and always passes. Set it (7 is High and above) and run it as its own job, because the first run downloads the NVD database; the delay between NVD calls is 3.5 s with an API key and 8 s without, which is why the key comes from a secret (`nvdApiKey`, or `nvdApiServerId` with `settings.xml`; never pass it with `-X`, which prints it). A false positive goes into `suppressionFiles` naming one CVE for one package with a reason and a review date, never a pattern that suppresses a whole library.

**Rule 6.** The reproducible builds guide says that "starting with Maven 3.10.0 ... Reproducible Builds mode is active by default", so the timestamps inside jars no longer depend on when the build ran. Setting `project.build.outputTimestamp` in the POM keeps that value under your control (the release plugin 3.0.0-M1+ updates it on each release) and makes the intent explicit for anyone building with an older Maven. A single non-numeric character, such as `x`, turns it off, so never set it to a placeholder. `artifact:check-buildplan` (maven-artifact-plugin 3.7.0) "fails the build if the execution plan contains non-reproducible plugins"; bound to `validate`, it catches an old plugin before the build runs. To prove it end to end, build twice and run `mvn verify artifact:compare`.

**Rule 7.** GitHub's security hardening guide says to "pin references to actions to a commit SHA" so a bad actor cannot add a backdoor behind an existing tag, and to grant `GITHUB_TOKEN` "the minimum required permissions". `@main` runs whatever was pushed to that branch, and `@v6` moves when the maintainer retags. `permissions: contents: read` at the top makes every job read-only unless it asks for more. Dependabot or Renovate can keep the SHAs current with the version comment.

The cost: `-Werror` turns every new JDK warning category into a red build on upgrade day, and the vulnerability job adds minutes and an NVD key to manage. Accept both. The fix is one commit, and a check that never fails is not a check.

## Good example

See [`examples/good/`](examples/good/).

```yaml
permissions:
  contents: read
```

```yaml
      - uses: actions/setup-java@b843ebf9b913146b38da2eaa7aeddf76074ae1ad # v6.1.0
        with:
          distribution: temurin
          java-version: ${{ matrix.java }}
          cache: maven
      - name: build, test and enforce (Maven pinned and checksummed by the wrapper)
        run: ./mvnw -B -ntp --strict-checksums verify
```

```xml
<rules>
<requireMavenVersion>
<version>[3.10.0,)</version>
</requireMavenVersion>
<requireJavaVersion>
<version>[25,)</version>
</requireJavaVersion>
<requirePluginVersions/>
<banDuplicatePomDependencyVersions/>
<dependencyConvergence/>
</rules>
```

```xml
<!-- The default is 11, above any CVSS score, so the check can never fail. -->
<failBuildOnCVSS>7</failBuildOnCVSS>
<nvdApiKey>${env.NVD_API_KEY}</nvdApiKey>
```

`mvn verify` on this project runs the enforcer rules, `check-buildplan` and a compile with `-Xlint:all` and `failOnWarning`, and `GuardrailsTest` fails the build if someone loosens a guardrail: a plugin without an exact version, `failBuildOnCVSS` above 10, a wrapper without a SHA-256, or a workflow with `|| true`, `skipTests`, a bare `mvn`, a non-SHA action or no read-only `permissions`.

## Bad example

See [`examples/bad/`](examples/bad/).

```yaml
      - uses: actions/checkout@main # MISTAKE: a branch; whoever pushes to it runs code in your pipeline.
      - uses: actions/setup-java@v6 # MISTAKE: a movable tag, not a commit SHA.
```

```yaml
      - name: build
        run: mvn install -DskipTests # MISTAKE: the runner's Maven, not the wrapper, and no tests at all.
      - name: tests
        run: mvn test || true # MISTAKE: the step can never fail.
```

```xml
<!-- MISTAKE: no version; the build uses whatever the Maven on the runner binds by default. -->
<artifactId>maven-surefire-plugin</artifactId>
<configuration>
<!-- MISTAKE: failing tests are reported and the build still goes green. -->
<testFailureIgnore>true</testFailureIgnore>
</configuration>
```

The rest of the bad project has no enforcer, a dependency-check without `failBuildOnCVSS`, a wrapper without `distributionSha256Sum`, a workflow without `permissions` and with `continue-on-error: true` on the scan, and a `Prices` class with a raw `List`, silent `long` overflow and a deprecated-for-removal `new Long(String)` that compile without a word because nothing turns warnings into errors.

## Checklist

When reviewing a pipeline under this skill, confirm:

- [ ] `mvnw` and `.mvn/wrapper/maven-wrapper.properties` are committed, pin Maven 3.10.x and set `distributionSha256Sum`, and CI calls `./mvnw`, never `mvn`.
- [ ] Every Maven call in CI uses `-B`, and the gate runs `--strict-checksums verify`.
- [ ] `maven-enforcer-plugin` runs `requireMavenVersion`, `requireJavaVersion [25,)`, `requirePluginVersions` and `dependencyConvergence`, and every plugin has an exact version.
- [ ] `maven-compiler-plugin` passes `-Xlint:all` with `failOnWarning` true, and no code uses `@SuppressWarnings("all")`.
- [ ] No workflow, POM or script uses `-DskipTests`, `-Dmaven.test.skip`, `testFailureIgnore`, `continue-on-error: true` or `|| true` on a check.
- [ ] `dependency-check-maven` sets `failBuildOnCVSS` to 10 or lower, takes the NVD key from a secret, and every suppression names one CVE with a reason.
- [ ] `project.build.outputTimestamp` is a real timestamp and `artifact:check-buildplan` runs in the build.
- [ ] Every `uses:` is pinned to a 40-character commit SHA, and the workflow sets `permissions: contents: read`.

## When not to apply

- **Gradle builds.** The same rules with Gradle's tools: commit the Gradle Wrapper with `distributionSha256Sum` in `gradle-wrapper.properties` (Gradle 9.8.1 docs: a mismatch fails the build), turn on dependency verification with `gradle/verification-metadata.xml`, set `options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))`, and use the dependency-check Gradle plugin with `failBuildOnCVSS`.
- **A vulnerability with no fixed version.** When a reachable CVE has no patched release yet, suppress that one CVE for that one package with an expiry date in the suppression file and track the upgrade, instead of raising `failBuildOnCVSS` or disabling the job.
- **A new JDK adds lint categories.** The first build on a new JDK may fail `-Werror` on code nobody touched. Fix the warnings in a separate commit, or exclude that one new key (`-Xlint:all,-<key>`) with an issue to remove it, never `-Xlint:none`.
- **Air-gapped or mirrored builds.** With an internal repository manager, point `distributionUrl` and `settings.xml` at the mirror, keep `distributionSha256Sum` and `--strict-checksums`, and use a mirrored NVD feed (`nvdApiEndpoint` or the dependency-check data mirror) for the scan.
- **Projects that cannot be reproducible yet.** A build that embeds the build time or host name on purpose (some generated version classes) cannot pass `artifact:compare`; keep `check-buildplan`, move the value out of the artifact into the deployment metadata, and turn on the comparison once it is gone.
- **Throwaway prototypes.** A spike that will never ship can skip the vulnerability job and reproducibility checks; keep the wrapper, the enforcer and the tests, which cost seconds.
