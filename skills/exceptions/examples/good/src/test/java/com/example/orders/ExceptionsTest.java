package com.example.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.ConnectException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ExceptionsTest {
    private InventoryClient inventory = sku -> 5;

    private MockMvc mvc() {
        OrderService service = new OrderService(sku -> inventory.available(sku));
        return MockMvcBuilders.standaloneSetup(new OrdersController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void missingOrderIs404ProblemDetail() throws Exception {
        mvc().perform(get("/orders/nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Order not found"))
                .andExpect(jsonPath("$.detail").value("order nope not found"))
                .andExpect(jsonPath("$.instance").value("/orders/nope"));
    }

    @Test
    void insufficientStockIs409() throws Exception {
        mvc().perform(post("/orders").param("sku", "A1").param("quantity", "9"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("sku A1: requested 9, available 5"));
    }

    @Test
    void dependencyFailureIs503AndKeepsTheCause() throws Exception {
        inventory = sku -> { throw new ConnectException("connection refused"); };
        mvc().perform(post("/orders").param("sku", "A1").param("quantity", "1"))
                .andExpect(status().isServiceUnavailable());

        assertThatThrownBy(() -> new OrderService(inventory).place("A1", 1))
                .isInstanceOf(InventoryUnavailableException.class)
                .hasMessageContaining("check stock for sku A1")
                .hasCauseInstanceOf(ConnectException.class);
    }

    @Test
    void unexpectedFailureIs500WithoutInternals() throws Exception {
        inventory = sku -> { throw new IllegalStateException("jdbc:postgresql://db:5432 password=s3cret"); };
        String body = mvc().perform(post("/orders").param("sku", "A1").param("quantity", "1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("unexpected error"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("s3cret").doesNotContain("postgresql");
    }

    @Test
    void springMvcErrorsAreProblemDetailsToo() throws Exception {
        mvc().perform(post("/orders").param("sku", "A1").param("quantity", "lots"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void interruptIsRestored() {
        inventory = sku -> { throw new InterruptedException(); };
        assertThatThrownBy(() -> new OrderService(inventory).place("A1", 1))
                .isInstanceOf(InventoryUnavailableException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void createdOrderCanBeRead() throws Exception {
        MockMvc mvc = mvc();
        String id = com.jayway.jsonpath.JsonPath.read(
                mvc.perform(post("/orders").param("sku", "A1").param("quantity", "2"))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(get("/orders/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.quantity").value(2));
    }

}
