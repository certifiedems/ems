import apiClient from './apiClient'

export const proctoringAPI = {
  reportViolation: (sessionId, data) =>
    apiClient.post(`/proctoring/sessions/${sessionId}/violations`, data),

  getSessionViolations: (sessionId) =>
    apiClient.get(`/proctoring/sessions/${sessionId}/violations`),

  getViolationSummary: (sessionId) =>
    apiClient.get(`/proctoring/sessions/${sessionId}/violations/summary`),

  /**
   * Logs an AI/browser-security violation and returns the authoritative strike state.
   *
   * Payload: { examId, studentId, violationType, evidenceImage, description, confidence }
   * Response data: { strikeCount, isTerminated, strikesRemaining, ... }
   *
   * The evidence frame can push this body past 100KB, so it gets a longer
   * timeout than the default read APIs.
   */
  logViolation: (payload) =>
    apiClient.post('/proctor/log-violation', payload, { timeout: 20000 }),

  /**
   * The exam page checking in. Returns the same state as the violation summary,
   * and lets the server notice the attempt open in two places at once:
   * `clientId` identifies this copy of the exam page, one per browser tab.
   */
  heartbeat: (sessionId, clientId) =>
    apiClient.post(`/proctoring/sessions/${sessionId}/heartbeat`, { clientId }, { timeout: 8000 }),

  /**
   * The proctoring rules for an application's exam: which detections to raise,
   * the sound thresholds and the strike limit. For an attempt already in
   * progress, the rules it started under.
   */
  getPolicyForApplication: (applicationId) =>
    apiClient.get(`/proctoring/policy/applications/${applicationId}`)
}
