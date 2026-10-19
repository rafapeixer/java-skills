package example;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

/**
 * Business-level telemetry on top of what the agent records for HTTP, JDBC and clients.
 * In production the OpenTelemetry comes from GlobalOpenTelemetry (the agent's SDK).
 */
public class CheckoutService {
    public enum Method { CARD, PIX, BOLETO }

    public record Receipt(String id, boolean approved) {}

    public interface PaymentGateway {
        Receipt charge(String customerId, long cents);
    }

    private final Tracer tracer;
    private final MeterRegistry registry;
    private final PaymentGateway gateway;

    public CheckoutService(OpenTelemetry otel, MeterRegistry registry, PaymentGateway gateway) {
        this.tracer = otel.getTracer("example.checkout");
        this.registry = registry;
        this.gateway = gateway;
    }

    public Receipt charge(String customerId, Method method, long cents) {
        // A fixed span name; the ids go in attributes, which are stored per span.
        Span span = tracer.spanBuilder("payment.charge")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("payment.method", method.name().toLowerCase())
                .setAttribute("customer.id", customerId)
                .startSpan();
        Timer.Sample sample = Timer.start(registry);
        String outcome = "error";
        try (Scope ignored = span.makeCurrent()) {
            Receipt receipt = gateway.charge(customerId, cents);
            outcome = receipt.approved() ? "approved" : "declined";
            return receipt;
        } catch (RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getClass().getSimpleName());
            throw e;
        } finally {
            // Rate, errors and duration from one timer; every tag value is from a closed set.
            sample.stop(Timer.builder("payment.charge.duration")
                    .tag("method", method.name().toLowerCase())
                    .tag("outcome", outcome)
                    .register(registry));
            span.end();
        }
    }
}
