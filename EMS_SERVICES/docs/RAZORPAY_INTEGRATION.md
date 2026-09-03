# Razorpay integration — setup and operations

This is the checklist for taking the exam fee from a real card. Everything in
part 1 happens in the Razorpay dashboard; everything in part 2 is configuration
this repository reads. Nothing here requires a code change.

---

## 0. How the flow actually works

Worth reading once, because it explains why some of the steps below are not
optional.

```
Candidate clicks "Pay now"
   │
   ├─► POST /api/exam-workflow/applications/{id}/payments/initiate
   │      EMS decides the fee from the certification level (never the client),
   │      creates a Razorpay ORDER, and returns { providerOrderId, providerKeyId }
   │
   ├─► Browser opens Razorpay Checkout with that order + publishable key
   │      Card details go to Razorpay's iframe. They never touch this app,
   │      which is what keeps EMS out of PCI scope.
   │
   ├─► Checkout returns { razorpay_order_id, razorpay_payment_id, razorpay_signature }
   │
   ├─► POST /api/exam-workflow/applications/{id}/payments/complete
   │      EMS recomputes HMAC_SHA256(order_id|payment_id, key_secret),
   │      then reads the payment back from Razorpay and checks the captured
   │      amount, currency and order match what it billed. Only then SUCCESS.
   │
   └─► Razorpay POSTs to /api/payments/webhooks/razorpay  (independently)
          The authoritative path. Fires even if the candidate closed the tab.
```

The browser is a courier, not a witness. A client that posts `success: true`
with no valid signature is recorded as a **failed** payment.

---

## 1. In the Razorpay dashboard

### 1.1 Activate the account

1. Sign in at <https://dashboard.razorpay.com>.
2. You start in **Test Mode** — the toggle is at the top of the left sidebar.
   Test mode works immediately and needs no KYC. Do all the integration work
   here first.
3. For **Live Mode** you must complete *Account Activation*: business type, PAN,
   GST (if registered), bank account for settlements, and a website/app URL that
   shows your pricing, terms, refund policy, and contact details. Razorpay
   reviews this — allow a few working days.

> The exam fee page and terms dialog already in this app satisfy the
> "pricing + refund policy visible" requirement reviewers look for.

### 1.2 Generate API keys

1. **Settings → API Keys** (Account & Settings → API Keys on newer dashboards).
2. Click **Generate Test Key**.
3. You get a pair:
   - **Key ID** — starts `rzp_test_…`. Publishable; the browser receives it.
   - **Key Secret** — shown **once**. Copy it now; it cannot be retrieved later,
     only regenerated.
4. Repeat in Live Mode later for the `rzp_live_…` pair.

### 1.3 Create the webhook

This is the step people skip, and it is the one that stops payments from going
missing. Without it, a candidate whose browser dies after paying is charged and
never gets access.

1. **Settings → Webhooks → Add New Webhook**.
2. **Webhook URL** — must be publicly reachable over HTTPS:
   ```
   https://<your-api-host>/api/payments/webhooks/razorpay
   ```
   For local development, expose port 8080 with a tunnel:
   ```bash
   ngrok http 8080
   # then use https://<subdomain>.ngrok-free.app/api/payments/webhooks/razorpay
   ```
3. **Secret** — invent a long random string and paste it. This is *not* your API
   key secret. Generate one with:
   ```bash
   openssl rand -hex 32
   ```
   Save it; you will set it as `RAZORPAY_WEBHOOK_SECRET`.
4. **Active Events** — tick at minimum:
   - `payment.captured`
   - `payment.failed`
5. Save. Razorpay sends a test delivery; check **Webhooks → your webhook →
   recent deliveries** for a `200`.

### 1.4 Payment methods (optional, and Live Mode only)

**You will not find this in Test Mode.** The Payment Methods tab exists only on
the live dashboard, and only for Owner / Admin / Manager roles — so on a fresh
unactivated account it is simply absent. That is expected, and nothing in the
integration depends on it. Skip this section until after activation.

Once live: **Account & Settings → Payment Methods**. UPI and cards are enabled by
default. Turn off anything you do not want to offer (international cards, EMI,
pay-later) — every extra method is another failure mode to support. A separate
**Account & Settings → Payment Configuration** (under Checkout settings) controls
the *order* methods appear in.

If you want to restrict methods before then, do it per-checkout in code instead —
this works in Test Mode today. In `EMS_UI/src/utils/razorpay.js`, add a `method`
block to the options passed to `new window.Razorpay({...})`:

```js
method: { upi: true, card: true, netbanking: true, wallet: false },
```

---

## 2. Configuration in this repository

All Razorpay settings are read from the environment. Nothing is committed.

