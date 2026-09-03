// ems_frontend/src/pages/exam/ExamPage.jsx
import React, { useState, useEffect, useRef, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useDispatch, useSelector } from 'react-redux'
import {
  Container, Paper, Grid, Typography, Button, Box, RadioGroup,
  FormControlLabel, Radio, FormGroup, Checkbox, Alert, LinearProgress,
  Dialog, DialogTitle, DialogContent, DialogActions, Snackbar, IconButton,
  Tooltip
} from '@mui/material'
import Webcam from 'react-webcam'
import { startExamStart, startExamSuccess, startExamFailure, loadSessionQuestion, endExamSession } from '../../store/slices/examSlice'
import { initializeProctoring, recordViolation, syncViolationCount } from '../../store/slices/proctoringSlice'
import { examAPI } from '../../api/examAPI'
import { userAPI } from '../../api/userAPI'
import { proctoringAPI } from '../../api/proctoringAPI'
import LoadingSpinner from '../../components/common/LoadingSpinner'
import PageHeader from '../../components/common/PageHeader'
import { tokens, fonts, surface, ctaButton, microLabel } from '../../styles/tokens'
import PcbBackdrop from '../../components/brand/PcbBackdrop'
import SyllabusDialog from '../../components/syllabus/SyllabusDialog'
import ProctorSecurityOverlay from '../../components/exam/proctoring/ProctorSecurityOverlay'
import useAiProctorWorker from '../../hooks/useAiProctorWorker'
import useBrowserHardening from '../../hooks/useBrowserHardening'
import useExamAutosave, {
  answersFromDraft, clearLocalDraft, markedFromDraft, readLocalDraft
} from '../../hooks/useExamAutosave'
import useSoundEnvironmentMonitor from '../../hooks/useSoundEnvironmentMonitor'
import { markSessionActive } from '../../hooks/useIdleTimeout'
import { captureEvidenceFrame } from '../../utils/evidenceCapture'
import { PROCTOR_VIDEO_CONSTRAINTS, describeFramingReasons } from '../../utils/proctorCapture'
import { bookingWindowClosed, examWindowState, formatCountdown, formatExamClock, formatExamSlot } from '../../utils/examJourney'
import VideocamIcon from '@mui/icons-material/Videocam'
import VideocamOffIcon from '@mui/icons-material/VideocamOff'
import MicIcon from '@mui/icons-material/Mic'
import MicOffIcon from '@mui/icons-material/MicOff'
import WarningIcon from '@mui/icons-material/Warning'
import FullscreenIcon from '@mui/icons-material/Fullscreen'
import ScreenShareIcon from '@mui/icons-material/ScreenShare'
import SecurityIcon from '@mui/icons-material/Security'
import MenuBookIcon from '@mui/icons-material/MenuBookRounded'
import RefreshIcon from '@mui/icons-material/Refresh'
import CheckRoundedIcon from '@mui/icons-material/CheckRounded'
import PriorityHighRoundedIcon from '@mui/icons-material/PriorityHighRounded'
import CloudDoneIcon from '@mui/icons-material/CloudDoneRounded'
import CloudOffIcon from '@mui/icons-material/CloudOffRounded'
import CloudSyncIcon from '@mui/icons-material/CloudSyncRounded'
import RestoreIcon from '@mui/icons-material/RestoreRounded'

/*
 * Sound-event policy.
 *
 * The detection itself lives in the audio worklet, which segments the microphone
 * into discrete sound events and classifies each one. What is left here is the
 * only part that is a policy question rather than a measurement: which classes
 * of sound carry a consequence.
 *
 * The engine reports everything it hears. This table decides what that means.
 */
const SOUND_VIOLATION_BY_CLASS = {
  /*
   * A voice near the candidate is the one class worth a strike on its own. It is
   * identified from periodicity, pitch range and voice-band energy — not
   * loudness — so a whisper across the desk qualifies and the fan at the same
   * level does not, which is exactly the distinction a loudness threshold could
   * never draw.
   */
  HUMAN_VOICE: 'VOICE_DETECTED',
  /* A television, a fan, road noise: sustained and unvarying. */
  SUSTAINED_NOISE: 'BACKGROUND_NOISE',
  /*
   * Both of these are "something happened and it was not speech". They can cost
   * a strike, but not the first ones: the server forgives the opening few of an
   * attempt and counts the rest, because a cough does not repeat on a schedule
   * and a conversation with someone off-camera does. Two gates decide what even
   * reaches that rule — the loudness floor below and the cooldown above — so
   * what the server is counting is disturbances, not keystrokes.
   */
  IMPULSE: 'SOUND_DETECTED',
  UNKNOWN_SOUND: 'SOUND_DETECTED'
}

/**
 * Per-type minimum spacing between violations of that type.
 *
 * Long enough that one noisy episode cannot drain all three strikes. Sustained
 * noise gets the longest window because the engine deliberately re-emits a
 * drone every 15s rather than reporting it once, so without this a television
 * left on would terminate an exam by itself.
 */
const SOUND_COOLDOWN_MS = {
  VOICE_DETECTED: 8000,
  BACKGROUND_NOISE: 20000,
  SOUND_DETECTED: 15000
}

/**
 * How far above the room floor an unidentified sound must peak to be recorded.
 *
 * Applies only to the unidentified classes, and exists because of one specific
 * candidate: the one with a mechanical keyboard. Every keystroke is a genuine
 * impulse the engine is right to detect, and now that repeated unidentified
 * sounds cost strikes, reporting them would spend that candidate's grace on the
 * sound of typing and then end their exam. This threshold is what separates a
 * disturbance from the noise of working.
 *
 * A voice is held to no such bar — a quiet one is the case that matters most.
 */
const UNIDENTIFIED_SOUND_MIN_ABOVE_FLOOR_DB = 15

/** Engine class names as they appear in the candidate-facing engine readout. */
const SOUND_CLASS_LABELS = {
  HUMAN_VOICE: 'a voice',
  SUSTAINED_NOISE: 'sustained noise',
  IMPULSE: 'a short sound',
  UNKNOWN_SOUND: 'an unidentified sound'
}

/**
 * The line an invigilator reads in the violation timeline.
 *
 * Written to be adjudicated rather than skimmed, which means saying what was
 * measured and not just what it was called. "A voice was heard" is a claim; "a
 * voice was heard for 2.3s, 71% voiced, around 130Hz, 19dB above the room" is a
 * claim someone can disagree with — and the candidate it is used against is
 * entitled to the version they can argue with. Levels are quoted relative to
 * that room's own floor because an absolute dBFS number means nothing without
 * knowing the microphone.
 */
const describeSoundEvent = (event) => {
  const seconds = (event.durationMs / 1000).toFixed(1)
  const loudness = `${event.peakAboveFloorDb}dB above the room's own level`

  switch (event.soundClass) {
    case 'HUMAN_VOICE':
      return `A voice was heard near the candidate for ${seconds}s ` +
        `(${Math.round(event.voicedRatio * 100)}% voiced` +
        `${event.pitchHz ? `, around ${event.pitchHz}Hz` : ''}, peaking ${loudness}).`
    case 'SUSTAINED_NOISE':
      return `Sustained background sound for ${seconds}s, peaking ${loudness}.`
    case 'IMPULSE':
      return `A short unidentified sound near the candidate ` +
        `(${event.durationMs}ms, peaking ${loudness}).`
    default:
      return `An unidentified sound near the candidate lasting ${seconds}s, peaking ${loudness}.`
  }
}

/** Cadence for refreshing the shared idle stamp while an exam is on screen. */
const SESSION_KEEPALIVE_MS = 60 * 1000

/**
 * How long the setup gate may go without a face verdict before it is called
 * stalled rather than slow.
 *
 * The face task publishes every 300ms whenever frames are reaching it, so this
 * is roughly 25 missed cycles — far past any normal hitch, and reached only
 * when frames are not arriving at all: a camera the OS handed to another app, a
 * video element that went to a black stream after sleep, a tab throttled in the
 * background. Those all look identical on screen to a slow check, and without a
 * bound the candidate waits on a message that will never change.
 */
const FACE_VERDICT_STALL_MS = 8000

/** How long the recheck reports itself as running before the result is judged. */
const CAMERA_RECHECK_SETTLE_MS = 2500

/**
 * How many times a submission is retried before the candidate is asked to act.
 *
 * Submitting is the one call in the exam that cannot be quietly deferred to the
 * next autosave: until it lands there is no attempt to score. The failure it is
 * most likely to hit is also the most likely to clear on its own — a few
 * seconds of lost connectivity at the end of an hour indoors — so it is worth
 * several attempts before telling the candidate their exam did not submit.
 */
const SUBMIT_MAX_ATTEMPTS = 4

/** Backoff between submission attempts. */
const SUBMIT_RETRY_DELAY_MS = [1500, 3000, 6000]

const delay = (ms) => new Promise((resolve) => { setTimeout(resolve, ms) })

/**
 * How a device permission is doing, as one of three states.
 *
 * "Not asked yet" is not a failure. The setup screen paints it neutral rather
 * than red, so a candidate arriving before the browser prompt has been answered
 * is not told something is broken; only an outright `denied` is red, because
 * that one needs them to go into site settings.
 *
 * The two maps exist because MUI spells the neutral tone differently per
 * component — SvgIcon takes `disabled`, IconButton takes `default` — and
 * passing the wrong one silently falls back to an unstyled colour.
 */
const permissionPhase = (granted, state) => {
  if (granted) {
    return 'granted'
  }
  return state === 'denied' ? 'denied' : 'pending'
}

const PERMISSION_ICON_COLOR = { granted: 'success', denied: 'error', pending: 'disabled' }
const PERMISSION_BUTTON_COLOR = { granted: 'success', denied: 'error', pending: 'default' }

/**
 * How one line of the pre-start setup timeline is drawn.
 *
 * Five tones rather than done/not-done, because the unfinished ones ask
 * different things of the candidate: `active` means wait, `warn` means move
 * into frame, `error` means something is broken and the step carries a button
 * for it, `pending` means this gate has not been reached yet. The glyph in the
 * dot changes with the tone as well as the colour — this timeline is the entire
 * explanation of a disabled Start button, so it must not rest on a viewer being
 * able to tell copper from amber.
 */
const SETUP_STEP_TONE = {
  done: { dot: tokens.greenLt, border: tokens.greenLt, glyph: '#062017', detail: tokens.body },
  active: { dot: 'rgba(192,138,46,.16)', border: tokens.copper, glyph: tokens.copperLt, detail: '#DCC79A' },
  warn: { dot: 'rgba(240,180,64,.14)', border: tokens.warn, glyph: tokens.warn, detail: '#E6D2A6' },
  error: { dot: 'rgba(190,40,40,.18)', border: tokens.danger, glyph: tokens.danger, detail: '#F0C9C9' },
  pending: { dot: tokens.sub2, border: tokens.line2, glyph: tokens.muted, detail: tokens.muted },
}

/**
 * Whether a failed request is worth repeating.
 *
 * A request that never got an answer (offline, timeout, DNS) and a 5xx are the
 * transport failing, and the same call can succeed a second later. A 4xx is the
 * server's considered answer — "this session is already submitted", "this
 * attempt was terminated" — and repeating it only delays telling the candidate.
 */
const isRetryableFailure = (err) => !err?.response || err.response.status >= 500

