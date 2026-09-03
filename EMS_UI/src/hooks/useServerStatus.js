// ems_frontend/src/hooks/useServerStatus.js
import { useCallback, useEffect, useRef, useState } from 'react'
import { fetchSystemStatus } from '../api/systemAPI'
import { readMaintenancePayload, subscribeToServerHealth } from '../api/serverHealth'

export const SERVER_STATUS = {
  UP: 'up',
  DOWN: 'down',
  MAINTENANCE: 'maintenance',
  OFFLINE: 'offline',
}

/**
 * Build-time override for a planned window.
 *
 * Set `VITE_MAINTENANCE_MODE=true` and redeploy the frontend to hold everyone
 * on the maintenance screen regardless of what the backend says. This is the
 * switch for work the backend cannot report on because it is the thing being
 * replaced — a host migration, a DNS cutover, a release that swaps the API out
 * from under the UI. For everything else, prefer the backend's own
 * `MAINTENANCE_MODE` flag: it needs no frontend rebuild and lifts the moment
 * the variable is unset.
 */
// Written without optional chaining deliberately. Vite substitutes these at
// build time by matching the exact `import.meta.env.VITE_NAME` expression; an
// `import.meta.env?.VITE_NAME` does not match, so it falls back to replacing
// `import.meta.env` with an empty object and the flag reads `undefined` in
// every production build — an override that silently never fires.
const FORCED = import.meta.env.VITE_MAINTENANCE_MODE === 'true'
const FORCED_MESSAGE =
  import.meta.env.VITE_MAINTENANCE_MESSAGE ||
  'We are rolling out an update. The service will be back shortly.'

/**
 * How many failed probes before the screen appears.
 *
 * Not one. The API is hosted on a platform that suspends idle instances, so the
 * first request after a quiet period routinely times out while the container
 * wakes. Demanding a second failure costs a couple of seconds of delay on a real
 * outage and removes the most common false positive by far.
 */
const CONFIRM_FAILURES = 2

/** Probe spacing, indexed by consecutive failures, in milliseconds. */
const BACKOFF_MS = [2000, 5000, 10000, 20000, 30000]

const backoffFor = (failures) => BACKOFF_MS[Math.min(failures, BACKOFF_MS.length - 1)]

/**
 * Tracks whether the API is reachable, and whether it is deliberately closed.
 *
 * Two sources feed it: an explicit liveness probe, and the pass/fail of every
 * ordinary request the app already makes (relayed by apiClient through the
 * serverHealth channel). The second is what makes detection near-instant —
 * the user's own failing request raises the alarm, and the probe only confirms.
 *
 * Nothing polls while the service is healthy. Probing starts when something
 * fails and stops again once the service answers, so a working app pays no
 * background request cost for this.
 */
