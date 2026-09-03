// ems_frontend/src/pages/admin/AdminBookingWindowsPage.jsx
//
// When each exam accepts bookings, who is stuck behind a window that has run
// out, and how to reopen it.
//
// This exists because the rule had no operator. An exam's booking window is
// enforced on every schedule call, and until now it was set once by the seed
// script — NOW() + 30 days — and then nothing could ever move it. Thirty days
// on, every candidate holding a paid application was refused for every date
// they tried, the schedule screen told them to contact support, and support had
// no button either. POST /exams/{id}/schedule had been on the server the whole
// time with nothing calling it.
//
// Kept apart from Exam Management on purpose. That screen is about what an exam
// *is* — its paper, its marks, its pass mark — and is edited when an exam is
// designed. This one is about when it can be sat, is read when something is
// wrong, and is the screen someone opens with a stuck candidate on the phone.
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Box, Paper, Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
  Button, Dialog, DialogTitle, DialogContent, DialogActions, Grid, Skeleton,
  Snackbar, Alert, Stack, Chip, Typography, Divider
} from '@mui/material'
import { adminAPI } from '../../api/adminAPI'
import PageHeader from '../../components/common/PageHeader'
import EmptyState from '../../components/common/EmptyState'
import PcbDateField from '../../components/common/PcbDateField'
import { fonts, tokens, tone } from '../../styles/tokens'
import EventAvailableIcon from '@mui/icons-material/EventAvailableRounded'
import EventBusyIcon from '@mui/icons-material/EventBusyRounded'
import LockClockIcon from '@mui/icons-material/LockClockRounded'
import EditCalendarIcon from '@mui/icons-material/EditCalendarRounded'
import RefreshIcon from '@mui/icons-material/RefreshRounded'
import HelpOutlineIcon from '@mui/icons-material/HelpOutlineRounded'
import PeopleIcon from '@mui/icons-material/PeopleAltRounded'

const DAY_MS = 24 * 60 * 60 * 1000

/**
 * An ISO instant rendered for `PcbDateField`.
 *
 * The field reads and writes local wall-clock text and has no timezone of its
 * own, so it must be handed the local time rather than the ISO string the
 * server sent. Feeding it UTC silently shifts the bound by the offset, which on
 * a window end is the difference between reopening an exam and closing it five
 * and a half hours early.
 */
const toLocalInputValue = (isoString) => {
  if (!isoString) return ''
  const date = new Date(isoString)
  if (Number.isNaN(date.getTime())) return ''
  return new Date(date.getTime() - date.getTimezoneOffset() * 60 * 1000)
    .toISOString()
    .slice(0, 16)
}

/** A local `YYYY-MM-DDTHH:mm` back to the ISO instant the API takes. */
const toIso = (localValue) => {
  if (!localValue) return null
  const date = new Date(localValue)
  return Number.isNaN(date.getTime()) ? null : date.toISOString()
}

const formatBound = (isoString) => {
  if (!isoString) return null
  const date = new Date(isoString)
  return Number.isNaN(date.getTime())
    ? null
    : date.toLocaleString(undefined, {
      day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit'
    })
}

const plural = (count, one, many) => `${count} ${count === 1 ? one : many}`

/**
 * Where an exam's booking window stands right now.
 *
 * `CLOSED` is the state this screen exists for: it is the one that silently
 * breaks scheduling for every candidate holding a paid application, and it
 * looks identical to a healthy exam on every other admin screen.
 */
const windowState = (row, now) => {
  const opensAt = row.bookingOpensAt ? new Date(row.bookingOpensAt).getTime() : null
  const closesAt = row.bookingClosesAt ? new Date(row.bookingClosesAt).getTime() : null
  if (opensAt === null && closesAt === null) return 'UNSET'
  if (closesAt !== null && now > closesAt) return 'CLOSED'
  if (opensAt !== null && now < opensAt) return 'PENDING'
  return 'OPEN'
}

