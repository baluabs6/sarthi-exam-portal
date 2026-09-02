import React, { useState } from 'react'
import { api } from '../services/api.js'

export default function FeedbackWidget({ rollNumber }) {
  const [helpful, setHelpful] = useState(null)
  const [comment, setComment] = useState('')
  const [submitted, setSubmitted] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  async function submit(value) {
    setHelpful(value)
    setSubmitting(true)
    try {
      await api.submitFeedback(rollNumber, value, comment.trim() || undefined)
      setSubmitted(true)
    } catch {
      // Non-critical — fail quietly rather than interrupting the result view.
      setSubmitted(true)
    } finally {
      setSubmitting(false)
    }
  }

  if (submitted) {
    return (
      <div className="feedback-widget">
        <p className="hint" role="status">Thanks for letting us know 🙏</p>
      </div>
    )
  }

  return (
    <div className="feedback-widget">
      <p className="hint" style={{ marginBottom: 0 }}>Was checking your result easy this time?</p>
      <div className="feedback-buttons">
        <button
          className={`feedback-btn ${helpful === true ? 'selected' : ''}`}
          onClick={() => submit(true)}
          disabled={submitting}
          aria-label="Yes, it was easy"
        >
          👍
        </button>
        <button
          className={`feedback-btn ${helpful === false ? 'selected' : ''}`}
          onClick={() => submit(false)}
          disabled={submitting}
          aria-label="No, it had issues"
        >
          👎
        </button>
      </div>
    </div>
  )
}
