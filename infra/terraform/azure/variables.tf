variable "environment" {
  type    = string
  default = "production"
}

variable "location" {
  description = "Azure region — pick one close to your primary user base"
  type        = string
  default     = "centralindia"
}

variable "azure_tenant_id" {
  type = string
}

variable "image_tag" {
  description = "Tag applied by CI to every service image, e.g. a git SHA"
  type        = string
  default     = "latest"
}

variable "allowed_origins" {
  description = "CORS origin for gateway-service — the Front Door custom domain once provisioned"
  type        = string
}

variable "postgres_admin_password" {
  type      = string
  sensitive = true
}

variable "postgres_sku" {
  type    = string
  default = "GP_Standard_D2ds_v5"
}

variable "gcp_cloud_sql_egress_ip" {
  description = "Static egress IP of the GCP Cloud SQL replica, so Azure's firewall allows the replication connection"
  type        = string
}

# Secrets — same values documented in .env.example; these populate Key
# Vault instead of a .env file for anything running in Azure.
variable "ticket_signing_secret" {
  type      = string
  sensitive = true
}

variable "pii_encryption_key" {
  type      = string
  sensitive = true
}

variable "admin_accounts_json" {
  type      = string
  sensitive = true
}

variable "partner_api_key" {
  type      = string
  sensitive = true
}

variable "anthropic_api_key" {
  description = "Leave empty to deploy assistant-service in its disabled state, same as .env"
  type        = string
  sensitive   = true
  default     = ""
}
