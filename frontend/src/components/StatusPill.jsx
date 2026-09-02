import React, { useEffect, useState } from 'react'
import { api } from '../services/api.js'

export default function StatusPill() {
  const [status, setStatus] = useState({ state: 'connected', label: 'Checking system status…' })

  useEffect(() => {
    let cancelled = false

    async function poll() {
      try {
        const data = await api.getSystemStatus()
        if (cancelled) return
        setStatus({
          state: data.state, // 'ok' | 'busy' | 'down'
          label: data.message
        })
      } catch {
        if (cancelled) return
        // If the health endpoint itself is unreachable, degrade gracefully
        // rather than showing a raw error to the end user.
        setStatus({ state: 'busy', label: 'High traffic — some checks delayed' })
      }
    }

    poll()
    const id = setInterval(poll, 15000)
    return () => { cancelled = true; clearInterval(id) }
  }, [])

  const dotClass = status.state === 'ok' ? '' : status.state === 'down' ? 'down' : 'busy'

  return (
    <div className="status-pill">
      <span className={`status-dot ${dotClass}`} />
      {status.label}
    </div>
  )
}
