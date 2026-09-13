// ems_frontend/src/components/layout/AuthLayout.jsx
import PropTypes from 'prop-types'
import { Box, Typography } from '@mui/material'
import PcbBackdrop from '../brand/PcbBackdrop'
import BrandMark from '../brand/BrandMark'
import SupportEmailLink from '../common/SupportEmailLink'
import { tokens, fonts, gradients, shadows } from '../../styles/tokens'

const LEVELS = [
  { code: 'L1', name: 'Foundation' },
  { code: 'L2', name: 'Advanced' },
  { code: 'L3', name: 'Master' },
]

const STATS = [
  { value: '12,400+', label: 'Engineers certified' },
  { value: '94%', label: 'First-attempt pass rate' },
  { value: '48 hrs', label: 'Average result time' },
]

const ProctorIcon = () => (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <rect x="6.5" y="6.5" width="11" height="11" rx="2" stroke="currentColor" strokeWidth="1.7" />
    <path
      d="M10 3.2v3.3M14 3.2v3.3M10 17.5v3.3M14 17.5v3.3M3.2 10h3.3M3.2 14h3.3M17.5 10h3.3M17.5 14h3.3"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
    />
    <path
      d="m10.2 12 1.4 1.5 2.6-3"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
)

const CredentialIcon = () => (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <circle cx="12" cy="9" r="5.3" stroke="currentColor" strokeWidth="1.7" />
    <path
      d="m9.4 12.4-1 8.2 3.6-2.1 3.6 2.1-1-8.2"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinejoin="round"
    />
    <path
      d="m10.2 9 1.4 1.4 2.4-2.6"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
)

const FEATURES = [
  { icon: <ProctorIcon />, text: 'Sit a proctored, industry-benchmarked assessment', tone: 'copper' },
  { icon: <CredentialIcon />, text: 'Earn a credential anyone can verify instantly', tone: 'green' },
]

/** Decorative die routing in the card's top-right corner. */
const DieCorner = () => (
  <Box
    component="svg"
    viewBox="0 0 150 150"
    fill="none"
    aria-hidden="true"
    sx={{ position: 'absolute', top: 0, right: 0, width: 150, height: 150, opacity: 0.55, pointerEvents: 'none' }}
  >
    <g stroke="rgba(192,138,46,.5)" strokeWidth="1.2">
      <path d="M150 40h-46l-18 18v46" />
      <path d="M150 74h-24l-16 16v34" />
      <path d="M116 0v22l-18 18v40" />
      <path d="M84 0v10L64 30v52" />
    </g>
    <g fill="rgba(232,192,113,.7)">
      <circle cx="104" cy="40" r="2.6" />
      <circle cx="126" cy="74" r="2.6" />
      <circle cx="64" cy="30" r="2.6" />
    </g>
  </Box>
)

/** Copper lead frame down either flank of the package. */
const Pins = ({ side }) => (
  <Box
    aria-hidden="true"
    sx={{
      position: 'absolute',
      top: 52,
      bottom: 52,
      width: 14,
      display: { xs: 'none', md: 'flex' },
      flexDirection: 'column',
      justifyContent: 'space-around',
      alignItems: side === 'l' ? 'flex-end' : 'flex-start',
      pointerEvents: 'none',
      [side === 'l' ? 'left' : 'right']: -14,
      '@keyframes pcbPin': { '0%,100%': { opacity: 0.3 }, '50%': { opacity: 1 } },
      '& i': {
        display: 'block',
        width: 14,
        height: 7,
        borderRadius: '2px',
        background: `linear-gradient(${side === 'l' ? '270deg' : '90deg'}, ${tokens.copperDk}, ${tokens.copperLt})`,
        opacity: 0.45,
        animation: 'pcbPin 4.5s ease-in-out infinite',
      },
      '& i:nth-of-type(2)': { animationDelay: '.5s' },
      '& i:nth-of-type(3)': { animationDelay: '1s' },
      '& i:nth-of-type(4)': { animationDelay: '1.5s' },
      '& i:nth-of-type(5)': { animationDelay: '2s' },
      '& i:nth-of-type(6)': { animationDelay: '2.5s' },
      '& i:nth-of-type(7)': { animationDelay: '3s' },
    }}
  >
    {Array.from({ length: 7 }, (_, i) => (
      <i key={i} />
    ))}
  </Box>
)

Pins.propTypes = { side: PropTypes.oneOf(['l', 'r']).isRequired }

/**
 * Shell for every unauthenticated screen: the brand story on the board to the
 * left, the form seated in a chip package to the right.
 */
