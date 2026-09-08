resource "azurerm_resource_group" "sarthi" {
  name     = "rg-sarthi-portal-${var.environment}"
  location = var.location
}

# ---------------------------------------------------------------------------
# Container Registry — every service's image lands here; mirrored to
# Google Artifact Registry by the CI pipeline (see .github/workflows).
# ---------------------------------------------------------------------------

resource "azurerm_container_registry" "sarthi" {
  name                = "acrsarthiportal${var.environment}"
  resource_group_name = azurerm_resource_group.sarthi.name
  location            = azurerm_resource_group.sarthi.location
  sku                 = "Standard"
  admin_enabled       = false # use managed identity below, not admin creds
}

# ---------------------------------------------------------------------------
# Key Vault — single writable source of truth for every secret this app
# needs. GCP's Secret Manager gets a read-only mirror; see shared secrets
# sync note in docs/CLOUD_DEPLOYMENT.md — that sync is a CI script, not
# Terraform, since it's a runtime data copy rather than infrastructure.
# ---------------------------------------------------------------------------

resource "azurerm_key_vault" "sarthi" {
  name                       = "kv-sarthi-${var.environment}"
  resource_group_name        = azurerm_resource_group.sarthi.name
  location                   = azurerm_resource_group.sarthi.location
  tenant_id                  = var.azure_tenant_id
  sku_name                   = "standard"
  soft_delete_retention_days = 30
  purge_protection_enabled   = true
}

resource "azurerm_key_vault_secret" "ticket_signing_secret" {
  name         = "TICKET-SIGNING-SECRET"
  value        = var.ticket_signing_secret
  key_vault_id = azurerm_key_vault.sarthi.id
}

resource "azurerm_key_vault_secret" "pii_encryption_key" {
  name         = "PII-ENCRYPTION-KEY"
  value        = var.pii_encryption_key
  key_vault_id = azurerm_key_vault.sarthi.id
}

resource "azurerm_key_vault_secret" "admin_accounts_json" {
  name         = "ADMIN-ACCOUNTS-JSON"
  value        = var.admin_accounts_json
  key_vault_id = azurerm_key_vault.sarthi.id
}

resource "azurerm_key_vault_secret" "partner_api_key" {
  name         = "PARTNER-API-KEY"
  value        = var.partner_api_key
  key_vault_id = azurerm_key_vault.sarthi.id
}

resource "azurerm_key_vault_secret" "anthropic_api_key" {
  count        = var.anthropic_api_key != "" ? 1 : 0
  name         = "ANTHROPIC-API-KEY"
  value        = var.anthropic_api_key
  key_vault_id = azurerm_key_vault.sarthi.id
}

resource "azurerm_key_vault_secret" "redis_password" {
  name         = "REDIS-PASSWORD"
  value        = azurerm_redis_cache.queue.primary_access_key
  key_vault_id = azurerm_key_vault.sarthi.id
}

# ---------------------------------------------------------------------------
# Postgres — the writable primary. See docs/CLOUD_DEPLOYMENT.md for why
# this is single-writer even though everything else here is active-active.
# ---------------------------------------------------------------------------

resource "azurerm_postgresql_flexible_server" "sarthi" {
  name                = "psql-sarthi-${var.environment}"
  resource_group_name = azurerm_resource_group.sarthi.name
  location            = azurerm_resource_group.sarthi.location
  version             = "16" # must match Cloud SQL's version in gcp/ for logical replication to work
  administrator_login = "sarthi_admin"
  administrator_password = var.postgres_admin_password

  storage_mb = 65536
  sku_name   = var.postgres_sku # e.g. "GP_Standard_D2ds_v5"

  backup_retention_days        = 14
  geo_redundant_backup_enabled = true

  # Required for logical replication to the GCP Cloud SQL replica.
  # (Azure exposes this as a server parameter, not a resource argument.)
}

