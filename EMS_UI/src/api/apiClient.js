import axios from 'axios'
import store from '../store/store'
import { setToken, logout } from '../store/slices/authSlice'
import { isOutageError, readMaintenancePayload, reportServerHealth } from './serverHealth'

// In local dev, keep requests relative so Vite proxy forwards to :8080 and
// prevents browser-side CORS errors.
const isLocalDev = import.meta.env.DEV && !import.meta.env.PROD
// const API_BASE_URL = isLocalDev
//   ? ''
//   : (import.meta.env?.VITE_API_URL || 'http://localhost:8080')
// ==> Available at your primary URL https://ems-1ze5.onrender.com
// const API_BASE_URL = 'https://ems-1ze5.onrender.com'
const API_BASE_URL = 'http://localhost:8080'

// Exported so the liveness probe in systemAPI can address the same host without
// going through this client's auth interceptors.
export { API_BASE_URL }

// Without this, axios' default is 0 (no timeout): a request against a dead
// network can hang indefinitely instead of rejecting, which leaves any
// isLoading flag driven by it stuck true. 20s comfortably covers a slow
// connection while still failing a truly unreachable one in bounded time.
// Endpoints that need more (e.g. large evidence uploads) pass their own
// per-request `timeout` to override this.
const REQUEST_TIMEOUT_MS = 20000

const apiClient = axios.create({
	baseURL: `${API_BASE_URL}/api`,
	headers: {
		'Content-Type': 'application/json',
	},
	timeout: REQUEST_TIMEOUT_MS,
})

// Request interceptor to add token
apiClient.interceptors.request.use(
	(config) => {
		const state = store.getState()
		const token = state.auth.token
		if (token) {
			config.headers.Authorization = `Bearer ${token}`
		}
		return config
	},
	(error) => {
		return Promise.reject(error)
	}
)

// Endpoints that establish a session rather than consume one. A 401 from any of
// them is the answer, not a stale-token symptom: refreshing and replaying a
// rejected login only replaces "Invalid credentials" with a refresh failure —
// and, because that path dispatches logout, it does so while wiping state out
// from under a user who was only trying to sign back in.
// Logout belongs here for the mirror-image reason: it is the one call whose
// whole purpose is to invalidate the session. Refreshing and replaying it would
// mint a new token pair on the way out, leaving the user "logged out" holding
// credentials newer than the ones they started with.
const SESSION_ENDPOINTS = ['/auth/login', '/auth/register', '/auth/refresh-token', '/auth/logout']

const isSessionEndpoint = (url = '') => SESSION_ENDPOINTS.some((path) => url.includes(path))

// Response interceptor to handle token refresh
apiClient.interceptors.response.use(
	(response) => {
		// Every answered request is evidence the service is alive. This is what
		// clears a maintenance screen raised by a false alarm without waiting for
		// the next scheduled probe.
		reportServerHealth({ reachable: true })
		return response
	},
	async (error) => {
		const originalRequest = error.config
		const state = store.getState()

		// Report before the refresh logic below, not after: that branch can await
		// a network call of its own, and an outage the user is staring at should
		// not wait on it to reach the screen.
		const maintenance = readMaintenancePayload(error)
		if (maintenance) {
			reportServerHealth({ reachable: false, maintenance: true, detail: maintenance })
		} else if (isOutageError(error)) {
			reportServerHealth({ reachable: false, maintenance: false })
		}

		if (
			error.response?.status === 401 &&
			!originalRequest._retry &&
			!isSessionEndpoint(originalRequest?.url) &&
			state.auth.refreshToken
		) {
			// Marked BEFORE the retry, not after it: the replayed request re-enters
			// this same interceptor, so without the flag a 401 that a fresh token
			// cannot fix (an authorisation problem, or a server-side bug answering
			// 401 regardless of credentials) refreshes and replays forever. On a
			// non-idempotent endpoint that is not just a loop — every replay is
			// another write. It let one detected violation record repeated strikes
			// against a candidate until the exam terminated itself.
			originalRequest._retry = true
			try {
				// Raw axios, so apiClient's timeout does not apply — it has to be
				// passed explicitly. Without it this call inherits axios' default of
				// 0 (wait forever): against a backend that accepts the connection and
				// never answers, the awaited promise never settles, the original
				// request never rejects, and whatever isLoading flag it drives stays
				// true with no error to clear it. That is the one remaining path that
				// can strand the UI badly enough to look like corrupt storage.
				const refreshResponse = await axios.post(
					`${API_BASE_URL}/api/auth/refresh-token`,
					{ refreshToken: state.auth.refreshToken },
					{ timeout: REQUEST_TIMEOUT_MS },
				)
				const data = refreshResponse.data.data
				const token = data.accessToken || data.token
				const refreshToken = data.refreshToken
				store.dispatch(setToken({ token, refreshToken }))
				originalRequest.headers.Authorization = `Bearer ${token}`
				return apiClient(originalRequest)
			} catch (refreshError) {
				store.dispatch(logout())
				return Promise.reject(refreshError)
			}
		}

		return Promise.reject(error)
	}
)

export default apiClient
