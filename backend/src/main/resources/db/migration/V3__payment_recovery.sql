ALTER TABLE mock_delivery ADD COLUMN exhausted boolean NOT NULL DEFAULT false;
ALTER TABLE mock_delivery ADD COLUMN last_error text;
ALTER TABLE customer_order DROP CONSTRAINT customer_order_status_check;
ALTER TABLE customer_order ADD CONSTRAINT customer_order_status_check CHECK(status IN ('AWAITING_PAYMENT','CONFIRMED','EXPIRED','PAYMENT_REVIEW','CANCELLED'));
CREATE TABLE review_resolution (
 order_id uuid PRIMARY KEY REFERENCES customer_order,
 operator_id text NOT NULL,
 reason text NOT NULL,
 disposition text NOT NULL CHECK(disposition='MOCK_REFUND_AND_CANCEL'),
 resolved_at timestamptz NOT NULL DEFAULT now()
);
