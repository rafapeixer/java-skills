package com.example.ordersapi.pricing;

import com.example.ordersapi.Deadline;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class QuoteController {

    /** Whole-request budget: the database read plus every pricing lookup must fit in it. */
    private static final Duration BUDGET = Duration.ofSeconds(3);

    record Quote(@JsonProperty("order_id") long orderId, @JsonProperty("total_cents") long totalCents) {}

    private final QuoteService quotes;

    QuoteController(QuoteService quotes) {
        this.quotes = quotes;
    }

    @GetMapping("/orders/{id}/quote")
    Quote quote(@PathVariable("id") long id) {
        Deadline deadline = Deadline.after(BUDGET);
        return new Quote(id, quotes.totalCents(id, deadline));
    }
}
