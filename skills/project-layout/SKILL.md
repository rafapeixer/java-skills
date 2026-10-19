---
name: project-layout
description: Use when creating a new Java backend service or Spring Boot application, adding a package, class or Maven module to one, deciding where an interface, a shared helper or the main class goes, or deciding whether to split into modules or services. Key rule: package by feature, not by layer; put the main class (@SpringBootApplication) in the root package, never the default package, with one subpackage per feature (com.example.shop.orders) that owns its types, logic and storage; make classes package-private unless another feature calls them; no FooService interface plus FooServiceImpl for a single implementation; when one feature needs another, declare a small interface in the consumer and wire it in the root; never name packages util, common, model, service, repository or controller; one Maven module and one deployable until a measured need, then split along a feature package. Enforce it with a test.
metadata:
  verified: 2026-10-18
---

# Project layout

## The decision

Every Java service starts with a package tree, and in Java the tree decides more than in most languages: package-private is the only visibility the compiler gives you between `private` and `public`, so the packages you choose are the boundaries you get. The layout most generators and tutorials produce, `controller/`, `service/`, `repository/`, `model/` and `util/`, puts the pieces of one feature in four packages. That forces every class to be `public`, turns the first new field into edits across four directories, and invites a `FooService` interface plus `FooServiceImpl` for every class. This skill fixes where the main class, the features, interfaces and shared code go in a backend service, and when one Maven module with one deployable should become several. Build guardrails (enforcer, wrapper, reproducible builds) belong to `ci-guardrails`; which JDK the project targets belongs to `java-version-policy`.

## The rule

