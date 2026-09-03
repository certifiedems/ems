// ems_frontend/src/pages/exam/PaymentPage.jsx
import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  Box, Paper, Grid, Typography, Button, Alert, Stack, Checkbox, FormControlLabel,
  Link, Divider, CircularProgress
} from '@mui/material'
import { useSelector } from 'react-redux'
import { examAPI } from '../../api/examAPI'
import { userAPI } from '../../api/userAPI'
import { loadRazorpayCheckout, openRazorpayCheckout } from '../../utils/razorpay'
import PageHeader from '../../components/common/PageHeader'
import PaymentTermsDialog from '../../components/payment/PaymentTermsDialog'
import { tokens, fonts, ctaButton } from '../../styles/tokens'
import CreditCardIcon from '@mui/icons-material/CreditCardRounded'
import CheckCircleIcon from '@mui/icons-material/CheckCircleRounded'
import ShieldRoundedIcon from '@mui/icons-material/ShieldRounded'

/*
 * Razorpay is the only gateway wired to real money, so it is the only one
 * offered. The four-way chooser this replaced was picking between one gateway
 * and three simulations that settled by asserting success -- a choice that
 * looked like a payment preference and was actually a choice of whether to pay.
 *
 * Nothing is lost by removing it: the methods below are what Razorpay presents
 * inside its own checkout, so the candidate still picks how they pay. They are
 * listed here only so the page says so before the modal opens.
 */
const PROVIDER = 'RAZORPAY'
const PAYMENT_METHODS = ['UPI', 'Cards', 'Net banking', 'Wallets']

const LEVEL_AMOUNT = {
  L1: 999,
  L2: 1999,
  L3: 2499
}