resource "azurerm_postgresql_flexible_server_configuration" "wal_level_logical" {
  name      = "wal_level"
  server_id = azurerm_postgresql_flexible_server.sarthi.id
  value     = "logical"
}

resource "azurerm_postgresql_flexible_server_database" "results" {
  name      = "sarthi_results"
  server_id = azurerm_postgresql_flexible_server.sarthi.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}

resource "azurerm_postgresql_flexible_server_firewall_rule" "allow_gcp_replication" {
  name             = "allow-gcp-cloud-sql-replica"
  server_id        = azurerm_postgresql_flexible_server.sarthi.id
  start_ip_address = var.gcp_cloud_sql_egress_ip
  end_ip_address   = var.gcp_cloud_sql_egress_ip
}

# ---------------------------------------------------------------------------
# Least-privilege app role — result-service previously had no dedicated
# credential wired up in Terraform at all (only the admin login existed),
# which would have meant either it couldn't connect, or someone would
# eventually "fix" that by handing it the admin password. Neither is
# acceptable for the service that holds every candidate's result.
# ---------------------------------------------------------------------------

resource "random_password" "result_service_db_password" {
  length  = 32
  special = false # avoid characters that need escaping in a JDBC URL / env var
}

resource "postgresql_role" "result_service" {
  name     = "sarthi_app"
  login    = true
  password = random_password.result_service_db_password.result
}

resource "postgresql_grant" "result_service_schema" {
  database    = azurerm_postgresql_flexible_server_database.results.name
  role        = postgresql_role.result_service.name
  schema      = "public"
  object_type = "schema"
  privileges  = ["USAGE", "CREATE"] # CREATE is needed for Flyway to run migrations as this role
}

resource "postgresql_grant" "result_service_tables" {
  database    = azurerm_postgresql_flexible_server_database.results.name
  role        = postgresql_role.result_service.name
  schema      = "public"
  object_type = "table"
  privileges  = ["SELECT", "INSERT", "UPDATE", "DELETE"]
}

resource "postgresql_grant" "result_service_sequences" {
  database    = azurerm_postgresql_flexible_server_database.results.name
  role        = postgresql_role.result_service.name
  schema      = "public"
  object_type = "sequence"
  privileges  = ["USAGE", "SELECT"]
}

resource "azurerm_key_vault_secret" "postgres_app_password" {
  name         = "POSTGRES-APP-PASSWORD"
  value        = random_password.result_service_db_password.result
  key_vault_id = azurerm_key_vault.sarthi.id
}

# ---------------------------------------------------------------------------
# Redis — queue-service's ZSET store. Regional only, by design — see
# docs/CLOUD_DEPLOYMENT.md's note on why queue state isn't cross-cloud
# replicated.
# ---------------------------------------------------------------------------

resource "azurerm_redis_cache" "queue" {
  name                = "redis-sarthi-queue-${var.environment}"
  resource_group_name = azurerm_resource_group.sarthi.name
  location            = azurerm_resource_group.sarthi.location
  capacity            = 1
  family              = "P" # Premium — needed for the persistence + zone redundancy options below
  sku_name            = "Premium"
  minimum_tls_version = "1.2"

  redis_configuration {
    maxmemory_policy = "volatile-ttl" # queue entries and captcha challenges are TTL'd; evict those first under memory pressure
  }
}

# ---------------------------------------------------------------------------
# Container Apps environment + one app per microservice, mirroring
# docker-compose.yml's service list exactly.
# ---------------------------------------------------------------------------

resource "azurerm_container_app_environment" "sarthi" {
  name                = "cae-sarthi-${var.environment}"
  resource_group_name = azurerm_resource_group.sarthi.name
  location            = azurerm_resource_group.sarthi.location
}

