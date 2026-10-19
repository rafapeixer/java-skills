package example;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;

/** A backstop for a tag that escaped review: cap its distinct values, then drop new series. */
public final class MetricsGuard {
    private MetricsGuard() {}

    public static void install(MeterRegistry registry) {
        registry.config()
                .meterFilter(MeterFilter.maximumAllowableTags("http.server.requests", "uri", 100, MeterFilter.deny()))
                .meterFilter(MeterFilter.maximumAllowableTags("payment.", "method", 10, MeterFilter.deny()));
    }
}
