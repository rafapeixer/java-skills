package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.InputStream;
import java.net.Socket;
import java.time.Duration;
import org.apache.coyote.AbstractProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

// connection-timeout is lowered here only so the idle-connection test takes one second.
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "server.tomcat.connection-timeout=1s")
@AutoConfigureRestTestClient
class OrdersApplicationTest {

    static final String ORDER = "{\"sku\":\"A-1\",\"quantity\":2}";

    @Autowired
    RestTestClient client;

    @LocalServerPort
    int port;

    @Autowired
    WebServerApplicationContext context;

    @Autowired
    AdmissionFilter admission;

    @Test
    void acceptBacklogIsRaisedFromTheDefault100() {
        var connector = ((TomcatWebServer) context.getWebServer()).getTomcat().getConnector();
        assertEquals(1024, ((AbstractProtocol<?>) connector.getProtocolHandler()).getAcceptCount());
    }

    @Test
    void oversizedBodyIsRejectedAsProblemJson() {
        String big = "{\"sku\":\"" + "x".repeat(128 * 1024) + "\",\"quantity\":1}";
        client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON).body(big)
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.status").isEqualTo(413);
    }

    @Test
    void malformedJsonIsProblemJson() {
        client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON).body("{\"sku\":")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.status").isEqualTo(400);
    }

    @Test
    void defaultVersionRunsOnVirtualThread() {
        client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON).body(ORDER)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.virtualThread").isEqualTo(true);
    }

    @Test
    void versionHeaderSelectsHandlerAndUnknownVersionIsProblemJson() {
        client.post().uri("/orders").header("API-Version", "2")
                .contentType(MediaType.APPLICATION_JSON).body(ORDER)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.apiVersion").isEqualTo(2);
        client.post().uri("/orders").header("API-Version", "9")
                .contentType(MediaType.APPLICATION_JSON).body(ORDER)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void fullAdmissionLimitAnswers503AtOnceButProbesStillAnswer() {
        int held = admission.permits.drainPermits(); // every slot taken by in-flight requests
        assertEquals(256, held);
        try {
            client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON).body(ORDER)
                    .exchange()
                    .expectStatus().isEqualTo(503)
                    .expectHeader().valueEquals("Retry-After", "1")
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody().jsonPath("$.status").isEqualTo(503);
            client.get().uri("/livez").exchange().expectStatus().isOk(); // outside admission
        } finally {
            admission.permits.release(held);
        }
        client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON).body(ORDER)
                .exchange()
                .expectStatus().isOk(); // permits back: admitted again
    }

    @Test
    void probesAreOnTheMainPort() {
        client.get().uri("/livez").exchange().expectStatus().isOk();
        client.get().uri("/readyz").exchange().expectStatus().isOk();
    }

    @Test
    void idleConnectionIsClosedByTheServer() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Socket socket = new Socket("localhost", port);
                    InputStream in = socket.getInputStream()) {
                assertEquals(-1, in.read()); // sent nothing: Tomcat drops us after connection-timeout
            }
        });
    }
}
