---
description: "Dockerfile for a Java 25 service in a 1 CPU / 1Gi Kubernetes pod sizes the heap from the limit"
tags: [java, hard]
runs: 3
max_turns: 15
allowed_tools: [Read, Glob, Grep, Skill, Write]
---
Write Dockerfile for our Spring Boot 4.1 service on Java 25. Maven builds target/orders-api.jar. It runs in Kubernetes with limits of 1 CPU and 1Gi of memory. Write the file; no explanation needed.
