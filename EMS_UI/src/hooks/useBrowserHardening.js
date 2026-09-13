import { useCallback, useEffect, useRef, useState } from 'react'

import { proctoringAPI } from '../api/proctoringAPI'
import { evidenceCaptureGuard } from '../utils/evidenceCapture'

/**
 * Network continuity + anti-recording defences.
 *
 * SCOPE, HONESTLY STATED
 * ----------------------
 * The recording defences below raise the cost of casual capture: they catch a
 * second `MediaRecorder`, an extra `getDisplayMedia` surface, and scripted
 * canvas readback from the page itself. They cannot stop an external capture
 * device (OBS on another machine, a phone pointed at the screen, a patched
 * browser), because no web API can. They are one layer behind the webcam and
 * screen-share evidence, not a replacement for it.
 */

/** Heartbeat cadence while the exam is live. */
const HEARTBEAT_INTERVAL_MS = 30000

/** Consecutive heartbeat failures tolerated before a violation is raised. */
const HEARTBEAT_FAILURE_THRESHOLD = 2

/** Grace period before a browser-reported offline event becomes a violation. */
const OFFLINE_GRACE_MS = 5000

/** How often page integrity is inspected while the exam is live. */
const INTEGRITY_POLL_MS = 1000

/** How long after a DOM change the watermark is inspected, so React's own commit has settled. */
const INTEGRITY_SETTLE_MS = 500

/** How long developer tools must stay open before it is reported. */
const DEVTOOLS_SUSTAIN_MS = 3000

/** Minimum spacing between two reports of the same tampering signal while it persists. */
const TAMPER_REPORT_COOLDOWN_MS = 30000

const WATERMARK_SELECTOR = '[data-proctor-overlay="watermark"]'

/** Whether the watermark is on the page but no longer doing its job. */
const isWatermarkHidden = (element) => {
  const style = window.getComputedStyle(element)
  return style.display === 'none'
    || style.visibility === 'hidden'
    || Number.parseFloat(style.opacity) < 0.01
    || style.backgroundImage === 'none'
}

const EXAM_CLIENT_ID_KEY = 'ems.exam.clientId'

/** Used when sessionStorage is unavailable: stable for this page load at least. */
let fallbackClientId = null

/**
 * This copy of the exam page, for the server's two-places-at-once check.
 *
 * Kept in sessionStorage, which belongs to one tab and survives a reload:
 * reloading the exam is the same copy, and a second tab or browser is another.
 */
