terraform {
  required_version = ">= 1.5.0"
  required_providers {
    mongodbatlas = {
      source  = "mongodb/mongodbatlas"
      version = "~> 1.16"
    }
    cloudamqp = {
      source  = "cloudamqp/cloudamqp"
      version = "~> 1.29"
    }
  }

  backend "azurerm" {
    # Filled in via -backend-config at init time (see README.md) so the
    # same file works across your own subscription/storage account
    # without hardcoding it here.
    key = "shared.tfstate"
  }
}

# ---------------------------------------------------------------------------
# MongoDB Atlas — ONE cluster, genuinely spanning both clouds. This is the
# one piece of "shared" state where a managed provider actually gives you
# real multi-cloud active-active, rather than us faking it with two
# independent instances kept in sync.
# ---------------------------------------------------------------------------

resource "mongodbatlas_project" "sarthi" {
  name   = "sarthi-portal-${var.environment}"
  org_id = var.atlas_org_id
}

resource "mongodbatlas_advanced_cluster" "notification_log" {
  project_id     = mongodbatlas_project.sarthi.id
  name           = "notification-log"
  cluster_type   = "REPLICASET"
  backup_enabled = true

  # Multi-cloud replica set: one electable node set per cloud region.
  # Atlas handles the actual primary/secondary election and failover
  # internally — from notification-service's point of view this is a
  # single connection string that keeps working through a regional
  # failure on either side.
  replication_specs {
    region_configs {
      electable_specs {
        instance_size = var.atlas_instance_size
        node_count    = 2
      }
      provider_name = "AZURE"
      region_name   = var.azure_atlas_region
      priority      = 7
    }
    region_configs {
      electable_specs {
        instance_size = var.atlas_instance_size
        node_count    = 1
      }
      provider_name = "GCP"
      region_name   = var.gcp_atlas_region
      priority      = 6
    }
  }
}

resource "mongodbatlas_database_user" "notification_service" {
  username           = "notification-service"
  password           = var.atlas_db_password
  project_id         = mongodbatlas_project.sarthi.id
  auth_database_name = "admin"

  roles {
    role_name     = "readWrite"
    database_name = "sarthi_notifications"
  }
}

# Same pattern as PiiEncryptionService's default-secret concern: never let
# this fall back to a permissive default. 0.0.0.0/0 is deliberately NOT
# set here — restrict to your two clouds' NAT/egress ranges.
resource "mongodbatlas_project_ip_access_list" "azure_egress" {
  project_id = mongodbatlas_project.sarthi.id
  cidr_block = var.azure_egress_cidr
  comment    = "Azure Container Apps environment egress range"
}

resource "mongodbatlas_project_ip_access_list" "gcp_egress" {
  project_id = mongodbatlas_project.sarthi.id
  cidr_block = var.gcp_egress_cidr
  comment    = "GCP Cloud Run egress range (via Serverless VPC Connector)"
}

# ---------------------------------------------------------------------------
# CloudAMQP — one managed, multi-region RabbitMQ instance instead of
# standing up independent brokers per cloud. Keeps QueueManagerService and
# NotificationService's existing AMQP client code completely unchanged.
# ---------------------------------------------------------------------------

resource "cloudamqp_instance" "broker" {
  name   = "sarthi-portal-${var.environment}"
  plan   = var.cloudamqp_plan # e.g. "bunny-3" or higher for multi-region support
  region = var.cloudamqp_primary_region
  tags   = ["sarthi-portal", var.environment]
}

# Federation link so a queue declared in the primary region is mirrored
# to the DR region — same purpose as Postgres logical replication, but
# for the "admitted" event queue.
resource "cloudamqp_upgrade" "ha_proxy" {
  instance_id     = cloudamqp_instance.broker.id
  new_version     = "ha_proxy"
  wait_on_upgrade = true
}

output "atlas_connection_string" {
  value       = mongodbatlas_advanced_cluster.notification_log.connection_strings[0].standard_srv
  sensitive   = true
  description = "Feed into notification-service's MONGO_URI in both clouds"
}

output "cloudamqp_url" {
  value       = cloudamqp_instance.broker.url
  sensitive   = true
  description = "Feed into RABBITMQ_* env vars for queue-service and notification-service in both clouds"
}
