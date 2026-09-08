# GCP module — DR

Applies after `shared/` and `azure/` (reads Azure's Postgres FQDN from
remote state). See `../README.md` for the full apply order.

## Setting up cross-cloud Postgres replication (one-time, after `apply`)

Terraform creates the Cloud SQL instance and the DMS connection profile
pointing at Azure's Postgres, but starting continuous replication is a
`gcloud` operation, not a Terraform resource (DMS migration jobs are
long-running stateful operations — start/promote/stop — that don't fit
create/destroy):

```bash
# 1. Create the continuous migration job using the connection profile
#    Terraform already created.
gcloud database-migration migration-jobs create sarthi-results-sync \
  --region=asia-south1 \
  --type=CONTINUOUS \
  --source=azure-postgres-primary \
  --destination=sarthi-results-replica-production \
  --destination-database=sarthi_results

# 2. Start it.
gcloud database-migration migration-jobs start sarthi-results-sync \
  --region=asia-south1

# 3. Verify lag before trusting it — check this regularly, not just once.
gcloud database-migration migration-jobs describe sarthi-results-sync \
  --region=asia-south1 --format="value(phase,state)"
```

## Promotion runbook (only during a DECLARED failover — see
docs/CLOUD_DEPLOYMENT.md for why this is a human action, not automatic)

```bash
# 1. Stop replication and promote the replica to standalone-writable.
gcloud database-migration migration-jobs promote sarthi-results-sync \
  --region=asia-south1

# 2. Confirm it's now writable.
gcloud sql instances describe sarthi-results-replica-production \
  --format="value(instanceType)"
# should now read "CLOUD_SQL_INSTANCE" (standalone), not "READ_REPLICA_INSTANCE"

# 3. Point result-service's Cloud Run instances at the now-writable
#    instance (if they weren't already reading from it) and flip
#    global/'s write-path origin priority — see docs/CLOUD_DEPLOYMENT.md's
#    runbook step 5.
```

## Failing back once Azure recovers

Re-run the DMS setup in reverse (source = the now-writable GCP instance,
destination = Azure Postgres, freshly re-provisioned or restored from
backup), let it fully catch up, then schedule the actual cutover as a
maintenance window — not immediately, and not automatically. Two
writable primaries existing even briefly during a rushed failback is the
same split-brain risk as an automated failover, just in the other
direction.
