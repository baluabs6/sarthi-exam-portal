import React, { useEffect, useState } from 'react'

function timeLeft(declareAt) {
  const diff = new Date(declareAt).getTime() - Date.now()
  if (diff <= 0) return null
  const h = Math.floor(diff / 3600000)
  const m = Math.floor((diff % 3600000) / 60000)
  const s = Math.floor((diff % 60000) / 1000)
  return { h, m, s }
}

/** Purely informational — reduces early refresh-mashing before results are actually live. Not enforced server-side. */
export default function ResultCountdown({ declareAt }) {
  const [left, setLeft] = useState(() => timeLeft(declareAt))

  useEffect(() => {
    const interval = setInterval(() => setLeft(timeLeft(declareAt)), 1000)
    return () => clearInterval(interval)
  }, [declareAt])

  if (!left) return null

  return (
    <div className="countdown-banner" role="status">
      Results go live in <strong>{left.h}h {left.m}m {left.s}s</strong>
    </div>
  )
}