const examClientId = () => {
  const draw = () => globalThis.crypto?.randomUUID?.()
    || `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
  try {
    let id = window.sessionStorage.getItem(EXAM_CLIENT_ID_KEY)
    if (!id) {
      id = draw()
      window.sessionStorage.setItem(EXAM_CLIENT_ID_KEY, id)
    }
    return id
  } catch {
    fallbackClientId = fallbackClientId || draw()
    return fallbackClientId
  }
}

/**
 * @param {object}   params
 * @param {boolean}  params.enabled
 * @param {number}   params.sessionId
 * @param {Function} params.onViolation
 * @param {Function} params.getOwnedStreams - returns the exam's current streams.
 *        A getter rather than a snapshot: the guard has to know what we own at
 *        the instant a MediaRecorder is constructed, and a memoised object can
 *        still be one render stale at exactly that moment.
 * @param {Function} [params.isDeveloperToolsOpen] - the exam page's own developer
 *        tools check, reused so the page's Start gate and this tamper check can
 *        never disagree about what "open" means.
 * @param {Function} [params.onSessionState] - called with each successful
 *        heartbeat payload: { strikeCount, strikeLimit, examTerminated, ... }.
 *        The heartbeat is the only channel that reports the server's view on a
 *        schedule rather than in reply to something we sent, which makes it the
 *        one place a client can find out about a strike whose write reply was
 *        lost, or catch up after a reload.
 */
const useBrowserHardening = ({
  enabled, sessionId, onViolation, getOwnedStreams, onSessionState, isDeveloperToolsOpen
}) => {
  const [networkStatus, setNetworkStatus] = useState(
    typeof navigator !== 'undefined' && navigator.onLine ? 'online' : 'offline'
  )
  const [heartbeatHealthy, setHeartbeatHealthy] = useState(true)
  const [recordingSuspected, setRecordingSuspected] = useState(false)

  const heartbeatTimerRef = useRef(null)
  const offlineTimerRef = useRef(null)
  const failureCountRef = useRef(0)
  const enabledRef = useRef(enabled)
  const onViolationRef = useRef(onViolation)
  const getOwnedStreamsRef = useRef(getOwnedStreams)
  const onSessionStateRef = useRef(onSessionState)
  const isDeveloperToolsOpenRef = useRef(isDeveloperToolsOpen)

  useEffect(() => {
    enabledRef.current = enabled
  }, [enabled])

  useEffect(() => {
    onViolationRef.current = onViolation
  }, [onViolation])

  useEffect(() => {
    onSessionStateRef.current = onSessionState
  }, [onSessionState])

  useEffect(() => {
    getOwnedStreamsRef.current = getOwnedStreams
  }, [getOwnedStreams])

  useEffect(() => {
    isDeveloperToolsOpenRef.current = isDeveloperToolsOpen
  }, [isDeveloperToolsOpen])

  const emit = useCallback((type, description, severity = 'HIGH') => {
    if (!enabledRef.current) {
      return
    }
    onViolationRef.current?.({
      type,
      description,
      severity,
      source: 'BROWSER_SECURITY',
      timestamp: new Date().toISOString()
    })
  }, [])

  /* ---------------------------------------------------------------- */
  /* Requirement 6 — network status + heartbeat                        */
  /* ---------------------------------------------------------------- */

  useEffect(() => {
    if (!enabled) {
      return undefined
    }

    const handleOnline = () => {
      setNetworkStatus('online')
      if (offlineTimerRef.current) {
        clearTimeout(offlineTimerRef.current)
        offlineTimerRef.current = null
      }
    }

    const handleOffline = () => {
      setNetworkStatus('offline')
      // A brief drop while switching APs is not misconduct; only a sustained
      // outage is worth a strike.
      if (offlineTimerRef.current) {
        clearTimeout(offlineTimerRef.current)
      }
      offlineTimerRef.current = setTimeout(() => {
        if (!navigator.onLine) {
          emit('NETWORK_LOSS', 'Network connectivity lost during the exam.', 'MEDIUM')
        }
      }, OFFLINE_GRACE_MS)
    }

    window.addEventListener('online', handleOnline)
    window.addEventListener('offline', handleOffline)

    return () => {
      window.removeEventListener('online', handleOnline)
      window.removeEventListener('offline', handleOffline)
      if (offlineTimerRef.current) {
        clearTimeout(offlineTimerRef.current)
        offlineTimerRef.current = null
      }
    }
  }, [enabled, emit])

  useEffect(() => {
    if (!enabled || !sessionId) {
      return undefined
    }

    const runHeartbeat = async () => {
      if (!enabledRef.current) {
        return
      }

      try {
        const response = await proctoringAPI.heartbeat(sessionId, examClientId())
        failureCountRef.current = 0
        setHeartbeatHealthy(true)

        /*
         * The probe was already fetching the session's violation summary purely
         * to have something cheap to call; handing that answer on costs nothing
         * and is what closes the gap left by a lost write reply. Guarded because
         * the heartbeat's job is liveness — a consumer that throws must not be
         * able to mark a healthy connection as failed.
         */
        const sessionState = response?.data?.data
        if (sessionState) {
          try {
            onSessionStateRef.current?.(sessionState)
          } catch (consumerError) {
            console.error('Heartbeat session-state consumer failed:', consumerError)
          }
        }
      } catch (error) {
        // A 401/403 means the session is gone server-side, which is a different
        // problem from a flaky link; both are surfaced, only the latter retries.
        const status = error?.response?.status
        failureCountRef.current += 1

        if (failureCountRef.current >= HEARTBEAT_FAILURE_THRESHOLD) {
          setHeartbeatHealthy(false)
          emit(
            'NETWORK_LOSS',
            status
              ? `Exam session heartbeat failed with status ${status}.`
              : 'Exam session heartbeat failed; the server is unreachable.',
            'MEDIUM'
          )
          // Reset so a long outage produces periodic signals rather than one
          // violation per heartbeat from here on.
          failureCountRef.current = 0
        }
      }
    }

    /*
     * Once immediately, then on the interval. Without the first call a client
     * that has just been reloaded mid-attempt spends its opening 30 seconds
     * believing it has no strikes, which is the window in which a candidate
     * whose exam was terminated while the tab was gone would otherwise be
     * allowed to carry on answering.
     */
    runHeartbeat()
    heartbeatTimerRef.current = setInterval(runHeartbeat, HEARTBEAT_INTERVAL_MS)

    return () => {
      if (heartbeatTimerRef.current) {
        clearInterval(heartbeatTimerRef.current)
        heartbeatTimerRef.current = null
      }
    }
  }, [enabled, sessionId, emit])

  /* ---------------------------------------------------------------- */
  /* Requirement 7 — concurrent recorders and canvas readback          */
  /* ---------------------------------------------------------------- */

  useEffect(() => {
    if (!enabled || typeof window === 'undefined') {
      return undefined
    }

    const originalMediaRecorder = window.MediaRecorder
    const originalGetDisplayMedia = navigator.mediaDevices?.getDisplayMedia
    const originalToDataURL = HTMLCanvasElement.prototype.toDataURL

    /** True when the stream is one the exam itself created. */
    const isOwnedStream = (stream) => {
      if (!stream) {
        return false
      }
      try {
        const owned = getOwnedStreamsRef.current?.() || {}
        return Object.values(owned).some((candidate) => candidate && candidate.id === stream.id)
      } catch (error) {
        // Fail open: wrongly accusing a candidate is worse than missing a
        // recorder we already have webcam and screen-share evidence for.
        console.warn('Owned-stream lookup failed; treating recorder as owned:', error)
        return true
      }
    }

    // --- Concurrent MediaRecorder ------------------------------------
    if (typeof originalMediaRecorder === 'function') {
      class GuardedMediaRecorder extends originalMediaRecorder {
        constructor(stream, options) {
          super(stream, options)

          if (!isOwnedStream(stream)) {
            setRecordingSuspected(true)
            emit(
              'SCREEN_RECORDING_SUSPECTED',
              'A media recorder was started on a stream the exam does not own.',
              'HIGH'
            )
          }
        }
      }

      // Preserve the static surface (isTypeSupported) that callers rely on.
      GuardedMediaRecorder.isTypeSupported = originalMediaRecorder.isTypeSupported?.bind(originalMediaRecorder)
      window.MediaRecorder = GuardedMediaRecorder
    }

    // --- Extra display-capture surfaces -------------------------------
    if (typeof originalGetDisplayMedia === 'function') {
      let displayMediaGrants = 0

      navigator.mediaDevices.getDisplayMedia = async function guardedGetDisplayMedia(constraints) {
        const stream = await originalGetDisplayMedia.call(navigator.mediaDevices, constraints)
        displayMediaGrants += 1

        // The exam requests exactly one display surface. Anything beyond that
        // is a second capture the candidate initiated.
        if (displayMediaGrants > 1) {
          setRecordingSuspected(true)
          emit(
            'SCREEN_RECORDING_SUSPECTED',
            'An additional screen-capture surface was opened during the exam.',
            'HIGH'
          )
        }
        return stream
      }
    }

    // --- Scripted canvas readback -------------------------------------
    // Blocks page-context scraping of exam content into an image. Our own
    // evidence capture opts out via a data attribute.
    HTMLCanvasElement.prototype.toDataURL = function guardedToDataURL(...args) {
      // Our own evidence capture runs through this same call — including
      // react-webcam's internal canvas, which we cannot tag. Without this the
      // snapshot taken for violation #1 would itself raise violations #2 and #3.
      if (evidenceCaptureGuard.active) {
        return originalToDataURL.apply(this, args)
      }

      if (this.dataset && this.dataset.proctorEvidence === 'true') {
        return originalToDataURL.apply(this, args)
      }

      if (this.width > 200 && this.height > 200) {
        emit('SCREEN_RECORDING_SUSPECTED', 'Canvas readback of exam content was attempted.', 'HIGH')
        setRecordingSuspected(true)
      }
      return originalToDataURL.apply(this, args)
    }

    return () => {
      // Always restore the originals; leaving patched globals behind would
      // follow the user out of the exam and into the rest of the app.
      if (typeof originalMediaRecorder === 'function') {
        window.MediaRecorder = originalMediaRecorder
      }
      if (typeof originalGetDisplayMedia === 'function') {
        navigator.mediaDevices.getDisplayMedia = originalGetDisplayMedia
      }
      HTMLCanvasElement.prototype.toDataURL = originalToDataURL
    }
  }, [enabled, emit])

  /* ---------------------------------------------------------------- */
  /* Requirement 8 — page integrity                                    */
  /* ---------------------------------------------------------------- */

  /*
   * Two signs the exam page itself is being interfered with, reported as
   * SESSION_TAMPERING: the proctoring watermark removed or hidden, and developer
   * tools open during the attempt. Neither happens to a candidate using the page
   * as delivered.
   */
  useEffect(() => {
    if (!enabled || typeof window === 'undefined' || typeof MutationObserver === 'undefined') {
      return undefined
    }

    const lastReportedAt = { watermark: 0, devtools: 0 }
    const reportTampering = (signal, description) => {
      const now = Date.now()
      if (now - lastReportedAt[signal] < TAMPER_REPORT_COOLDOWN_MS) {
        return
      }
      lastReportedAt[signal] = now
      emit('SESSION_TAMPERING', description, 'HIGH')
    }

    /*
     * The exam is marked live a moment before the exam screen — and the
     * watermark with it — has rendered. So an absent watermark only counts once
     * it has been seen on the page; before that it simply has not arrived. A
     * watermark hidden from the start is still caught, because hiding it needs it
     * to be there.
     */
    let watermarkMounted = false
    let settleTimer = null
    const inspectWatermark = () => {
      settleTimer = null
      if (!enabledRef.current) {
        return
      }
      const watermark = document.querySelector(WATERMARK_SELECTOR)
      if (!watermark) {
        if (watermarkMounted) {
          reportTampering('watermark', 'The proctoring watermark was removed from the exam page.')
        }
        return
      }
      watermarkMounted = true
      if (isWatermarkHidden(watermark)) {
        reportTampering('watermark', 'The proctoring watermark was hidden on the exam page.')
      }
    }
    // Settled rather than immediate, so React replacing the page at the end of the
    // exam is disconnected below before it can be mistaken for a removal.
    const scheduleWatermarkInspection = () => {
      if (settleTimer === null) {
        settleTimer = setTimeout(inspectWatermark, INTEGRITY_SETTLE_MS)
      }
    }

    const observer = new MutationObserver(scheduleWatermarkInspection)
    observer.observe(document.body, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['style', 'class', 'hidden', 'data-proctor-overlay']
    })

    /*
     * Developer tools, by the same window measurement that already blocks Start.
     * Browser zoom moves that measurement too, but changes the pixel ratio along
     * with it; while the ratio differs from the one the exam began with, the
     * reading is not trusted. Failing that way can only miss a report.
     */
    const baselinePixelRatio = window.devicePixelRatio
    let devtoolsOpenSince = null
    const inspectDevtools = () => {
      const isOpen = isDeveloperToolsOpenRef.current
      if (!enabledRef.current || typeof isOpen !== 'function'
        || window.devicePixelRatio !== baselinePixelRatio || !isOpen()) {
        devtoolsOpenSince = null
        return
      }
      devtoolsOpenSince = devtoolsOpenSince ?? Date.now()
      if (Date.now() - devtoolsOpenSince >= DEVTOOLS_SUSTAIN_MS) {
        reportTampering('devtools', 'Developer tools were open during the exam.')
      }
    }

    // The timer also re-inspects the watermark, which catches a stylesheet
    // injected to hide it without touching the element itself.
    const integrityTimer = setInterval(() => {
      inspectDevtools()
      scheduleWatermarkInspection()
    }, INTEGRITY_POLL_MS)

    return () => {
      observer.disconnect()
      clearInterval(integrityTimer)
      if (settleTimer !== null) {
        clearTimeout(settleTimer)
      }
    }
  }, [enabled, emit])

  return {
    networkStatus,
    heartbeatHealthy,
    recordingSuspected
  }
}

export default useBrowserHardening
