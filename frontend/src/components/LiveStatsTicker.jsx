import React, { useEffect, useState } from 'react'
import { api } from '../services/api.js'

export default function LiveStatsTicker() {
  const [count, setCount] = useState(null)

  useEffect(() => {
    let cancelled = false
    function load() {
      api.getLiveCount()
        .then((data) => { if (!cancelled) setCount(data.totalWaiting) })
        .catch(() => {})
    }
    load()
    const interval = setInterval(load, 10000)
    return () => { cancelled = true; clearInterval(interval) }
  }, [])

  if (count == null) return null

  return (
    <div className="live-ticker" role="status">
      <span className="live-dot" />
      {count.toLocaleString()} {count === 1 ? 'person' : 'people'} currently in queue across all exams
    </div>
  )
}
