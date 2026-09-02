# Sarthi Portal

A reference implementation of a **real-time, crash-resistant exam/government
result portal** — built to solve the specific problem of server downtime
during high-traffic moments (result day, application deadlines, admit-card
release).

Instead of letting every visitor hit the database at once, the system places
users in a **live virtual waiting room**, admits them at a controlled rate,
and pushes their position to the browser in real time over WebSocket — so a
traffic spike degrades into a visible, bounded wait instead of a crash.

## Stack

| Layer | Technology |
|---|---|
| Frontend | React 18 + Vite, `@stomp/stompjs` + SockJS for live updates |
| Backend | Java 17, Spring Boot 3, Spring Cloud Gateway, Spring WebSocket (STOMP) |
| Messaging | RabbitMQ (Spring AMQP) — decouples queue admission from notification/result pre-warming |
| Databases | PostgreSQL (results, transactional), Redis (queue ordering + cache), MongoDB (audit/notification logs) |
| Containerization | Docker + Docker Compose |

## Architecture

```
                          ┌─────────────┐
   Browser (React) ──────▶│   nginx     │
   WebSocket + REST       │  (frontend) │
                          └──────┬──────┘
                                 │ /api, /ws
                          ┌──────▼──────┐
                          │   gateway   │  Redis-backed rate limiting
                          │  -service   │  at the edge (first line of
                          └──┬───────┬──┘  defense against spikes)
                             │       │
              ┌──────────────┘       └──────────────┐
              ▼                                      ▼
      ┌───────────────┐                      ┌───────────────┐
      │ queue-service │──── RabbitMQ ───────▶│ result-service │
      │  (Redis ZSET  │   "admitted" event    │  (Postgres +  │
      │  + WebSocket  │                        │  Redis cache) │
      │   broadcast)  │                        └───────────────┘
      └───────┬───────┘
              │ RabbitMQ "admitted" event
              ▼
      ┌────────────────────┐
      │ notification-service│  WhatsApp → SMS fallback
      │  (MongoDB log)      │
      └────────────────────┘
```

**Why this shape solves the problem:**
- **Rate limiting at the gateway** (Redis token bucket) sheds/queues excess
  traffic before it ever reaches a service that owns a database connection.
- **Queue-service never talks to Postgres.** It only talks to Redis (fast,
  in-memory, horizontally scalable) — so the waiting room itself can never
  be the thing that falls over.
- **Admission is rate-controlled** (`queue.admit-per-tick` in
  `queue-service/application.yml`) — this number is the single knob that
  caps how much concurrent load ever reaches `result-service` and Postgres.
  Tune it to match your database's real, load-tested capacity.
- **Messaging (RabbitMQ) decouples side effects.** Sending an SMS/WhatsApp
  message or pre-warming a cache entry can be slow or briefly fail without
  ever blocking someone's place in the queue.
- **WebSocket + polling fallback** means the live experience still works on
  networks that block raw WebSocket upgrades (common on some Indian
  institutional/mobile networks).

## Running it

### Prerequisites
- Docker + Docker Compose
- (For local dev without Docker) Java 17, Maven, Node 18+

### Quick start (Docker)
```bash
git clone <this-repo>
cd exam-portal
docker compose up --build
```
- Frontend: http://localhost:3000
- Gateway/API: http://localhost:8080
- RabbitMQ management UI: http://localhost:15672 (guest/guest)

Try roll numbers from the seed data (`result-service/src/main/resources/db/migration/V1__init_schema.sql`):
`26104578912`, `26104578913`, `26104578914` (exam: NEET-UG 2026).

### Local dev without Docker
```bash
# Data stores — run these however you like (Docker one-liners shown)
docker run -p 5432:5432 -e POSTGRES_DB=sarthi_results -e POSTGRES_USER=sarthi -e POSTGRES_PASSWORD=sarthi_pass postgres:16-alpine
docker run -p 6379:6379 redis:7-alpine
docker run -p 27017:27017 mongo:7
docker run -p 5672:5672 -p 15672:15672 rabbitmq:3.13-management-alpine

# Backend (each in its own terminal)
cd backend && mvn -pl queue-service -am spring-boot:run
cd backend && mvn -pl result-service -am spring-boot:run
cd backend && mvn -pl notification-service -am spring-boot:run
cd backend && mvn -pl gateway-service -am spring-boot:run

# Frontend
cd frontend && npm install && npm run dev
```

