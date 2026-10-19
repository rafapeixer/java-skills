---
name: null-safety-jspecify
description: Use when adding nullness annotations to Java code, choosing between null, Optional and @Nullable for a missing value, migrating from org.springframework.lang.Nullable or javax.annotation (JSR 305) in a Spring Framework 7 / Spring Boot 4 upgrade, setting up NullAway or Error Prone, writing a record that must reject null, or chasing a NullPointerException. Key rule: @NullMarked on every package (package-info.java) so unannotated means non-null, org.jspecify.annotations.Nullable on the exceptions (String @Nullable [] for a nullable array); NullAway on Error Prone in maven-compiler-plugin at ERROR with OnlyNullMarked=true so a mistake fails the build; Optional only as a return type, never a field, parameter or null; empty collections, never null; records check every non-null component with Objects.requireNonNull in a compact constructor; @NullUnmarked for code not yet migrated.
metadata:
  verified: 2026-10-18
---

# Null safety with JSpecify and NullAway

## The decision

Every Java reference can be null, and the type system does not say which ones are allowed to be, so the decision is made one method at a time, by habit: some code returns null, some returns `Optional`, some throws, and callers guess. The guess fails as a `NullPointerException` in production, far from the line that produced the null. Spring Framework 7 settled the annotation question for the Java ecosystem: its own APIs now use [JSpecify](https://jspecify.dev/docs/user-guide/) and are checked with [NullAway](https://github.com/uber/NullAway) in its build ([Spring null-safety](https://docs.spring.io/spring-framework/reference/core/null-safety.html)). This skill takes the same position for application code: non-null by default at package level, `@Nullable` only where null is part of the contract, a checker that fails the build, and runtime checks where data enters from code the checker cannot see.

## The rule

1. Put `@NullMarked` (JSpecify 1.0) on every package in `package-info.java`, so unannotated types mean non-null and only the exceptions carry `@Nullable`.
2. Use `org.jspecify.annotations.Nullable`, never `org.springframework.lang.Nullable`, `javax.annotation.Nullable` or other JSR 305 annotations, and write `String @Nullable []` for a nullable array.
3. Run NullAway as an Error Prone plugin in `maven-compiler-plugin` with `-Xep:NullAway:ERROR` and `-XepOpt:NullAway:OnlyNullMarked=true`, so a nullness mistake fails the build.
4. Return `Optional` only from methods where "no result" is a normal answer, and never use it as a field, a parameter, a collection element or a null value.
5. Return an empty collection or map, never null.
6. Check every non-null reference component of a record with `Objects.requireNonNull` in a compact constructor, because annotations are not enforced at runtime.
7. Mark code that is not yet migrated `@NullUnmarked` instead of leaving the whole module unchecked, and remove the marker as each package is fixed.

## Why

**Rule 1.** The JSpecify user guide: under `@NullMarked`, "unannotated types in that scope are treated as if they were annotated with `@NonNull`". Most references in a service are never null, so non-null by default puts an annotation only on the few that are, and those stand out in review. Packages "are *not* hierarchical": `example` being marked says nothing about `example.web`, so every package needs its own `package-info.java`. `@NullMarked` on a module works too, when the project uses `module-info.java`. The JSpecify docs give the coordinates `org.jspecify:jspecify:1.0.0`.

**Rule 2.** Spring's `@Nullable`, `@NonNull`, `@NonNullApi` and `@NonNullFields` in `org.springframework.lang` "are deprecated as of Spring Framework 7 in favor of JSpecify annotations" (Spring docs, 7.0.9); they were built on JSR 305, which is dormant. JSpecify annotations are type-use annotations, so position matters: per the user guide, "it is the thing right after `@Nullable` that can be `null`". `@Nullable String[]` is an array of nullable strings; `String @Nullable []` is a nullable array. The Spring migration note gives exactly this change, `@Nullable Object[] array` to `Object @Nullable [] array`. Mixed annotations confuse every tool: IDEs and NullAway read JSpecify, and Kotlin translates JSpecify to its own null safety.

**Rule 3.** Annotations without a checker are comments. NullAway runs inside `javac` as an Error Prone plugin and reports "dereferenced expression ... is @Nullable" or "returning @Nullable expression" as compile errors. NullAway needs Error Prone 2.36.0 or higher and JDK 17+, and "requires exactly one of the `AnnotatedPackages` or `OnlyNullMarked` configuration arguments" ([NullAway](https://github.com/uber/NullAway)); `OnlyNullMarked` follows JSpecify's own scoping and is what Spring recommends. Error Prone "must be run on JDK 21 or newer" and, since JEP 396, needs the `--add-exports` and `--add-opens` lines in `.mvn/jvm.config` ([Error Prone installation](https://errorprone.info/docs/installation)). The cost is compile time and a pinned pair of versions (Error Prone 2.50.0, NullAway 0.14.2 here) to update together. `NullAway:JSpecifyMode=true` adds checks on generics, arrays and varargs; Spring calls it "still under development", requiring JDK 22 or later, and recommends it as a second step once the base configuration is clean.

**Rule 4.** The `Optional` javadoc: it "is primarily intended for use as a method return type where there is a clear need to represent 'no result'", and "a variable whose type is `Optional` should never itself be `null`" ([Optional](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Optional.html)). As a parameter it gives callers three ways to say "absent" (null, `Optional.empty()`, an overload) instead of one; as a field it adds an allocation per object and is not `Serializable`. Inside a `@NullMarked` package, a `@Nullable` return checked by NullAway is just as safe and costs nothing, so use it on hot paths and in APIs that follow Spring's style; use `Optional` where the caller benefits from `map`/`orElse` chaining.

**Rule 5.** A null collection forces a check at every call site and every loop; an empty one makes the absent case the same code path as the normal one. `List.of()`, `Map.of()` and `Stream.toList()` are immutable and cost nothing to return.

**Rule 6.** JSpecify and NullAway work at compile time on code they check. A JSON mapper, a JDBC row mapper, reflection, or a library compiled without the checker can still call the canonical constructor with null, and the record will store it. A compact constructor with `Objects.requireNonNull(id, "id")` fails at the boundary with the component's name, instead of a `NullPointerException` three calls later. NullAway accepts the check as consistent with the non-null type. The good example's test calls the constructor with null from a `@NullUnmarked` class, as such callers do.

**Rule 7.** Turning the checker on for an old code base reports hundreds of errors at once. `@NullUnmarked` "simply undoes the effects of the surrounding `@NullMarked`" for a package, class or method, and NullAway skips that code, so the build can enforce the rule for everything else from day one. Each removed marker is a reviewed step; NullAway's `RequireExplicitNullMarking` checker can verify that every class carries an explicit marking.

## Good example

See [`examples/good/`](examples/good/).

```java
@NullMarked
package example;
```

```java
public record Customer(String id, String email, @Nullable String nickname) {
    public Customer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(email, "email");
    }
```

```java
    public Optional<Customer> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }
    public @Nullable Customer findOrNull(String id) {
        return byId.get(id);
    }
```

```xml
<arg>-XDcompilePolicy=simple</arg>
<arg>--should-stop=ifError=FLOW</arg>
<arg>-Xplugin:ErrorProne -Xep:NullAway:ERROR -XepOpt:NullAway:OnlyNullMarked=true</arg>
```

The compiler configuration lists `error_prone_core` and `nullaway` under `annotationProcessorPaths`, and `.mvn/jvm.config` carries Error Prone's ten `--add-exports`/`--add-opens` lines. `mvn verify` compiles the package with NullAway at ERROR and passes. `NullAwayTest` runs `javac` with the same flags on two snippets and asserts the build rejects dereferencing a `@Nullable` return and returning null from a non-null method, the two mistakes in the bad example, and accepts the checked version.

## Bad example

See [`examples/bad/`](examples/bad/).

```java
        // MISTAKE: dereferencing a @Nullable component; NullAway would reject this line.
        return nickname.isBlank() ? email : nickname;
```

```java
    // MISTAKE: Optional as a parameter: callers now pass null, Optional.empty() or a value.
    public void add(Customer customer, Optional<String> source) {
```

```java
    // MISTAKE: the return type is non-null under @NullMarked, yet Map.get returns null for a missing id.
    public Customer find(String id) {
        return byId.get(id);
    }
```

```xml
<!-- MISTAKE: annotations with no checker in the build. Nothing reads @NullMarked, so every
nullness mistake below compiles and waits for production as a NullPointerException. -->
```

The example also stores an `Optional` in a field, returns a null list, uses the deprecated `org.springframework.lang.Nullable`, and has a record with no compact constructor. It all compiles cleanly with plain `javac`, which is the point: the annotations promise null safety and nothing checks the promise.

## Checklist

When reviewing code under this skill, confirm:

- [ ] Every package has a `package-info.java` with `@NullMarked` (or the module is `@NullMarked`), and `@NullUnmarked` appears only on code awaiting migration.
- [ ] Nullness annotations come from `org.jspecify.annotations`; no `org.springframework.lang`, `javax.annotation` or other JSR 305 imports remain.
- [ ] A nullable array is written `T @Nullable []`, and `@Nullable T[]` appears only where the elements may be null.
- [ ] The build runs NullAway at ERROR with `OnlyNullMarked=true` (or `AnnotatedPackages`), and `.mvn/jvm.config` has Error Prone's exports.
- [ ] `Optional` appears only as a return type, never as a field, parameter or collection element, and no method returns a null `Optional`.
- [ ] Methods returning collections or maps return empty ones, never null.
- [ ] Every record with non-null reference components checks them with `Objects.requireNonNull` in a compact constructor.
- [ ] No `@SuppressWarnings("NullAway")` or castToNonNull without a comment saying why the value cannot be null.

## When not to apply

- **Code generated by tools.** Generated sources (OpenAPI clients, jOOQ, protobuf) do not carry JSpecify annotations; keep them in their own package, leave it unmarked, and validate their output at the boundary where your code receives it.
- **Values the checker cannot reason about.** A field set by a framework after construction (dependency injection into a field, `@BeforeEach` in tests) looks nullable to NullAway. Prefer constructor injection; when that is not possible, use NullAway's documented initializer support instead of marking the field `@Nullable`.
- **A build that cannot run Error Prone.** Error Prone needs JDK 21+ for compilation and access to `javac` internals. If a toolchain forbids it, keep the JSpecify annotations (IDEs and Kotlin still read them) and add the checker as soon as the build allows; do not switch to JSR 305 meanwhile.
- **Performance-sensitive inner loops.** `Optional` allocates unless the JIT removes it; in hot code inside a `@NullMarked` package, return `@Nullable` and let NullAway enforce the check instead.
- **Public library APIs with Java 8 consumers.** JSpecify annotations are harmless on old JDKs, but the checker runs only in your build; keep the runtime `requireNonNull` checks on every public entry point.
