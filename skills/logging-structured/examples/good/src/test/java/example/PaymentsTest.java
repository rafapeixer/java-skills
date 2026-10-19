package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class PaymentsTest {

    static final Payments.Card CARD = new Payments.Card("4111111111111111", "Ana");

    @Autowired
    Payments payments;

    @Autowired
    LoggingSystem loggingSystem;

    static List<String> paymentLines(CapturedOutput output) {
        return output.getOut().lines().filter(l -> l.contains("\"logger\":\"example.Payments\"")).toList();
    }

    @Test
    void oneJsonLinePerEventWithRequestIdAndFields(CapturedOutput output) {
        assertTrue(payments.charge("req-1", "ord-1", CARD, 500));
        assertFalse(payments.charge("req-2", "ord-2", CARD, 500_000));

        List<String> lines = paymentLines(output);
        assertEquals(2, lines.size(), "one line per event, no DEBUG, no log-and-rethrow: " + lines);
        String authorized = lines.get(0);
        assertEquals("payment authorized", JsonPath.read(authorized, "$.message"));
        assertEquals("req-1", JsonPath.read(authorized, "$.request.id")); // from the MDC
        assertEquals("ord-1", JsonPath.read(authorized, "$.order.id")); // from addKeyValue
        assertEquals(500, (int) JsonPath.read(authorized, "$.amount.cents"));
        assertEquals("payments", JsonPath.read(authorized, "$.service.name"));

        String declined = lines.get(1);
        assertEquals("WARN", JsonPath.read(declined, "$.log.level"));
        assertEquals("req-2", JsonPath.read(declined, "$.request.id"));
        assertEquals("over card limit", JsonPath.read(declined, "$.decline.reason"));

        assertNull(MDC.get("request.id"), "the request id must not leak to the next task");
        output.getOut().lines().forEach(l -> assertTrue(l.startsWith("{"), "not JSON: " + l));
    }

    @Test
    void cardNumberNeverReachesTheLogEvenAtDebug(CapturedOutput output) {
        loggingSystem.setLogLevel("example.Payments", LogLevel.DEBUG);
        try {
            payments.charge("req-3", "ord-3", CARD, 700);
        } finally {
            loggingSystem.setLogLevel("example.Payments", null);
        }
        List<String> lines = paymentLines(output);
        assertEquals(2, lines.size(), lines.toString());
        assertEquals("authorizing 700 cents on Card[last4=1111]", JsonPath.read(lines.get(0), "$.message"));
        assertFalse(output.getAll().contains(CARD.number()));
        assertFalse(output.getAll().contains(CARD.holder()));
    }
}
