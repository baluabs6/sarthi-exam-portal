import React, { useEffect, useRef } from 'react'

export default function Modal({ title, eyebrow, onClose, children, wide }) {
  const closeBtnRef = useRef(null)

  useEffect(() => {
    function onKey(e) {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    closeBtnRef.current?.focus()
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div
        className={`modal-panel ${wide ? 'modal-wide' : ''}`}
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby="modal-title"
      >
        <div className="modal-head">
          <div>
            {eyebrow && <p className="eyebrow" style={{ marginBottom: 4 }}>{eyebrow}</p>}
            <h2 className="modal-title" id="modal-title">{title}</h2>
          </div>
          <button className="modal-close" onClick={onClose} aria-label="Close dialog" ref={closeBtnRef}>×</button>
        </div>
        <div className="modal-body">
          {children}
        </div>
      </div>
    </div>
  )
}
