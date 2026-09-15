// ems_frontend/src/components/dashboard/charts/chartKit.jsx
import { useEffect, useRef, useState } from 'react'
import PropTypes from 'prop-types'
import { Box, Typography } from '@mui/material'
import { tokens, shadows } from '../../../styles/tokens'

/**
 * One colour per metric, so a figure keeps its colour from tile to chart.
 *
 * Not the brand tokens themselves: copper-light, glow-green and info-blue are
 * all too light and too grey for marks on the dark card, and glow-green beside
 * info-blue is hard to tell apart even with full colour vision. These are the
 * same three hues stepped darker and more saturated. As a set they pass the
 * lightness-band, chroma, colour-blind separation and 3:1 contrast checks
 * against the card surface (#0A2419).
 */
export const SERIES = {
  revenue: '#B7802A',
  certified: '#34A077',
  candidates: '#6184E0',
}

/** Chart furniture: hairlines one step off the card, and text tokens for every label. */
export const CHROME = {
  grid: 'rgba(150,195,172,.10)',
  baseline: 'rgba(150,195,172,.28)',
  crosshair: 'rgba(233,243,238,.35)',
  surface: '#0A2419',
  label: tokens.muted,
  value: tokens.body,
}

/** Measures a container, so charts draw in real pixels and text is never stretched by a viewBox. */
export const useElementWidth = () => {
  const ref = useRef(null)
  const [width, setWidth] = useState(0)

  useEffect(() => {
    const element = ref.current
    if (!element) return undefined
    setWidth(element.getBoundingClientRect().width)
    if (typeof ResizeObserver === 'undefined') return undefined
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width))
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  return [ref, width]
}

/** Round axis ticks from zero: 0 / 250 / 500 / 750 rather than 0 / 243 / 486. */
export const niceTicks = (maxValue, { count = 4, integer = false } = {}) => {
  if (!Number.isFinite(maxValue) || maxValue <= 0) return [0]
  const rough = maxValue / count
  const magnitude = 10 ** Math.floor(Math.log10(rough))
  let step = [1, 2, 2.5, 5, 10].map((m) => m * magnitude).find((s) => s >= rough) || 10 * magnitude
  if (integer) step = Math.max(1, Math.ceil(step))
  const steps = Math.ceil(maxValue / step)
  return Array.from({ length: steps + 1 }, (_, i) => Number((i * step).toFixed(6)))
}

const safeFormat = (options, value, fallback) => {
  try {
    return new Intl.NumberFormat('en-IN', options).format(Number(value) || 0)
  } catch {
    return fallback
  }
}

export const formatMoney = (value, currency = 'INR') =>
  safeFormat({ style: 'currency', currency, maximumFractionDigits: 0 }, value, `${currency} ${Math.round(Number(value) || 0)}`)

export const formatMoneyCompact = (value, currency = 'INR') =>
  safeFormat(
    { style: 'currency', currency, notation: 'compact', maximumFractionDigits: 1 },
    value,
    `${currency} ${Math.round(Number(value) || 0)}`
  )

export const formatCount = (value) => safeFormat({}, value, String(value ?? 0))

export const formatCountCompact = (value) =>
  safeFormat({ notation: 'compact', maximumFractionDigits: 1 }, value, String(value ?? 0))

export const formatPercent = (ratio) => {
  if (!Number.isFinite(ratio) || ratio <= 0) return '0%'
  if (ratio < 0.01) return '<1%'
  return `${Math.round(ratio * 100)}%`
}

const monthDate = (key) => {
  const [year, month] = String(key).split('-').map(Number)
  return new Date(year, (month || 1) - 1, 1)
}

/**
 * "Sep", or "Sep '26" where the year needs saying (the first month shown, and
 * every January). Spelled out by hand: the locale's own "Sep 26" reads as a date.
 */
export const monthShort = (key, withYear = false) => {
  const date = monthDate(key)
  const month = date.toLocaleString('en-IN', { month: 'short' })
  return withYear ? `${month} '${String(date.getFullYear()).slice(2)}` : month
}

export const monthLong = (key) => monthDate(key).toLocaleString('en-IN', { month: 'long', year: 'numeric' })

/**
 * The hover readout. The value leads and the series name follows, keyed by a
 * short stroke of the series colour; text itself stays in text tokens.
 */
export const ChartTooltip = ({ x, y, width, color, value, title, detail }) => {
  const left = Math.min(Math.max(x, 90), Math.max(90, width - 90))
  const below = y < 64
  return (
    <Box
      role="status"
      aria-live="polite"
      sx={{
        position: 'absolute',
        left,
        top: y,
        transform: below ? 'translate(-50%, 14px)' : 'translate(-50%, calc(-100% - 14px))',
        pointerEvents: 'none',
        zIndex: 2,
        minWidth: 136,
        px: 1.25,
        py: 1,
        borderRadius: '10px',
        background: 'rgba(3,17,12,.96)',
        border: `1px solid ${tokens.line2}`,
        boxShadow: shadows.package,
        whiteSpace: 'nowrap',
      }}
    >
      <Typography sx={{ fontSize: 14, fontWeight: 800, lineHeight: 1.2, color: tokens.ink }}>{value}</Typography>
      <Box sx={{ mt: 0.5, display: 'flex', alignItems: 'center', gap: 0.75 }}>
        <Box sx={{ flex: 'none', width: 10, height: 2, borderRadius: 1, background: color }} />
        <Typography sx={{ fontSize: 11.5, color: tokens.body }}>{title}</Typography>
      </Box>
      {detail && <Typography sx={{ mt: 0.25, fontSize: 11, color: tokens.muted }}>{detail}</Typography>}
    </Box>
  )
}

ChartTooltip.propTypes = {
  x: PropTypes.number.isRequired,
  y: PropTypes.number.isRequired,
  width: PropTypes.number.isRequired,
  color: PropTypes.string.isRequired,
  value: PropTypes.node.isRequired,
  title: PropTypes.node,
  detail: PropTypes.node,
}

/** The shape every trend chart takes: one point per month. */
export const trendPointShape = PropTypes.shape({
  key: PropTypes.string.isRequired,
  label: PropTypes.string.isRequired,
  fullLabel: PropTypes.string,
  value: PropTypes.number.isRequired,
  detail: PropTypes.node,
})
