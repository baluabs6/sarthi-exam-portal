import React, { useEffect, useRef, useState } from 'react'
import { useParams, useLocation, useNavigate } from 'react-router-dom'
import { subscribeToQueue } from '../services/websocket.js'
import { api } from '../services/api.js'
import { useTranslation } from 'react-i18next'

function formatWait(seconds) {
  if (seconds == null) return '—'
  if (seconds < 60) return `${seconds}s`
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${m}m ${s}s`
}

function speakPosition(position, waitSeconds) {
  // Same native Web Speech API already used on the result page — no
  // external service, works offline once loaded. Genuinely useful for
  // someone who wants a spoken update without staring at the screen.
  if (typeof window === 'undefined' || !window.speechSynthesis) return
  const waitText = waitSeconds != null ? `, estimated wait ${Math.round(waitSeconds / 60)} minutes` : ''
  const utterance = new SpeechSynthesisUtterance(`You are number ${position} in the queue${waitText}.`)
  utterance.rate = 0.95
  window.speechSynthesis.cancel()
  window.speechSynthesis.speak(utterance)
}

function notifyAdmitted() {
  // Best-effort only: if permission was never granted, or the browser
  // doesn't support the Notification API, we just skip it silently —
  // the in-app redirect below is what actually matters.
  if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return
  try {
    new Notification("It's your turn", {
      body: 'Your Sarthi Portal queue turn has arrived — opening your result now.',
    })
  } catch {
    // Some browsers require notifications to be shown via a service
    // worker registration rather than the constructor directly; failing
    // silently here is fine since it's a bonus, not core functionality.
  }
}

function copyShareLink(setCopied) {
  // Safe to share: this URL only contains the opaque queueId, never the
  // roll number — a parent/relative can watch the position update
  // without seeing anything personal.
  navigator.clipboard?.writeText(window.location.href).then(() => {
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }).catch(() => {})
}

export default function WaitingRoom() {
  const { t } = useTranslation()
  const { queueId } = useParams()
  const { state } = useLocation()
  const navigate = useNavigate()

  const [snapshot, setSnapshot] = useState(null)
  const [connection, setConnection] = useState('connecting')
  const [notifPermission, setNotifPermission] = useState(
    typeof Notification !== 'undefined' ? Notification.permission : 'unsupported'
  )
  const [linkCopied, setLinkCopied] = useState(false)
  const pollRef = useRef(null)

  function enableNotifications() {
    if (typeof Notification === 'undefined') return
    Notification.requestPermission().then(setNotifPermission)
  }

  useEffect(() => {
    // Primary path: live WebSocket push from queue-service.
    const unsubscribe = subscribeToQueue(queueId, {
      onUpdate: (payload) => setSnapshot(payload),
      onAdmitted: (payload) => {
        const roll = state?.rollNumber
        notifyAdmitted()
        // Forward the signed resultTicket the server just minted — not
        // the queueId. result-service now verifies this ticket, so it
        // has to be the real thing.
        navigate(roll ? `/result/${roll}` : '/', { state: { admissionTicket: payload?.resultTicket } })
      },
      onConnectionChange: setConnection,
    })

    return unsubscribe
  }, [queueId])

  useEffect(() => {
    // Fallback path: if the socket can't connect (proxies/firewalls common
    // on institutional networks in India), degrade to polling every 3s so
    // the experience still works, just less instantly.
    if (connection === 'connected') {
      clearInterval(pollRef.current)
      return
    }
    pollRef.current = setInterval(async () => {
      try {
        const data = await api.getQueueStatus(queueId)
        setSnapshot(data)
        if (data.admitted) {
          const roll = state?.rollNumber
          notifyAdmitted()
          navigate(roll ? `/result/${roll}` : '/', { state: { admissionTicket: data.resultTicket } })
        }
      } catch {
        // stay on screen; next tick will retry
      }
    }, 3000)
    return () => clearInterval(pollRef.current)
  }, [connection, queueId])

  const position = snapshot?.position
  const total = snapshot?.aheadOfYou != null ? snapshot.aheadOfYou + position : null
  const progressPct = total ? Math.min(100, Math.round(((total - position) / total) * 100)) : 4

  return (
    <div className="board-wrap">
      <div className="board">
        <p className="board-label">Live Queue &nbsp;·&nbsp; Ticket {queueId?.slice(0, 8).toUpperCase()}</p>

        <div className="token-number" aria-live="polite" aria-atomic="true">
          {position != null ? `#${position}` : <span className="spinner" role="status" aria-label="Assigning your position" />}
        </div>
        <p className="token-sub">
          {position != null ? t('waiting_your_position') : t('waiting_assigning')}
        </p>

        {position != null && typeof window !== 'undefined' && window.speechSynthesis && (
          <button
            className="btn-ghost btn-notify"
            style={{ marginBottom: 16 }}
            onClick={() => speakPosition(position, snapshot?.estimatedWaitSeconds)}
            aria-label="Speak my queue position aloud"
          >
            🔊 Speak my position
          </button>
        )}

        <div className="progress-track" role="progressbar" aria-valuenow={progressPct} aria-valuemin={0} aria-valuemax={100} aria-label="Queue progress">
          <div className="progress-fill" style={{ width: `${progressPct}%` }} />
        </div>

        <div className="board-grid" aria-live="polite">
          <div className="board-cell">
            <div className="k">Est. wait</div>
            <div className="v">{formatWait(snapshot?.estimatedWaitSeconds)}</div>
          </div>
          <div className="board-cell">
            <div className="k">Ahead of you</div>
            <div className="v">{snapshot?.aheadOfYou ?? '—'}</div>
          </div>
          <div className="board-cell">
            <div className="k">Connection</div>
            <div className="v" style={{ fontSize: 14, textTransform: 'capitalize' }}>
              {connection === 'connected' ? 'live' : connection === 'connecting' ? 'connecting' : 'polling'}
            </div>
          </div>
          <div className="board-cell">
            <div className="k">System load</div>
            <div className="v" style={{ fontSize: 14 }}>{snapshot?.status ?? 'normal'}</div>
          </div>
        </div>

        <p className="hint">
          <strong>You don't need to keep this tab active.</strong> We'll move
          you forward automatically and redirect you the moment it's your
          turn — no need to refresh.
        </p>

        {notifPermission === 'default' && (
          <button className="btn-ghost btn-notify" onClick={enableNotifications} aria-label="Enable turn notifications">
            {t('waiting_notify_me')}
          </button>
        )}
        {notifPermission === 'granted' && (
          <p className="hint hint-muted" role="status">{t('waiting_notify_on')}</p>
        )}

        <button className="btn-ghost btn-notify" onClick={() => copyShareLink(setLinkCopied)} aria-label="Copy shareable queue link">
          {linkCopied ? t('waiting_share_copied') : t('waiting_share')}
        </button>
      </div>
    </div>
  )
}
