package example;

import static example.Json.MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import example.Dtos.Order;
import example.Dtos.Payment;
import example.Dtos.UpstreamPrice;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.InvalidTypeIdException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.exc.ValueInstantiationException;

class JsonTest {

    static final String ORDER = """
            {"sku":"A-1","quantity":2,"placedAt":"2026-10-18T12:00:00Z","payment":{"type":"pix","key":"k"}}""";

    @Test
    void recordsRoundTripWithIsoDates() {
        Order order = MAPPER.readValue(ORDER, Order.class);
        assertEquals(new Payment.Pix("k"), order.payment());
        String json = MAPPER.writeValueAsString(order);
        assertTrue(json.contains("\"placedAt\":\"2026-10-18T12:00:00Z\""), json); // not a timestamp
        assertEquals(order, MAPPER.readValue(json, Order.class));
    }

    @Test
    void misspelledFieldInOurContractFails() {
        String typo = ORDER.replace("\"payment\"", "\"paymnet\"");
        assertThrows(UnrecognizedPropertyException.class, () -> MAPPER.readValue(typo, Order.class));
    }

    @Test
    void upstreamPayloadMayGrowNewFields() {
        UpstreamPrice price = MAPPER.readValue(
                "{\"sku\":\"A-1\",\"amount\":9.90,\"currency\":\"BRL\"}", UpstreamPrice.class);
        assertEquals(new BigDecimal("9.90"), price.amount());
    }

    @Test
    void absentOrInvalidValuesAreRejected() {
        String missing = ORDER.replace("\"quantity\":2,", "");
        assertThrows(MismatchedInputException.class, () -> MAPPER.readValue(missing, Order.class));
        String zero = ORDER.replace("\"quantity\":2", "\"quantity\":0");
        assertThrows(ValueInstantiationException.class, () -> MAPPER.readValue(zero, Order.class));
        String noDate = ORDER.replace("\"placedAt\":\"2026-10-18T12:00:00Z\",", "");
        assertThrows(DatabindException.class, () -> MAPPER.readValue(noDate, Order.class));
    }

    @Test
    void classNamesAreNotAcceptedAsTypeIds() {
        String gadget = ORDER.replace("\"type\":\"pix\"", "\"type\":\"java.lang.ProcessBuilder\"");
        assertThrows(InvalidTypeIdException.class, () -> MAPPER.readValue(gadget, Order.class));
    }

    @Test
    void limitsRejectHugeStringsDeepNestingAndHugeDocuments() {
        String longSku = ORDER.replace("A-1", "x".repeat(200_000));
        assertThrows(StreamConstraintsException.class, () -> MAPPER.readValue(longSku, Order.class));

        String deep = "[".repeat(100) + "]".repeat(100);
        assertThrows(StreamConstraintsException.class, () -> MAPPER.readTree(deep));

        byte[] big = ("[" + "\"0123456789\",".repeat(150_000) + "1]").getBytes(StandardCharsets.UTF_8);
        assertThrows(StreamConstraintsException.class, () -> MAPPER.readTree(big));
    }

    @Test
    void everyJacksonErrorIsOneUncheckedType() {
        // Trailing content fails by default in Jackson 3 (FAIL_ON_TRAILING_TOKENS).
        for (String bad : new String[] {"{\"sku\":", ORDER + " {}", "not json"}) {
            JacksonException e = assertThrows(JacksonException.class, () -> MAPPER.readValue(bad, Order.class));
            assertTrue(e instanceof RuntimeException);
        }
        JsonNode node = MAPPER.readTree(ORDER);
        assertEquals(2, node.get("quantity").intValue());
    }
}
