create table orders (
    id bigint generated always as identity primary key,
    customer_id bigint not null,
    total_cents bigint not null,
    created_at timestamptz not null default now()
);
create index orders_customer_created_id on orders (customer_id, created_at, id);
create table order_items (
    order_id bigint not null references orders (id),
    sku text not null,
    qty int not null check (qty > 0)
);
create table outbox (
    id bigint generated always as identity primary key,
    topic text not null,
    payload jsonb not null
);
create table stock (sku text primary key, available int not null);
