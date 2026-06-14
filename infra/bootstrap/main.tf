# State-backend bootstrap (local state).
#
# Resolves the chicken-and-egg of the S3 backend bucket's own existence: this
# minimal configuration keeps its state on local disk and creates the versioned,
# encrypted, public-access-blocked bucket that the main infra/ configuration then
# uses as its S3 backend (see ../backend.tf). Apply this once before running
# `tofu -chdir=infra init`.

variable "region" {
  description = "AWS region for the state bucket."
  type        = string
  default     = "us-east-1"
}

variable "state_bucket_name" {
  description = "Globally-unique name for the OpenTofu remote-state bucket. Must match the bucket in ../backend.tf."
  type        = string
  default     = "unicoach-tofu-state"
}

provider "aws" {
  region = var.region
}

resource "aws_s3_bucket" "state" {
  bucket = var.state_bucket_name
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket = aws_s3_bucket.state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

output "state_bucket" {
  description = "Name of the remote-state bucket; reference it in ../backend.tf."
  value       = aws_s3_bucket.state.id
}
