import React, { useEffect, useRef } from 'react'
import QRCode from 'qrcode'

/** Real, scannable QR code — encodes a direct link to /track/{ticketRef} so scanning jumps straight to status, no typing needed. */
export default function TicketQrCode({ ticketRef }) {
  const canvasRef = useRef(null)

  useEffect(() => {
    if (!canvasRef.current) return
    const url = `${window.location.origin}/track/${encodeURIComponent(ticketRef)}`
    QRCode.toCanvas(canvasRef.current, url, { width: 140, margin: 1 }).catch(() => {
      // Non-critical — the ticket reference text above still works without the QR.
    })
  }, [ticketRef])

  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6, marginTop: 12 }}>
      <canvas ref={canvasRef} />
      <p className="hint" style={{ margin: 0 }}>Scan to check status later</p>
    </div>
  )
}
