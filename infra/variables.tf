variable "region" {
  description = "AWS region for all resources."
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Deployment environment id (e.g. prod); drives name_prefix, the SSM prefix, the state key, and the IAM scope. No default: fail fast if bin/infra-* did not supply it as TF_VAR_environment from .env.<env>'s ENVIRONMENT."
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

variable "google_client_ids" {
  description = "Accepted Google OAuth client IDs (comma-separated token audiences) the backend verifies ID tokens against (auth.google.clientIds). No default: bin/infra-plan / bin/infra-apply supply it as TF_VAR_google_client_ids from .env.<env>."
  type        = string
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
