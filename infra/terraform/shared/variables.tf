variable "environment" {
  description = "e.g. production, staging"
  type        = string
  default     = "production"
}

variable "atlas_org_id" {
  description = "MongoDB Atlas organization ID"
  type        = string
}

variable "atlas_instance_size" {
  description = "Atlas node tier, e.g. M10 for production"
  type        = string
  default     = "M10"
}

variable "azure_atlas_region" {
  description = "Atlas region name for the Azure side, e.g. CENTRAL_INDIA"
  type        = string
  default     = "CENTRAL_INDIA"
}

variable "gcp_atlas_region" {
  description = "Atlas region name for the GCP side, e.g. ASIA_SOUTH1"
  type        = string
  default     = "ASIA_SOUTH1"
}

variable "atlas_db_password" {
  description = "Password for the notification-service Atlas DB user"
  type        = string
  sensitive   = true
}

variable "azure_egress_cidr" {
  description = "CIDR range Azure Container Apps egresses from — restrict Atlas access to this, never 0.0.0.0/0"
  type        = string
}

variable "gcp_egress_cidr" {
  description = "CIDR range GCP Cloud Run (via Serverless VPC Connector) egresses from"
  type        = string
}

variable "cloudamqp_plan" {
  description = "CloudAMQP plan — needs a plan tier that supports multi-region federation"
  type        = string
  default     = "bunny-3"
}

variable "cloudamqp_primary_region" {
  description = "Primary CloudAMQP region, colocated with the Azure primary"
  type        = string
  default     = "azure-arm::centralindia"
}
