import React, { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../services/api.js'
import LiveStatsTicker from '../components/LiveStatsTicker.jsx'
import ResultCountdown from '../components/ResultCountdown.jsx'
import SavedRollNumbers, { saveRollNumber } from '../components/SavedRollNumbers.jsx'

// Declared-time config per exam — purely a frontend display hint (a
// friendly "results go live at" banner), not enforced anywhere on the
// backend. If you want real staged release, that needs to be enforced
// server-side too.
const RESULT_DECLARE_TIMES = {
  'NEET-UG-2026': null,       // already live — no countdown shown
  'JEE-MAIN-2026': null,
  'SSC-CGL-2026': null,
}

import { useTranslation } from 'react-i18next'

export default function JoinPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [examId, setExamId] = useState('NEET-UG-2026')
  const [rollNumber, setRollNumber] = useState('')
  const [captcha, setCaptcha] = useState(null) // { captchaId, question }
  const [captchaAnswer, setCaptchaAnswer] = useState('')
  const [website, setWebsite] = useState('') // honeypot — real users never see or fill this
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function loadCaptcha() {
    try {
      const challenge = await api.getCaptcha()
      setCaptcha(challenge)
      setCaptchaAnswer('')
    } catch {
      // If the challenge itself can't be fetched, leave captcha null —
      // the submit handler below will surface a clear error instead of
      // silently letting the request through.
      setCaptcha(null)
    }
  }

  useEffect(() => {
    loadCaptcha()
  }, [])

  async function handleSubmit(e) {
    e.preventDefault()
    setError('')
    if (!rollNumber.trim()) {
      setError('Enter your roll number to continue.')
      return
    }
    if (!captcha) {
      setError('Verification challenge failed to load. Please try again.')
      loadCaptcha()
      return
    }
    if (!captchaAnswer.trim()) {
      setError('Answer the verification challenge to continue.')
      return
    }
    setLoading(true)
    try {
      // The backend decides, per current load, whether this user goes
      // straight through or is placed in the live queue. Either way the
      // response is a queueId the WaitingRoom can track — so the UI never
      // needs to special-case "server is fine right now".
      const { queueId } = await api.joinQueue(
        examId, rollNumber.trim(), captcha.captchaId, captchaAnswer.trim(), website
      )
      saveRollNumber(rollNumber.trim())
      navigate(`/queue/${queueId}`, { state: { rollNumber: rollNumber.trim() } })
    } catch (err) {
      setError(err.message || 'Could not reach the portal. Please try again in a moment.')
      // A wrong/expired answer is single-use on the server either way —
      // always fetch a fresh challenge after any failed attempt.
      loadCaptcha()
    } finally {
      setLoading(false)
    }
  }

  const declareAt = RESULT_DECLARE_TIMES[examId]

  return (
    <div className="card">
      <p className="eyebrow">{t('join_eyebrow')}</p>
      <h1>{t('join_title')}</h1>
      <p className="lead">
        {t('join_lead')}
      </p>

      <LiveStatsTicker />
      {declareAt && <ResultCountdown declareAt={declareAt} />}
      <SavedRollNumbers onSelect={(r) => setRollNumber(r)} />

      <form onSubmit={handleSubmit}>
        <div className="field">
          <label htmlFor="examId">{t('join_exam_label')}</label>
          <select id="examId" value={examId} onChange={(e) => setExamId(e.target.value)}>
            <option value="NEET-UG-2026">NEET-UG 2026 Result</option>
            <option value="JEE-MAIN-2026">JEE Main 2026 Result</option>
            <option value="SSC-CGL-2026">SSC CGL 2026 Application</option>
          </select>
        </div>

        <div className="field">
          <label htmlFor="rollNumber">{t('join_roll_label')}</label>
          <input
            id="rollNumber"
            value={rollNumber}
            onChange={(e) => setRollNumber(e.target.value)}
            placeholder={t('join_roll_placeholder')}
            autoComplete="off"
          />
        </div>

        <div className="field">
          <label htmlFor="captchaAnswer">
            {t('join_verification_label')} &nbsp;·&nbsp; {captcha ? captcha.question : t('join_loading_challenge')}
          </label>
          <input
            id="captchaAnswer"
            value={captchaAnswer}
            onChange={(e) => setCaptchaAnswer(e.target.value)}
            placeholder="Your answer"
            inputMode="numeric"
            autoComplete="off"
          />
        </div>

        {/* Honeypot: visually hidden (not display:none, which some bots
            detect and skip) and out of tab order. Real users never see or
            fill this in; a naive form-filling bot script often does. */}
        <div className="hp-field" aria-hidden="true">
          <label htmlFor="website">Leave this field blank</label>
          <input
            id="website"
            name="website"
            value={website}
            onChange={(e) => setWebsite(e.target.value)}
            tabIndex={-1}
            autoComplete="off"
          />
        </div>

        {error && <p className="error-text">{error}</p>}

        <button className="btn" type="submit" disabled={loading}>
          {loading ? t('join_connecting') : t('join_submit')}
        </button>
      </form>
    </div>
  )
}
