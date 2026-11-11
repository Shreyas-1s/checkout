CREATE TABLE agent_action_audit (
 action_id uuid PRIMARY KEY,
 action text NOT NULL CHECK(action='RETRY_FAILED_PAYMENT'),
 order_id uuid NOT NULL REFERENCES customer_order,
 attempt_id uuid NOT NULL REFERENCES payment_attempt,
 reason text NOT NULL,
 approval_status text NOT NULL CHECK(approval_status='APPROVED'),
 approved_by text NOT NULL,
 approved_at timestamptz NOT NULL,
 executed_by text NOT NULL,
 executed_at timestamptz NOT NULL DEFAULT now()
);
