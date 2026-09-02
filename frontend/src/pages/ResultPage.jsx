import React, { useEffect, useState } from 'react'
import { useParams, useLocation, Link } from 'react-router-dom'
import { api } from '../services/api.js'
import FeedbackWidget from '../components/FeedbackWidget.jsx'
import { useTranslation } from 'react-i18next'

function speakResult(result, rollNumber) {
  // Uses the browser's built-in Web Speech API (SpeechSynthesis) —
  // no external API, no key, works fully offline once the page is
  // loaded. Genuinely useful for visually impaired or low-literacy
  // candidates, unlike a "fake" AI voice that would need a real
  // text-to-speech service key we don't have here.
  if (typeof window === 'undefined' || !window.speechSynthesis) return
  const passed = result?.status === 'PASS'
  const text = `Roll number ${rollNumber}. ${result?.name}. Exam: ${result?.examId}. Score: ${result?.score}. Status: ${passed ? 'Pass' : 'Fail'}.`
  const utterance = new SpeechSynthesisUtterance(text)
  utterance.rate = 0.95
  window.speechSynthesis.cancel() // stop any previous readout before starting a new one
  window.speechSynthesis.speak(utterance)
}

async function downloadAdmitCard(rollNumber, admissionTicket, setError) {
  try {
    const blob = await api.getAdmitCard(rollNumber, admissionTicket)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `admit-card-${rollNumber}.pdf`
    a.click()
    URL.revokeObjectURL(url)
  } catch (err) {
    setError(err.message || 'Could not download admit card.')
  }
}

export default function ResultPage({ onRaiseGrievance }) {
  const { t } = useTranslation()
  const { rollNumber } = useParams()
  const { state } = useLocation()
  const [result, setResult] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const hasTicket = Boolean(state?.admissionTicket)

  useEffect(() => {
    if (!hasTicket) {
      // Nothing to fetch without a real signed ticket — result-service
      // would just reject this with 401 anyway. Skip the round trip and
      // explain clearly instead of showing a generic error.
      setLoading(false)
      return
    }
    let cancelled = false
    api.getResult(rollNumber, state?.admissionTicket)
      .then((data) => { if (!cancelled) setResult(data) })
      .catch((err) => { if (!cancelled) setError(err.message) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [rollNumber])

  if (loading) {
    return (
      <div className="card" style={{ textAlign: 'center' }}>
        <div className="spinner" />
        <p className="hint">Fetching your result…</p>
      </div>
    )
  }

  if (!hasTicket) {
    return (
      <div className="card">
        <p className="eyebrow">Admission ticket missing or expired</p>
        <h1>Please rejoin the queue</h1>
        <p className="lead">
          Results can only be shown right after a genuine queue admission.
          Your earlier session may have expired — please rejoin the queue
          to check this result again.
        </p>
        <Link className="btn" to="/" style={{ display: 'block', textAlign: 'center', textDecoration: 'none' }}>
          Back to start
        </Link>
      </div>
    )
  }

  if (error) {
    return (
      <div className="card">
        <p className="eyebrow">Something went wrong</p>
        <h1>Couldn't load result</h1>
        <p className="lead">{error}</p>
        <Link className="btn-ghost btn" to="/" style={{ display: 'block', textAlign: 'center', textDecoration: 'none', marginTop: 12 }}>
          Back to start
        </Link>
      </div>
    )
  }

  const passed = result?.status === 'PASS'

  return (
    <div className="result-card">
      <div className="result-head">
        <div className={`result-badge ${passed ? '' : 'fail'}`}>{passed ? '✓' : '!'}</div>
        <div>
          <p className="eyebrow" style={{ marginBottom: 4 }}>Roll No. {rollNumber}</p>
          <h1 style={{ fontSize: 20, margin: 0 }}>{result?.name}</h1>
        </div>
      </div>
      <div className="result-rows">
        <div className="result-row"><span className="k">Exam</span><span className="v">{result?.examId}</span></div>
        <div className="result-row"><span className="k">Score</span><span className="v">{result?.score}</span></div>
        <div className="result-row"><span className="k">Status</span><span className="v">{result?.status}</span></div>
        <div className="result-row"><span className="k">{t('result_declared')}</span><span className="v">{result?.declaredAt}</span></div>
      </div>
      <p className="footer-note">A copy has also been sent to your registered mobile number via SMS/WhatsApp.</p>

      {typeof window !== 'undefined' && window.speechSynthesis && (
        <button
          className="btn-ghost btn"
          style={{ marginTop: 12 }}
          onClick={() => speakResult(result, rollNumber)}
          aria-label="Read result aloud"
        >
          {t('result_read_aloud')}
        </button>
      )}

      <button
        className="btn-ghost btn"
        style={{ marginTop: 12 }}
        onClick={() => downloadAdmitCard(rollNumber, state?.admissionTicket, setError)}
      >
        {t('result_admit_card')}
      </button>

      {onRaiseGrievance && (
        <button
          className="btn-ghost btn"
          style={{ marginTop: 12 }}
          onClick={() => onRaiseGrievance(rollNumber, result?.examId)}
        >
          {t('result_raise_grievance')}
        </button>
      )}

      <FeedbackWidget rollNumber={rollNumber} />
    </div>
  )
}
