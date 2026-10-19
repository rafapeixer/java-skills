package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class TelemetryTest {
    final InMemorySpanExporter spans = InMemorySpanExporter.create();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    OpenTelemetrySdk sdk(Sampler sampler) {
        return OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .setSampler(sampler)
                        .addSpanProcessor(SimpleSpanProcessor.create(spans))
                        .build())
                .build();
    }

    @Test
    void chargeRecordsOneSpanAndOneLowCardinalityTimer() {
        var checkout = new CheckoutService(sdk(Sampler.alwaysOn()), registry,
                (customer, cents) -> new CheckoutService.Receipt("r-1", true));
        checkout.charge("customer-42", CheckoutService.Method.PIX, 1_000);

        SpanData span = spans.getFinishedSpanItems().getFirst();
        assertEquals("payment.charge", span.getName());
        assertEquals("pix", span.getAttributes().get(AttributeKey.stringKey("payment.method")));
        assertEquals("customer-42", span.getAttributes().get(AttributeKey.stringKey("customer.id")));
        assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
        Timer timer = registry.get("payment.charge.duration").tag("method", "pix").tag("outcome", "approved").timer();
        assertEquals(1, timer.count());
    }

    @Test
    void failureEndsTheSpanWithErrorStatusAndCountsAsError() {
        var checkout = new CheckoutService(sdk(Sampler.alwaysOn()), registry, (customer, cents) -> {
            throw new IllegalStateException("gateway timeout");
        });
        assertThrows(IllegalStateException.class, () -> checkout.charge("c-1", CheckoutService.Method.CARD, 10));

        SpanData span = spans.getFinishedSpanItems().getFirst();
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals("exception", span.getEvents().getFirst().getName());
        assertEquals(1, registry.get("payment.charge.duration").tag("outcome", "error").timer().count());
    }

    @Test
    void thousandCustomersStillMakeOneSeries() {
        var checkout = new CheckoutService(sdk(Sampler.alwaysOn()), registry,
                (customer, cents) -> new CheckoutService.Receipt("r", true));
        for (int i = 0; i < 1_000; i++) {
            checkout.charge("customer-" + i, CheckoutService.Method.CARD, 100);
        }
        assertEquals(1, registry.find("payment.charge.duration").timers().size());
    }

    @Test
    void guardCapsATagThatEscapedReview() {
        MetricsGuard.install(registry);
        for (int i = 0; i < 150; i++) {
            registry.timer("http.server.requests", "uri", "/orders/" + i).record(java.time.Duration.ofMillis(1));
        }
        assertEquals(100, registry.find("http.server.requests").timers().size());
    }

    @Test
    void parentBasedSamplerFollowsTheCallerDecision() {
        var tracer = sdk(Sampler.parentBased(Sampler.traceIdRatioBased(0.0))).getTracer("test");
        tracer.spanBuilder("new root").startSpan().end(); // ratio 0: dropped
        SpanContext sampledCaller = SpanContext.createFromRemoteParent("0af7651916cd43dd8448eb211c80319c",
                "b7ad6b7169203331", TraceFlags.getSampled(), TraceState.getDefault());
        tracer.spanBuilder("child of sampled caller")
                .setParent(Context.root().with(Span.wrap(sampledCaller)))
                .startSpan().end(); // the caller kept it, so this service keeps it too
        assertEquals(1, spans.getFinishedSpanItems().size());
        assertEquals("child of sampled caller", spans.getFinishedSpanItems().getFirst().getName());
    }

    @Test
    void agentConfigNamesTheServiceAndSamplesByRatio() throws Exception {
        var config = new Properties();
        try (Reader in = Files.newBufferedReader(Path.of("otel-agent.properties"))) {
            config.load(in);
        }
        String service = config.getProperty("otel.service.name", "");
        assertFalse(service.isBlank() || service.startsWith("unknown_service"));
        assertEquals("parentbased_traceidratio", config.getProperty("otel.traces.sampler"));
        double ratio = Double.parseDouble(config.getProperty("otel.traces.sampler.arg"));
        assertTrue(ratio > 0 && ratio <= 1, "ratio " + ratio);
    }
}