const STATE_META = {
  OPEN: { label: 'Open', tone: 'green', icon: EventAvailableIcon },
  CLOSED: { label: 'Closed', tone: 'danger', icon: EventBusyIcon },
  PENDING: { label: 'Not open yet', tone: 'info', icon: LockClockIcon },
  UNSET: { label: 'No window', tone: 'neutral', icon: HelpOutlineIcon }
}

/** One-click ends for the dialog, measured from now. */
const END_PRESETS = [
  { label: '+3 months', days: 91 },
  { label: '+6 months', days: 182 },
  { label: '+1 year', days: 365 },
  { label: '+2 years', days: 730 }
]

const StateChip = ({ state }) => {
  const meta = STATE_META[state]
  const t = tone[meta.tone]
  const Icon = meta.icon
  return (
    <Chip
      size="small"
      variant="outlined"
      icon={<Icon sx={{ fontSize: 15 }} />}
      label={meta.label}
      sx={{
        fontFamily: fonts.mono,
        fontSize: '0.68rem',
        height: 24,
        color: t.fg,
        background: t.bg,
        borderColor: t.border,
        '& .MuiChip-icon': { color: 'inherit' }
      }}
    />
  )
}

/** The current window as two lines, or a dash when the exam has none. */
const WindowCell = ({ row }) => {
  const opens = formatBound(row.bookingOpensAt)
  const closes = formatBound(row.bookingClosesAt)
  if (!opens && !closes) {
    return <Typography sx={{ fontFamily: fonts.mono, fontSize: 12, color: tokens.muted }}>—</Typography>
  }
  return (
    <Stack spacing={0.25}>
      <Typography sx={{ fontFamily: fonts.mono, fontSize: 12, color: tokens.body }}>
        {opens || 'no start'} →
      </Typography>
      <Typography sx={{ fontFamily: fonts.mono, fontSize: 12, color: tokens.ink }}>
        {closes || 'no end'}
      </Typography>
    </Stack>
  )
}

/**
 * How many people this row is holding up, and how many it is not.
 *
 * "Waiting" is red only where the window is actually shut. The same candidates
 * behind an open window are not stuck — they simply have not booked yet, which
 * is an ordinary state and must not be dressed up as an incident.
 */
const CandidatesCell = ({ row, blocked }) => {
  if (!row.waitingApplications && !row.bookedApplications) {
    return <Typography sx={{ fontFamily: fonts.mono, fontSize: 12, color: tokens.muted }}>—</Typography>
  }
  return (
    <Stack spacing={0.25}>
      {row.waitingApplications > 0 && (
        <Typography
          sx={{
            fontFamily: fonts.mono,
            fontSize: 12.5,
            fontWeight: blocked ? 700 : 500,
            color: blocked ? tone.danger.fg : tokens.body
          }}
        >
          {plural(row.waitingApplications, 'waiting', 'waiting')}
        </Typography>
      )}
      {row.bookedApplications > 0 && (
        <Typography sx={{ fontFamily: fonts.mono, fontSize: 12, color: tokens.muted }}>
          {plural(row.bookedApplications, 'booked', 'booked')}
        </Typography>
      )}
    </Stack>
  )
}

