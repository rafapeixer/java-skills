package example;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;

public class CheckoutService {
    public record Receipt(String id, boolean approved) {}

    public interface PaymentGateway {
        Receipt charge(String customerId, long cents);
    }

    // MISTAKE: a second SDK built in application code while the agent is attached. Its spans
    // bypass the agent's exporter and configuration (this one has no exporter at all), and its
    // default Resource names the service unknown_service:java.
    private static final Tracer TRACER = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder()
                    // MISTAKE: ratio without parentBased. This service drops spans of traces the
                    // caller kept and keeps spans of traces it dropped: broken traces either way.
                    .setSampler(Sampler.traceIdRatioBased(0.1))
                    .build())
            .build()
            .getTracer("checkout");

    private final MeterRegistry registry;
    private final PaymentGateway gateway;

    public CheckoutService(MeterRegistry registry, PaymentGateway gateway) {
        this.registry = registry;
        this.gateway = gateway;
    }

    public Receipt charge(String requestUri, String customerId, String method, long cents) {
        // MISTAKE: the id in the span name. Every customer becomes its own operation, so the
        // backend cannot group or compute p99 per endpoint.
        Span span = TRACER.spanBuilder("charge " + customerId).startSpan();
        long start = System.nanoTime();
        Receipt receipt = gateway.charge(customerId, cents);
        // MISTAKE: the customer id and the raw URI (/checkout/42/pay) as metric tags. Each value
        // is a new time series with its own histogram buckets; a million customers is a million
        // series, and a scanner probing random URIs adds more.
        registry.timer("payment.charge.duration",
                        "customer", customerId, "uri", requestUri, "method", method)
                .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        // MISTAKE: end() only on the happy path. An exception from the gateway leaves the span
        // open: never exported, no error status, and the failure is invisible in the trace.
        span.end();
        return receipt;
    }
}
