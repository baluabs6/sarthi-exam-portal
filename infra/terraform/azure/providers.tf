terraform {
  required_version = ">= 1.5.0"
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 3.110"
    }
    postgresql = {
      source  = "cyrilgdn/postgresql"
      version = "~> 1.22"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  backend "azurerm" {
    key = "azure-primary.tfstate"
  }
}

provider "azurerm" {
  features {
    key_vault {
      purge_soft_delete_on_destroy = false # never let `terraform destroy` permanently nuke secrets by accident
    }
  }
}

# Used ONLY to provision a least-privilege app role below — result-service
# itself never authenticates as this admin account. Connects using the
# admin credentials purely at apply-time, over the same firewall-allowed
# path Terraform itself runs from.
provider "postgresql" {
  host            = azurerm_postgresql_flexible_server.sarthi.fqdn
  port            = 5432
  username        = "sarthi_admin"
  password        = var.postgres_admin_password
  sslmode         = "require"
  superuser       = false
  connect_timeout = 15
}
