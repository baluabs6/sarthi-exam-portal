import React, { useState } from 'react'
import Modal from './Modal.jsx'

const SECTIONS = [
  {
    id: 'join',
    title: '1. Joining the queue',
    steps: [
      'You pick an exam/portal event and type your roll number on the landing page.',
      'The page fetches a small arithmetic verification challenge from the server (e.g. "4 + 7 = ?") and shows it next to a text box.',
      'You answer the challenge and submit. The server checks the roll number format, the exam ID, and the challenge answer before doing anything else — a wrong or expired answer is rejected with a clear message, and a fresh challenge is issued automatically so you can retry.',
      'Once accepted, the server places you in a first-in-first-out line for that specific exam and gives your browser a queue ticket ID (not your result — just your place in line).',
    ],
  },
  {
    id: 'waiting',
    title: '2. The live waiting room',
    steps: [
      'Your browser opens a live connection (WebSocket) to watch your position update in real time, without you refreshing the page.',
      'If your network blocks that kind of live connection (common on some institutional/mobile networks), the page automatically falls back to checking your position every few seconds instead — you don\'t have to do anything differently.',
      'The board shows your position, how many people are ahead of you, your estimated wait, and the overall system load (normal / high-load / critical), so you always know what\'s happening.',
      'You can close the tab and come back later — your place in line is kept safely (it\'s stored server-side, not in your browser), and admission continues in the background at a steady, controlled rate so the system never gets overloaded.',
    ],
  },
  {
    id: 'admission',
    title: '3. Getting admitted',
    steps: [
      'Behind the scenes, a scheduler lets a fixed, tested number of people through every couple of seconds — this is the "throttle valve" that keeps the results database from ever being hit by more traffic than it can handle.',
      'The moment you\'re admitted, the server creates a signed, time-limited digital ticket that is bound specifically to your roll number and exam — this ticket is your proof that you genuinely came through the queue.',
      'Your browser is notified instantly (or on the next status check) and automatically redirected to your result page, carrying that ticket along with it.',
      'Because the ticket expires after a short window and can\'t be reused for a different roll number, someone can\'t just copy a link and use it to check someone else\'s result, or skip the queue by guessing a roll number.',
    ],
  },
  {
    id: 'result',
    title: '4. Viewing your result',
    steps: [
      'Your browser requests your result, attaching the signed ticket from the previous step.',
      'The result service checks that the ticket is valid, unexpired, and was issued for the exact roll number being requested — if any of that doesn\'t match, the request is refused rather than shown.',
      'Once verified, your result is looked up (with a fast cache for repeat checks, since families often refresh a few times) and displayed: exam, score, pass/fail status, and when it was declared.',
      'If something goes wrong mid-lookup, you\'ll see a friendly retry message rather than a technical error — and your queue position is never lost, so you don\'t have to start over.',
    ],
  },
  {
    id: 'notify',
    title: '5. Notifications',
    steps: [
      'When you\'re admitted from the queue, an event is quietly published in the background for the notification system to pick up.',
      'That system attempts to send you a WhatsApp message first, and automatically falls back to SMS if WhatsApp delivery isn\'t available.',
      'This happens independently of your browser session — so even a slow or dropped connection on your end doesn\'t block or delay your place in the queue.',
    ],
  },
  {
    id: 'status',
    title: '6. System status indicator',
    steps: [
      'The small pill in the top-right corner of every page shows live system health: normal, high-load, or a heavier-traffic state.',
      'This is checked automatically every 15 seconds and is deliberately never blocked by rate limiting, so you can always see honest system status even during the busiest moments.',
    ],
  },
  {
    id: 'security',
    title: '7. Security measures protecting the portal',
    steps: [
      'Signed admission tickets: results can only be fetched with a valid, non-expired, single-use ticket tied to that exact roll number — direct guessing/URL-sharing, or replaying an old link, no longer works.',
      'Verification challenge: a lightweight puzzle must be solved to join the queue, raising the cost of automated scripts flooding the system.',
      'Input validation: roll numbers and exam identifiers are checked against a strict format before touching any database or cache.',
      'Tamper-evident audit trail: every queue event is chained together with cryptographic hashes, so any attempt to alter the history afterward would be detectable.',
      'Locked-down cross-origin access: only the portal\'s own known address is allowed to talk to the backend or open a live connection.',
      'Protective response headers: the site instructs browsers to block being embedded in other pages, refuses insecure content types, and enforces secure connections — standard defenses against phishing/clone attempts.',
      'Rate limiting at the edge: excess requests are throttled before they ever reach the systems holding real data, so a traffic spike degrades into a wait, never a crash.',
    ],
  },
  {
    id: 'grievance',
    title: '8. Grievance ticketing',
    steps: [
      'From your result page, or the "Grievance" button in the header, you can formally raise a query — wrong marks, a name spelling issue, a certificate problem, or anything else.',
      'Submitting gives you a reference number immediately (e.g. GRV-A1B2C3D4) — no account or login required.',
      'Use the "Track a ticket" tab with that reference number any time afterward to see its current status: Raised, Under Review, Resolved, or Rejected, along with any note left by staff.',
    ],
  },
  {
    id: 'admin',
    title: '9. Admin console (staff only)',
    steps: [
      'Exam authority staff can view live queue depth per exam and the current system load through a protected admin endpoint.',
      'The rate at which people are admitted from the queue can be adjusted live during an actual result-day event — no redeploy needed.',
      'Every admin action requires a separate admin key, kept apart from anything a regular candidate ever sees, and repeated wrong keys are automatically locked out for a period.',
      'All admin actions — like changing the admission rate or resolving a grievance — are recorded in a reviewable activity log.',
    ],
  },
  {
    id: 'offline',
    title: '10. Works even on a shaky connection',
    steps: [
      'The portal can be installed like an app on your phone or desktop for quicker access.',
      'If notifications are enabled, your browser alerts you the moment it\'s your turn, even on another tab.',
      'The core page layout stays available briefly even if your connection drops mid-wait.',
    ],
  },
  {
    id: 'help',
    title: '11. Built-in help widget',
    steps: [
      'The "?" button in the corner opens a small searchable help panel with answers to common questions.',
      'It searches a small, fixed set of curated answers rather than generating new ones, so answers stay consistent — for anything result-specific, use the grievance ticket flow instead.',
    ],
  },
]

export default function AboutModal({ onClose }) {
  const [openId, setOpenId] = useState(SECTIONS[0].id)

  return (
    <Modal title="About Sarthi Portal" eyebrow="Feature walkthrough" onClose={onClose} wide>
      <p className="modal-lead">
        Sarthi Portal is a real-time waiting-room system built so exam and
        government result days don't crash under traffic spikes. Here's
        exactly what happens, feature by feature, step by step.
      </p>

      <div className="accordion">
        {SECTIONS.map((section) => {
          const isOpen = openId === section.id
          return (
            <div className={`accordion-item ${isOpen ? 'open' : ''}`} key={section.id}>
              <button
                className="accordion-trigger"
                onClick={() => setOpenId(isOpen ? null : section.id)}
                aria-expanded={isOpen}
              >
                <span>{section.title}</span>
                <span className="accordion-caret">{isOpen ? '−' : '+'}</span>
              </button>
              {isOpen && (
                <ol className="accordion-steps">
                  {section.steps.map((step, i) => <li key={i}>{step}</li>)}
                </ol>
              )}
            </div>
          )
        })}
      </div>

      <button className="btn" style={{ marginTop: 20 }} onClick={onClose}>Close</button>
    </Modal>
  )
}
