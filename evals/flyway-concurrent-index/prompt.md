---
description: "Flyway migration that builds a PostgreSQL index on a live table"
tags: [java, hard]
runs: 3
max_turns: 15
allowed_tools: [Read, Glob, Grep, Skill, Write]
---
We use Flyway 13 with PostgreSQL 17. The `orders` table has 80 million rows and takes writes all day. Add an index on `orders (customer_id, created_at)` as the next migration, V14. Write the migration as `V14__orders_customer_created_idx.sql` and, if Flyway needs anything else for it, `V14__orders_customer_created_idx.sql.conf` and the project configuration `flyway.toml`, all at the root of the working directory. No explanation needed.