const AuthLayout = ({ title, subtitle, children, stamp = 'EMS-AUTH v2.4', wide = false }) => (
  <>
    <PcbBackdrop intensity="full" />

    <Box
      component="main"
      sx={{
        position: 'relative',
        zIndex: 1,
        display: 'grid',
        // minmax(0, …) rather than a bare fr: a default `1fr` track is floored
        // at its content's min width, so the fixed-width package on the right
        // pushes the whole page wider than a phone viewport.
        gridTemplateColumns: {
          xs: 'minmax(0, 1fr)',
          md: 'minmax(0, 1.06fr) minmax(0, .94fr)',
        },
        minHeight: '100vh',
      }}
    >
      {/* ==================== BRAND ==================== */}
      <Box
        component="section"
        sx={{
          display: 'flex',
          flexDirection: 'column',
          minWidth: 0,
          p: { xs: '28px 18px 32px', sm: '38px 26px 40px', lg: '52px 60px' },
        }}
      >
        <BrandMark size={46} />

        <Box sx={{ my: 'auto', py: { xs: '26px', md: '36px' }, maxWidth: { md: 620 } }}>
          <Box
            className="mono"
            sx={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: '9px',
              mb: 3,
              px: '16px',
              py: '7px',
              pl: '11px',
              borderRadius: 999,
              fontSize: 11.5,
              fontWeight: 600,
              letterSpacing: '1.2px',
              color: '#DCC79A',
              background: 'rgba(192,138,46,.12)',
              border: '1px solid rgba(192,138,46,.36)',
              '@keyframes pcbBlink': { '0%,100%': { opacity: 1 }, '50%': { opacity: 0.3 } },
            }}
          >
            <Box
              component="span"
              sx={{
                width: 7,
                height: 7,
                borderRadius: '50%',
                background: tokens.greenGlow,
                boxShadow: `0 0 9px ${tokens.greenGlow}`,
                animation: 'pcbBlink 2.4s ease-in-out infinite',
              }}
            />
            L1 · L2 · L3 OSS ENGINEER CERTIFICATION
          </Box>

          <Typography
            variant="h1"
            sx={{
              fontSize: { xs: 30, sm: 34, md: 'clamp(35px,3.8vw,53px)' },
              lineHeight: 1.06,
              overflowWrap: 'break-word',
            }}
          >
            Want to elevate your career as an{' '}
            <Box
              component="span"
              sx={{
                background: gradients.brandText,
                WebkitBackgroundClip: 'text',
                backgroundClip: 'text',
                color: 'transparent',
              }}
            >
              Automation OSS Engineer?
            </Box>
          </Typography>

          <Typography
            sx={{
              mt: 2.5,
              fontSize: { xs: 15.5, md: 16.5 },
              lineHeight: 1.66,
              color: tokens.body,
              maxWidth: 530,
            }}
          >
            Complete the assessment, earn the certification, and{' '}
            <Box component="b" sx={{ color: '#D8E8DF', fontWeight: 600 }}>
              show the world you&rsquo;re levelling up
            </Box>{' '}
            — ready to change how automation gets built as a certified L1, L2 or L3 OSS Engineer.
          </Typography>

          <Box sx={{ display: 'flex', gap: 1.25, mt: 3.25, flexWrap: 'wrap' }}>
            {LEVELS.map((l) => (
              <Box
                key={l.code}
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: '9px',
                  px: '15px',
                  py: '9px',
                  pl: '11px',
                  borderRadius: '11px',
                  background: 'rgba(14,77,60,.5)',
                  border: '1px solid rgba(192,138,46,.3)',
                }}
              >
                <Box
                  component="b"
                  sx={{ fontFamily: fonts.mono, fontSize: 14, fontWeight: 500, color: tokens.copperLt }}
                >
                  {l.code}
                </Box>
                <Box component="span" sx={{ fontSize: 12.5, color: tokens.body }}>
                  {l.name}
                </Box>
              </Box>
            ))}
          </Box>

          <Box sx={{ mt: 4, display: 'flex', flexDirection: 'column', gap: 1.75 }}>
            {FEATURES.map((f) => (
              <Box key={f.text} sx={{ display: 'flex', alignItems: 'center', gap: 1.75 }}>
                <Box
                  sx={{
                    flex: 'none',
                    width: 42,
                    height: 42,
                    borderRadius: '12px',
                    display: 'grid',
                    placeItems: 'center',
                    color: f.tone === 'copper' ? tokens.copperLt : tokens.greenLt,
                    background:
                      f.tone === 'copper' ? 'rgba(192,138,46,.10)' : 'rgba(95,174,146,.10)',
                    border: `1px solid ${f.tone === 'copper' ? 'rgba(192,138,46,.3)' : 'rgba(95,174,146,.3)'}`,
                  }}
                >
                  {f.icon}
                </Box>
                <Typography sx={{ fontSize: 15, fontWeight: 500, color: '#CFE2D8' }}>
                  {f.text}
                </Typography>
              </Box>
            ))}
          </Box>
        </Box>

        <Box
          sx={{
            display: 'flex',
            flexWrap: 'wrap',
            rowGap: 2,
            gap: { xs: 3, md: 4.75 },
            pt: 3.25,
            mt: { xs: 3, md: 3.75 },
            borderTop: `1px solid ${tokens.line}`,
          }}
        >
          {STATS.map((s) => (
            <Box key={s.label}>
              <Box
                component="b"
                sx={{
                  display: 'block',
                  fontSize: { xs: 19, md: 23 },
                  fontWeight: 800,
                  letterSpacing: '-.4px',
                  color: tokens.ink,
                }}
              >
                {s.value}
              </Box>
              <Box component="span" sx={{ fontSize: 11.5, color: tokens.muted }}>
                {s.label}
              </Box>
            </Box>
          ))}
        </Box>

        <Typography
          className="mono"
          sx={{
            mt: 3.25,
            fontSize: 11,
            letterSpacing: '1.2px',
            color: tokens.muted,
            display: { xs: 'none', md: 'block' },
          }}
        >
          © {new Date().getFullYear()} CERTIFIED EMS ENGINEERS · EMS CERTIFICATION BOARD
        </Typography>
      </Box>

      {/* ==================== FORM ==================== */}
      <Box
        component="section"
        sx={{
          display: 'grid',
          placeItems: 'center',
          // Packs the card and the help line under it into one centred group;
          // at the default each would be centred in its own half of the column.
          alignContent: 'center',
          minWidth: 0,
          // Heavier bottom padding than top: a centred card reads as sitting low,
          // so the extra weight below lifts it to the optical centre. The two
          // figures still sum to the same column height as an even 48/48.
          p: { xs: '8px 16px 44px', sm: '14px 26px 56px', md: '26px 40px 70px' },
        }}
      >
        <Box
          sx={{
            position: 'relative',
            width: '100%',
            maxWidth: { xs: '100%', sm: wide ? 620 : 452 },
          }}
        >
          <Pins side="l" />
          <Pins side="r" />

          <Box
            sx={{
              position: 'relative',
              p: { xs: '32px 22px 28px', sm: '40px 38px 34px' },
              borderRadius: '22px',
              background: gradients.card,
              border: '1px solid rgba(150,195,172,.22)',
              boxShadow: shadows.package,
              overflow: 'hidden',
              '&::before': {
                content: '""',
                position: 'absolute',
                top: 16,
                left: 16,
                width: 11,
                height: 11,
                borderRadius: '50%',
                background: 'rgba(192,138,46,.62)',
                boxShadow: '0 0 12px rgba(232,192,113,.7)',
              },
            }}
          >
            <DieCorner />

            <Box
              className="mono"
              sx={{
                position: 'absolute',
                top: 18,
                right: 20,
                fontSize: 9.5,
                letterSpacing: '2px',
                color: tokens.muted,
              }}
            >
              {stamp}
            </Box>

            <Box sx={{ mb: 3 }}>
              <Typography variant="h2" sx={{ fontSize: { xs: 26, sm: 31 }, letterSpacing: '-.8px' }}>
                {title}
              </Typography>
              {subtitle && (
                <Typography sx={{ mt: 1, fontSize: 14.5, color: tokens.body }}>{subtitle}</Typography>
              )}
            </Box>

            {children}
          </Box>
        </Box>

        {/* Outside the package so every auth screen carries it — including the
            ones a candidate lands on precisely because signing in went wrong. */}
        <Typography sx={{ mt: 2.5, textAlign: 'center', fontSize: 13, color: tokens.body }}>
          Need help? Email us at <SupportEmailLink />
        </Typography>
      </Box>
    </Box>
  </>
)

AuthLayout.propTypes = {
  title: PropTypes.string.isRequired,
  subtitle: PropTypes.string,
  children: PropTypes.node,
  stamp: PropTypes.string,
  wide: PropTypes.bool,
}

export default AuthLayout
