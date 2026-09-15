// ems_frontend/src/pages/admin/AdminExamTrackerDetailPage.jsx
import { Fragment, useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  Alert, Box, Button, Chip, Collapse, Grid, Skeleton, Stack, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, ToggleButton, ToggleButtonGroup, Typography
} from '@mui/material'
import ArrowBackIcon from '@mui/icons-material/ArrowBackRounded'
import RefreshIcon from '@mui/icons-material/RefreshRounded'
import CheckIcon from '@mui/icons-material/CheckRounded'
import CloseIcon from '@mui/icons-material/CloseRounded'
import RemoveIcon from '@mui/icons-material/RemoveRounded'
import ExpandMoreIcon from '@mui/icons-material/ExpandMoreRounded'
import FlagIcon from '@mui/icons-material/FlagRounded'
import RadioButtonCheckedIcon from '@mui/icons-material/RadioButtonCheckedRounded'
import { adminAPI } from '../../api/adminAPI'
import { getApiErrorMessage } from '../../utils/apiError'
import PageHeader from '../../components/common/PageHeader'
import {
  AnswerStateChip, StageChip, formatDate, formatDateTime, formatLocalDate, formatRelative, formatTime
} from '../../components/examTracker/trackerKit'
import { formatMoney } from '../../components/dashboard/charts/chartKit'
import { tokens, fonts, surface, microLabel, tone as toneMap } from '../../styles/tokens'

const LIVE_REFRESH_MS = 20000
const OPTION_LETTERS = 'ABCDEFGH'
const panelSx = { ...surface, p: { xs: 2, md: 2.5 } }

const humanize = (value) =>
  value ? String(value).replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase()) : null

/** How the gateway names a payment method, kept as an acronym where it is one ("UPI", not "Upi"). */
const PAYMENT_METHOD_LABELS = { UPI: 'UPI', EMI: 'EMI', CARD: 'Card', NETBANKING: 'Net banking', WALLET: 'Wallet', PAYLATER: 'Pay later' }
const paymentMethodLabel = (method) =>
  method ? PAYMENT_METHOD_LABELS[String(method).toUpperCase()] || humanize(method) : null

const sameOption = (a, b) => String(a ?? '').trim().toLowerCase() === String(b ?? '').trim().toLowerCase()

const plural = (count, one, many = `${one}s`) => `${count} ${count === 1 ? one : many}`

const pulse = {
  '@keyframes stepPulse': { '0%,100%': { opacity: 1 }, '50%': { opacity: 0.3 } },
  animation: 'stepPulse 1.6s ease-in-out infinite',
}

/** Each journey step's look. Every state has a shape as well as a colour. */
const STEP_LOOK = {
  done: { color: tokens.greenGlow, fill: 'rgba(63,211,160,.12)', Icon: CheckIcon },
  current: { color: tokens.copperLt, fill: 'rgba(192,138,46,.12)' },
  failed: { color: tokens.danger, fill: 'rgba(190,40,40,.16)', Icon: CloseIcon },
  skipped: { color: tokens.muted, fill: 'transparent', Icon: RemoveIcon },
  pending: { color: tokens.muted, fill: 'transparent' },
}

/**
 * The application end to end, as the seven things that have to happen for a
 * candidate to walk away certified. Built from the same record the rest of the
 * page shows, so the journey can never tell a different story from the figures.
 */
