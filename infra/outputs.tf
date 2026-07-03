output "environment" {
  description = "The deployment environment id this state was applied for (var.environment)."
  value       = var.environment
}

output "alb_dns_name" {
  description = "Public DNS name of the ALB (target of the api ALIAS record)."
  value       = aws_lb.main.dns_name
}

output "app_url" {
  description = "Public HTTPS URL the API is served at."
  value       = "https://${local.api_domain}"
}

output "public_web_url" {
  description = "Public HTTPS URL public-web is served at."
  value       = "https://${local.web_services["public-web"].subdomain}.${var.app_domain}"
}

output "admin_web_url" {
  description = "Public HTTPS URL admin-web is served at."
  value       = "https://${local.web_services["admin-web"].subdomain}.${var.app_domain}"
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

output "ops_log_group_name" {
  description = "CloudWatch log group bin/remote points an ops run's --cloud-watch-output-config at."
  value       = aws_cloudwatch_log_group.ops_run.name
}
