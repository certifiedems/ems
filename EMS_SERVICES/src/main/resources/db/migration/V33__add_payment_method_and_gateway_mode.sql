-- Record how each payment was paid, and whether it was real money.
--
-- payment_method / payment_method_detail: what the payer chose inside the
-- gateway's checkout (UPI, CARD, NETBANKING, WALLET, ...) and the instrument
-- within it (bank, wallet, UPI id, card network and last four). Only the gateway
-- knows this, so both stay NULL until it reports on the payment, and for every
-- simulated payment.
--
-- gateway_mode: LIVE, TEST or SIMULATED, stamped when the payment is opened from
-- the keys in force at that moment. It cannot be derived later -- keys are
-- swapped between test and live without touching existing rows, and Razorpay's
-- payment object has no mode flag to read back.

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS payment_method VARCHAR(30);

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS payment_method_detail VARCHAR(100);

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS gateway_mode VARCHAR(20);

ALTER TABLE payments
    DROP CONSTRAINT IF EXISTS chk_payments_gateway_mode;

ALTER TABLE payments
    ADD CONSTRAINT chk_payments_gateway_mode
    CHECK (gateway_mode IS NULL OR gateway_mode IN ('LIVE', 'TEST', 'SIMULATED'));

-- Backfill what can be known. A live gateway creates its order before the row is
-- committed (a failed order rolls the row back with it), so a payment with no
-- order id never reached a gateway: it is simulated, as is everything written
-- before V27 introduced the first real one.
--
-- Rows that do have an order id are left NULL. Whether they were taken on test
-- or live keys is not recorded anywhere; the admin console's "Check with
-- gateway" action fills it in for any order the current keys can see.
UPDATE payments
SET gateway_mode = 'SIMULATED'
WHERE gateway_mode IS NULL
  AND provider_order_id IS NULL;
