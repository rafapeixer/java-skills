package example;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@SpringBootApplication
public class OrdersApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrdersApplication.class, args);
    }

    record Order(String sku, int quantity) {}

    @RestController
    static class OrderController {

        // MISTAKE: nothing bounds this body. max-swallow-size and max-http-form-post-size in
        // application.properties do not apply to JSON, so one 2 GB POST is read into the heap.
        @PostMapping("/v1/orders")
        Map<String, Object> createV1(@RequestBody Order order) {
            return Map.of("sku", order.sku());
        }

        // MISTAKE: a second copy of the controller per version. Framework 7 maps versions
        // on one path (version = "2" with spring.mvc.apiversion.*) and rejects unknown ones.
        @PostMapping("/v2/orders")
        Map<String, Object> createV2(@RequestBody Order order) {
            return Map.of("sku", order.sku(), "quantity", order.quantity());
        }
    }

    @RestControllerAdvice
    static class Errors {

        // MISTAKE: a catch-all that turns malformed JSON (400), unknown routes (404) and
        // 413s into 500s, in an ad hoc shape, with the raw exception message sent to clients.
        @ExceptionHandler(Exception.class)
        ResponseEntity<Map<String, String>> handle(Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }
}
