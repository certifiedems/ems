// ems_frontend/src/components/testimonial/TestimonialPrompt.jsx
import { useEffect, useState } from 'react'
import PropTypes from 'prop-types'
import { Alert, Box, Button, Typography } from '@mui/material'
import FormatQuoteIcon from '@mui/icons-material/FormatQuoteRounded'
import { testimonialAPI } from '../../api/testimonialAPI'
import TestimonialDialog from './TestimonialDialog'
import { tokens, tone } from '../../styles/tokens'

/**
 * "Share your experience" card for certified candidates.
 *
 * Renders nothing unless the candidate holds a level they have not written a
 * testimonial for, and nothing at all if the check fails: it is an invitation,
 * never something that should put an error on the page. When several levels
 * qualify it asks about the highest, the one they most likely just passed.
 */
const TestimonialPrompt = ({ sx }) => {
  const [level, setLevel] = useState(null)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [thanks, setThanks] = useState('')

  useEffect(() => {
    let mounted = true
    testimonialAPI.getMine()
      .then((response) => {
        const eligible = response.data?.data?.eligibleLevels || []
        if (mounted) setLevel(eligible.length ? eligible[eligible.length - 1] : null)
      })
      .catch(() => {})
    return () => { mounted = false }
  }, [])

  const handleSubmitted = (_testimonial, message) => {
    setDialogOpen(false)
    setLevel(null)
    setThanks(message || 'Thank you! Your testimonial will appear once it has been reviewed.')
  }

  if (thanks) {
    return (
      <Alert severity="success" sx={{ mb: 2, ...sx }} onClose={() => setThanks('')}>
        {thanks} You can see or withdraw it from your profile.
      </Alert>
    )
  }

  if (!level) return null

  return (
    <>
      <Box
        sx={{
          mb: 2,
          p: { xs: 2, sm: 2.5 },
          display: 'flex',
          alignItems: { xs: 'flex-start', sm: 'center' },
          flexDirection: { xs: 'column', sm: 'row' },
          gap: 2,
          borderRadius: '16px',
          background: tone.copper.bg,
          border: `1px solid ${tone.copper.border}`,
          ...sx,
        }}
      >
        <Box
          sx={{
            flex: 'none', width: 44, height: 44, borderRadius: '12px', display: 'grid', placeItems: 'center',
            color: tokens.copperLt, background: 'rgba(192,138,46,.14)', border: `1px solid ${tone.copper.border}`,
          }}
        >
          <FormatQuoteIcon />
        </Box>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography sx={{ fontSize: 15, fontWeight: 700, color: tokens.ink }}>
            Share your {level} experience
          </Typography>
          <Typography sx={{ mt: 0.25, fontSize: 13, color: tokens.body }}>
            A sentence or two about what the certification helped you do. It helps other engineers decide, and takes a minute.
          </Typography>
        </Box>
        <Button variant="contained" onClick={() => setDialogOpen(true)} sx={{ flex: 'none', textTransform: 'none' }}>
          Write a testimonial
        </Button>
      </Box>

      <TestimonialDialog
        open={dialogOpen}
        level={level}
        onClose={() => setDialogOpen(false)}
        onSubmitted={handleSubmitted}
      />
    </>
  )
}

TestimonialPrompt.propTypes = { sx: PropTypes.object }

export default TestimonialPrompt