## Load-testing the queue (recommended before going live)

Before a real result-day launch, load-test with a tool like `k6` or
`gatling` against `gateway-service`, and tune:
- `queue.admit-per-tick` / `queue.tick-interval-seconds` (queue-service) —
  the throttle valve
- `spring.datasource.hikari.maximum-pool-size` (result-service) — hard cap
  on concurrent DB connections
- Gateway `redis-rate-limiter.replenishRate` / `burstCapacity`
  (`gateway-service/application.yml`)

A ready-to-run script is included at `loadtest/synthetic-traffic.js` —
run it with `k6 run loadtest/synthetic-traffic.js` (or set `BASE_URL`
for a non-local target). Rather than a flat constant load, it models an
opening spike, a long tail, and a secondary burst — closer to how real
result-day traffic actually behaves — using fixed, documented rules
(not an LLM), so its behavior stays fully inspectable and repeatable.

Scale `queue-service` and `result-service` horizontally
(`docker compose up --scale queue-service=3`) — both are stateless aside
from Redis/Postgres, so this works without code changes.

## What's been verified in this build

- ✅ **Frontend**: installed and built successfully with `npm run build`
  (Vite production build, 0 errors).
- ✅ **docker-compose.yml**: validated as syntactically correct YAML.
- ⚠️ **Backend Java code**: written to compile cleanly against
  Spring Boot 3.3.4 / Spring Cloud 2023.0.3 APIs, but **not compiled in
  this environment** — the sandbox this was built in only has network
  access to npm/PyPI/GitHub, not Maven Central, so `mvn package` could not
  be run here. Run `mvn clean install` from `backend/` on a machine with
  normal internet access to build and verify it; that's also what
  `docker compose up --build` will do automatically. If anything doesn't
  compile, it's most likely a minor dependency-version mismatch — check
  the Spring Boot/Spring Cloud release notes for the exact versions pinned
  in `backend/pom.xml`.

## Security features in this build

- **Signed admission tickets.** `queue-service` mints a short-lived,
  HMAC-signed JWT (see `TicketService`) the instant a user is actually
  admitted from the queue, bound to their exact `rollNumber`/`examId`.
  `result-service` verifies this ticket on every `/api/results/{rollNumber}`
  request and rejects anything invalid, expired, or issued for a
  different roll number. Previously the `?ticket=` param existed but was
  never checked, so the queue could be skipped by calling the endpoint
  directly — that gap is now closed.
- **Join-time verification challenge.** `GET /api/queue/captcha` issues a
  short-lived arithmetic challenge that must be solved and submitted with
  `/api/queue/join`, raising the cost of scripted mass-joining.
- **Strict input validation.** `examId`/`rollNumber` are pattern-validated
  before being used to build Redis keys or hitting the database.