| Variable | Required | Notes |
| --- | --- | --- |
| `RAZORPAY_ENABLED` | yes | `true` turns on the live gateway. Default `false` locally, `true` in the `prod` profile. |
| `RAZORPAY_KEY_ID` | yes | `rzp_test_…` / `rzp_live_…` |
| `RAZORPAY_KEY_SECRET` | yes | Never leaves the server. |
| `RAZORPAY_WEBHOOK_SECRET` | yes | The string from step 1.3, not the key secret. |
| `RAZORPAY_API_BASE_URL` | no | Defaults to `https://api.razorpay.com/v1`. Override only to point at a stub in tests. |

Local run:

```bash
export RAZORPAY_ENABLED=true
export RAZORPAY_KEY_ID=rzp_test_xxxxxxxxxxxx
export RAZORPAY_KEY_SECRET=xxxxxxxxxxxxxxxxxxxxxxxx
export RAZORPAY_WEBHOOK_SECRET=$(openssl rand -hex 32)   # same value as the dashboard
mvn spring-boot:run -Dspring-boot.run.profiles=h2
```

**With `RAZORPAY_ENABLED` unset or false**, the app keeps its previous simulated
provider: "Pay now" completes straight through. That is deliberate — the whole
exam workflow, and its tests, stay runnable without a gateway account.

### Database

The gateway's order id needs a column. Postgres gets it from Flyway
(`V27__add_payment_provider_order_id.sql`) on the next boot. H2 gets it from an
idempotent `ALTER TABLE` in `db/h2/schema-h2.sql`, so an existing local
`data/ems_h2_db.mv.db` is upgraded in place rather than needing a delete.

---

## 3. Testing before you go live

Stay in Test Mode and use Razorpay's test instruments. **No real money moves.**

**Cards**

| Purpose | Number | Expiry | CVV |
| --- | --- | --- | --- |
| Success | `4111 1111 1111 1111` | any future date | any 3 digits |
| Failure | `4000 0000 0000 0002` | any future date | any 3 digits |

On the 3-D Secure page that follows, click **Success** or **Failure** to choose
the outcome.

**UPI** — enter `success@razorpay` or `failure@razorpay` as the VPA.

**What to check, in this order**

1. **Happy path.** Pay with the success card. You should land on exam
   scheduling, and the application should read `paymentStatus: SUCCESS`.
2. **Declined card.** Pay with the failure card. The page should show the
   decline and the application should stay unpaid — no access granted.
3. **Abandonment.** Open Checkout, then close the modal. Nothing should be
   recorded, and clicking "Pay now" again should reopen it.
4. **The one that actually matters — closed tab.** Start a payment, complete it
   on the bank page, then **close the tab before it redirects back**. Wait for
   the webhook. The payment must still flip to SUCCESS. If it does not, your
   webhook URL or secret is wrong, and in production that is a charged candidate
   with no exam.
5. **Dashboard reconciliation.** **Transactions → Payments** in Razorpay. Each
   row's *receipt* is the EMS transaction id, and *notes* carries the
   application id — so any payment can be traced to an application without a
   lookup table.

Server-side test coverage for the verification rules lives in
`RazorpayPaymentProviderStrategyTest` — forged signature, someone else's order,
underpayment, and authorised-but-uncaptured all assert that access is refused.

---

## 4. Going live

1. Complete Account Activation and wait for approval.
2. Switch the dashboard to **Live Mode** and generate a **live** key pair.
3. Create the webhook **again** in Live Mode — test and live webhooks are
   separate. Point it at the production host, with a freshly generated secret.
4. Set `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET` and
   `RAZORPAY_ENABLED=true` in the production environment. Restart.
5. Make one real payment of the lowest fee, confirm it in the dashboard, then
   refund it from **Transactions → Payments → Refund**.
6. Check **Settings → Settlements** for your payout schedule (T+2 working days
   by default) and confirm the bank account.

**Pricing to expect:** standard Razorpay charge is 2% + GST per successful
domestic transaction (UPI is often lower or free up to a cap; international
cards ~3%). Confirm your own rate under **Settings → Pricing**, because it
determines whether the ₹999 fee nets what you assumed.

---

## 5. Operating notes

- **Refunds.** `POST /api/payments/{transactionId}/refund` (admin only) calls
  Razorpay's refund API for real when the gateway is enabled. A refund issued
  from the Razorpay dashboard instead is *not* currently mirrored back into EMS —
  refund through the app, or reconcile by hand.
- **Turning the gateway off.** Set `RAZORPAY_ENABLED=false` and restart. New
  payments fall back to the simulated provider. Payments already opened against
  a real order cannot be verified while it is off.
- **Rotating the key secret.** Generate a new pair in the dashboard, deploy the
  new values, *then* disable the old key. Doing it the other way round breaks
  verification for every payment in flight.
- **Logs.** Every rejection is logged with the transaction id — search
  `Razorpay signature rejected`, `Razorpay order mismatch`, or
  `does not match the billed amount` when a candidate reports a failed payment.
