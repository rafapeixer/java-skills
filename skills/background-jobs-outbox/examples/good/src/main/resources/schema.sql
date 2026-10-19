create table orders (
    id bigint generated always as identity primary key,
    customer_id bigint not null,
    total_cents bigint not null check (total_cents > 0)
);
create table outbox (
    id bigint generated always as identity primary key,
    topic text not null,
    payload jsonb not null,
    created_at timestamptz not null default now(),
    available_at timestamptz not null default now(),
    attempts int not null default 0,
    sent_at timestamptz,
    dead_at timestamptz
);
-- Only pending rows are indexed, so the claim stays fast as sent rows pile up.
create index outbox_pending on outbox (available_at, id) where sent_at is null and dead_at is null;
-- The consumer's record of work done, keyed by the event id.
create table processed_events (
    event_id bigint primary key,
    processed_at timestamptz not null default now()
);
create table loyalty_points (customer_id bigint primary key, points bigint not null);
