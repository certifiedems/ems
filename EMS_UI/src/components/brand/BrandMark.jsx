// ems_frontend/src/components/brand/BrandMark.jsx
import PropTypes from 'prop-types'
import { Box, Typography } from '@mui/material'
import { tokens, fonts } from '../../styles/tokens'

let gradientSeq = 0

/**
 * Brand mark: a QFP chip package with a copper trace routed into a
 * certification check. Rendered inline as SVG so it inherits the page's
 * colour treatment and stays crisp at every size.
 */
export const ChipLogo = ({ size = 46 }) => {
  // Each instance needs its own gradient id — duplicated ids in one document
  // make every later copy reuse the first one's coordinate space.
  const id = `cu-${(gradientSeq += 1)}`
  return (
    <svg
      width={size}
      height={size * (120 / 109)}
      viewBox="0 0 109 120"
      fill="none"
      aria-hidden="true"
      style={{ flex: 'none' }}
    >
      <defs>
        <linearGradient id={id} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stopColor="#E8C071" />
          <stop offset="55%" stopColor="#C08A2E" />
          <stop offset="100%" stopColor="#9C6B1E" />
        </linearGradient>
      </defs>
      <g fill={`url(#${id})`}>
        <rect x="26" y="0" width="9" height="15" rx="2" />
        <rect x="50" y="0" width="9" height="15" rx="2" />
        <rect x="74" y="0" width="9" height="15" rx="2" />
        <rect x="26" y="105" width="9" height="15" rx="2" />
        <rect x="50" y="105" width="9" height="15" rx="2" />
        <rect x="74" y="105" width="9" height="15" rx="2" />
        <rect x="0" y="26" width="15" height="9" rx="2" />
        <rect x="0" y="50" width="15" height="9" rx="2" />
        <rect x="0" y="74" width="15" height="9" rx="2" />
        <rect x="94" y="26" width="15" height="9" rx="2" />
        <rect x="94" y="50" width="15" height="9" rx="2" />
        <rect x="94" y="74" width="15" height="9" rx="2" />
      </g>
      <rect x="13" y="13" width="83" height="94" rx="11" fill="#0E4D3C" stroke="#5FAE92" strokeWidth="2" />
      <circle cx="27" cy="27" r="5" fill="#C08A2E" />
      <path
        d="M34 62 L50 78 L80 40"
        fill="none"
        stroke={`url(#${id})`}
        strokeWidth="9"
        strokeLinecap="square"
        strokeLinejoin="miter"
      />
      <circle cx="80" cy="40" r="7.5" fill="#E8C071" />
      <circle cx="34" cy="62" r="5.5" fill="#E8C071" />
    </svg>
  )
}

ChipLogo.propTypes = { size: PropTypes.number }

/** Full lockup: chip mark + wordmark + domain sub-line. */
const BrandMark = ({ size = 46, showWordmark = true, compact = false, sx }) => (
  <Box sx={{ display: 'flex', alignItems: 'center', gap: compact ? 1.25 : 1.75, ...sx }}>
    <ChipLogo size={size} />
    {showWordmark && (
      <Box sx={{ minWidth: 0 }}>
        <Typography
          component="span"
          sx={{
            display: 'block',
            fontSize: compact ? 12 : 20,
            fontWeight: 800,
            letterSpacing: compact ? '1.6px' : '3.4px',
            color: tokens.ink,
            lineHeight: 1.1,
          }}
        >
          CERTIFIED EMS ENGINEERS
        </Typography>
        {!compact && (
          <Typography
            component="span"
            sx={{
              display: 'block',
              fontFamily: fonts.mono,
              fontSize: 9.5,
              letterSpacing: '2.4px',
              color: tokens.copper,
              mt: '5px',
            }}
          >
            WWW.CERTIFIEDEMSENGINEERS.COM
          </Typography>
        )}
      </Box>
    )}
  </Box>
)

BrandMark.propTypes = {
  size: PropTypes.number,
  showWordmark: PropTypes.bool,
  compact: PropTypes.bool,
  sx: PropTypes.object,
}

export default BrandMark
