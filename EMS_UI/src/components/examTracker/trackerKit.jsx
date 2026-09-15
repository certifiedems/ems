// ems_frontend/src/components/examTracker/trackerKit.jsx
import PropTypes from 'prop-types'
import { Box, Chip } from '@mui/material'
import CheckRoundedIcon from '@mui/icons-material/CheckRounded'
import CloseRoundedIcon from '@mui/icons-material/CloseRounded'
import RemoveRoundedIcon from '@mui/icons-material/RemoveRounded'
import HelpOutlineRoundedIcon from '@mui/icons-material/HelpOutlineRounded'
import { fonts, tone as toneMap } from '../../styles/tokens'

/**
 * How each tracker stage reads. The server decides the stage; this only names
 * and colours it, so the list and the detail screen can never disagree.
 */
export const STAGE_META = {
  UPCOMING: { label: 'Upcoming', tone: 'info' },
  LIVE: { label: 'Live now', tone: 'green', live: true },
  AWAITING_SLOT: { label: 'Awaiting slot', tone: 'copper' },
  MISSED: { label: 'Missed', tone: 'danger' },
  COMPLETED: { label: 'Completed', tone: 'neutral' },
  TERMINATED: { label: 'Terminated', tone: 'danger' },
  CLOSED: { label: 'Closed', tone: 'neutral' },
}

/** Every verdict carries an icon and a word, never colour alone. */
export const ANSWER_META = {
  CORRECT: { label: 'Correct', tone: 'green', Icon: CheckRoundedIcon },
  WRONG: { label: 'Wrong', tone: 'danger', Icon: CloseRoundedIcon },
  UNANSWERED: { label: 'Unanswered', tone: 'neutral', Icon: RemoveRoundedIcon },
  NOT_JUDGED: { label: 'Not judged', tone: 'neutral', Icon: HelpOutlineRoundedIcon },
}

const chipSx = (t) => ({
  fontFamily: fonts.mono,
  fontSize: '0.68rem',
  fontWeight: 500,
  letterSpacing: '.6px',
  textTransform: 'uppercase',
  color: t.fg,
  background: t.bg,
  borderColor: t.border,
  '& .MuiChip-icon': { color: t.fg, ml: '7px', mr: '-2px' },
})

export const StageChip = ({ stage, size = 'small' }) => {
  const meta = STAGE_META[stage] || { label: stage || 'Unknown', tone: 'neutral' }
  const t = toneMap[meta.tone]
  return (
    <Chip
      size={size}
      variant="outlined"
      label={meta.label}
      icon={
        meta.live ? (
          <Box
            component="span"
            sx={{
              width: 7,
              height: 7,
              borderRadius: '50%',
              background: t.fg,
              boxShadow: `0 0 8px ${t.fg}`,
              '@keyframes trackerPulse': { '0%,100%': { opacity: 1 }, '50%': { opacity: 0.35 } },
              animation: 'trackerPulse 1.6s ease-in-out infinite',
            }}
          />
        ) : undefined
      }
      sx={chipSx(t)}
    />
  )
}

StageChip.propTypes = {
  stage: PropTypes.string,
  size: PropTypes.oneOf(['small', 'medium']),
}

export const AnswerStateChip = ({ state }) => {
  const meta = ANSWER_META[state] || ANSWER_META.NOT_JUDGED
  const { Icon } = meta
  return (
    <Chip
      size="small"
      variant="outlined"
      icon={<Icon sx={{ fontSize: 14 }} />}
      label={meta.label}
      sx={chipSx(toneMap[meta.tone])}
    />
  )
}

AnswerStateChip.propTypes = {
  state: PropTypes.string,
}

export const formatDateTime = (value) =>
  value ? new Date(value).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' }) : '—'

export const formatDate = (value) =>
  value ? new Date(value).toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' }) : '—'

export const formatTime = (value) =>
  value ? new Date(value).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' }) : '—'

/**
 * A server `LocalDate` ("2026-09-15"). Built from its parts, because
 * `new Date("2026-09-15")` is midnight UTC and shows as the day before anywhere
 * west of Greenwich.
 */
export const formatLocalDate = (value) => {
  if (!value) return '—'
  const [year, month, day] = String(value).split('-').map(Number)
  if (!year || !month || !day) return String(value)
  return new Date(year, month - 1, day).toLocaleDateString('en-IN', { dateStyle: 'medium' })
}

/** "in 2h 15m" / "12m ago". */
export const formatRelative = (value, now = Date.now()) => {
  if (!value) return ''
  const diff = new Date(value).getTime() - now
  const minutes = Math.round(Math.abs(diff) / 60000)
  let text
  if (minutes < 1) {
    text = 'under a minute'
  } else if (minutes < 60) {
    text = `${minutes}m`
  } else if (minutes < 60 * 24) {
    const hours = Math.floor(minutes / 60)
    text = minutes % 60 ? `${hours}h ${minutes % 60}m` : `${hours}h`
  } else {
    const days = Math.floor(minutes / (60 * 24))
    text = `${days} day${days === 1 ? '' : 's'}`
  }
  return diff >= 0 ? `in ${text}` : `${text} ago`
}