1. Use the Maven standard directory layout in one module (`src/main/java`, `src/main/resources`, `src/test/java`) and put the main class in the root package (`com.example.shop`), never in the default package.
2. Give each feature one direct subpackage of the root (`com.example.shop.orders`, `com.example.shop.billing`) that owns its types, logic and storage, instead of layer packages (`controller`, `service`, `repository`, `model`).
3. Make every class package-private by default and `public` only when another feature or the root has to call it.
4. Write a concrete class, not an interface plus a single `Impl`, and add an interface only for a second real implementation or a boundary you must fake.
5. When one feature needs another, declare the smallest interface in the consumer (often a `@FunctionalInterface`) and wire it in the root with a method reference, so the consumer never imports the provider.
6. Name packages after what they provide, never `util`, `utils`, `common`, `helpers`, `shared`, `base` or `model`.
7. Keep one module and one deployable until a feature has a measured need for independent release, scaling or failure isolation, then split along an existing feature package.
8. Enforce the layout with a test that fails the build (a reflection test, ArchUnit, or Spring Modulith's `ApplicationModules.verify()`), not with a wiki page.

## Why

**Rule 1.** Maven, Gradle, IDEs and every plugin assume the standard directory layout (maven.apache.org/guides/introduction/introduction-to-the-standard-directory-layout.html); a custom tree costs configuration in every tool forever. Spring Boot's "Structuring Your Code" says the default package "is generally discouraged and should be avoided" because `@ComponentScan`, `@EntityScan` and `@SpringBootApplication` there make Spring read "every class from every jar". It recommends the main class "in a root package above other classes": `@SpringBootApplication` uses that package as the base for component and entity scanning, so a main class in a subpackage silently misses beans in sibling packages. A class in the default package also cannot be imported by anything, so a test cannot reach it.

**Rule 2.** A layer package couples every feature to every layer: adding a field to an order touches the controller, the service, the repository and the model, and a review cannot see the feature in one place. A feature package keeps what changes together in one directory and lets the feature hide its storage. Spring Modulith formalizes the same shape: "each direct sub-package of the main package is considered an application module package" (docs.spring.io/spring-modulith/reference/fundamentals.html), so this layout is the one its verification and documentation tools expect if you adopt it later.

**Rule 3.** Package-private is the only boundary `javac` enforces between packages without JPMS. In a feature package, `OrderStore` and `Ledger` are package-private, so no other feature can read orders' map or billing's ledger, and both can change shape without a search across the code base. Layer packages make this impossible: the repository must be `public` because the service is in another package, and once it is public everyone uses it. Spring injects package-private classes and constructors without complaint.

**Rule 4.** An interface with one implementation doubles every signature change, sends "go to definition" to the interface instead of the code, and hides nothing: the `Impl` is public anyway. Mockito and Spring both work with concrete classes, so "for testability" is not a reason. Add the interface when a second implementation exists (two payment providers) or when the boundary leaves the process and you want a fake.

**Rule 5.** A provider-side `BillingService` listing every method makes each consumer depend on methods it never calls and each fake implement all of them. A one-method `Orders.Charger` declared by orders documents exactly what orders needs, the test fake is a one-line lambda, and orders compiles without billing on the classpath. The root wires `new Orders(billing::charge)`, and that method reference is the compile-time check that the two still fit. With Spring, the same thing is a `@Bean` method in the root configuration.

**Rule 6.** A package name is part of every import: `billing.Billing` says what it is, `util.MoneyUtils` says nothing, so unrelated code piles up there and it becomes the hub of the dependency graph. When a helper serves one feature, put it in that feature, package-private. When it is a real shared capability, name the package after it (`money`, `http`, `postgres`).

**Rule 7.** A Maven multi-module build adds a reactor, a parent POM, inter-module versions and slower builds; a separate service adds network calls with timeouts, retries, partial failure and a distributed transaction where there was one database transaction, plus its own pipeline, dashboards and on-call. Feature packages already give you most of the modularity: if orders only reaches billing through `Charger`, moving billing into its own Maven module or service later is a change in the root wiring and one adapter, not a rewrite. Split into Maven modules when a part must be released or reused on its own (a client library, a shared schema), not to draw boxes.

**Rule 8.** Conventions decay one "just this once" `public` at a time, and coding agents reproduce the layer layout from training data. A test is the only reviewer that checks every commit. The good example's `LayoutTest` uses plain reflection over `target/classes`; ArchUnit (archunit.org) and Spring Modulith (`ApplicationModules.of(Application.class).verify()`) do the same with more rules, including references into another module's internal subpackages, which the compiler cannot catch.

The cost: a feature package sometimes repeats a small type (an order reference in billing) instead of sharing a `model`, and the root grows a few lines of wiring per feature. That duplication is what keeps features independent; share a type only when both sides must change together.

A layout that follows these rules:

```text
pom.xml
src/main/java/com/example/shop/ShopApplication.java   # main class and wiring only
src/main/java/com/example/shop/orders/                # Orders, Order (public); OrderStore (package-private)
src/main/java/com/example/shop/billing/               # Billing (public); Ledger (package-private)
src/test/java/com/example/shop/LayoutTest.java        # fails the build when the layout drifts
```

## Good example

See [`examples/good/`](examples/good/).

```java
public final class Orders {

    /** What orders needs from billing, declared here by the consumer, not by billing. */
    @FunctionalInterface
    public interface Charger {
        String charge(String customerId, long cents);
    }

    private final Charger charger;
    private final OrderStore store = new OrderStore();
```

```java
    static Orders wire() {
        Billing billing = new Billing(10_000);
        return new Orders(billing::charge); // the root is the only package that sees both features
    }
```

```java
        assertEquals(Map.of(
                ROOT, Set.of("ShopApplication"),
                ROOT + ".billing", Set.of("Billing"),
                ROOT + ".orders", Set.of("Order", "Orders")), publicTypes);
```

`orders` never imports `billing`; the root is the only package that sees both. `OrderStore` and `Ledger` are package-private, so each feature owns its storage. `LayoutTest` fails `mvn verify` if a new class becomes public, a package is named after a layer or a catch-all, or orders starts depending on billing, and `OrdersTest` fakes billing with a lambda.

## Bad example

See [`examples/bad/`](examples/bad/).

```java
// MISTAKE: the entry point is in the default package; it cannot be imported, and a
// @SpringBootApplication here makes component scanning read every class in every jar.
public class Main {
```

```java
// MISTAKE: an interface with exactly one implementation, written "for testability";
// every change is made twice and navigation lands on the interface, not the code.
public interface OrderService {
    Order place(String customerId, long cents);
}
```

```java
        // MISTAKE: orders reads billing's data directly through the shared store;
        // billing can no longer change how it keeps charges.
        if (!store.charges.containsKey(chargeId)) {
```

The bad project is the layout generated most often for a "clean" Java service: `model`, `repository`, `service` and `util` packages, `OrderService`/`OrderServiceImpl` and `BillingService`/`BillingServiceImpl` pairs, every class `public`, one store with public maps shared by both features, and a discount rule in `main`. It compiles and runs; the cost shows up on the second feature, when every change touches every layer.

## Checklist

When reviewing code under this skill, confirm:

- [ ] The project uses `src/main/java`, `src/main/resources` and `src/test/java`, and no class is in the default package.
- [ ] The main class (`@SpringBootApplication`) is in the root package, above every other package.
- [ ] Each direct subpackage of the root is a feature that owns its types, logic and storage; none is named `controller`, `service`, `repository`, `model`, `dto` or `entity`.
- [ ] No package is named `util`, `utils`, `common`, `helpers`, `shared` or `base`.
- [ ] Classes are package-private unless another feature or the root calls them.
- [ ] No interface has exactly one implementation unless it marks a boundary that tests fake.
- [ ] A feature that needs another declares the interface it consumes and is wired in the root; no feature reads another feature's storage.
- [ ] The build has one module and one deployable, or the split names the measured release, reuse or isolation need behind it.
- [ ] A test (reflection, ArchUnit or Spring Modulith `verify()`) fails the build when the layout rules are broken.

## When not to apply

- **A single-file tool or script.** A compact source file run with `java Main.java` needs no packages at all; add the root package when a second class appears.
- **A library.** When other projects import the jar, the public packages are the product: design them as an API (and consider a JPMS `module-info.java` that `exports` only those packages), and keep implementation in non-exported packages.
- **A feature that grew large.** Inside one feature, subpackages are fine (`orders.internal`, `orders.web`); keep the feature's public API in the feature package and treat the subpackages as internal, which Spring Modulith verifies.
- **Frameworks that dictate structure.** JPA entities, generated OpenAPI or protobuf code and jOOQ classes come with their own packages; keep generated code in a package named after what it is (`orders.db`, `api.gen`) and do not hand-edit it to match these rules.
- **A provider-owned contract.** When a package defines an extension point many unrelated implementations satisfy (a `Filter`, a JDBC `Driver`, a plugin SPI), the provider declares the interface; that is an API, not a dependency you are faking.
- **Separate teams or hard isolation.** When two teams already release on different schedules, or a component handles regulated data that must run in its own security boundary, split into modules or services now and record the reason; the cost in rule 7 is the price of a real requirement.
