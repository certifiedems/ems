// ems_frontend/src/pages/exam/ExamApplicationPage.jsx
import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import {
  Box, Card, Button, Typography, Collapse, IconButton,
  Dialog, DialogContent, DialogActions,
  Radio, RadioGroup, FormControlLabel,
  Alert, Skeleton, Stack, Menu, MenuItem
} from '@mui/material'
import { userAPI } from '../../api/userAPI'
import { examAPI } from '../../api/examAPI'
import StatusChip from '../../components/common/StatusChip'
import { tokens, fonts, gradients, shadows, ctaButton } from '../../styles/tokens'
import EmptyState from '../../components/common/EmptyState'
import SyllabusPanel from '../../components/syllabus/SyllabusPanel'
import PageHeader from '../../components/common/PageHeader'
import SyllabusDialog from '../../components/syllabus/SyllabusDialog'
import { getSyllabus } from '../../data/syllabus'
import { bookingWindowClosed, examWindowState, formatExamSlot, isClosedStatus, nextStep } from '../../utils/examJourney'
import AddIcon from '@mui/icons-material/AddRounded'
import AssignmentIcon from '@mui/icons-material/AssignmentRounded'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import MenuBookIcon from '@mui/icons-material/MenuBookRounded'
import CloseIcon from '@mui/icons-material/CloseRounded'
import CheckIcon from '@mui/icons-material/CheckRounded'

const LEVELS = ['L1', 'L2', 'L3']

/** Applications shown per level before the pager kicks in. */
const APPS_PAGE_SIZE = 5

const isClosed = isClosedStatus

/** Badge / pill palette for a level section, keyed on how the level ended up. */
const levelTone = (anyPassed, allFailed) => {
  if (anyPassed) {
    return { bg: 'rgba(95,174,146,.12)', border: 'rgba(95,174,146,.4)', fg: tokens.greenLt, label: 'PASSED' }
  }
  if (allFailed) {
    return { bg: 'rgba(224,101,101,.12)', border: 'rgba(224,101,101,.4)', fg: '#E06565', label: 'FAILED' }
  }
  return { bg: 'rgba(192,138,46,.12)', border: 'rgba(192,138,46,.4)', fg: tokens.copperLt, label: 'IN PROGRESS' }
}

const panelSx = {
  borderRadius: '18px',
  background: gradients.card,
  border: `1px solid ${tokens.line}`,
  boxShadow: shadows.card,
  overflow: 'hidden',
}

