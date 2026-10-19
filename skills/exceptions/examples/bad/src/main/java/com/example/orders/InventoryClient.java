package com.example.orders;

import java.io.IOException;

public interface InventoryClient {
    int available(String sku) throws IOException, InterruptedException;
}
