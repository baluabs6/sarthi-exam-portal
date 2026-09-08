output "postgres_fqdn" {
  value       = azurerm_postgresql_flexible_server.sarthi.fqdn
  description = "Consumed by gcp/main.tf to configure the Cloud SQL replica's upstream"
}

output "postgres_server_name" {
  value = azurerm_postgresql_flexible_server.sarthi.name
}

output "container_registry_login_server" {
  value = azurerm_container_registry.sarthi.login_server
}

output "key_vault_uri" {
  value = azurerm_key_vault.sarthi.vault_uri
}

output "frontend_fqdn" {
  value       = azurerm_container_app.services["frontend"].latest_revision_fqdn
  description = "Registered as one of the two origins in global/ (Front Door)"
}

output "resource_group_name" {
  value = azurerm_resource_group.sarthi.name
}
