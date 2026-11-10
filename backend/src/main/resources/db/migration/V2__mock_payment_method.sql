ALTER TABLE payment_attempt ADD COLUMN method text CHECK(method IN ('UPI','CARD','NETBANKING'));
