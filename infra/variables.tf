variable "region" {
  description = "AWS region for all resources."
  type        = string
  default     = "us-east-1"
}

variable "api_domain" {
  description = "Fully-qualified hostname the API is served at; ACM cert subject and ALIAS record."
  type        = string
  default     = "api.unicoachapp.com"
}

variable "hosted_zone_name" {
  description = "Parent Route53 hosted zone (registered out-of-band); referenced as a data source, never created."
  type        = string
  default     = "unicoachapp.com"
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