locals {
  # Mirrors docker-compose.yml's service list. Kept as one map so adding a
  # new microservice (as happened with assistant-service) is a single
  # entry here rather than a new copy-pasted resource block.
  #
  # secret_refs maps ENV_VAR_NAME -> Key Vault secret ID, and is kept
  # separate from plain `env` so each service only receives the secrets
  # it actually reads (previously ADMIN_ACCOUNTS_JSON was hardcoded onto
  # every single container app below, including frontend/gateway-service/
  # notification-service, none of which ever read it — unnecessary
  # exposure of a secret to services that have no use for it).
  services = {
    frontend = {
      image_path   = "frontend"
      port         = 80
      cpu          = 0.25
      memory       = "0.5Gi"
      min_replicas = 2
      max_replicas = 10
      # NOTE: the existing frontend/nginx.conf proxies /api and /ws to the
      # docker-compose DNS alias "gateway-service:8080". Container Apps'
      # internal DNS names differ (gateway-service.internal.<env-domain>),
      # so nginx.conf needs to read this via an env-var-driven template
      # (envsubst on container start) rather than the hardcoded upstream
      # it has today — a small, real follow-up, not yet done here.
      env = {
        GATEWAY_INTERNAL_HOST = "gateway-service.internal.${azurerm_container_app_environment.sarthi.default_domain}"
      }
      secret_refs = {}
    }
    gateway-service = {
      image_path   = "gateway-service"
      port         = 8080
      cpu          = 0.5
      memory       = "1Gi"
      min_replicas = 2 # never scale to zero — this is the public entry point
      max_replicas = 10
      env = {
        QUEUE_SERVICE_URL     = "http://queue-service"
        RESULT_SERVICE_URL    = "http://result-service"
        ASSISTANT_SERVICE_URL = "http://assistant-service"
        ALLOWED_ORIGINS       = var.allowed_origins
      }
      secret_refs = {}
    }
    queue-service = {
      image_path   = "queue-service"
      port         = 8081
      cpu          = 0.5
      memory       = "1Gi"
      min_replicas = 2
      max_replicas = 20 # this is the service that has to absorb the result-day spike
      env = {
        REDIS_HOST        = azurerm_redis_cache.queue.hostname
        REDIS_PORT        = tostring(azurerm_redis_cache.queue.ssl_port)
        REDIS_SSL_ENABLED = "true"
      }
      secret_refs = {
        ADMIN_ACCOUNTS_JSON   = azurerm_key_vault_secret.admin_accounts_json.id
        REDIS_PASSWORD        = azurerm_key_vault_secret.redis_password.id
        TICKET_SIGNING_SECRET = azurerm_key_vault_secret.ticket_signing_secret.id
      }
    }
    result-service = {
      image_path   = "result-service"
      port         = 8082
      cpu          = 1.0
      memory       = "2Gi"
      min_replicas = 2
      max_replicas = 15
      env = {
        POSTGRES_HOST     = azurerm_postgresql_flexible_server.sarthi.fqdn
        POSTGRES_DB       = azurerm_postgresql_flexible_server_database.results.name
        POSTGRES_USER     = postgresql_role.result_service.name
        REDIS_HOST        = azurerm_redis_cache.queue.hostname
        REDIS_PORT        = tostring(azurerm_redis_cache.queue.ssl_port)
        REDIS_SSL_ENABLED = "true"
      }
      secret_refs = {
        ADMIN_ACCOUNTS_JSON   = azurerm_key_vault_secret.admin_accounts_json.id
        REDIS_PASSWORD        = azurerm_key_vault_secret.redis_password.id
        TICKET_SIGNING_SECRET = azurerm_key_vault_secret.ticket_signing_secret.id
        PII_ENCRYPTION_KEY    = azurerm_key_vault_secret.pii_encryption_key.id
        PARTNER_API_KEY       = azurerm_key_vault_secret.partner_api_key.id
        POSTGRES_PASSWORD     = azurerm_key_vault_secret.postgres_app_password.id
      }
    }
    notification-service = {
      image_path   = "notification-service"
      port         = 8083
      cpu          = 0.25
      memory       = "0.5Gi"
      min_replicas = 1
      max_replicas = 5
      env          = {}
      secret_refs  = {}
    }
    assistant-service = {
      image_path   = "assistant-service"
      port         = 8084
      cpu          = 0.5
      memory       = "1Gi"
      min_replicas = 0 # safe to scale to zero — every caller already handles "AI unavailable" gracefully
      max_replicas = 5
      env = {
        REDIS_HOST        = azurerm_redis_cache.queue.hostname
        REDIS_PORT        = tostring(azurerm_redis_cache.queue.ssl_port)
        REDIS_SSL_ENABLED = "true"
      }
      secret_refs = merge(
        {
          ADMIN_ACCOUNTS_JSON = azurerm_key_vault_secret.admin_accounts_json.id
          REDIS_PASSWORD      = azurerm_key_vault_secret.redis_password.id
        },
        # ANTHROPIC_API_KEY's Key Vault secret only exists at all when a
        # key was actually supplied (see its `count` argument above) —
        # merge in an empty map otherwise rather than referencing a
        # resource index that doesn't exist.
        var.anthropic_api_key != "" ? { ANTHROPIC_API_KEY = azurerm_key_vault_secret.anthropic_api_key[0].id } : {}
      )
    }
  }
}

