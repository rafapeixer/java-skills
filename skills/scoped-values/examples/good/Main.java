import java.util.NoSuchElementException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

// Request context: immutable, bound once per request at the edge, read anywhere below.
record RequestContext(String requestId, String userId) {}

static final ScopedValue<RequestContext> REQUEST = ScopedValue.newInstance();

void main() throws Exception {
    var ctx = new RequestContext("req-42", "user-7");

    // The binding exists only while handle() runs, on this thread.
    String response = ScopedValue.where(REQUEST, ctx).call(() -> handle("/orders/1"));
    check(response.equals("req-42 user-7 /orders/1 audit=req-42 worker=req-42"), response);

    // After the scope ends the value is gone: nothing to remove, nothing to leak.
    check(!REQUEST.isBound(), "REQUEST still bound after the scope");
    try {
        REQUEST.get();
        throw new AssertionError("get() outside the scope must throw");
    } catch (NoSuchElementException expected) {
        // unbound, as it should be
    }

    // A nested binding shadows the outer one for its callees only, then reverts.
    ScopedValue.where(REQUEST, ctx).run(() -> {
        var system = new RequestContext("req-42", "system");
        String inner = ScopedValue.where(REQUEST, system).call(() -> REQUEST.get().userId());
        check(inner.equals("system"), inner);
        check(REQUEST.get().userId().equals("user-7"), "outer binding was not restored");
    });
    System.out.println("ok: " + response);
}

static String handle(String path) throws Exception {
    RequestContext ctx = REQUEST.get();
    return ctx.requestId() + " " + ctx.userId() + " " + path
            + " audit=" + audit() + " worker=" + inWorker();
}

static String audit() {
    // Deep in the call stack, no parameter threading needed.
    return REQUEST.orElse(new RequestContext("none", "none")).requestId();
}

static String inWorker() throws Exception {
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
}

static void check(boolean ok, String what) {
    if (!ok) {
        throw new AssertionError(what);
    }
}
