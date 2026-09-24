// ems_frontend/src/components/testimonial/MyTestimonials.jsx
import { useCallback, useEffect, useState } from 'react'
import {
  Alert, Box, Button, CircularProgress, Dialog, DialogActions, DialogContent, Stack, Typography,
} from '@mui/material'
import StatusChip from '../common/StatusChip'
import TestimonialDialog from './TestimonialDialog'
import { testimonialAPI } from '../../api/testimonialAPI'
import { getApiErrorMessage } from '../../utils/apiError'
import { tokens, fonts } from '../../styles/tokens'

const STATUS_LINE = {
  PENDING: 'Waiting for review. It is not on the site yet.',
  APPROVED: 'Published on the site.',
  REJECTED: 'Not published.',
}

/**
 * The profile's "My Testimonials" section: each testimonial the candidate has
 * written, where it stands, and a way to withdraw it. Withdrawing deletes it
 * outright, which is also how they take back their consent to publication.
 *
 * Hidden entirely for candidates who are not certified yet and so have nothing
 * to show or write.
 */
const MyTestimonials = () => {
  const [data, setData] = useState(null)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [writeLevel, setWriteLevel] = useState(null)
  const [confirming, setConfirming] = useState(null)
  const [withdrawing, setWithdrawing] = useState(false)

  const load = useCallback(async () => {
    try {
      const response = await testimonialAPI.getMine()
      setData(response.data?.data || { eligibleLevels: [], testimonials: [] })
    } catch {
      // The profile works without this section; say nothing rather than
      // putting an error on a page the candidate came to for something else.
      setData({ eligibleLevels: [], testimonials: [] })
    }
  }, [])

  useEffect(() => { load() }, [load])

  const withdraw = async () => {
    setWithdrawing(true)
    setError('')
    try {
      const response = await testimonialAPI.withdraw(confirming.id)
      setNotice(response.data?.message || 'Your testimonial has been withdrawn and deleted.')
      setConfirming(null)
      await load()
    } catch (err) {
      setError(getApiErrorMessage(err, 'Could not withdraw your testimonial. Please try again.'))
    } finally {
      setWithdrawing(false)
    }
  }

  if (!data || (data.testimonials.length === 0 && data.eligibleLevels.length === 0)) return null

  return (
    <Box sx={{ mt: 3.5 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 1.5, mb: 1.5 }}>
        <Typography sx={{ fontSize: 16, fontWeight: 700, letterSpacing: '-.2px', color: tokens.ink }}>
          My Testimonials
        </Typography>
        <Stack direction="row" spacing={1}>
          {data.eligibleLevels.map((level) => (
            <Button key={level} size="small" variant="outlined" onClick={() => setWriteLevel(level)} sx={{ textTransform: 'none' }}>
              Write one for {level}
            </Button>
          ))}
        </Stack>
      </Box>

      {notice && <Alert severity="success" sx={{ mb: 1.5 }} onClose={() => setNotice('')}>{notice}</Alert>}
      {error && <Alert severity="error" sx={{ mb: 1.5 }} onClose={() => setError('')}>{error}</Alert>}

      {data.testimonials.length === 0 ? (
        <Typography sx={{ fontSize: 13, color: tokens.body }}>
          You haven&apos;t shared a testimonial yet. A sentence or two about what the certification helped you do
          helps other engineers decide.
        </Typography>
      ) : (
        <Stack spacing={1.25}>
          {data.testimonials.map((t) => (
            <Box key={t.id} sx={{ p: 2, borderRadius: '14px', border: `1px solid ${tokens.line}`, background: 'rgba(6,26,19,.55)' }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap', mb: 1 }}>
                <Typography sx={{ fontFamily: fonts.mono, fontSize: 13, color: tokens.copperLt }}>{t.certificationLevel}</Typography>
                <StatusChip status={t.status} />
                <Typography sx={{ fontSize: 12, color: tokens.muted }}>{STATUS_LINE[t.status]}</Typography>
                <Button
                  size="small"
                  color="error"
                  onClick={() => setConfirming(t)}
                  sx={{ ml: 'auto', textTransform: 'none' }}
                >
                  Withdraw
                </Button>
              </Box>
              <Typography sx={{ fontSize: 14, lineHeight: 1.6, fontStyle: 'italic', color: '#CFE2D8' }}>
                &ldquo;{t.publishedQuote}&rdquo;
              </Typography>
              {t.publishedQuote !== t.quote && (
                <Typography sx={{ mt: 0.75, fontSize: 12, color: tokens.muted }}>
                  Spelling corrected by our team. You wrote: &ldquo;{t.quote}&rdquo;
                </Typography>
              )}
              {t.reviewNote && (
                <Typography sx={{ mt: 0.75, fontSize: 12.5, color: tokens.body }}>
                  Note from our team: {t.reviewNote}
                </Typography>
              )}
            </Box>
          ))}
        </Stack>
      )}

      <TestimonialDialog
        open={Boolean(writeLevel)}
        level={writeLevel || undefined}
        onClose={() => setWriteLevel(null)}
        onSubmitted={(_t, message) => {
          setWriteLevel(null)
          setNotice(message || 'Thank you! Your testimonial will appear once it has been reviewed.')
          load()
        }}
      />

      <Dialog open={Boolean(confirming)} onClose={() => !withdrawing && setConfirming(null)} PaperProps={{ sx: { maxWidth: 420 } }}>
        <DialogContent sx={{ pt: 3 }}>
          <Typography sx={{ fontSize: 16, fontWeight: 700, mb: 1 }}>Withdraw your {confirming?.certificationLevel} testimonial?</Typography>
          <Typography sx={{ fontSize: 13.5, color: tokens.body }}>
            It will be deleted and, if published, removed from the site straight away. You can write a new one later.
          </Typography>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5 }}>
          <Button onClick={() => setConfirming(null)} disabled={withdrawing} sx={{ textTransform: 'none' }}>Keep it</Button>
          <Button color="error" variant="contained" onClick={withdraw} disabled={withdrawing} sx={{ textTransform: 'none' }}>
            {withdrawing ? <CircularProgress size={16} color="inherit" /> : 'Withdraw and delete'}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}

export default MyTestimonials
