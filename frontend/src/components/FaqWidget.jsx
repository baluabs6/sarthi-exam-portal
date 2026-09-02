import React, { useEffect, useMemo, useState } from 'react'
import { FAQ_ENTRIES } from './faqData.js'
import { api } from '../services/api.js'

// Deliberately simple keyword-overlap scoring rather than a real
// embeddings/vector search — this stays honest about what "search" means
// here (no ML model, no network call, nothing to fail or hallucinate)
// while still being genuinely useful for a small, fixed FAQ set. If this
// scales to a large/changing knowledge base, that's the point to
// introduce real retrieval (embeddings + a vector store) behind a
// backend endpoint, since API keys must never live in frontend code.
function search(query) {
  const terms = query.toLowerCase().split(/\s+/).filter(Boolean)
  if (terms.length === 0) return FAQ_ENTRIES.slice(0, 3)

  return FAQ_ENTRIES
    .map((entry) => {
      const haystack = (entry.q + ' ' + entry.keywords.join(' ')).toLowerCase()
      const score = terms.reduce((acc, term) => acc + (haystack.includes(term) ? 1 : 0), 0)
      return { entry, score }
    })
    .filter((r) => r.score > 0)
    .sort((a, b) => b.score - a.score)
    .map((r) => r.entry)
    .slice(0, 4)
}

export default function FaqWidget() {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const results = useMemo(() => search(query), [query])

  useEffect(() => {
    if (query.trim().length < 4 || results.length > 0) return
    const timeout = setTimeout(() => {
      api.reportFaqMiss(query.trim()).catch(() => {}) // best-effort, never blocks the UI
    }, 800) // wait for the person to stop typing before reporting
    return () => clearTimeout(timeout)
  }, [query, results])

  return (
    <div className="faq-widget">
      {open && (
        <div className="faq-panel">
          <div className="faq-panel-head">
            <span>Help &amp; FAQ</span>
            <button className="modal-close" onClick={() => setOpen(false)} aria-label="Close help">×</button>
          </div>
          <input
            className="faq-search"
            placeholder="Ask something, e.g. 'lose my place'"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            autoFocus
          />
          <div className="faq-results">
            {results.length === 0 && (
              <p className="hint" style={{ padding: '0 2px' }}>No matching answer — try different words, or raise a grievance for anything result-specific.</p>
            )}
            {results.map((entry) => (
              <div className="faq-item" key={entry.q}>
                <p className="faq-q">{entry.q}</p>
                <p className="faq-a">{entry.a}</p>
              </div>
            ))}
          </div>
        </div>
      )}
      <button className="faq-fab" onClick={() => setOpen((v) => !v)} aria-label="Open help">
        {open ? '×' : '?'}
      </button>
    </div>
  )
}
