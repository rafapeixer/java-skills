---
name: scoped-values
description: Use when writing or reviewing Java code that carries request context (request id, trace id, tenant, authenticated user) down the call stack without passing parameters, replacing a ThreadLocal or InheritableThreadLocal, writing a servlet filter or interceptor that sets per-request state, or handing that context to tasks on other threads. Key rule: on JDK 25+ use a static final ScopedValue (final in JDK 25, JEP 506) bound with ScopedValue.where(KEY, value).run/call at the edge, holding an immutable record; read it with get(), isBound() or orElse(); rebind for a nested scope instead of mutating; never InheritableThreadLocal, never ThreadLocal.set without remove; executor threads do not inherit bindings (only StructuredTaskScope forks, a preview API), so capture the value and bind it again inside the task.
metadata:
  verified: 2026-10-18
---

# Scoped values

## The decision

Some data belongs to the request, not to any one method: the request id for logs, the tenant for queries, the authenticated user for authorization checks. Passing it through every signature is noisy, so Java code has used `ThreadLocal` for twenty years: set it in a filter, read it anywhere, and hope someone calls `remove()`. That hope is where the bugs are: a pooled thread starts the next request with the previous user, an `InheritableThreadLocal` copies stale context into a worker thread that outlives the request, a callee mutates the context and every later reader sees it. `ScopedValue`, final in JDK 25 ([JEP 506](https://openjdk.org/jeps/506)), gives the same "read it anywhere" convenience with a lifetime the code structure enforces. The decision is which one carries request context; on the floor JDK 25, the answer is `ScopedValue`.

## The rule

1. Declare request context as a `static final ScopedValue<T>` created with `ScopedValue.newInstance()`, holding an immutable value such as a record.
2. Bind it once at the edge (filter, interceptor, message listener) with `ScopedValue.where(KEY, value).run(...)` or `.call(...)`, around the whole unit of work.
3. Read it with `KEY.get()` where it must be present, and with `isBound()` or `orElse(default)` in code that may also run outside a request.
4. To change the context for a sub-operation, bind a new value in a nested `where(...)` scope; never mutate the bound object.
5. When work moves to an executor, capture the value with `get()` on the caller and bind it again inside the task, because only threads forked by `StructuredTaskScope` (a preview API) inherit bindings.
6. Do not use `InheritableThreadLocal` for request context, and do not call `ThreadLocal.set` for it anywhere; if a library forces a `ThreadLocal` on you, set and `remove()` it in the same `try`/`finally`.

## Why

**Rules 1 and 2.** A binding exists only while `run` or `call` executes, on the thread that executes it; when the lambda returns, the binding is gone ([JEP 506](https://openjdk.org/jeps/506): "After the `run` method finishes, the binding is destroyed"). There is no `set` and no `remove`, so there is no way to forget the cleanup that `ThreadLocal` needs. This matters more with virtual threads: there can be millions of them, and JEP 506 notes that each child thread created with `InheritableThreadLocal` must allocate storage for every inherited thread-local. A `static final` key keeps the scoped value a single well-known name, like a constant, instead of something passed around. `call` returns the handler's result and propagates its exception, so it wraps a request handler as easily as `run` wraps a `void` one.

**Rule 3.** [The `ScopedValue` API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ScopedValue.html) throws `NoSuchElementException` from `get()` when the value is not bound. That is what you want in code that must never run without a request: a missing binding fails loudly at the read, not as a `null` three calls later. Code that legitimately runs both inside and outside a request (a shared audit helper, a scheduled job) uses `isBound()` or `orElse(default)`; since JDK 25 `orElse` no longer accepts `null`.

**Rule 4.** A scoped value cannot be reassigned within a scope; a nested `where(...)` changes what callees see and reverts when it returns (JEP 506: once the nested call returns, the outer value is visible again). That makes "run this block as the system user" a scoped, visible operation instead of a mutation that silently persists for the rest of the request. The binding is only as immutable as the object you bind, which is why rule 1 asks for a record with no setters.

**Rule 5.** Inheritance is deliberately limited to structured cases. The API documentation says sharing across threads "is limited to structured cases where child threads are started and terminate within the bounded period of execution by a parent thread", and describes it only for threads forked by a `StructuredTaskScope`. A thread created by `Executors.newVirtualThreadPerTaskExecutor()` or any pool starts unbound, and JEP 506 adds that `ForkJoinPool` (so `parallelStream()` and `CompletableFuture.supplyAsync` without an executor) does not inherit either. The good example proves it: a task submitted from inside the scope sees `isBound() == false`. `StructuredTaskScope` is still a preview API in JDK 25, 26 and 27 (JEPs [505](https://openjdk.org/jeps/505), [525](https://openjdk.org/jeps/525), [533](https://openjdk.org/jeps/533)), final only in JDK 28 ([JEP 543](https://openjdk.org/jeps/543)); until then, capture and rebind explicitly. It is two lines, and the hand-off is visible in review.

**Rule 6.** `InheritableThreadLocal` copies the parent's values when a thread is created, not when a task is submitted. A pool thread created during request A carries request A's context into every task it runs afterwards, so the audit log records request 2 under request 1's id; the bad example does exactly that. A plain `ThreadLocal.set` without `remove()` on a pooled server thread does the same across requests. JEP 506 does not deprecate `ThreadLocal`, and it says some uses (per-thread caches of expensive mutable objects) are still a better fit for thread-locals; with virtual threads, though, prefer shared immutable objects for caches too (see `virtual-threads`).

The cost: the code that binds must wrap the unit of work in a lambda, which changes the shape of filters and interceptors, and every hand-off to another thread is explicit. Libraries that read their own `ThreadLocal` (logging context, framework request holders) keep working as before and do not see your scoped value.

## Good example

See [`examples/good/`](examples/good/).

```java
// Request context: immutable, bound once per request at the edge, read anywhere below.
record RequestContext(String requestId, String userId) {}

static final ScopedValue<RequestContext> REQUEST = ScopedValue.newInstance();
```

```java
// The binding exists only while handle() runs, on this thread.
String response = ScopedValue.where(REQUEST, ctx).call(() -> handle("/orders/1"));
check(response.equals("req-42 user-7 /orders/1 audit=req-42 worker=req-42"), response);

// After the scope ends the value is gone: nothing to remove, nothing to leak.
check(!REQUEST.isBound(), "REQUEST still bound after the scope");
```

Handing the context to another thread is explicit:

```java
// A thread started by an executor does not inherit the binding (only
// StructuredTaskScope forks do). Capture the value and bind it again.
RequestContext ctx = REQUEST.get();
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    Future<Boolean> leaked = executor.submit(REQUEST::isBound);
    check(!leaked.get(), "executor thread saw the caller's binding");

    Future<String> f = executor.submit(() ->
            ScopedValue.where(REQUEST, ctx).call(() -> REQUEST.get().requestId()));
    return f.get();
}
```

The example checks that the value is visible deep in the call stack, unbound after the scope (`get()` throws `NoSuchElementException`), shadowed and then restored by a nested binding, not inherited by an executor thread, and visible there once rebound.

## Bad example

See [`examples/bad/`](examples/bad/).

```java
// MISTAKE: InheritableThreadLocal for request context. Every child thread copies
// the parent's map at creation; a pooled worker created during request A keeps
// request A's context for every later task it runs.
static final InheritableThreadLocal<RequestContext> REQUEST = new InheritableThreadLocal<>();
```

```java
// MISTAKE: set() with no remove() in a finally block. On a pooled thread the
// next request starts with this request's user, and the value lives as long
// as the thread does.
REQUEST.set(ctx);
```

```java
static void impersonate(String user) {
    // MISTAKE: a callee mutates the shared context; the caller and every later
    // reader now act as "admin", with no scope that ends the change.
    REQUEST.get().userId = user;
}
```

This is the shape most request-context code still has, often copied from pre-JDK 21 answers: a mutable holder, set in a filter, inherited "so async code works", and changed in place for impersonation. Each line works in a single-request test and fails under concurrency or thread reuse.

## Checklist

When reviewing code under this skill, confirm:

- [ ] Request context is a `static final ScopedValue` created with `ScopedValue.newInstance()`, not a `ThreadLocal` or `InheritableThreadLocal`.
- [ ] The bound value is immutable (a record or final fields with immutable types).
- [ ] The binding is made once at the edge with `where(...).run` or `.call` around the whole unit of work.
- [ ] Code that must run inside a request reads with `get()`; code that may run outside uses `isBound()` or `orElse(default)`, never a null check on a `ThreadLocal`.
- [ ] Context changes for a sub-operation use a nested `where(...)` scope, and nothing mutates the bound object.
- [ ] Every task submitted to an executor, `parallelStream()` or `CompletableFuture` that needs the context captures it before submission and binds it again inside the task.
- [ ] No `InheritableThreadLocal` exists for request data, and any `ThreadLocal` forced by a library is set and removed in one `try`/`finally`.
- [ ] `StructuredTaskScope`, if used for inheritance, is in code built with `--enable-preview` for one specific JDK.

## When not to apply

- **Library-owned thread-locals.** Logging context maps and framework request holders are read by their own code from a `ThreadLocal`; keep populating them the way the library documents, in a `try`/`finally`, and bind your own context in a `ScopedValue` alongside.
- **Context that must change and be read back by the caller**, such as a transaction or a counter that callees increment: that is mutable shared state, not context. Pass an explicit object, or use the framework's transaction support.
- **One or two parameters through a few methods.** If the call chain is short, pass the value as an argument; a scoped value is for data that crosses many layers you do not want to change.
- **Per-thread caches on platform-thread pools.** JEP 506 keeps `ThreadLocal` for caching expensive mutable objects on a small, long-lived pool; on virtual threads, share an immutable object instead.
- **Code that must still build on JDK 21.** `ScopedValue` is preview there; keep `ThreadLocal` with strict `try`/`finally` until the build moves to the JDK 25 floor.
