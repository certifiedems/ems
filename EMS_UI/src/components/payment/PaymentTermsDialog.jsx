// ems_frontend/src/components/payment/PaymentTermsDialog.jsx
import PropTypes from 'prop-types'
import {
  Dialog, DialogContent, DialogActions, Button,
  Box, Typography, IconButton, Stack
} from '@mui/material'
import CloseIcon from '@mui/icons-material/CloseRounded'
import { tokens, fonts, ctaButton } from '../../styles/tokens'
import { SUPPORT_EMAIL } from '../../config/support'

/*
 * The terms the checkbox on the payment screen is agreeing to.
 *
 * Standard payment-agreement clauses — authorisation, taxes, refunds, failed
 * and duplicate debits, chargebacks, PCI handling, receipts — narrowed to what
 * this product actually does. Clauses 3, 4 and 6 restate rules enforced
 * elsewhere in the app (the one-sitting fee, the free unlimited reschedule and
 * ±10 minute window on the scheduling screen, the violation policy that
 * terminates an attempt), so a candidate who reads this and then reads those
 * screens is told the same thing twice rather than two different things. Keep
 * them in step if that behaviour changes.
 *
 * Not legal advice: this is the working default, and it is worth a review by
 * whoever owns the commercial terms before it is relied on in a dispute.
 */
const TERMS = [
  {
    title: 'Fees and authorisation',
    body: 'Submitting this payment authorises the selected payment provider to debit the amount shown on this screen. The certification level, amount and currency are displayed for confirmation before the transaction is placed.'
  },
  {
    title: 'Currency and taxes',
    body: 'All amounts are billed in Indian Rupees and are inclusive of applicable taxes at the time of the transaction. Any foreign exchange, cross-border or convenience charge levied by your bank or card issuer is additional and is borne by you.'
  },
  {
    title: 'What the fee covers',
    body: 'The fee entitles you to one exam attempt against this application only. It is not transferable to another application, another candidate, or another certification level, and it cannot be redeemed as credit against a future attempt.'
  },
  {
    title: 'Rescheduling and missed slots',
    body: 'Rescheduling is free and unlimited until the attempt begins. The booked slot opens ten minutes before the scheduled time and closes ten minutes after it; a slot missed within that window may be re-booked at no additional charge.'
  },
  {
    title: 'Refunds and cancellation',
    body: 'The fee is non-refundable once the attempt has started or the application has been assessed. Where a refund is approved it is credited only to the original payment instrument, and is subject to the processing timelines of the payment provider.'
  },
  {
    title: 'Attempts ended for policy violations',
    body: 'An attempt terminated under the exam violation policy is treated as consumed. No refund, credit or replacement attempt is issued in respect of a terminated attempt.'
  },
  {
    title: 'Failed and duplicate transactions',
    body: 'If an amount is debited but the payment is not confirmed, it is reversed to the original payment instrument, ordinarily within five to seven business days. Do not re-attempt payment until the status of the earlier transaction is confirmed; duplicate charges are refunded on verification.'
  },
  {
    title: 'Chargebacks and disputes',
    body: `Raise any dispute with support at ${SUPPORT_EMAIL} first, quoting the transaction reference, so that it can be investigated. A chargeback raised with your bank without prior contact may result in the associated application being held until the dispute is resolved.`
  },
  {
    title: 'Payment security',
    body: 'Payments are processed over encrypted connections by third-party providers. Card numbers, banking credentials and UPI PINs are never collected, seen or stored by this platform; only the transaction reference, amount, status and timestamp are retained against your account.'
  },
  {
    title: 'Receipts and records',
    body: 'A receipt is generated for every successful payment and can be downloaded at any time from the payments section of your profile. Please retain it as proof of payment.'
  },
  {
    title: 'Changes to fees',
    body: 'Examination and certification fees may be revised from time to time. The amount displayed at the moment of payment is the amount that applies to that transaction; revisions do not affect payments already completed.'
  }
]

/** Modal statement of the payment terms, opened from the agreement checkbox. */
const PaymentTermsDialog = ({ open, onClose }) => (
  <Dialog
    open={open}
    onClose={onClose}
    maxWidth={false}
    fullWidth
    scroll="paper"
    aria-labelledby="payment-terms-title"
    PaperProps={{ sx: { width: '100%', maxWidth: 560, maxHeight: '80vh' } }}
  >
    <Box sx={{ p: '22px 26px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1.75 }}>
      <Box sx={{ minWidth: 0 }}>
        <Typography
          id="payment-terms-title"
          component="h2"
          sx={{ fontSize: 18, fontWeight: 800, letterSpacing: '-.3px' }}
        >
          Payment Terms &amp; Conditions
        </Typography>
        <Typography sx={{ mt: 0.5, fontSize: 12.5, color: '#93AC9E' }}>
          Applies to examination and certification fees
        </Typography>
      </Box>
      <IconButton
        onClick={onClose}
        aria-label="Close payment terms"
        sx={{ flex: 'none', width: 30, height: 30, borderRadius: '8px', background: 'rgba(95,174,146,.1)' }}
      >
        <CloseIcon sx={{ fontSize: 14 }} />
      </IconButton>
    </Box>

    <DialogContent sx={{ px: '26px', pt: 0, pb: 2.5, borderTop: `1px solid ${tokens.line}` }}>
      <Stack spacing={2.25} sx={{ pt: 2.5 }}>
        {TERMS.map((term, index) => (
          <Stack key={term.title} direction="row" spacing={1.5} alignItems="flex-start">
            {/* Numbered rather than bulleted: clauses in an agreement need to be
                citable, and "clause 7" is how a support ticket refers to one. */}
            <Box
              aria-hidden
              sx={{
                flex: 'none',
                fontFamily: fonts.mono,
                fontSize: 11,
                fontWeight: 700,
                lineHeight: '20px',
                color: tokens.copperLt
              }}
            >
              {String(index + 1).padStart(2, '0')}
            </Box>
            <Box sx={{ minWidth: 0 }}>
              <Typography sx={{ fontSize: 12.5, fontWeight: 800, lineHeight: '20px', color: tokens.ink }}>
                {term.title}
              </Typography>
              <Typography sx={{ mt: 0.4, fontSize: 12.5, lineHeight: 1.6, color: '#CFE2D8' }}>
                {term.body}
              </Typography>
            </Box>
          </Stack>
        ))}
      </Stack>
    </DialogContent>

    <DialogActions sx={{ p: '18px 26px', borderTop: `1px solid ${tokens.line}` }}>
      <Button
        variant="contained"
        onClick={onClose}
        sx={{ ...ctaButton, width: 'auto', height: 38, px: 2.25, fontSize: 12, letterSpacing: '.3px', textTransform: 'none' }}
      >
        Close
      </Button>
    </DialogActions>
  </Dialog>
)

PaymentTermsDialog.propTypes = {
  open: PropTypes.bool.isRequired,
  onClose: PropTypes.func.isRequired
}

export default PaymentTermsDialog
