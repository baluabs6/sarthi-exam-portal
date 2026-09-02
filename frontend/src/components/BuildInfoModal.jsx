import React from 'react'
import Modal from './Modal.jsx'

export const BUILD_INFO = {
  version: '1.2.0',
  codename: 'Sarthi Portal — Hardened Build',
  builtStack: [
    ['Frontend', 'React 18 + Vite, STOMP/SockJS live updates, PWA shell'],
    ['Backend', 'Java 17, Spring Boot 3, Spring Cloud Gateway'],
    ['Queueing', 'Redis ZSET waiting room, rate-controlled admission'],
    ['Messaging', 'RabbitMQ (admission → notification/result events)'],
    ['Data', 'PostgreSQL (results, grievances), Redis (cache/queue/tickets), MongoDB (audit log)'],
  ],
  verified: [
    ['Frontend', 'Builds cleanly with npm run build (0 errors)'],
    ['docker-compose.yml', 'Valid YAML, service wiring checked'],
    ['Backend Java sources', 'Written to compile against pinned Spring Boot/Cloud versions — not compiled in this sandbox (no Maven Central egress); run mvn clean install to verify'],
  ],
  securityAdded: [
    'Signed, single-use, short-lived admission tickets — result lookups are cryptographically tied to a real queue admission and can\'t be replayed',
    'Server-verified arithmetic challenge on queue join, to slow scripted mass-joining',
    'Roll number / exam ID are pattern-validated before touching Redis or the database',
    'Tamper-evident, hash-chained audit trail for every queue event, plus a separate admin action audit trail',
    'Admin key lockout after repeated failed attempts',
    'Locked-down CORS + WebSocket origin allowlist',
    'Security response headers (CSP, HSTS, X-Frame-Options, etc.) applied at the gateway',
    'Request body size limits, PII-masked logging, non-root containers',
    'Secrets moved to environment variables with a documented .env.example',
  ],
}

export default function BuildInfoModal({ onClose }) {
  return (
    <Modal title={BUILD_INFO.codename} eyebrow={`Build ${BUILD_INFO.version}`} onClose={onClose}>
      <p className="modal-lead">
        This build adds signed admission tickets, join-time verification,
        and several other hardening changes on top of the original
        real-time queueing design. Here's what's inside.
      </p>

      <h3 className="modal-section-title">Stack</h3>
      <div className="kv-list">
        {BUILD_INFO.builtStack.map(([k, v]) => (
          <div className="kv-row" key={k}>
            <span className="kv-k">{k}</span>
            <span className="kv-v">{v}</span>
          </div>
        ))}
      </div>

      <h3 className="modal-section-title">Security hardening in this build</h3>
      <ul className="modal-list">
        {BUILD_INFO.securityAdded.map((line) => <li key={line}>{line}</li>)}
      </ul>

      <h3 className="modal-section-title">What's been verified</h3>
      <div className="kv-list">
        {BUILD_INFO.verified.map(([k, v]) => (
          <div className="kv-row" key={k}>
            <span className="kv-k">{k}</span>
            <span className="kv-v">{v}</span>
          </div>
        ))}
      </div>

      <button className="btn" style={{ marginTop: 20 }} onClick={onClose}>Got it</button>
    </Modal>
  )
}