const ExamApplicationPage = () => {
  const navigate = useNavigate()
  const location = useLocation()
  const [applications, setApplications] = useState([])
  const [loading, setLoading] = useState(true)
  const [reApplying, setReApplying] = useState(null)
  const [reApplyError, setReApplyError] = useState('')

  // New application dialog state
  const [open, setOpen] = useState(false)
  const [activeStep, setActiveStep] = useState(0)
  const [level, setLevel] = useState(location.state?.level || 'L1')
  const [options, setOptions] = useState(null)
  const [optionsLoading, setOptionsLoading] = useState(false)
  const [selectedExamId, setSelectedExamId] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const restartNotice = location.state?.restartMessage || ''

  // Level whose syllabus is open in the modal; null when the modal is closed.
  const [syllabusLevel, setSyllabusLevel] = useState(null)
  const [syllabusMenuAnchor, setSyllabusMenuAnchor] = useState(null)

  // Per-level accordion + pager state, keyed by level code.
  const [collapsed, setCollapsed] = useState({})
  const [levelPage, setLevelPage] = useState({})

  const openSyllabus = (lvl) => {
    setSyllabusMenuAnchor(null)
    setSyllabusLevel(lvl)
  }

  const loadApplications = async () => {
    try {
      const [dashboardRes, reApplyableRes] = await Promise.all([
        userAPI.getDashboard(),
        examAPI.getReApplyableApplications().catch(() => ({ data: { data: [] } }))
      ])

      const dashboardApps = dashboardRes.data.data?.examStatuses || []
      const reApplyableApps = reApplyableRes.data.data || []
      const reApplyMap = new Map(reApplyableApps.map((app) => [app.applicationId, app]))

      const mergedApplications = dashboardApps.map((app) => {
        const reApplyMeta = reApplyMap.get(app.applicationId)
        const failedLike = isClosed(app.applicationStatus)
        return {
          ...app,
          ...(reApplyMeta || {}),
          canReApply: Boolean(reApplyMeta?.canReApply) || failedLike,
          restartRequired: Boolean(reApplyMeta?.restartRequired),
          restartMessage: reApplyMeta?.restartMessage || null
        }
      })

      const mergedById = new Map(mergedApplications.map((app) => [app.applicationId, app]))
      reApplyableApps.forEach((app) => {
        if (!mergedById.has(app.applicationId)) {
          mergedById.set(app.applicationId, {
            ...app,
            canReApply: Boolean(app.canReApply) || isClosed(app.applicationStatus)
          })
        }
      })

      setApplications(Array.from(mergedById.values()))
    } catch (err) {
      console.error('Failed to load applications', err)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadApplications()
  }, [])

  const handleReApply = async (applicationId) => {
    setReApplying(applicationId)
    setReApplyError('')
    try {
      const res = await examAPI.reApply(applicationId)
      const newApplicationId = res.data.data?.applicationId
      if (!newApplicationId) {
        throw new Error('Re-application created, but no new application id was returned.')
      }
      navigate(`/exam/payment/${newApplicationId}`, { replace: true })
    } catch (err) {
      setReApplyError(err.response?.data?.message || 'Failed to re-apply. Please try again.')
    } finally {
      setReApplying(null)
    }
  }

  const openDialog = () => {
    setActiveStep(0)
    setSelectedExamId('')
    setError('')
    setOpen(true)
  }

  const loadOptions = async (lvl) => {
    setOptionsLoading(true)
    setError('')
    try {
      const res = await examAPI.getWorkflowOptions(lvl)
      setOptions(res.data.data)
    } catch (err) {
      setError(err.response?.data?.message || 'Failed to load exam options')
      setOptions(null)
    } finally {
      setOptionsLoading(false)
    }
  }

  const handleNext = async () => {
    if (activeStep === 0) {
      await loadOptions(level)
      setActiveStep(1)
    } else if (activeStep === 1) {
      await handleApply()
    }
  }

  const handleApply = async () => {
    /*
     * Guarded as well as disabled. The option cannot be picked while its exam is
     * outside its booking window, but the window can lapse between opening this
     * dialog and pressing Apply — and the next screen after this one is payment,
     * which is the last place to discover the exam cannot be booked.
     */
    const chosen = (options?.availableExams || []).find((ex) => String(ex.examId) === String(selectedExamId))
    const chosenIssue = chosen ? bookingIssue(chosen) : null
    if (chosenIssue) {
      setError(chosenIssue)
      return
    }
    setSubmitting(true)
    setError('')
    try {
      const res = await examAPI.applyForExam({
        certificationLevel: level,
        examId: Number(selectedExamId),
        remarks: ''
      })
      const applicationId = res.data.data?.applicationId
      setOpen(false)
      navigate(`/exam/payment/${applicationId}`)
    } catch (err) {
      setError(err.response?.data?.message || 'Failed to apply for exam')
    } finally {
      setSubmitting(false)
    }
  }

  const eligible = options?.eligibility?.eligible !== false
  const availableExams = options?.availableExams || []
  const wizardSyllabus = getSyllabus(level)

  /*
   * Whether an exam can actually be booked, on the same bounds the server
   * checks. An exam outside its window is still listed — hiding it would leave
   * "No exams are currently available" as the only explanation for a level the
   * candidate can plainly see they are eligible for — but it cannot be picked,
   * because the step straight after this one takes their money.
   */
  const bookingIssue = (exam) => {
    const now = Date.now()
    const opensAt = exam.scheduledStartTime ? new Date(exam.scheduledStartTime).getTime() : null
    const closesAt = exam.scheduledEndTime ? new Date(exam.scheduledEndTime).getTime() : null
    if (closesAt !== null && now > closesAt) {
      return `Stopped taking bookings on ${formatExamSlot(exam.scheduledEndTime)} — contact support.`
    }
    if (opensAt !== null && now < opensAt) {
      return `Opens for booking on ${formatExamSlot(exam.scheduledStartTime)}.`
    }
    return null
  }

  const groupedApplications = applications.reduce((acc, app) => {
    const levelKey = app.certificationLevel || 'UNKNOWN'
    if (!acc[levelKey]) {
      acc[levelKey] = []
    }
    acc[levelKey].push(app)
    return acc
  }, {})

  // Highest level first, matching the way the journey is read top-down.
  const groupedEntries = Object.entries(groupedApplications)
    .map(([levelKey, apps]) => [levelKey, apps.sort((a, b) => b.applicationId - a.applicationId)])
    .sort(([a], [b]) => String(b).localeCompare(String(a)))

  let stepTwoContent
  if (optionsLoading) {
    stepTwoContent = <Skeleton variant="rounded" height={120} />
  } else if (!eligible) {
    stepTwoContent = (
      <Alert severity="warning">
        {options?.eligibility?.message || `You are not currently eligible for ${level}.`}
      </Alert>
    )
  } else if (availableExams.length === 0) {
    stepTwoContent = <Alert severity="info">No exams are currently available for {level}.</Alert>
  } else {
    stepTwoContent = (
      <RadioGroup
        value={selectedExamId}
        onChange={(e) => setSelectedExamId(e.target.value)}
        sx={{ gap: 1.25 }}
      >
        {availableExams.map((ex) => {
          const selected = selectedExamId === String(ex.examId)
          const issue = bookingIssue(ex)
          return (
            <FormControlLabel
              key={ex.examId}
              value={String(ex.examId)}
              disabled={Boolean(issue)}
              control={<Radio sx={{ p: 0, mr: 1.75, color: 'rgba(150,195,172,.35)' }} />}
              sx={{
                m: 0,
                p: '16px 18px',
                borderRadius: '14px',
                alignItems: 'center',
                transition: 'background .15s, border-color .15s',
                background: selected ? 'rgba(192,138,46,.12)' : 'rgba(95,174,146,.06)',
                border: `1.5px solid ${selected ? 'rgba(192,138,46,.5)' : tokens.line}`,
                ...(issue && { opacity: 0.55, background: 'rgba(150,195,172,.04)' }),
              }}
              label={
                <Box sx={{ minWidth: 0 }}>
                  <Typography sx={{ fontSize: 15, fontWeight: 700 }}>{ex.examName}</Typography>
                  <Typography sx={{ mt: 0.5, fontFamily: fonts.mono, fontSize: 11.5, color: '#93AC9E' }}>
                    {ex.examCode} · {ex.durationMinutes} min · Pass {String(ex.passingPercentage)}%
                  </Typography>
                  {/*
                    * The reason sits on the row rather than in a tooltip. A
                    * greyed option with no explanation reads as a bug in the
                    * page, and this one is the difference between "come back
                    * later" and "someone has to reopen this for you".
                    */}
                  {issue && (
                    <Typography sx={{ mt: 0.75, fontSize: 12, lineHeight: 1.5, color: '#E0A0A0' }}>
                      {issue}
                    </Typography>
                  )}
                </Box>
              }
            />
          )
        })}
      </RadioGroup>
    )
  }

  /** The chosen exam's booking problem, if the one they picked has developed one. */
  const selectedExamIssue = (() => {
    const chosen = availableExams.find((ex) => String(ex.examId) === String(selectedExamId))
    return chosen ? bookingIssue(chosen) : null
  })()

  const banners = (
    <>
      {reApplyError && (
        <Alert severity="error" onClose={() => setReApplyError('')}>
          {reApplyError}
        </Alert>
      )}
      {restartNotice && <Alert severity="warning">{restartNotice}</Alert>}
    </>
  )

  let mainContent
  if (loading) {
    mainContent = (
      <Stack spacing={2}>
        {banners}
        {[1, 2, 3].map((i) => (
          <Skeleton key={i} variant="rounded" height={92} sx={{ borderRadius: '18px' }} />
        ))}
      </Stack>
    )
  } else if (applications.length === 0) {
    mainContent = (
      <Card>
        <EmptyState
          icon={<AssignmentIcon fontSize="large" />}
          title="No applications yet"
          description="Start your certification journey by applying for an L1 exam."
          action={<Button variant="contained" startIcon={<AddIcon />} onClick={openDialog}>Apply now</Button>}
        />
      </Card>
    )
  } else {
    mainContent = (
      <Stack spacing={2.75}>
        {banners}

        {groupedEntries.map(([levelKey, levelApps]) => {
          const anyPassed = levelApps.some((app) => app.applicationStatus === 'PASSED')
          const allFailed = levelApps.every((app) => isClosed(app.applicationStatus))
          const tone = levelTone(anyPassed, allFailed)

          // Re-applying supersedes the previous attempt, so only the newest
          // application in a level can offer it — and a level that has already
          // been passed cannot be re-attempted at all. `levelApps` is sorted
          // newest-first, so the head is the only candidate.
          const reApplyableId = anyPassed ? null : levelApps[0]?.applicationId
          const expanded = collapsed[levelKey] !== true

          const pageCount = Math.max(1, Math.ceil(levelApps.length / APPS_PAGE_SIZE))
          // Derived, not stored: a reload returning fewer applications must not
          // strand the pager on a page that no longer exists.
          const page = Math.min(levelPage[levelKey] || 1, pageCount)
          const pageStart = (page - 1) * APPS_PAGE_SIZE
          const visibleApps = levelApps.slice(pageStart, pageStart + APPS_PAGE_SIZE)

          return (
            <Box key={levelKey} sx={panelSx}>

              {/* ---- level header ---- */}
              <Box
                role="button"
                tabIndex={0}
                onClick={() => setCollapsed((prev) => ({ ...prev, [levelKey]: expanded }))}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    setCollapsed((prev) => ({ ...prev, [levelKey]: expanded }))
                  }
                }}
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 2,
                  p: '20px 22px',
                  cursor: 'pointer',
                  flexWrap: { xs: 'wrap', sm: 'nowrap' },
                  rowGap: 1.25,
                  '&:hover': { background: 'rgba(192,138,46,.04)' },
                }}
              >
                <Box
                  sx={{
                    flex: 'none',
                    width: 48,
                    height: 48,
                    borderRadius: '13px',
                    display: 'grid',
                    placeItems: 'center',
                    background: tone.bg,
                    border: `1.5px solid ${tone.border}`,
                    color: tone.fg,
                    fontFamily: fonts.mono,
                    fontWeight: 700,
                    fontSize: 16,
                  }}
                >
                  {levelKey}
                </Box>

                <Box sx={{ flex: 1, minWidth: 0 }}>
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.25, flexWrap: 'wrap' }}>
                    <Typography sx={{ fontSize: 17, fontWeight: 700 }}>{levelKey} Exam</Typography>
                    <Box
                      component="span"
                      sx={{
                        display: 'inline-flex',
                        px: 1.25,
                        py: '3px',
                        borderRadius: 999,
                        fontFamily: fonts.mono,
                        fontSize: 9.5,
                        fontWeight: 600,
                        letterSpacing: '.06em',
                        background: tone.bg,
                        border: `1px solid ${tone.border}`,
                        color: tone.fg,
                      }}
                    >
                      {tone.label}
                    </Box>
                  </Box>
                  <Typography sx={{ mt: 0.5, fontSize: 12.5, color: '#93AC9E' }}>
                    {levelApps.length} application{levelApps.length === 1 ? '' : 's'}
                    {LEVELS.includes(levelKey) && (
                      <>
                        {' · '}
                        <Box
                          component="span"
                          role="link"
                          tabIndex={0}
                          onClick={(e) => { e.stopPropagation(); setSyllabusLevel(levelKey) }}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter') { e.stopPropagation(); setSyllabusLevel(levelKey) }
                          }}
                          sx={{ color: tokens.copperLt, cursor: 'pointer', '&:hover': { textDecoration: 'underline' } }}
                        >
                          View {levelKey} syllabus
                        </Box>
                      </>
                    )}
                  </Typography>
                </Box>

                <ExpandMoreIcon
                  sx={{
                    flex: 'none',
                    color: '#93AC9E',
                    transform: expanded ? 'rotate(180deg)' : 'rotate(0deg)',
                    transition: 'transform .2s',
                  }}
                />
              </Box>

              {/* ---- applications ---- */}
              <Collapse in={expanded} timeout="auto" unmountOnExit>
                <Box sx={{ borderTop: `1px solid ${tokens.line}`, px: '22px', pt: 0.75, pb: 2.25 }}>
                  {visibleApps.map((app) => {
                    const canReApply = Boolean(app.canReApply || app.restartRequired)
                    const showReApply = canReApply && app.applicationId === reApplyableId
                    const step = canReApply ? null : nextStep(app)
                    const failed = isClosed(app.applicationStatus)
                    // Offered next to Start, so a candidate who wants a different
                    // time does not have to guess that the start screen is where
                    // rescheduling lives. Withheld once the exam has stopped
                    // taking bookings: there is no slot left to move to, and the
                    // button would only lead to a picker that refuses every date.
                    const showReschedule = Boolean(step?.label === 'Start Exam') && !bookingWindowClosed(app)
                    const slot = formatExamSlot(app.scheduledExamTime)
                    // Outside the window `step` already points at the booking
                    // screen, so this only has to name what happened to the
                    // slot; the button beside it says what to do about it.
                    const slotLabel = app.attemptInProgress
                      ? 'Started'
                      : examWindowState(app) === 'MISSED' ? 'Missed' : 'Booked'
                    // Said on the row itself, not left for the screen behind the
                    // button. A slot marked "Missed" beside no way to rebook is
                    // the product looking broken; naming the reason is the whole
                    // difference between that and a candidate who knows to ask.
                    const bookingShut = !failed
                      && app.applicationStatus !== 'PASSED'
                      && !app.attemptInProgress
                      && bookingWindowClosed(app)

                    return (
                      <Box
                        key={app.applicationId}
                        sx={{
                          display: 'flex',
                          flexDirection: 'column',
                          gap: 1.25,
                          py: 1.75,
                          borderBottom: '1px solid rgba(150,195,172,.08)',
                          '&:last-of-type': { borderBottom: 'none' },
                        }}
                      >
                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}>
                          <Box
                            sx={{
                              flex: 'none',
                              width: 9,
                              height: 9,
                              borderRadius: '50%',
                              background: failed ? '#E06565' : app.applicationStatus === 'PASSED' ? tokens.greenLt : tokens.copperLt,
                            }}
                          />
                          <Box sx={{ minWidth: 0 }}>
                            <Typography component="span" sx={{ fontSize: 13.5, fontWeight: 700, whiteSpace: 'nowrap' }}>
                              Application #{app.applicationId}
                            </Typography>
                            <Typography
                              component="span"
                              sx={{ ml: 1, fontFamily: fonts.mono, fontSize: 10.5, color: '#93AC9E', whiteSpace: 'nowrap' }}
                            >
                              {app.appliedOn ? new Date(app.appliedOn).toLocaleDateString() : '-'}
                            </Typography>
                          </Box>

                          <StatusChip status={app.applicationStatus} />

                          {slot && !failed && app.applicationStatus !== 'PASSED' && (
                            <Typography
                              sx={{
                                fontFamily: fonts.mono,
                                fontSize: 10.5,
                                // A missed slot in the same copper as a live
                                // booking reads as "all fine"; it is the one
                                // slot state on this row that needs acting on.
                                color: slotLabel === 'Missed' ? '#E06565' : tokens.copperLt,
                                whiteSpace: 'nowrap'
                              }}
                            >
                              {slotLabel} · {slot}
                            </Typography>
                          )}

                          <Box sx={{ ml: { sm: 'auto' }, display: 'flex', gap: 1, flexWrap: 'wrap' }}>
                            {step && (
                              <Button
                                size="small"
                                variant="contained"
                                onClick={() => navigate(step.route)}
                                sx={{ height: 34, px: 1.75, fontSize: 11, borderRadius: '9px' }}
                              >
                                {step.label}
                              </Button>
                            )}
                            {showReschedule && (
                              <Button
                                size="small"
                                variant="outlined"
                                onClick={() => navigate(`/exam/schedule/${app.applicationId}`)}
                                sx={{ height: 34, px: 1.75, fontSize: 11, borderRadius: '9px' }}
                              >
                                Reschedule
                              </Button>
                            )}
                            {showReApply && (
                              <Button
                                size="small"
                                variant="contained"
                                disabled={reApplying === app.applicationId}
                                onClick={() => handleReApply(app.applicationId)}
                                sx={{ ...ctaButton, width: 'auto', height: 34, px: 1.75, fontSize: 11, letterSpacing: '.2px', textTransform: 'none', borderRadius: '9px' }}
                              >
                                {reApplying === app.applicationId ? 'Re-applying…' : 'Re-apply & Pay Again'}
                              </Button>
                            )}
                          </Box>
                        </Box>

                        {bookingShut && (
                          <Box sx={{ pl: '21px' }}>
                            <Typography sx={{ fontSize: 12.5, lineHeight: 1.5, color: '#E0A0A0' }}>
                              This exam stopped taking bookings on {formatExamSlot(app.bookingClosesAt)}.
                              Contact support to have the window reopened — your payment stays on this application.
                            </Typography>
                          </Box>
                        )}

                        {(app.remarks || app.restartRequired) && (
                          <Box sx={{ pl: '21px' }}>
                            {app.remarks && (
                              <Typography sx={{ fontSize: 12.5, lineHeight: 1.5, color: '#B7CFC3' }}>
                                {app.remarks}
                              </Typography>
                            )}
                            {app.restartRequired && (
                              <Typography sx={{ mt: 0.25, fontSize: 12.5, lineHeight: 1.5, color: '#93AC9E' }}>
                                {app.restartMessage || 'This attempt was terminated due to policy violations. Re-apply and complete payment to restart from question 1.'}
                              </Typography>
                            )}
                          </Box>
                        )}
                      </Box>
                    )
                  })}

                  {pageCount > 1 && (
                    <Box
                      sx={{
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        gap: 1.25,
                        flexWrap: 'wrap',
                        pt: 1.75,
                      }}
                    >
                      <Typography sx={{ fontFamily: fonts.mono, fontSize: 11, color: '#93AC9E' }}>
                        Showing {pageStart + 1}-{Math.min(pageStart + APPS_PAGE_SIZE, levelApps.length)} of {levelApps.length}
                      </Typography>
                      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
                        <Button
                          size="small"
                          variant="outlined"
                          disabled={page === 1}
                          onClick={() => setLevelPage((prev) => ({ ...prev, [levelKey]: page - 1 }))}
                          sx={{ height: 28, px: 1.5, fontSize: 11, borderRadius: '8px' }}
                        >
                          Prev
                        </Button>
                        <Typography sx={{ fontFamily: fonts.mono, fontSize: 11, color: '#CFE2D8', px: 0.5 }}>
                          {page} / {pageCount}
                        </Typography>
                        <Button
                          size="small"
                          variant="outlined"
                          disabled={page === pageCount}
                          onClick={() => setLevelPage((prev) => ({ ...prev, [levelKey]: page + 1 }))}
                          sx={{ height: 28, px: 1.5, fontSize: 11, borderRadius: '8px' }}
                        >
                          Next
                        </Button>
                      </Box>
                    </Box>
                  )}

                </Box>
              </Collapse>
            </Box>
          )
        })}
      </Stack>
    )
  }

  const stepBadgeSx = (state) => ({
    width: 24,
    height: 24,
    borderRadius: '50%',
    display: 'grid',
    placeItems: 'center',
    fontFamily: fonts.mono,
    fontSize: 11,
    fontWeight: 700,
    ...(state === 'active'
      ? { background: 'rgba(192,138,46,.18)', border: `1.5px solid ${tokens.copperLt}`, color: tokens.copperLt }
      : state === 'done'
        ? { background: 'rgba(95,174,146,.16)', border: `1.5px solid ${tokens.greenLt}`, color: tokens.greenLt }
        : { background: 'rgba(150,195,172,.1)', border: '1.5px solid rgba(150,195,172,.3)', color: '#93AC9E' }),
  })

  return (
    <Box>
      {/* ==================== PAGE HEADER ==================== */}
      <PageHeader
        title="Exam Applications"
        subtitle="Apply for certification exams and resume in-progress applications."
        action={
          <>
            <Button
              variant="outlined"
              startIcon={<MenuBookIcon sx={{ fontSize: 15 }} />}
              onClick={(e) => setSyllabusMenuAnchor(e.currentTarget)}
              sx={{ height: 42, px: 2.25, fontSize: 12.5, borderRadius: '11px', whiteSpace: 'nowrap' }}
            >
              Syllabus
            </Button>
            <Button
              variant="contained"
              startIcon={<AddIcon sx={{ fontSize: 15 }} />}
              onClick={openDialog}
              sx={{ ...ctaButton, width: 'auto', height: 42, px: 2.5, fontSize: 12.5, letterSpacing: '.8px', borderRadius: '11px', whiteSpace: 'nowrap' }}
            >
              New application
            </Button>
          </>
        }
      />

      <Menu
        anchorEl={syllabusMenuAnchor}
        open={Boolean(syllabusMenuAnchor)}
        onClose={() => setSyllabusMenuAnchor(null)}
      >
        {LEVELS.map((l) => (
          <MenuItem key={l} onClick={() => openSyllabus(l)}>
            {l} syllabus
          </MenuItem>
        ))}
      </Menu>

      {mainContent}

      {/* ==================== NEW APPLICATION ==================== */}
      <Dialog
        open={open}
        onClose={() => setOpen(false)}
        maxWidth={false}
        fullWidth
        PaperProps={{
          sx: {
            width: '100%',
            maxWidth: 680,
            maxHeight: '86vh',
            borderRadius: '22px',
            background: gradients.card,
            border: `1px solid ${tokens.line2}`,
            boxShadow: shadows.package,
          },
        }}
      >
        <Box sx={{ p: '24px 28px 18px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <Typography component="h2" sx={{ fontSize: 20, fontWeight: 800, letterSpacing: '-.4px' }}>
            New Exam Application
          </Typography>
          <IconButton
            onClick={() => setOpen(false)}
            aria-label="Close"
            sx={{ flex: 'none', width: 32, height: 32, borderRadius: '9px', background: 'rgba(95,174,146,.1)' }}
          >
            <CloseIcon sx={{ fontSize: 16 }} />
          </IconButton>
        </Box>

        {/* step rail */}
        <Box sx={{ px: '28px', pb: 2.5, display: 'flex', alignItems: 'center', gap: 1.5 }}>
          <Box sx={{ flex: 'none', display: 'flex', alignItems: 'center', gap: 1 }}>
            <Box sx={stepBadgeSx(activeStep === 0 ? 'active' : 'done')}>
              {activeStep === 0 ? '1' : <CheckIcon sx={{ fontSize: 13 }} />}
            </Box>
            <Typography sx={{ fontSize: 13, fontWeight: 600, color: activeStep === 0 ? tokens.ink : tokens.greenLt }}>
              Choose level
            </Typography>
          </Box>
          <Box sx={{ flex: 1, height: '2px', background: activeStep === 1 ? tokens.greenLt : 'rgba(150,195,172,.2)' }} />
          <Box sx={{ flex: 'none', display: 'flex', alignItems: 'center', gap: 1 }}>
            <Box sx={stepBadgeSx(activeStep === 1 ? 'active' : 'todo')}>2</Box>
            <Typography sx={{ fontSize: 13, fontWeight: 600, color: activeStep === 1 ? tokens.ink : '#93AC9E' }}>
              Select exam
            </Typography>
          </Box>
        </Box>

        <DialogContent sx={{ px: '28px', pt: 0, pb: 2.5, borderTop: 'none' }}>
          {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}

          {activeStep === 0 && (
            <Box>
              <Typography sx={{ mb: 2, fontSize: 13, color: '#B7CFC3' }}>
                Select the certification level you want to apply for.
              </Typography>

              {/* level segmented control */}
              <Box
                sx={{
                  display: 'flex',
                  gap: 0.75,
                  mb: 2.25,
                  p: '5px',
                  borderRadius: '11px',
                  background: 'rgba(3,16,11,.6)',
                  border: `1px solid ${tokens.line}`,
                }}
              >
                {LEVELS.map((l) => {
                  const active = level === l
                  return (
                    <Box
                      key={l}
                      component="button"
                      type="button"
                      onClick={() => setLevel(l)}
                      sx={{
                        flex: 1,
                        height: 34,
                        border: 0,
                        borderRadius: '8px',
                        cursor: 'pointer',
                        fontFamily: fonts.mono,
                        fontSize: 12,
                        fontWeight: 700,
                        letterSpacing: '.08em',
                        color: active ? '#062017' : '#CFE2D8',
                        background: active ? gradients.copper : 'transparent',
                      }}
                    >
                      {l}
                    </Box>
                  )
                })}
              </Box>

              {/* syllabus for the highlighted level, so the choice is informed */}
              <Box
                sx={{
                  mb: 2,
                  borderRadius: '14px',
                  background: 'rgba(95,174,146,.05)',
                  border: `1px solid ${tokens.line}`,
                  overflow: 'hidden',
                }}
              >
                <Box
                  sx={{
                    p: '14px 18px',
                    display: 'flex',
                    alignItems: 'center',
                    gap: 1.25,
                    flexWrap: 'wrap',
                    borderBottom: `1px solid ${tokens.line}`,
                  }}
                >
                  <Box
                    sx={{
                      flex: 'none',
                      width: 30,
                      height: 30,
                      borderRadius: '9px',
                      display: 'grid',
                      placeItems: 'center',
                      background: 'rgba(192,138,46,.14)',
                      border: '1px solid rgba(192,138,46,.4)',
                      color: tokens.copperLt,
                    }}
                  >
                    <MenuBookIcon sx={{ fontSize: 15 }} />
                  </Box>
                  <Typography sx={{ fontSize: 14, fontWeight: 700 }}>
                    {level} Syllabus Modules
                  </Typography>
                  {wizardSyllabus && (
                    <Box
                      component="span"
                      sx={{
                        display: 'inline-flex',
                        px: 1.125,
                        py: '3px',
                        borderRadius: 999,
                        fontFamily: fonts.mono,
                        fontSize: 9.5,
                        fontWeight: 700,
                        background: 'rgba(95,174,146,.14)',
                        border: '1px solid rgba(95,174,146,.4)',
                        color: tokens.greenLt,
                      }}
                    >
                      {wizardSyllabus.levelTitle}
                    </Box>
                  )}
                </Box>
                <Box sx={{ maxHeight: 220, overflowY: 'auto' }}>
                  <SyllabusPanel level={level} dense elevated={false} showHeader={false} />
                </Box>
              </Box>
            </Box>
          )}

          {activeStep === 1 && (
            <Box>
              <Typography sx={{ mb: 2, fontSize: 13, color: '#B7CFC3' }}>
                Confirm the exam you&rsquo;re applying for.
              </Typography>
              {stepTwoContent}
            </Box>
          )}
        </DialogContent>

        <DialogActions sx={{ p: '18px 28px', borderTop: `1px solid ${tokens.line}`, gap: 1.5 }}>
          <Button onClick={() => setOpen(false)} sx={{ height: 40, px: 2 }}>
            Cancel
          </Button>
          {activeStep === 1 && (
            <Button
              variant="outlined"
              onClick={() => setActiveStep(0)}
              disabled={submitting}
              sx={{ height: 40, px: 2.25, fontSize: 12, borderRadius: '10px' }}
            >
              Back
            </Button>
          )}
          <Button
            variant="contained"
            onClick={handleNext}
            disabled={submitting || optionsLoading || (activeStep === 1 && (!selectedExamId || Boolean(selectedExamIssue)))}
            sx={{ ...ctaButton, width: 'auto', height: 40, px: 2.75, fontSize: 12, letterSpacing: '.5px', borderRadius: '10px' }}
          >
            {activeStep === 0 ? 'Next' : submitting ? 'Applying…' : 'Apply'}
          </Button>
        </DialogActions>
      </Dialog>

      <SyllabusDialog
        open={Boolean(syllabusLevel)}
        level={syllabusLevel}
        onClose={() => setSyllabusLevel(null)}
      />
    </Box>
  )
}

export default ExamApplicationPage
