import React, { useEffect, useState } from 'react'
import Modal from './Modal.jsx'
import { api } from '../services/api.js'
import TicketQrCode from './TicketQrCode.jsx'

const CATEGORIES = [
  ['MARKS_DISCREPANCY', 'Marks look incorrect'],
  ['NAME_CORRECTION', 'Name spelling is wrong'],
  ['CERTIFICATE_ISSUE', 'Certificate / document issue'],
  ['OTHER', 'Something else'],
]

// Rule-based starting templates, not an LLM call — a real "smart draft"
// would need a hosted model + API key routed through a backend endpoint
// (see README's AI integration points). This just saves a blank-page
// moment and nudges toward the details staff actually need.
const DRAFT_TEMPLATES = {
  MARKS_DISCREPANCY: 'My score shows ___ but I believe it should be ___ because ___. I am attaching/referencing ___ as evidence.',
  NAME_CORRECTION: 'My name is printed as "___" but should read "___" as per my ID proof (___).',
  CERTIFICATE_ISSUE: 'I am unable to ___ (download/print/view) my certificate. The issue started when ___.',
  OTHER: '',
}

// Keyword hints for auto-categorization when someone picks "Other" — a
// lightweight rule-based nudge, not a trained classifier, that suggests
// a better-fitting category as they type instead of everything vague
// landing in one bucket.
const CATEGORY_KEYWORDS = {
  MARKS_DISCREPANCY: ['marks', 'score', 'percentage', 'grade', 'total', 'incorrect mark'],
  NAME_CORRECTION: ['name', 'spelling', 'spelt', 'misspell', 'surname'],
  CERTIFICATE_ISSUE: ['certificate', 'download', 'pdf', 'print', 'marksheet'],
}

function suggestCategory(message) {
  const lower = message.toLowerCase()
  for (const [category, keywords] of Object.entries(CATEGORY_KEYWORDS)) {
    if (keywords.some((k) => lower.includes(k))) return category
  }
  return null
}

// A short, non-exhaustive keyword list — not real moderation, just a
// cheap client-side nudge before submission. Real content moderation
// belongs server-side (see README) and would need a proper classifier
// or hosted moderation API, not a hardcoded word list in the frontend.
const FLAGGED_TERMS = ['idiot', 'stupid', 'useless', 'scam']

function useDictation(onResult) {
  const [listening, setListening] = useState(false)
  const supported = typeof window !== 'undefined' && (window.SpeechRecognition || window.webkitSpeechRecognition)

  function toggle() {
    if (!supported) return
    if (listening) {
      setListening(false)
      return
    }
    const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition
    const recognition = new Recognition()
    recognition.lang = 'en-IN'
    recognition.interimResults = false
    recognition.onresult = (event) => {
      const transcript = event.results[0][0].transcript
      onResult(transcript)
    }
    recognition.onend = () => setListening(false)
    recognition.onerror = () => setListening(false)
    recognition.start()
    setListening(true)
  }

  return { supported, listening, toggle }
}

