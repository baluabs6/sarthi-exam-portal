variable "location" {
  type    = string
  default = "global"
}

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

variable "gcp_state_bucket" {
  description = "GCS bucket holding the gcp/ module's Terraform state"
  type        = string
}
