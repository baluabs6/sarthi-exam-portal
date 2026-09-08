terraform {
  required_version = ">= 1.5.0"
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 3.110"
    }
  }

  backend "azurerm" {
    key = "global.tfstate"
  }
}

provider "azurerm" {
  features {}
}

data "terraform_remote_state" "azure" {
  backend = "azurerm"
  config = {
    resource_group_name  = var.azure_state_resource_group
    storage_account_name = var.azure_state_storage_account
    container_name        = var.azure_state_container
    key                    = "azure-primary.tfstate"
  }
}

data "terraform_remote_state" "gcp" {
  # GCP's own outputs (the Cloud Run URL) are stored in a GCS backend, but
  # Terraform can still read a GCS-backed state file from here as a data
  # source — this is a READ, not a cross-provider dependency, which is
  # why global/ only needs the azurerm provider.
  backend = "gcs"
  config = {
    bucket = var.gcp_state_bucket
    prefix = "gcp-dr.tfstate"
  }
}

resource "azurerm_resource_group" "global" {
  name     = "rg-sarthi-portal-global"
  location = var.location
}

resource "azurerm_cdn_frontdoor_profile" "sarthi" {
  name                = "fd-sarthi-portal"
  resource_group_name = azurerm_resource_group.global.name
  sku_name            = "Premium_AzureFrontDoor" # Premium tier needed for private-link/WAF; Standard also works if you don't need those
}

resource "azurerm_cdn_frontdoor_endpoint" "sarthi" {
  name                     = "sarthi-portal"
  cdn_frontdoor_profile_id = azurerm_cdn_frontdoor_profile.sarthi.id
}

# ---------------------------------------------------------------------------
# WAF — previously absent entirely. This is the one truly internet-facing
# entry point across BOTH clouds (everything else, in both azure/main.tf
# and gcp/main.tf, is internal-only ingress) and had no protection beyond
# the application's own per-IP rate limiters. Microsoft_DefaultRuleSet
# covers the OWASP-style injection/traversal/etc. patterns; the bot
# manager rule set adds known-bad bot signature blocking on top.
# ---------------------------------------------------------------------------

resource "azurerm_cdn_frontdoor_firewall_policy" "sarthi" {
  name                = "fdwafsarthiportal"
  resource_group_name = azurerm_resource_group.global.name
  sku_name            = azurerm_cdn_frontdoor_profile.sarthi.sku_name
  mode                = "Prevention"

  managed_rule {
    type    = "Microsoft_DefaultRuleSet"
    version = "2.1"
    action  = "Block"
  }

  managed_rule {
    type    = "Microsoft_BotManagerRuleSet"
    version = "1.0"
    action  = "Block"
  }
}

resource "azurerm_cdn_frontdoor_security_policy" "sarthi" {
  name                     = "sarthi-portal-waf"
  cdn_frontdoor_profile_id = azurerm_cdn_frontdoor_profile.sarthi.id

  security_policies {
    firewall {
      cdn_frontdoor_firewall_policy_id = azurerm_cdn_frontdoor_firewall_policy.sarthi.id

      association {
        domain {
          cdn_frontdoor_domain_id = azurerm_cdn_frontdoor_endpoint.sarthi.id
        }
        patterns_to_match = ["/*"]
      }
    }
  }
}

# ---------------------------------------------------------------------------
# One origin group, two origins, EQUAL weight — this is what makes it
# active-active rather than active-passive. Front Door's health probes
# are what turn "equal weight" into "automatic failover" the moment
# either origin starts failing.
# ---------------------------------------------------------------------------

resource "azurerm_cdn_frontdoor_origin_group" "app" {
  name                     = "app-origins"
  cdn_frontdoor_profile_id = azurerm_cdn_frontdoor_profile.sarthi.id

  load_balancing {
    sample_size                 = 4
    successful_samples_required = 3
  }

  health_probe {
    protocol            = "Https"
    path                = "/api/status/health"
    request_type        = "GET"
    interval_in_seconds = 30
  }
}

resource "azurerm_cdn_frontdoor_origin" "azure_frontend" {
  name                          = "azure-frontend"
  cdn_frontdoor_origin_group_id = azurerm_cdn_frontdoor_origin_group.app.id

  enabled                        = true
  host_name                      = data.terraform_remote_state.azure.outputs.frontend_fqdn
  origin_host_header             = data.terraform_remote_state.azure.outputs.frontend_fqdn
  certificate_name_check_enabled = true
  weight                         = 500 # equal split with the GCP origin below (both out of 1000)
  priority                       = 1   # same priority = Front Door load-balances by weight rather than treating one as failover-only
}

