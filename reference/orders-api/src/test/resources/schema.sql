-- The schema the service expects (SPEC.md); the service never creates or migrates it.
CREATE TABLE orders (
  id          bigserial PRIMARY KEY,
  customer_id bigint      NOT NULL,
  status      text        NOT NULL DEFAULT 'new',
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX orders_customer_created ON orders (customer_id, created_at DESC, id DESC);

CREATE TABLE order_items (
  order_id bigint NOT NULL REFERENCES orders (id),
  sku      text   NOT NULL,
  qty      int    NOT NULL CHECK (qty > 0),
  PRIMARY KEY (order_id, sku)
);
