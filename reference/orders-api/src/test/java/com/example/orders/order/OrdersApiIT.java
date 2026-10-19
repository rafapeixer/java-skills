package com.example.orders.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.orders.OrdersApiEnvironment;
import com.example.orders.PricingStub;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** The API contract of SPEC.md, against PostgreSQL 17 and the stub pricing server. */
class OrdersApiIT extends OrdersApiEnvironment {

    /** Each test uses its own customers, so tests never see each other's orders. */
    private static final AtomicLong CUSTOMERS = new AtomicLong(1_000);

    private static long newCustomer() {
        return CUSTOMERS.incrementAndGet();
    }

    // POST /orders

    @Test
    void createReturns201WithIdAndLocation() {
        Response created = post("/orders", "{\"customer_id\":42,\"items\":[{\"sku\":\"A1\",\"qty\":2}]}");

        assertThat(created.status()).isEqualTo(201);
        long id = created.json().get("id").asLong();
        assertThat(id).isPositive();
        assertThat(created.headers().firstValue("Location")).hasValue("/orders/" + id);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"items\":[{\"sku\":\"A1\",\"qty\":1}]}",                                   // no customer_id
        "{\"customer_id\":0,\"items\":[{\"sku\":\"A1\",\"qty\":1}]}",                 // customer_id not > 0
        "{\"customer_id\":-5,\"items\":[{\"sku\":\"A1\",\"qty\":1}]}",
        "{\"customer_id\":42}",                                                         // no items
        "{\"customer_id\":42,\"items\":[]}",                                            // 0 items
        "{\"customer_id\":42,\"items\":[null]}",
        "{\"customer_id\":42,\"items\":[{\"qty\":1}]}",                                 // no sku
        "{\"customer_id\":42,\"items\":[{\"sku\":\"\",\"qty\":1}]}",                    // empty sku
        "{\"customer_id\":42,\"items\":[{\"sku\":\"A1\"}]}",                            // no qty
        "{\"customer_id\":42,\"items\":[{\"sku\":\"A1\",\"qty\":0}]}",                  // qty < 1
        "{\"customer_id\":42,\"items\":[{\"sku\":\"A1\",\"qty\":1},{\"sku\":\"A1\",\"qty\":2}]}", // duplicate sku
        "{\"customer_id\":42,\"items\":[{\"sku\":\"A1\",\"qty\":1}],\"note\":\"x\"}",   // unknown field
        "{\"customer_id\":42,\"items\":[{\"sku\":\"A1\",\"qty\":1}]",                   // malformed JSON
        "[]",
    })
    void invalidOrderIs400WithErrorBody(String body) {
        Response response = post("/orders", body);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.error()).isNotBlank();
    }

    @Test
    void moreThan100ItemsIs400And100IsAccepted() {
        assertThat(post("/orders", order(newCustomer(), items(101))).status()).isEqualTo(400);
        assertThat(post("/orders", order(newCustomer(), items(100))).status()).isEqualTo(201);
    }

    @Test
    void rejectedOrderWritesNothing() {
        long customer = newCustomer();
        post("/orders", "{\"customer_id\":" + customer + ",\"items\":[{\"sku\":\"A1\",\"qty\":0}]}");

        assertThat(get("/customers/" + customer + "/orders").json().get("orders")).isEmpty();
    }

    // GET /orders/{id}

    @Test
    void getReturnsTheOrderWithItsItems() {
        long customer = newCustomer();
        long id = createOrder(customer, "[{\"sku\":\"B2\",\"qty\":3},{\"sku\":\"A1\",\"qty\":2}]");

        Response response = get("/orders/" + id);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                v -> assertThat(v).startsWith("application/json"));
        JsonNode order = response.json();
        assertThat(order.get("id").asLong()).isEqualTo(id);
        assertThat(order.get("customer_id").asLong()).isEqualTo(customer);
        assertThat(order.get("status").asString()).isEqualTo("new");
        // RFC 3339: parses as an offset date-time.
        assertThat(OffsetDateTime.parse(order.get("created_at").asString()))
                .isBetween(OffsetDateTime.now().minusMinutes(5), OffsetDateTime.now().plusMinutes(5));
        assertThat(items(order)).containsExactlyInAnyOrder("A1x2", "B2x3");
    }

    @Test
    void unknownOrderIs404() {
        Response response = get("/orders/999999999");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.error()).isEqualTo("order not found");
    }

    @Test
    void nonNumericOrderIdIs400() {
        Response response = get("/orders/abc");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.error()).isNotBlank();
    }

    // GET /customers/{id}/orders

    @Test
    void pagesAreNewestFirstAndTheLastPageHasAnEmptyCursor() {
        long customer = newCustomer();
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(createOrder(customer, "[{\"sku\":\"A1\",\"qty\":" + (i + 1) + "}]"));
        }
        long other = createOrder(newCustomer(), "[{\"sku\":\"A1\",\"qty\":1}]"); // never listed

        List<Long> seen = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String cursor = "";
        do {
            Response page = get("/customers/" + customer + "/orders?limit=2&cursor=" + cursor);
            assertThat(page.status()).isEqualTo(200);
            JsonNode orders = page.json().get("orders");
            pageSizes.add(orders.size());
            for (JsonNode order : orders) {
                seen.add(order.get("id").asLong());
                assertThat(order.get("customer_id").asLong()).isEqualTo(customer);
                assertThat(order.get("items")).hasSize(1); // each order comes with its items
            }
            cursor = page.json().get("next_cursor").asString();
        } while (!cursor.isEmpty());

        assertThat(pageSizes).containsExactly(2, 2, 1);
        assertThat(seen).containsExactlyElementsOf(created.reversed()).doesNotContain(other);
    }

    @Test
    void aFullLastPageStillHasAnEmptyCursor() {
        long customer = newCustomer();
        for (int i = 0; i < 3; i++) {
            createOrder(customer, "[{\"sku\":\"A1\",\"qty\":1}]");
        }

        JsonNode page = get("/customers/" + customer + "/orders?limit=3").json();

        assertThat(page.get("orders")).hasSize(3);
        assertThat(page.get("next_cursor").asString()).isEmpty();
    }

    @Test
    void limitDefaultsTo20() {
        long customer = newCustomer();
        for (int i = 0; i < 21; i++) {
            createOrder(customer, "[{\"sku\":\"A1\",\"qty\":1}]");
        }

        JsonNode first = get("/customers/" + customer + "/orders").json();
        assertThat(first.get("orders")).hasSize(20);
        String cursor = first.get("next_cursor").asString();
        assertThat(cursor).isNotEmpty();

        JsonNode second = get("/customers/" + customer + "/orders?cursor=" + cursor).json();
        assertThat(second.get("orders")).hasSize(1);
        assertThat(second.get("next_cursor").asString()).isEmpty();
    }

    @Test
    void limitIsBetween1And100() {
        long customer = newCustomer();
        assertThat(get("/customers/" + customer + "/orders?limit=100").status()).isEqualTo(200);
        assertThat(get("/customers/" + customer + "/orders?limit=1").status()).isEqualTo(200);

        for (String limit : List.of("0", "101", "-1", "abc")) {
            Response response = get("/customers/" + customer + "/orders?limit=" + limit);
            assertThat(response.status()).as("limit=%s", limit).isEqualTo(400);
            assertThat(response.error()).isNotBlank();
        }
    }

    @Test
    void customerWithoutOrdersGetsAnEmptyPage() {
        JsonNode page = get("/customers/" + newCustomer() + "/orders").json();

        assertThat(page.get("orders")).isEmpty();
        assertThat(page.get("next_cursor").asString()).isEmpty();
    }

    @Test
    void invalidCursorOrCustomerIs400() {
        for (String path : List.of(
                "/customers/7/orders?cursor=not-a-cursor",
                "/customers/7/orders?cursor=bm9jb2xvbg", // "nocolon"
                "/customers/0/orders",
                "/customers/abc/orders")) {
            Response response = get(path);
            assertThat(response.status()).as(path).isEqualTo(400);
            assertThat(response.error()).isNotBlank();
        }
    }

    // GET /orders/{id}/quote

    @Test
    void quoteSumsPriceTimesQtyForEveryItem() {
        long id = createOrder(newCustomer(),
                "[{\"sku\":\"A1\",\"qty\":2},{\"sku\":\"B2\",\"qty\":3},{\"sku\":\"C 3\",\"qty\":4}]");

        Response response = get("/orders/" + id + "/quote");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("order_id").asLong()).isEqualTo(id);
        assertThat(response.json().get("total_cents").asLong()).isEqualTo(2 * 1250 + 3 * 300 + 4 * 75);
    }

    @Test
    void quoteOfUnknownOrderIs404() {
        Response response = get("/orders/999999999/quote");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.error()).isEqualTo("order not found");
    }

    @Test
    void quoteWithAnSkuPricingDoesNotKnowIs422() {
        long id = createOrder(newCustomer(), "[{\"sku\":\"A1\",\"qty\":1},{\"sku\":\"NOPE\",\"qty\":1}]");

        Response response = get("/orders/" + id + "/quote");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.error()).contains("NOPE");
    }

    @Test
    void failingPricingIs503WithRetryAfter() {
        long id = createOrder(newCustomer(), "[{\"sku\":\"" + PricingStub.BROKEN + "\",\"qty\":1}]");

        Response response = get("/orders/" + id + "/quote");

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.headers().firstValue("Retry-After")).hasValue("1");
        assertThat(response.error()).isEqualTo("pricing service unavailable");
        assertThat(PRICING.hits(PricingStub.BROKEN)).isEqualTo(1); // a 500 is not retried
    }

    @Test
    void slowPricingIs503WithinTheQuoteBudget() {
        long id = createOrder(newCustomer(), "[{\"sku\":\"A1\",\"qty\":1},{\"sku\":\"" + PricingStub.SLOW + "\",\"qty\":1}]");

        long start = System.nanoTime();
        Response response = get("/orders/" + id + "/quote");
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.error()).isEqualTo("pricing service unavailable");
        // The stub answers after 3 s; the 800 ms call timeout ends the quote well inside 1.5 s.
        assertThat(took).isLessThan(QuoteService.BUDGET);
    }

    @Test
    void a503FromPricingIsRetriedOnce() {
        long id = createOrder(newCustomer(), "[{\"sku\":\"" + PricingStub.FLAKY + "\",\"qty\":2}]");

        Response response = get("/orders/" + id + "/quote");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("total_cents").asLong()).isEqualTo(200);
        assertThat(PRICING.hits(PricingStub.FLAKY)).isEqualTo(2);
    }

    private static String order(long customer, String items) {
        return "{\"customer_id\":" + customer + ",\"items\":" + items + "}";
    }

    private static String items(int n) {
        return IntStream.range(0, n).mapToObj(i -> "{\"sku\":\"S" + i + "\",\"qty\":1}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static List<String> items(JsonNode order) {
        return StreamSupport.stream(order.get("items").spliterator(), false)
                .map(i -> i.get("sku").asString() + "x" + i.get("qty").asInt())
                .toList();
    }
}