const buildJourney = ({ booking, payment, certificate }) => {
  const { stage, slot, session, result, exam } = booking
  const terminated = stage === 'TERMINATED'
  const failed = result?.resultStatus === 'FAIL'

  const steps = [
    {
      title: 'Applied',
      state: 'done',
      detail: formatLocalDate(booking.appliedOn),
      meta: `Attempt ${booking.attemptNumber} of ${booking.attemptsAllowed} on this payment`,
    },
  ]

  let paidState = 'pending'
  if (booking.paymentStatus === 'SUCCESS') paidState = 'done'
  else if (booking.paymentStatus === 'FAILED' || booking.paymentStatus === 'REFUNDED') paidState = 'failed'
  steps.push({
    title: 'Paid',
    state: paidState,
    detail: payment
      ? [formatMoney(payment.amount, payment.currency), paymentMethodLabel(payment.paymentMethod), payment.paidAt && formatDateTime(payment.paidAt)]
          .filter(Boolean)
          .join(' · ')
      : humanize(booking.paymentStatus),
    meta: payment?.transactionId,
  })

  let slotState = 'pending'
  if (slot) slotState = stage === 'MISSED' ? 'failed' : 'done'
  else if (stage === 'AWAITING_SLOT') slotState = 'current'
  steps.push({
    title: 'Slot booked',
    state: slotState,
    detail: slot ? `${formatDateTime(slot.start)}${slot.end ? ` – ${formatTime(slot.end)}` : ''}` : 'Not booked yet',
    meta: slot
      ? stage === 'MISSED'
        ? `Start window closed at ${formatTime(slot.startWindowClosesAt)} without a start`
        : `Start window ${formatTime(slot.startWindowOpensAt)} – ${formatTime(slot.startWindowClosesAt)}`
      : null,
  })

  let startState = 'pending'
  let startDetail = 'Not started yet'
  if (session) {
    startState = 'done'
    startDetail = formatDateTime(session.startedAt)
  } else if (stage === 'MISSED') {
    startState = 'failed'
    startDetail = 'Not started'
  } else if (stage === 'UPCOMING' && slot) {
    startState = slot.startWindowOpen ? 'current' : 'pending'
    startDetail = slot.startWindowOpen ? 'Start window is open now' : `Starts ${formatRelative(slot.start)}`
  }
  steps.push({
    title: 'Exam started',
    state: startState,
    detail: startDetail,
    meta: session ? `${plural(session.questionsAssigned, 'question')} drawn` : null,
  })

  let submitted = { state: 'pending', detail: 'Not submitted' }
  if (result) {
    submitted = {
      state: 'done',
      detail: formatDateTime(result.submittedAt),
      meta: `${result.attemptedQuestions} of ${result.totalQuestions} answered`,
    }
  } else if (terminated) {
    submitted = {
      state: 'failed',
      detail: session?.endedAt ? `Ended by proctoring at ${formatDateTime(session.endedAt)}` : 'Ended by proctoring',
      meta: session ? plural(session.violationCount, 'violation') : null,
    }
  } else if (stage === 'LIVE' && session) {
    submitted = {
      state: 'current',
      detail: `In progress · ${session.questionsAnswered} of ${session.questionsAssigned} answered`,
      meta: session.progressSavedAt ? `Last autosave ${formatRelative(session.progressSavedAt)}` : 'No autosave yet',
    }
  }
  steps.push({ title: 'Submitted', ...submitted })

  steps.push({
    title: 'Result',
    state: result ? (failed ? 'failed' : 'done') : terminated ? 'skipped' : 'pending',
    detail: result
      ? `${failed ? 'Not passed' : 'Passed'} with ${Number(result.percentage)}%`
      : terminated ? 'Not scored' : 'Awaiting submission',
    meta: result ? `${Number(result.obtainedMarks)} marks · pass mark ${Number(exam?.passingPercentage)}%` : null,
  })

  let certificateState = 'pending'
  let certificateDetail = 'Issued on a pass'
  if (certificate) {
    certificateState = 'done'
    certificateDetail = certificate.certificateNumber
  } else if (failed || terminated) {
    certificateState = 'skipped'
    certificateDetail = 'Not earned'
  } else if (result) {
    certificateDetail = 'No certificate on record for this attempt'
  }
  steps.push({
    title: 'Certificate',
    state: certificateState,
    detail: certificateDetail,
    meta: certificate
      ? `Issued ${formatLocalDate(certificate.issueDate)} · valid until ${formatLocalDate(certificate.expiryDate)}`
      : null,
  })

  return steps
}

