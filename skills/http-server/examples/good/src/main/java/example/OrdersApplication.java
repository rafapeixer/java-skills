package example;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class OrdersApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrdersApplication.class, args);
    }

    record Order(String sku, int quantity) {}

    @RestController
    static class OrderController {

        // One path, two versions, picked from the API-Version header (spring.mvc.apiversion.*).
        @PostMapping(path = "/orders", version = "1")
        Map<String, Object> createV1(@RequestBody Order order) {
            return Map.of("sku", order.sku(), "virtualThread", Thread.currentThread().isVirtual());
        }

        @PostMapping(path = "/orders", version = "2")
        Map<String, Object> createV2(@RequestBody Order order) {
            return Map.of("sku", order.sku(), "quantity", order.quantity(), "apiVersion", 2);
        }
    }
}
