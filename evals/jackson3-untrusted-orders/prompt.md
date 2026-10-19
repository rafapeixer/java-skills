---
description: "Jackson 3 mapper for untrusted order JSON with a polymorphic payment"
tags: [java, hard]
runs: 3
max_turns: 15
allowed_tools: [Read, Glob, Grep, Skill, Write]
---
Write OrderJson.java for a JDK 25 service that uses Jackson 3.1 without Spring: one class that holds the JSON mapper and has a method `Order parse(byte[] body)` for order request bodies sent by public, untrusted clients. An order has a sku, a quantity, a placedAt instant and a payment that is either a card (last4) or a pix (key); put the types in the same file. Write the file; no explanation needed.

Write `OrderJson.java` at the root of the working directory, not under src/ or package folders.
