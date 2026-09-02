import React, { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'

const STORAGE_KEY = 'sarthi-saved-roll-numbers'

function loadSaved() {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]')
  } catch {
    return []
  }
}

/**
 * Lets one person (a cybercafé operator, a parent checking for several
 * kids) quickly re-select a roll number they've checked before, instead
 * of retyping it. This is purely a local convenience list — it does NOT
 * bypass the queue or admission ticket for any of them; each one still
 * goes through the same join → admit → ticket flow individually.
 */
export default function SavedRollNumbers({ onSelect }) {
  const { t } = useTranslation()
  const [saved, setSaved] = useState(loadSaved)

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(saved))
  }, [saved])

  function remove(rollNumber) {
    setSaved((prev) => prev.filter((r) => r !== rollNumber))
  }

  if (saved.length === 0) return null

  return (
    <div style={{ marginBottom: 16 }}>
      <p className="hint" style={{ marginBottom: 8 }}>{t('join_recently_checked')}</p>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {saved.map((rollNumber) => (
          <span key={rollNumber} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            <button
              type="button"
              className="btn-ghost"
              style={{ padding: '6px 12px', fontSize: 12.5 }}
              onClick={() => onSelect(rollNumber)}
            >
              {rollNumber}
            </button>
            <button
              type="button"
              className="modal-close"
              style={{ width: 22, height: 22, fontSize: 13 }}
              onClick={() => remove(rollNumber)}
              aria-label={`Remove ${rollNumber} from saved list`}
            >
              ×
            </button>
          </span>
        ))}
      </div>
    </div>
  )
}

export function saveRollNumber(rollNumber) {
  const current = loadSaved()
  if (current.includes(rollNumber)) return
  const updated = [rollNumber, ...current].slice(0, 6) // cap so this stays a quick-pick list, not a full history
  localStorage.setItem(STORAGE_KEY, JSON.stringify(updated))
}
