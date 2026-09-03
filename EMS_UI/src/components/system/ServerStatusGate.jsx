// ems_frontend/src/components/system/ServerStatusGate.jsx
import PropTypes from 'prop-types'
import { useLocation } from 'react-router-dom'
import { Box, Typography } from '@mui/material'
import useServerStatus from '../../hooks/useServerStatus'
import MaintenancePage from '../../pages/system/MaintenancePage'
import { fonts, tone } from '../../styles/tokens'

/**
 * The live exam, and only the live exam.
 *
 * `/exam/:applicationId` is a candidate mid-assessment. The sibling routes that
 * share the prefix — `/exam/schedule/:id`, `/exam/payment/:id`,
 * `/exam/result/:id` — all carry a second segment, so this pattern excludes
 * them and they are covered by the takeover like any other page.
 */
const LIVE_EXAM_ROUTE = /^\/exam\/[^/]+$/

/**
 * Warns a candidate without destroying their attempt.
 *
 * Replacing a running exam with a full-screen notice would unmount the webcam,
 * the proctoring monitors and the answer state held in that tree — turning a
 * thirty-second backend hiccup into a lost attempt. The exam autosaves and has
 * its own submission retry, so the right thing here is a strip that informs and
 * gets out of the way.
 */
const ExamOutageBanner = ({ status }) => (
  <Box
    role="status"
    aria-live="polite"
    sx={{
      position: 'fixed',
      top: 0,
      left: 0,
      right: 0,
      zIndex: (theme) => theme.zIndex.modal + 10,
      px: 2,
      py: 1,
      textAlign: 'center',
      background: 'rgba(26,6,6,.94)',
      borderBottom: `1px solid ${tone.danger.border}`,
      backdropFilter: 'blur(6px)',
    }}
  >
    <Typography
      sx={{
        fontFamily: fonts.mono,
        fontSize: 11,
        letterSpacing: '1.2px',
        color: tone.danger.fg,
      }}
    >
      {status === 'offline'
        ? 'CONNECTION LOST — your answers are saved locally and will sync when you reconnect'
        : 'SERVER UNREACHABLE — your answers are saved locally and will sync automatically'}
    </Typography>
  </Box>
)

ExamOutageBanner.propTypes = { status: PropTypes.string.isRequired }

/**
 * Swaps the application for the maintenance screen while the API is
 * unavailable, and restores it automatically once the API answers again.
 *
 * Must render inside the Router — it reads the current path to keep an
 * in-progress exam out of the takeover.
 */
const ServerStatusGate = ({ children }) => {
  const { status, detail, isChecking, nextProbeAt, isDegraded, retryNow } = useServerStatus()
  const { pathname } = useLocation()

  if (!isDegraded) {
    return children
  }

  if (LIVE_EXAM_ROUTE.test(pathname)) {
    return (
      <>
        <ExamOutageBanner status={status} />
        {children}
      </>
    )
  }

  return (
    <MaintenancePage
      status={status}
      detail={detail}
      isChecking={isChecking}
      nextProbeAt={nextProbeAt}
      onRetry={retryNow}
    />
  )
}

ServerStatusGate.propTypes = { children: PropTypes.node }

export default ServerStatusGate
