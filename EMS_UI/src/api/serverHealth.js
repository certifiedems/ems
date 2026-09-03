// ems_frontend/src/api/serverHealth.js
//
// A tiny publish/subscribe channel between the axios layer and the maintenance
// screen.
//
// It exists to break a cycle: apiClient is the first place that learns the API
// has stopped answering, but it cannot import the React hook that renders that
// fact, because the hook imports the API layer. Both sides talk to this module
// instead, which imports nothing.

const listeners = new Set()

/**
 * Statuses that mean "the service, not this request, is the problem".
 *
 * 502/504 are a proxy in front of a backend that is down or restarting — the
 * shape of a deploy in progress. 503 is the backend explicitly refusing, which
 * is what MaintenanceGateFilter answers while planned work is running.
 *
 * Everything else is deliberately excluded. A 500 is one endpoint failing, a
 * 401 is a session problem, a 404 is a bad path: none of them are reasons to
 * throw a full-screen outage over a working application.
 */
const OUTAGE_STATUSES = new Set([502, 503, 504])

/** Axios error codes that mean the request never reached a server at all. */
const OUTAGE_CODES = new Set(['ERR_NETWORK', 'ECONNABORTED', 'ETIMEDOUT', 'ERR_CONNECTION_REFUSED'])

/**
 * Whether an axios error should be read as the service being unreachable.
 *
 * A cancelled request is explicitly not an outage — the exam screen aborts
 * in-flight calls on unmount, and counting those would flash a maintenance
 * screen every time a candidate navigated away.
 */
export const isOutageError = (error) => {
  if (!error || error.code === 'ERR_CANCELED' || error.name === 'CanceledError') {
    return false
  }
  const status = error.response?.status
  if (status) {
    return OUTAGE_STATUSES.has(status)
  }
  // No response object: DNS failure, refused connection, a CORS-blocked reply,
  // or our own 20s timeout firing. `ERR_NETWORK` covers most of these, but
  // browsers are inconsistent enough about the code that an absent response
  // with no recognised code is still treated as unreachable.
  return !error.code || OUTAGE_CODES.has(error.code)
}

/**
 * Reads the maintenance envelope out of a 503, if the backend sent one.
 * Returns null for any other failure, including a 503 from an intermediate
 * proxy that knows nothing about this application.
 */
export const readMaintenancePayload = (error) => {
  const body = error?.response?.data
  if (error?.response?.status !== 503 || !body || body.status !== 'MAINTENANCE') {
    return null
  }
  return {
    message: body.message || '',
    eta: body.eta || '',
    retryAfterSeconds: Number(body.retryAfterSeconds) || 0,
  }
}

/** Subscribe to health events. Returns an unsubscribe function. */
export const subscribeToServerHealth = (listener) => {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/**
 * Broadcast what a request just revealed about the service.
 *
 * `reachable: true` is sent on every success, which is how the app recovers
 * from a false alarm without waiting for the next scheduled probe.
 */
export const reportServerHealth = (event) => {
  listeners.forEach((listener) => {
    try {
      listener(event)
    } catch {
      // A broken subscriber must not turn into a failed API response — the
      // caller of this is an axios interceptor sitting in every request path.
    }
  })
}
