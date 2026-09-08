output "frontend_url" {
  value       = google_cloud_run_v2_service.services["frontend"].uri
  description = "Registered as the GCP external origin in global/ (Front Door)"
}

output "cloud_sql_connection_name" {
  value = google_sql_database_instance.results_replica.connection_name
}

output "cloud_sql_private_ip" {
  value = google_sql_database_instance.results_replica.private_ip_address
}

output "redis_host" {
  value = google_redis_instance.queue.host
}