/** What the question list is showing, and how far to trust it. */
const answerNotice = ({ booking, answerSource }) => {
  const { stage, session, result, exam } = booking
  if (!session) {
    if (stage === 'MISSED') {
      return {
        severity: 'warning',
        text: 'The start window closed before the candidate began, so no question paper was drawn. They can book a new slot on the same payment.',
      }
    }
    const count = exam?.questionsPerAttempt ? plural(exam.questionsPerAttempt, 'question') : 'The questions'
    return {
      severity: 'info',
      text: `The question paper is drawn the moment the candidate starts, not when the slot is booked, so there is nothing to show yet. ${count} will be drawn for this sitting.`,
    }
  }
  if (answerSource === 'AUTOSAVE' && stage === 'LIVE') {
    return {
      severity: 'info',
      text: `Live answers from the candidate's latest autosave${session.progressSavedAt ? ` (${formatRelative(session.progressSavedAt)})` : ''}. Verdicts are provisional until the attempt is submitted. This page refreshes every 20 seconds.`,
    }
  }
  if (answerSource === 'AUTOSAVE' && result) {
    return {
      severity: 'warning',
      text: `This attempt was submitted before submitted answers were stored, so these answers come from its last autosave and may miss the final changes. The official result is ${result.correctAnswers} correct and ${result.wrongAnswers} wrong.`,
    }
  }
  if (answerSource === 'AUTOSAVE') {
    return { severity: 'info', text: 'Answers from the last autosave before the attempt ended.' }
  }
  if (answerSource === 'NONE' && result) {
    return {
      severity: 'warning',
      text: `This attempt was scored before answers were stored and left no autosave, so individual answers cannot be shown. The official result is ${result.correctAnswers} correct and ${result.wrongAnswers} wrong.`,
    }
  }
  if (answerSource === 'NONE' && stage === 'LIVE') {
    return { severity: 'info', text: 'The candidate has not saved an answer yet. This page refreshes every 20 seconds.' }
  }
  return null
}

const renderFacts = (rows) => (
  <Box
    component="dl"
    sx={{ m: 0, display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'minmax(110px, 36%) 1fr' }, columnGap: 2, rowGap: 0.9 }}
  >
    {rows
      .filter(([, value]) => value !== undefined)
      .map(([label, value]) => (
        <Fragment key={label}>
          <Typography component="dt" sx={{ fontSize: 12, color: tokens.muted }}>{label}</Typography>
          <Typography component="dd" sx={{ m: 0, fontSize: 13, color: tokens.ink, wordBreak: 'break-word' }}>
            {value === null || value === '' ? '—' : value}
          </Typography>
        </Fragment>
      ))}
  </Box>
)

const filterToggleSx = {
  flexWrap: 'wrap',
  '& .MuiToggleButton-root': {
    px: 1.25,
    height: 34,
    border: `1px solid ${tokens.line2}`,
    color: tokens.body,
    fontSize: 12,
    fontWeight: 600,
    textTransform: 'none',
    '&.Mui-selected': {
      background: 'rgba(192,138,46,.18)',
      color: tokens.copperLt,
      '&:hover': { background: 'rgba(192,138,46,.24)' },
    },
  },
}

