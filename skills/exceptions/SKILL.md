---
name: exceptions
description: Use when writing or reviewing Java code that throws, catches, wraps or defines exceptions, chooses checked vs unchecked, handles InterruptedException, or turns errors into HTTP responses in Spring (@ExceptionHandler, @RestControllerAdvice, ProblemDetail, ResponseEntity error bodies). Key rule: a small unchecked domain hierarchy for failures no direct caller can recover from, checked only when the immediate caller is expected to handle it; when translating across a layer, wrap with new XException("operation + ids", cause) and never drop the cause; handle each exception once (recover, or wrap and rethrow, never log-and-rethrow, never an empty catch); on InterruptedException call Thread.currentThread().interrupt() before anything else; map domain exceptions to HTTP status in one @RestControllerAdvice that returns RFC 9457 ProblemDetail, with no try/catch in controllers and no HTTP status in the domain; a catch-all logs once and returns a generic 500 without e.getMessage().
metadata:
  verified: 2026-10-18
---

# Exceptions

## The decision

Every Java method that can fail decides what to throw, every caller decides whether to catch, and every web service decides how a failure becomes an HTTP response. The usual answers each look reasonable on their own: a checked exception "so callers don't forget", a `catch` that logs "so we see it", a `RuntimeException(e.getMessage())` "to get rid of the checked one", a `try/catch` in each controller "to return the right status", an empty `catch (InterruptedException e) {}` "because it never happens". Together they produce a service whose logs show the same failure five times without the line that caused it, whose clients get a 500 with a SQL fragment in the body, whose status codes differ between endpoints for the same error, and whose threads ignore shutdown. This skill fixes which exceptions a codebase defines, where they are caught, what wrapping keeps, and the single place where they become HTTP responses. How long a call may wait before failing is `timeouts-and-deadlines`; what is logged with each error is `logging-structured`.

## The rule

1. Define a small unchecked domain hierarchy (one abstract base extending `RuntimeException`) for failures no direct caller can recover from, and use a checked exception only when the immediate caller is expected to handle it.
2. When an exception crosses a layer boundary, wrap it with a message that names the operation and the ids involved and pass the original as the cause: `new XException("check stock for sku " + sku, e)`.
3. Handle each exception exactly once: either recover from it, or wrap and rethrow it, and never both log and rethrow it.
4. Never write an empty `catch` and never catch `Exception` or `Throwable` except at the outermost boundary (the HTTP handler, the job runner, `main`).
5. When you catch `InterruptedException`, call `Thread.currentThread().interrupt()` first, then stop the work and throw; never continue as if nothing happened.
6. Map exceptions to HTTP status in one `@RestControllerAdvice` that returns `ProblemDetail` (RFC 9457), with no `try/catch` in controllers and no HTTP status inside domain exceptions.
7. End that advice with a catch-all `@ExceptionHandler(Exception.class)` that logs the stack trace once and returns a generic 500 whose body never contains `e.getMessage()`.

## Why

