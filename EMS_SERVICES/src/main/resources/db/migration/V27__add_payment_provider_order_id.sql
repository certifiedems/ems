-- Give a payment somewhere to keep the gateway's order id.
--
-- Until now a payment had one gateway-side identifier, provider_reference, and
-- the mock providers only ever needed one. A real gateway has two, and they
-- arrive at different moments: Razorpay mints an order when the payment is
-- initiated, and a payment id only once the candidate has actually paid.
--
-- Overloading the single column would mean the order id is destroyed by the very
-- call that needs it -- verification signs order_id|payment_id, so the value
-- being overwritten is half the input to the check that authorises the
-- overwrite. Refunds and webhook reconciliation then have no way back to the
-- order at all. So the order gets its own column and provider_reference keeps
-- its existing meaning: the settled payment reference.
--
-- Nullable, because providers without an order concept (UPI QR) and every row
-- written before this migration legitimately have none.

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS provider_order_id VARCHAR(100);

-- Webhooks identify a payment by its order, not by our transaction id, and they
-- can arrive before the browser callback does. That lookup is on the hot path of
-- every callback, so it gets an index rather than a table scan.
CREATE INDEX IF NOT EXISTS idx_payments_provider_order_id
    ON payments (provider_order_id);
