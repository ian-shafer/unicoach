output "alb_dns_name" {
  description = "Public DNS name of the ALB (target of the api ALIAS record)."
  value       = aws_lb.main.dns_name
}

output "app_url" {
  description = "Public HTTPS URL the API is served at."
  value       = "https://${local.api_domain}"
}

output "rds_endpoint" {
  description = "RDS instance endpoint (host:port)."
  value       = aws_db_instance.main.endpoint
}

output "artifacts_bucket" {
  description = "S3 bucket bin/deploy uploads the release bundle to."
  value       = aws_s3_bucket.artifacts.id
}

output "instance_id" {
  description = "EC2 instance id bin/deploy targets via SSM Run Command."
  value       = aws_instance.app.id
}
