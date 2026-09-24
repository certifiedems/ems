// ems_frontend/src/components/testimonial/TestimonialDialog.jsx
import { useState } from 'react'
import PropTypes from 'prop-types'
import { useSelector } from 'react-redux'
import { useForm } from 'react-hook-form'
import {
  Alert, Box, Button, Checkbox, CircularProgress, Dialog, DialogActions, DialogContent,
  IconButton, Rating, Stack, Typography,
} from '@mui/material'
import CloseIcon from '@mui/icons-material/CloseRounded'
import PcbField from '../common/PcbField'
import { testimonialAPI } from '../../api/testimonialAPI'
import { getApiErrorMessage } from '../../utils/apiError'
import { tokens, fonts, ctaButton } from '../../styles/tokens'

export const QUOTE_MIN = 20
export const QUOTE_MAX = 250

/** The name as the public site would show it for each choice, e.g. "Priya S.". */
export const nameForDisplay = (choice, firstName = '', lastName = '') => {
  const first = firstName.trim()
  const last = lastName.trim()
  if (choice === 'FULL_NAME') return `${first} ${last}`.trim()
  if (choice === 'FIRST_NAME_INITIAL') return last ? `${first} ${last[0]}.` : first
  return null
}

/**
 * The testimonial form: a rating, the quote, how to be named, and the consent
 * the backend refuses to publish without.
 *
 * The consent wording is versioned server-side (TestimonialServiceImpl
 * .CONSENT_VERSION); change that constant whenever this text changes.
 */
