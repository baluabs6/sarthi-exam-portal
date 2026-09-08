import React, { useEffect, useState } from 'react'
import { api } from '../services/api.js'

const STATUS_OPTIONS = ['RAISED', 'UNDER_REVIEW', 'RESOLVED', 'REJECTED']

/** Shows the backend's extractive summary (real sentences from the message, never generated) by default, with a toggle to see the full text. */
function GrievanceMessageDisplay({ grievance }) {
  const [expanded, setExpanded] = useState(false)
  const isTruncated = grievance.summary && grievance.summary !== grievance.message

  return (
    <div>
      <span className="kv-v">{expanded || !isTruncated ? grievance.message : grievance.summary}</span>
      {isTruncated && (
        <button
          type="button"
          className="btn-ghost"
          style={{ padding: '2px 8px', fontSize: 11, marginLeft: 8 }}
          onClick={() => setExpanded((v) => !v)}
        >
          {expanded ? 'Show summary' : 'Show full message'}
        </button>
      )}
    </div>
  )
}

export default function AdminDashboard() {
  const [adminKey, setAdminKey] = useState(() => sessionStorage.getItem('sarthi-admin-key') || '')
  const [keyInput, setKeyInput] = useState('')
  const [error, setError] = useState('')

  const [overview, setOverview] = useState(null)
  const [rateInput, setRateInput] = useState('')
  const [grievanceStatus, setGrievanceStatus] = useState('RAISED')
  const [searchQuery, setSearchQuery] = useState('')
  const [grievances, setGrievances] = useState([])
  const [selected, setSelected] = useState(() => new Set())
  const [auditLog, setAuditLog] = useState([])
  const [loading, setLoading] = useState(false)
  const [digest, setDigest] = useState(null)
  const [digestLoading, setDigestLoading] = useState(false)
  const [draftSuggestions, setDraftSuggestions] = useState({}) // category -> suggestion text
  const [clusters, setClusters] = useState([])
  const [faqGaps, setFaqGaps] = useState({})
  const [enumerationAlerts, setEnumerationAlerts] = useState([])
  const [anomalies, setAnomalies] = useState({})
  const [clusterLabels, setClusterLabels] = useState({}) // index -> { loading, label, disabled }
  const [whoami, setWhoami] = useState(null) // { name, role, canTuneQueue, canManageGrievances }

  function saveKey(e) {
    e.preventDefault()
    if (!keyInput.trim()) return
    sessionStorage.setItem('sarthi-admin-key', keyInput.trim())
    setAdminKey(keyInput.trim())
    setError('')
  }

  function logout() {
    sessionStorage.removeItem('sarthi-admin-key')
    setAdminKey('')
    setKeyInput('')
    setWhoami(null)
  }

  async function loadAll() {
    if (!adminKey) return
    setLoading(true)
    setError('')
    try {
      const [who, ov, gr, log, cl, gaps, enumIps, anom] = await Promise.all([
        api.adminWhoami(adminKey),
        api.adminGetQueues(adminKey),
        api.adminListGrievances(adminKey, grievanceStatus, searchQuery),
        api.adminGetAuditLog(adminKey),
        api.adminGetClusters(adminKey),
        api.adminGetFaqGaps(adminKey),
        api.adminGetEnumerationAlerts(adminKey),
        api.adminGetAnomalies(adminKey),
      ])
      setWhoami(who)
      setOverview(ov)
      setRateInput(String(ov.admitPerTick))
      setGrievances(gr)
      setSelected(new Set())
      setAuditLog(log)
      setClusters(cl)
      setFaqGaps(gaps)
      setEnumerationAlerts(enumIps)
      setAnomalies(anom)
    } catch (err) {
      setError(err.message || 'Could not load admin data — check your admin key.')
      if (String(err.message || '').includes('401') || String(err.message || '').toLowerCase().includes('invalid')) {
        // Don't keep hammering with a bad key.
        logout()
      }
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadAll()
    if (!adminKey) return
    const interval = setInterval(loadAll, 15000)
    return () => clearInterval(interval)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [adminKey, grievanceStatus, searchQuery])

  async function updateRate(e) {
    e.preventDefault()
    const value = parseInt(rateInput, 10)
    if (!Number.isFinite(value) || value < 1) {
      setError('Admit rate must be a positive number.')
      return
    }
    try {
      await api.adminSetAdmitRate(adminKey, value)
      loadAll()
    } catch (err) {
      setError(err.message || 'Could not update admission rate.')
    }
  }

  async function resolveGrievance(ticketRef, status, category) {
    let defaultNote = ''
    if (status === 'RESOLVED') {
      try {
        const { suggestion } = await api.adminGetDraftSuggestion(adminKey, category)
        defaultNote = suggestion || ''
      } catch { /* suggestion is a nice-to-have, never block resolution on it */ }
    }
    const note = window.prompt(`Note for ${ticketRef} (${status})${defaultNote ? ' — pre-filled from a similar resolved ticket' : ''}:`, defaultNote) || ''
    try {
      await api.adminUpdateGrievance(adminKey, ticketRef, status, note)
      loadAll()
    } catch (err) {
      setError(err.message || 'Could not update grievance.')
    }
  }

  /** Bounded, on-demand: only called when an admin clicks it for a specific cluster, never in bulk. Degrades to a "not configured" note if AI isn't set up. */
  async function labelCluster(index, cluster) {
    setClusterLabels((prev) => ({ ...prev, [index]: { loading: true } }))
    try {
      const res = await api.adminLabelCluster(adminKey, cluster.sampleMessage, cluster.size)
      setClusterLabels((prev) => ({
        ...prev,
        [index]: res.aiEnabled
          ? { label: res.label || 'Could not generate a label for this cluster.' }
          : { disabled: true }
      }))
    } catch {
      setClusterLabels((prev) => ({ ...prev, [index]: { error: true } }))
    }
  }

  function toggleSelected(ticketRef) {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(ticketRef)) next.delete(ticketRef)
      else next.add(ticketRef)
      return next
    })
  }

  async function bulkResolve(status) {
    if (selected.size === 0) return
    const note = window.prompt(`Optional note for ${selected.size} ticket(s) (${status}):`, '') || ''
    try {
      await api.adminBulkUpdateGrievances(adminKey, Array.from(selected), status, note)
      loadAll()
    } catch (err) {
      setError(err.message || 'Could not update selected grievances.')
    }
  }

  async function downloadCsv() {
    try {
      const csv = await api.adminExportGrievancesCsv(adminKey, grievanceStatus)
      const blob = new Blob([csv], { type: 'text/csv' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `grievances-${grievanceStatus}.csv`
      a.click()
      URL.revokeObjectURL(url)
    } catch (err) {
      setError(err.message || 'Could not export CSV.')
    }
  }

  // A genuinely data-driven summary — real counts pulled from the API,
  // assembled with a fixed sentence template. Deliberately NOT an LLM
  // call: no model is asked to "write a digest", so there's nothing here
  // that could invent a number. See README for what a real LLM-authored
  // digest would need (a backend endpoint + provider API key).
  async function generateDigest() {
    setDigestLoading(true)
    setError('')
    try {
      const results = await Promise.all(STATUS_OPTIONS.map((s) => api.adminListGrievances(adminKey, s)));
      const byStatus = Object.fromEntries(STATUS_OPTIONS.map((s, i) => [s, results[i]]))
      const all = results.flat()
      const byCategory = {}
      all.forEach((g) => { byCategory[g.category] = (byCategory[g.category] || 0) + 1 })
      const topCategory = Object.entries(byCategory).sort((a, b) => b[1] - a[1])[0]

      setDigest({
        total: all.length,
        byStatus: Object.fromEntries(STATUS_OPTIONS.map((s) => [s, byStatus[s].length])),
        byCategory,
        topCategory,
      })
    } catch (err) {
      setError(err.message || 'Could not generate digest.')
    } finally {
      setDigestLoading(false)
    }
  }

  if (!adminKey) {
    return (
      <div className="card">
        <p className="eyebrow">Staff access only</p>
        <h1>Admin sign-in</h1>
        <p className="lead">Enter the admin key to view live queue status and manage grievances.</p>
        <form onSubmit={saveKey}>
          <div className="field">
            <label htmlFor="adminKey">Admin Key</label>
            <input id="adminKey" type="password" value={keyInput} onChange={(e) => setKeyInput(e.target.value)} autoComplete="off" />
          </div>
          {error && <p className="error-text">{error}</p>}
          <button className="btn" type="submit">Continue</button>
        </form>
      </div>
    )
  }

  return (
    <div className="card" style={{ maxWidth: 720 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <div>
          <p className="eyebrow">Staff console</p>
          <h1>Admin Dashboard</h1>
          {whoami && (
            <p className="hint" style={{ marginTop: 2 }}>
              Signed in as <strong>{whoami.name}</strong> ({whoami.role.replace('_', ' ')})
              {whoami.role === 'AUDITOR' && ' — read-only'}
            </p>
          )}
        </div>
        <button className="btn-ghost" onClick={logout} style={{ padding: '8px 14px', fontSize: 12.5 }}>Sign out</button>
      </div>

      {error && <p className="error-text">{error}</p>}
      {loading && !overview && <p className="hint">Loading…</p>}

      {overview && (
        <>
          <h3 className="modal-section-title">Live Queue Depth</h3>
          <div className="kv-list">
            {Object.entries(overview.depthByExam).map(([exam, depth]) => (
              <div className="kv-row" key={exam}>
                <span className="kv-k">{exam}</span>
                <span className="kv-v">
                  {depth} waiting
                  {overview.minutesToCriticalByExam?.[exam] != null && (
                    <span style={{ color: 'var(--alert)', marginLeft: 8 }}>
                      ⚠ ~{overview.minutesToCriticalByExam[exam]}m to critical load at current rate
                    </span>
                  )}
                  {overview.possiblyAbandonedByExam?.[exam] != null && (
                    <span style={{ color: 'var(--text-mid)', marginLeft: 8 }}>
                      · ~{overview.possiblyAbandonedByExam[exam]} possibly abandoned (rough estimate)
                    </span>
                  )}
                </span>
              </div>
            ))}
            <div className="kv-row">
              <span className="kv-k">System load</span>
              <span className="kv-v">{overview.systemLoad}</span>
            </div>
            {overview.observedAdmitRate >= 0 && (
              <div className="kv-row">
                <span className="kv-k">Observed vs configured rate</span>
                <span className="kv-v">{overview.observedAdmitRate.toFixed(1)} / {overview.admitPerTick} per tick</span>
              </div>
            )}
          </div>

          {enumerationAlerts.length > 0 && (
            <>
              <h3 className="modal-section-title">⚠ Possible Result-Enumeration Activity</h3>
              <p className="hint" style={{ marginBottom: 10 }}>
                These IPs have tried an unusually high number of distinct roll numbers recently — a signal, not proof, of scripted probing.
              </p>
              <div className="kv-list">
                {enumerationAlerts.map((ip) => (
                  <div className="kv-row" key={ip}><span className="kv-v">{ip}</span></div>
                ))}
              </div>
            </>
          )}

          {Object.keys(anomalies).length > 0 && (
            <>
              <h3 className="modal-section-title">⚠ Admission Bursts (last hour)</h3>
              <p className="hint" style={{ marginBottom: 10 }}>
                Minutes where admissions spiked well outside the recent baseline — a statistical flag, not proof of anything wrong.
              </p>
              <div className="kv-list">
                {Object.entries(anomalies).map(([exam, windows]) =>
                  windows.map((w, i) => (
                    <div className="kv-row" key={`${exam}-${i}`}>
                      <span className="kv-k">{exam} · {new Date(w.minuteStart).toLocaleTimeString()}</span>
                      <span className="kv-v">{w.admittedCount} admitted (baseline ~{w.baselineMean.toFixed(1)} ± {w.baselineStdDev.toFixed(1)})</span>
                    </div>
                  ))
                )}
              </div>
            </>
          )}

          <h3 className="modal-section-title">Admission Rate</h3>
          {whoami && !whoami.canTuneQueue ? (
            <p className="hint">Your role ({whoami.role.replace('_', ' ')}) can view this but not change it.</p>
          ) : (
            <form onSubmit={updateRate} style={{ display: 'flex', gap: 10, alignItems: 'flex-end' }}>
              <div className="field" style={{ marginBottom: 0, flex: 1 }}>
                <label>Admitted per tick (every 2s)</label>
                <input type="number" min="1" value={rateInput} onChange={(e) => setRateInput(e.target.value)} />
              </div>
              <button className="btn" type="submit" style={{ width: 'auto', padding: '12px 20px' }}>Update</button>
            </form>
          )}

          <h3 className="modal-section-title">Grievances</h3>
          <div className="faq-tabs">
            {STATUS_OPTIONS.map((s) => (
              <button key={s} className={`faq-tab ${grievanceStatus === s ? 'active' : ''}`} onClick={() => setGrievanceStatus(s)}>
                {s.replace('_', ' ')}
              </button>
            ))}
          </div>

          <div className="field" style={{ marginBottom: 12 }}>
            <input
              placeholder="🔍 Search grievances (ticket ref, category, or message text)"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>

          <div style={{ display: 'flex', gap: 8, marginBottom: 10, flexWrap: 'wrap' }}>
            <button className="btn-ghost" style={{ padding: '6px 12px', fontSize: 12 }} onClick={downloadCsv}>
              ⬇ Export CSV
            </button>
            {whoami?.canManageGrievances && selected.size > 0 && STATUS_OPTIONS.filter((s) => s !== grievanceStatus).map((s) => (
              <button key={s} className="btn-ghost" style={{ padding: '6px 12px', fontSize: 12 }} onClick={() => bulkResolve(s)}>
                Bulk mark {selected.size} as {s.replace('_', ' ')}
              </button>
            ))}
          </div>

          {whoami && !whoami.canManageGrievances && (
            <p className="hint">Your role ({whoami.role.replace('_', ' ')}) can view grievances but not change their status.</p>
          )}

          {grievances.length === 0 && <p className="hint">No grievances with this status.</p>}
          <div className="kv-list">
            {grievances.map((g) => (
              <div className="kv-row" key={g.ticketRef} style={{ flexDirection: 'column', gap: 6, alignItems: 'stretch' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                  <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontWeight: 800 }}>
                    {whoami?.canManageGrievances && (
                      <input type="checkbox" checked={selected.has(g.ticketRef)} onChange={() => toggleSelected(g.ticketRef)} />
                    )}
                    {g.ticketRef}
                  </label>
                  <span className="kv-k">{g.category}{g.languageHint && g.languageHint !== 'en' ? ` · lang: ${g.languageHint}` : ''}</span>
                </div>
                <GrievanceMessageDisplay grievance={g} />
                {whoami?.canManageGrievances && (
                  <div style={{ display: 'flex', gap: 8, marginTop: 4, flexWrap: 'wrap' }}>
                    {STATUS_OPTIONS.filter((s) => s !== g.status).map((s) => (
                      <button key={s} className="btn-ghost" style={{ padding: '6px 12px', fontSize: 12 }} onClick={() => resolveGrievance(g.ticketRef, s, g.category)}>
                        Mark {s.replace('_', ' ')}
                      </button>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>

          <h3 className="modal-section-title">Possible Systemic Issues</h3>
          <p className="hint" style={{ marginBottom: 10 }}>
            Open grievances with substantially overlapping wording, grouped across all roll numbers — a stronger signal than category counts alone.
          </p>
          {clusters.length === 0 && <p className="hint">No repeated patterns detected right now.</p>}
          <div className="kv-list">
            {clusters.map((c, i) => {
              const labelState = clusterLabels[i]
              return (
                <div className="kv-row" key={i} style={{ flexDirection: 'column', gap: 4, alignItems: 'stretch' }}>
                  <span className="kv-v" style={{ fontWeight: 800 }}>{c.size} similar tickets: {c.ticketRefs.join(', ')}</span>
                  <span className="kv-v">"{c.sampleMessage}"</span>
                  {!labelState && (
                    <button className="btn-ghost" style={{ padding: '2px 8px', fontSize: 11, alignSelf: 'flex-start' }} onClick={() => labelCluster(i, c)}>
                      Label with AI
                    </button>
                  )}
                  {labelState?.loading && <span className="hint">Labeling…</span>}
                  {labelState?.label && <span className="hint" style={{ fontWeight: 700 }}>AI label: {labelState.label}</span>}
                  {labelState?.disabled && <span className="hint">AI assistant isn't configured on this deployment (set ANTHROPIC_API_KEY to enable).</span>}
                  {labelState?.error && <span className="hint">Could not generate a label right now.</span>}
                </div>
              )
            })}
          </div>

          <h3 className="modal-section-title">FAQ Gaps</h3>
          <p className="hint" style={{ marginBottom: 10 }}>Searches in the help widget that came back empty — real content gaps to fill.</p>
          {Object.keys(faqGaps).length === 0 && <p className="hint">No unanswered searches recorded yet.</p>}
          <div className="kv-list">
            {Object.entries(faqGaps).map(([q, count]) => (
              <div className="kv-row" key={q}><span className="kv-k">"{q}"</span><span className="kv-v">{count}× searched</span></div>
            ))}
          </div>

          <h3 className="modal-section-title">Weekly Digest</h3>
          <p className="hint" style={{ marginBottom: 10 }}>
            A plain summary built from real counts across every status — not AI-generated, just aggregated data.
          </p>
          {!digest && (
            <button className="btn-ghost" style={{ padding: '8px 16px', fontSize: 12.5 }} onClick={generateDigest} disabled={digestLoading}>
              {digestLoading ? 'Crunching numbers…' : '📊 Generate digest'}
            </button>
          )}
          {digest && (
            <div className="kv-list">
              <div className="kv-row"><span className="kv-k">Total grievances</span><span className="kv-v">{digest.total}</span></div>
              {STATUS_OPTIONS.map((s) => (
                <div className="kv-row" key={s}><span className="kv-k">{s.replace('_', ' ')}</span><span className="kv-v">{digest.byStatus[s]}</span></div>
              ))}
              {digest.topCategory && (
                <div className="kv-row">
                  <span className="kv-k">Most common issue</span>
                  <span className="kv-v">
                    {digest.topCategory[0].replace('_', ' ')} ({digest.topCategory[1]} ticket{digest.topCategory[1] === 1 ? '' : 's'})
                    {digest.topCategory[1] >= 3 && ' — worth checking for a systemic cause'}
                  </span>
                </div>
              )}
            </div>
          )}

          <h3 className="modal-section-title">Recent Admin Activity</h3>
          <div className="kv-list">
            {auditLog.slice(0, 10).map((entry) => (
              <div className="kv-row" key={entry.id}>
                <span className="kv-k">{new Date(entry.timestamp).toLocaleTimeString()}</span>
                <span className="kv-v">{entry.action}: {entry.detail} (from {entry.sourceIp})</span>
              </div>
            ))}
            {auditLog.length === 0 && <p className="hint" style={{ padding: '0 2px' }}>No admin actions recorded yet.</p>}
          </div>
        </>
      )}
    </div>
  )
}
