import axios from 'axios'
import apiClient, { API_BASE_URL } from './apiClient'

export const testimonialAPI = {
	// { eligibleLevels: ['L1', ...], testimonials: [...] }: the levels the
	// candidate can still write a testimonial for, and the ones they have written.
	getMine: () => apiClient.get('/testimonials/me'),

	submit: (data) => apiClient.post('/testimonials', data),

	// Deletes the testimonial outright; it leaves the public site immediately.
	withdraw: (testimonialId) => apiClient.delete(`/testimonials/${testimonialId}`),

	// Approved quotes for the sign-in screen. Deliberately not sent through
	// apiClient: its interceptors report an unreachable server, which switches
	// the whole app to the maintenance screen. A decorative quote failing to
	// load must never do that, least of all during a cold start on the sign-in
	// page. No token is needed either.
	getPublished: () => axios.get(`${API_BASE_URL}/api/testimonials/public`, { timeout: 15000 }),
}