const TestimonialDialog = ({ open, level, onClose, onSubmitted }) => {
  const { user } = useSelector((state) => state.auth)
  const [rating, setRating] = useState(5)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  const { register, handleSubmit, watch, reset, formState: { errors } } = useForm({
    defaultValues: { quote: '', nameDisplay: 'FIRST_NAME_INITIAL', jobTitle: '', company: '', consent: false },
  })
  const quoteLength = (watch('quote') || '').trim().length
  // MUI's Checkbox forwards `ref` to its wrapper, not the <input>, so the form
  // would never see it ticked; the input ref has to go in through inputRef.
  const { ref: consentRef, ...consentField } = register('consent', {
    validate: (checked) => checked || 'Please tick the box to agree.',
  })

  const nameOptions = [
    { value: 'FIRST_NAME_INITIAL', label: `First name and initial (${nameForDisplay('FIRST_NAME_INITIAL', user?.firstName, user?.lastName) || 'e.g. Priya S.'})` },
    { value: 'FULL_NAME', label: `Full name (${nameForDisplay('FULL_NAME', user?.firstName, user?.lastName) || 'e.g. Priya Sharma'})` },
    { value: 'ANONYMOUS', label: "Don't show my name" },
  ]

  const close = () => {
    if (submitting) return
    setError('')
    onClose()
  }

  const onSubmit = async (values) => {
    setSubmitting(true)
    setError('')
    try {
      const response = await testimonialAPI.submit({
        certificationLevel: level,
        rating,
        quote: values.quote.trim(),
        nameDisplay: values.nameDisplay,
        jobTitle: values.jobTitle.trim() || null,
        company: values.company.trim() || null,
        consent: values.consent,
      })
      reset()
      setRating(5)
      onSubmitted(response.data?.data, response.data?.message)
    } catch (err) {
      setError(getApiErrorMessage(err, 'Could not send your testimonial. Please try again.'))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog
      open={open}
      onClose={close}
      maxWidth={false}
      fullWidth
      scroll="paper"
      aria-labelledby="testimonial-dialog-title"
      PaperProps={{ sx: { width: '100%', maxWidth: 560 } }}
    >
      <Box component="form" onSubmit={handleSubmit(onSubmit)} noValidate sx={{ display: 'contents' }}>
        <Box sx={{ p: '22px 26px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1.75 }}>
          <Box sx={{ minWidth: 0 }}>
            <Typography id="testimonial-dialog-title" component="h2" sx={{ fontSize: 18, fontWeight: 800, letterSpacing: '-.3px' }}>
              Share your {level} experience
            </Typography>
            <Typography sx={{ mt: 0.5, fontSize: 12.5, color: '#93AC9E' }}>
              What did {level} help you do? A new role, a promotion, more confidence on the job?
            </Typography>
          </Box>
          <IconButton
            onClick={close}
            aria-label="Close"
            sx={{ flex: 'none', width: 30, height: 30, borderRadius: '8px', background: 'rgba(95,174,146,.1)' }}
          >
            <CloseIcon sx={{ fontSize: 14 }} />
          </IconButton>
        </Box>

        <DialogContent sx={{ px: '26px', pt: 2.5, pb: 1, borderTop: `1px solid ${tokens.line}` }}>
          {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}

          <Typography sx={{ fontSize: 12.5, fontWeight: 600, color: tokens.body, mb: 0.75 }}>
            How would you rate the certification?
          </Typography>
          <Rating
            value={rating}
            onChange={(_, value) => setRating(value || 1)}
            sx={{ mb: 2.5, color: tokens.copperLt, '& .MuiRating-iconEmpty': { color: tokens.line2 } }}
          />

          <PcbField
            label="Your testimonial"
            multiline
            rows={4}
            maxLength={QUOTE_MAX}
            placeholder="e.g. Passing L1 gave me the confidence to move from assembly to line technician."
            error={errors.quote?.message}
            {...register('quote', {
              validate: (value) => {
                const length = value.trim().length
                if (length < QUOTE_MIN) return `Please write at least ${QUOTE_MIN} characters.`
                if (length > QUOTE_MAX) return `Please keep it under ${QUOTE_MAX} characters.`
                return true
              },
            })}
          />
          <Typography sx={{ mt: -1, mb: 2, textAlign: 'right', fontFamily: fonts.mono, fontSize: 11, color: quoteLength > QUOTE_MAX ? tokens.danger : tokens.muted }}>
            {quoteLength} / {QUOTE_MAX}
          </Typography>

          <PcbField label="Show my name as" options={nameOptions} {...register('nameDisplay')} />

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={{ xs: 0, sm: 1.5 }}>
            <PcbField sx={{ flex: 1 }} label="Job title (optional)" placeholder="e.g. Line Technician" maxLength={100} {...register('jobTitle')} />
            <PcbField sx={{ flex: 1 }} label="Company (optional)" placeholder="e.g. Acme Electronics" maxLength={100} {...register('company')} />
          </Stack>

          <Box
            component="label"
            sx={{
              display: 'flex', alignItems: 'flex-start', gap: 1, mt: 0.5, p: 1.5, borderRadius: '12px', cursor: 'pointer',
              border: `1px solid ${errors.consent ? 'rgba(248,113,113,.6)' : tokens.line}`,
              background: 'rgba(3,16,11,.5)',
            }}
          >
            <Checkbox size="small" sx={{ p: 0.25 }} inputRef={consentRef} {...consentField} />
            <Typography sx={{ fontSize: 12.5, lineHeight: 1.55, color: '#CFE2D8' }}>
              I agree that my testimonial, my name as chosen above, and my job title and company (if given) may be
              shown on certifiedemsengineers.com. I can withdraw it at any time from my profile.
            </Typography>
          </Box>
          {errors.consent && (
            <Typography sx={{ mt: 0.75, fontSize: 12, color: tokens.danger }}>{errors.consent.message}</Typography>
          )}

          <Typography sx={{ mt: 1.5, fontSize: 12, color: tokens.muted }}>
            An administrator reviews every testimonial before it appears. We may fix spelling, but never change what you meant.
          </Typography>
        </DialogContent>

        <DialogActions sx={{ p: '18px 26px', borderTop: `1px solid ${tokens.line}`, gap: 1 }}>
          <Button onClick={close} disabled={submitting} sx={{ textTransform: 'none' }}>Cancel</Button>
          <Button
            type="submit"
            variant="contained"
            disabled={submitting}
            sx={{ ...ctaButton, width: 'auto', height: 40, px: 2.5, fontSize: 12.5, letterSpacing: '.3px', textTransform: 'none' }}
          >
            {submitting ? <CircularProgress size={16} sx={{ color: '#062017' }} /> : 'Send testimonial'}
          </Button>
        </DialogActions>
      </Box>
    </Dialog>
  )
}

TestimonialDialog.propTypes = {
  open: PropTypes.bool.isRequired,
  level: PropTypes.oneOf(['L1', 'L2', 'L3']),
  onClose: PropTypes.func.isRequired,
  onSubmitted: PropTypes.func.isRequired,
}

export default TestimonialDialog
