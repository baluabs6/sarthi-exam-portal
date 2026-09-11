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


## How this is different from other exam/result portals

Most government or institutional exam-result sites are built as a single
web app talking directly to one database, sized for average traffic. They
work fine until result day, when millions of people refresh the same page
in the same few minutes — at which point the database connection pool
saturates and the site either slows to a crawl or goes down entirely.
Sarthi Portal is architected specifically around that one failure mode,
rather than treating it as an edge case to patch later:

- **The bottleneck is engineered away, not just rate-limited.** A typical
  portal puts a rate limiter or a CDN cache in front of the *same*
  database-backed app. Here, the component that absorbs the traffic spike
  (`queue-service`) is structurally incapable of touching Postgres at
  all — it only ever talks to Redis. There is no code path by which a
  result-day surge can reach the database faster than the admission rate
  allows, regardless of how much traffic arrives.
- **The wait is visible, not silent.** Instead of a spinning loader or a
  generic "high traffic, try again later" error, users are placed in an
  actual live queue with a real-time position, pushed over WebSocket. A
  traffic spike degrades gracefully into a bounded, honest wait instead of
  timeouts or crashes.
- **The admission rate is a live, tunable knob, not a fixed limit baked
  into deploy-time config.** Admins can raise or lower how many people are
  let through per tick while the system is running, based on what the
  database can actually handle in the moment — no redeploy required.
- **Side effects are decoupled through messaging.** Sending a
  WhatsApp/SMS notification or pre-warming a cache entry happens over
  RabbitMQ, so a slow or failing third-party notification provider can
  never block someone's place in the queue — a coupling that takes down
  many simpler portals when an SMS gateway has a bad day.
- **Security and integrity are treated as first-class, not bolted on
  after a breach.** Signed, single-use, resource-scoped admission
  tickets; a hash-chained tamper-evident audit trail; PII encryption at
  rest; SSRF-hardened webhooks; and a documented, honestly-scoped set of
  what's implemented versus deliberately deferred — most reference builds
  in this space don't hold themselves to that same standard of anti-abuse
  and auditability.
- **It's explicit about what it doesn't fake.** AI-adjacent features in
  this build are real but modest (statistics, extractive summarization,
  browser-native speech) — nothing is dressed up as "AI-powered" if it's
  actually a fixed rule or a keyword list. Many portals overstate this;
  this one documents the honest boundary.

In short: rather than being a CRUD app with a cache in front of it, Sarthi
Portal is designed from the ground up around the one moment that breaks
most exam-result sites — the synchronized rush of everyone checking at
once — and treats that as the core engineering problem, not an
afterthought.
