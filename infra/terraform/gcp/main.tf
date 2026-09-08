locals {
  required_apis = [
    "run.googleapis.com",
    "sqladmin.googleapis.com",
    "redis.googleapis.com",
    "secretmanager.googleapis.com",
    "artifactregistry.googleapis.com",
    "vpcaccess.googleapis.com",
    "datamigration.googleapis.com", # backs the cross-cloud Postgres replication link below
  ]
}

resource "google_project_service" "apis" {
  for_each = toset(local.required_apis)
  project  = var.gcp_project_id
  service  = each.value
  disable_on_destroy = false
}

# ---------------------------------------------------------------------------
# Artifact Registry — mirror target for images built and pushed to Azure
# Container Registry first (see .github/workflows for the mirror step).
# ---------------------------------------------------------------------------

resource "google_artifact_registry_repository" "sarthi" {
  location      = var.gcp_region
  repository_id = "sarthi-portal"
  format        = "DOCKER"
  depends_on    = [google_project_service.apis]
}

# ---------------------------------------------------------------------------
# Secret Manager — READ-ONLY mirror of Azure Key Vault. Values are pushed
# here by the same CI secrets-sync job described in
# docs/CLOUD_DEPLOYMENT.md; Terraform only creates the containers, not the
# values, so `terraform apply` here never accidentally becomes a second
# writable source of truth for these secrets.
# ---------------------------------------------------------------------------

resource "google_secret_manager_secret" "mirrored" {
  for_each  = toset(["ticket-signing-secret", "pii-encryption-key", "admin-accounts-json", "partner-api-key", "anthropic-api-key"])
  secret_id = each.value
  project   = var.gcp_project_id

  replication {
    auto {}
  }

  depends_on = [google_project_service.apis]
}

# ---------------------------------------------------------------------------
# Cloud SQL — the promotable Postgres replica. Version MUST track
# azure/main.tf's `version = "16"` for logical replication to keep working.
# ---------------------------------------------------------------------------

resource "google_sql_database_instance" "results_replica" {
  name             = "sarthi-results-replica-${var.environment}"
  database_version = "POSTGRES_16"
  region           = var.gcp_region
  project          = var.gcp_project_id

  settings {
    tier              = var.cloud_sql_tier
    availability_type = "REGIONAL" # zone-redundant even before any cross-cloud failover
    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
    }
  }

  deletion_protection = true
  depends_on          = [google_project_service.apis]
}

# The actual replication link (Azure Postgres -> this instance) is set up
# via Database Migration Service in continuous-replication mode, since
# native `google_sql_database_instance` replica config only supports
# GCP-to-GCP read replicas, not an arbitrary external primary. DMS is the
# supported path for "replicate FROM an external Postgres source
# continuously," which is what cross-cloud replication actually is here.
resource "google_database_migration_service_connection_profile" "azure_primary" {
  connection_profile_id = "azure-postgres-primary"
  location              = var.gcp_region
  project               = var.gcp_project_id

  postgresql {
    host     = data.terraform_remote_state.azure.outputs.postgres_fqdn
    port     = 5432
    username = "sarthi_admin"
    password = var.postgres_admin_password
    ssl {
      type = "SERVER_ONLY"
    }
  }

  depends_on = [google_project_service.apis]
}

# NOTE: the DMS *migration job* resource itself (the thing that actually
# starts continuous replication using the connection profile above) needs
# the destination Cloud SQL instance to exist first and is deliberately
# left as a documented `gcloud` command in gcp/README.md rather than a
# Terraform resource — DMS jobs are stateful, long-running operations
# (start/promote/stop) that fit an operational runbook better than
# infrastructure-as-code's create/destroy model. See the runbook in
# docs/CLOUD_DEPLOYMENT.md for the promotion step.

# ---------------------------------------------------------------------------
# Memorystore — queue-service's regional Redis. Not replicated from
# Azure's Redis Cache; see docs/CLOUD_DEPLOYMENT.md for why.
# ---------------------------------------------------------------------------

resource "google_redis_instance" "queue" {
  name           = "sarthi-queue-${var.environment}"
  project        = var.gcp_project_id
  region         = var.gcp_region
  tier           = "STANDARD_HA"
  memory_size_gb = 1
  redis_version  = "REDIS_7_0"

  # SECURITY: previously had neither set, unlike Azure's Redis Cache
  # (TLS 1.2 minimum + key-based auth by default) — meaning anyone with
  # network access to this VPC could connect with no credential at all.
  auth_enabled            = true
  transit_encryption_mode = "SERVER_AUTHENTICATION" # TLS in transit

  depends_on = [google_project_service.apis]
}

