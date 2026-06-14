# Runtime configuration and secrets under /unicoach/prod/. render-env.sh on the
# instance fetches the whole prefix (decrypted) and writes /etc/unicoach/env.
#
# Two SecureString sources:
#   - OpenTofu-owned (PGPASSWORD = the generated RDS master password): value
#     managed here.
#   - Out-of-band (DATABASE_PASSWORD, JWT_SECRET, CHAT_ANTHROPIC_API_KEY): created
#     with a placeholder and ignore_changes on value, so the operator seeds the
#     real secret with the AWS CLI and OpenTofu never reverts it.

locals {
  ssm_prefix = "/unicoach/prod"

  # Non-secret String parameters fully owned by OpenTofu.
  ssm_string_params = {
    PGHOST                = aws_db_instance.main.address
    DATABASE_HOST         = aws_db_instance.main.address
    POSTGRES_PORT         = "5432"
    POSTGRES_DB           = "unicoach"
    POSTGRES_USER         = aws_db_instance.main.username
    DATABASE_USER         = var.app_db_user
    SERVER_HOST           = "0.0.0.0"
    SERVER_PORT           = "8080"
    SESSION_COOKIE_SECURE = "true"
    SESSION_COOKIE_DOMAIN = var.api_domain
    JWT_ISSUER            = "https://${var.api_domain}/"
    CHAT_PROVIDER         = "anthropic"
  }

  # SecureString secrets the operator seeds out-of-band.
  ssm_out_of_band_secrets = [
    "DATABASE_PASSWORD",
    "JWT_SECRET",
    "CHAT_ANTHROPIC_API_KEY",
  ]
}

resource "aws_ssm_parameter" "string" {
  for_each = local.ssm_string_params

  name  = "${local.ssm_prefix}/${each.key}"
  type  = "String"
  value = each.value
}

# RDS master password: generated and written straight to a SecureString so no
# human handles it; the migration psql steps read it as PGPASSWORD.
resource "aws_ssm_parameter" "pgpassword" {
  name  = "${local.ssm_prefix}/PGPASSWORD"
  type  = "SecureString"
  value = random_password.db_master.result
}

# Out-of-band secrets: placeholder value, then ignore_changes so operator-seeded
# values survive subsequent applies.
resource "aws_ssm_parameter" "out_of_band_secret" {
  for_each = toset(local.ssm_out_of_band_secrets)

  name  = "${local.ssm_prefix}/${each.value}"
  type  = "SecureString"
  value = "PLACEHOLDER_SEED_OUT_OF_BAND"

  lifecycle {
    ignore_changes = [value]
  }
}
