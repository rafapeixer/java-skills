---
description: "Order write plus Kafka event that survives rollbacks, crashes and several replicas"
tags: [java, hard]
runs: 3
max_turns: 15
allowed_tools: [Read, Glob, Grep, Skill, Write]
---
Write OrderEvents.java for a Spring Boot 4.1 service on PostgreSQL (JDK 25, Spring JdbcClient, KafkaTemplate<String, String>): a service method placeOrder(long customerId, long totalCents) that inserts the order and makes sure an order.placed event with the order id reaches the Kafka topic orders, plus the scheduled component that gets the event to Kafka. The service runs as three replicas, Kafka is sometimes unavailable for minutes, and no event may be lost or sent for an order that was rolled back. Put every class in that one file (nested static classes are fine) and include the SQL it needs as a comment or string. Write the file; no explanation needed.

Write `OrderEvents.java` at the root of the working directory, not under src/ or package folders.
