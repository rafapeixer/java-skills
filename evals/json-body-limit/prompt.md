---
description: "Size limit for JSON request bodies in Spring Boot MVC on Tomcat"
tags: [java, hard]
runs: 3
max_turns: 15
allowed_tools: [Read, Glob, Grep, Skill, Write]
---
Spring Boot 4.1 MVC on embedded Tomcat, Java 25. Our POST /orders endpoint takes a JSON body, and we must reject any request body larger than 64 KiB so a client cannot exhaust memory. Write `BodyLimits.java` in package com.acme.orders, at the root of the working directory, with whatever code is needed to enforce this limit for JSON requests. Write the file; no explanation needed.
