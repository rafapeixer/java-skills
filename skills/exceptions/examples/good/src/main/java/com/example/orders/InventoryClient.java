package com.example.orders;

import java.io.IOException;

/** Remote stock lookup; throws what HttpClient.send throws. */
public interface InventoryClient {
    int available(String sku) throws IOException, InterruptedException;
}