const useServerStatus = () => {
  const [status, setStatus] = useState(FORCED ? SERVER_STATUS.MAINTENANCE : SERVER_STATUS.UP)
  const [failures, setFailures] = useState(0)
  const [detail, setDetail] = useState({
    message: FORCED ? FORCED_MESSAGE : '',
    eta: '',
    retryAfterSeconds: 0,
    version: '',
  })
  const [isChecking, setIsChecking] = useState(false)
  const [nextProbeAt, setNextProbeAt] = useState(null)

  // Guards against overlapping probes: the retry button, the backoff timer and
  // the visibility listener can all fire within the same second.
  const probeInFlight = useRef(false)
  const mounted = useRef(true)

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  const probe = useCallback(async () => {
    if (FORCED || probeInFlight.current) {
      return
    }
    probeInFlight.current = true
    setIsChecking(true)
    setNextProbeAt(null)

    try {
      const result = await fetchSystemStatus()
      if (!mounted.current) {
        return
      }
      setFailures(0)
      setDetail({
        message: result.message,
        eta: result.eta,
        retryAfterSeconds: result.retryAfterSeconds,
        version: result.version,
      })
      setStatus(result.maintenance ? SERVER_STATUS.MAINTENANCE : SERVER_STATUS.UP)
    } catch (error) {
      if (!mounted.current) {
        return
      }
      // A 503 carrying the maintenance envelope is a successful answer to the
      // question asked — the service is up and telling us it is closed. Counting
      // it as a failure would show "cannot reach the server" over a backend that
      // just explained itself perfectly clearly.
      const maintenance = readMaintenancePayload(error)
      if (maintenance) {
        setFailures(0)
        setDetail((current) => ({ ...current, ...maintenance }))
        setStatus(SERVER_STATUS.MAINTENANCE)
        return
      }
      setFailures((count) => count + 1)
    } finally {
      probeInFlight.current = false
      if (mounted.current) {
        setIsChecking(false)
      }
    }
  }, [])

  // A device with no network cannot distinguish a dead server from its own
  // missing wifi, and blaming the server for the user's connection sends them
  // to the wrong place. `navigator.onLine` settles it without a request.
  useEffect(() => {
    if (FORCED) {
      return undefined
    }
    const handleOffline = () => setStatus(SERVER_STATUS.OFFLINE)
    const handleOnline = () => {
      // Deliberately not optimistic about the status here. Flipping to UP would
      // uncover the running application for as long as the probe takes, and if
      // the network came back to a server that is still down the user watches a
      // broken app for eight seconds before the screen returns. Staying put and
      // letting the probe decide keeps the transition to one step.
      setFailures(0)
      probe()
    }
    if (!navigator.onLine) {
      handleOffline()
    }
    window.addEventListener('offline', handleOffline)
    window.addEventListener('online', handleOnline)
    return () => {
      window.removeEventListener('offline', handleOffline)
      window.removeEventListener('online', handleOnline)
    }
  }, [probe])

  // One probe at boot. It catches a maintenance flag that was set before this
  // user arrived, and doubles as the wake-up call for a suspended instance so
  // the cold start burns down while they are still reading the sign-in screen.
  useEffect(() => {
    if (!FORCED) {
      probe()
    }
  }, [probe])

  // Relay from the axios interceptor: the app's own traffic is the fastest
  // signal available, since a user's failing request beats any polling interval.
  useEffect(() => {
    if (FORCED) {
      return undefined
    }
    return subscribeToServerHealth((event) => {
      if (event.maintenance) {
        setFailures(0)
        setDetail((current) => ({ ...current, ...(event.detail || {}) }))
        setStatus(SERVER_STATUS.MAINTENANCE)
        return
      }
      if (event.reachable) {
        // Functional updates that can return the current value let React bail
        // out of re-rendering. This runs on every successful response in the
        // app, so it has to be free when there is nothing to change.
        setFailures((count) => (count === 0 ? count : 0))
        // Only DOWN is cleared here. During planned maintenance the backend
        // deliberately keeps /api/auth/* open so administrators can sign in —
        // so a successful response is entirely compatible with the service
        // still being closed, and only a probe can lift that state.
        setStatus((current) => (current === SERVER_STATUS.DOWN ? SERVER_STATUS.UP : current))
        return
      }
      setFailures((count) => count + 1)
    })
  }, [])

  // Promote accumulated failures into the outage state.
  useEffect(() => {
    if (failures < CONFIRM_FAILURES) {
      return
    }
    setStatus((current) => {
      // A declared maintenance window outranks a connection failure: during a
      // deploy the backend goes away *because* of the work it already announced,
      // and "scheduled maintenance" stays the more truthful of the two screens
      // until a probe says otherwise.
      if (current === SERVER_STATUS.MAINTENANCE) {
        return current
      }
      // Keep OFFLINE only while the device really is offline. Once the network
      // is back, further failures belong to the server, and telling the user
      // they are offline sends them to debug a connection that is working.
      if (current === SERVER_STATUS.OFFLINE && !navigator.onLine) {
        return current
      }
      return SERVER_STATUS.DOWN
    })
  }, [failures])

  // The only polling loop, and it runs solely while something is wrong: either
  // the service is known to be down, or a request has failed and needs
  // confirming. Back to healthy and this effect schedules nothing at all.
  useEffect(() => {
    // A device with no network has nothing to poll — but the moment the network
    // returns, this has to start watching again even though the status is still
    // OFFLINE, or a reconnect onto a server that is genuinely down would leave
    // the screen stuck on the wrong explanation with no automatic way out.
    if (FORCED || (status === SERVER_STATUS.OFFLINE && !navigator.onLine)) {
      return undefined
    }
    const needsWatching = status !== SERVER_STATUS.UP || failures > 0
    if (!needsWatching) {
      return undefined
    }

    // While the backend is openly in maintenance it has told us how long to
    // wait. Honouring that keeps a room full of open tabs from hammering a
    // service that is trying to migrate a database.
    const delay =
      status === SERVER_STATUS.MAINTENANCE && detail.retryAfterSeconds > 0
        ? detail.retryAfterSeconds * 1000
        : backoffFor(failures)

    setNextProbeAt(Date.now() + delay)
    const timer = setTimeout(probe, delay)
    return () => clearTimeout(timer)
  }, [status, failures, detail.retryAfterSeconds, probe])

  // A backgrounded tab's timers are throttled to the point of uselessness, so
  // whatever it shows on return is stale. Re-probe the moment it is looked at.
  useEffect(() => {
    if (FORCED) {
      return undefined
    }
    const handleVisibility = () => {
      if (document.visibilityState === 'visible' && status !== SERVER_STATUS.UP) {
        probe()
      }
    }
    document.addEventListener('visibilitychange', handleVisibility)
    return () => document.removeEventListener('visibilitychange', handleVisibility)
  }, [status, probe])

  return {
    status,
    detail,
    isChecking,
    nextProbeAt,
    isForced: FORCED,
    /** Whether the app should be replaced by the maintenance screen. */
    isDegraded: status !== SERVER_STATUS.UP,
    retryNow: probe,
  }
}

export default useServerStatus