const AdminBookingWindowsPage = () => {
  const [rows, setRows] = useState([])
  const [loading, setLoading] = useState(true)
  const [feedback, setFeedback] = useState(null)
  const [editing, setEditing] = useState(null)
  const [saving, setSaving] = useState(false)
  const [form, setForm] = useState({ opensAt: '', closesAt: '' })

  /*
   * Ticks so a window that lapses while this screen is open starts reading
   * "Closed" on its own. An admin leaves this tab sitting there — it is the one
   * they keep open while working through a support queue — and a stale "Open"
   * beside an exam that has since shut is the same wrong answer this whole
   * screen was built to stop giving.
   */
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const tick = setInterval(() => setNow(Date.now()), 30000)
    return () => clearInterval(tick)
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await adminAPI.getBookingWindows()
      setRows(res.data.data || [])
    } catch (err) {
      setFeedback({ severity: 'error', msg: err.response?.data?.message || 'Failed to load booking windows' })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  /*
   * Blocked exams first, and among those the one holding up the most people.
   * The table is short enough to read whole, but the order is what the screen
   * is for: an admin opening it should not have to scan for the row that is on
   * fire.
   */
  const ordered = useMemo(() => {
    const rank = { CLOSED: 0, PENDING: 1, UNSET: 2, OPEN: 3 }
    return [...rows].sort((a, b) => {
      const byState = rank[windowState(a, now)] - rank[windowState(b, now)]
      if (byState !== 0) return byState
      const byWaiting = (b.waitingApplications || 0) - (a.waitingApplications || 0)
      return byWaiting !== 0 ? byWaiting : String(a.examCode).localeCompare(String(b.examCode))
    })
  }, [rows, now])

  const impact = useMemo(() => {
    const closed = rows.filter((row) => windowState(row, now) === 'CLOSED')
    return {
      exams: closed.length,
      blocked: closed.reduce((sum, row) => sum + (row.waitingApplications || 0), 0),
      unset: rows.filter((row) => windowState(row, now) === 'UNSET').length
    }
  }, [rows, now])

  const openDialog = (row) => {
    const state = windowState(row, now)
    setEditing(row)
    /*
     * A closed window opens on a fix rather than on what is already there.
     * Everything about arriving here says the end date is wrong, and prefilling
     * the expired one asks the admin to retype the very field they came to
     * change. The start is kept: when booking originally opened is history, and
     * moving it forward would lock out nobody usefully.
     */
    setForm({
      opensAt: toLocalInputValue(row.bookingOpensAt) || toLocalInputValue(new Date().toISOString()),
      closesAt: state === 'CLOSED'
        ? toLocalInputValue(new Date(Date.now() + 365 * DAY_MS).toISOString())
        : toLocalInputValue(row.bookingClosesAt)
    })
  }

  const closeDialog = () => {
    setEditing(null)
    setSaving(false)
  }

  const applyPreset = (days) => {
    setForm((prev) => ({
      ...prev,
      closesAt: toLocalInputValue(new Date(Date.now() + days * DAY_MS).toISOString())
    }))
  }

  const opensAtMs = form.opensAt ? new Date(form.opensAt).getTime() : null
  const closesAtMs = form.closesAt ? new Date(form.closesAt).getTime() : null
  /*
   * The same rule the server applies, so the admin is not told "end must be
   * after start" by a round trip. `ExamServiceImpl.schedule` rejects it too —
   * this only spares them the trip.
   */
  const rangeInvalid = opensAtMs !== null && closesAtMs !== null && closesAtMs <= opensAtMs
  const endsInPast = closesAtMs !== null && closesAtMs < Date.now()
  const canSave = Boolean(form.opensAt && form.closesAt) && !rangeInvalid

  const save = async () => {
    if (!editing || !canSave) return
    setSaving(true)
    try {
      await adminAPI.scheduleExam(editing.examId, {
        scheduledStartTime: toIso(form.opensAt),
        scheduledEndTime: toIso(form.closesAt)
      })
      const freed = editing.waitingApplications || 0
      setFeedback({
        severity: 'success',
        msg: freed > 0
          ? `Booking window updated for ${editing.examCode} — ${plural(freed, 'candidate', 'candidates')} can now book.`
          : `Booking window updated for ${editing.examCode}.`
      })
      closeDialog()
      load()
    } catch (err) {
      setFeedback({ severity: 'error', msg: err.response?.data?.message || 'Failed to update booking window' })
      setSaving(false)
    }
  }

  const tableContent = (
    <TableContainer>
      <Table>
        <TableHead>
          <TableRow>
            <TableCell>Exam</TableCell>
            <TableCell>Level</TableCell>
            <TableCell>Booking window</TableCell>
            <TableCell>Candidates</TableCell>
            <TableCell>State</TableCell>
            <TableCell align="right">Actions</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {ordered.map((row) => {
            const state = windowState(row, now)
            const blocked = state === 'CLOSED' && row.waitingApplications > 0
            return (
              <TableRow
                key={row.examId}
                hover
                sx={blocked ? { background: 'rgba(190,40,40,.07)' } : undefined}
              >
                <TableCell>
                  <Stack spacing={0.25}>
                    <Typography sx={{ fontFamily: fonts.mono, fontSize: 12.5, color: tokens.ink }}>
                      {row.examCode}
                    </Typography>
                    <Typography sx={{ fontSize: 12, color: tokens.muted }}>{row.examName}</Typography>
                  </Stack>
                </TableCell>
                <TableCell>{row.certificationLevel}</TableCell>
                <TableCell><WindowCell row={row} /></TableCell>
                <TableCell><CandidatesCell row={row} blocked={blocked} /></TableCell>
                <TableCell><StateChip state={state} /></TableCell>
                <TableCell align="right">
                  <Button
                    size="small"
                    variant={state === 'CLOSED' ? 'contained' : 'outlined'}
                    startIcon={<EditCalendarIcon />}
                    onClick={() => openDialog(row)}
                  >
                    {state === 'CLOSED' ? 'Reopen' : 'Set window'}
                  </Button>
                </TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </TableContainer>
  )

  let paperContent
  if (loading) {
    paperContent = <Box sx={{ p: 2 }}>{[1, 2, 3].map((i) => <Skeleton key={i} height={64} />)}</Box>
  } else if (rows.length === 0) {
    paperContent = <EmptyState title="No exams yet" description="Create an exam before setting a booking window." />
  } else {
    paperContent = tableContent
  }

  return (
    <Box>
      <PageHeader
        title="Booking Windows"
        subtitle="When candidates can book a slot for each exam"
        action={
          <Button variant="outlined" startIcon={<RefreshIcon />} onClick={load} disabled={loading}>
            Refresh
          </Button>
        }
      />

      {/*
        * Led with, not buried in the table. A closed window produces no error
        * anywhere in the admin product — the exam still reads PUBLISHED and
        * SCHEDULED on every other screen — so if this page does not say it out
        * loud, the only symptom is a candidate who cannot book and cannot say
        * why. The headline is the number of people affected rather than the
        * number of exams, because one closed exam holding up forty candidates
        * and one holding up nobody need very different responses.
        */}
      {!loading && impact.exams > 0 && (
        <Alert severity="error" sx={{ mb: 2 }} icon={<EventBusyIcon fontSize="inherit" />}>
          {impact.blocked > 0
            ? <>
              <strong>{plural(impact.blocked, 'candidate is', 'candidates are')} blocked.</strong>{' '}
              {plural(impact.exams, 'exam has', 'exams have')} stopped taking bookings, and every one of
              those candidates has paid and cannot schedule or reschedule, whatever date they pick.
              Reopen the window to unblock them — their payments and applications are untouched.
            </>
            : <>
              {plural(impact.exams, 'exam has', 'exams have')} stopped taking bookings. Nobody is waiting
              on {impact.exams === 1 ? 'it' : 'them'} right now, but new applications are refused too.
            </>}
        </Alert>
      )}

      {!loading && impact.unset > 0 && (
        <Alert severity="info" sx={{ mb: 2 }}>
          {plural(impact.unset, 'exam has', 'exams have')} no booking window at all. That is not an
          error — an exam without bounds accepts any future date.
        </Alert>
      )}

      <Paper>{paperContent}</Paper>

      <Dialog open={Boolean(editing)} onClose={closeDialog} maxWidth="sm" fullWidth>
        <DialogTitle>
          {editing?.examCode} · booking window
          <Typography sx={{ fontSize: 12.5, color: tokens.muted, mt: 0.5 }}>
            {editing?.examName}
          </Typography>
        </DialogTitle>
        <DialogContent dividers>
          {editing?.waitingApplications > 0 && (
            <Alert severity="warning" icon={<PeopleIcon fontSize="inherit" />} sx={{ mb: 2 }}>
              {plural(editing.waitingApplications, 'candidate has', 'candidates have')} paid and still
              {editing.waitingApplications === 1 ? ' needs' : ' need'} a slot for this exam.
            </Alert>
          )}

          <Typography sx={{ fontSize: 13, lineHeight: 1.6, color: tokens.body, mb: 2 }}>
            Candidates may book any slot inside this range. The server refuses anything outside it,
            and the schedule screen will not offer it.
          </Typography>

          <Grid container spacing={2}>
            <Grid item xs={12} sm={6}>
              <PcbDateField
                type="datetime-local"
                fullWidth
                label="Booking opens"
                value={form.opensAt}
                onChange={(value) => setForm((prev) => ({ ...prev, opensAt: value }))}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <PcbDateField
                type="datetime-local"
                fullWidth
                label="Booking closes"
                value={form.closesAt}
                min={form.opensAt || undefined}
                onChange={(value) => setForm((prev) => ({ ...prev, closesAt: value }))}
                error={rangeInvalid}
                helperText={rangeInvalid ? 'Must be after the opening time' : undefined}
              />
            </Grid>
          </Grid>

          {/*
            * Not marked `disablePast`. A window that has already closed is a
            * legitimate thing to set — an exam can be retired deliberately —
            * and a picker that refuses past dates would make the one state this
            * screen reports impossible to create on purpose. It is warned about
            * below instead, which is the honest treatment of a valid but
            * usually-unintended choice.
            */}
          <Typography
            sx={{
              display: 'block', mt: 2.5, mb: 1, fontFamily: fonts.mono, fontSize: 9.5,
              letterSpacing: '.5px', textTransform: 'uppercase', color: tokens.muted
            }}
          >
            Close it
          </Typography>
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
            {END_PRESETS.map((preset) => (
              <Button
                key={preset.label}
                size="small"
                variant="outlined"
                onClick={() => applyPreset(preset.days)}
                sx={{ fontFamily: fonts.mono, fontSize: 11.5 }}
              >
                {preset.label}
              </Button>
            ))}
          </Stack>

          {endsInPast && (
            <Alert severity="warning" sx={{ mt: 2.5 }}>
              This closes the window in the past, which stops the exam being booked at all —
              including by anyone still waiting. Deliberate for an exam you are retiring; otherwise
              pick a later date.
            </Alert>
          )}

          <Divider sx={{ my: 2.5, borderColor: tokens.line }} />

          {/*
            * Both of these surprise people. Reopening does not rebook anyone —
            * the candidate still has to go and pick a time — and the endpoint
            * moves the exam to SCHEDULED as a side effect, which is worth
            * saying before it happens rather than after.
            */}
          <Typography sx={{ fontSize: 12.5, lineHeight: 1.6, color: tokens.muted }}>
            Saving marks this exam <strong style={{ color: tokens.body }}>SCHEDULED</strong>. Candidates
            whose slot has already passed are not rebooked automatically — reopening lets them pick a new
            time themselves, with their existing payment and application intact.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={closeDialog} disabled={saving}>Cancel</Button>
          <Button variant="contained" onClick={save} disabled={!canSave || saving}>
            {saving ? 'Saving…' : 'Save window'}
          </Button>
        </DialogActions>
      </Dialog>

      <Snackbar
        open={Boolean(feedback)}
        autoHideDuration={6000}
        onClose={() => setFeedback(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        {feedback ? (
          <Alert severity={feedback.severity} onClose={() => setFeedback(null)} sx={{ width: '100%' }}>
            {feedback.msg}
          </Alert>
        ) : undefined}
      </Snackbar>
    </Box>
  )
}

export default AdminBookingWindowsPage
