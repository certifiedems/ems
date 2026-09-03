// ems_frontend/src/pages/system/MaintenancePage.jsx
import { useEffect, useState } from 'react'
import PropTypes from 'prop-types'
import { Box, Button, Typography, CircularProgress } from '@mui/material'
import PcbBackdrop from '../../components/brand/PcbBackdrop'
import BrandMark from '../../components/brand/BrandMark'
import { SERVER_STATUS } from '../../hooks/useServerStatus'
import { tokens, fonts, gradients, shadows, tone, microLabel } from '../../styles/tokens'

/**
 * Copy for each way the service can be unavailable.
 *
 * These are three genuinely different situations and conflating them sends
 * users to the wrong remedy: planned work needs patience, an unreachable server
 * needs nothing at all from them, and a dropped connection needs them to check
 * their own network. Each entry also carries the tone the status pill uses, so
 * a scheduled window never wears the same alarm colour as a real outage.
 */
const PRESENTATION = {
  [SERVER_STATUS.MAINTENANCE]: {
    label: 'Scheduled maintenance',
    title: 'We will be right back',
    body: 'The platform is closed while we complete scheduled work. Nothing you have submitted is affected.',
    tone: tone.copper,
  },
  [SERVER_STATUS.DOWN]: {
    label: 'Service unavailable',
    title: 'Reconnecting to the server',
    body: 'The service is not responding. This is usually a deployment finishing up — this page clears itself the moment the connection is back.',
    tone: tone.danger,
  },
  [SERVER_STATUS.OFFLINE]: {
    label: 'No connection',
    title: 'You appear to be offline',
    body: 'Your device has lost its network connection. Reconnect and this page will continue on its own.',
    tone: tone.info,
  },
}

/** A board trace that resolves into a wrench — maintenance in the PCB idiom. */
const MaintenanceGlyph = () => (
  <svg width="44" height="44" viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <path
      d="M14.7 6.3a3.6 3.6 0 0 0 4.6 4.6l-6.9 6.9a2.3 2.3 0 0 1-3.2-3.2z"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinejoin="round"
    />
    <path
      d="M14.7 6.3 17 4a4.6 4.6 0 0 0-5.6 5.6"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
    <path d="M3 12h4M5 8v8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
  </svg>
)

/**
 * Live countdown to the next automatic probe.
 *
 * Owns its own one-second ticker rather than letting the parent re-render every
 * second: this is the only thing on the screen that changes between probes.
 */
const RetryCountdown = ({ nextProbeAt }) => {
  const [remaining, setRemaining] = useState(() =>
    nextProbeAt ? Math.max(0, Math.ceil((nextProbeAt - Date.now()) / 1000)) : 0,
  )

  useEffect(() => {
    if (!nextProbeAt) {
      setRemaining(0)
      return undefined
    }
    const tick = () => setRemaining(Math.max(0, Math.ceil((nextProbeAt - Date.now()) / 1000)))
    tick()
    const timer = setInterval(tick, 1000)
    return () => clearInterval(timer)
  }, [nextProbeAt])

  if (!nextProbeAt || remaining <= 0) {
    return null
  }

  return (
    <Typography sx={{ ...microLabel, fontSize: 10.5 }}>
      Retrying automatically in {remaining}s
    </Typography>
  )
}

RetryCountdown.propTypes = { nextProbeAt: PropTypes.number }

/**
 * Full-screen stand-in for the application while the API is unavailable.
 *
 * Deliberately self-contained: it renders no route, reads no store slice and
 * makes no API call of its own. Whatever went wrong with the backend, this
 * screen still has to draw.
 */
