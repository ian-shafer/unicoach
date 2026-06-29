variable "region" {
  description = "AWS region for all resources."
  type        = string
  default     = "us-east-1"
}

variable "app_domain" {
  description = "The deploy apex/zone (e.g. uni.coach); the single domain knob. The Route53 hosted zone is this name and the API is served at api.<app_domain> (see locals.tf). Has no default: bin/infra-plan / bin/infra-apply supply it as TF_VAR_app_domain from .env.prod, keeping the domain set in exactly one place."
  type        = string
}

variable "google_client_ids" {
  description = "Accepted Google OAuth client IDs (comma-separated token audiences) the backend verifies ID tokens against (auth.google.clientIds). No default: bin/infra-plan / bin/infra-apply supply it as TF_VAR_google_client_ids from .env.prod."
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
