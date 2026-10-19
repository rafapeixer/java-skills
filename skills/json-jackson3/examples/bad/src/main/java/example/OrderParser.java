package example;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

public class OrderParser {

    // MISTAKE: the Jackson 2 annotation (com.fasterxml.jackson.databind.annotation) on a type
    // read by Jackson 3. It compiles and is silently ignored: output says "unitPrice", and
    // input "unit_price" is an unknown property.
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Order(String sku, long unitPrice, Object metadata) {}

    public Order parse(String body) {
        // MISTAKE: a new mapper per call. Mappers are immutable and thread-safe; building one
        // per request rebuilds every serializer and deserializer cache each time.
        ObjectMapper mapper = JsonMapper.builder()
                // MISTAKE: default typing with a validator that allows any subtype of Object.
                // Untrusted JSON now names the class to instantiate in "metadata": a gadget chain.
                .activateDefaultTyping(BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .build(), DefaultTyping.NON_FINAL)
                // MISTAKE: no StreamReadConstraints; document length is unlimited by default and
                // one string may be 100,000,000 chars, so one request can take the heap.
                .build();
        try {
            return mapper.readValue(body, Order.class);
        } catch (JacksonException e) {
            // MISTAKE: every parse error becomes null; the caller answers 200 with an empty
            // order, or fails later with a NullPointerException far from the cause.
            return null;
        }
    }
}