const ExamPage = () => {
  const { applicationId } = useParams()
  const navigate = useNavigate()
  const dispatch = useDispatch()

  /*
   * Holds the shared idle clock open for as long as the exam is on screen.
   *
   * This page renders outside the app shell, so it never arms an idle expiry of
   * its own — but the clock lives in localStorage and is shared across tabs. A
   * portal tab left open in the same browser would otherwise hit ten minutes,
   * revoke the refresh token, and take this exam down with it at the next token
   * refresh. Stamping activity here makes an exam in progress count as activity
   * everywhere. See hooks/useIdleTimeout.js.
   */
  useEffect(() => {
    markSessionActive()
    const timer = window.setInterval(markSessionActive, SESSION_KEEPALIVE_MS)
    return () => window.clearInterval(timer)
  }, [])
  const webcamRef = useRef(null)
  const mediaRecorderRef = useRef(null)
  const recordedChunksRef = useRef([])
  const autoTerminateRef = useRef(false)

  const { currentSession, sessionQuestions, isLoading, examInProgress, error: examError } = useSelector((state) => state.exam)
  const { violationCount, isRecording, violations } = useSelector((state) => state.proctoring)
  const { user } = useSelector((state) => state.auth)

  const [currentQuestionNumber, setCurrentQuestionNumber] = useState(1)
  const [answers, setAnswers] = useState({})
  const [markedForReview, setMarkedForReview] = useState({})
  const [timeLeft, setTimeLeft] = useState(0)
  const [cameraPermission, setCameraPermission] = useState(false)
  const [microphonePermission, setMicrophonePermission] = useState(false)
  const [cameraPermissionState, setCameraPermissionState] = useState('prompt')
  const [microphonePermissionState, setMicrophonePermissionState] = useState('prompt')
  const [isRequestingPermissions, setIsRequestingPermissions] = useState(false)
  const [permissionError, setPermissionError] = useState('')
  const [showTerminateDialog, setShowTerminateDialog] = useState(false)
  const [testStarted, setTestStarted] = useState(false)
  const [viewedQuestions, setViewedQuestions] = useState(new Set())
  const [showViolationAlert, setShowViolationAlert] = useState(false)
  const [lastViolation, setLastViolation] = useState(null)
  const [showTerminationNotice, setShowTerminationNotice] = useState(false)
  const [examSubmitted, setExamSubmitted] = useState(false)
  const [isSubmittingExam, setIsSubmittingExam] = useState(false)
  const [submitError, setSubmitError] = useState('')
  /** Which attempt the submission is on, so the wait can be explained. */
  const [submitAttempt, setSubmitAttempt] = useState(0)
  /**
   * Set when an interrupted attempt came back with work already done, so the
   * candidate is told what was recovered instead of having to count.
   */
  const [resumeNotice, setResumeNotice] = useState('')
  const [screenShareActive, setScreenShareActive] = useState(false)
  const [devToolsOpen, setDevToolsOpen] = useState(false)
  const [blockedActionWarning, setBlockedActionWarning] = useState({ open: false, message: '' })
  const [showFullscreenRecoveryPrompt, setShowFullscreenRecoveryPrompt] = useState(false)
  // Flips once the webcam element is actually producing frames, which is the
  // signal the AI worker needs before it is worth loading models.
  const [proctorStreamReady, setProctorStreamReady] = useState(false)
  /**
   * Bumped by the "Recheck camera" control; used as the <Webcam> key.
   *
   * Changing the key is the point rather than a trick: it unmounts the element,
   * which stops the old tracks, and mounts a fresh one that calls getUserMedia
   * again. A track the OS has muted, frozen after sleep, or handed to another
   * application cannot be revived by anything short of re-acquiring it, and
   * re-acquiring in place is exactly what react-webcam does not offer.
   */
  const [cameraGeneration, setCameraGeneration] = useState(0)
  /** When the current recheck began, so the UI can report it as in progress. */
  const [cameraRecheckAt, setCameraRecheckAt] = useState(null)
  /**
   * Instant the setup gate was last (re)armed — engine ready, or a recheck.
   *
   * The reference point for deciding whether the check has stopped receiving
   * video rather than merely taking its time.
   */
  const [gateClockAt, setGateClockAt] = useState(null)
  // Certification level of this application, used to show the right syllabus on
  // the pre-start screen. Resolved from the dashboard because the route only
  // carries the application id and the session payload does not exist yet.
  const [applicationLevel, setApplicationLevel] = useState(null)
  const [syllabusOpen, setSyllabusOpen] = useState(false)
  /**
   * True when the server already holds a running session for this application.
   *
   * Read on the pre-start screen, which is where a candidate lands after a
   * crash and which otherwise looks identical to a first sitting.
   */
  const [attemptWasInterrupted, setAttemptWasInterrupted] = useState(false)
  /**
   * The booking for this application, and when it may be sat.
   *
   * The server refuses a start outside that window, but by the time it does the
   * candidate has already granted camera, microphone and screen share and
   * waited for the proctoring engine to load. Knowing the window up front means
   * the refusal can be shown before any of that, next to the one thing that
   * fixes it — moving the booking.
   */
  const [booking, setBooking] = useState(null)

  /**
   * Points at the live <video> node inside react-webcam. The AI worker reads
   * frames from this element; `webcamRef` itself exposes the component, not
   * the media node.
   */
  const proctorVideoRef = useRef(null)

  /**
   * Timestamp of the last violation of each type, for the per-type cooldowns.
   *
   * Keyed by violation type rather than by sound class, because two classes map
   * onto SOUND_DETECTED and they deliberately share one budget: a cough and a
   * door inside the same window are one entry in the invigilator's timeline,
   * not two.
   *
   * Everything else this used to hold — the context, the stream, the source
   * node, the silence counters, the resume-on-gesture listeners — moved into
   * useSoundEnvironmentMonitor along with the detection itself.
   */
  const lastSoundViolationAtRef = useRef({})
  /**
   * Always the current `reportViolationEvent`.
   *
   * The noise interval is created inside startExam(), from the closure of the
   * render that was live when the candidate clicked Start — a render in which
   * `currentSession` is still null, because startExamSuccess has not been
   * dispatched yet. Calling the captured `reportViolationEvent` directly meant
   * every noise violation for the whole exam ran against `examId === undefined`
   * and was dropped before the API call, so BACKGROUND_NOISE was recorded in
   * Redux but never reached the server. Same stale-closure trap the
   * `examInProgressRef` above already guards against, one layer further in.
   */
  const reportViolationEventRef = useRef(null)
  /**
   * Mirrors `examInProgress` for the noise-check interval below. That interval
   * is created once, inside a closure captured before the exam-start dispatch
   * has re-rendered the component, so the plain `examInProgress` state value
   * would be frozen at `false` for the interval's entire lifetime. A ref kept
   * in sync via effect always reads the current value instead.
   */
  const examInProgressRef = useRef(examInProgress)
  const screenStreamRef = useRef(null)
  const screenShareCheckIntervalRef = useRef(null)
  const screenShareRetryTimeoutRef = useRef(null)
  const screenShareDeniedRetryTimeoutRef = useRef(null)
  const screenShareGraceRef = useRef(false)
  const fullscreenEnforcementReadyRef = useRef(false)
  const blockedActionToastTsRef = useRef(0)
  const examEndingRef = useRef(false)
  const proctoringWarmupUntilRef = useRef(0)

  useEffect(() => {
    examInProgressRef.current = examInProgress
  }, [examInProgress])

  /*
   * Resolved once on mount so the syllabus is already in hand on the pre-start
   * screen. A failure here is deliberately silent — it only costs the syllabus
   * button, and nothing about launching the exam depends on it.
   */
  useEffect(() => {
    let mounted = true
    ;(async () => {
      try {
        const res = await userAPI.getDashboard()
        const apps = res.data.data?.examStatuses || []
        const app = apps.find((item) => String(item.applicationId) === String(applicationId))
        if (mounted && app?.certificationLevel) {
          setApplicationLevel(app.certificationLevel)
        }
        if (mounted && app?.attemptInProgress) {
          setAttemptWasInterrupted(true)
        }
        if (mounted && app) {
          setBooking({
            scheduledExamTime: app.scheduledExamTime,
            examWindowStart: app.examWindowStart,
            examWindowEnd: app.examWindowEnd,
            attemptInProgress: app.attemptInProgress
          })
        }
      } catch (err) {
        console.error('Failed to resolve certification level for syllabus', err)
      }
    })()
    return () => { mounted = false }
  }, [applicationId])

  /*
   * A running attempt is a rejoin, not a new sitting, so it is never gated by
   * the booked window — the candidate started on time and the session's own
   * clock is what limits them now. Anything the dashboard could not tell us
   * about is left open and settled by the server at start.
   */
  const bookingWindowState = booking && !booking.attemptInProgress
    ? examWindowState(booking)
    : 'OPEN'
  const startsTooEarly = bookingWindowState === 'EARLY'
  const slotMissed = bookingWindowState === 'MISSED'
  const bookingBlocked = startsTooEarly || slotMissed
  /*
   * The exam has stopped taking bookings, so the way out of a missed slot that
   * this alert normally offers does not exist. It still sends the candidate to
   * the schedule screen — that is where the explanation lives — but it must not
   * promise a new time on the way.
   */
  const rebookingClosed = bookingWindowClosed(booking)

  /*
   * Only ticks while the candidate is actually waiting for a window to open, so
   * the pre-start screen unlocks itself rather than needing a reload. Stopping
   * once it opens keeps a per-second re-render off this page for the whole of
   * the exam that follows.
   */
  const [windowNow, setWindowNow] = useState(() => Date.now())
  useEffect(() => {
    if (!startsTooEarly) {
      return undefined
    }
    const tick = setInterval(() => setWindowNow(Date.now()), 1000)
    return () => clearInterval(tick)
  }, [startsTooEarly])

  /**
   * The proctoring <Webcam> lives in both the pre-start and in-exam render
   * trees, so crossing that boundary unmounts one instance and mounts another.
   * Between those two events `proctorVideoRef` still points at the old,
   * detached element whose tracks react-webcam has already stopped — capturing
   * from it would yield a stale or blank frame. Clearing it here means frame
   * dispatch simply pauses until the new instance reports `onUserMedia`.
   */
  useEffect(() => {
    proctorVideoRef.current = null
    setProctorStreamReady(false)
  }, [testStarted])

  const lockEscapeKeyInFullscreen = useCallback(async () => {
    try {
      if (navigator.keyboard?.lock && document.fullscreenElement) {
        await navigator.keyboard.lock(['Escape'])
      }
    } catch (err) {
      console.warn('Escape key lock is unavailable:', err)
    }
  }, [])

  const unlockKeyboardIfNeeded = useCallback(() => {
    try {
      navigator.keyboard?.unlock?.()
    } catch (err) {
      console.warn('Failed to unlock keyboard:', err)
    }
  }, [])

  const requestFullscreenRecovery = useCallback(async () => {
    if (!examInProgress || examSubmitted || examEndingRef.current) {
      return true
    }

    try {
      if (!document.fullscreenElement && document.documentElement.requestFullscreen) {
        await document.documentElement.requestFullscreen({ navigationUI: 'hide' })
      }
      await lockEscapeKeyInFullscreen()
      setShowFullscreenRecoveryPrompt(false)
      return true
    } catch (err) {
      console.warn('Failed to recover fullscreen mode:', err)
      setShowFullscreenRecoveryPrompt(true)
      return false
    }
  }, [examInProgress, examSubmitted, lockEscapeKeyInFullscreen])

  // Block tab close / refresh while exam is in progress
  useEffect(() => {
    const handleBeforeUnload = (e) => {
      if (examInProgress && !examSubmitted) {
        e.preventDefault()
        e.returnValue = ''
      }
    }
    window.addEventListener('beforeunload', handleBeforeUnload)
    return () => window.removeEventListener('beforeunload', handleBeforeUnload)
  }, [examInProgress, examSubmitted])

  // Block browser back/forward navigation while exam is in progress.
  useEffect(() => {
    if (!examInProgress || examSubmitted) {
      return
    }
    const handlePopState = () => {
      window.history.pushState(null, '', window.location.href)
      setBlockedActionWarning({
        open: true,
        message: 'Navigation is blocked while exam is in progress.'
      })
    }
    window.history.pushState(null, '', window.location.href)
    window.addEventListener('popstate', handlePopState)
    return () => window.removeEventListener('popstate', handlePopState)
  }, [examInProgress, examSubmitted])

  const refreshPermissionStates = useCallback(async () => {
    if (!navigator.permissions?.query) {
      return
    }
    try {
      const cameraStatus = await navigator.permissions.query({ name: 'camera' })
      const microphoneStatus = await navigator.permissions.query({ name: 'microphone' })
      setCameraPermissionState(cameraStatus.state)
      setMicrophonePermissionState(microphoneStatus.state)
    } catch (err) {
      console.error('Failed to read permission states:', err)
    }
  }, [])

  const detectDeveloperToolsOpen = useCallback(() => {
    const widthGap = globalThis.outerWidth - globalThis.innerWidth
    const heightGap = globalThis.outerHeight - globalThis.innerHeight
    const threshold = 160
    return widthGap > threshold || heightGap > threshold
  }, [])

  const requestPermissions = useCallback(async () => {
    setIsRequestingPermissions(true)
    setPermissionError('')
    try {
      const mediaStream = await navigator.mediaDevices.getUserMedia({
        video: true,
        audio: true
      })
      console.log('Camera and microphone permissions granted----', mediaStream);
      setCameraPermission(true)
      setMicrophonePermission(true)
      setCameraPermissionState('granted')
      setMicrophonePermissionState('granted')
      mediaStream.getTracks().forEach((track) => track.stop())
    } catch (err) {
      setCameraPermission(false)
      setMicrophonePermission(false)
      setPermissionError('Camera and microphone permission is required to start the exam.')
      console.error('Permission denied:', err)
      await refreshPermissionStates()
    } finally {
      setIsRequestingPermissions(false)
    }
  }, [refreshPermissionStates])

  // Ask for proctoring permission every time this exam page is opened.
  useEffect(() => {
    setCameraPermission(false)
    setMicrophonePermission(false)
    setCameraPermissionState('prompt')
    setMicrophonePermissionState('prompt')
    setTestStarted(false)
    refreshPermissionStates()
    requestPermissions()
  }, [applicationId, requestPermissions, refreshPermissionStates])

  useEffect(() => {
    refreshPermissionStates()
    const intervalId = globalThis.setInterval(() => {
      refreshPermissionStates()
    }, 3000)
    return () => globalThis.clearInterval(intervalId)
  }, [refreshPermissionStates])

  useEffect(() => {
    const evaluateDevTools = () => {
      setDevToolsOpen(detectDeveloperToolsOpen())
    }
    evaluateDevTools()
    const intervalId = globalThis.setInterval(evaluateDevTools, 1000)
    return () => globalThis.clearInterval(intervalId)
  }, [detectDeveloperToolsOpen])

  const handleStartExamClicked = async () => {
    if (devToolsOpen || detectDeveloperToolsOpen()) {
      return
    }

    // Before anything that awaits. See primeAudioContext() in
    // useSoundEnvironmentMonitor: this is the only moment in the whole start
    // sequence that still holds user activation, and an AudioContext created
    // without it starts suspended and stays that way.
    primeAudioContext()

    examEndingRef.current = false

    // Request screen share immediately from the click handler to preserve
    // browser user-activation requirements for getDisplayMedia.
    const preflightScreenShare = await requestScreenShare({
      trackViolation: false,
      autoRetry: false
    })
    if (!preflightScreenShare.ok) {
      dispatch(startExamFailure(preflightScreenShare.message))
      return
    }

    const started = await startExam({ skipScreenShareRequest: true })
    setTestStarted(started)
  }

  const startExam = async ({ skipScreenShareRequest = false } = {}) => {
    if (detectDeveloperToolsOpen()) {
      setDevToolsOpen(true)
      dispatch(startExamFailure('Developer tools must be closed before starting the exam.'))
      return false
    }

    dispatch(startExamStart())
    try {
      examEndingRef.current = false
      fullscreenEnforcementReadyRef.current = false
      const response = await examAPI.startExam(applicationId)
      const examData = response.data.data

      // Request screen share FIRST before fullscreen
      if (!skipScreenShareRequest) {
        const screenShareResult = await requestScreenShare({
          trackViolation: false,
          autoRetry: false
        })
        if (!screenShareResult.ok) {
          throw new Error(screenShareResult.message || 'Screen sharing permission is required to start the exam.')
        }
      }

      // Enable fullscreen mode AFTER screen sharing confirmation (exit only on submission)
      try {
        if (document.documentElement.requestFullscreen) {
          await document.documentElement.requestFullscreen({ navigationUI: 'hide' })
          await lockEscapeKeyInFullscreen()
          console.log('Fullscreen mode enabled')
        }
      } catch (err) {
        console.warn('Fullscreen request failed:', err)
      }

      dispatch(startExamSuccess(examData))
      proctoringWarmupUntilRef.current = Date.now() + 10000
      dispatch(initializeProctoring({
        cameraEnabled: cameraPermission,
        microphoneEnabled: microphonePermission
      }))

      /*
       * The server's number, not a fresh full duration.
       *
       * Starting the countdown at the exam length on every start meant rejoining
       * an attempt handed the candidate another complete sitting, as often as
       * they cared to rejoin. `remainingSeconds` is measured against the stored
       * session start, so a resumed attempt gets back only what was left of it.
       * The fallback covers a server that predates the field.
       */
      setTimeLeft(
        typeof examData.remainingSeconds === 'number'
          ? examData.remainingSeconds
          : (examData.durationMinutes || 60) * 60
      )

      startVideoRecording()

      // Load first question
      if (examData.firstQuestion) {
        dispatch(loadSessionQuestion({
          number: 1,
          question: examData.firstQuestion
        }))
      }

      if (examData.resumed) {
        await restoreInterruptedAttempt(examData)
      } else {
        // A fresh attempt must not inherit a draft left by an earlier one on
        // this machine. Session tokens differ, so this is belt and braces —
        // but the belt is cheap and wearing the wrong answers is not.
        clearLocalDraft(examData.sessionToken)
      }

      return true
    } catch (err) {
      const errorMessage = err.response?.data?.message || err.message || 'Failed to start exam'
      dispatch(startExamFailure(errorMessage))
      if (errorMessage.includes('Re-apply and complete payment')) {
        navigate('/exams', {
          state: {
            restartMessage: errorMessage,
            fromApplicationId: Number(applicationId)
          }
        })
      }
      return false
    }
  }

  /**
   * Puts the answers back into an attempt that was cut short.
   *
   * <p>Two drafts can exist: the one the server acknowledged, and the one this
   * browser wrote to disk. They disagree exactly when the interruption happened
   * between an answer and the autosave that would have carried it — which is
   * the common case, because that is when interruptions are noticed. The newer
   * one wins, so a candidate returning on the same machine gets everything, and
   * one returning on a different machine gets everything the network
   * carried.</p>
   */
  const restoreInterruptedAttempt = async (examData) => {
    const serverDraft = examData.savedProgress
    const localDraft = readLocalDraft(examData.sessionToken)

    const serverAt = serverDraft?.savedAt ? new Date(serverDraft.savedAt).getTime() : 0
    const localAt = localDraft?.updatedAt || 0
    const draft = localAt > serverAt ? localDraft : serverDraft

    if (!draft?.answers?.length && !draft?.markedForReview?.length) {
      return
    }

    const restoredAnswers = answersFromDraft(draft.answers)
    setAnswers(restoredAnswers)
    setMarkedForReview(markedFromDraft(draft.markedForReview))

    const answeredCount = Object.keys(restoredAnswers).length
    setResumeNotice(
      answeredCount > 0
        ? `Session recovered. ${answeredCount} answer${answeredCount === 1 ? '' : 's'} you had already given ` +
          'have been restored, and the clock has continued from where it was.'
        : 'Session recovered. The clock has continued from where it was.'
    )

    /*
     * Land the candidate back on the question they were on. Fetched directly
     * rather than through loadQuestion(), which reads the session out of Redux —
     * the dispatch that puts it there has only just been made, so the value in
     * this closure is still null.
     */
    const target = draft.currentQuestionNumber
    if (!target || target <= 1 || target > (examData.questionCount || 0)) {
      return
    }
    try {
      const response = await examAPI.getSessionQuestion(examData.sessionToken, target)
      dispatch(loadSessionQuestion({ number: target, question: response.data.data.question }))
      setCurrentQuestionNumber(target)
    } catch (err) {
      // Q1 is already loaded, so a failure here costs the candidate a click.
      console.warn('Could not reopen the question the attempt was left on', err)
    }
  }

  const getScreenShareErrorMessage = (err) => {
    if (!err) {
      return 'Screen sharing permission is required to start the exam.'
    }

    if (err.name === 'NotAllowedError') {
      return 'Screen sharing was blocked. Please allow screen sharing in the browser prompt. On macOS, also enable Screen Recording for your browser in System Settings > Privacy & Security > Screen Recording, then retry.'
    }

    if (err.name === 'AbortError') {
      return 'Screen sharing was cancelled before completion. Please share your full screen and retry.'
    }

    if (err.name === 'NotFoundError') {
      return 'No shareable screen was found. Connect a display and retry.'
    }

    return `Unable to start screen sharing: ${err.message || 'Unknown error'}`
  }

  const startVideoRecording = async () => {
    // Sound monitoring owns a dedicated audio stream and must start regardless
    // of whether the webcam element has mounted yet. It used to be nested
    // inside the webcam branch below, which meant it never ran at all when the
    // camera stream was not ready at exam-start time.
    void startSoundMonitoring()

    if (webcamRef.current?.stream) {
      const mediaRecorder = new MediaRecorder(webcamRef.current.stream)
      mediaRecorderRef.current = mediaRecorder
      recordedChunksRef.current = []

      mediaRecorder.ondataavailable = (event) => {
        recordedChunksRef.current.push(event.data)
      }

      mediaRecorder.start()
      console.log('Video recording started for exam proctoring')
    }
  }

  const showBlockedActionWarning = (message) => {
    const now = Date.now()
    if (now - blockedActionToastTsRef.current < 1500) {
      return
    }
    blockedActionToastTsRef.current = now
    setBlockedActionWarning({ open: true, message })
  }

  const isInProctoringWarmup = () => Date.now() < proctoringWarmupUntilRef.current

  const clearScreenShareRetryTimers = () => {
    if (screenShareRetryTimeoutRef.current) {
      clearTimeout(screenShareRetryTimeoutRef.current)
      screenShareRetryTimeoutRef.current = null
    }
    if (screenShareDeniedRetryTimeoutRef.current) {
      clearTimeout(screenShareDeniedRetryTimeoutRef.current)
      screenShareDeniedRetryTimeoutRef.current = null
    }
  }

  const stopScreenShareTracking = () => {
    if (screenShareCheckIntervalRef.current) {
      clearInterval(screenShareCheckIntervalRef.current)
      screenShareCheckIntervalRef.current = null
    }
    clearScreenShareRetryTimers()
  }

  const exitFullscreenSafely = async () => {
    if (!document.fullscreenElement && !document.webkitFullscreenElement) {
      return
    }

    try {
      if (document.exitFullscreen) {
        await document.exitFullscreen()
        return
      }
    } catch (err) {
      console.warn('Failed to exit fullscreen:', err)
    }

    try {
      if (document.webkitExitFullscreen) {
        document.webkitExitFullscreen()
      }
    } catch (err) {
      console.warn('Failed to exit webkit fullscreen:', err)
    }
  }

  const getPostExamRedirectPath = useCallback(() => {
    const isAdmin = user?.role === 'ADMIN' || user?.roles?.includes('ADMIN')
    return isAdmin ? '/admin/dashboard' : '/dashboard'
  }, [user])

  const mapViolationTypeForApi = useCallback((type) => {
    switch (type) {
      case 'TAB_SWITCH':
        return 'TAB_SWITCH'
      case 'WINDOW_BLUR':
        return 'WINDOW_FOCUS_LOST'
      case 'RESTRICTED_KEY_PRESS':
        return 'BROWSER_MONITORING'

      // AI worker detections and hardened browser signals now map to their own
      // server-side types instead of being flattened into BROWSER_MONITORING /
      // SESSION_TAMPERING, so invigilators can see what actually happened.
      case 'BACKGROUND_NOISE':
        return 'BACKGROUND_NOISE'
      // Sound-event engine classes. BACKGROUND_NOISE above now means one
      // specific thing — a sustained source — rather than "the mic was loud".
      case 'VOICE_DETECTED':
        return 'VOICE_DETECTED'
      case 'SOUND_DETECTED':
        return 'SOUND_DETECTED'
      case 'PHONE_DETECTED':
        return 'PHONE_DETECTED'
      case 'MULTIPLE_FACES':
        return 'MULTIPLE_FACES'
      case 'FACE_NOT_VISIBLE':
        return 'FACE_NOT_VISIBLE'
      case 'FACE_TURNED_AWAY':
        return 'FACE_TURNED_AWAY'
      case 'EYES_OFF_SCREEN':
        return 'EYES_OFF_SCREEN'
      case 'NETWORK_LOSS':
        return 'NETWORK_LOSS'
      case 'SCREEN_RECORDING_SUSPECTED':
        return 'SCREEN_RECORDING_SUSPECTED'
      case 'PROCTOR_SETUP_INVALID':
        return 'PROCTOR_SETUP_INVALID'
      case 'SCREEN_SHARE_STOPPED':
        return 'SCREEN_SHARE_STOPPED'
      case 'SCREEN_SHARE_DENIED':
        return 'SCREEN_SHARE_DENIED'
      case 'FULLSCREEN_EXIT_ATTEMPT':
        return 'FULLSCREEN_EXIT'
      default:
        return null
    }
  }, [])

  /** Detections worth attaching a webcam frame to; the rest are browser-state events. */
  const violationNeedsEvidence = useCallback((type) => [
    'PHONE_DETECTED',
    'MULTIPLE_FACES',
    'FACE_NOT_VISIBLE',
    'FACE_TURNED_AWAY',
    'EYES_OFF_SCREEN',
    'BACKGROUND_NOISE',
    // A microphone can establish that someone spoke; only the camera can
    // establish who, and that is the question the strike will be argued over.
    'VOICE_DETECTED',
    'SOUND_DETECTED',
    // Especially this one: "the camera setup is wrong" is a claim an invigilator
    // can only adjudicate by seeing the frame it was made from.
    'PROCTOR_SETUP_INVALID'
  ].includes(type), [])

  const reportViolationEvent = useCallback((payload) => {
    const apiViolationType = mapViolationTypeForApi(payload.type)
    const examId = currentSession?.examId
    const studentId = user?.userId
    const willReport = Boolean(apiViolationType && examId && studentId)

    /*
     * The detection always joins the local timeline. Whether it advances the
     * strike counter is a different question, and one this client is not
     * entitled to answer for anything it is about to report: the server decides
     * which detections cost a strike — review-only types cost none, and an
     * unidentified sound costs none until the attempt's grace is spent — so
     * counting it here optimistically produces a number that is wrong the
     * moment a forgiven sound goes through, and `violationCount >= 3` ends the
     * exam off that wrong number. Only unreportable detections, which nothing
     * else will ever count, are still counted locally.
     */
    dispatch(recordViolation({ ...payload, countsLocally: !willReport }))

    if (!willReport) {
      return
    }

    // Grabbed synchronously, at the moment of the violation — a frame captured
    // after an await would show the candidate already back in position.
    const evidenceImage = violationNeedsEvidence(payload.type)
      ? captureEvidenceFrame(webcamRef)
      : null

    // Fire-and-forget: the exam UI must never wait on the proctoring write.
    proctoringAPI.logViolation({
      examId,
      studentId,
      violationType: apiViolationType,
      evidenceImage,
      description: payload.description,
      confidence: typeof payload.confidence === 'number' ? payload.confidence : null
    }).then((response) => {
      const result = response?.data?.data
      if (!result) {
        return
      }

      if (Number.isInteger(result.strikeCount)) {
        dispatch(syncViolationCount(result.strikeCount))
      }

      // The server owns the termination verdict. Honour it even if the local
      // counter disagrees, which it can after a dropped or retried request.
      if (result.isTerminated) {
        dispatch(syncViolationCount(3))
      }
    }).catch((err) => {
      console.error('Failed to persist violation:', err)
    })
  }, [dispatch, currentSession, user, mapViolationTypeForApi, violationNeedsEvidence])

  useEffect(() => {
    reportViolationEventRef.current = reportViolationEvent
  }, [reportViolationEvent])

  /**
   * Streams the exam legitimately owns. Read at call time (not memoised) so the
   * anti-recording guard sees the webcam stream even when our own MediaRecorder
   * is constructed in the same tick the stream is attached.
   */
  const getOwnedStreams = useCallback(() => ({
    webcam: webcamRef.current?.stream || null,
    screen: screenStreamRef.current || null
  }), [])

  /** Called by react-webcam once the camera is actually delivering frames. */
  const handleProctorStreamReady = useCallback(() => {
    proctorVideoRef.current = webcamRef.current?.video || null
    setProctorStreamReady(Boolean(proctorVideoRef.current))
  }, [])

  const handleProctorStreamError = useCallback((error) => {
    console.error('Proctoring camera stream failed:', error)
    proctorVideoRef.current = null
    setProctorStreamReady(false)
  }, [])

  /**
   * The engine boots as soon as camera permission is granted — during the
   * pre-start screen, not after the exam begins.
   *
   * Two reasons. First, the models are a ~15MB download plus WASM compile;
   * booting them at exam start meant the opening ~30 seconds of every exam had
   * no phone or face detection at all, silently. Second, a candidate can now
   * be told the engine is unavailable *before* they burn an attempt on it.
   *
   * Deliberately NOT gated on `proctorStreamReady`: the pre-start and in-exam
   * screens are different render trees, so the <Webcam> unmounts and remounts
   * across that transition. Gating on stream readiness would tear the worker
   * down and reload every model at the exact moment the exam begins.
   * `dispatchFrame` already no-ops while the video element is detached, so the
   * worker simply idles for those few frames instead.
   */
  const aiProctoringEnabled = cameraPermission && !examSubmitted

  /**
   * Detections raised before the exam is live are warmup diagnostics, not
   * misconduct — the candidate has not agreed to anything yet and there is no
   * session to attribute a strike to. Uses the ref to avoid the stale-closure
   * trap that silently disabled noise detection.
   */
  const handleAiViolation = useCallback((payload) => {
    if (!examInProgressRef.current || examSubmitted) {
      return
    }
    reportViolationEvent(payload)
  }, [reportViolationEvent, examSubmitted])

  const {
    workerReady: aiWorkerReady,
    workerError: aiWorkerError,
    lastStatusLog: aiWorkerStatusLog,
    capabilities: aiCapabilities,
    detectionStatus,
    throughput: aiThroughput,
    recalibrate: recalibrateProctorPose,
    resetFraming: resetProctorFraming,
    restart: handleRetryAiEngine
  } = useAiProctorWorker({
    enabled: aiProctoringEnabled,
    videoRef: proctorVideoRef,
    onViolation: handleAiViolation
  })

  /**
   * Turns a classified sound into a violation, or decides it is not one.
   *
   * The engine's contract is that it reports every sound it segments; this is
   * where that becomes policy. Three gates, in order of how often they fire:
   *
   *   - Unidentified sounds have to be clearly louder than the room before they
   *     are worth an invigilator's attention. See
   *     REVIEW_SOUND_MIN_ABOVE_FLOOR_DB — this is the mechanical-keyboard rule.
   *   - Sounds before the exam is live, or during the warmup the other checks
   *     already honour, are setup noise. The meter still moves for them, which
   *     is the point of showing it on the pre-start screen at all.
   *   - Then the per-type cooldown, so one bad minute cannot spend three strikes.
   *
   * Reported through `reportViolationEventRef`, never the captured binding: this
   * is invoked from a worklet message port whose handler was installed during
   * exam start, before `startExamSuccess` had re-rendered the page, so the
   * captured `reportViolationEvent` would run against `examId === undefined`
   * forever. That is the exact bug that kept BACKGROUND_NOISE in Redux and out
   * of the database for the whole life of the previous monitor.
   */
  const handleSoundEvent = useCallback((event) => {
    const violationType = SOUND_VIOLATION_BY_CLASS[event.soundClass]
    if (!violationType) {
      return
    }

    if (violationType === 'SOUND_DETECTED' && event.peakAboveFloorDb < UNIDENTIFIED_SOUND_MIN_ABOVE_FLOOR_DB) {
      return
    }

    if (!examInProgressRef.current || examSubmitted || isInProctoringWarmup()) {
      return
    }

    const now = Date.now()
    const lastAt = lastSoundViolationAtRef.current[violationType] || 0
    if (now - lastAt <= (SOUND_COOLDOWN_MS[violationType] ?? 10000)) {
      return
    }
    lastSoundViolationAtRef.current[violationType] = now

    console.warn(
      `Sound detected — ${event.soundClass} ${event.durationMs}ms, ` +
      `peak ${event.peakDb}dB (${event.peakAboveFloorDb}dB over room floor), ` +
      `voiced ${Math.round(event.voicedRatio * 100)}%` +
      (event.pitchHz ? `, pitch ${event.pitchHz}Hz` : '')
    )

    reportViolationEventRef.current?.({
      type: violationType,
      description: describeSoundEvent(event),
      // One severity for every sound class now that they all cost a strike:
      // labelling one LOW while it spends the same third of an attempt as the
      // others tells the candidate it is safe to keep making it.
      severity: 'MEDIUM',
      confidence: event.confidence,
      timestamp: new Date().toISOString()
    })
  }, [examSubmitted])

  const {
    contextState: audioContextState,
    engineError: soundEngineError,
    micSilent,
    level: soundLevel,
    soundState,
    lastEvent: lastSoundEvent,
    eventCounts: soundEventCounts,
    diagnostics: audioDiagnostics,
    primeAudioContext,
    startListening: startSoundMonitoring
  } = useSoundEnvironmentMonitor({ onSoundEvent: handleSoundEvent })

  /*
   * Setup gate.
   *
   * One question, and only one: is the camera on, and can the engine see the
   * candidate? Framing, head pose and gaze are judged during the exam, where the
   * worker already allows a long streak of bad geometry before any of it becomes
   * a violation — so a candidate who is merely sitting badly is coached there
   * rather than held at the door here, with a check they cannot interpret and a
   * button that stays disabled.
   *
   * Presence is taken from whichever task is alive: FaceMesh answers it every
   * 300ms where it exists, COCO-SSD every second where it does not, and the
   * latter keeps answering when the face model disables itself mid-session.
   * Accepting either means the gate has a live source in every configuration the
   * engine can reach — a face-only gate could be left permanently shut by a
   * degraded face model, with nothing on the page able to reopen it.
   */
  const framingStatus = detectionStatus?.framing
  const framingGateApplies = Boolean(aiCapabilities?.faceLandmarks)
  const framingInstructions = describeFramingReasons(framingStatus?.reasons)
  const candidateVisible = Boolean(
    detectionStatus?.faceVisible || (detectionStatus?.peopleCount || 0) > 0
  )
  /**
   * No verdict has been received from either task yet.
   *
   * Distinguished from "we cannot see you" so the first moments after the engine
   * comes up do not accuse a candidate who is sitting correctly of being absent.
   */
  const presenceAwaitingVerdict = detectionStatus?.faceVisible == null
    && detectionStatus?.peopleCount == null

  /**
   * Re-acquires the camera and re-opens the setup gate.
   *
   * Both halves are needed, because the gate sticks for two unrelated reasons
   * and the candidate cannot tell them apart. Either the camera stopped
   * delivering frames — the gate then holds its last verdict forever, since a
   * verdict is only ever replaced by the next frame's — or frames are arriving
   * from a camera that is pointed at nothing, and the candidate has since moved
   * into frame. Re-mounting the element fixes the first, clearing the worker's
   * verdict fixes the second, and doing both means one control is enough for the
   * candidate to reach for.
   *
   * Not offered mid-exam: dropping and re-acquiring the camera is a gap in
   * proctoring coverage, which is acceptable before an attempt starts and is a
   * hole to be exploited once one has.
   */
  const handleRecheckCamera = useCallback(() => {
    proctorVideoRef.current = null
    setProctorStreamReady(false)
    setCameraRecheckAt(Date.now())
    setGateClockAt(Date.now())
    setCameraGeneration((generation) => generation + 1)
    resetProctorFraming()
  }, [resetProctorFraming])

  /*
   * Clears the "Rechecking…" label on a timer rather than on stream readiness.
   *
   * `onUserMedia` fires when the track opens, which is well before the first
   * decodable frame and several face cycles before a verdict exists. Clearing on
   * it would flip the label back to the old complaint while the new check has
   * genuinely not produced anything yet — the precise confusion the control is
   * meant to remove.
   */
  useEffect(() => {
    if (!cameraRecheckAt) {
      return undefined
    }
    const timerId = setTimeout(() => setCameraRecheckAt(null), CAMERA_RECHECK_SETTLE_MS)
    return () => clearTimeout(timerId)
  }, [cameraRecheckAt])

  /*
   * Starts the stall clock when the engine comes up, so the wait for the very
   * first verdict is bounded by the same rule as every later one. Without a
   * starting instant, "no verdict yet" and "no verdict for a minute" are the
   * same observation and the check can only be called stalled or never.
   */
  useEffect(() => {
    setGateClockAt(aiWorkerReady ? Date.now() : null)
  }, [aiWorkerReady])

  /*
   * "Slow" versus "stopped".
   *
   * Measured on the last frame the engine actually analysed rather than on one
   * task's verdict clock: presence may legitimately be answered by either model,
   * so the only thing that now means "no video" is no frame completing at all.
   * dispatchFrame counts a starved camera as a skip and posts nothing, so this
   * stops advancing the moment frames stop arriving. Re-evaluated on the
   * throughput publish, which ticks every couple of seconds for as long as the
   * engine is enabled and is therefore what makes this cross the threshold
   * without a timer of its own.
   */
  const lastAnalysedFrameAt = aiThroughput?.lastFrameAt
  const gateSilentSince = Math.max(lastAnalysedFrameAt || 0, gateClockAt || 0)
  const cameraCheckStalled = Boolean(
    aiWorkerReady
      && gateSilentSince > 0
      && Date.now() - gateSilentSince > FACE_VERDICT_STALL_MS
  )

  /*
   * Deliberately not `candidateVisible` alone. That is the last verdict
   * received, and a camera that dies leaves it reading true forever — the gate
   * would sit open on a stale observation while nothing at all was being
   * watched. Requiring live frames as well means the gate closes again when the
   * video does.
   */
  const setupGatePassed = candidateVisible && !cameraCheckStalled

  /**
   * Re-baselines head pose and gaze once the exam layout is on screen.
   *
   * The engine boots on the pre-start screen, so without this the neutral pose
   * it learns is the candidate reading the setup checklist — a different camera
   * framing, a different seating position, and often a different head angle
   * from the one they hold for the next hour. Every subsequent deviation was
   * then measured from the wrong origin, which both masks real head turns and
   * reports imaginary ones. Waiting for `proctorStreamReady` matters: the exam
   * tree mounts its own <Webcam>, and calibrating against the old detached
   * element would sample nothing.
   */
  useEffect(() => {
    if (!examInProgress || examSubmitted || !proctorStreamReady || !aiWorkerReady) {
      return undefined
    }
    // Small delay so auto-exposure and auto-focus have settled on the newly
    // attached stream; calibrating against the first over-bright frames yields
    // a baseline the candidate never returns to.
    const timerId = setTimeout(() => recalibrateProctorPose(), 1500)
    return () => clearTimeout(timerId)
  }, [examInProgress, examSubmitted, proctorStreamReady, aiWorkerReady, recalibrateProctorPose])

  /**
   * Adopts the server's view of the attempt from a heartbeat.
   *
   * The write path reports a strike in its own reply, which is fine right up to
   * the moment one of those replies does not arrive — a dropped connection, a
   * timeout, a tab reloaded between the request and the response. The strike is
   * recorded server-side regardless, so a client relying only on replies can sit
   * on a stale count indefinitely, and in the worst case keep a candidate
   * answering an attempt the server has already invalidated. The heartbeat is
   * the only thing that speaks on a schedule rather than on demand, so it is
   * where that disagreement gets settled.
   *
   * `examTerminated` is honoured ahead of the count and independently of it: it
   * is the server's verdict, and a client that has just learned the attempt is
   * over has no business re-deriving that from a number.
   */
  const handleHeartbeatSessionState = useCallback((sessionState) => {
    if (!examInProgressRef.current || examEndingRef.current) {
      return
    }

    if (Number.isInteger(sessionState.strikeCount)) {
      dispatch(syncViolationCount(sessionState.strikeCount))
    }

    if (sessionState.examTerminated) {
      dispatch(syncViolationCount(3))
    }
  }, [dispatch])

  const { networkStatus, heartbeatHealthy, recordingSuspected } = useBrowserHardening({
    enabled: examInProgress && !examSubmitted,
    sessionId: currentSession?.examSessionId,
    onViolation: reportViolationEvent,
    getOwnedStreams,
    onSessionState: handleHeartbeatSessionState
  })

  /*
   * Answers are mirrored to disk and to the server for the whole attempt, so an
   * interruption — a dropped connection, a power cut, a closed laptop — costs
   * at most the seconds since the last save rather than the whole paper. See
   * hooks/useExamAutosave.js.
   */
  const {
    status: autosaveStatus,
    lastSavedAt: autosaveAt,
    flush: flushProgress
  } = useExamAutosave({
    sessionToken: currentSession?.sessionToken,
    enabled: examInProgress && !examSubmitted,
    answers,
    markedForReview,
    currentQuestionNumber
  })

  /*
   * Moving to another question is the natural save point: the candidate has
   * finished with the one behind them, and it is the moment a save costs
   * nothing they would notice. The timer still covers the candidate who spends
   * twenty minutes on one hard question.
   */
  useEffect(() => {
    if (!examInProgress || examSubmitted) {
      return
    }
    void flushProgress()
  }, [currentQuestionNumber, examInProgress, examSubmitted, flushProgress])

  const handleGoBackFromPreStart = () => {
    navigate(getPostExamRedirectPath())
  }

  const preventExamInteractionCopy = (e) => {
    if (!examInProgress) return

    e.preventDefault()

    if (e.type === 'contextmenu') {
      showBlockedActionWarning('Right-click is disabled during the exam.')
      return
    }

    showBlockedActionWarning('Copy and paste actions are disabled during the exam.')
  }

  // Handle browser/tab switching, prevent cheating shortcuts, and block copy actions
  useEffect(() => {
    const handleVisibilityChange = () => {
      if (isInProctoringWarmup()) {
        return
      }
      if (document.hidden && examInProgress && screenShareActive && !screenShareGraceRef.current) {
        reportViolationEvent({
          type: 'TAB_SWITCH',
          description: 'User switched to a different browser tab during exam',
          severity: 'HIGH',
          timestamp: new Date().toISOString()
        })
        console.warn('Tab switch detected - exam window blurred')
      }
    }

    const handleBlur = () => {
      if (isInProctoringWarmup()) {
        return
      }
      if (examInProgress && screenShareActive && !screenShareGraceRef.current) {
        reportViolationEvent({
          type: 'WINDOW_BLUR',
          description: 'User minimized exam window or switched to another application',
          severity: 'HIGH',
          timestamp: new Date().toISOString()
        })
        console.warn('Browser window focus lost')
      }
    }

    const handleKeyDown = (e) => {
      if (!examInProgress) return

      const lowerKey = (e.key || '').toLowerCase()
      const isCopyAction = (e.ctrlKey || e.metaKey) && ['c', 'x', 'v', 'a', 's', 'u', 'p'].includes(lowerKey)
      const isDevToolsAction = e.key === 'F12' || ((e.ctrlKey || e.metaKey) && e.shiftKey && ['i', 'j', 'c'].includes(lowerKey))

      // Prevent common cheating shortcuts
      if (
        e.key === 'F11' || // Fullscreen toggle
        (e.ctrlKey && e.key === 'q') || // Close window
        (e.ctrlKey && e.key === 'w') || // Close tab
        (e.ctrlKey && e.key === 't') || // New tab
        (e.ctrlKey && e.key === 'n') || // New window
        (e.altKey && e.key === 'F4') || // Close window (Alt+F4)
        isCopyAction || // Copy/paste/select all/save/view source/print
        isDevToolsAction || // DevTools shortcuts
        e.key === 'Escape' // Escape key
      ) {
        e.preventDefault()
        if (isDevToolsAction) {
          showBlockedActionWarning('Developer tools shortcuts are disabled during the exam.')
        } else if (isCopyAction) {
          showBlockedActionWarning('Copy and paste shortcuts are disabled during the exam.')
        } else {
          showBlockedActionWarning('Restricted keyboard shortcut blocked during the exam.')
        }
        reportViolationEvent({
          type: 'RESTRICTED_KEY_PRESS',
          description: `Restricted key combination detected: ${e.key}`,
          severity: 'LOW',
          timestamp: new Date().toISOString()
        })
      }
    }

    document.addEventListener('visibilitychange', handleVisibilityChange)
    globalThis.addEventListener('blur', handleBlur)
    globalThis.addEventListener('keydown', handleKeyDown, true)
    document.addEventListener('contextmenu', preventExamInteractionCopy)
    document.addEventListener('copy', preventExamInteractionCopy)
    document.addEventListener('cut', preventExamInteractionCopy)
    document.addEventListener('paste', preventExamInteractionCopy)
    document.addEventListener('dragstart', preventExamInteractionCopy)

    return () => {
      document.removeEventListener('visibilitychange', handleVisibilityChange)
      globalThis.removeEventListener('blur', handleBlur)
      globalThis.removeEventListener('keydown', handleKeyDown, true)
      document.removeEventListener('contextmenu', preventExamInteractionCopy)
      document.removeEventListener('copy', preventExamInteractionCopy)
      document.removeEventListener('cut', preventExamInteractionCopy)
      document.removeEventListener('paste', preventExamInteractionCopy)
      document.removeEventListener('dragstart', preventExamInteractionCopy)
    }
  }, [examInProgress, screenShareActive, reportViolationEvent]) // eslint-disable-line react-hooks/exhaustive-deps

  // Timer countdown
  useEffect(() => {
    if (!examInProgress || timeLeft <= 0) return
    const timer = setInterval(() => {
      setTimeLeft((prev) => {
        if (prev <= 1) {
          handleSubmitExam()
          return 0
        }
        return prev - 1
      })
    }, 1000)
    return () => clearInterval(timer)
  }, [examInProgress, timeLeft]) // eslint-disable-line react-hooks/exhaustive-deps

  // Auto-terminate the exam when the candidate reaches 3 violations.
  useEffect(() => {
    if (violationCount >= 3 && examInProgress && !autoTerminateRef.current) {
      autoTerminateRef.current = true
      setShowTerminateDialog(false)
      setShowViolationAlert(false)
      handleAutoTerminateExam()
    }
  }, [violationCount, examInProgress]) // eslint-disable-line react-hooks/exhaustive-deps

  // Monitor fullscreen - user should not exit until submission
  useEffect(() => {
    const handleFullscreenChange = () => {
      if (!examInProgress || examSubmitted) {
        return
      }

      if (document.fullscreenElement) {
        // Enable enforcement only after the first confirmed fullscreen entry.
        fullscreenEnforcementReadyRef.current = true
        lockEscapeKeyInFullscreen()
        return
      }

      if (!fullscreenEnforcementReadyRef.current) {
        return
      }

      if (isInProctoringWarmup()) {
        return
      }

      if (!document.fullscreenElement) {
        // User attempted to exit fullscreen, record violation
        reportViolationEvent({
          type: 'FULLSCREEN_EXIT_ATTEMPT',
          description: 'User attempted to exit fullscreen mode during exam',
          severity: 'HIGH',
          timestamp: new Date().toISOString()
        })

        // Re-enable fullscreen mode after violation.
        setTimeout(() => {
          requestFullscreenRecovery()
        }, 0)
      }
    }

    if (examInProgress) {
      document.addEventListener('fullscreenchange', handleFullscreenChange)
      return () => document.removeEventListener('fullscreenchange', handleFullscreenChange)
    }
  }, [examInProgress, examSubmitted, reportViolationEvent, lockEscapeKeyInFullscreen, requestFullscreenRecovery])

  // Watch for new violations and show alert
  useEffect(() => {
    if (violations.length > 0) {
      const currentViolation = violations[violations.length - 1]
      if (currentViolation !== lastViolation) {
        setLastViolation(currentViolation)
        if (violationCount < 3) {
          setShowViolationAlert(true)
        } else {
          setShowViolationAlert(false)
        }
      }
    }
  }, [violations, lastViolation])

  const getViolationDetails = (violation) => {
    const details = {
      BACKGROUND_NOISE: {
        title: 'Excessive Background Noise Detected',
        details: 'The proctoring system detected excessive noise from your surroundings.',
        action: 'Please ensure a quiet environment for the remainder of the exam.'
      },
      WINDOW_BLUR: {
        title: 'Window Focus Lost',
        details: 'You minimized the exam window or switched to another application.',
        action: 'Keep the exam window in focus and do not switch to other applications.'
      },
      TAB_SWITCH: {
        title: 'Tab Switch Detected',
        details: 'You switched to a different browser tab while the exam was active.',
        action: 'Keep only this exam tab open and do not switch tabs during the exam.'
      },
      FULLSCREEN_EXIT_ATTEMPT: {
        title: 'Fullscreen Mode Violation',
        details: 'You attempted to exit fullscreen mode during the exam.',
        action: 'The system has automatically re-enabled fullscreen. Please keep the exam in fullscreen mode.'
      },
      SCREEN_SHARE_STOPPED: {
        title: 'Screen Sharing Violation',
        details: 'You attempted to stop screen sharing during the exam.',
        action: 'The system is re-requesting screen sharing permission. Please approve to continue the exam.'
      },

      /*
       * The AI detections below previously had no entry here at all, so they
       * fell through to the generic branch and showed the candidate a raw
       * constant ("PHONE_DETECTED") plus the worker's internal description
       * ("matched 'cell phone'"). A candidate cannot act on that; these say
       * what was seen and what to do about it.
       */
      PHONE_DETECTED: {
        title: 'Mobile Phone Detected',
        details: 'A mobile phone was seen in your camera view.',
        action: 'Do not use a mobile phone while taking the exam. Put the device away, out of camera view, and keep your hands visible.'
      },
      MULTIPLE_FACES: {
        title: 'Another Person Detected',
        details: 'More than one person was visible in your camera view.',
        action: 'You must be alone for the whole exam. Ask anyone else to leave the room.'
      },
      FACE_NOT_VISIBLE: {
        title: 'Face Not Visible',
        details: 'Your face could not be seen in the camera for a sustained period.',
        action: 'Stay centred in front of the camera, with your face lit and unobstructed.'
      },
      FACE_TURNED_AWAY: {
        title: 'Head Turned Away From Screen',
        details: 'You turned your head away from the exam screen.',
        action: 'Keep facing the screen. Looking around the room is recorded as a violation.'
      },
      EYES_OFF_SCREEN: {
        title: 'Eyes Off Screen',
        details: 'Your gaze moved away from the exam window — down or to the side.',
        action: 'Keep your eyes on the exam window. Do not consult notes, a second screen, or a phone.'
      }
    }
    return details[violation.type] || {
      title: violation.type,
      details: violation.description,
      action: 'Please follow exam guidelines.'
    }
  }

  // Request and maintain screen share throughout exam (user cannot stop it)
  const requestScreenShare = async ({ trackViolation = true, autoRetry = true } = {}) => {
    if (examEndingRef.current) {
      return { ok: false, message: 'Exam is ending. Screen sharing request has been cancelled.' }
    }

    const existingTrack = screenStreamRef.current?.getVideoTracks?.()?.[0]
    if (existingTrack && existingTrack.readyState === 'live') {
      console.log('Screen share already active')
      return { ok: true }
    }

    try {
      stopScreenShareTracking()

      const screenStream = await navigator.mediaDevices.getDisplayMedia({
        video: { cursor: 'always', displaySurface: 'monitor' },
        audio: false
      })
      screenStreamRef.current = screenStream
      // Suppress violation detection briefly while the browser
      // transitions focus back from the OS screen-picker dialog to the exam window
      screenShareGraceRef.current = true
      setTimeout(() => { screenShareGraceRef.current = false }, 2000)
      setScreenShareActive(true)
      console.log('Screen sharing started for exam proctoring - stream maintained')

      // Monitor if user tries to stop screen sharing
      screenShareCheckIntervalRef.current = setInterval(() => {
        if (screenStreamRef.current) {
          const videoTracks = screenStreamRef.current.getVideoTracks()
          if (videoTracks.length === 0 || videoTracks[0].readyState === 'ended') {
            console.warn('Screen share stopped by user - attempting to re-request')
            if (trackViolation) {
              reportViolationEvent({
                type: 'SCREEN_SHARE_STOPPED',
                description: 'User attempted to stop screen sharing during exam',
                severity: 'HIGH',
                timestamp: new Date().toISOString()
              })
            }
            screenStreamRef.current = null
            setScreenShareActive(false)
            // Attempt to re-request screen share after 1 second
            if (autoRetry) {
              screenShareRetryTimeoutRef.current = setTimeout(() => {
                if (!examEndingRef.current && !examSubmitted && examInProgress) {
                  requestScreenShare()
                }
              }, 1000)
            }
          }
        }
      }, 2000)

      return { ok: true }
    } catch (err) {
      setScreenShareActive(false)
      const message = getScreenShareErrorMessage(err)

      if (err.name === 'NotAllowedError') {
        console.warn('Screen sharing permission denied or blocked by system')
        if (trackViolation) {
          reportViolationEvent({
            type: 'SCREEN_SHARE_DENIED',
            description: 'User denied screen sharing permission during exam',
            severity: 'LOW',
            timestamp: new Date().toISOString()
          })
        }
        // Re-attempt screen share if user denied
        if (autoRetry) {
          screenShareDeniedRetryTimeoutRef.current = setTimeout(() => {
            if (!examEndingRef.current && !examSubmitted && examInProgress && !screenStreamRef.current) {
              console.log('Re-attempting screen sharing after denial...')
              requestScreenShare()
            }
          }, 2000)
        }
      } else {
        console.warn('Screen share setup failed:', err.message)
      }
      return { ok: false, message }
    }
  }

  // Cleanup screen share and keyboard lock on unmount. The audio graph is not
  // torn down here — useSoundEnvironmentMonitor owns its own context, stream
  // and worklet node, and releases them in its own unmount effect.
  useEffect(() => {
    return () => {
      if (screenShareCheckIntervalRef.current) {
        clearInterval(screenShareCheckIntervalRef.current)
      }
      unlockKeyboardIfNeeded()
      clearScreenShareRetryTimers()
      if (screenStreamRef.current) {
        screenStreamRef.current.getTracks().forEach((track) => track.stop())
      }
    }
  }, [unlockKeyboardIfNeeded])

  // Mark current question as viewed
  useEffect(() => {
    const currentQ = sessionQuestions[currentQuestionNumber]
    if (currentQ?.questionId) {
      setViewedQuestions((prev) => new Set(prev).add(currentQ.questionId))
    }
  }, [currentQuestionNumber, sessionQuestions])

  // Fetch question on demand
  const loadQuestion = useCallback(async (questionNumber) => {
    if (sessionQuestions[questionNumber]) {
      setCurrentQuestionNumber(questionNumber)
      return
    }
    try {
      const response = await examAPI.getSessionQuestion(
        currentSession.sessionToken,
        questionNumber
      )
      dispatch(loadSessionQuestion({
        number: questionNumber,
        question: response.data.data.question
      }))
      setCurrentQuestionNumber(questionNumber)
    } catch (err) {
      console.error('Failed to load question:', err)
    }
  }, [currentSession, sessionQuestions, dispatch])

  /*
   * Functional updates, not spreads of the captured `answers`. Two changes
   * landing in one tick used to lose the first, which mattered little when the
   * only reader was the submit at the end and matters a great deal now that
   * every change is also what gets persisted.
   */
  const handleAnswerChange = (questionId, answer, isMultiple = false) => {
    setAnswers((prev) => {
      if (!isMultiple) {
        return { ...prev, [questionId]: answer }
      }
      const selected = prev[questionId] || []
      return {
        ...prev,
        [questionId]: selected.includes(answer)
          ? selected.filter((a) => a !== answer)
          : [...selected, answer]
      }
    })
  }

  const isQuestionAttempted = useCallback((questionId) => {
    const answer = answers[questionId]
    if (Array.isArray(answer)) {
      return answer.length > 0
    }
    return Boolean(answer)
  }, [answers])

  const handleToggleReview = () => {
    if (!currentQuestion?.questionId) {
      return
    }
    const questionId = currentQuestion.questionId
    setMarkedForReview((prev) => ({ ...prev, [questionId]: !prev[questionId] }))
  }

  const handlePreviousQuestion = () => {
    if (currentQuestionNumber > 1) {
      loadQuestion(currentQuestionNumber - 1)
    }
  }

  const handleNextQuestion = () => {
    if (currentQuestionNumber < (currentSession?.questionCount || 0)) {
      loadQuestion(currentQuestionNumber + 1)
    }
  }

  const handleSubmitExam = async () => {
    if (isSubmittingExam || examSubmitted) {
      return
    }

    if (!currentSession?.examSessionId) {
      setSubmitError('Unable to submit exam: session information is missing. Please refresh and try again.')
      return
    }

    setSubmitError('')
    setSubmitAttempt(0)
    setIsSubmittingExam(true)

    // Mark exam as submitted to prevent screen share re-request
    examEndingRef.current = true
    setExamSubmitted(true)
    setScreenShareActive(false)
    fullscreenEnforcementReadyRef.current = false

    if (mediaRecorderRef.current && isRecording) {
      mediaRecorderRef.current.stop()
    }

    // Stop screen sharing
    if (screenStreamRef.current) {
      screenStreamRef.current.getTracks().forEach((track) => track.stop())
      screenStreamRef.current = null
    }

    // Clear screen share check interval
    stopScreenShareTracking()

    // Exit fullscreen after submission
    await exitFullscreenSafely()
    unlockKeyboardIfNeeded()

    // Backend expects: { answers: [{ questionId, selectedOptions: [...] }] }
    const payload = {
      answers: Object.entries(answers).map(([questionId, value]) => ({
        questionId: Number(questionId),
        selectedOptions: Array.isArray(value) ? value : [value]
      }))
    }

    /*
     * Retried rather than failed on the first refusal. The submit is the one
     * call that cannot wait for the next autosave — until it lands the attempt
     * has no score — and the failure it is most likely to meet is a few seconds
     * of dead network at the end of an hour. Only transport failures are
     * repeated; a 4xx is the server's settled answer and repeating it would
     * just delay showing it. See isRetryableFailure.
     */
    let lastError = null
    for (let attempt = 1; attempt <= SUBMIT_MAX_ATTEMPTS; attempt += 1) {
      setSubmitAttempt(attempt)
      try {
        await examAPI.submitExam(currentSession.examSessionId, payload)
        clearLocalDraft(currentSession.sessionToken)
        setShowTerminateDialog(false)
        setIsSubmittingExam(false)
        dispatch(endExamSession())
        navigate(`/exam/result/${currentSession.examSessionId}`)
        return
      } catch (err) {
        lastError = err
        console.error(`Failed to submit exam (attempt ${attempt}/${SUBMIT_MAX_ATTEMPTS}):`, err)
        if (!isRetryableFailure(err) || attempt === SUBMIT_MAX_ATTEMPTS) {
          break
        }
        await delay(SUBMIT_RETRY_DELAY_MS[attempt - 1] ?? 6000)
      }
    }

    /*
     * Back to a live exam rather than a dead end. The session is still
     * IN_PROGRESS on the server and the answers are still saved, so the
     * candidate can wait for their connection and press submit again.
     */
    examEndingRef.current = false
    setExamSubmitted(false)
    setIsSubmittingExam(false)
    setSubmitError(
      isRetryableFailure(lastError)
        ? 'Your exam could not be submitted — the connection to the server failed. '
          + 'Your answers are saved. Check your connection and press Submit again; '
          + 'nothing has been lost.'
        : lastError?.response?.data?.message || lastError?.message || 'Failed to submit exam. Please try again.'
    )
  }

  const handleAutoTerminateExam = async () => {
    examEndingRef.current = true
    setExamSubmitted(true)
    setScreenShareActive(false)
    fullscreenEnforcementReadyRef.current = false

    if (mediaRecorderRef.current && isRecording) {
      mediaRecorderRef.current.stop()
    }

    if (screenStreamRef.current) {
      screenStreamRef.current.getTracks().forEach((track) => track.stop())
      screenStreamRef.current = null
    }

    stopScreenShareTracking()

    await exitFullscreenSafely()
    unlockKeyboardIfNeeded()

    dispatch(endExamSession())
    setShowTerminationNotice(true)
  }

  const handleTerminationAcknowledge = () => {
    navigate('/exams', {
      replace: true,
      state: {
        restartMessage: 'You received 3 proctoring violations. Your exam was terminated and you must re-apply and complete payment from the beginning to take the exam again.'
      }
    })
  }

  if (!cameraPermission || !microphonePermission) {
    const isDenied = cameraPermissionState === 'denied' || microphonePermissionState === 'denied'

    return (
      <>
      <PcbBackdrop intensity="subtle" />
      <Container maxWidth="lg" sx={{ py: 3, position: 'relative', zIndex: 1 }}>
        <PageHeader
          title="Exam Proctoring Setup"
          subtitle="Complete device checks before entering the secure exam environment"
        />

        <Grid container spacing={2}>
          <Grid item xs={12} md={8}>
            <Paper sx={{ p: 3 }}>
              <Typography variant="h5" gutterBottom>Permission Required</Typography>
              <Typography variant="body1" sx={{ mb: 2 }}>
                Camera and microphone access are mandatory to continue.
              </Typography>

              {permissionError && (
                <Alert severity="error" sx={{ mb: 2 }}>{permissionError}</Alert>
              )}

              {isDenied && (
                <Alert severity="warning" sx={{ mb: 2 }}>
                  <Typography variant="body2" sx={{ mb: 1 }}>Browser permission is currently blocked.</Typography>
                  <Typography variant="body2" sx={{ mb: 1 }}>Please allow Camera and Microphone in browser site settings for this page.</Typography>
                  <Typography variant="body2">After allowing access, click Retry to continue the exam.</Typography>
                </Alert>
              )}

              <Box sx={{ mb: 2 }}>
                <Typography variant="body2" color="textSecondary">Camera: {cameraPermissionState}</Typography>
                <Typography variant="body2" color="textSecondary">Microphone: {microphonePermissionState}</Typography>
              </Box>

              <Box sx={{ display: 'flex', gap: 1, mb: 3 }}>
                {/*
                  * Granted is green here, not copper. It used to be `primary`,
                  * which is the brand accent this app puts on call-to-action
                  * buttons — so a granted camera looked like something to click,
                  * and read as a different state from the green tick saying the
                  * same thing in the checklist beside it.
                  */}
                <Tooltip title="Camera access required">
                  <IconButton
                    size="small"
                    color={PERMISSION_BUTTON_COLOR[permissionPhase(cameraPermission, cameraPermissionState)]}
                  >
                    {cameraPermission ? <VideocamIcon /> : <VideocamOffIcon />}
                  </IconButton>
                </Tooltip>
                <Tooltip title="Microphone access required">
                  <IconButton
                    size="small"
                    color={PERMISSION_BUTTON_COLOR[permissionPhase(microphonePermission, microphonePermissionState)]}
                  >
                    {microphonePermission ? <MicIcon /> : <MicOffIcon />}
                  </IconButton>
                </Tooltip>
              </Box>

              <Button variant="contained" onClick={requestPermissions} disabled={isRequestingPermissions} fullWidth>
                {isRequestingPermissions ? 'Requesting Permission...' : 'Retry Camera & Microphone Access'}
              </Button>
            </Paper>
          </Grid>

          <Grid item xs={12} md={4}>
            <Paper sx={{ p: 2.5, height: '100%' }}>
              <Typography variant="subtitle1" sx={{ mb: 1.5, fontWeight: 700 }}>Security Checklist</Typography>
              <Box sx={{ display: 'grid', gap: 1.25 }}>
                {/*
                  * The glyph changes with the state, not just its colour. This
                  * list sits on the screen that appears *because* a permission
                  * is missing, so the one line that matters must not depend on
                  * a viewer being able to tell grey from green.
                  */}
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                  {cameraPermission
                    ? <VideocamIcon color="success" fontSize="small" />
                    : <VideocamOffIcon
                        color={PERMISSION_ICON_COLOR[permissionPhase(false, cameraPermissionState)]}
                        fontSize="small"
                      />}
                  <Typography variant="body2">Camera ready</Typography>
                </Box>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                  {microphonePermission
                    ? <MicIcon color="success" fontSize="small" />
                    : <MicOffIcon
                        color={PERMISSION_ICON_COLOR[permissionPhase(false, microphonePermissionState)]}
                        fontSize="small"
                      />}
                  <Typography variant="body2">Microphone ready</Typography>
                </Box>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                  <ScreenShareIcon fontSize="small" color="action" />
                  <Typography variant="body2">Screen sharing will be requested before start</Typography>
                </Box>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                  <FullscreenIcon fontSize="small" color="action" />
                  <Typography variant="body2">Exam runs in locked fullscreen mode</Typography>
                </Box>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                  <SecurityIcon fontSize="small" color="action" />
                  <Typography variant="body2">Proctoring violations are monitored continuously</Typography>
                </Box>
              </Box>
            </Paper>
          </Grid>
        </Grid>
      </Container>
      </>
    )
  }

  // Render pre-start screen before exam starts
  if (!testStarted) {
    /*
     * The setup as one sequence rather than a stack of alerts.
     *
     * Every entry below is a gate the Start button itself reads, so what the
     * candidate is shown cannot drift from the reason the button is disabled —
     * the same `devToolsOpen` / `aiWorkerReady` / `setupGatePassed` values
     * decide both. Recovery sits on the step that failed rather than in a panel
     * elsewhere, because the only other move a stuck candidate has is a page
     * reload, which discards the camera permission they have just granted.
     */
    const engineDetail = aiWorkerError
      ? `Could not start: ${aiWorkerError}`
      : (aiWorkerReady
        ? `Phone detection${aiCapabilities?.faceLandmarks ? ', face and gaze tracking' : ' and presence tracking'} active`
        : (aiWorkerStatusLog || 'Downloading detection models — one-time, about 23MB'))

    /*
     * Four outcomes for the presence gate, not two. "No video at all", "no
     * verdict yet" and "video, but nobody in it" each have a different fix, and
     * collapsing them into one message sends the candidate correcting something
     * that was never the problem.
     */
    const framingStep = (() => {
      if (!aiWorkerReady) {
        return { tone: 'pending', detail: 'Waiting for the AI proctoring engine' }
      }
      if (setupGatePassed) {
        return { tone: 'done', detail: 'Positioning verified — you are visible to the proctoring system' }
      }
      if (cameraCheckStalled) {
        return {
          tone: 'error',
          detail: 'No frames have reached the check for a while. Close any other app using the camera '
            + '(Zoom, Teams, Meet), make sure this tab stays in front, then recheck.'
        }
      }
      if (presenceAwaitingVerdict) {
        return { tone: 'active', detail: 'Waiting for the first frame from your camera. This usually takes a second.' }
      }
      return {
        tone: 'warn',
        detail: 'Your camera is working, but nobody is visible in it. Sit in front of the camera so your face '
          + 'is in frame, then wait a moment for this to clear.'
      }
    })()

    const setupSteps = [
      {
        key: 'permissions',
        title: 'Camera & microphone',
        detail: 'Access granted — the proctoring feed is live',
        tone: 'done'
      },
      {
        key: 'security',
        title: 'Security check',
        detail: devToolsOpen
          ? 'Developer tools are open. Close developer tools to start the exam.'
          : 'Developer tools closed, browser hardened for the attempt',
        tone: devToolsOpen ? 'error' : 'done'
      },
      {
        key: 'aiEngine',
        title: 'AI proctoring engine',
        detail: engineDetail,
        tone: aiWorkerError ? 'error' : (aiWorkerReady ? 'done' : 'active'),
        action: aiWorkerError
          ? (
            <Button size="small" variant="outlined" startIcon={<RefreshIcon />} onClick={handleRetryAiEngine}>
              Retry
            </Button>
          )
          : null
      },
      {
        key: 'framing',
        title: 'Camera framing',
        detail: framingStep.detail,
        tone: framingStep.tone,
        action: aiWorkerReady && !setupGatePassed
          ? (
            <Button
              size="small"
              variant="outlined"
              startIcon={<RefreshIcon />}
              onClick={handleRecheckCamera}
              disabled={isLoading || Boolean(cameraRecheckAt)}
            >
              {cameraRecheckAt ? 'Rechecking…' : 'Recheck camera'}
            </Button>
          )
          : null
      }
    ]

    /*
     * The ring counts gates that have actually passed — the same four the Start
     * button reads. It deliberately does not track the model download: the
     * engine publishes stage text, never a percentage, so a bar claiming one
     * would be invented. The stage text is shown verbatim on its own step.
     */
    const stepsPassed = setupSteps.filter((step) => step.tone === 'done').length
    const setupPercent = Math.round((stepsPassed / setupSteps.length) * 100)
    const ringRadius = 52
    const ringLength = 2 * Math.PI * ringRadius
    const ringColor = aiWorkerError || devToolsOpen || cameraCheckStalled
      ? tokens.danger
      : (setupPercent === 100 ? tokens.greenLt : tokens.copperLt)

    /*
     * Faults outrank progress. A candidate with developer tools open is not
     * waiting for anything, so reporting the download they happen to also be
     * waiting on would hide the one thing they have to act on — and would leave
     * the headline disagreeing with a ring already painted red.
     */
    const engineHeadline = aiWorkerError
      ? 'AI engine unavailable'
      : (devToolsOpen
        ? 'Developer tools open'
        : (!aiWorkerReady
          ? 'Loading AI engine…'
          : (setupGatePassed ? 'Environment ready' : 'Camera check pending')))

    const engineFeatures = [
      { label: 'Face & gaze tracking', ready: Boolean(aiCapabilities?.faceLandmarks) },
      { label: 'Phone & object detection', ready: aiWorkerReady },
      { label: 'Voice & noise detection', ready: microphonePermission }
    ]

    const sessionFacts = [
      {
        label: 'Application',
        value: `#${applicationId}${applicationLevel ? ` · ${applicationLevel}` : ''}`
      },
      {
        label: 'Engine',
        value: aiCapabilities?.backend
          ? `${aiCapabilities.backend}${aiCapabilities.simd ? ' + SIMD' : ''}`
          : (aiWorkerError ? 'unavailable' : 'loading…')
      },
      {
        label: 'Exam slot',
        value: booking?.scheduledExamTime ? formatExamSlot(booking.scheduledExamTime) : 'Open now'
      }
    ]

    return (
      <>
      <PcbBackdrop intensity="subtle" />
      <Container maxWidth="lg" sx={{ py: 3, position: 'relative', zIndex: 1 }}>
        <PageHeader
          title="Preparing Exam Environment"
          subtitle="Final checks before entering your secure exam session"
        />

        {/*
          * Three cards, one row, equal height — the row stretches to its
          * tallest card and each one fills it, so the bottom edges line up
          * whatever the setup state happens to put inside them. Nothing here is
          * sized by hand: the buttons and the syllabus link are pushed down
          * with `mt: auto`, which is what makes an unequal amount of content
          * still finish level.
          */}
        <Grid container spacing={2} alignItems="stretch">
          <Grid item xs={12} md={6} lg={3}>
            <Paper sx={{ p: 3, height: '100%', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2 }}>
              <Box sx={{ position: 'relative', width: 132, height: 132 }}>
                <Box component="svg" viewBox="0 0 132 132" sx={{ width: 132, height: 132, display: 'block' }}>
                  <circle cx="66" cy="66" r={ringRadius} fill="none" stroke="rgba(150,195,172,.14)" strokeWidth="8" />
                  <circle
                    cx="66"
                    cy="66"
                    r={ringRadius}
                    fill="none"
                    stroke={ringColor}
                    strokeWidth="8"
                    strokeLinecap="round"
                    strokeDasharray={ringLength}
                    strokeDashoffset={ringLength * (1 - setupPercent / 100)}
                    transform="rotate(-90 66 66)"
                    style={{ transition: 'stroke-dashoffset .6s ease, stroke .3s ease' }}
                  />
                </Box>
                <Box sx={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center' }}>
                  {setupPercent === 100
                    ? <CheckRoundedIcon sx={{ fontSize: 40, color: tokens.greenLt }} />
                    : (
                      <Typography sx={{ fontFamily: fonts.mono, fontSize: 22, fontWeight: 800, color: ringColor }}>
                        {setupPercent}%
                      </Typography>
                    )}
                </Box>
              </Box>

              <Box sx={{ textAlign: 'center' }}>
                <Typography sx={{ fontSize: 14, fontWeight: 800, color: tokens.ink }}>{engineHeadline}</Typography>
                <Typography sx={{ mt: 0.5, fontFamily: fonts.mono, fontSize: 10.5, color: tokens.muted, lineHeight: 1.5 }}>
                  {stepsPassed} of {setupSteps.length} checks passed
                </Typography>
              </Box>

              <Box sx={{ width: '100%', display: 'grid', gap: 1.1 }}>
                {engineFeatures.map((feature) => (
                  <Box key={feature.label} sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                    <Box
                      sx={{
                        flex: 'none',
                        width: 18,
                        height: 18,
                        borderRadius: '50%',
                        display: 'grid',
                        placeItems: 'center',
                        bgcolor: feature.ready ? tokens.greenLt : 'rgba(150,195,172,.10)',
                        color: feature.ready ? '#062017' : tokens.muted
                      }}
                    >
                      <CheckRoundedIcon sx={{ fontSize: 12 }} />
                    </Box>
                    <Typography sx={{ fontSize: 12.5, color: feature.ready ? tokens.body : tokens.muted }}>
                      {feature.label}
                    </Typography>
                  </Box>
                ))}
              </Box>

              <Box sx={{ width: '100%', display: 'grid', gap: 1, pt: 1.5, borderTop: `1px solid ${tokens.line}` }}>
                {sessionFacts.map((fact) => (
                  <Box key={fact.label} sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1.5 }}>
                    <Typography sx={{ fontSize: 11.5, color: tokens.muted }}>{fact.label}</Typography>
                    <Typography sx={{ fontFamily: fonts.mono, fontSize: 11, color: tokens.body, textAlign: 'right' }}>
                      {fact.value}
                    </Typography>
                  </Box>
                ))}
              </Box>
            </Paper>
          </Grid>

          {/*
            * The proctoring camera, shown rather than hidden.
            *
            * The engine needs a live, decoding <video> on this screen — it warms
            * up against these frames and the presence gate is judged from them.
            * That element used to be parked off-screen at 1x1, which meant the
            * one thing a candidate needs in order to pass the gate, namely what
            * the camera can actually see, was the one thing they could not look
            * at. Same element, same ref and key, moved to where it answers the
            * question.
            */}
          <Grid item xs={12} md={6} lg={4}>
            <Paper sx={{ p: 2, height: '100%', display: 'flex', flexDirection: 'column', gap: 1.25 }}>
              <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1 }}>
                <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>Camera Framing</Typography>
                <Box
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 0.75,
                    px: 1,
                    py: 0.35,
                    borderRadius: '999px',
                    bgcolor: proctorStreamReady ? 'rgba(63,211,160,.12)' : 'rgba(192,138,46,.12)',
                    border: `1px solid ${proctorStreamReady ? 'rgba(63,211,160,.34)' : 'rgba(192,138,46,.36)'}`
                  }}
                >
                  <Box
                    sx={{
                      width: 6,
                      height: 6,
                      borderRadius: '50%',
                      bgcolor: proctorStreamReady ? tokens.greenGlow : tokens.copperLt
                    }}
                  />
                  <Typography
                    sx={{
                      fontFamily: fonts.mono,
                      fontSize: 9.5,
                      letterSpacing: '.08em',
                      color: proctorStreamReady ? tokens.greenGlow : tokens.copperLt
                    }}
                  >
                    {proctorStreamReady ? 'LIVE' : 'STARTING'}
                  </Typography>
                </Box>
              </Box>

              <Box
                sx={{
                  position: 'relative',
                  borderRadius: '12px',
                  overflow: 'hidden',
                  lineHeight: 0,
                  bgcolor: tokens.sub0,
                  border: `1px solid ${tokens.line}`
                }}
              >
                <Webcam
                  /*
                    * Keyed on the recheck counter so "Recheck camera" tears the
                    * element down and mounts a new one, which is what releases
                    * the old track and calls getUserMedia again.
                    */
                  key={`proctor-camera-${cameraGeneration}`}
                  ref={webcamRef}
                  audio={false}
                  videoConstraints={PROCTOR_VIDEO_CONSTRAINTS}
                  screenshotFormat="image/jpeg"
                  screenshotQuality={0.5}
                  onUserMedia={handleProctorStreamReady}
                  onUserMediaError={handleProctorStreamError}
                  /*
                    * Mirrored, as every video-call preview is: an unmirrored
                    * feed makes "move left" mean move right, and a candidate
                    * correcting their position from it moves the wrong way.
                    * Only the preview is flipped — the frames the engine reads
                    * come from the element itself, untouched by CSS.
                    */
                  style={{ width: '100%', display: 'block', transform: 'scaleX(-1)' }}
                />

                {/*
                  * Where the face is meant to sit. The presence gate is pass or
                  * fail with no notion of "nearly", so without a target the only
                  * way to find the right position is to shuffle about until the
                  * button turns copper.
                  */}
                <Box sx={{ position: 'absolute', inset: 0, pointerEvents: 'none', display: 'grid', placeItems: 'center' }}>
                  <Box
                    sx={{
                      width: '44%',
                      aspectRatio: '3 / 4',
                      borderRadius: '50%',
                      border: `2px dashed ${setupGatePassed ? tokens.greenGlow : tokens.copperLt}`,
                      opacity: 0.55,
                      transition: 'border-color .3s ease'
                    }}
                  />
                </Box>
              </Box>

              {/*
                * The worker's own reasons, verbatim and actionable ("move
                * closer", "raise your camera"). Only shown while the gate is
                * shut: repeating corrections at a candidate already sitting
                * correctly teaches them to ignore the panel.
                */}
              {!setupGatePassed && framingInstructions.length > 0 && (
                <Box sx={{ display: 'grid', gap: 0.5 }}>
                  {framingInstructions.map((instruction) => (
                    <Typography key={instruction} sx={{ fontSize: 12, lineHeight: 1.5, color: tokens.copperLt }}>
                      {instruction}
                    </Typography>
                  ))}
                </Box>
              )}

              {setupGatePassed && (
                <Typography sx={{ fontSize: 12, lineHeight: 1.5, color: tokens.body }}>
                  You are in frame. Keep this position and lighting for the whole exam.
                </Typography>
              )}

              {!setupGatePassed && framingInstructions.length === 0 && (
                <Typography sx={{ fontSize: 12, lineHeight: 1.5, color: tokens.muted }}>
                  Sit so your face fills the guide, at eye level and in good light.
                </Typography>
              )}
              {/*
                * Last chance to read or download the syllabus: once the exam
                * starts the browser is locked to fullscreen and a download
                * prompt would itself look like a violation.
                */}
              {applicationLevel && (
                <Button
                  variant="outlined"
                  fullWidth
                  size="small"
                  startIcon={<MenuBookIcon />}
                  onClick={() => setSyllabusOpen(true)}
                  sx={{ mt: 'auto' }}
                >
                  View {applicationLevel} syllabus
                </Button>
              )}
            </Paper>
          </Grid>

          <Grid item xs={12} lg={5}>
            <Paper sx={{ p: 3, height: '100%', display: 'flex', flexDirection: 'column' }}>
              <Typography variant="h6" sx={{ fontWeight: 700 }} gutterBottom>Exam Readiness</Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 2.5 }}>
                Do not switch tabs, minimize the window, or use restricted keyboard shortcuts once the exam starts.
              </Typography>

              <Box sx={{ mb: 2 }}>
                {setupSteps.map((step, index) => {
                  const stepTone = SETUP_STEP_TONE[step.tone]
                  const isLast = index === setupSteps.length - 1
                  return (
                    <Box key={step.key} sx={{ display: 'flex', gap: 1.75 }}>
                      <Box sx={{ flex: 'none', display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
                        <Box
                          sx={{
                            width: 28,
                            height: 28,
                            borderRadius: '50%',
                            display: 'grid',
                            placeItems: 'center',
                            fontSize: 12.5,
                            fontWeight: 800,
                            bgcolor: stepTone.dot,
                            border: `1.5px solid ${stepTone.border}`,
                            color: stepTone.glyph
                          }}
                        >
                          {step.tone === 'done' && <CheckRoundedIcon sx={{ fontSize: 16 }} />}
                          {(step.tone === 'error' || step.tone === 'warn') && <PriorityHighRoundedIcon sx={{ fontSize: 16 }} />}
                          {(step.tone === 'active' || step.tone === 'pending') && (index + 1)}
                        </Box>
                        {!isLast && (
                          <Box
                            sx={{
                              width: '2px',
                              flex: 1,
                              minHeight: 24,
                              my: '3px',
                              bgcolor: step.tone === 'done' ? tokens.greenLt : tokens.line2
                            }}
                          />
                        )}
                      </Box>
                      <Box sx={{ flex: 1, minWidth: 0, pb: isLast ? 0 : 2.25 }}>
                        <Typography sx={{ fontSize: 13.5, fontWeight: 700, color: tokens.ink }}>{step.title}</Typography>
                        <Typography sx={{ mt: 0.5, fontSize: 12, lineHeight: 1.55, color: stepTone.detail }}>
                          {step.detail}
                        </Typography>
                        {step.action && <Box sx={{ mt: 1.25 }}>{step.action}</Box>}
                      </Box>
                    </Box>
                  )
                })}
              </Box>

              {/*
                * What is left as an alert is what the timeline above cannot
                * carry: a failed start attempt, a booking the server will refuse
                * whatever the device checks say, and the news that this is a
                * rejoin rather than a first sitting. Every device-level fault
                * now lives on the step it belongs to.
                */}
              {!devToolsOpen && examError && (
                <Alert severity="warning" sx={{ mb: 2 }}>{examError}</Alert>
              )}

              {bookingBlocked && (
                <Alert
                  severity={slotMissed ? 'error' : 'info'}
                  sx={{ mb: 2 }}
                  action={
                    <Button
                      size="small"
                      color="inherit"
                      onClick={() => navigate(`/exam/schedule/${applicationId}`)}
                    >
                      {rebookingClosed ? 'View booking' : slotMissed ? 'Pick a new time' : 'Reschedule'}
                    </Button>
                  }
                >
                  {slotMissed
                    ? rebookingClosed
                      ? `Your slot of ${formatExamSlot(booking.scheduledExamTime)} has passed — it closed at
                         ${formatExamClock(booking.examWindowEnd)} — and this exam stopped taking bookings on
                         ${formatExamSlot(booking.bookingClosesAt)}. Contact support to have the window reopened;
                         your payment still stands.`
                      : `Your slot of ${formatExamSlot(booking.scheduledExamTime)} has passed — it closed at
                         ${formatExamClock(booking.examWindowEnd)}. Rebook to sit the exam; your payment still stands.`
                    : `Your exam is booked for ${formatExamSlot(booking.scheduledExamTime)} and opens in
                       ${formatCountdown(new Date(booking.examWindowStart).getTime() - windowNow)}, at
                       ${formatExamClock(booking.examWindowStart)}. Come back then${rebookingClosed ? '.' : ', or move your booking earlier.'}`}
                </Alert>
              )}

              {/*
                * A candidate coming back from a crash lands on this screen, which
                * otherwise reads exactly like a first sitting. Saying what is
                * about to happen — same paper, same clock, answers intact — is
                * the difference between continuing and quietly giving up on an
                * attempt they think they have already lost.
                */}
              {attemptWasInterrupted && (
                <Alert severity="info" icon={<RestoreIcon fontSize="inherit" />} sx={{ mb: 2 }}>
                  You have an attempt already in progress for this application. Continuing
                  reopens the same paper with the answers you had given; the clock has kept
                  running since you first started, so only the time that was left remains.
                </Alert>
              )}

              {/*
                * `mt: auto` rather than a fixed spacer: this is what lands the
                * button row on the bottom edge of a card whose height is set by
                * whichever of the three is tallest, so all three finish level
                * however much the timeline and the alerts above happen to fill.
                */}
              <Box sx={{ mt: 'auto', pt: 1, display: 'flex', gap: 1, flexDirection: { xs: 'column', sm: 'row' } }}>
                <Button
                  variant="outlined"
                  color="inherit"
                  fullWidth
                  size="large"
                  onClick={handleGoBackFromPreStart}
                  disabled={isLoading}
                >
                  Go Back
                </Button>
                <Button
                  variant="contained"
                  fullWidth
                  size="large"
                  onClick={handleStartExamClicked}
                  /*
                   * Blocked until the engine is up. Letting an exam start with
                   * proctoring dead would make blocking the model CDN a
                   * one-step way to disable detection entirely. Blocked equally
                   * on the presence check, for the same reason: an exam started
                   * from a camera showing nobody is one the engine cannot
                   * proctor at all.
                   *
                   * `bookingBlocked` is the same refusal the server would give,
                   * brought forward to before the setup so the candidate is not
                   * asked for their camera to be told their slot is tomorrow.
                   */
                  disabled={devToolsOpen || isLoading || !aiWorkerReady || !setupGatePassed || bookingBlocked}
                >
                  {isLoading && 'Loading...'}
                  {/*
                    * Named ahead of the setup gates, and suppressing them,
                    * because it outranks them: no amount of fixing a camera
                    * makes a slot that is a day away startable, and "Camera
                    * cannot see you" would send the candidate off correcting
                    * the wrong thing entirely.
                    */}
                  {!isLoading && bookingBlocked && (slotMissed ? 'Slot expired' : 'Not open yet')}
                  {!isLoading && !bookingBlocked && !aiWorkerReady && 'Preparing AI engine…'}
                  {/*
                    * Three labels rather than one for the closed gate: "no video
                    * at all", "no verdict yet" and "video, but nobody in it"
                    * have different fixes, and a single label would send the
                    * candidate correcting something that was never the problem.
                    */}
                  {!isLoading && !bookingBlocked && aiWorkerReady && !setupGatePassed && cameraCheckStalled && 'Camera check stalled'}
                  {!isLoading && !bookingBlocked && aiWorkerReady && !setupGatePassed && !cameraCheckStalled && presenceAwaitingVerdict && 'Checking camera…'}
                  {!isLoading && !bookingBlocked && aiWorkerReady && !setupGatePassed && !cameraCheckStalled && !presenceAwaitingVerdict && 'Camera cannot see you'}
                  {!isLoading && !bookingBlocked && aiWorkerReady && setupGatePassed
                    && (attemptWasInterrupted ? 'Resume Exam' : 'Start Exam Now')}
                </Button>
              </Box>
            </Paper>
          </Grid>
        </Grid>

        <SyllabusDialog
          open={syllabusOpen}
          level={applicationLevel}
          onClose={() => setSyllabusOpen(false)}
        />
      </Container>
      </>
    )
  }

  if (isLoading) {
    return <LoadingSpinner open={true} />
  }

  const currentQuestion = sessionQuestions[currentQuestionNumber]
  const questionNumbers = Array.from({ length: currentSession?.questionCount || 0 }, (_, i) => i + 1)
  const attemptedQuestionNumbers = questionNumbers.filter((num) => {
    const questionId = sessionQuestions[num]?.questionId
    return questionId ? isQuestionAttempted(questionId) : false
  })
  const reviewedQuestionNumbers = questionNumbers.filter((num) => {
    const questionId = sessionQuestions[num]?.questionId
    return questionId ? Boolean(markedForReview[questionId]) : false
  })
  const viewedCount = viewedQuestions.size

  /*
   * The candidate's evidence that their work is safe. Deliberately never shows
   * a failed save as an error: the draft is on this device either way, the next
   * tick retries, and an alarming message about a save that will succeed in
   * eight seconds only invites someone to do something rash mid-exam.
   */
  const autosaveLabel = (() => {
    if (networkStatus !== 'online' || autosaveStatus === 'offline') {
      return 'Offline · answers saved on this device'
    }
    if (autosaveStatus === 'saving') {
      return 'Saving answers…'
    }
    if (autosaveStatus === 'error') {
      return 'Answers saved on this device · retrying sync'
    }
    if (autosaveAt) {
      return `Answers saved ${new Date(autosaveAt).toLocaleTimeString()}`
    }
    return 'Answers save automatically'
  })()

  const autosaveTone = networkStatus !== 'online' || autosaveStatus === 'offline' || autosaveStatus === 'error'
    ? 'warning.main'
    : 'text.secondary'

  const getQuestionStatus = (num) => {
    const questionId = sessionQuestions[num]?.questionId
    if (!questionId) {
      return 'unanswered'
    }
    if (markedForReview[questionId]) {
      return 'review'
    }
    if (isQuestionAttempted(questionId)) {
      return 'attempted'
    }
    return 'unanswered'
  }
  /*
   * Board-surface recipes for the exam screen.
   *
   * Declared once rather than inline so the question panel, the navigator cell
   * and the two dialogs cannot drift apart: every surface below resolves back
   * to the same substrate/copper tokens the rest of the application uses.
   */
  const panelSx = { ...surface, borderRadius: '16px' }

  /** Neutral control — navigation and the review toggle. */
  const secondaryBtnSx = {
    height: 36,
    minWidth: 0,
    px: 2,
    py: 0,
    borderRadius: '9px',
    fontSize: 12,
    fontWeight: 600,
    lineHeight: 1,
    color: '#CFE2D8',
    background: 'rgba(95,174,146,.08)',
    border: `1.5px solid ${tokens.line2}`,
    '&:hover': { background: 'rgba(95,174,146,.14)', borderColor: 'rgba(150,195,172,.44)' },
    '&.Mui-disabled': {
      color: tokens.muted,
      background: 'rgba(95,174,146,.04)',
      border: `1.5px solid ${tokens.line}`,
    },
  }

  /** The copper CTA, sized down from the full-width form variant. */
  const ctaBtnSx = {
    ...ctaButton,
    width: 'auto',
    height: 36,
    px: 2.25,
    borderRadius: '9px',
    fontSize: 12,
    letterSpacing: '.3px',
    textTransform: 'none',
  }

  /** One answer row. Selection is carried by the copper edge, not just the dot. */
  const optionRowSx = (selected) => ({
    m: 0,
    width: '100%',
    gap: 1.25,
    px: 1.75,
    py: 1.25,
    borderRadius: '11px',
    background: selected ? 'rgba(192,138,46,.10)' : 'rgba(95,174,146,.06)',
    border: `1px solid ${selected ? 'rgba(232,192,113,.46)' : tokens.line}`,
    transition: 'background .16s, border-color .16s',
    '&:hover': { borderColor: 'rgba(232,192,113,.4)' },
    '& .MuiFormControlLabel-label': { fontSize: 14, color: tokens.ink, fontWeight: 500 },
  })

  const optionControlSx = {
    p: 0,
    color: tokens.line2,
    '&.Mui-checked': { color: tokens.copperLt },
  }

  /** One cell of the navigator grid, coloured by the question's own state. */
  const navCellSx = (status, isCurrent) => {
    const base = {
      minWidth: 28,
      width: 28,
      height: 28,
      p: 0,
      borderRadius: '7px',
      fontFamily: fonts.mono,
      fontSize: 11,
      fontWeight: 600,
      lineHeight: 1,
      color: '#CFE2D8',
      background: 'rgba(95,174,146,.06)',
      border: `1.5px solid ${tokens.line2}`,
      '&:hover': { background: 'rgba(95,174,146,.14)', borderColor: 'rgba(150,195,172,.44)' },
    }
    if (isCurrent) {
      return {
        ...base,
        background: `linear-gradient(160deg, ${tokens.copperLt}, ${tokens.copper})`,
        borderColor: tokens.copperLt,
        color: '#062017',
        fontWeight: 700,
        '&:hover': { background: `linear-gradient(160deg, ${tokens.copperLt}, ${tokens.copper})`, filter: 'brightness(1.07)' },
      }
    }
    if (status === 'review') {
      return {
        ...base,
        background: 'rgba(192,138,46,.08)',
        border: `1.5px dashed ${tokens.copperLt}`,
        color: tokens.copperLt,
        '&:hover': { background: 'rgba(192,138,46,.18)' },
      }
    }
    if (status === 'attempted') {
      return {
        ...base,
        background: 'rgba(95,174,146,.16)',
        borderColor: tokens.greenLt,
        '&:hover': { background: 'rgba(95,174,146,.26)' },
      }
    }
    return base
  }

  /** Gold caution block — the one used in both dialogs and the question footer. */
  const cautionBoxSx = {
    display: 'flex',
    gap: 1.2,
    p: '11px 13px',
    borderRadius: '10px',
    background: 'rgba(192,138,46,.10)',
    border: '1px solid rgba(192,138,46,.30)',
  }

  const sectionLabelSx = { ...microLabel, fontSize: 9.5, letterSpacing: '.6px', display: 'block' }

  const isOffline = examInProgress && !examSubmitted && networkStatus !== 'online'
  const hasBanner = Boolean(resumeNotice) || Boolean(submitError && !examSubmitted) || isOffline
  const allQuestionsViewed = viewedCount >= (currentSession?.questionCount || 0)

  return (
    <>
    <PcbBackdrop intensity="subtle" />
    <Box
      sx={{
        position: 'relative',
        zIndex: 1,
        boxSizing: 'border-box',
        display: 'flex',
        flexDirection: 'column',
        height: { xs: 'auto', lg: '100vh' },
        p: { xs: 1.5, sm: 2, lg: '16px 20px' },
      }}
    >
      {/* Capture-deterrent layer: traceable watermark + anti-photography grid. */}
      <ProctorSecurityOverlay
        visible={examInProgress && !examSubmitted}
        studentId={user?.userId}
        sessionId={currentSession?.examSessionId}
      />

      {/*
        * Board header. Carries the one status a candidate should never have to
        * hunt for — whether their answers are safe — outside the question
        * panel, so it cannot scroll away behind a long question.
        */}
      <Box
        sx={{
          flex: 'none',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: 1.5,
          mb: 1.5,
        }}
      >
        <Typography sx={{ fontSize: 15, fontWeight: 800, letterSpacing: '-.2px' }}>
          EMS Certification Exam
        </Typography>
        <Typography
          variant="caption"
          sx={{ color: autosaveTone, display: 'flex', alignItems: 'center', gap: 0.5 }}
        >
          {autosaveStatus === 'saving'
            ? <CloudSyncIcon sx={{ fontSize: 14 }} />
            : autosaveTone === 'warning.main'
              ? <CloudOffIcon sx={{ fontSize: 14 }} />
              : <CloudDoneIcon sx={{ fontSize: 14 }} />}
          {autosaveLabel}
        </Typography>
      </Box>

      {hasBanner && (
        <Box sx={{ flex: 'none', display: 'flex', flexDirection: 'column', gap: 1, mb: 1.5 }}>
          {/*
            * What an interruption cost, stated plainly. A candidate who has just
            * lost power and logged back in needs to know their answers came back
            * before they start re-doing them.
            */}
          {resumeNotice && (
            <Alert
              severity="success"
              icon={<RestoreIcon fontSize="inherit" />}
              onClose={() => setResumeNotice('')}
            >
              {resumeNotice}
            </Alert>
          )}

          {/*
            * Also shown here, not only inside the submit dialog. The timer's
            * automatic submission runs with no dialog open, so a failure there
            * used to leave the candidate on a finished exam with nothing on
            * screen to tell them it had not been handed in.
            */}
          {submitError && !examSubmitted && (
            <Alert severity="error" onClose={() => setSubmitError('')}>
              {submitError}
            </Alert>
          )}

          {/*
            * The connection dropping is not a violation and must not read like
            * one — the exam carries on, the clock carries on, and the answers
            * are already on this device. Saying so is what stops a candidate
            * abandoning an attempt they have not actually lost.
            */}
          {isOffline && (
            <Alert severity="warning" icon={<CloudOffIcon fontSize="inherit" />}>
              <strong>You are offline.</strong> Keep answering — your answers are being saved on
              this device and will sync automatically when the connection returns. If this
              machine shuts down, sign back in and reopen this exam: your attempt and its
              remaining time will still be there.
            </Alert>
          )}
        </Box>
      )}

      <Box
        sx={{
          flex: 1,
          minHeight: 0,
          display: 'grid',
          gap: 2,
          gridTemplateColumns: { xs: '1fr', lg: '1fr 320px' },
        }}
      >
        {/* Left: question panel */}
        <Box
          sx={{
            ...panelSx,
            minHeight: { xs: '60vh', lg: 0 },
            display: 'flex',
            flexDirection: 'column',
            p: { xs: 2, md: '22px 26px' },
          }}
        >
          <Box
            sx={{
              flex: 'none',
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'baseline',
              gap: 2,
            }}
          >
            <Typography sx={{ fontSize: 18, fontWeight: 800, letterSpacing: '-.3px' }}>
              Question {currentQuestionNumber} of {currentSession?.questionCount}
            </Typography>
            <Typography
              sx={{
                fontFamily: fonts.mono,
                fontSize: 14,
                fontWeight: 700,
                whiteSpace: 'nowrap',
                color: timeLeft < 300 ? tokens.danger : tokens.copperLt,
              }}
            >
              Time: {Math.floor(timeLeft / 60)}:{String(timeLeft % 60).padStart(2, '0')}
            </Typography>
          </Box>

          <LinearProgress
            variant="determinate"
            value={(currentQuestionNumber / (currentSession?.questionCount || 1)) * 100}
            sx={{
              flex: 'none',
              height: 4,
              mt: 1.25,
              mb: 2.5,
              backgroundColor: 'rgba(150,195,172,.14)',
              '& .MuiLinearProgress-bar': {
                background: `linear-gradient(90deg, ${tokens.greenLt}, ${tokens.copperLt})`,
              },
            }}
          />

          <Box sx={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
            {currentQuestion && (
              <>
                <Typography sx={{ ...sectionLabelSx, fontSize: 10, mb: 1 }}>
                  Question {currentQuestionNumber}
                  {currentQuestion.questionCode ? ` · ${currentQuestion.questionCode}` : ''}
                  {currentQuestion.questionType === 'SINGLE_CHOICE'
                    ? ' · Select one answer'
                    : ' · Select all that apply'}
                </Typography>

                <Typography sx={{ fontSize: 18, fontWeight: 700, letterSpacing: '-.2px', mb: 2 }}>
                  {currentQuestion.questionText}
                </Typography>

                {currentQuestion.questionType === 'SINGLE_CHOICE' ? (
                  <RadioGroup
                    value={answers[currentQuestion.questionId] || ''}
                    onChange={(e) => handleAnswerChange(currentQuestion.questionId, e.target.value)}
                    sx={{ gap: 1 }}
                  >
                    {currentQuestion.options.map((option, idx) => (
                      <FormControlLabel
                        key={idx}
                        value={option}
                        control={<Radio sx={optionControlSx} />}
                        label={option}
                        sx={optionRowSx(answers[currentQuestion.questionId] === option)}
                      />
                    ))}
                  </RadioGroup>
                ) : (
                  <FormGroup sx={{ gap: 1 }}>
                    {currentQuestion.options.map((option, idx) => (
                      <FormControlLabel
                        key={idx}
                        control={
                          <Checkbox
                            checked={(answers[currentQuestion.questionId] || []).includes(option)}
                            onChange={() => handleAnswerChange(currentQuestion.questionId, option, true)}
                            sx={optionControlSx}
                          />
                        }
                        label={option}
                        sx={optionRowSx((answers[currentQuestion.questionId] || []).includes(option))}
                      />
                    ))}
                  </FormGroup>
                )}
              </>
            )}
          </Box>

          <Box
            sx={{
              flex: 'none',
              mt: 1.75,
              pt: 2,
              borderTop: `1px solid ${tokens.line}`,
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              flexWrap: 'wrap',
              gap: 1.25,
            }}
          >
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.25, flexWrap: 'wrap' }}>
              <Typography variant="caption" sx={{ color: tokens.body, fontWeight: 500 }}>
                Viewed: {viewedCount}/{currentSession?.questionCount || 0}
              </Typography>
              {!allQuestionsViewed && (
                <Box
                  sx={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 0.6,
                    px: 1.1,
                    py: 0.45,
                    borderRadius: 999,
                    background: 'rgba(192,138,46,.10)',
                    border: '1px solid rgba(192,138,46,.30)',
                  }}
                >
                  <WarningIcon sx={{ fontSize: 12, color: tokens.copperLt }} />
                  <Typography sx={{ fontSize: 10.5, fontWeight: 600, color: tokens.copperLt }}>
                    Not all viewed
                  </Typography>
                </Box>
              )}
            </Box>

            <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
              <Button sx={secondaryBtnSx} onClick={handlePreviousQuestion} disabled={currentQuestionNumber === 1}>
                Previous
              </Button>
              <Button
                sx={secondaryBtnSx}
                onClick={handleNextQuestion}
                disabled={currentQuestionNumber === (currentSession?.questionCount || 0)}
              >
                Next
              </Button>
              <Button
                onClick={handleToggleReview}
                disabled={!currentQuestion}
                sx={{
                  ...secondaryBtnSx,
                  color: tokens.copperLt,
                  background: currentQuestion?.questionId && markedForReview[currentQuestion.questionId]
                    ? 'rgba(192,138,46,.24)'
                    : 'rgba(192,138,46,.10)',
                  border: `1.5px solid ${currentQuestion?.questionId && markedForReview[currentQuestion.questionId]
                    ? tokens.copper
                    : 'rgba(232,192,113,.40)'}`,
                  '&:hover': { background: 'rgba(192,138,46,.28)', borderColor: tokens.copperLt },
                }}
              >
                {currentQuestion?.questionId && markedForReview[currentQuestion.questionId]
                  ? 'Marked for Review'
                  : 'Mark for Review'}
              </Button>
              <Button variant="contained" sx={{ ...ctaBtnSx, fontWeight: 800 }} onClick={() => setShowTerminateDialog(true)}>
                Submit Exam
              </Button>
            </Box>
          </Box>
        </Box>

        {/* Right: navigator over proctoring, sharing one rail */}
        <Box
          sx={{
            ...panelSx,
            minHeight: { xs: 0, lg: 0 },
            display: 'flex',
            flexDirection: 'column',
            p: 2,
          }}
        >
          <Box
            sx={{
              flex: { lg: '0.85 1 0' },
              minHeight: 0,
              display: 'flex',
              flexDirection: 'column',
            }}
          >
            <Typography sx={{ flex: 'none', fontSize: 12.5, fontWeight: 800, mb: 1.1 }}>
              Question Navigator
            </Typography>

            <Box sx={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
              <Box
                sx={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(6, 1fr)',
                  justifyItems: 'center',
                  gap: '6px',
                  mb: 1.25,
                }}
              >
                {questionNumbers.map((num) => {
                  const status = getQuestionStatus(num)
                  return (
                    <Button
                      key={num}
                      onClick={() => loadQuestion(num)}
                      sx={navCellSx(status, currentQuestionNumber === num)}
                    >
                      {num}
                    </Button>
                  )
                })}
              </Box>

              <Box
                sx={{
                  display: 'flex',
                  gap: 1.5,
                  flexWrap: 'wrap',
                  fontFamily: fonts.mono,
                  '& .MuiTypography-root': { fontFamily: fonts.mono, fontSize: 10 },
                }}
              >
                <Typography sx={{ color: tokens.greenGlow }}>Attempted: {attemptedQuestionNumbers.length}</Typography>
                <Typography sx={{ color: tokens.copperLt }}>Review: {reviewedQuestionNumbers.length}</Typography>
                <Typography sx={{ color: tokens.muted }}>
                  Unanswered: {(currentSession?.questionCount || 0) - attemptedQuestionNumbers.length}
                </Typography>
              </Box>

              <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap', mt: 1.1 }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.6 }}>
                  <Box sx={{ width: 9, height: 9, borderRadius: '3px', background: `linear-gradient(160deg, ${tokens.copperLt}, ${tokens.copper})` }} />
                  <Typography sx={{ fontSize: 10, color: tokens.muted }}>Current</Typography>
                </Box>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.6 }}>
                  <Box sx={{ width: 9, height: 9, borderRadius: '3px', background: 'rgba(95,174,146,.35)', border: `1px solid ${tokens.greenLt}` }} />
                  <Typography sx={{ fontSize: 10, color: tokens.muted }}>Attempted</Typography>
                </Box>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.6 }}>
                  <Box sx={{ width: 9, height: 9, borderRadius: '3px', border: `1px dashed ${tokens.copperLt}` }} />
                  <Typography sx={{ fontSize: 10, color: tokens.muted }}>Review</Typography>
                </Box>
              </Box>
            </Box>
          </Box>

          <Box
            sx={{
              flex: { lg: '1.15 1 0' },
              minHeight: 0,
              display: 'flex',
              flexDirection: 'column',
              mt: 1.75,
              pt: 1.75,
              borderTop: `1px solid ${tokens.line}`,
            }}
          >
            <Box
              sx={{
                flex: 'none',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 1,
                mb: 1.1,
              }}
            >
              <Typography sx={{ fontSize: 12.5, fontWeight: 800 }}>Video Proctoring</Typography>
              <Box
                sx={{
                  px: 1,
                  py: 0.35,
                  borderRadius: 999,
                  fontFamily: fonts.mono,
                  fontSize: 9.5,
                  fontWeight: 700,
                  whiteSpace: 'nowrap',
                  color: violationCount > 0 ? '#F0A9A9' : tokens.muted,
                  background: violationCount > 0 ? 'rgba(224,101,101,.12)' : 'rgba(95,174,146,.08)',
                  border: `1px solid ${violationCount > 0 ? 'rgba(224,101,101,.34)' : tokens.line}`,
                }}
              >
                Violations: {violationCount}/3
              </Box>
            </Box>

            <Box sx={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
              {cameraPermission ? (
                <Box>
                  <Box
                    sx={{
                      borderRadius: '10px',
                      overflow: 'hidden',
                      border: '1px solid rgba(150,195,172,.18)',
                      lineHeight: 0,
                      mb: 1.1,
                    }}
                  >
                    <Webcam
                      ref={webcamRef}
                      audio={false}
                      /*
                       * 640x480, and no longer tied to the evidence size: gaze
                       * precision is bounded by the track resolution, while
                       * stored evidence is not. The worker downscales per task,
                       * so the phone detector still sees 320x240. Audio is off
                       * here because noise detection owns its own dedicated
                       * stream with echo cancellation disabled.
                       */
                      videoConstraints={PROCTOR_VIDEO_CONSTRAINTS}
                      screenshotFormat="image/jpeg"
                      screenshotQuality={0.5}
                      onUserMedia={handleProctorStreamReady}
                      onUserMediaError={handleProctorStreamError}
                      style={{ width: '100%', display: 'block', transform: 'scaleX(-1)' }}
                    />
                  </Box>

                  <Box
                    sx={{
                      display: 'flex',
                      flexWrap: 'wrap',
                      gap: 0.5,
                      mb: 1.1,
                      '& .MuiTypography-root': { fontFamily: fonts.mono, fontSize: 9.5, lineHeight: 1.5 },
                    }}
                  >
                    <Typography color={aiWorkerReady ? 'success.main' : 'text.secondary'}>
                      AI engine: {aiWorkerReady ? `active (${aiCapabilities?.backend || 'wasm'}${aiCapabilities?.simd ? ' + SIMD' : ''})` : 'loading…'}
                    </Typography>
                    <Typography color={networkStatus === 'online' && heartbeatHealthy ? 'success.main' : 'error.main'}>
                      · Network: {networkStatus === 'online' ? (heartbeatHealthy ? 'stable' : 'unstable') : 'offline'}
                    </Typography>
                    <Typography
                      color={soundState === 'sound' || audioContextState === 'suspended'
                        ? 'error.main'
                        : 'text.secondary'}
                    >
                      {/*
                        * "quiet" is only reported when the microphone is
                        * actually being heard. Reporting it from a suspended
                        * context claims a measurement that was never taken.
                        */}
                      · Mic: {audioContextState === 'suspended'
                        ? 'not listening'
                        : (soundState === 'unknown' ? 'starting…' : soundState)}
                    </Typography>
                    {detectionStatus?.phonePresent && (
                      <Typography color="error.main">· Phone in frame</Typography>
                    )}
                    {detectionStatus?.faceVisible === false && (
                      <Typography color="warning.main">· Face not visible</Typography>
                    )}
                    {detectionStatus?.turnedAway && (
                      <Typography color="warning.main">· Head turned away</Typography>
                    )}
                    {detectionStatus?.lookingDown && (
                      <Typography color="warning.main">
                        · {detectionStatus?.gazeAway ? 'Eyes off screen' : 'Looking down'}
                      </Typography>
                    )}
                    {recordingSuspected && (
                      <Typography color="error.main">· Recording activity detected</Typography>
                    )}
                  </Box>

                  {/*
                    * Live engine readout.
                    *
                    * Every silent failure in this pipeline looks the same from
                    * the outside — "nothing gets flagged" is equally consistent
                    * with a worker that never started, a video element that was
                    * never attached, a pose baseline that never calibrated, and
                    * a candidate doing nothing wrong. These four lines tell them
                    * apart without a debugger: frames analysed proves the models
                    * are seeing real video, the detection list proves what they
                    * scored it, and each pose number is printed next to the
                    * threshold it has to beat.
                    */}
                  <Box
                    sx={{
                      mb: 1.1,
                      p: 1,
                      bgcolor: 'rgba(150,195,172,.08)',
                      borderRadius: '8px',
                      border: `1px solid ${tokens.line}`,
                      '& .MuiTypography-root': { fontFamily: fonts.mono, fontSize: 9.5, lineHeight: 1.65 },
                    }}
                  >
                    <Typography display="block" color="text.secondary">
                      Frames analysed: {aiThroughput?.framesAnalysed ?? 0}
                      {aiThroughput?.framesSkipped ? ` (skipped ${aiThroughput.framesSkipped})` : ''}
                      {aiThroughput?.lastInferenceMs != null ? ` · ${aiThroughput.lastInferenceMs}ms` : ''}
                      {aiWorkerReady && !aiThroughput?.framesAnalysed && ' · no camera frames reaching the engine'}
                    </Typography>
                    <Typography display="block" color="text.secondary">
                      Camera sees: {detectionStatus?.topDetections?.length
                        ? detectionStatus.topDetections.join(', ')
                        : 'nothing above threshold'}
                    </Typography>
                    <Typography
                      display="block"
                      color={audioContextState === 'running' ? 'text.secondary' : 'error.main'}
                    >
                      Mic level: {soundLevel.db > -120 ? `${soundLevel.db}dB` : '–'}
                      {' / '}
                      {soundLevel.thresholdDb > -120 ? `${soundLevel.thresholdDb}dB` : '–'} onset
                      {soundLevel.floorDb > -120
                        ? ` (room floor ${soundLevel.floorDb}dB)`
                        : ' (measuring room…)'}
                      {/*
                        * The state is printed next to the level because the two
                        * together are the whole diagnosis: silence while running
                        * is a quiet room, silence while suspended is a dead
                        * microphone, and they are indistinguishable otherwise.
                        */}
                      {audioContextState !== 'running' && ` · audio ${audioContextState}`}
                    </Typography>
                    {/*
                      * The audio counterpart of "Camera sees", and it exists for
                      * the same reason: an engine that reports nothing is either
                      * working in a quiet room or not working at all, and the two
                      * are indistinguishable from the outside. A running event
                      * count proves sounds are being segmented and classified;
                      * a count stuck at zero on a mic that shows a live level
                      * means the thresholds are what to question.
                      */}
                    <Typography display="block" color="text.secondary">
                      Mic hears: {lastSoundEvent
                        ? `${SOUND_CLASS_LABELS[lastSoundEvent.soundClass] || lastSoundEvent.soundClass}` +
                          ` · ${lastSoundEvent.durationMs}ms · +${lastSoundEvent.peakAboveFloorDb}dB over floor`
                        : 'nothing above the room floor yet'}
                      {soundEventCounts.total ? ` · ${soundEventCounts.total} events` : ''}
                    </Typography>
                    {/*
                      * The track's own view of itself. `muted` here is the
                      * browser's flag for "this source is delivering no audio",
                      * which is not the same as the user muting anything, and
                      * it is the one state that produces a perfect 0.000 from a
                      * perfectly healthy context.
                      */}
                    <Typography
                      display="block"
                      color={audioDiagnostics?.muted || audioDiagnostics?.readyState === 'ended'
                        ? 'error.main'
                        : 'text.secondary'}
                    >
                      Mic input: {audioDiagnostics
                        ? `${audioDiagnostics.label} · ${audioDiagnostics.readyState}` +
                          `${audioDiagnostics.muted ? ' · MUTED BY BROWSER' : ''}` +
                          `${audioDiagnostics.enabled === false ? ' · disabled' : ''}`
                        : 'not initialised'}
                    </Typography>
                    <Typography display="block" color="text.secondary">
                      {detectionStatus?.calibrated
                        ? `Pose — yaw ${detectionStatus.yawDeviation ?? '–'}/${detectionStatus.yawThreshold ?? '–'} · ` +
                          `pitch ${detectionStatus.pitchDrop ?? '–'}/${detectionStatus.pitchThreshold ?? '–'} · ` +
                          `gaze ${detectionStatus.gazeHDeviation ?? '–'}/${detectionStatus.gazeVDeviation ?? '–'}`
                        : (detectionStatus?.calibrationBlocked
                          ? 'Pose — waiting for a usable camera position before calibrating'
                          : 'Pose — calibrating neutral position…')}
                    </Typography>
                    <Typography display="block" color="text.secondary">
                      Framing — balance {framingStatus?.faceBalance ?? '–'}
                      {framingStatus?.faceBalanceRange
                        ? `/${framingStatus.faceBalanceRange[0]}–${framingStatus.faceBalanceRange[1]}`
                        : ''}
                      {' · size '}{framingStatus?.faceWidthRatio ?? '–'}
                      {' · tilt '}{framingStatus?.eyeTilt ?? '–'}
                    </Typography>
                  </Box>

                  {/*
                    * Warned before it becomes a violation. The worker gives
                    * ~9s of sustained bad framing before it reports one, so
                    * a candidate who knocks their laptop out of position gets
                    * a chance to fix it rather than silently accruing one.
                    */}
                  {framingGateApplies && framingStatus && !framingStatus.ok && (
                    <Alert severity="warning" sx={{ mb: 1.1 }}>
                      {framingInstructions[0] || 'Adjust your camera position.'}
                    </Alert>
                  )}

                  {/*
                    * Surfaced rather than left to fail quietly. A suspended
                    * context means noise proctoring is switched off, and the
                    * only way it can be switched back on is a user gesture —
                    * so the candidate is the one who has to be told.
                    */}
                  {audioContextState === 'suspended' && (
                    <Alert severity="warning" sx={{ mb: 1.1 }}>
                      Microphone monitoring is paused by the browser. Click anywhere on the page to resume it.
                    </Alert>
                  )}

                  {/*
                    * Deliberately severity="error" and unconditional. A silent
                    * microphone means the noise rule is not being enforced at
                    * all, and the previous behaviour — showing a calm "quiet"
                    * — actively misled everyone reading it.
                    */}
                  {micSilent && audioContextState === 'running' && (
                    <Alert severity="error" sx={{ mb: 1.1 }}>
                      No audio is reaching the proctoring system
                      {audioDiagnostics?.label ? ` from "${audioDiagnostics.label}"` : ''}.
                      Check that your microphone is not muted in your operating system and that no
                      other application is using it.
                    </Alert>
                  )}

                  {aiWorkerError && (
                    <Alert severity="warning" sx={{ mb: 1.1 }}>
                      AI proctoring is degraded: {aiWorkerError}
                    </Alert>
                  )}

                  {/*
                    * Never swallowed. If the engine did not start, no sound
                    * near the candidate is being detected at all — and unlike
                    * the camera, there is nothing on screen that would make
                    * that obvious to anyone.
                    */}
                  {soundEngineError && (
                    <Alert severity="warning" sx={{ mb: 1.1 }}>
                      Sound monitoring is not running: {soundEngineError}
                    </Alert>
                  )}
                </Box>
              ) : (
                <Alert severity="error" sx={{ mb: 1.1 }}>Camera access denied</Alert>
              )}

              <Box sx={{ display: 'flex', gap: 0.5, mb: 1.1 }}>
                <Tooltip title="Camera">
                  <IconButton size="small" color={cameraPermission ? 'primary' : 'error'}>
                    {cameraPermission ? <VideocamIcon fontSize="small" /> : <VideocamOffIcon fontSize="small" />}
                  </IconButton>
                </Tooltip>
                <Tooltip title="Microphone">
                  <IconButton size="small" color={microphonePermission ? 'primary' : 'error'}>
                    {microphonePermission ? <MicIcon fontSize="small" /> : <MicOffIcon fontSize="small" />}
                  </IconButton>
                </Tooltip>
              </Box>

              {violationCount > 0 && (
                <Alert severity={violationCount >= 3 ? 'error' : 'warning'} sx={{ mb: 1.1 }}>
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                    <Typography variant="body2">
                      Violations: {violationCount}/3{' '}
                      {violationCount === 1 && '(1 more warning)'}
                      {violationCount === 2 && '(Final warning)'}
                    </Typography>
                  </Box>
                </Alert>
              )}

              {violations.length > 0 && (
                <Box sx={{ maxHeight: 160, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 0.75 }}>
                  {violations.map((v, idx) => (
                    <Box
                      key={idx}
                      sx={{
                        display: 'flex',
                        alignItems: 'flex-start',
                        gap: 1,
                        p: '8px 9px',
                        borderRadius: '8px',
                        background: 'rgba(224,101,101,.08)',
                        border: '1px solid rgba(224,101,101,.22)',
                      }}
                    >
                      <WarningIcon sx={{ fontSize: 13, color: '#F0A9A9', mt: '1px', flex: 'none' }} />
                      <Box sx={{ minWidth: 0 }}>
                        <Typography sx={{ fontSize: 11, fontWeight: 700, color: '#F0C9C9' }}>
                          {getViolationDetails(v).title}
                        </Typography>
                        <Typography sx={{ fontFamily: fonts.mono, fontSize: 9, color: '#DCADAD', mt: '2px' }}>
                          {new Date(v.timestamp).toLocaleTimeString()}
                        </Typography>
                      </Box>
                    </Box>
                  ))}
                </Box>
              )}
            </Box>
          </Box>
        </Box>
      </Box>

      {/* Violation Alert Dialog */}
      <Dialog open={showViolationAlert} onClose={() => setShowViolationAlert(false)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ bgcolor: 'rgba(240,180,64,.14)', color: tokens.warn, fontWeight: 700 }}>
          Proctoring Violation Detected
        </DialogTitle>
        <DialogContent sx={{ pt: 2 }}>
          {lastViolation && (() => {
            const violationInfo = getViolationDetails(lastViolation)
            return (
              <Box>
                <Typography variant="subtitle2" sx={{ mb: 1.5, fontWeight: 700, color: tokens.warn }}>
                  {violationInfo.title}
                </Typography>
                <Typography variant="body2" sx={{ mb: 2 }}>
                  {violationInfo.details}
                </Typography>
                <Box sx={{ bgcolor: 'rgba(240,180,64,.10)', p: 1.5, borderRadius: 1, mb: 2, border: '1px solid rgba(240,180,64,.34)' }}>
                  <Typography variant="caption" sx={{ display: 'block', mb: 0.5, fontWeight: 600 }}>⬛ Violation Details</Typography>
                  <Typography variant="caption" sx={{ display: 'block', color: tokens.muted, mb: 0.5 }}><strong>Type:</strong> {lastViolation.type}</Typography>
                  <Typography variant="caption" sx={{ display: 'block', color: tokens.muted, mb: 0.5 }}><strong>Time:</strong> {new Date(lastViolation.timestamp).toLocaleTimeString()}</Typography>
                  <Typography variant="caption" sx={{ display: 'block', color: tokens.muted, mb: 0.5 }}><strong>Severity:</strong> {lastViolation.severity || 'MEDIUM'}</Typography>
                  <Typography variant="caption" sx={{ display: 'block', color: tokens.muted }}><strong>Violations:</strong> {violationCount}/3</Typography>
                </Box>
                <Box sx={{ bgcolor: 'rgba(150,195,172,.06)', p: 1.5, borderRadius: 1, mb: 2, border: `1px solid ${tokens.line}` }}>
                  <Typography variant="caption" sx={{ display: 'block', mb: 0.5, fontWeight: 600 }}>✓ What you should do:</Typography>
                  <Typography variant="caption" sx={{ display: 'block', color: tokens.muted }}>
                    {violationInfo.action}
                  </Typography>
                </Box>
                {violationCount === 1 && (
                  <Alert severity="warning" sx={{ mb: 1 }}>
                    <strong>First Warning:</strong> You have 2 more violation(s) before your exam is automatically submitted.
                  </Alert>
                )}
                {violationCount === 2 && (
                  <Alert severity="error" sx={{ mb: 1 }}>
                    <strong>⚠ FINAL WARNING:</strong> This is your second violation. One more violation will immediately auto-submit your exam. Please be more careful.
                  </Alert>
                )}
              </Box>
            )
          })()}
        </DialogContent>
        <DialogActions sx={{ p: 2 }}>
          <Button onClick={() => setShowViolationAlert(false)} variant="contained" fullWidth>
            Continue Exam
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={showTerminationNotice} maxWidth="sm" fullWidth disableEscapeKeyDown>
        <DialogTitle sx={{ pb: 1.5 }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.25 }}>
            <Box
              sx={{
                flex: 'none',
                width: 34,
                height: 34,
                borderRadius: '10px',
                display: 'grid',
                placeItems: 'center',
                background: 'rgba(192,138,46,.12)',
                border: '1px solid rgba(192,138,46,.40)',
                color: tokens.copperLt,
              }}
            >
              <WarningIcon sx={{ fontSize: 18 }} />
            </Box>
            <Typography sx={{ fontSize: 18, fontWeight: 800, letterSpacing: '-.3px' }}>
              Exam Terminated After 3 Violations
            </Typography>
          </Box>
        </DialogTitle>
        <DialogContent sx={{ pt: 0 }}>
          <Box sx={{ ...cautionBoxSx, mb: 1.75 }}>
            <WarningIcon sx={{ fontSize: 16, color: tokens.copperLt, mt: '1px', flex: 'none' }} />
            <Typography sx={{ fontSize: 11.5, color: '#DCC79A', lineHeight: 1.5 }}>
              Your exam has been terminated due to repeated policy violations.
            </Typography>
          </Box>

          {violations.length > 0 && (
            <>
              <Typography sx={{ ...sectionLabelSx, mb: 1 }}>Violation log</Typography>
              <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.9, mb: 1.75 }}>
                {violations.map((v, idx) => (
                  <Box
                    key={idx}
                    sx={{
                      display: 'flex',
                      alignItems: 'flex-start',
                      gap: 1,
                      p: '8px 9px',
                      borderRadius: '8px',
                      background: 'rgba(95,174,146,.06)',
                      border: `1px solid rgba(150,195,172,.18)`,
                    }}
                  >
                    <WarningIcon sx={{ fontSize: 13, color: tokens.copperLt, mt: '1px', flex: 'none' }} />
                    <Box sx={{ minWidth: 0 }}>
                      <Typography sx={{ fontSize: 11.5, fontWeight: 700 }}>
                        {getViolationDetails(v).title}
                      </Typography>
                      <Typography sx={{ fontFamily: fonts.mono, fontSize: 9.5, color: tokens.muted, mt: '2px' }}>
                        {new Date(v.timestamp).toLocaleTimeString()}
                      </Typography>
                    </Box>
                  </Box>
                ))}
              </Box>
            </>
          )}

          <Typography sx={{ fontSize: 12.5, color: tokens.body, lineHeight: 1.8, mb: 1.5 }}>
            Enforcement actions completed in order:<br />
            1. Screen sharing was stopped.<br />
            2. Fullscreen mode was exited.<br />
            3. This attempt was closed and marked for restart.
          </Typography>
          <Typography sx={{ fontSize: 12.5, fontWeight: 700 }}>
            To continue, re-apply for the examination and complete payment again from the beginning.
          </Typography>
        </DialogContent>
        <DialogActions sx={{ p: 2 }}>
          <Button
            onClick={handleTerminationAcknowledge}
            variant="contained"
            fullWidth
            sx={{ ...ctaButton, height: 44, borderRadius: '11px', fontSize: 12, letterSpacing: '.3px', textTransform: 'none', fontWeight: 800 }}
          >
            Go to Exam Applications
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={showFullscreenRecoveryPrompt} maxWidth="xs" fullWidth disableEscapeKeyDown>
        <DialogTitle sx={{ fontWeight: 700 }}>Fullscreen Required</DialogTitle>
        <DialogContent>
          <Alert severity="warning" sx={{ mb: 2 }}>
            Fullscreen mode is required during the exam. You cannot continue outside fullscreen.
          </Alert>
          <Typography variant="body2">
            Click the button below to return to fullscreen mode and continue.
          </Typography>
        </DialogContent>
        <DialogActions sx={{ p: 2 }}>
          <Button
            variant="contained"
            fullWidth
            onClick={requestFullscreenRecovery}
          >
            Return to Fullscreen
          </Button>
        </DialogActions>
      </Dialog>

      {/* Submit Confirmation Dialog */}
      <Dialog open={showTerminateDialog} onClose={() => setShowTerminateDialog(false)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ fontSize: 18, pb: 1.25 }}>Submit Exam</DialogTitle>
        <DialogContent sx={{ pt: 0 }}>
          {submitError && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {submitError}
            </Alert>
          )}
          <Typography sx={{ fontSize: 13, color: tokens.body, lineHeight: 1.6, mb: 1.75 }}>
            Are you sure you want to submit the exam? You cannot change your answers after submission.
          </Typography>

          {!allQuestionsViewed && (
            <Box sx={{ ...cautionBoxSx, mb: 2 }}>
              <WarningIcon sx={{ fontSize: 16, color: tokens.copperLt, mt: '1px', flex: 'none' }} />
              <Typography sx={{ fontSize: 11.5, color: '#DCC79A', lineHeight: 1.5 }}>
                You have not viewed all questions ({viewedCount}/{currentSession?.questionCount || 0}).
                Consider reviewing the remaining questions before submitting.
              </Typography>
            </Box>
          )}

          <Typography sx={{ fontSize: 12, color: tokens.muted, lineHeight: 1.9 }}>
            Viewed: {viewedCount} / {currentSession?.questionCount || 0}<br />
            Attempted: {attemptedQuestionNumbers.length} / {currentSession?.questionCount || 0}
          </Typography>
          <Typography sx={{ ...sectionLabelSx, mt: 1.5, mb: 1 }}>Attempted question numbers</Typography>
          <Box sx={{ display: 'flex', gap: 0.75, flexWrap: 'wrap' }}>
            {attemptedQuestionNumbers.length > 0 ? (
              attemptedQuestionNumbers.map((num) => (
                <Box
                  key={num}
                  sx={{
                    px: 1.1,
                    py: 0.4,
                    borderRadius: '7px',
                    fontFamily: fonts.mono,
                    fontSize: 11,
                    fontWeight: 600,
                    bgcolor: 'rgba(95,174,146,.16)',
                    color: tokens.greenGlow,
                    border: `1px solid ${tokens.greenLt}`,
                  }}
                >
                  Q{num}
                </Box>
              ))
            ) : (
              <Typography sx={{ fontSize: 12, color: tokens.muted }}>No questions attempted yet.</Typography>
            )}
          </Box>
        </DialogContent>
        <DialogActions sx={{ p: 2, gap: 1 }}>
          <Button
            onClick={() => setShowTerminateDialog(false)}
            disabled={isSubmittingExam}
            sx={{ ...secondaryBtnSx, height: 40, px: 2.25, borderRadius: '10px', color: tokens.copperLt }}
          >
            Cancel
          </Button>
          <Button
            onClick={handleSubmitExam}
            variant="contained"
            disabled={isSubmittingExam}
            sx={{ ...ctaButton, width: 'auto', height: 40, px: 2.5, borderRadius: '10px', fontSize: 12, letterSpacing: '.3px', textTransform: 'none', fontWeight: 800 }}
          >
            {isSubmittingExam
              ? (submitAttempt > 1
                ? `Retrying submission (${submitAttempt}/${SUBMIT_MAX_ATTEMPTS})…`
                : 'Submitting...')
              : 'Confirm Submit'}
          </Button>
        </DialogActions>
      </Dialog>

      <Snackbar
        open={blockedActionWarning.open}
        autoHideDuration={2200}
        onClose={() => setBlockedActionWarning({ open: false, message: '' })}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert
          onClose={() => setBlockedActionWarning({ open: false, message: '' })}
          severity="warning"
          variant="filled"
          sx={{ width: '100%' }}
        >
          {blockedActionWarning.message}
        </Alert>
      </Snackbar>
    </Box>
    </>
  )
}

export default ExamPage
