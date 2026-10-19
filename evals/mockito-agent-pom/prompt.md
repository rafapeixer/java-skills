---
description: "Maven test setup that loads Mockito as a Java agent on JDK 25"
tags: [java, hard]
runs: 3
max_turns: 15
allowed_tools: [Read, Glob, Grep, Skill, Write]
---
Write pom.xml for a JDK 25 Maven library (groupId com.acme, artifactId pricing, version 1.0.0, no parent). Its unit tests use JUnit and Mockito (mockito-core and mockito-junit-jupiter) and mock final classes. Pin every dependency and plugin version. Write the file; no explanation needed.
