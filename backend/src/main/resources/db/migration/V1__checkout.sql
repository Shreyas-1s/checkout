CREATE TABLE product (
 id varchar(40) PRIMARY KEY, name text NOT NULL, category text NOT NULL,
 description text NOT NULL, price bigint NOT NULL CHECK(price > 0),
 available integer NOT NULL CHECK(available >= 0)
);
INSERT INTO product VALUES
 ('mat','Performance yoga mat','MOVE','Your space to stretch, reset and go again.',129900,12),
 ('bands','Resistance band set','BUILD','Five levels. One stronger you.',79900,18),
 ('bottle','Everyday steel bottle','HYDRATE','Cold water. Long sessions. Zero distractions.',69900,15),
 ('rope','Speed skipping rope','TRAIN','Find your rhythm. Raise your game.',39900,20),
 ('roller','Recovery foam roller','RECOVER','Give your next session a better start.',99900,8);
CREATE TABLE customer_order (
 id uuid PRIMARY KEY, customer_id text NOT NULL,
 status text NOT NULL CHECK(status IN ('AWAITING_PAYMENT','CONFIRMED','EXPIRED','PAYMENT_REVIEW')),
 total bigint NOT NULL CHECK(total > 0), currency char(3) NOT NULL DEFAULT 'INR',
 created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL,
 stock_released boolean NOT NULL DEFAULT false
);
CREATE TABLE order_line (
 order_id uuid NOT NULL REFERENCES customer_order, sku_id varchar(40) NOT NULL REFERENCES product,
 name text NOT NULL, quantity integer NOT NULL CHECK(quantity BETWEEN 1 AND 10),
 unit_price bigint NOT NULL CHECK(unit_price > 0), PRIMARY KEY(order_id,sku_id)
);
CREATE TABLE payment_attempt (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES customer_order,
 status text NOT NULL CHECK(status IN ('PENDING','SUCCEEDED','FAILED','REVIEW')),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX one_pending_attempt ON payment_attempt(order_id) WHERE status='PENDING';
CREATE TABLE idempotency (
 customer_id text NOT NULL, operation text NOT NULL, key text NOT NULL,
 fingerprint text NOT NULL, response text,
 PRIMARY KEY(customer_id,operation,key)
);
CREATE TABLE webhook_event (
 id uuid PRIMARY KEY, payload_hash text NOT NULL, received_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE mock_delivery (
 attempt_id uuid PRIMARY KEY REFERENCES payment_attempt, event_id uuid NOT NULL UNIQUE,
 payload text NOT NULL, delivered boolean NOT NULL DEFAULT false,
 retries integer NOT NULL DEFAULT 0, next_delivery timestamptz NOT NULL DEFAULT now()
);
