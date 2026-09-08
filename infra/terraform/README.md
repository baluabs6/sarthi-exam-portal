# Terraform: Azure primary + GCP active-active DR

Read `docs/CLOUD_DEPLOYMENT.md` first — it explains *why* each of these
modules is shaped the way it is, especially the Postgres single-writer
decision inside an otherwise active-active design.

**I could not run `terraform init`/`plan`/`validate` in the sandbox that
wrote this** — it has no network access to `registry.terraform.io` or
the Azure/GCP/Mongo/CloudAMQP APIs. Every `.tf` file here is
hand-reviewed for correct HCL syntax and provider argument names as of
each provider's documented schema, but **please run `terraform validate`
and `terraform plan` yourself before applying anything** — treat this as
a strong first draft, not a pre-verified deployment.

## Apply order

State is intentionally split into four independent state files so a
`plan`/`apply` in one cloud can never accidentally touch resources in
the other:

```
1. shared/   — MongoDB Atlas (spans both clouds) + CloudAMQP broker
2. azure/    — Azure primary: Container Apps, Postgres, Redis, Key Vault, ACR
3. gcp/      — GCP DR: reads azure/'s state (Postgres FQDN) to configure
               the cross-cloud replication link; Cloud Run, Cloud SQL,
               Memorystore, Secret Manager
4. global/   — Front Door: reads BOTH azure/'s and gcp/'s state to
               register both frontends as origins
```

Each subdirectory needs its own `terraform init -backend-config=...`
with your actual storage account / GCS bucket — nothing is hardcoded so
this repo doesn't accidentally point at a real backend that isn't yours.

```bash
# Example for azure/ — same pattern for the others
cd infra/terraform/azure
terraform init \
  -backend-config="resource_group_name=rg-tfstate" \
  -backend-config="storage_account_name=<your storage account>" \
  -backend-config="container_name=tfstate"
terraform plan -out=plan.tfplan
terraform apply plan.tfplan
```

## What's deliberately NOT in Terraform

- **The actual DMS replication job** (start/promote/stop for the
  cross-cloud Postgres link) — see `gcp/README.md`. It's a stateful
  operational action, not a create/destroy resource.
- **Secret *values* syncing from Key Vault to Secret Manager** — a small
  CI script, not infrastructure. Terraform only creates the empty
  Secret Manager containers in `gcp/main.tf`.
- **The failover decision itself** — see the runbook in
  `docs/CLOUD_DEPLOYMENT.md`. Routing failover is automatic (Front Door
  health probes); Postgres promotion is a deliberate human action, on
  purpose.
- **CI/CD pipeline changes** to actually build+push images to both
  ACR and Artifact Registry — `.github/workflows/dependency-check.yml`
  exists for scanning only; a deploy workflow would need to be added
  separately and isn't part of this Terraform.

## Required variables you'll need to supply

Every module's `variables.tf` lists what it needs; nothing has a secret
baked in as a default. At minimum, gather:
- An Azure subscription + tenant ID, with a storage account for TF state
- A GCP project ID, with a GCS bucket for TF state
- A MongoDB Atlas organization ID + API keys (set as provider
  credentials via environment variables, not committed here)
- A CloudAMQP account
- The same secret values already in this repo's `.env` (ticket signing
  secret, PII encryption key, admin accounts JSON, partner API key) —
  reuse them or rotate first, your call
