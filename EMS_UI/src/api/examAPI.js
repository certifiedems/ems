import apiClient from './apiClient'

export const examAPI = {
	getWorkflowOptions: (level) => apiClient.get(`/exam-workflow/options/${level}`),

	// How many attempts a payment made now covers at a level, shown before paying.
	getAttemptAllowance: (level) => apiClient.get(`/exam-workflow/attempt-allowance/${level}`),

	applyForExam: (data) => apiClient.post('/exam-workflow/applications', data),

	initiatePayment: (applicationId, data) =>
		apiClient.post(`/exam-workflow/applications/${applicationId}/payments/initiate`, data),

	completePayment: (applicationId, data) =>
		apiClient.post(`/exam-workflow/applications/${applicationId}/payments/complete`, data),

	scheduleExam: (applicationId, data) =>
		apiClient.post(`/exam-workflow/applications/${applicationId}/schedule`, data),

	// The slots an application's exam can be booked into between `from` and `to`
	// (the candidate's local day, as ISO instants), with the seats left in each.
	getExamSlots: (applicationId, params) =>
		apiClient.get(`/exam-workflow/applications/${applicationId}/slots`, { params }),

	startExam: (applicationId, data) =>
		apiClient.post(`/exam-workflow/applications/${applicationId}/start`, data || {}),

	reApply: (applicationId) => apiClient.post(`/exam-workflow/applications/${applicationId}/re-apply`),

	getReApplyableApplications: () => apiClient.get('/exam-workflow/re-applyable'),

	getSessionQuestion: (sessionToken, questionNumber) =>
		apiClient.get(`/exam-workflow/sessions/${sessionToken}/questions/${questionNumber}`),

	// Autosave of the answers given so far. Sent on a timer and on navigation so
	// an attempt cut short by a dropped connection, a flat battery or a closed
	// laptop can be rejoined with its work intact. The complete answer set goes
	// on every call — see saveProgress on the server for why it is not a delta.
	// A short timeout on purpose: this is a background call, and one that hangs
	// on a dying network must give way to the next attempt rather than block it.
	saveExamProgress: (sessionToken, data) =>
		apiClient.post(`/exam-workflow/sessions/${sessionToken}/progress`, data, { timeout: 8000 }),

	getExamProgress: (sessionToken) =>
		apiClient.get(`/exam-workflow/sessions/${sessionToken}/progress`),

	// Submission & result use the numeric exam session id (examSessionId from startExam).
	// Use /submit for compatibility with backend deployments where /evaluate alias is unavailable.
	submitExam: (examSessionId, data) => apiClient.post(`/results/sessions/${examSessionId}/submit`, data),

	getExamResult: (examSessionId) => apiClient.get(`/results/sessions/${examSessionId}`),

	getMyResults: () => apiClient.get('/results/me'),

	// Question bank (admin)
	searchQuestions: (params) => apiClient.get('/questions', { params }),

	getQuestion: (id) => apiClient.get(`/questions/${id}`),
}
