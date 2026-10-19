package example;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public final class Dtos {

    private Dtos() {}

    /** Our API's request body. The compact constructor is the validation. */
    public record Order(String sku, int quantity, Instant placedAt, Payment payment) {
        public Order {
            if (sku == null || sku.isBlank()) {
                throw new IllegalArgumentException("sku is required");
            }
            if (quantity < 1) { // absent fails earlier: FAIL_ON_NULL_FOR_PRIMITIVES is on in 3
                throw new IllegalArgumentException("quantity must be at least 1");
            }
            Objects.requireNonNull(placedAt, "placedAt is required");
        }
    }

    /** Polymorphism by logical name from a closed list, never by class name. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Payment.Card.class, name = "card"),
        @JsonSubTypes.Type(value = Payment.Pix.class, name = "pix")
    })
    public sealed interface Payment {
        record Card(String last4) implements Payment {}

        record Pix(String key) implements Payment {}
    }

    /** Another service's payload: we do not own it, so new fields must not break us. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpstreamPrice(String sku, BigDecimal amount) {}
}
