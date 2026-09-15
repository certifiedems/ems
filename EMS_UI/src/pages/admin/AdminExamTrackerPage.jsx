// ems_frontend/src/pages/admin/AdminExamTrackerPage.jsx
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Alert, Box, Button, InputAdornment, LinearProgress, OutlinedInput, Skeleton, Table, TableBody,
  TableCell, TableContainer, TableHead, TablePagination, TableRow, ToggleButton, ToggleButtonGroup, Typography
} from '@mui/material'
import SearchIcon from '@mui/icons-material/SearchRounded'
import RefreshIcon from '@mui/icons-material/RefreshRounded'
import ChevronRightIcon from '@mui/icons-material/ChevronRightRounded'
import EventAvailableIcon from '@mui/icons-material/EventAvailableRounded'
import { adminAPI } from '../../api/adminAPI'
import { getApiErrorMessage } from '../../utils/apiError'
import PageHeader from '../../components/common/PageHeader'
import PcbSelect from '../../components/common/PcbSelect'
import StatusChip from '../../components/common/StatusChip'
import EmptyState from '../../components/common/EmptyState'
import { StageChip, formatDate, formatRelative, formatTime } from '../../components/examTracker/trackerKit'
import { tokens, fonts, surface } from '../../styles/tokens'

const LIVE_REFRESH_MS = 30000
const CLOCK_TICK_MS = 60000
const DAY_MS = 24 * 60 * 60 * 1000

const time = (value) => (value ? new Date(value).getTime() : 0)
const newestFirst = (pick) => (a, b) => time(pick(b)) - time(pick(a))

/**
 * One tab per stage the server reports. Upcoming and live keep the server's
 * soonest-first order; the lists that look back read newest first.
 */
const TABS = [
  { value: 'UPCOMING', label: 'Upcoming', stages: ['UPCOMING'], empty: 'No sittings are booked ahead.' },
  { value: 'LIVE', label: 'Live now', stages: ['LIVE'], empty: 'Nobody is sitting an exam right now.' },
  {
    value: 'AWAITING_SLOT', label: 'Awaiting slot', stages: ['AWAITING_SLOT'],
    sort: newestFirst((b) => b.appliedOn), empty: 'Every paid candidate has booked a slot.',
  },
  {
    value: 'MISSED', label: 'Missed', stages: ['MISSED'],
    sort: newestFirst((b) => b.slot?.start), empty: 'No booked slot has been missed.',
  },
  {
    value: 'COMPLETED', label: 'Completed', stages: ['COMPLETED'],
    sort: newestFirst((b) => b.result?.submittedAt || b.slot?.start), empty: 'No attempt has been submitted yet.',
  },
  {
    value: 'TERMINATED', label: 'Terminated', stages: ['TERMINATED'],
    sort: newestFirst((b) => b.session?.endedAt || b.session?.startedAt), empty: 'No attempt has been terminated.',
  },
  { value: 'ALL', label: 'All', stages: null, empty: 'No paid or booked applications yet.' },
]

const SLOT_WINDOWS = [
  { value: '', label: 'Any time' },
  { value: 'today', label: 'Today' },
  { value: 'next7', label: 'Next 7 days' },
  { value: 'next30', label: 'Next 30 days' },
  { value: 'past7', label: 'Past 7 days' },
  { value: 'past30', label: 'Past 30 days' },
]

const LEVELS = [
  { value: '', label: 'All levels' },
  { value: 'L1', label: 'L1' },
  { value: 'L2', label: 'L2' },
  { value: 'L3', label: 'L3' },
]

const inSlotWindow = (slotStart, windowKey, now) => {
  if (!windowKey) return true
  if (!slotStart) return false
  const at = time(slotStart)
  const startOfToday = new Date(now)
  startOfToday.setHours(0, 0, 0, 0)
  switch (windowKey) {
    case 'today': return at >= startOfToday.getTime() && at < startOfToday.getTime() + DAY_MS
    case 'next7': return at >= now && at < now + 7 * DAY_MS
    case 'next30': return at >= now && at < now + 30 * DAY_MS
    case 'past7': return at < now && at >= now - 7 * DAY_MS
    case 'past30': return at < now && at >= now - 30 * DAY_MS
    default: return true
  }
}