const AdminExamTrackerDetailPage = () => {
  const { applicationId } = useParams()
  const navigate = useNavigate()
  const [detail, setDetail] = useState(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [error, setError] = useState('')
  const [filter, setFilter] = useState('ALL')
  const [expanded, setExpanded] = useState(() => new Set())

  const load = useCallback(async ({ silent = false } = {}) => {
    if (silent) setRefreshing(true)
    else setLoading(true)
    try {
      const res = await adminAPI.getExamTrackerBooking(applicationId)
      setDetail(res.data?.data || null)
      setError('')
    } catch (err) {
      setError(getApiErrorMessage(err, 'Failed to load this exam booking'))
    } finally {
      setLoading(false)
      setRefreshing(false)
    }
  }, [applicationId])

  useEffect(() => { load() }, [load])

  const stage = detail?.booking?.stage
  useEffect(() => {
    if (stage !== 'LIVE') return undefined
    const id = setInterval(() => load({ silent: true }), LIVE_REFRESH_MS)
    return () => clearInterval(id)
  }, [stage, load])

  const questions = useMemo(() => detail?.questions || [], [detail])
  const counts = useMemo(() => ({
    ALL: questions.length,
    CORRECT: questions.filter((q) => q.state === 'CORRECT').length,
    WRONG: questions.filter((q) => q.state === 'WRONG').length,
    UNANSWERED: questions.filter((q) => q.state === 'UNANSWERED').length,
    MARKED: questions.filter((q) => q.markedForReview).length,
  }), [questions])

  const visibleQuestions = useMemo(() => {
    if (filter === 'ALL') return questions
    if (filter === 'MARKED') return questions.filter((q) => q.markedForReview)
    return questions.filter((q) => q.state === filter)
  }, [questions, filter])

  const toggleQuestion = (number) => setExpanded((current) => {
    const next = new Set(current)
    if (next.has(number)) next.delete(number)
    else next.add(number)
    return next
  })
  const allOpen = visibleQuestions.length > 0 && visibleQuestions.every((q) => expanded.has(q.number))
  const toggleAll = () => setExpanded(allOpen ? new Set() : new Set(visibleQuestions.map((q) => q.number)))

  const backButton = (
    <Button variant="outlined" startIcon={<ArrowBackIcon />} onClick={() => navigate('/admin/exam-tracker')}>
      Exam Tracker
    </Button>
  )

  if (loading && !detail) {
    return (
      <Box>
        <Skeleton variant="rounded" height={96} sx={{ mb: 2 }} />
        <Grid container spacing={2}>
          {[1, 2, 3, 4, 5, 6].map((i) => (
            <Grid item xs={6} md={2} key={i}><Skeleton variant="rounded" height={96} /></Grid>
          ))}
          <Grid item xs={12} md={5}><Skeleton variant="rounded" height={420} /></Grid>
          <Grid item xs={12} md={7}><Skeleton variant="rounded" height={420} /></Grid>
        </Grid>
      </Box>
    )
  }

  if (!detail) {
    return (
      <Box>
        <PageHeader title="Exam booking" action={backButton} />
        <Alert
          severity="error"
          action={<Button color="inherit" size="small" onClick={() => load()}>Retry</Button>}
        >
          {error || 'This exam booking could not be found.'}
        </Alert>
      </Box>
    )
  }

  const { booking, payment, sessionDetails, answerSource, violations, certificate } = detail
  const { candidate, exam, slot, session, result } = booking
  const journey = buildJourney(detail)
  const notice = answerNotice(detail)

  const provisional = !result && answerSource !== 'NONE' && questions.length > 0
  const assigned = result ? result.totalQuestions : session?.questionsAssigned
  const answered = result ? result.attemptedQuestions : session?.questionsAnswered

  let slotCaption = 'The candidate has not picked a time'
  if (slot) {
    const when = stage === 'UPCOMING' && slot.startWindowOpen ? 'window open now' : formatRelative(slot.start)
    slotCaption = `${formatTime(slot.start)}${slot.end ? ` – ${formatTime(slot.end)}` : ''} · ${when}`
  }

  const figures = [
    { label: 'Slot', value: slot ? formatDate(slot.start) : 'Not booked', caption: slotCaption },
    {
      label: 'Questions',
      value: session ? assigned : '—',
      caption: session
        ? 'drawn when the attempt started'
        : `${exam?.questionsPerAttempt ?? '—'} to be drawn at start`,
    },
    {
      label: 'Answered',
      value: session ? `${answered} / ${assigned}` : '—',
      caption: session ? `${Math.max(0, (assigned || 0) - (answered || 0))} blank · ${session.markedForReview} marked` : null,
    },
    {
      label: 'Correct',
      value: result ? result.correctAnswers : provisional ? counts.CORRECT : '—',
      caption: provisional ? 'provisional' : null,
      accent: toneMap.green.fg,
    },
    {
      label: 'Wrong',
      value: result ? result.wrongAnswers : provisional ? counts.WRONG : '—',
      caption: provisional ? 'provisional' : null,
      accent: toneMap.danger.fg,
    },
    {
      label: 'Score',
      value: result ? `${Number(result.percentage)}%` : '—',
      caption: exam
        ? `${result ? `${result.resultStatus === 'PASS' ? 'Passed' : 'Not passed'} · ` : ''}pass mark ${Number(exam.passingPercentage)}%`
        : null,
    },
  ]

  const deadline = stage === 'LIVE' && session?.startedAt && exam?.durationMinutes
    ? formatTime(new Date(new Date(session.startedAt).getTime() + exam.durationMinutes * 60000))
    : undefined

  const factSections = [
    {
      title: 'Candidate',
      rows: [
        ['Name', candidate?.name],
        ['User ID', candidate?.userId],
        ['Email', candidate?.email],
        ['Mobile', candidate?.mobileNumber],
      ],
    },
    {
      title: 'Exam',
      rows: [
        ['Exam', exam ? `${exam.examCode} · ${exam.examName}` : null],
        ['Level', exam?.certificationLevel],
        ['Duration', exam?.durationMinutes ? `${exam.durationMinutes} minutes` : null],
        ['Questions per attempt', exam?.questionsPerAttempt],
        ['Application', `#${booking.applicationId} · ${humanize(booking.applicationStatus)}`],
      ],
    },
    {
      title: 'Payment',
      rows: payment
        ? [
            ['Transaction', payment.transactionId],
            ['Status', humanize(payment.status)],
            ['Amount', formatMoney(payment.amount, payment.currency)],
            ['Method', [paymentMethodLabel(payment.paymentMethod), payment.paymentMethodDetail].filter(Boolean).join(' · ') || null],
            ['Environment', humanize(payment.gatewayMode)],
            ['Paid at', payment.paidAt ? formatDateTime(payment.paidAt) : null],
          ]
        : [['Status', humanize(booking.paymentStatus)]],
    },
    {
      title: 'Session',
      rows: session
        ? [
            ['Session', `#${session.sessionId} · ${humanize(session.status)}`],
            ['Started', formatDateTime(session.startedAt)],
            ['Ended', session.endedAt ? formatDateTime(session.endedAt) : null],
            ['Time runs out', deadline],
            ['On question', session.lastQuestionNumber ? `${session.lastQuestionNumber} of ${session.questionsAssigned}` : null],
            ['Last autosave', session.progressSavedAt
              ? `${formatDateTime(session.progressSavedAt)} (${formatRelative(session.progressSavedAt)})`
              : null],
            ['Violations', session.violationCount],
            ['IP address', sessionDetails?.ipAddress],
            ['Browser', sessionDetails?.browserFingerprint],
            ['Rules accepted', sessionDetails?.rulesAcceptedAt
              ? `${formatDateTime(sessionDetails.rulesAcceptedAt)}${sessionDetails.rulesVersion ? ` · ${sessionDetails.rulesVersion}` : ''}`
              : null],
          ]
        : [['Status', 'Not started']],
    },
  ]

  const answerFilters = [
    { value: 'ALL', label: 'All' },
    { value: 'CORRECT', label: 'Correct' },
    { value: 'WRONG', label: 'Wrong' },
    { value: 'UNANSWERED', label: 'Unanswered' },
    { value: 'MARKED', label: 'Marked for review' },
  ]

  return (
    <Box>
      <PageHeader
        title={candidate?.name || 'Candidate'}
        subtitle={`${exam ? `${exam.examCode} · ${exam.examName}` : 'Exam'} · Application #${booking.applicationId}`}
        breadcrumbs={[
          { label: 'Home', to: '/admin/dashboard' },
          { label: 'Exam Tracker', to: '/admin/exam-tracker' },
          { label: `#${booking.applicationId}` },
        ]}
        action={
          <>
            <StageChip stage={stage} size="medium" />
            {result && <Chip size="medium" label={result.resultStatus === 'PASS' ? 'Passed' : 'Not passed'} variant="outlined" sx={{
              fontFamily: fonts.mono, fontSize: '0.72rem', textTransform: 'uppercase', letterSpacing: '.6px',
              color: result.resultStatus === 'PASS' ? toneMap.green.fg : toneMap.danger.fg,
              background: result.resultStatus === 'PASS' ? toneMap.green.bg : toneMap.danger.bg,
              borderColor: result.resultStatus === 'PASS' ? toneMap.green.border : toneMap.danger.border,
            }} />}
            <Button
              variant="outlined"
              startIcon={<RefreshIcon />}
              onClick={() => load({ silent: true })}
              disabled={refreshing}
            >
              {refreshing ? 'Refreshing…' : 'Refresh'}
            </Button>
            {backButton}
          </>
        }
      />

      {error && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError('')}>{error}</Alert>}

      <Box sx={{ opacity: refreshing ? 0.7 : 1, transition: 'opacity .2s' }}>
        <Grid container spacing={2} sx={{ mb: 2 }}>
          {figures.map((figure) => (
            <Grid item xs={6} sm={4} lg={2} key={figure.label}>
              <Box
                sx={{
                  ...surface,
                  p: 1.75,
                  height: '100%',
                  position: 'relative',
                  overflow: 'hidden',
                  '&::after': figure.accent
                    ? { content: '""', position: 'absolute', left: 0, top: 0, bottom: 0, width: 2, background: figure.accent, opacity: 0.6 }
                    : undefined,
                }}
              >
                <Typography sx={microLabel}>{figure.label}</Typography>
                <Typography sx={{ mt: 0.75, fontSize: 21, fontWeight: 800, lineHeight: 1.15, color: tokens.ink }}>
                  {figure.value}
                </Typography>
                {figure.caption && (
                  <Typography sx={{ mt: 0.5, fontSize: 11.5, color: tokens.body }}>{figure.caption}</Typography>
                )}
              </Box>
            </Grid>
          ))}
        </Grid>

        <Grid container spacing={2} sx={{ mb: 2 }}>
          <Grid item xs={12} md={5}>
            <Box sx={{ ...panelSx, height: '100%' }}>
              <Typography sx={{ ...microLabel, mb: 2 }}>Journey</Typography>
              <Box component="ol" sx={{ m: 0, p: 0, listStyle: 'none' }}>
                {journey.map((step, index) => {
                  const look = STEP_LOOK[step.state]
                  const last = index === journey.length - 1
                  const StepIcon = look.Icon
                  return (
                    <Box component="li" key={step.title} sx={{ display: 'flex', gap: 1.5 }}>
                      <Box sx={{ flex: 'none', display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
                        <Box
                          sx={{
                            width: 24,
                            height: 24,
                            borderRadius: '50%',
                            display: 'grid',
                            placeItems: 'center',
                            color: look.color,
                            background: look.fill,
                            border: `1.5px ${step.state === 'pending' ? 'dashed' : 'solid'} ${look.color}`,
                          }}
                        >
                          {StepIcon ? (
                            <StepIcon sx={{ fontSize: 14 }} />
                          ) : (
                            <Box
                              sx={{
                                width: 7,
                                height: 7,
                                borderRadius: '50%',
                                background: step.state === 'current' ? look.color : 'transparent',
                                ...(step.state === 'current' ? pulse : {}),
                              }}
                            />
                          )}
                        </Box>
                        {!last && (
                          <Box
                            sx={{
                              flexGrow: 1,
                              width: '1.5px',
                              minHeight: 16,
                              my: 0.5,
                              background: step.state === 'done' ? 'rgba(63,211,160,.4)' : tokens.line,
                            }}
                          />
                        )}
                      </Box>
                      <Box sx={{ minWidth: 0, pb: last ? 0 : 1.75 }}>
                        <Typography
                          sx={{
                            fontSize: 13.5,
                            fontWeight: 700,
                            lineHeight: '24px',
                            color: step.state === 'pending' || step.state === 'skipped' ? tokens.body : tokens.ink,
                          }}
                        >
                          {step.title}
                        </Typography>
                        {step.detail && <Typography sx={{ fontSize: 12.5, color: tokens.body }}>{step.detail}</Typography>}
                        {step.meta && (
                          <Typography sx={{ fontSize: 11.5, color: tokens.muted, wordBreak: 'break-word' }}>{step.meta}</Typography>
                        )}
                      </Box>
                    </Box>
                  )
                })}
              </Box>
            </Box>
          </Grid>

          <Grid item xs={12} md={7}>
            <Box sx={{ ...panelSx, height: '100%' }}>
              <Stack spacing={2.25}>
                {factSections.map((section, index) => (
                  <Box
                    key={section.title}
                    sx={index ? { pt: 2.25, borderTop: `1px solid ${tokens.line}` } : undefined}
                  >
                    <Typography sx={{ ...microLabel, mb: 1.25 }}>{section.title}</Typography>
                    {renderFacts(section.rows)}
                  </Box>
                ))}
              </Stack>
            </Box>
          </Grid>
        </Grid>

        <Box sx={{ ...panelSx, mb: 2 }}>
          <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 1.5, mb: 1.75 }}>
            <Box>
              <Typography sx={{ fontSize: 15, fontWeight: 700, color: tokens.ink }}>Question paper</Typography>
              <Typography sx={{ fontSize: 12.5, color: tokens.body }}>
                {questions.length
                  ? `${plural(questions.length, 'question')} in the order the candidate was given them`
                  : 'No paper drawn yet'}
              </Typography>
            </Box>
            {questions.length > 0 && (
              <Box sx={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 1 }}>
                <ToggleButtonGroup
                  exclusive
                  size="small"
                  value={filter}
                  onChange={(event, next) => next && setFilter(next)}
                  aria-label="Show questions"
                  sx={filterToggleSx}
                >
                  {answerFilters.map((option) => (
                    <ToggleButton key={option.value} value={option.value}>
                      {option.label} · {counts[option.value]}
                    </ToggleButton>
                  ))}
                </ToggleButtonGroup>
                <Button size="small" onClick={toggleAll} disabled={visibleQuestions.length === 0}>
                  {allOpen ? 'Collapse all' : 'Expand all'}
                </Button>
              </Box>
            )}
          </Box>

          {notice && <Alert severity={notice.severity} sx={{ mb: questions.length ? 1.75 : 0 }}>{notice.text}</Alert>}

          <Stack spacing={1}>
            {visibleQuestions.map((question) => {
              const open = expanded.has(question.number)
              const strayAnswers = question.selectedOptions.filter(
                (selected) => selected && !question.options.some((option) => sameOption(option, selected))
              )
              return (
                <Box
                  key={question.number}
                  sx={{ border: `1px solid ${tokens.line}`, borderRadius: '12px', background: 'rgba(3,16,11,.35)', overflow: 'hidden' }}
                >
                  <Box
                    component="button"
                    type="button"
                    onClick={() => toggleQuestion(question.number)}
                    aria-expanded={open}
                    sx={{
                      all: 'unset',
                      boxSizing: 'border-box',
                      display: 'block',
                      width: '100%',
                      px: 2,
                      py: 1.5,
                      cursor: 'pointer',
                      '&:hover': { background: 'rgba(192,138,46,.05)' },
                      '&:focus-visible': { outline: `2px solid ${tokens.copper}`, outlineOffset: -2 },
                    }}
                  >
                    <Box sx={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 1 }}>
                      <Typography sx={{ minWidth: 36, fontFamily: fonts.mono, fontSize: 12, fontWeight: 600, color: tokens.copperLt }}>
                        Q{question.number}
                      </Typography>
                      <Typography sx={{ fontFamily: fonts.mono, fontSize: 11, color: tokens.muted }}>
                        {[
                          question.questionCode,
                          question.severity,
                          question.marks != null && plural(Number(question.marks), 'mark'),
                          humanize(question.questionType),
                        ].filter(Boolean).join(' · ')}
                      </Typography>
                      <Box sx={{ flexGrow: 1 }} />
                      {question.markedForReview && (
                        <Chip
                          size="small"
                          variant="outlined"
                          icon={<FlagIcon sx={{ fontSize: 13 }} />}
                          label="Marked"
                          sx={{
                            fontFamily: fonts.mono, fontSize: '0.68rem', textTransform: 'uppercase',
                            color: toneMap.copper.fg, background: toneMap.copper.bg, borderColor: toneMap.copper.border,
                            '& .MuiChip-icon': { color: toneMap.copper.fg, ml: '7px' },
                          }}
                        />
                      )}
                      <AnswerStateChip state={question.state} />
                      <ExpandMoreIcon
                        sx={{ color: tokens.muted, transform: open ? 'rotate(180deg)' : 'none', transition: 'transform .2s' }}
                      />
                    </Box>
                    <Typography
                      sx={{
                        mt: 0.75,
                        fontSize: 13.5,
                        lineHeight: 1.5,
                        color: question.questionText ? tokens.ink : tokens.muted,
                        ...(open ? {} : { display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }),
                      }}
                    >
                      {question.questionText || `Question #${question.questionId} has since been deleted from the bank.`}
                    </Typography>
                  </Box>

                  <Collapse in={open} unmountOnExit>
                    <Stack spacing={0.75} sx={{ px: 2, pb: 2 }}>
                      {question.options.map((option, index) => {
                        const isCorrect = question.correctOptions.some((correct) => sameOption(correct, option))
                        const isSelected = question.selectedOptions.some((selected) => sameOption(selected, option))
                        const highlight = isCorrect || isSelected
                        const t = isCorrect ? toneMap.green : isSelected ? toneMap.danger : toneMap.neutral
                        return (
                          <Box
                            key={`${question.number}-${index}`}
                            sx={{
                              display: 'flex',
                              alignItems: 'flex-start',
                              flexWrap: 'wrap',
                              gap: 1.25,
                              px: 1.25,
                              py: 1,
                              borderRadius: '10px',
                              border: `1px solid ${highlight ? t.border : tokens.line}`,
                              background: highlight ? t.bg : 'transparent',
                            }}
                          >
                            <Typography sx={{ minWidth: 16, fontFamily: fonts.mono, fontSize: 12, fontWeight: 600, color: highlight ? t.fg : tokens.muted }}>
                              {OPTION_LETTERS[index] || index + 1}
                            </Typography>
                            <Typography sx={{ flex: '1 1 220px', fontSize: 13, color: tokens.ink }}>{option}</Typography>
                            <Stack direction="row" spacing={0.75} sx={{ flex: 'none' }}>
                              {isSelected && (
                                <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, fontSize: 11.5, color: isCorrect ? toneMap.green.fg : toneMap.danger.fg }}>
                                  <RadioButtonCheckedIcon sx={{ fontSize: 14 }} /> Candidate&apos;s answer
                                </Box>
                              )}
                              {isCorrect && (
                                <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, fontSize: 11.5, color: toneMap.green.fg }}>
                                  <CheckIcon sx={{ fontSize: 14 }} /> Correct answer
                                </Box>
                              )}
                            </Stack>
                          </Box>
                        )
                      })}
                      {question.options.length === 0 && question.selectedOptions.length > 0 && (
                        <Typography sx={{ fontSize: 12.5, color: tokens.body }}>
                          Answered: {question.selectedOptions.join(', ')}
                        </Typography>
                      )}
                      {question.options.length > 0 && strayAnswers.length > 0 && (
                        <Typography sx={{ fontSize: 12, color: tokens.muted }}>
                          Also sent, matching no current option: {strayAnswers.join(', ')}
                        </Typography>
                      )}
                    </Stack>
                  </Collapse>
                </Box>
              )
            })}
            {questions.length > 0 && visibleQuestions.length === 0 && (
              <Typography sx={{ py: 3, textAlign: 'center', fontSize: 13, color: tokens.muted }}>
                No questions in this group.
              </Typography>
            )}
          </Stack>
        </Box>

        {session && (
          <Box sx={{ ...panelSx, p: 0, overflow: 'hidden' }}>
            <Box sx={{ p: { xs: 2, md: 2.5 }, pb: 1.5 }}>
              <Typography sx={{ fontSize: 15, fontWeight: 700, color: tokens.ink }}>Proctoring violations</Typography>
              <Typography sx={{ fontSize: 12.5, color: tokens.body }}>
                {violations.length ? `${plural(violations.length, 'violation')}, newest first` : 'None recorded for this attempt'}
              </Typography>
            </Box>
            {violations.length > 0 && (
              <TableContainer sx={{ overflowX: 'auto' }}>
                <Table size="small" sx={{ minWidth: 640 }}>
                  <TableHead>
                    <TableRow>
                      <TableCell>Detected</TableCell>
                      <TableCell>Type</TableCell>
                      <TableCell align="right">Strike</TableCell>
                      <TableCell>Action</TableCell>
                      <TableCell>Description</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {violations.map((violation) => (
                      <TableRow key={violation.violationId} hover>
                        <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDateTime(violation.detectedAt)}</TableCell>
                        <TableCell sx={{ whiteSpace: 'nowrap' }}>{humanize(violation.violationType)}</TableCell>
                        <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{violation.violationLevel}</TableCell>
                        <TableCell sx={{ whiteSpace: 'nowrap', color: violation.actionTaken === 'EXAM_TERMINATED' ? tokens.danger : undefined }}>
                          {humanize(violation.actionTaken) || '—'}
                        </TableCell>
                        <TableCell sx={{ minWidth: 220 }}>{violation.description || '—'}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          </Box>
        )}
      </Box>
    </Box>
  )
}

export default AdminExamTrackerDetailPage
