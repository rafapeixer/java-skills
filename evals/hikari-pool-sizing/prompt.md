---
description: "HikariCP pool sized from the connection budget, fixed-size, failing fast"
tags: [java, hard]
runs: 3
max_turns: 15
allowed_tools: [Read, Glob, Grep, Skill, Write]
---
Write DatabaseConfig.java in package com.acme.orders: a Spring Boot 4.1 @Configuration that builds the HikariCP DataSource for our PostgreSQL database in code, reading the JDBC URL, user and password from environment variables. We run 6 replicas (up to 8 during rolling deploys), the DBA gave this service 200 connections in total, request handlers run on virtual threads, and a proxy in front of Postgres closes any connection older than 30 minutes. Write it as `DatabaseConfig.java` at the root of the working directory (not under src/ or package folders); no explanation needed.