resource "azurerm_cdn_frontdoor_origin" "gcp_frontend" {
  name                          = "gcp-frontend"
  cdn_frontdoor_origin_group_id = azurerm_cdn_frontdoor_origin_group.app.id

  enabled = true
  # Cloud Run's URL, e.g. frontend-xyz-uc.a.run.app — Front Door supports
  # any externally-resolvable hostname as an origin, which is the whole
  # mechanism that makes "Azure-native routing, GCP as a real origin"
  # possible without a third-party global load balancer.
  host_name                      = replace(replace(data.terraform_remote_state.gcp.outputs.frontend_url, "https://", ""), "/", "")
  origin_host_header             = replace(replace(data.terraform_remote_state.gcp.outputs.frontend_url, "https://", ""), "/", "")
  certificate_name_check_enabled = true
  weight                         = 500
  priority                       = 1
}

resource "azurerm_cdn_frontdoor_route" "app" {
  name                          = "app-route"
  cdn_frontdoor_endpoint_id     = azurerm_cdn_frontdoor_endpoint.sarthi.id
  cdn_frontdoor_origin_group_id = azurerm_cdn_frontdoor_origin_group.app.id
  cdn_frontdoor_origin_ids      = [azurerm_cdn_frontdoor_origin.azure_frontend.id, azurerm_cdn_frontdoor_origin.gcp_frontend.id]

  supported_protocols    = ["Http", "Https"]
  patterns_to_match      = ["/*"]
  forwarding_protocol    = "HttpsOnly"
  https_redirect_enabled = true
  link_to_default_domain = true
}

# ---------------------------------------------------------------------------
# Write-path override: admin/result-mutating routes get PRIORITY routing
# (Azure first) rather than weighted, since these ultimately hit whichever
# cloud currently holds the writable Postgres primary. During normal
# operation this still resolves to Azure; during a declared failover, the
# runbook in docs/CLOUD_DEPLOYMENT.md updates this origin group's priority
# values (not Terraform — a fast operational flip, not a full apply cycle).
# ---------------------------------------------------------------------------

resource "azurerm_cdn_frontdoor_origin_group" "write_path" {
  name                     = "write-path-origins"
  cdn_frontdoor_profile_id = azurerm_cdn_frontdoor_profile.sarthi.id

  load_balancing {
    sample_size                 = 4
    successful_samples_required = 3
  }

  health_probe {
    protocol            = "Https"
    path                = "/api/status/health"
    request_type        = "GET"
    interval_in_seconds = 15 # tighter interval — this is the path where a slow-to-detect failure matters most
  }
}

resource "azurerm_cdn_frontdoor_origin" "azure_write" {
  name                          = "azure-write"
  cdn_frontdoor_origin_group_id = azurerm_cdn_frontdoor_origin_group.write_path.id
  enabled                       = true
  host_name                     = data.terraform_remote_state.azure.outputs.frontend_fqdn
  origin_host_header            = data.terraform_remote_state.azure.outputs.frontend_fqdn
  weight                        = 1000
  priority                      = 1 # Azure preferred while it holds the writable Postgres primary
}

resource "azurerm_cdn_frontdoor_origin" "gcp_write" {
  name                          = "gcp-write"
  cdn_frontdoor_origin_group_id = azurerm_cdn_frontdoor_origin_group.write_path.id
  enabled                       = true
  host_name                     = replace(replace(data.terraform_remote_state.gcp.outputs.frontend_url, "https://", ""), "/", "")
  origin_host_header            = replace(replace(data.terraform_remote_state.gcp.outputs.frontend_url, "https://", ""), "/", "")
  weight                        = 1000
  priority                      = 2 # only used once the runbook flips this to 1 during a declared failover
}

resource "azurerm_cdn_frontdoor_route" "write_path" {
  name                          = "write-path-route"
  cdn_frontdoor_endpoint_id     = azurerm_cdn_frontdoor_endpoint.sarthi.id
  cdn_frontdoor_origin_group_id = azurerm_cdn_frontdoor_origin_group.write_path.id
  cdn_frontdoor_origin_ids      = [azurerm_cdn_frontdoor_origin.azure_write.id, azurerm_cdn_frontdoor_origin.gcp_write.id]

  supported_protocols    = ["Http", "Https"]
  patterns_to_match      = ["/api/grievances/*", "/api/admin/*", "/api/partner/*"]
  forwarding_protocol    = "HttpsOnly"
  https_redirect_enabled = true
  link_to_default_domain = true
}