resource "google_secret_manager_secret" "redis_auth_string" {
  secret_id = "redis-auth-string"
  project   = var.gcp_project_id
  replication {
    auto {}
  }
  depends_on = [google_project_service.apis]
}

resource "google_secret_manager_secret_version" "redis_auth_string" {
  secret      = google_secret_manager_secret.redis_auth_string.id
  secret_data = google_redis_instance.queue.auth_string
}

resource "google_secret_manager_secret_iam_member" "redis_auth_access" {
  for_each = toset(["queue-service", "result-service", "assistant-service"])

  project   = var.gcp_project_id
  secret_id = google_secret_manager_secret.redis_auth_string.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.services[each.value].email}"
}

# The AUTH string is generated by GCP, not chosen by us — surfaced as an
# output so it can be fed into queue-service's REDIS_PASSWORD env var
# (queue-service's RedisConfig already reads a password if present; no
# code change needed, just this value flowing through to it).
output "redis_auth_string" {
  value       = google_redis_instance.queue.auth_string
  sensitive   = true
  description = "Feed into queue-service and assistant-service's REDIS_PASSWORD env var in GCP"
}

# ---------------------------------------------------------------------------
# Serverless VPC Connector — lets Cloud Run reach Memorystore (which is
# VPC-internal-only) and lets Atlas's IP allowlist (in shared/) see a
# stable egress range for GCP.
# ---------------------------------------------------------------------------

resource "google_vpc_access_connector" "connector" {
  name          = "sarthi-vpc-connector"
  project       = var.gcp_project_id
  region        = var.gcp_region
  ip_cidr_range = "10.8.0.0/28"
  network       = "default"
  depends_on    = [google_project_service.apis]
}

# ---------------------------------------------------------------------------
# Dedicated, least-privilege service accounts — one per microservice,
# instead of every Cloud Run service implicitly running as the project's
# default compute service account (which is far broader than any single
# service needs, and is inconsistent with the Azure side, where each
# container already runs under its own azurerm_user_assigned_identity).
# ---------------------------------------------------------------------------

locals {
  service_names = ["frontend", "gateway-service", "queue-service", "result-service", "notification-service", "assistant-service"]

  # Which services need to read which mirrored secrets — mirrors the
  # secret_refs mapping in azure/main.tf so the two clouds grant the same
  # shape of access.
  secret_access = {
    queue-service         = ["admin-accounts-json", "ticket-signing-secret"]
    result-service        = ["admin-accounts-json", "ticket-signing-secret", "pii-encryption-key", "partner-api-key"]
    assistant-service     = ["admin-accounts-json", "anthropic-api-key"]
    frontend              = []
    gateway-service       = []
    notification-service  = []
  }
}

resource "google_service_account" "services" {
  for_each     = toset(local.service_names)
  project      = var.gcp_project_id
  account_id   = "sarthi-${each.value}"
  display_name = "Sarthi Portal — ${each.value} (Cloud Run)"
}

resource "google_secret_manager_secret_iam_member" "secret_access" {
  for_each = merge([
    for svc, secrets in local.secret_access : {
      for s in secrets : "${svc}-${s}" => { svc = svc, secret = s }
    }
  ]...)

  project   = var.gcp_project_id
  secret_id = google_secret_manager_secret.mirrored[each.value.secret].secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.services[each.value.svc].email}"
}

# ---------------------------------------------------------------------------
# Cloud Run — one service per microservice, mirroring the Azure Container
# Apps list exactly.
# ---------------------------------------------------------------------------

