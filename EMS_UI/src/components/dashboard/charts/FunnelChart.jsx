// ems_frontend/src/components/dashboard/charts/FunnelChart.jsx
import PropTypes from 'prop-types'
import { Box, Typography } from '@mui/material'
import { tokens } from '../../../styles/tokens'
import { formatCount, formatPercent } from './chartKit'

/**
 * How many candidates reached each step, as thin horizontal bars sized against
 * the first step. Every value and both conversion rates are printed, so nothing
 * here waits on a hover.
 */
const FunnelChart = ({ steps, color }) => {
  const first = steps[0]?.value || 0

  return (
    <Box component="ol" sx={{ m: 0, p: 0, listStyle: 'none', display: 'flex', flexDirection: 'column', gap: 1.6 }}>
      {steps.map((step, index) => {
        const share = first > 0 ? step.value / first : 0
        const previous = index > 0 ? steps[index - 1].value : 0
        return (
          <Box component="li" key={step.label}>
            <Box sx={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: 1.5, mb: 0.6 }}>
              <Typography sx={{ fontSize: 13, fontWeight: 600, color: tokens.ink }}>{step.label}</Typography>
              <Typography sx={{ fontSize: 12.5, color: tokens.muted, fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                <Box component="span" sx={{ fontWeight: 700, color: tokens.ink }}>{formatCount(step.value)}</Box>
                {index > 0 && ` · ${formatPercent(share)} of ${steps[0].label.toLowerCase()}`}
              </Typography>
            </Box>
            <Box sx={{ height: 12, borderRadius: '0 4px 4px 0', background: 'rgba(150,195,172,.08)' }}>
              <Box
                sx={{
                  width: `${step.value > 0 ? Math.max(share * 100, 1) : 0}%`,
                  height: '100%',
                  borderRadius: '0 4px 4px 0',
                  background: color,
                  transition: 'width .4s ease',
                }}
              />
            </Box>
            {index > 0 && (
              <Typography sx={{ mt: 0.4, fontSize: 11, color: tokens.muted }}>
                {previous > 0 ? `${formatPercent(step.value / previous)} of those who ${steps[index - 1].verb}` : '—'}
              </Typography>
            )}
          </Box>
        )
      })}
    </Box>
  )
}

FunnelChart.propTypes = {
  steps: PropTypes.arrayOf(
    PropTypes.shape({
      label: PropTypes.string.isRequired,
      /** How the step reads after "of those who …", e.g. "registered". */
      verb: PropTypes.string.isRequired,
      value: PropTypes.number.isRequired,
    })
  ).isRequired,
  color: PropTypes.string.isRequired,
}

export default FunnelChart
