// ems_frontend/src/components/dashboard/charts/ColumnChart.jsx
import { useState } from 'react'
import PropTypes from 'prop-types'
import { Box } from '@mui/material'
import { fonts } from '../../../styles/tokens'
import { CHROME, ChartTooltip, niceTicks, trendPointShape, useElementWidth } from './chartKit'

const PLOT_HEIGHT = 180
const TOP_PAD = 24
const AXIS_BAND = 28
const LEFT = 58
const RIGHT = 8
const MAX_BAR = 24
const MIN_LABEL_GAP = 46

/** A column with a 4px rounded data end and a square foot on the baseline. */
const columnPath = (x, top, width, height) => {
  if (height <= 0) return ''
  const r = Math.min(4, width / 2, height)
  return `M${x},${top + height} V${top + r} Q${x},${top} ${x + r},${top} `
    + `H${x + width - r} Q${x + width},${top} ${x + width},${top + r} V${top + height} Z`
}

/**
 * One series of monthly columns.
 *
 * Only the current month and the peak carry a value label; every other value
 * is on hover, on keyboard focus, and in the card's table view. Each month's
 * whole column of plot space is its hover target, not just the painted bar.
 */
const ColumnChart = ({ data, color, formatValue, formatCompact, formatAxis, integer = false, ariaLabel }) => {
  const [ref, width] = useElementWidth()
  const [active, setActive] = useState(null)

  const values = data.map((point) => Math.max(0, Number(point.value) || 0))
  const peak = values.length ? Math.max(...values) : 0
  const ticks = niceTicks(peak, { integer })
  const top = ticks[ticks.length - 1] || 1
  const plotWidth = Math.max(0, width - LEFT - RIGHT)
  const band = data.length ? plotWidth / data.length : 0
  const barWidth = Math.max(2, Math.min(MAX_BAR, band * 0.6))
  const baseY = TOP_PAD + PLOT_HEIGHT
  const height = baseY + AXIS_BAND
  const yOf = (value) => baseY - (value / top) * PLOT_HEIGHT

  const lastIndex = data.length - 1
  const peakIndex = peak > 0 ? values.indexOf(peak) : -1
  const labelled = new Set()
  if (lastIndex >= 0 && values[lastIndex] > 0) labelled.add(lastIndex)
  if (peakIndex >= 0 && (peakIndex === lastIndex || Math.abs(peakIndex - lastIndex) * band >= 64)) {
    labelled.add(peakIndex)
  }
  // Month labels thin out on narrow cards, counted back from the current month so it is always named.
  const labelEvery = Math.max(1, Math.ceil(MIN_LABEL_GAP / Math.max(band, 1)))

  return (
    <Box ref={ref} sx={{ position: 'relative', width: '100%', height }}>
      {width > 0 && (
        <svg width={width} height={height} role="img" aria-label={ariaLabel} style={{ display: 'block', overflow: 'visible' }}>
          {ticks.map((tick) => (
            <g key={tick}>
              {tick > 0 && (
                <line x1={LEFT} x2={width - RIGHT} y1={yOf(tick)} y2={yOf(tick)} stroke={CHROME.grid} shapeRendering="crispEdges" />
              )}
              <text
                x={LEFT - 8}
                y={yOf(tick) + 3.5}
                textAnchor="end"
                fill={CHROME.label}
                style={{ fontFamily: fonts.mono, fontSize: 10, fontVariantNumeric: 'tabular-nums' }}
              >
                {formatAxis(tick)}
              </text>
            </g>
          ))}
          <line x1={LEFT} x2={width - RIGHT} y1={baseY} y2={baseY} stroke={CHROME.baseline} shapeRendering="crispEdges" />

          {data.map((point, index) => {
            const centre = LEFT + band * index + band / 2
            const barTop = yOf(values[index])
            return (
              <g key={point.key}>
                <path
                  d={columnPath(centre - barWidth / 2, barTop, barWidth, baseY - barTop)}
                  fill={color}
                  style={{ filter: active === index ? 'brightness(1.3)' : undefined, transition: 'filter .15s' }}
                />
                {labelled.has(index) && (
                  <text x={centre} y={barTop - 7} textAnchor="middle" fill={CHROME.value} style={{ fontSize: 11, fontWeight: 700 }}>
                    {formatCompact(values[index])}
                  </text>
                )}
                {(lastIndex - index) % labelEvery === 0 && (
                  <text x={centre} y={baseY + 18} textAnchor="middle" fill={CHROME.label} style={{ fontSize: 10.5 }}>
                    {point.label}
                  </text>
                )}
                <rect
                  x={LEFT + band * index}
                  y={TOP_PAD}
                  width={band}
                  height={PLOT_HEIGHT}
                  fill="transparent"
                  tabIndex={0}
                  aria-label={`${point.fullLabel || point.label}: ${formatValue(values[index])}`}
                  onPointerEnter={() => setActive(index)}
                  onPointerLeave={() => setActive(null)}
                  onFocus={() => setActive(index)}
                  onBlur={() => setActive(null)}
                  style={{ outline: 'none' }}
                />
              </g>
            )
          })}
        </svg>
      )}

      {active != null && data[active] && (
        <ChartTooltip
          x={LEFT + band * active + band / 2}
          y={yOf(values[active])}
          width={width}
          color={color}
          value={formatValue(values[active])}
          title={data[active].fullLabel || data[active].label}
          detail={data[active].detail}
        />
      )}
    </Box>
  )
}

ColumnChart.propTypes = {
  data: PropTypes.arrayOf(trendPointShape).isRequired,
  color: PropTypes.string.isRequired,
  formatValue: PropTypes.func.isRequired,
  formatCompact: PropTypes.func.isRequired,
  formatAxis: PropTypes.func.isRequired,
  integer: PropTypes.bool,
  ariaLabel: PropTypes.string.isRequired,
}

export default ColumnChart
