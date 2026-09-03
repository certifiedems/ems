// ems_frontend/src/utils/razorpay.js
//
// Razorpay Checkout must be loaded from Razorpay's own domain — it is not
// distributable via npm, and the widget it opens has to be served by them for
// card data to stay outside our origin (which is the whole point: the card
// number never touches this app, so this app never comes into PCI scope).
//
// The script is fetched on demand rather than from index.html so the ~100KB and
// the third-party connection are paid for by the one page that needs them,
// instead of by every visitor on first paint.

const CHECKOUT_SCRIPT_URL = 'https://checkout.razorpay.com/v1/checkout.js'

// Module-scoped so concurrent callers and re-renders share one load rather than
// racing to append duplicate <script> tags.
let loadPromise = null

export const loadRazorpayCheckout = () => {
  if (window.Razorpay) {
    return Promise.resolve(true)
  }
  if (loadPromise) {
    return loadPromise
  }

  loadPromise = new Promise((resolve) => {
    const existing = document.querySelector(`script[src="${CHECKOUT_SCRIPT_URL}"]`)
    if (existing) {
      existing.addEventListener('load', () => resolve(Boolean(window.Razorpay)))
      existing.addEventListener('error', () => resolve(false))
      return
    }

    const script = document.createElement('script')
    script.src = CHECKOUT_SCRIPT_URL
    script.async = true
    script.onload = () => resolve(Boolean(window.Razorpay))
    script.onerror = () => {
      // Cleared so a later attempt can retry: a blocked or flaky first load
      // should not permanently disable payment for the session.
      loadPromise = null
      resolve(false)
    }
    document.body.appendChild(script)
  })

  return loadPromise
}

/**
 * Opens Checkout and resolves with what the gateway handed back.
 *
 * Resolves `{ cancelled: true }` when the payer dismisses the modal, because
 * backing out is an ordinary outcome and not an error the page should shout
 * about. Genuine gateway failures reject.
 */
export const openRazorpayCheckout = ({ keyId, orderId, amount, currency, name, description, prefill, notes }) =>
  new Promise((resolve, reject) => {
    if (!window.Razorpay) {
      reject(new Error('Razorpay Checkout is unavailable'))
      return
    }

    let settled = false
    const settle = (fn, value) => {
      if (settled) return
      settled = true
      fn(value)
    }

    // Checkout keeps the modal open after a declined card so the payer can try
    // another method. Settling on the first failure would therefore abandon a
    // payment that is still very much in progress, so the failure is only
    // remembered here and reported if the payer then gives up and closes.
    let lastError = null

    const checkout = new window.Razorpay({
      key: keyId,
      // Paise. The server created the order in the same unit, and Checkout
      // shows this figure to the payer — a mismatch here is a mis-stated price.
      amount: Math.round(Number(amount) * 100),
      currency,
      name,
      description,
      order_id: orderId,
      prefill,
      notes,
      theme: { color: '#C08A2E' },
      handler: (response) => settle(resolve, { cancelled: false, ...response }),
      modal: {
        ondismiss: () =>
          lastError ? settle(reject, lastError) : settle(resolve, { cancelled: true }),
      },
    })

    // Card declines and bank-side failures arrive as an event rather than as a
    // thrown error; without this listener the reason would be lost and a
    // declined payment would read as a plain cancellation.
    checkout.on('payment.failed', (event) => {
      lastError = new Error(event?.error?.description || 'The payment could not be completed.')
    })

    checkout.open()
  })
