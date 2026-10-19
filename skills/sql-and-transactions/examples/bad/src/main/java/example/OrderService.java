package example;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

public class OrderService {
    public record Item(String sku, int qty) {}

    private final JdbcClient jdbc;
    private final HttpClient http = HttpClient.newHttpClient();

    public OrderService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void importOrders(List<List<Item>> batches) throws IOException, InterruptedException {
        for (List<Item> items : batches) {
            // MISTAKE: self-invocation. this.placeOrder skips the Spring proxy, so no
            // transaction starts: a failure leaves an order without its items.
            this.placeOrder(1, items);
        }
    }

    // MISTAKE: IOException is checked, and checked exceptions do not trigger rollback
    // by default; a failed mail call still commits the order.
    @Transactional
    public long placeOrder(long customerId, List<Item> items) throws IOException, InterruptedException {
        long id = jdbc.sql("insert into orders (customer_id, total_cents) values (?, 0) returning id")
                .param(customerId).query(Long.class).single();
        for (Item item : items) {
            // MISTAKE: one round trip per item instead of one batch.
            jdbc.sql("insert into order_items (order_id, sku, qty) values (?, ?, ?)")
                    .param(id).param(item.sku()).param(item.qty()).update();
        }
        // MISTAKE: an HTTP call inside the transaction. The connection and the row locks
        // are held for the mail service's latency, and a rollback cannot unsend the email.
        http.send(HttpRequest.newBuilder(URI.create("https://mail.internal/send"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"order_id\":" + id + "}")).build(),
                HttpResponse.BodyHandlers.discarding());
        return id;
    }

    public List<Map<String, Object>> page(String customerId, String sort, int page) {
        // MISTAKE: request text concatenated into SQL. sort=id;drop table orders-- runs.
        // MISTAKE: OFFSET reads and discards every earlier row; page 5000 scans 100,000 rows,
        // and an insert between requests shifts rows across pages.
        return jdbc.sql("select * from orders where customer_id = " + customerId
                + " order by " + sort + " limit 20 offset " + (page * 20))
                .query().listOfRows();
    }

    @Transactional
    public void reserve(String sku) {
        try {
            jdbc.sql("update stock set available = available - 1 where sku = ?").param(sku).update();
        } catch (DataAccessException e) {
            // MISTAKE: retrying one statement inside the failed transaction. PostgreSQL has
            // already aborted it, so this fails with 25P02; a 40001 or 40P01 is never recovered.
            if (e.getMessage().contains("deadlock")) {
                jdbc.sql("update stock set available = available - 1 where sku = ?").param(sku).update();
            }
        }
    }
}