resource "azurerm_user_assigned_identity" "container_apps" {
  name                = "id-sarthi-container-apps-${var.environment}"
  resource_group_name = azurerm_resource_group.sarthi.name
  location            = azurerm_resource_group.sarthi.location
}

resource "azurerm_key_vault_access_policy" "container_apps_read" {
  key_vault_id = azurerm_key_vault.sarthi.id
  tenant_id    = var.azure_tenant_id
  object_id    = azurerm_user_assigned_identity.container_apps.principal_id

  secret_permissions = ["Get", "List"]
}

resource "azurerm_role_assignment" "acr_pull" {
  scope                = azurerm_container_registry.sarthi.id
  role_definition_name = "AcrPull"
  principal_id         = azurerm_user_assigned_identity.container_apps.principal_id
}

resource "azurerm_container_app" "services" {
  for_each = local.services

  name                         = each.key
  resource_group_name         = azurerm_resource_group.sarthi.name
  container_app_environment_id = azurerm_container_app_environment.sarthi.id
  revision_mode                = "Single"

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.container_apps.id]
  }

  registry {
    server   = azurerm_container_registry.sarthi.login_server
    identity = azurerm_user_assigned_identity.container_apps.id
  }

  template {
    min_replicas = each.value.min_replicas
    max_replicas = each.value.max_replicas

    container {
      name   = each.key
      image  = "${azurerm_container_registry.sarthi.login_server}/${each.value.image_path}:${var.image_tag}"
      cpu    = each.value.cpu
      memory = each.value.memory

      dynamic "env" {
        for_each = each.value.env
        content {
          name  = env.key
          value = env.value
        }
      }

      # Secrets pulled from Key Vault, one `env` entry per declared
      # secret_ref — see the `secret` block below for where each of
      # these names is bound to an actual Key Vault secret ID.
      dynamic "env" {
        for_each = each.value.secret_refs
        content {
          name        = env.key
          secret_name = lower(replace(env.key, "_", "-"))
        }
      }
    }
  }

  dynamic "secret" {
    for_each = each.value.secret_refs
    content {
      name                = lower(replace(secret.key, "_", "-"))
      key_vault_secret_id = secret.value
      identity            = azurerm_user_assigned_identity.container_apps.id
    }
  }

  ingress {
    external_enabled = each.key == "frontend" # frontend's nginx is the public entry point — it proxies /api and /ws internally to gateway-service, same as the docker-compose network today
    target_port      = each.value.port
    traffic_weight {
      percentage      = 100
      latest_revision = true
    }
  }
}
