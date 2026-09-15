// ems_frontend/src/components/dashboard/charts/AreaChart.jsx
import { useState } from 'react'
import PropTypes from 'prop-types'
import { Box } from '@mui/material'
import { fonts } from '../../../styles/tokens'
import { CHROME, ChartTooltip, niceTicks, trendPointShape, useElementWidth } from './chartKit'

const PLOT_HEIGHT = 180
const TOP_PAD = 24
const AXIS_BAND = 28
const LEFT = 58
const RIGHT = 10
const MIN_LABEL_GAP = 46

/**
 * One series as a 2px line over a faint wash, for a running total.
 *
 * The crosshair finds the month: the pointer only has to be over the plot, not
 * on the line. The arrow keys walk the same readout once the plot has focus.
 * The latest value is labelled at the line's end; the rest are on hover and in
 * the card's table view.
 */
const AreaChart = ({ data, color, formatValue, formatCompact, formatAxis, integer = false, ariaLabel }) => {
  const [ref, width] = useElementWidth()
  const [active, setActive] = useState(null)

  const values = data.map((point) => Math.max(0, Number(point.value) || 0))
  const count = values.length
  const ticks = niceTicks(count ? Math.max(...values) : 0, { integer })
  const top = ticks[ticks.length - 1] || 1
  const plotWidth = Math.max(0, width - LEFT - RIGHT)
  const baseY = TOP_PAD + PLOT_HEIGHT
  const height = baseY + AXIS_BAND
  const xOf = (index) => (count <= 1 ? LEFT + plotWidth / 2 : LEFT + (plotWidth * index) / (count - 1))
  const yOf = (value) => baseY - (value / top) * PLOT_HEIGHT

  const points = values.map((value, index) => [xOf(index), yOf(value)])
  const line = points.map(([x, y], index) => `${index ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`).join(' ')
  const area = count
    ? `${line} L${points[count - 1][0].toFixed(1)},${baseY} L${points[0][0].toFixed(1)},${baseY} Z`
    : ''
  const spacing = count > 1 ? plotWidth / (count - 1) : plotWidth
  const labelEvery = Math.max(1, Math.ceil(MIN_LABEL_GAP / Math.max(spacing, 1)))
  const lastIndex = count - 1

  const pick = (event) => {
    if (!count) return
    const bounds = event.currentTarget.getBoundingClientRect()
    const ratio = (event.clientX - bounds.left) / Math.max(bounds.width, 1)
    setActive(count === 1 ? 0 : Math.min(count - 1, Math.max(0, Math.round(ratio * (count - 1)))))
  }

  const step = (event) => {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
    event.preventDefault()
    const delta = event.key === 'ArrowLeft' ? -1 : 1
    setActive((current) => Math.min(lastIndex, Math.max(0, (current ?? lastIndex) + delta)))
  }

  return (
    <Box ref={ref} sx={{ position: 'relative', width: '100%', height }}>
      {width > 0 && count > 0 && (
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

          <path d={area} fill={color} fillOpacity={0.1} />
          <path d={line} fill="none" stroke={color} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />

          {data.map((point, index) => (
            (lastIndex - index) % labelEvery === 0 && (
              <text key={point.key} x={xOf(index)} y={baseY + 18} textAnchor="middle" fill={CHROME.label} style={{ fontSize: 10.5 }}>
                {point.label}
              </text>
            )
          ))}

          {active != null && (
            <line x1={xOf(active)} x2={xOf(active)} y1={TOP_PAD} y2={baseY} stroke={CHROME.crosshair} shapeRendering="crispEdges" />
          )}

          <circle
            cx={points[active ?? lastIndex][0]}
            cy={points[active ?? lastIndex][1]}
            r={4}
            fill={color}
            stroke={CHROME.surface}
            strokeWidth={2}
          />
          {active == null && (
            <text
              x={points[lastIndex][0] - 8}
              y={points[lastIndex][1] - 10}
              textAnchor="end"
              fill={CHROME.value}
              style={{ fontSize: 11, fontWeight: 700 }}
            >
              {formatCompact(values[lastIndex])}
            </text>
          )}

          <rect
            x={LEFT}
            y={TOP_PAD}
            width={plotWidth}
            height={PLOT_HEIGHT}
            fill="transparent"
            tabIndex={0}
            aria-label={`${ariaLabel}. Use the left and right arrow keys to read each month.`}
            onPointerMove={pick}
            onPointerLeave={() => setActive(null)}
            onFocus={() => setActive(lastIndex)}
            onBlur={() => setActive(null)}
            onKeyDown={step}
            style={{ outline: 'none' }}
          />
        </svg>
      )}

      {active != null && data[active] && (
        <ChartTooltip
          x={xOf(active)}
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

AreaChart.propTypes = {
  data: PropTypes.arrayOf(trendPointShape).isRequired,
  color: PropTypes.string.isRequired,
  formatValue: PropTypes.func.isRequired,
  formatCompact: PropTypes.func.isRequired,
  formatAxis: PropTypes.func.isRequired,
  integer: PropTypes.bool,
  ariaLabel: PropTypes.string.isRequired,
}

export default AreaChart
