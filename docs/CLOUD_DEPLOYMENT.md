# Cloud Deployment: Azure (primary) + GCP (active-active DR)

This document is the design for running Sarthi Portal on **Azure as the
primary cloud**, with **GCP as an active-active disaster-recovery
region**. The Terraform in `infra/terraform/` implements it.

Read the "Where 'active-active' needs an asterisk" section before the
service mapping — it's the single most important decision in this
document and it affects almost every resource choice below.

## Where "active-active" needs an asterisk

True active-active (both clouds accepting writes to the same data,
simultaneously, with no single source of truth) is straightforward for
**stateless** services and genuinely dangerous for **the result
database**. Two Postgres primaries in different clouds, both accepting
writes to the same `results`/`grievances` tables, will diverge the
moment both are written to concurrently — and for this system, a
diverged exam result is not an acceptable failure mode. Multi-master
conflict resolution ("last write wins" or similar) can silently produce
a *wrong* result being shown as authoritative. That's a worse outcome
than a slower failover.

So this design gives you active-active everywhere it's safe, and the
closest safe equivalent everywhere it isn't:

| Layer | Strategy | Why |
|---|---|---|
| Frontend, gateway, queue-service, assistant-service | **True active-active.** Both clouds run live, traffic-serving instances behind a global load balancer. | Stateless or Redis-local (queue position is naturally regional — a candidate's queue turn only needs to be consistent within the region serving them). |
| result-service reads | **Active-active.** Both clouds read from their local Postgres replica. | Reads don't have a consistency hazard. |
| result-service writes / Postgres | **Single active writer, hot standby, fast promotion.** One cloud holds the writable primary at any moment; the other continuously replicates and can be promoted in minutes, not hours. | This is what "active-active" should mean for the one dataset where correctness matters more than write availability. |
| MongoDB (notification log) | **True active-active** via a MongoDB Atlas multi-cloud replica set spanning Azure and GCP. | Atlas is designed for exactly this — a genuinely multi-cloud, multi-master-safe replica set (Mongo's replication model, unlike two independent Postgres primaries, has one real primary at a time internally but Atlas manages this transparently across clouds and handles the failover for you). |
| RabbitMQ | **Active-active** via a managed multi-region broker (CloudAMQP) rather than standing up independent brokers per cloud. | Keeps the AMQP protocol/client code in `queue-service`/`notification-service` completely unchanged, and avoids needing to hand-roll federation between two self-hosted brokers. |
| Secrets (PII key, ticket-signing secret, admin keys) | **Single source of truth, replicated read-only.** | A signing/encryption key existing in two independently-writable secret stores is a security hazard (rotation can silently desync them), not a resilience win. |

If your actual requirement is "the result lookup itself must accept
writes from both clouds simultaneously," that requires re-architecting
`result-service` around a CRDT-friendly or event-sourced write model —
a materially bigger project than a cloud migration. Flagging this now
so it's a decision made on purpose, not discovered during an incident.

## Service mapping

| Component | Azure (primary) | GCP (DR) | Notes |
|---|---|---|---|
| Frontend (nginx) | Azure Container Apps | Cloud Run | Static build, same image both places |
| gateway-service | Azure Container Apps | Cloud Run | Stateless |
| queue-service | Azure Container Apps + Azure Cache for Redis | Cloud Run + Memorystore for Redis | Redis is per-region, not replicated — see below |
| result-service | Azure Container Apps | Cloud Run | Stateless compute; see Postgres below |
| notification-service | Azure Container Apps | Cloud Run | Backed by the shared multi-cloud Mongo Atlas cluster |
| assistant-service | Azure Container Apps | Cloud Run | Stateless; degrades gracefully if `ANTHROPIC_API_KEY` unset, as already built |
| Postgres | Azure Database for PostgreSQL Flexible Server (writable primary) | Cloud SQL for PostgreSQL (standby replica, promotable) | Cross-cloud native logical replication |
| Redis (queue ZSETs) | Azure Cache for Redis | Memorystore for Redis | **Not replicated** — see below |
| MongoDB (notification log) | MongoDB Atlas (multi-cloud cluster, Azure region) | Same Atlas cluster, GCP region | One cluster, two cloud regions, Atlas-managed |
| Message broker (RabbitMQ) | CloudAMQP (hosted on Azure region) | Same CloudAMQP org, GCP region, federated | One managed multi-cloud broker instead of two |
| Secrets | Azure Key Vault (source of truth) | GCP Secret Manager (synced read replica via CI job) | See secrets section |
| Container images | Azure Container Registry | Google Artifact Registry (mirrored on push) | |
| Global routing / failover | Azure Front Door (primary control plane) | GCP Cloud Run as a registered external origin | Weighted active-active + automatic health-probe failover |

### Why Redis (queue state) is intentionally NOT cross-cloud replicated

A candidate's place in the queue is meaningful for the duration of
their session against *one* region. If Azure goes down mid-queue, the
honest, safe behavior is: Front Door routes them to GCP, they rejoin
the queue there and get a new position — not a best-effort guess at
where they "should" be based on stale replicated state. Trying to
replicate live ZSET queue positions across clouds adds real complexity
for a guarantee ("your exact queue position survives a total regional
failure") that isn't worth promising. This is called out explicitly so
it isn't mistaken for an oversight.

## Postgres cross-cloud replication (the core of the DR story)

1. **Azure Database for PostgreSQL Flexible Server** is the writable
   primary, in an Azure region close to your user base (e.g.
   `centralindia`).
2. Native Postgres **logical replication** streams from the Azure
   primary to a **Cloud SQL for PostgreSQL** instance in GCP. Both
   support standard logical replication (`wal_level = logical`,
   publication/subscription) since both are "just Postgres" underneath
   — this is not a proprietary Azure-to-GCP integration, which is what
   makes it possible at all.
3. GCP's Cloud SQL instance serves **read traffic** for `result-service`
   instances running in GCP during normal operation — so it's genuinely
   warm and continuously validated, not a cold backup you find out is
   broken during the actual incident.
4. On a declared failover: promote the GCP replica to standalone
   writable (`gcloud sql instances promote-replica` or equivalent),
   flip Front Door to send 100% of write-path traffic to GCP, and once
   Azure recovers, re-seed replication in the reverse direction before
   failing back.

**RPO**: sub-second to low-seconds under normal link health (logical
replication lag). **RTO**: the promotion step itself is minutes; the
main variable is how fast the failover is *declared* (automate the
health check, keep the promotion decision a deliberate human action for
now — see runbook below for why).

## Secrets strategy

`PII_ENCRYPTION_KEY`, `TICKET_SIGNING_SECRET`, and the admin/partner
keys live in **Azure Key Vault** as the single writable source. A
scheduled CI job (`infra/terraform` includes the resources; the sync
job itself is a small script, not Terraform, since it's a runtime
action not infrastructure) mirrors current values into **GCP Secret
Manager** as read-only replicas that `result-service`/`queue-service`
running in GCP read from. Rotating a secret always happens in Key
Vault first, then propagates — never the other direction. This avoids
the failure mode where two independently-writable secret stores drift
and only one of them still matches what's encrypted at rest.

## Global routing / failover mechanics

**Azure Front Door** is configured with an origin group containing two
origins with equal weight:
- Azure Container Apps (native Azure origin)
- The GCP Cloud Run frontend's public URL, registered as an **external
  origin** (Front Door supports non-Azure origins by hostname/IP —
  this is what makes "Azure cloud, with GCP as DR" achievable through a
  single Azure-native routing layer rather than a third-party global
  load balancer)

Both origins have active health probes against `/api/status/health`.
Weighted-equal + automatic probe-based failover gives you real
active-active traffic distribution with automatic failover if either
origin's probes start failing — Front Door stops sending traffic to a
failing origin without a human in the loop for the *routing* decision.

The **data-layer** failover (promoting GCP's Postgres replica) is
**not** automated to the same degree — see the runbook below for why.

## Failover runbook (data layer)

Automating routing failover is safe: worst case, you send some traffic
to a healthy region. Automating *Postgres promotion* is not safe to
fully automate yet, because:
- A network partition that makes Azure *unreachable from GCP* can look
  identical to "Azure is down" from GCP's side, while Azure is actually
  fine and still serving its own users — auto-promoting GCP in that
  scenario creates two writable primaries (a split-brain), which is the
  exact failure mode this whole design exists to prevent.

So: **routing fails over automatically, promotion is a deliberate,
scripted, human-triggered action.**

1. Front Door health probes detect Azure origin failures and shift
   traffic to GCP automatically (candidates start being served from
   GCP within the probe interval, on read paths and the general app).
2. On-call is paged (wire this to your existing alerting — not
   included here since it depends on your paging tool).
3. On-call confirms this is a genuine regional outage, not a network
   partition or a transient blip (checklist: Azure status page, direct
   connectivity check from a third vantage point, Azure resource health).
4. On-call runs the promotion script (`infra/terraform` sets up the
   Cloud SQL replica; the promotion command itself is an operational
   action, documented in `infra/terraform/gcp/README.md`).
5. Front Door's write-path routing (a separate, priority-based route
   for `/api/results/*` POST-equivalent/admin-write paths, distinct
   from the weighted read/general route) is flipped to GCP-only.
6. Once Azure recovers: re-establish replication Azure←GCP (reverse
   direction), let it fully catch up, then fail back in a maintenance
   window rather than automatically — the same split-brain risk applies
   symmetrically on the way back.

## What's genuinely hard about this that a diagram won't show you

- **Postgres major-version parity** must be maintained across Azure and
  GCP for logical replication to keep working — a Flexible Server minor
  upgrade on Azure needs the Cloud SQL side kept compatible.
  Cloud SQL's own automatic minor-version updates can also
  quietly break this. This needs an explicit maintenance-window
  compatibility check, not just "both auto-update."
- **Clock skew and event ordering** across two clouds' audit logs
  (`QueueAuditEvent`'s hash chain, per the security review) means the
  hash-chained tamper-evidence guarantee only holds *within* whichever
  region actually produced the events — merging two clouds' audit
  trails into one timeline needs a documented merge procedure, not an
  assumption that timestamps alone are enough.
- **Cost**: active-active roughly doubles compute spend for the
  stateless services (both regions running live capacity, not one idle
  standby) plus the Atlas/CloudAMQP multi-cloud premiums. This is the
  actual cost of the DR tier you chose — worth confirming against a
  real budget before this goes further than Terraform on paper.

## What's in `infra/terraform/`

```
infra/terraform/
  azure/    — Azure primary: Container Apps, Postgres Flexible Server,
              Redis Cache, Key Vault, Container Registry
  gcp/      — GCP DR: Cloud Run, Cloud SQL replica, Memorystore,
              Secret Manager, Artifact Registry
  shared/   — MongoDB Atlas multi-cloud cluster, CloudAMQP broker
              (both genuinely span both clouds, provisioned once)
  global/   — Azure Front Door with the GCP external origin, health
              probes, weighted routing
```

Each has its own `README.md` with apply order and prerequisites — apply
`shared/` first (Mongo/RabbitMQ need to exist before either cloud's
services can start), then `azure/`, then `gcp/` (it reads the Azure
Postgres connection details as a remote-state input to configure
replication), then `global/`.