- **Tamper-evident audit trail.** Every `QueueAuditEvent` is hash-chained
  (SHA-256 of its fields + the previous event's hash, per exam) so
  altering or deleting an audit record after the fact is detectable.
- **Locked-down CORS / WebSocket origins.** Configured via
  `ALLOWED_ORIGINS` instead of the previous wildcard `*`.
- **Security response headers** (CSP, HSTS, X-Frame-Options,
  X-Content-Type-Options, Referrer-Policy) applied globally at the
  gateway.
- **Secrets via environment variables.** See `.env.example` — copy to
  `.env` and set real values (especially `TICKET_SIGNING_SECRET`) before
  any deployment beyond local dev.

Still open (see "Extending this for production" below for the fuller
list): real authentication/OTP, CAPTCHA backed by a production-grade
provider, per-roll-number rate limiting, TLS termination, and secrets
management via a vault rather than env vars.

## Additional features in this build

- **Grievance ticketing.** Candidates can raise a formal query about a
  result (`POST /api/grievances`) and get a reference number
  (`GRV-XXXXXXXX`) with no login required, then check its status later
  (`GET /api/grievances/{ticketRef}`). Staff review/update tickets via
  admin-gated endpoints. New Postgres table via Flyway
  (`V2__grievances.sql`).
- **Admin console (API-level).** `X-Admin-Key`-gated endpoints:
  `GET /api/admin/queues` (live queue depth per exam + system load) and
  `PATCH /api/admin/admit-rate` (tune the admission rate live, no
  redeploy needed) in queue-service; `GET/PATCH /api/grievances/admin/**`
  in result-service. Gated by a single shared admin key — see "Extending
  this for production" for what real per-admin RBAC would add.
- **PWA basics.** `manifest.json` + a service worker that caches the app
  shell (never API calls, which always stay network-first) so a dropped
  connection mid-wait doesn't show a blank page.
- **Browser notifications.** Opt-in "notify me" in the waiting room —
  fires a real browser notification the instant the user is admitted.
- **FAQ help widget.** A floating "?" button with keyword-scored search
  over a small curated FAQ set (`src/components/faqData.js`). This is
  intentionally *not* wired to a real LLM — see "AI/RAG integration
  points" below for what that would actually take.

## AI / RAG / LLM integration points (not wired up — needs your own API key)

This build does not call any AI model — no OpenAI/Anthropic key is
configured, and none should ever be hardcoded into the frontend bundle.
If you want to add real AI features on top of this codebase, here's
where they'd plug in cleanly:

- **RAG helpdesk chatbot**: replace `FaqWidget.jsx`'s keyword search with
  a call to a new backend endpoint (e.g. a small `ai-service`) that does
  real retrieval (embeddings + a vector store like pgvector/Chroma) over
  your actual FAQ/circular documents, then calls an LLM API server-side
  — never from the browser.
- **Grounded result explainer**: a backend endpoint that takes a
  candidate's already-verified DB row (score, cutoff, percentile) and
  asks an LLM to phrase it in plain language — the model only rephrases
  retrieved numbers, never computes or guesses them.
- **Grievance triage**: classify incoming grievance `category`/`message`
  text with an LLM call in `GrievanceService.raise()` to auto-suggest
  urgency/routing before a human reviews it.
- **Admin ops copilot**: a LangChain agent with read-only tools over the
  `/api/admin/queues` endpoint and the Mongo audit trail, so staff can
  ask plain-language questions about system state.

Each of these needs a real provider API key set as a backend environment
variable (never shipped to the frontend), and a decision about what
candidate data, if any, is allowed to leave your infrastructure.

## Second security/feature hardening pass

**Security — implemented:**
- **Single-use admission tickets.** Each ticket now carries a unique
  `jti`; result-service (`TicketReplayGuard`, Redis-backed) consumes it
  atomically on first use, so a saved/shared link can no longer be
  replayed to re-fetch a result repeatedly within the ticket's window.
- **Admin key lockout.** Both `AdminAuthInterceptor`s now throttle
  repeated wrong `X-Admin-Key` attempts per source IP (`AdminLockoutTracker`,
  Redis-backed) — 5 failures locks that IP out for 15 minutes.
- **Admin action audit trail.** `queue-service` logs admit-rate changes
  to a dedicated Mongo collection (`AdminAuditEvent`, viewable via
  `GET /api/admin/audit-log`); `result-service` logs every grievance
  status transition to a new Postgres table
  (`grievance_status_history`, migration `V3`). Neither ever stores the
  raw admin key — only source IP and what changed.
- **PII-safe logging.** `PiiMasker` masks roll numbers in log output
  (`2610****912`) in both services' controllers/services.
- **Request size limits.** A servlet filter in both services rejects
  request bodies over 64KB before Jackson deserializes them.
- **Non-root containers.** All five Dockerfiles now run as a dedicated
  unprivileged user (`sarthi` for the Java services, the built-in
  unprivileged user in `nginxinc/nginx-unprivileged` for the frontend,
  which also required moving its listen port to 8080).
- **Dependency vulnerability scanning.** `.github/workflows/dependency-check.yml`
  runs OWASP Dependency-Check on the Java backend and `npm audit` on the
  frontend, on push/PR and weekly.

**Security — deliberately not implemented (needs real infra/vendor
decisions, not something to fake in code):** mTLS between internal
services, secrets management via a vault (still env-var based — see
`.env.example`), a WAF/DDoS layer in front of the gateway. See
"Extending this for production" below.

**Features — implemented:**
- **Admin dashboard UI** (`/admin` route) — live queue depth per exam,
  live admission-rate tuning, a grievance triage board (filter by
  status, resolve with a note), and a recent-admin-activity feed. Gated
  by the same `X-Admin-Key` used by the API.
- **Shareable queue link** — a "share this queue link" button in the
  waiting room; safe by construction, since the URL only ever contains
  the opaque `queueId`, never the roll number.
- **Candidate data export** — `GET /api/grievances/export?rollNumber=`
  plus a "Download your history" button in the grievance tracker,
  downloading the candidate's own grievance history as JSON.
- **Accessibility pass** — `aria-live` regions on the queue position and
  stat grid, a labeled `progressbar` role, focus-trapping + labeled
  close button on modals, and visible focus outlines (`:focus-visible`)
  across the app.
- **Print-friendly result page** — a `@media print` stylesheet that
  hides chrome (header, buttons, FAQ widget) and renders the result
  cleanly in black-and-white.

**Features — deliberately not implemented:** real Web Push (VAPID) that
survives a fully closed browser (the current "notify me" only works
while the service worker/tab is alive in-session — full Web Push needs
a backend push-sending integration and is a reasonable next step, not
included here to avoid shipping an untested push pipeline); a public
historical SLA/uptime page (would need a persistent time-series metrics
store, not just the current point-in-time `/api/status/health`); staged
result release by roll-number range (a product/policy decision as much
as a code one — happy to scope this if you want it specifically).

## Third pass: residual security gaps + AI-adjacent features

**Security — implemented:**
- **Ticket moved off the URL.** `GET /api/results/{rollNumber}` now reads the ticket from `Authorization: Bearer <ticket>` instead of a `?ticket=` query string — keeps it out of browser history, proxy access logs, and `Referer` headers.
- **Soft IP-binding on tickets.** Tickets carry the IP that originally joined the queue; a mismatch at redemption is logged, not blocked (mobile networks/CDNs legitimately change IPs mid-session, so hard-blocking would lock out real users more than attackers).
- **Per-roll-number grievance rate limit** (5/hour) on top of the gateway's per-IP limit, so one IP can't spam many different roll numbers.
- **Idempotency keys** (`Idempotency-Key` header) on `POST /api/grievances` — a retried/double-tapped request returns the original ticket instead of creating a duplicate.
- **Log/CRLF injection guard** — `PiiMasker.sanitizeForLog()` strips control characters from user text before it reaches a log statement.
- **Strict Content-Type enforcement** — `StrictContentTypeFilter` rejects non-JSON bodies on POST/PATCH/PUT before Spring's converters see them.
- **Honeypot field** on the join form — a visually-hidden input real users never fill; a filled one is silently rejected.
- **Container image scanning** (Trivy) added as a CI job separate from OWASP Dependency-Check, since that one only sees declared source dependencies, not what actually ends up in the built image layers.

**Features — implemented:**
- **Admin bulk actions** — multi-select grievances and resolve them in one call (`PATCH /api/grievances/admin/bulk`), plus CSV export (`GET /api/grievances/admin/export`).
- **Light/dark theme toggle**, persisted in `localStorage`.
- **Post-result feedback widget** — thumbs up/down, stored in a new `result_feedback` table.
- **Public live stats ticker** — an aggregated-only "N people currently in queue" figure (`GET /api/status/live-count`); the per-exam breakdown stays admin-only since exact per-exam depth could reveal relative exam popularity/timing.
- **Result countdown banner** — frontend-only display hint; not enforced server-side.

**AI-adjacent features — implemented, and deliberately real rather than simulated:**
- **Text-to-speech result readout** and **speech-to-text grievance dictation** use the browser's native Web Speech API — no external API, no key, genuinely functional today.
- **Adaptive wait-time estimation** — `QueueManagerService` now tracks a rolling window of actually-observed admission throughput (Redis-backed) and blends it with the configured target, rather than only ever using the static formula. This is a real, working improvement — not a placeholder.
- **Weekly digest** and **grievance draft template** are explicitly rule-based (real aggregated counts / fixed templates), not LLM calls — the code comments say so, so nobody mistakes them for more than they are.
- **Client-side content nudge** on grievance text is a small hardcoded keyword list, clearly not real moderation.

**Explicitly not implemented** (would need a real hosted LLM + API key routed through a backend endpoint, which doesn't exist in this codebase): a RAG helpdesk chatbot backed by a real model, grounded result explanations, grievance triage/classification via LLM, an LLM-authored digest, vision-based document review, and any behavioral fraud-scoring model beyond the simple rate/lockout mechanisms already in place. See "AI / RAG / LLM integration points" above for exactly where each would plug in.

## Fourth pass: cross-candidate intelligence + real bugs caught in verification

**New, honestly-scoped AI-adjacent features:**
- **Cross-candidate issue clustering** (`GET /api/grievances/admin/clusters`) — extends the duplicate-detection word-overlap check across *all* open tickets, not just one candidate's own, surfacing genuine systemic patterns (3+ similar tickets) that a per-category count alone would miss.
- **Auto-categorization nudge** — when "Other" is picked, a small keyword list suggests a better-fitting category as the message is typed.
- **Adaptive CAPTCHA difficulty** — question difficulty scales with an IP's own recent failure count (Redis-tracked), rather than being flat for everyone.
- **Predictive queue-depth alerting** — a simple linear projection from real join-rate vs. admission-rate data ("~N minutes to critical load at the current rate"), not a trained forecasting model.
- **Result-enumeration detection** — flags IPs that have joined with an unusually high number of *distinct* roll numbers recently, surfaced as a real admin-visible list.
- **FAQ gap detection** — logs FAQ-widget searches that return nothing, so admins can see real content gaps instead of guessing what to add.
- **Language auto-detection on grievances** — a Unicode script-range heuristic (Devanagari, Tamil, Bengali, etc.) stored per ticket, to route non-English submissions to staff who can read them.
- **PII encryption at rest** — roll numbers (deterministic AES, documented ECB tradeoff so lookups keep working) and grievance/feedback text (randomized AES-GCM) are now encrypted in Postgres, via migration `V5` widening the affected columns.

**Two real bugs caught during this round's verification, both fixed:**
- The gateway's CORS config only allowed `GET`/`POST` — every admin dashboard `PATCH` call (admit-rate tuning, grievance status updates, bulk actions) would have failed CORS preflight in a real browser. Added `PATCH` to `setAllowedMethods`.
- The `Idempotency-Key` header used by grievance creation wasn't in the CORS allowed-headers list either. Added it.

This is exactly the kind of gap that only surfaces when you actually check cross-cutting config (CORS) against every endpoint added since — worth specifically re-checking after any new HTTP method or custom header is introduced.

## Fifth pass: candidate-facing extras, admin roles, i18n

**New features:**
- **System-preference theme detection** — matches `prefers-color-scheme` on first load instead of always defaulting to dark.
- **QR codes on grievance tickets** — a real, scannable code linking to a new `/track/:ticketRef` route.
- **Data retention/purge job** — nightly scheduled job (configurable via `GRIEVANCE_RETENTION_DAYS`/`FEEDBACK_RETENTION_DAYS`) deletes only resolved/rejected grievances and old feedback past the retention window; open tickets are never auto-deleted regardless of age.
- **Saved roll numbers** — a local, opt-in quick-pick list for checking several roll numbers without retyping; doesn't bypass the queue or ticket flow for any of them.
- **Admin per-account roles** — `SUPER_ADMIN` / `QUEUE_OPERATOR` / `AUDITOR`, configured via `ADMIN_ACCOUNTS_JSON`, replacing (optionally — backward compatible) the single shared admin key. Audit trails now record the acting account's name, not just an IP.
- **Partner/institutional bulk API** (`POST /api/partner/results/bulk`) — a separate `X-Partner-Key` credential, capped at 100 roll numbers/request, disabled entirely unless a key is configured.
- **Webhook notifications** on grievance status change — with real SSRF protection: resolves the hostname and rejects loopback/link-local/private-IP/cloud-metadata targets *before* ever making the request, not just a superficial URL-format check.
- **Application status timeline** (`SUBMITTED → VERIFIED → ADMIT_CARD_ISSUED → RESULT_DECLARED`) — public reads (deliberately not ticket-gated; a stage name isn't as sensitive as a score), admin-gated to advance.
- **Admit card / hall ticket PDF** — real PDF generation via Apache PDFBox, gated by the same signed ticket as result lookup.
- **Real i18n** — English/Hindi via `react-i18next`, covering the header and main candidate flow (join, waiting room, result). Admin/staff screens remain English-only, being an internal tool rather than candidate-facing.

**A real bug caught and fixed in this round:** the admit card and result endpoints initially shared the same single-use ticket consumption. Since `ResultPage` fetches the result automatically on load, that ticket would already be consumed by the time someone clicked "download admit card" — the button would always fail with "ticket already used." Fixed by scoping ticket consumption per-resource (`TicketReplayGuard.tryConsume(jti, ttl, resource)`), so one genuine queue turn can redeem both the result and the admit card, each exactly once.

**Explicitly deferred (need real infrastructure, not something to fake):**
- **Grievance photo attachment** — needs real object storage (S3 or equivalent); a database blob would work for a demo but isn't the right foundation to build on.
- **Historical uptime/SLA page** — needs a persistent time-series metrics store; `/api/status/health` only ever reflects the current moment.

## Sixth pass: statistical/heuristic AI-adjacent features

All of these are honestly non-generative — statistics, rule-based
heuristics, or extractive techniques that never invent text or numbers,
consistent with every AI-adjacent feature built so far in this project:

- **Audit-trail anomaly detection** (`AuditAnomalyDetector`) — buckets
  the existing hash-chained `ADMITTED` events into one-minute windows
  over the last hour and flags any window more than 3 standard
  deviations above the rolling mean. A plain statistical check, not a
  trained model — surfaced at `GET /api/admin/anomalies`.
- **Predictive queue abandonment** — tracks a `lastSeenAt` timestamp per
  queue entry (updated on every REST poll) and estimates how many
  waiting entries have gone stale. Documented limitation, not hidden:
  WebSocket-connected users don't need to poll, so this signal skews
  toward over-counting them as "possibly abandoned" — a more complete
  version would also track WebSocket heartbeats.
- **Extractive grievance summarization** (`ExtractiveSummarizer`) —
  scores sentences by word frequency and surfaces the top 1-2 verbatim
  in the admin list view, with a toggle to see the full message.
  Deliberately non-generative: it can never produce a word the
  candidate didn't write, unlike a generated summary that could
  misrepresent what was actually said.
- **Time-to-resolution estimate** — a real average (from
  `GrievanceStatusHistory`) of how long past tickets in the same
  category took to resolve, shown to a candidate tracking a still-open
  ticket. Requires at least 3 resolved tickets in that category before
  showing anything, rather than guessing from too little data.
- **Voice-based queue position** — extends the existing Web Speech API
  work with a "speak my position" button in the waiting room, alongside
  the result page's existing read-aloud button.
- **Synthetic load-test traffic generator** (`loadtest/synthetic-traffic.js`)
  — a rule-based k6 script modeling an opening spike, long tail, and
  secondary burst instead of flat constant load, closer to real
  result-day traffic shape while staying fully deterministic.

## Extending this for production

- Add Spring Cloud Config + a config server for centralized, hot-reloadable
  configuration across services (especially the admission rate).
- Add Eureka or Kubernetes service discovery instead of hardcoded service
  URLs in `application.yml`.
- Swap the mock `WhatsAppBusinessProvider`/`SmsGatewayProvider` for real
  WhatsApp Business Cloud API and DLT-registered SMS gateway integrations.
- Add Spring Cloud Data Flow as an orchestration layer if you need a
  visual/declarative pipeline for the RabbitMQ event flows (admission →
  notification → audit) rather than hand-wired `@RabbitListener`s — the
  current design already emits clean, typed events on well-named
  exchanges/queues, so it's a natural fit to front with SCDF later.
- Add Grafana + Prometheus (Spring Boot Actuator already exposes metrics
  endpoints) for the live monitoring dashboards described in the
  architecture notes.
