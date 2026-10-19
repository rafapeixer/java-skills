import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// MISTAKE: a mutable context object. Any callee can change the user mid-request,
// and every thread that inherited it sees the change.
static final class RequestContext {
    String requestId;
    String userId;
}

// MISTAKE: InheritableThreadLocal for request context. Every child thread copies
// the parent's map at creation; a pooled worker created during request A keeps
// request A's context for every later task it runs.
static final InheritableThreadLocal<RequestContext> REQUEST = new InheritableThreadLocal<>();

static final ExecutorService AUDIT_POOL = Executors.newFixedThreadPool(4);

void main() throws Exception {
    handle("req-1", "user-1", "/orders/1");
    handle("req-2", "user-2", "/orders/2");
    AUDIT_POOL.shutdown();
}

static void handle(String requestId, String userId, String path) {
    var ctx = new RequestContext();
    ctx.requestId = requestId;
    ctx.userId = userId;
    // MISTAKE: set() with no remove() in a finally block. On a pooled thread the
    // next request starts with this request's user, and the value lives as long
    // as the thread does.
    REQUEST.set(ctx);

    impersonate("admin");
    // MISTAKE: the audit task runs on a pool thread created by the first request;
    // it inherited that request's context, so request 2 is audited as request 1.
    AUDIT_POOL.submit(() -> System.out.println("audit " + REQUEST.get().requestId + " " + path));
}

static void impersonate(String user) {
    // MISTAKE: a callee mutates the shared context; the caller and every later
    // reader now act as "admin", with no scope that ends the change.
    REQUEST.get().userId = user;
}
