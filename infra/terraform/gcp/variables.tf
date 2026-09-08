variable "gcp_project_id" {
  type = string
}

variable "gcp_region" {
  description = "GCP region for DR — pick one geographically distinct from the Azure primary region for genuine disaster isolation"
  type        = string
  default     = "asia-south1"
}

variable "environment" {
  type    = string
  default = "production"
}

variable "image_tag" {
  type    = string
  default = "latest"
}

variable "allowed_origins" {
  type = string
}

variable "cloud_sql_tier" {
  type    = string
  default = "db-custom-2-8192"
}

variable "postgres_admin_password" {
  description = "Same value as azure/variables.tf's postgres_admin_password — needed here for the DMS connection profile"
  type        = string
  sensitive   = true
}

# --- Azure remote-state lookup coordinates ---
variable "azure_state_resource_group" {
  type = string
}

variable "azure_state_storage_account" {
  type = string
}

variable "azure_state_container" {
  type    = string
  default = "tfstate"
}