const toggleSx = {
  flexWrap: 'wrap',
  '& .MuiToggleButton-root': {
    px: 1.5,
    height: 38,
    border: `1px solid ${tokens.line2}`,
    color: tokens.body,
    fontSize: 12.5,
    fontWeight: 600,
    textTransform: 'none',
    gap: 0.75,
    '&.Mui-selected': {
      background: 'rgba(192,138,46,.18)',
      color: tokens.copperLt,
      '&:hover': { background: 'rgba(192,138,46,.24)' },
    },
  },
}

const countBadgeSx = {
  minWidth: 20,
  px: 0.6,
  borderRadius: '999px',
  fontFamily: fonts.mono,
  fontSize: 10.5,
  lineHeight: '18px',
  textAlign: 'center',
  background: 'rgba(150,195,172,.12)',
  color: 'inherit',
}

const renderSlot = (booking, now) => {
  const { slot, stage } = booking
  if (!slot) {
    return <Typography sx={{ fontSize: 12.5, color: tokens.muted }}>Not booked yet</Typography>
  }
  let hint = null
  if (stage === 'UPCOMING') {
    hint = slot.startWindowOpen
      ? { text: 'Start window open', color: tokens.greenGlow }
      : { text: `Starts ${formatRelative(slot.start, now)}`, color: tokens.body }
  } else if (stage === 'MISSED') {
    hint = { text: 'Window closed, not started', color: tokens.danger }
  }
  return (
    <Box sx={{ whiteSpace: 'nowrap' }}>
      <Typography sx={{ fontSize: 13, fontWeight: 700, color: tokens.ink }}>{formatDate(slot.start)}</Typography>
      <Typography sx={{ fontSize: 12, fontFamily: fonts.mono, color: tokens.body }}>
        {formatTime(slot.start)}{slot.end ? ` – ${formatTime(slot.end)}` : ''}
      </Typography>
      {hint && <Typography sx={{ fontSize: 11.5, color: hint.color }}>{hint.text}</Typography>}
    </Box>
  )
}

const renderProgress = (booking, now) => {
  const { session, result, exam, stage } = booking
  if (result) {
    const blank = Math.max(0, result.totalQuestions - result.attemptedQuestions)
    return (
      <Box sx={{ whiteSpace: 'nowrap' }}>
        <Typography sx={{ fontSize: 13, fontWeight: 700, color: tokens.ink }}>{Number(result.percentage)}%</Typography>
        <Typography sx={{ fontSize: 11.5, color: tokens.body }}>
          {result.correctAnswers} correct · {result.wrongAnswers} wrong · {blank} blank
        </Typography>
      </Box>
    )
  }
  if (session) {
    const total = session.questionsAssigned || 0
    const share = total ? Math.round((session.questionsAnswered / total) * 100) : 0
    return (
      <Box sx={{ minWidth: 150 }}>
        <Typography sx={{ fontSize: 12.5, fontWeight: 600, color: tokens.ink }}>
          {session.questionsAnswered} of {total} answered
        </Typography>
        <LinearProgress
          variant="determinate"
          value={share}
          aria-label={`${share}% of questions answered`}
          sx={{
            mt: 0.6,
            height: 4,
            borderRadius: 2,
            background: 'rgba(150,195,172,.12)',
            '& .MuiLinearProgress-bar': { background: stage === 'TERMINATED' ? tokens.danger : tokens.greenGlow },
          }}
        />
        <Typography sx={{ mt: 0.4, fontSize: 11, color: tokens.muted }}>
          {session.progressSavedAt ? `Saved ${formatRelative(session.progressSavedAt, now)}` : 'No autosave yet'}
        </Typography>
      </Box>
    )
  }
  return (
    <Typography sx={{ fontSize: 12, color: tokens.muted }}>
      {exam?.questionsPerAttempt ? `${exam.questionsPerAttempt} questions, ` : ''}drawn at start
    </Typography>
  )
}

