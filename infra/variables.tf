variable "region" {
  description = "AWS region for all resources."
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Deployment environment id (e.g. prod); drives name_prefix, the SSM prefix, the state key, and the IAM scope. No default: fail fast if bin/infra-* did not supply it as TF_VAR_environment, which they derive from the <env> command argument (RFC 95 removed the .env.<env> ENVIRONMENT key; the <env> argument is the sole identity)."
  type        = string
}

variable "hosted_zone_name" {
  description = "Route53 zone containing app_domain. Defaults to app_domain (apex env, e.g. prod's uni.coach); set to the parent zone for a subdomain-per-env (e.g. uni.coach when app_domain = staging.uni.coach)."
  type        = string
  default     = null
}

variable "app_domain" {
  description = "The env's web host (e.g. uni.coach for prod, staging.uni.coach for staging); the single domain knob. The API is served at api.<app_domain>; the Route53 zone it lives in is hosted_zone_name (defaults to app_domain for apex envs). Has no default: bin/infra-plan / bin/infra-apply supply it as TF_VAR_app_domain from .env.<env>, keeping the domain set in exactly one place."
  type        = string
}

variable "public_web_port" {
  description = "public-web's bind port, driving its ALB target group, security-group rule, and the PUBLIC_WEB_PORT SSM value the JVM reads. No default: it is needed by both Terraform and the JVM, so per CONFIGURATION.md it is sourced once in .env and supplied as TF_VAR_public_web_port (mirroring app_domain) rather than owned as a Terraform default."
  type        = number
}

variable "admin_web_port" {
  description = "admin-web's bind port, driving its ALB target group, security-group rule, and the ADMIN_WEB_PORT SSM value the JVM reads. No default: needed by both Terraform and the JVM, so sourced in .env and supplied as TF_VAR_admin_web_port (mirroring public_web_port / app_domain)."
  type        = number
}

variable "instance_type" {
  description = "EC2 instance type for the application host (Graviton/ARM)."
  type        = string
  default     = "t4g.small"
}

variable "db_instance_class" {
  description = "RDS instance class (Graviton/ARM)."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_engine_version" {
  description = "RDS PostgreSQL major version; RDS selects the latest matching minor."
  type        = string
  default     = "18"
}

variable "app_db_user" {
  description = "Application login-role name; the single knob driving both the SSM DATABASE_USER parameter and bin/db-create-role."
  type        = string
  default     = "unicoach"
}
