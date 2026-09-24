// ems_frontend/src/components/testimonial/TestimonialShowcase.jsx
import { useEffect, useState } from 'react'
import { Box, Typography } from '@mui/material'
import FormatQuoteIcon from '@mui/icons-material/FormatQuoteRounded'
import { testimonialAPI } from '../../api/testimonialAPI'
import { tokens, fonts } from '../../styles/tokens'

const ROTATE_MS = 8000

const initials = (name) =>
  name.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]).join('').toUpperCase()

const prefersReducedMotion = () =>
  typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches

/**
 * Approved candidate testimonials on the sign-in screens, one at a time.
 *
 * Renders nothing until there is at least one approved quote, and nothing if
 * the request fails: the sign-in screen must look finished either way. Quotes
 * rotate every few seconds, pausing while hovered and not at all for visitors
 * who asked for reduced motion; the dots let anyone step through by hand.
 */
const TestimonialShowcase = () => {
  const [items, setItems] = useState([])
  const [index, setIndex] = useState(0)
  const [paused, setPaused] = useState(false)

  useEffect(() => {
    let mounted = true
    testimonialAPI.getPublished()
      .then((response) => { if (mounted) setItems(response.data?.data || []) })
      .catch(() => {})
    return () => { mounted = false }
  }, [])

  useEffect(() => {
    if (items.length < 2 || paused || prefersReducedMotion()) return undefined
    const timer = setInterval(() => setIndex((i) => (i + 1) % items.length), ROTATE_MS)
    return () => clearInterval(timer)
  }, [items.length, paused])

  if (items.length === 0) return null

  const t = items[index % items.length]
  const byline = [t.jobTitle, t.company].filter(Boolean).join(', ')

  return (
    <Box
      component="figure"
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      sx={{
        m: 0,
        mt: 3.5,
        p: '18px 20px',
        borderRadius: '14px',
        background: 'rgba(6,26,19,.7)',
        border: `1px solid ${tokens.line}`,
        maxWidth: 560,
      }}
    >
      <Box component="blockquote" key={t.id} sx={{ m: 0, '@keyframes quoteIn': { from: { opacity: 0 }, to: { opacity: 1 } }, animation: 'quoteIn .5s ease' }}>
        <Typography sx={{ fontSize: 14.5, lineHeight: 1.6, fontStyle: 'italic', color: '#CFE2D8' }}>
          &ldquo;{t.quote}&rdquo;
        </Typography>
      </Box>

      <Box component="figcaption" sx={{ display: 'flex', alignItems: 'center', gap: 1.25, mt: 1.5 }}>
        <Box
          aria-hidden
          sx={{
            flex: 'none', width: 32, height: 32, borderRadius: '50%', display: 'grid', placeItems: 'center',
            fontSize: 12, fontWeight: 700, color: tokens.copperLt, background: tokens.green, border: `1px solid ${tokens.greenLt}`,
          }}
        >
          {t.displayName ? initials(t.displayName) : <FormatQuoteIcon sx={{ fontSize: 16 }} />}
        </Box>
        <Box sx={{ minWidth: 0 }}>
          <Typography sx={{ fontSize: 13, fontWeight: 700, color: tokens.ink }}>
            {t.displayName || 'Certified engineer'}
          </Typography>
          {byline && <Typography sx={{ fontSize: 11.5, color: tokens.muted }}>{byline}</Typography>}
        </Box>
        <Box
          component="span"
          sx={{
            ml: 'auto', flex: 'none', fontFamily: fonts.mono, fontSize: 10, letterSpacing: '1.2px', color: tokens.greenLt,
            border: '1px solid rgba(95,174,146,.4)', px: 1, py: '3px', borderRadius: '6px',
          }}
        >
          {t.certificationLevel} CERTIFIED
        </Box>
      </Box>

      {items.length > 1 && (
        <Box sx={{ display: 'flex', gap: 0.75, mt: 1.5 }}>
          {items.map((item, i) => (
            <Box
              key={item.id}
              component="button"
              type="button"
              aria-label={`Show testimonial ${i + 1} of ${items.length}`}
              aria-current={i === index % items.length}
              onClick={() => setIndex(i)}
              sx={{
                width: i === index % items.length ? 18 : 7, height: 7, p: 0, border: 0, borderRadius: 4, cursor: 'pointer',
                background: i === index % items.length ? tokens.copperLt : tokens.line2, transition: 'width .2s',
              }}
            />
          ))}
        </Box>
      )}
    </Box>
  )
}

export default TestimonialShowcase
