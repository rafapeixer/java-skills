package com.example.orders;

import com.example.orders.Dtos.OrderItem;
import com.example.orders.Dtos.OrderPage;
import com.example.orders.Dtos.OrderView;
import com.example.orders.OrderRepository.OrderRow;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrderService {

    private final OrderRepository repository;

    OrderService(OrderRepository repository) {
        this.repository = repository;
    }

    public long create(long customerId, List<OrderItem> items) {
        return repository.insert(customerId, items);
    }

    // readOnly transactions keep each request to a single pool checkout for both queries.
    @Transactional(readOnly = true)
    public OrderView get(long id) {
        OrderRow row = repository.findOrder(id).orElseThrow(() -> ApiException.notFound("order not found"));
        List<OrderItem> items = repository.findItems(List.of(id)).getOrDefault(id, List.of());
        return toView(row, items);
    }

    @Transactional(readOnly = true)
    public OrderPage listForCustomer(long customerId, Cursor after, int limit) {
        // Fetch one extra row to learn whether another page exists.
        List<OrderRow> rows = repository.findCustomerOrders(customerId, after, limit + 1);
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = rows.subList(0, limit);
        }
        Map<Long, List<OrderItem>> items = repository.findItems(rows.stream().map(OrderRow::id).toList());
        List<OrderView> orders = rows.stream()
                .map(row -> toView(row, items.getOrDefault(row.id(), List.of())))
                .toList();
        String nextCursor = "";
        if (hasMore) {
            OrderRow last = rows.getLast();
            nextCursor = new Cursor(last.createdAt(), last.id()).encode();
        }
        return new OrderPage(orders, nextCursor);
    }

    private static OrderView toView(OrderRow row, List<OrderItem> items) {
        // Instant.toString() is ISO-8601 in UTC with a 'Z' suffix, which is valid RFC 3339.
        return new OrderView(row.id(), row.customerId(), row.status(), row.createdAt().toString(), items);
    }
}
