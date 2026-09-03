import apiClient from './apiClient'

// Admin console API. User/violation/payment/certificate read-models live under
// /api/admin; exam and question management use the dedicated /api/exams and
// /api/questions controllers.
export const adminAPI = {
	// ----- Admin read models (/api/admin) -----
	getAllUsers: (params) => apiClient.get('/admin/users', { params }),

	getUserById: (userId) => apiClient.get(`/admin/users/${userId}`),

	setUserEnabled: (userId, enabled) =>
		apiClient.patch(`/admin/users/${userId}/${enabled ? 'enable' : 'disable'}`, {}),

	setUserLocked: (userId, locked) =>
		apiClient.patch(`/admin/users/${userId}/${locked ? 'lock' : 'unlock'}`, {}),

	getAdminQuestions: (params) => apiClient.get('/admin/questions', { params }),

	getAdminPayments: (params) => apiClient.get('/admin/payments', { params }),

	getCertificationApplications: (params) =>
		apiClient.get('/admin/certification-applications', { params }),

	getAdminCertifications: (params) => apiClient.get('/admin/certifications', { params }),

	getAdminCertificates: (params) => apiClient.get('/admin/certificates', { params }),

	getAllViolations: (params) => apiClient.get('/admin/violations', { params }),

	getSessionViolations: (sessionId) => apiClient.get(`/admin/sessions/${sessionId}/violations`),

	getAllRecordings: (params) => apiClient.get('/admin/recordings', { params }),

	getSessionRecordings: (sessionId) => apiClient.get(`/admin/sessions/${sessionId}/recordings`),

	// ----- Exam management (/api/exams) -----
	getAllExams: (params) => apiClient.get('/exams', { params }),

	createExam: (data) => apiClient.post('/exams', data),

	updateExam: (examId, data) => apiClient.put(`/exams/${examId}`, data),

	publishExam: (examId) => apiClient.post(`/exams/${examId}/publish`, {}),

	scheduleExam: (examId, data) => apiClient.post(`/exams/${examId}/schedule`, data),

	// Every exam's booking window plus how many paid candidates are waiting on
	// it. Separate from getAllExams because the counts are an aggregate over
	// every application, which the exam list has no use for.
	getBookingWindows: () => apiClient.get('/exams/booking-windows'),

	// ----- Question management (/api/questions) -----
	getAllQuestions: (params) => apiClient.get('/questions', { params }),

	createQuestion: (data) => apiClient.post('/questions', data),

	updateQuestion: (questionId, data) => apiClient.put(`/questions/${questionId}`, data),

	deleteQuestion: (questionId) => apiClient.delete(`/questions/${questionId}`),

	bulkUploadQuestions: (file) => {
		const formData = new FormData()
		formData.append('file', file)
		return apiClient.post('/questions/bulk-upload', formData, {
			headers: { 'Content-Type': 'multipart/form-data' },
		})
	},
}