export default function GrievanceModal({ onClose, defaultRollNumber, defaultExamId, initialTab = 'raise', initialTrackRef = '' }) {
  const [tab, setTab] = useState(initialTab) // 'raise' | 'track'

  // Export state
  const [exportRoll, setExportRoll] = useState(defaultRollNumber || '')
  const [exportError, setExportError] = useState('')
  const [exporting, setExporting] = useState(false)

  async function handleExport(e) {
    e.preventDefault()
    setExportError('')
    if (!exportRoll.trim()) {
      setExportError('Enter your roll number to export your grievance history.')
      return
    }
    setExporting(true)
    try {
      const history = await api.exportGrievances(exportRoll.trim())
      const blob = new Blob([JSON.stringify(history, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `sarthi-grievances-${exportRoll.trim()}.json`
      a.click()
      URL.revokeObjectURL(url)
    } catch (err) {
      setExportError(err.message || 'Could not export your data.')
    } finally {
      setExporting(false)
    }
  }

  // Raise-a-ticket state
  const [rollNumber, setRollNumber] = useState(defaultRollNumber || '')
  const [examId, setExamId] = useState(defaultExamId || '')
  const [category, setCategory] = useState(CATEGORIES[0][0])
  const [message, setMessage] = useState('')
  const [webhookUrl, setWebhookUrl] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState('')
  const [ticket, setTicket] = useState(null)

  const dictation = useDictation((transcript) => setMessage((prev) => (prev ? prev + ' ' + transcript : transcript)))

  function applyTemplate() {
    const template = DRAFT_TEMPLATES[category]
    if (template) setMessage(template)
  }

  const flaggedTerm = FLAGGED_TERMS.find((term) => message.toLowerCase().includes(term))
  const suggestedCategory = category === 'OTHER' ? suggestCategory(message) : null

  // Track-a-ticket state
  const [trackRef, setTrackRef] = useState(initialTrackRef)
  const [tracked, setTracked] = useState(null)
  const [trackError, setTrackError] = useState('')
  const [tracking, setTracking] = useState(false)

  useEffect(() => {
    if (initialTrackRef) {
      handleTrack({ preventDefault() {} })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function handleRaise(e) {
    e.preventDefault()
    setSubmitError('')
    if (!rollNumber.trim() || !examId.trim() || !message.trim()) {
      setSubmitError('Please fill in all fields.')
      return
    }
    setSubmitting(true)
    try {
      const result = await api.raiseGrievance(rollNumber.trim(), examId.trim(), category, message.trim(), webhookUrl.trim())
      setTicket(result)
    } catch (err) {
      setSubmitError(err.message || 'Could not submit your grievance. Please try again.')
    } finally {
      setSubmitting(false)
    }
  }

  async function handleTrack(e) {
    e.preventDefault()
    setTrackError('')
    setTracked(null)
    if (!trackRef.trim()) {
      setTrackError('Enter your ticket reference number.')
      return
    }
    setTracking(true)
    try {
      const result = await api.getGrievance(trackRef.trim())
      setTracked(result)
    } catch (err) {
      setTrackError(err.message || 'Ticket not found.')
    } finally {
      setTracking(false)
    }
  }

  return (
    <Modal title="Grievance" eyebrow="Raise or track a query about your result" onClose={onClose}>
      <div className="faq-tabs">
        <button className={`faq-tab ${tab === 'raise' ? 'active' : ''}`} onClick={() => setTab('raise')}>Raise a grievance</button>
        <button className={`faq-tab ${tab === 'track' ? 'active' : ''}`} onClick={() => setTab('track')}>Track a ticket</button>
      </div>

      {tab === 'raise' && (
        ticket ? (
          <div>
            <p className="modal-lead">
              Your grievance has been recorded. Save this reference number to check its status later.
            </p>
            <div className="kv-list">
              <div className="kv-row"><span className="kv-k">Reference</span><span className="kv-v" style={{ fontWeight: 800 }}>{ticket.ticketRef}</span></div>
              <div className="kv-row"><span className="kv-k">Status</span><span className="kv-v">{ticket.status}</span></div>
            </div>
            <TicketQrCode ticketRef={ticket.ticketRef} />
            <button className="btn" style={{ marginTop: 16 }} onClick={onClose}>Done</button>
          </div>
        ) : (
          <form onSubmit={handleRaise}>
            <div className="field">
              <label>Roll Number</label>
              <input value={rollNumber} onChange={(e) => setRollNumber(e.target.value)} placeholder="e.g. 26104578912" />
            </div>
            <div className="field">
              <label>Exam / Portal</label>
              <input value={examId} onChange={(e) => setExamId(e.target.value)} placeholder="e.g. NEET-UG-2026" />
            </div>
            <div className="field">
              <label>Category</label>
              <select value={category} onChange={(e) => setCategory(e.target.value)}>
                {CATEGORIES.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
              </select>
            </div>
            <div className="field">
              <label>Describe the issue</label>
              <textarea rows={4} value={message} onChange={(e) => setMessage(e.target.value)} placeholder="Explain what looks wrong…" />
              <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
                {DRAFT_TEMPLATES[category] && (
                  <button type="button" className="btn-ghost" style={{ padding: '6px 12px', fontSize: 12 }} onClick={applyTemplate}>
                    ✍️ Use a starting template
                  </button>
                )}
                {dictation.supported && (
                  <button
                    type="button"
                    className={`btn-ghost ${dictation.listening ? 'selected' : ''}`}
                    style={{ padding: '6px 12px', fontSize: 12 }}
                    onClick={dictation.toggle}
                  >
                    {dictation.listening ? '⏹ Stop dictation' : '🎤 Dictate instead'}
                  </button>
                )}
              </div>
              {flaggedTerm && (
                <p className="hint" style={{ marginTop: 8 }}>
                  Your message may come across as harsh — consider rephrasing for a faster, more helpful response.
                </p>
              )}
              {suggestedCategory && (
                <p className="hint" style={{ marginTop: 8 }}>
                  This sounds like it might fit <strong>{CATEGORIES.find(([v]) => v === suggestedCategory)?.[1]}</strong> better —{' '}
                  <button type="button" className="btn-ghost" style={{ padding: '2px 8px', fontSize: 12 }} onClick={() => setCategory(suggestedCategory)}>
                    switch category
                  </button>
                </p>
              )}
            </div>
            <div className="field">
              <label>Webhook URL (optional, for developers/institutions)</label>
              <input
                value={webhookUrl}
                onChange={(e) => setWebhookUrl(e.target.value)}
                placeholder="https://your-system.example.com/webhook"
              />
            </div>

            {submitError && <p className="error-text">{submitError}</p>}
            <button className="btn" type="submit" disabled={submitting}>
              {submitting ? 'Submitting…' : 'Submit Grievance'}
            </button>
          </form>
        )
      )}

      {tab === 'track' && (
        <div>
          <form onSubmit={handleTrack}>
            <div className="field">
              <label>Ticket Reference</label>
              <input value={trackRef} onChange={(e) => setTrackRef(e.target.value)} placeholder="e.g. GRV-A1B2C3D4" />
            </div>
            {trackError && <p className="error-text">{trackError}</p>}
            <button className="btn" type="submit" disabled={tracking}>
              {tracking ? 'Checking…' : 'Check Status'}
            </button>
          </form>

          {tracked && (
            <div className="kv-list" style={{ marginTop: 16 }}>
              <div className="kv-row"><span className="kv-k">Status</span><span className="kv-v">{tracked.status}</span></div>
              <div className="kv-row"><span className="kv-k">Category</span><span className="kv-v">{tracked.category}</span></div>
              <div className="kv-row"><span className="kv-k">Submitted</span><span className="kv-v">{new Date(tracked.createdAt).toLocaleString()}</span></div>
              {tracked.estimatedResolutionDays != null && (
                <div className="kv-row">
                  <span className="kv-k">Typical resolution time</span>
                  <span className="kv-v">~{tracked.estimatedResolutionDays} day{tracked.estimatedResolutionDays === 1 ? '' : 's'} for this category (based on past tickets)</span>
                </div>
              )}
              {tracked.adminNote && (
                <div className="kv-row"><span className="kv-k">Note</span><span className="kv-v">{tracked.adminNote}</span></div>
              )}
            </div>
          )}

          <h3 className="modal-section-title">Export your history</h3>
          <p className="hint" style={{ marginBottom: 10 }}>Download every grievance you've raised, as a JSON file for your own records.</p>
          <form onSubmit={handleExport} style={{ display: 'flex', gap: 10, alignItems: 'flex-end' }}>
            <div className="field" style={{ marginBottom: 0, flex: 1 }}>
              <label>Roll Number</label>
              <input value={exportRoll} onChange={(e) => setExportRoll(e.target.value)} placeholder="e.g. 26104578912" />
            </div>
            <button className="btn" type="submit" disabled={exporting} style={{ width: 'auto', padding: '12px 20px' }}>
              {exporting ? 'Exporting…' : 'Download'}
            </button>
          </form>
          {exportError && <p className="error-text">{exportError}</p>}
        </div>
      )}
    </Modal>
  )
}
