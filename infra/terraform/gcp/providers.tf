terraform {
  required_version = ">= 1.5.0"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.40"
    }
  }

  backend "gcs" {
    # bucket set via -backend-config at init time, see README.md
    prefix = "gcp-dr.tfstate"
  }
}

provider "google" {
  project = var.gcp_project_id
  region  = var.gcp_region
}

# Reads the Azure module's state to pull the Postgres FQDN for the
# replication link below — this is the one place the two clouds'
# Terraform runs are wired together, deliberately kept to a single
# read-only dependency rather than a shared root module.
data "terraform_remote_state" "azure" {
  backend = "azurerm"
  config = {
    resource_group_name  = var.azure_state_resource_group
    storage_account_name = var.azure_state_storage_account
    container_name        = var.azure_state_container
    key                    = "azure-primary.tfstate"
  }
}
