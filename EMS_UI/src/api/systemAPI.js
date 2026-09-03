// ems_frontend/src/api/systemAPI.js
import axios from 'axios'
import { API_BASE_URL } from './apiClient'

/**
 * Shorter than the app-wide 20s: this call's only job is to answer "is anything
 * there?", and the maintenance screen needs that answer fast enough to show a
 * live retry countdown. A backend that has not responded in 8s is, for the
 * purpose of this question, down — and the probe simply runs again.
 */
const PROBE_TIMEOUT_MS = 8000

/**
 * Asks the backend whether it is up, and whether it is deliberately closed.
 *
 * Uses bare axios rather than the shared apiClient on purpose. apiClient
 * attaches a bearer token and, on a 401, tries to refresh it and dispatches a
 * logout when that fails — so probing through it would sign users out every
 * time the backend was unreachable, which is precisely when this runs.
 */
export const fetchSystemStatus = async ({ signal } = {}) => {
  const response = await axios.get(`${API_BASE_URL}/api/system/status`, {
    timeout: PROBE_TIMEOUT_MS,
    signal,
    // The probe's value is knowing the state *now*; a cached 200 from before
    // the outage would report health that no longer exists.
    headers: { 'Cache-Control': 'no-cache' },
  })

  // Unwrap the standard ApiResponse envelope, tolerating a bare body so the
  // probe still works against a proxy or a future endpoint that does not use it.
  const payload = response.data?.data ?? response.data ?? {}

  return {
    maintenance: Boolean(payload.maintenance),
    message: payload.message || '',
    eta: payload.eta || '',
    retryAfterSeconds: Number(payload.retryAfterSeconds) || 0,
    version: payload.version || '',
  }
}

export default { fetchSystemStatus }
