const BASE = '/api'

async function handle(res) {
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw new Error(body.message || `Request failed (${res.status})`)
  }
  return res.json()
}

export const api = {
  // Fetches a lightweight arithmetic verification challenge that must be
  // solved and submitted along with /queue/join.
  // Backend: GET queue-service /api/queue/captcha
  getCaptcha() {
    return fetch(`${BASE}/queue/captcha`).then(handle)
  },

  // Joins the virtual waiting room for a given exam/portal event.
  // Backend: POST queue-service /api/queue/join
  joinQueue(examId, rollNumber, captchaId, captchaAnswer, website = '') {
    return fetch(`${BASE}/queue/join`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ examId, rollNumber, captchaId, captchaAnswer, website })
    }).then(handle)
  },

  // Polling fallback if WebSocket is unavailable (e.g. corporate proxies).
  getQueueStatus(queueId) {
    return fetch(`${BASE}/queue/status/${queueId}`).then(handle)
  },

  // Backend: GET result-service /api/results/{rollNumber}
  // `admissionTicket` must be the signed ticket returned once the queue
  // snapshot reports admitted:true. Sent via the Authorization header
  // (not a query string) so it doesn't end up in browser history, proxy
  // access logs, or a stray Referer header.
  getResult(rollNumber, admissionTicket) {
    return fetch(`${BASE}/results/${rollNumber}`, {
      headers: { Authorization: `Bearer ${admissionTicket || ''}` }
    }).then(handle)
  },

  // Public system health, shown as the header status pill.
  getSystemStatus() {
    return fetch(`${BASE}/status/health`).then(handle)
  },

  // Aggregated-only public figure for the live stats ticker.
  getLiveCount() {
    return fetch(`${BASE}/status/live-count`).then(handle)
  },

  // Best-effort — reports an FAQ search that returned nothing, for gap detection.
  reportFaqMiss(query) {
    return fetch(`${BASE}/status/faq-miss`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query })
    }).then(handle)
  },

  // Post-result feedback (thumbs up/down + optional comment).
  submitFeedback(rollNumber, helpful, comment) {
    return fetch(`${BASE}/results/feedback`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ rollNumber, helpful, comment })
    }).then(handle)
  },

  // Backend: POST result-service /api/grievances
  raiseGrievance(rollNumber, examId, category, message, webhookUrl) {
    return fetch(`${BASE}/grievances`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ rollNumber, examId, category, message, webhookUrl: webhookUrl || undefined })
    }).then(handle)
  },

  // Backend: GET result-service /api/grievances/{ticketRef}
  getGrievance(ticketRef) {
    return fetch(`${BASE}/grievances/${encodeURIComponent(ticketRef)}`).then(handle)
  },

  // Self-serve export of a candidate's own grievance history.
  exportGrievances(rollNumber) {
    return fetch(`${BASE}/grievances/export?rollNumber=${encodeURIComponent(rollNumber)}`).then(handle)
  },

  // ---- Admin (requires X-Admin-Key) ----
  adminGetQueues(adminKey) {
    return fetch(`${BASE}/admin/queues`, { headers: { 'X-Admin-Key': adminKey } }).then(handle)
  },

  adminSetAdmitRate(adminKey, admitPerTick) {
    return fetch(`${BASE}/admin/admit-rate`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', 'X-Admin-Key': adminKey },
      body: JSON.stringify({ admitPerTick })
    }).then(handle)
  },

  adminGetAuditLog(adminKey) {
    return fetch(`${BASE}/admin/audit-log`, { headers: { 'X-Admin-Key': adminKey } }).then(handle)
  },

  adminGetFaqGaps(adminKey) {
    return fetch(`${BASE}/admin/faq-gaps`, { headers: { 'X-Admin-Key': adminKey } }).then(handle)
  },

  adminGetClusters(adminKey) {
    return fetch(`${BASE}/grievances/admin/clusters`, { headers: { 'X-Admin-Key': adminKey } }).then(handle)
  },

  adminGetEnumerationAlerts(adminKey) {
    return fetch(`${BASE}/admin/enumeration-alerts`, { headers: { 'X-Admin-Key': adminKey } }).then(handle)
  },

  adminGetAnomalies(adminKey) {
    return fetch(`${BASE}/admin/anomalies`, { headers: { 'X-Admin-Key': adminKey } }).then(handle)
  },

  adminListGrievances(adminKey, status, query) {
    const q = query ? `&q=${encodeURIComponent(query)}` : ''
    return fetch(`${BASE}/grievances/admin?status=${encodeURIComponent(status)}${q}`, {
      headers: { 'X-Admin-Key': adminKey }
    }).then(handle)
  },

  adminGetDraftSuggestion(adminKey, category) {
    return fetch(`${BASE}/grievances/admin/draft-suggestion?category=${encodeURIComponent(category)}`, {
      headers: { 'X-Admin-Key': adminKey }
    }).then(handle)
  },

  adminUpdateGrievance(adminKey, ticketRef, status, adminNote) {
    return fetch(`${BASE}/grievances/admin/${encodeURIComponent(ticketRef)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', 'X-Admin-Key': adminKey },
      body: JSON.stringify({ status, adminNote })
    }).then(handle)
  },

  adminBulkUpdateGrievances(adminKey, ticketRefs, status, adminNote) {
    return fetch(`${BASE}/grievances/admin/bulk`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', 'X-Admin-Key': adminKey },
      body: JSON.stringify({ ticketRefs, update: { status, adminNote } })
    }).then(handle)
  },

  // Returns a raw CSV string, not JSON — caller triggers a download.
  async adminExportGrievancesCsv(adminKey, status) {
    const res = await fetch(`${BASE}/grievances/admin/export?status=${encodeURIComponent(status)}`, {
      headers: { 'X-Admin-Key': adminKey }
    })
    if (!res.ok) throw new Error(`Export failed (${res.status})`)
    return res.text()
  },

  // Returns a PDF blob for direct download — same ticket gate as getResult.
  async getAdmitCard(rollNumber, admissionTicket) {
    const res = await fetch(`${BASE}/admit-card/${rollNumber}`, {
      headers: { Authorization: `Bearer ${admissionTicket || ''}` }
    })
    if (!res.ok) {
      const body = await res.json().catch(() => ({}))
      throw new Error(body.message || `Could not fetch admit card (${res.status})`)
    }
    return res.blob()
  },

  // Public — see ApplicationStatusController for why this isn't ticket-gated.
  getApplicationTimeline(rollNumber, examId) {
    return fetch(`${BASE}/applications/${rollNumber}/timeline?examId=${encodeURIComponent(examId)}`).then(handle)
  }
}