locals {
  services = {
    frontend = {
      port         = 80
      cpu          = "1"
      memory       = "512Mi"
      min_instances = 1
      max_instances = 10
      env          = {}
    }
    gateway-service = {
      port         = 8080
      cpu          = "1"
      memory       = "1Gi"
      min_instances = 1
      max_instances = 10
      env = {
        QUEUE_SERVICE_URL     = "" # filled after apply once each service's URL is known — see gcp/README.md two-pass note
        RESULT_SERVICE_URL    = ""
        ASSISTANT_SERVICE_URL = ""
        ALLOWED_ORIGINS       = var.allowed_origins
      }
    }
    queue-service = {
      port          = 8081
      cpu           = "1"
      memory        = "1Gi"
      min_instances = 1
      max_instances = 20
      env = {
        REDIS_HOST        = google_redis_instance.queue.host
        REDIS_PORT        = tostring(google_redis_instance.queue.port)
        REDIS_SSL_ENABLED = "true"
      }
    }
    result-service = {
      port          = 8082
      cpu           = "2"
      memory        = "2Gi"
      min_instances = 1
      max_instances = 15
      env = {
        POSTGRES_HOST     = google_sql_database_instance.results_replica.private_ip_address
        POSTGRES_DB       = "sarthi_results"
        REDIS_HOST        = google_redis_instance.queue.host
        REDIS_PORT        = tostring(google_redis_instance.queue.port)
        REDIS_SSL_ENABLED = "true"
      }
    }
    notification-service = {
      port          = 8083
      cpu           = "1"
      memory        = "512Mi"
      min_instances = 0
      max_instances = 5
      env           = {}
    }
    assistant-service = {
      port          = 8084
      cpu           = "1"
      memory        = "1Gi"
      min_instances = 0
      max_instances = 5
      env = {
        REDIS_HOST        = google_redis_instance.queue.host
        REDIS_PORT        = tostring(google_redis_instance.queue.port)
        REDIS_SSL_ENABLED = "true"
      }
    }
  }
}

resource "google_cloud_run_v2_service" "services" {
  for_each = local.services

  name     = each.key
  project  = var.gcp_project_id
  location = var.gcp_region
  ingress  = each.key == "frontend" ? "INGRESS_TRAFFIC_ALL" : "INGRESS_TRAFFIC_INTERNAL_ONLY"

  template {
    service_account = google_service_account.services[each.key].email

    scaling {
      min_instance_count = each.value.min_instances
      max_instance_count = each.value.max_instances
    }

    vpc_access {
      connector = google_vpc_access_connector.connector.id
      egress    = "PRIVATE_RANGES_ONLY"
    }

    containers {
      image = "${var.gcp_region}-docker.pkg.dev/${var.gcp_project_id}/${google_artifact_registry_repository.sarthi.repository_id}/${each.key}:${var.image_tag}"

      resources {
        limits = {
          cpu    = each.value.cpu
          memory = each.value.memory
        }
      }

      dynamic "env" {
        for_each = each.value.env
        content {
          name  = env.key
          value = env.value
        }
      }

      # One secret-backed env var per secret this specific service is
      # granted access to (see local.secret_access and the
      # google_secret_manager_secret_iam_member bindings above) — env var
      # name is derived from the secret ID the same way azure/main.tf
      # derives its Key Vault secret names, so both clouds' env var names
      # match exactly.
      dynamic "env" {
        for_each = lookup(local.secret_access, each.key, [])
        content {
          name = upper(replace(env.value, "-", "_"))
          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.mirrored[env.value].secret_id
              version = "latest"
            }
          }
        }
      }

      dynamic "env" {
        for_each = contains(["queue-service", "result-service", "assistant-service"], each.key) ? ["redis"] : []
        content {
          name = "REDIS_PASSWORD"
          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.redis_auth_string.secret_id
              version = "latest"
            }
          }
        }
      }

      ports {
        container_port = each.value.port
      }
    }
  }

  depends_on = [google_project_service.apis]
}

# ---------------------------------------------------------------------------
# Cloud Run's INGRESS_TRAFFIC_INTERNAL_ONLY controls WHERE a request can
# come from, but Cloud Run still requires the CALLER to be an authorized
# IAM identity separately — an internal-only service with no invoker
# grants rejects every caller, internal or not. Wire up exactly the calls
# this system actually makes: frontend's nginx proxies to gateway-service,
# and gateway-service routes to the three backend services.
# ---------------------------------------------------------------------------

locals {
  invoker_grants = {
    "frontend-to-gateway"          = { caller = "frontend", target = "gateway-service" }
    "gateway-to-queue"             = { caller = "gateway-service", target = "queue-service" }
    "gateway-to-result"            = { caller = "gateway-service", target = "result-service" }
    "gateway-to-assistant"         = { caller = "gateway-service", target = "assistant-service" }
  }
}

resource "google_cloud_run_v2_service_iam_member" "invokers" {
  for_each = local.invoker_grants

  project  = var.gcp_project_id
  location = var.gcp_region
  name     = google_cloud_run_v2_service.services[each.value.target].name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.services[each.value.caller].email}"
}
