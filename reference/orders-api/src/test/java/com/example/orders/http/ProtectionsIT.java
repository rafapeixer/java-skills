package com.example.orders.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.orders.OrdersApiEnvironment;
import java.io.ByteArrayInputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Health check, body limit, admission control and the {"error": ...} shape of every error. */
class ProtectionsIT extends OrdersApiEnvironment {

    private static final String ORDER = "{\"customer_id\":42,\"items\":[{\"sku\":\"A1\",\"qty\":1}]}";

    @Autowired
    AdmissionFilter admission;

    @Test
    void healthzIs200() {
        Response response = get("/healthz");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("status").asString()).isEqualTo("ok");
    }

    @Test
    void bodyOver64KiBIs413() {
        Response response = post("/orders", padded(BodyLimitFilter.MAX_BODY_BYTES + 1));

        assertThat(response.status()).isEqualTo(413);
        assertThat(response.error()).isEqualTo("request body too large");
    }

    @Test
    void chunkedBodyOver64KiBIs413() {
        byte[] body = padded(BodyLimitFilter.MAX_BODY_BYTES + 1).getBytes(StandardCharsets.UTF_8);
        // An InputStream publisher has no length, so the client sends Transfer-Encoding: chunked.
        Response response = send(request("/orders").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))));

        assertThat(response.status()).isEqualTo(413);
        assertThat(response.error()).isEqualTo("request body too large");
    }

    @Test
    void bodyOfExactly64KiBIsAccepted() {
        assertThat(post("/orders", padded(BodyLimitFilter.MAX_BODY_BYTES)).status()).isEqualTo(201);
    }

    @Test
    void fullAdmissionIs503WithRetryAfterButHealthzStillAnswers() {
        int held = admission.permits.drainPermits(); // as if every slot were taken by a request in flight
        assertThat(held).isEqualTo(AdmissionFilter.MAX_IN_FLIGHT);
        try {
            Response shed = post("/orders", ORDER);
            assertThat(shed.status()).isEqualTo(503);
            assertThat(shed.headers().firstValue("Retry-After")).hasValue("1");
            assertThat(shed.error()).isEqualTo("server busy");

            assertThat(get("/orders/1").status()).isEqualTo(503);
            assertThat(get("/healthz").status()).isEqualTo(200); // never shed
        } finally {
            admission.permits.release(held);
        }
        assertThat(post("/orders", ORDER).status()).isEqualTo(201); // permits back: admitted again
    }

    @Test
    void frameworkErrorsUseTheErrorShapeToo() {
        Response unknownRoute = get("/nope");
        assertThat(unknownRoute.status()).isEqualTo(404);
        assertThat(unknownRoute.error()).isEqualTo("not found");

        Response wrongMethod = send(request("/orders").DELETE());
        assertThat(wrongMethod.status()).isEqualTo(405);
        assertThat(wrongMethod.error()).isNotBlank();

        Response wrongType = send(request("/orders").header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(ORDER)));
        assertThat(wrongType.status()).isEqualTo(415);
        assertThat(wrongType.error()).isNotBlank();

        Response malformed = post("/orders", "{\"customer_id\":");
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.error()).isEqualTo("malformed JSON body");
    }

    /** A valid order padded with trailing spaces to exactly {@code bytes} bytes. */
    private static String padded(int bytes) {
        return ORDER + " ".repeat(bytes - ORDER.length());
    }
}