const MaintenancePage = ({ status, detail, isChecking, nextProbeAt, onRetry }) => {
  const view = PRESENTATION[status] || PRESENTATION[SERVER_STATUS.DOWN]
  // An operator-written message always wins over the generic copy — it is the
  // one line on this screen that can say something specific about *this* window.
  const body = detail?.message || view.body

  return (
    <Box
      sx={{
        position: 'relative',
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        px: 2.5,
        py: 6,
        background: gradients.board,
      }}
    >
      <PcbBackdrop intensity="full" />

      <Box
        role="status"
        aria-live="polite"
        sx={{
          position: 'relative',
          zIndex: 1,
          width: '100%',
          maxWidth: 560,
          p: { xs: 3.5, sm: 5 },
          textAlign: 'center',
          background: gradients.panel,
          border: `1px solid ${tokens.line}`,
          borderRadius: `${tokens.radius + 6}px`,
          boxShadow: shadows.package,
        }}
      >
        <BrandMark size={40} sx={{ justifyContent: 'center', mb: 4 }} />

        <Box
          sx={{
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            width: 84,
            height: 84,
            mb: 3,
            borderRadius: '22px',
            color: view.tone.fg,
            background: view.tone.bg,
            border: `1px solid ${view.tone.border}`,
          }}
        >
          <MaintenanceGlyph />
        </Box>

        <Box
          sx={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 1,
            mb: 2,
            px: 1.5,
            py: 0.6,
            borderRadius: '999px',
            background: view.tone.bg,
            border: `1px solid ${view.tone.border}`,
          }}
        >
          {/* Pulsing dot: the one cue that this screen is actively watching for
              recovery rather than sitting on a dead page waiting for a refresh. */}
          <Box
            sx={{
              width: 7,
              height: 7,
              borderRadius: '50%',
              background: view.tone.fg,
              '@keyframes statusPulse': {
                '0%,100%': { opacity: 0.35, transform: 'scale(.85)' },
                '50%': { opacity: 1, transform: 'scale(1)' },
              },
              animation: 'statusPulse 1.6s ease-in-out infinite',
            }}
          />
          <Typography sx={{ ...microLabel, color: view.tone.fg, fontSize: 10 }}>
            {view.label}
          </Typography>
        </Box>

        <Typography variant="h4" sx={{ fontSize: { xs: 26, sm: 32 }, color: tokens.ink, mb: 1.5 }}>
          {view.title}
        </Typography>

        <Typography sx={{ fontSize: 14.5, lineHeight: 1.7, color: tokens.body, maxWidth: 430, mx: 'auto' }}>
          {body}
        </Typography>

        {detail?.eta ? (
          <Box
            sx={{
              mt: 3,
              px: 2,
              py: 1.4,
              borderRadius: `${tokens.radius}px`,
              background: 'rgba(150,195,172,.06)',
              border: `1px solid ${tokens.line}`,
            }}
          >
            <Typography sx={{ ...microLabel, mb: 0.5 }}>Expected back</Typography>
            <Typography sx={{ fontSize: 14, fontWeight: 700, color: tokens.copperLt }}>
              {detail.eta}
            </Typography>
          </Box>
        ) : null}

        <Button
          onClick={onRetry}
          disabled={isChecking}
          variant="outlined"
          sx={{
            mt: 4,
            minWidth: 190,
            height: 46,
            borderRadius: `${tokens.radius}px`,
            borderColor: tokens.line2,
            color: tokens.ink,
            letterSpacing: '1.2px',
            '&:hover': { borderColor: tokens.copper, background: 'rgba(192,138,46,.10)' },
          }}
          startIcon={isChecking ? <CircularProgress size={15} thickness={4} color="inherit" /> : null}
        >
          {isChecking ? 'Checking' : 'Try again'}
        </Button>

        <Box sx={{ mt: 2.5, minHeight: 16 }}>
          <RetryCountdown nextProbeAt={nextProbeAt} />
        </Box>

        <Box
          sx={{
            mt: 4,
            pt: 2.5,
            borderTop: `1px solid ${tokens.line}`,
            display: 'flex',
            flexWrap: 'wrap',
            justifyContent: 'center',
            gap: 1.5,
          }}
        >
          <Typography sx={{ ...microLabel, fontFamily: fonts.mono }}>
            EMS · {status.toUpperCase()}
          </Typography>
          {detail?.version ? (
            <Typography sx={{ ...microLabel, fontFamily: fonts.mono }}>
              BUILD {detail.version}
            </Typography>
          ) : null}
        </Box>
      </Box>
    </Box>
  )
}

MaintenancePage.propTypes = {
  status: PropTypes.oneOf(Object.values(SERVER_STATUS)).isRequired,
  detail: PropTypes.shape({
    message: PropTypes.string,
    eta: PropTypes.string,
    version: PropTypes.string,
  }),
  isChecking: PropTypes.bool,
  nextProbeAt: PropTypes.number,
  onRetry: PropTypes.func.isRequired,
}

export default MaintenancePage