const PaymentPage = () => {
  const navigate = useNavigate()
  const { applicationId } = useParams()
  const { user } = useSelector((state) => state.auth)
  const [currency] = useState('INR')
  const [processing, setProcessing] = useState(false)
  const [error, setError] = useState('')
  const [done, setDone] = useState(false)
  const [applicationLevel, setApplicationLevel] = useState('L1')
  /*
   * The fee is non-transferable and the attempt it buys can be lost to a
   * violation, so the terms are not fine print the candidate can pay past: the
   * CTA stays disabled until this is ticked.
   */
  const [termsAgreed, setTermsAgreed] = useState(false)
  const [termsOpen, setTermsOpen] = useState(false)
  /*
   * Nothing is shown until the application has been checked. A paid application
   * that renders the payment form for even a moment is an invitation to pay
   * twice, and the click is quicker than the redirect.
   */
  const [checking, setChecking] = useState(true)

  useEffect(() => {
    let mounted = true
    ;(async () => {
      try {
        const res = await userAPI.getDashboard()
        const apps = res.data.data?.examStatuses || []
        const app = apps.find((item) => String(item.applicationId) === String(applicationId))
        if (!mounted || !app) {
          return
        }
        if (app.certificationLevel) {
          setApplicationLevel(app.certificationLevel)
        }
        /*
         * One payment buys one application, and it stays bought until that
         * application reaches a pass or a fail. Landing here again — from a
         * bookmark, the back button, or a stale tab — means the candidate has
         * been sent to a step they already completed, so send them on to the one
         * they have not: their booked exam, or the booking.
         */
        if (app.paymentStatus === 'SUCCESS') {
          navigate(
            app.scheduledExamTime ? `/exam/${applicationId}` : `/exam/schedule/${applicationId}`,
            { replace: true }
          )
          return
        }
      } catch {
        // Keep default amount fallback for payment UI if dashboard fetch fails.
      } finally {
        if (mounted) setChecking(false)
      }
    })()
    return () => { mounted = false }
  }, [applicationId, navigate])

  const amount = LEVEL_AMOUNT[applicationLevel] || LEVEL_AMOUNT.L1

  const settle = async (payload) => {
    const res = await examAPI.completePayment(applicationId, payload)
    if (res.data.data?.paymentStatus !== 'SUCCESS') {
      /*
       * The server verified the gateway handshake and did not accept it. An
       * authorised-but-uncaptured payment lands here too, which is why the
       * wording points at the webhook rather than claiming a failure: the money
       * may well arrive, just not in time for this page.
       */
      throw new Error(
        'We could not confirm this payment yet. If you were charged, it will be reconciled shortly — please check your payment history before paying again.'
      )
    }
    setDone(true)
    setTimeout(() => navigate(`/exam/schedule/${applicationId}`), 1200)
  }

  const handlePay = async () => {
    setProcessing(true)
    setError('')
    try {
      // 1. Ask the server to open a payment. For a live gateway this creates the
      //    order that Checkout will be paid against; the amount and the order are
      //    both decided server-side so neither can be edited on the way through.
      const initRes = await examAPI.initiatePayment(applicationId, { provider: PROVIDER, currency })
      const initiated = initRes.data.data || {}

      // 2. A publishable key and an order id mean a real gateway is configured.
      //    Without them the backend is running its simulated provider, and the
      //    old straight-through completion is still the correct flow.
      if (!initiated.providerKeyId || !initiated.providerOrderId) {
        await settle({
          success: true,
          providerReference: initiated.transactionId || `TXN-${Date.now()}`,
        })
        return
      }

      if (!(await loadRazorpayCheckout())) {
        throw new Error('Could not reach the payment gateway. Check your connection and try again.')
      }

      // 3. Hand the payer to Razorpay. Card details go to Razorpay's frame, not
      //    to this origin.
      const result = await openRazorpayCheckout({
        keyId: initiated.providerKeyId,
        orderId: initiated.providerOrderId,
        amount: initiated.amount ?? amount,
        currency: initiated.currency || currency,
        name: 'Certified EMS Engineer',
        description: initiated.description || `${applicationLevel} certification exam fee`,
        prefill: {
          name: [user?.firstName, user?.lastName].filter(Boolean).join(' '),
          email: user?.email || '',
        },
        notes: { applicationId: String(applicationId) },
      })

      if (result.cancelled) {
        // Nothing was charged and nothing needs saying — the order stays open
        // and the same button will reopen Checkout.
        return
      }

      // 4. The browser's word is not proof. The three fields below are what the
      //    server re-signs and checks against Razorpay before granting access.
      await settle({
        success: true,
        providerReference: result.razorpay_payment_id,
        razorpayOrderId: result.razorpay_order_id,
        razorpayPaymentId: result.razorpay_payment_id,
        razorpaySignature: result.razorpay_signature,
      })
    } catch (err) {
      const message = err.response?.data?.message || err.message || 'Payment failed. Please try again.'
      if (err.response?.status === 409 && message.includes('already completed')) {
        navigate(`/exam/schedule/${applicationId}`, { replace: true })
        return
      }
      setError(message)
    } finally {
      setProcessing(false)
    }
  }

  /*
   * The link sits inside the agreement's <label>, so an uncancelled click is
   * forwarded on to the checkbox by the browser: reaching for the terms would
   * tick the box that says they have been read. Cancelling the event leaves the
   * rest of the label doing its normal job of toggling the box.
   */
  const openTerms = (e) => {
    e.preventDefault()
    e.stopPropagation()
    setTermsOpen(true)
  }

  return (
    <Box>
      <PageHeader
        title="Complete Payment"
        subtitle={`Exam application #${applicationId}`}
        breadcrumbs={[
          { label: 'Exams', to: '/exams' },
          { label: 'Payment' }
        ]}
      />

      <Grid container spacing={3} justifyContent="center">
        <Grid item xs={12} md={7}>
          <Paper sx={{ p: 4 }}>
            {checking ? (
              <Stack alignItems="center" spacing={2} sx={{ py: 6 }}>
                <CircularProgress size={28} />
                <Typography color="text.secondary">Checking payment status…</Typography>
              </Stack>
            ) : done ? (
              <Stack alignItems="center" spacing={2} sx={{ py: 4 }}>
                <CheckCircleIcon color="success" sx={{ fontSize: 64 }} />
                <Typography variant="h6" fontWeight={700}>Payment successful</Typography>
                <Typography color="text.secondary">Redirecting to exam scheduling…</Typography>
              </Stack>
            ) : (
              <>
                <Typography variant="h6" fontWeight={700} gutterBottom>
                  Payment method
                </Typography>
                {error && <Alert severity="error" sx={{ my: 2 }}>{error}</Alert>}

                {/*
                  * Read-only chips, not a control: the candidate chooses among
                  * these inside Razorpay's own window a moment from now. Shown
                  * here so the page answers "how can I pay?" before committing
                  * them to a modal.
                  */}
                <Stack
                  direction="row"
                  flexWrap="wrap"
                  useFlexGap
                  spacing={1}
                  sx={{ my: 2 }}
                >
                  {PAYMENT_METHODS.map((method) => (
                    <Box
                      key={method}
                      sx={{
                        height: 32,
                        px: 1.4,
                        display: 'flex',
                        alignItems: 'center',
                        border: `1.5px solid ${tokens.line2}`,
                        borderRadius: '9px',
                        fontFamily: fonts.mono,
                        fontSize: 11,
                        fontWeight: 700,
                        letterSpacing: '.5px',
                        color: tokens.body,
                      }}
                    >
                      {method}
                    </Box>
                  ))}
                </Stack>

                <Typography sx={{ fontSize: 12, color: tokens.muted }}>
                  Processed by Razorpay. Your card details are entered on
                  Razorpay's secure window and are never sent to this site.
                </Typography>

                <Divider sx={{ my: 2 }} />

                <Stack direction="row" justifyContent="space-between" sx={{ mb: 1 }}>
                  <Typography color="text.secondary">Certification level</Typography>
                  <Typography fontWeight={600}>{applicationLevel}</Typography>
                </Stack>

                <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
                  <Typography color="text.secondary">Amount</Typography>
                  {/* The figure being agreed to — set in the board's mono face so
                      it reads as a number, not a sentence. */}
                  <Typography sx={{ fontFamily: fonts.mono, fontWeight: 700, fontSize: 16 }}>
                    ₹{amount.toLocaleString('en-IN')}
                  </Typography>
                </Stack>

                <Stack direction="row" justifyContent="space-between" sx={{ mb: 1 }}>
                  <Typography color="text.secondary">Currency</Typography>
                  <Typography fontWeight={600}>{currency}</Typography>
                </Stack>

                <Divider sx={{ my: 2 }} />

                <FormControlLabel
                  sx={{ alignItems: 'flex-start', mr: 0, mb: 2 }}
                  control={
                    <Checkbox
                      checked={termsAgreed}
                      onChange={(e) => setTermsAgreed(e.target.checked)}
                      sx={{ pt: 0.25 }}
                    />
                  }
                  label={
                    <Typography sx={{ fontSize: 12.5, lineHeight: 1.6, color: 'text.secondary' }}>
                      I agree to the{' '}
                      {/* A span, not a button: a <label> may not contain another
                          labelable control, so the trigger carries the button
                          role instead of the element. */}
                      <Link
                        component="span"
                        role="button"
                        tabIndex={0}
                        underline="hover"
                        onClick={openTerms}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' || e.key === ' ') openTerms(e)
                        }}
                        sx={{ color: tokens.copperLt, fontWeight: 700, cursor: 'pointer' }}
                      >
                        terms &amp; conditions
                      </Link>
                      {' '}and understand that exam fees are non-transferable.
                    </Typography>
                  }
                />

                <Button
                  variant="contained" size="large" fullWidth
                  startIcon={processing ? <CircularProgress size={18} color="inherit" /> : <CreditCardIcon />}
                  disabled={processing || !termsAgreed}
                  onClick={handlePay}
                  sx={{ ...ctaButton, height: 48, fontSize: 13, letterSpacing: '.3px', textTransform: 'none' }}
                >
                  {processing ? 'Processing…' : 'Pay now'}
                </Button>

                <Stack
                  direction="row"
                  spacing={0.9}
                  alignItems="center"
                  justifyContent="center"
                  sx={{ mt: 1.75, color: tokens.muted }}
                >
                  <ShieldRoundedIcon sx={{ fontSize: 14 }} />
                  <Typography sx={{ fontSize: 11 }}>
                    Payments are encrypted and processed securely.
                  </Typography>
                </Stack>
              </>
            )}
          </Paper>
        </Grid>
      </Grid>

      <PaymentTermsDialog open={termsOpen} onClose={() => setTermsOpen(false)} />
    </Box>
  )
}

export default PaymentPage
