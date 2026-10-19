---
description: "Sequential outbound calls bounded by one request deadline"
tags: [java, core]
runs: 3
max_turns: 15
allowed_tools: [Read, Glob, Grep, Skill, Write]
---
Write QuoteService.java for a JDK 25 service: a class with a method quote(String sku) that an HTTP request handler calls, where the whole incoming request must be answered within 2 seconds. The method calls the pricing service (GET http://pricing.internal/prices/{sku}) and then the stock service (GET http://stock.internal/stock/{sku}) with java.net.http.HttpClient, one after the other, and returns both answers in a record. JDK only. Write the file; no explanation needed.

Write `QuoteService.java` at the root of the working directory, not under src/ or package folders.
