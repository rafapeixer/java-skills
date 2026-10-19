---
description: "Concurrent fan-out on virtual threads with a per-dependency concurrency cap"
tags: [java, core]
runs: 3
max_turns: 15
allowed_tools: [Read, Glob, Grep, Skill, Write]
---
Write PriceFetcher.java for a JDK 25 service: a class with a method that fetches prices for up to 10,000 product IDs from an HTTP pricing API with java.net.http.HttpClient, concurrently, and returns them as a Map<String, BigDecimal>. The pricing API allows at most 50 concurrent requests from us. Write the file; no explanation needed.

Write `PriceFetcher.java` at the root of the working directory, not under src/ or package folders.
