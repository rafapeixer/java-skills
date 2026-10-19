create table if not exists reservation (
    id bigint generated always as identity primary key,
    guest text not null,
    expires_at timestamptz not null
);