**Rule 1.** The [Java tutorial's bottom line](https://docs.oracle.com/javase/tutorial/essential/exceptions/runtime.html): "If a client can reasonably be expected to recover from an exception, make it a checked exception. If a client cannot do anything to recover from the exception, make it an unchecked exception." In a web service, the caller of `OrderService.get` is a controller that cannot conjure a missing order; making `OrderNotFoundException` checked forces `throws` through every layer, and in practice someone wraps it in a bare `RuntimeException` to make the compiler stop, which loses the type the handler needed. Checked still fits where the direct caller has a real alternative: a parser whose caller falls back to a default, or `IOException` inside an adapter that retries. A shared abstract base lets the boundary see "this is ours" while each subclass carries one meaning. Keep the hierarchy small: one class per distinct response or recovery, not one per call site.

**Rule 2.** The cause is the stack trace of the real failure. [`Throwable`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Throwable.html) prints it as `Caused by:` under your own trace, so a log shows both what the service was doing and what broke underneath. `new RuntimeException(e.getMessage())` keeps neither the type nor the trace: "connection refused" with no hint of which host, which call, which line. The message should say what you were doing with which ids ("check stock for sku A1"), not what the exception class already says. Wrap where meaning changes, typically where an adapter (HTTP client, JDBC) meets the domain; between two methods of the same layer, let it propagate.

**Rule 3.** A method that logs and rethrows logs the failure once per layer: the same error three to five times, at different levels, each looking like a separate incident, and the alert counts multiply. Decide per catch site: either you can do something (fall back, retry, return a default) and the exception ends here, or you cannot and it goes up, wrapped if the layer changes, with one log line at the boundary that handles it. The example's handler logs a dependency failure at `warn` and an unexpected one at `error`, each once, with the full cause chain.

**Rule 4.** An empty `catch` turns a failure into wrong data: the bad example treats an interrupted stock lookup as "0 in stock" and carries on. A broad `catch (Exception e)` in the middle of the code also catches the `NullPointerException` and `IllegalStateException` that reveal bugs, and hides them behind whatever the catch returns. At the outermost boundary a catch-all is right, because something must turn any failure into a response or a failed job.

**Rule 5.** Interruption is how Java asks a thread to stop: `ExecutorService.shutdownNow`, `Future.cancel(true)`, `HttpClient.close`. Throwing `InterruptedException` clears the thread's interrupted status ([javadoc](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/InterruptedException.html): `Thread.interrupted()` "Clears interrupted status!"), so a catch that does not restore it erases the request. The thread then ignores shutdown, a cancelled task keeps running, and the next blocking call waits as if nothing happened. [The tutorial on interrupts](https://docs.oracle.com/javase/tutorial/essential/concurrency/interrupt.html) describes the convention. Restore the flag, then throw an exception that says the work did not finish; the caller and the executor see the flag and stop.

**Rule 6.** The Spring Framework reference supports [RFC 9457](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html) through `ProblemDetail`: returned from an `@ExceptionHandler`, its `status` sets the HTTP status, `instance` is filled from the request path, and Jackson writes it as `application/problem+json`. Extending `ResponseEntityExceptionHandler` makes Spring MVC's own exceptions (a missing parameter, a type mismatch, an unsupported media type) render the same way, so clients parse one error format for everything. One advice means one table from exception to status: a reviewer reads it in a minute, and a new endpoint gets correct errors without writing any. Keep HTTP out of the domain: `ErrorResponseException` or `@ResponseStatus` on a domain class ties the service layer to Spring MVC, and the same exception thrown from a message listener has no status to use. Spring Boot's `spring.mvc.problemdetails.enabled` covers only the built-in exceptions, so it does not replace your advice.

**Rule 7.** `e.getMessage()` of an unexpected exception is written for developers: a JDBC URL, a SQL fragment, a file path, sometimes a value from the request. Returned to a client, it is an information leak and a stable contract you never meant to publish. The client needs a status and a generic detail; the operator needs the stack trace in the log, written once by the handler. The example's test throws an `IllegalStateException` containing a password and checks the 500 body does not contain it.

## Good example

See [`examples/good/`](examples/good/).

```java
public abstract class OrderException extends RuntimeException {
    protected OrderException(String message) {
        super(message);
    }

    protected OrderException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

```java
} catch (IOException e) {
    // Translate at the layer boundary: say what we were doing, keep the cause.
    throw new InventoryUnavailableException("check stock for sku " + sku, e);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt(); // restore the flag so the caller sees it
    throw new InventoryUnavailableException("check stock for sku " + sku + " (interrupted)", e);
}
```

```java
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
```

```java
@ExceptionHandler(OrderNotFoundException.class)
ProblemDetail notFound(OrderNotFoundException e) {
    return problem(HttpStatus.NOT_FOUND, "Order not found", e.getMessage());
}
```

```java
@ExceptionHandler(Exception.class)
ProblemDetail unexpected(Exception e) {
    log.error("unhandled exception", e);
    // Never echo e.getMessage(): it can carry SQL, hostnames or secrets.
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "unexpected error");
}
```

The controller has no `try/catch`; the advice is the only place that knows status codes. `ExceptionsTest` drives the controller and advice through `MockMvc` and checks that a missing order is a 404 `application/problem+json` with `title`, `detail` and `instance`; that insufficient stock is a 409; that an inventory `ConnectException` becomes a 503 and the domain exception keeps it as its cause with the sku in the message; that an unexpected exception is a 500 without its message in the body; that a bad request parameter is a problem detail too; and that an interrupted lookup leaves the thread's interrupt flag set.

## Bad example

See [`examples/bad/`](examples/bad/).

```java
} catch (IOException e) {
    // MISTAKE: logs and rethrows, so the same failure is logged again by every layer
    // above; and new RuntimeException(e.getMessage()) drops the cause and its stack trace.
    log.error("inventory failed", e);
    throw new RuntimeException(e.getMessage());
} catch (InterruptedException e) {
    // MISTAKE: swallows the interrupt; the flag is cleared, so shutdown or a cancelled
    // Future cannot stop this thread, and the order goes ahead with zero stock.
    available = 0;
}
```

```java
// MISTAKE: picks the status by matching the message text, which breaks the day
// someone rewords it; and returns e.getMessage() for a 500, leaking internals
// (SQL, hostnames, file paths) to the client.
if (e.getMessage() != null && e.getMessage().contains("stock")) {
    return ResponseEntity.status(409).body(e.getMessage());
}
return ResponseEntity.status(500).body(e.getMessage());
```

The project also makes `OrderNotFoundException` checked, so every layer declares it, throws it without the order id, and maps it to a status inside the controller method with a bare string body. It compiles, and every endpoint returns something plausible until the first real failure.

## Checklist

When reviewing code under this skill, confirm:

- [ ] Domain exceptions extend one small unchecked base, and checked exceptions appear only where the direct caller handles them.
- [ ] Every wrap passes the original exception as the cause, and its message names the operation and the ids involved.
- [ ] No catch block both logs and rethrows (or wraps and rethrows) the same exception.
- [ ] No empty `catch`, and no `catch (Exception e)` or `catch (Throwable t)` outside the outermost boundary.
- [ ] Every `catch (InterruptedException e)` calls `Thread.currentThread().interrupt()` and then stops the work.
- [ ] Controllers contain no `try/catch` for mapping errors; one `@RestControllerAdvice` maps every domain exception to a status and returns `ProblemDetail`.
- [ ] Domain exceptions carry no HTTP status (`@ResponseStatus`, `HttpStatus`, `ErrorResponseException`).
- [ ] The advice extends `ResponseEntityExceptionHandler` and ends with a catch-all handler that logs once and returns a generic 500 without `e.getMessage()`.
- [ ] Status codes are chosen by exception type, never by matching message text.

## When not to apply

- **Libraries** shared across services do not know about Spring or HTTP. Define the library's own exceptions, and let each service map them in its advice; a checked exception is more defensible in a library API whose callers differ.
- **Expected outcomes in hot loops** (a cache miss, a lookup that is usually absent, validation of each row in a batch) are not exceptional. Return `Optional`, a result record or a list of errors; creating an exception captures a stack trace on every call.
- **Batch jobs and message listeners** have no HTTP response. The same rules apply with a different boundary: the job runner or listener container catches, logs once, and decides retry or dead-letter.
- **Code that owns the thread** (your own worker loop that exits when interrupted) may catch `InterruptedException` and return without re-interrupting, because nothing above it needs the flag; say so in a comment.
- **Validation of request bodies** with Bean Validation is handled by `ResponseEntityExceptionHandler` (`MethodArgumentNotValidException`); override `handleMethodArgumentNotValid` to add field errors to the `ProblemDetail` instead of writing a separate handler.