const AdminExamTrackerPage = () => {
  const navigate = useNavigate()
  const [bookings, setBookings] = useState([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [error, setError] = useState('')
  const [tab, setTab] = useState('UPCOMING')
  const [search, setSearch] = useState('')
  const [level, setLevel] = useState('')
  const [slotWindow, setSlotWindow] = useState('')
  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(25)
  const [now, setNow] = useState(() => Date.now())

  const load = useCallback(async ({ silent = false } = {}) => {
    if (silent) setRefreshing(true)
    else setLoading(true)
    try {
      const res = await adminAPI.getExamTracker()
      setBookings(res.data?.data || [])
      setError('')
    } catch (err) {
      setError(getApiErrorMessage(err, 'Failed to load exam bookings'))
    } finally {
      setLoading(false)
      setRefreshing(false)
      setNow(Date.now())
    }
  }, [])

  useEffect(() => { load() }, [load])

  // "Starts in 2h" has to keep counting down without a refetch.
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), CLOCK_TICK_MS)
    return () => clearInterval(id)
  }, [])

  // Only the live list changes by itself; the others move when someone acts.
  useEffect(() => {
    if (tab !== 'LIVE') return undefined
    const id = setInterval(() => load({ silent: true }), LIVE_REFRESH_MS)
    return () => clearInterval(id)
  }, [tab, load])

  const counts = useMemo(
    () => bookings.reduce((acc, booking) => {
      acc[booking.stage] = (acc[booking.stage] || 0) + 1
      return acc
    }, {}),
    [bookings]
  )

  const activeTab = TABS.find((t) => t.value === tab) || TABS[0]
  const filtersActive = Boolean(search.trim() || level || slotWindow)

  const rows = useMemo(() => {
    const needle = search.trim().toLowerCase()
    const filtered = bookings.filter((booking) => {
      if (activeTab.stages && !activeTab.stages.includes(booking.stage)) return false
      if (level && booking.exam?.certificationLevel !== level) return false
      if (!inSlotWindow(booking.slot?.start, slotWindow, now)) return false
      if (!needle) return true
      return [
        booking.candidate?.name,
        booking.candidate?.email,
        booking.candidate?.userId,
        booking.candidate?.mobileNumber,
        booking.exam?.examCode,
        booking.exam?.examName,
        `#${booking.applicationId}`,
      ].some((value) => value && String(value).toLowerCase().includes(needle))
    })
    return activeTab.sort ? [...filtered].sort(activeTab.sort) : filtered
  }, [bookings, activeTab, search, level, slotWindow, now])

  const pageRows = rows.slice(page * rowsPerPage, page * rowsPerPage + rowsPerPage)
  const openBooking = (applicationId) => navigate(`/admin/exam-tracker/${applicationId}`)

  let content
  if (loading && bookings.length === 0) {
    content = (
      <Box sx={{ p: 2 }}>
        {[1, 2, 3, 4, 5].map((i) => <Skeleton key={i} height={64} />)}
      </Box>
    )
  } else if (rows.length === 0) {
    content = (
      <EmptyState
        icon={<EventAvailableIcon fontSize="large" />}
        title={filtersActive ? 'No bookings match these filters' : activeTab.empty}
        description={filtersActive ? 'Clear the search or widen the level and slot filters.' : undefined}
      />
    )
  } else {
    content = (
      <>
        <TableContainer sx={{ overflowX: 'auto' }}>
          <Table sx={{ minWidth: 980 }}>
            <TableHead>
              <TableRow>
                <TableCell>Slot</TableCell>
                <TableCell>Candidate</TableCell>
                <TableCell>Exam</TableCell>
                <TableCell>Stage</TableCell>
                <TableCell>Progress</TableCell>
                <TableCell align="right">Violations</TableCell>
                <TableCell>Application</TableCell>
                <TableCell padding="checkbox" />
              </TableRow>
            </TableHead>
            <TableBody>
              {pageRows.map((booking) => (
                <TableRow
                  key={booking.applicationId}
                  hover
                  tabIndex={0}
                  onClick={() => openBooking(booking.applicationId)}
                  onKeyDown={(event) => { if (event.key === 'Enter') openBooking(booking.applicationId) }}
                  sx={{ cursor: 'pointer', '&:focus-visible': { outline: `2px solid ${tokens.copper}`, outlineOffset: -2 } }}
                >
                  <TableCell>{renderSlot(booking, now)}</TableCell>
                  <TableCell>
                    <Typography sx={{ fontSize: 13, fontWeight: 700, color: tokens.ink }}>{booking.candidate?.name || '—'}</Typography>
                    <Typography sx={{ fontSize: 11.5, fontFamily: fonts.mono, color: tokens.body }}>
                      {booking.candidate?.userId || '—'}
                    </Typography>
                    <Typography sx={{ fontSize: 11.5, color: tokens.muted }}>{booking.candidate?.email}</Typography>
                  </TableCell>
                  <TableCell>
                    <Typography sx={{ fontSize: 13, fontWeight: 700, color: tokens.ink }}>
                      {booking.exam?.examCode || '—'}
                      <Box component="span" sx={{ ml: 0.75, fontFamily: fonts.mono, fontSize: 11, color: tokens.copperLt }}>
                        {booking.exam?.certificationLevel}
                      </Box>
                    </Typography>
                    <Typography sx={{ fontSize: 11.5, color: tokens.body }}>{booking.exam?.examName}</Typography>
                    {booking.exam?.durationMinutes && (
                      <Typography sx={{ fontSize: 11, color: tokens.muted }}>{booking.exam.durationMinutes} min</Typography>
                    )}
                  </TableCell>
                  <TableCell>
                    <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 0.5 }}>
                      <StageChip stage={booking.stage} />
                      {booking.result && <StatusChip status={booking.result.resultStatus} />}
                    </Box>
                  </TableCell>
                  <TableCell>{renderProgress(booking, now)}</TableCell>
                  <TableCell align="right">
                    <Typography
                      sx={{
                        fontSize: 13,
                        fontWeight: 700,
                        fontVariantNumeric: 'tabular-nums',
                        color: booking.session?.violationCount ? tokens.danger : tokens.muted,
                      }}
                    >
                      {booking.session ? booking.session.violationCount : '—'}
                    </Typography>
                  </TableCell>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>
                    <Typography sx={{ fontSize: 12.5, fontFamily: fonts.mono, color: tokens.ink }}>#{booking.applicationId}</Typography>
                    <Typography sx={{ fontSize: 11, color: tokens.muted }}>
                      Attempt {booking.attemptNumber} of {booking.attemptsAllowed}
                    </Typography>
                  </TableCell>
                  <TableCell padding="checkbox">
                    <ChevronRightIcon sx={{ color: tokens.muted }} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
        <TablePagination
          component="div"
          count={rows.length}
          page={Math.min(page, Math.max(0, Math.ceil(rows.length / rowsPerPage) - 1))}
          onPageChange={(event, next) => setPage(next)}
          rowsPerPage={rowsPerPage}
          onRowsPerPageChange={(event) => { setRowsPerPage(Number(event.target.value)); setPage(0) }}
          rowsPerPageOptions={[10, 25, 50, 100]}
          sx={{ borderTop: `1px solid ${tokens.line}` }}
        />
      </>
    )
  }

  return (
    <Box>
      <PageHeader
        title="Exam Tracker"
        subtitle="Every booked sitting, from slot to result, question by question"
        action={
          <Button
            variant="outlined"
            startIcon={<RefreshIcon />}
            onClick={() => load({ silent: true })}
            disabled={loading || refreshing}
          >
            {refreshing ? 'Refreshing…' : 'Refresh'}
          </Button>
        }
      />

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError('')}>{error}</Alert>
      )}

      <ToggleButtonGroup
        exclusive
        value={tab}
        onChange={(event, next) => { if (next) { setTab(next); setPage(0) } }}
        aria-label="Stage"
        sx={{ ...toggleSx, mb: 1.5 }}
      >
        {TABS.map((option) => (
          <ToggleButton key={option.value} value={option.value}>
            {option.label}
            <Box component="span" sx={countBadgeSx}>
              {option.stages
                ? option.stages.reduce((sum, stage) => sum + (counts[stage] || 0), 0)
                : bookings.length}
            </Box>
          </ToggleButton>
        ))}
      </ToggleButtonGroup>

      <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 1.5, mb: 2 }}>
        <OutlinedInput
          size="small"
          placeholder="Search name, email, exam, #id"
          value={search}
          onChange={(event) => { setSearch(event.target.value); setPage(0) }}
          startAdornment={
            <InputAdornment position="start">
              <SearchIcon sx={{ fontSize: 17, color: tokens.muted }} />
            </InputAdornment>
          }
          sx={{ width: { xs: '100%', sm: 340 }, height: 42, borderRadius: '11px' }}
        />
        <PcbSelect
          size="small"
          label="Level"
          value={level}
          onChange={(value) => { setLevel(value); setPage(0) }}
          options={LEVELS}
          placeholder="All levels"
          sx={{ minWidth: 150 }}
        />
        <PcbSelect
          size="small"
          label="Slot"
          value={slotWindow}
          onChange={(value) => { setSlotWindow(value); setPage(0) }}
          options={SLOT_WINDOWS}
          placeholder="Any time"
          sx={{ minWidth: 170 }}
        />
      </Box>

      <Box sx={{ ...surface, overflow: 'hidden', opacity: refreshing ? 0.6 : 1, transition: 'opacity .2s' }}>
        {content}
      </Box>
    </Box>
  )
}

export default AdminExamTrackerPage
