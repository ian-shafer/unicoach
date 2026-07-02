# Runtime configuration and secrets under the env's SSM prefix
# (/unicoach/${var.environment}, owned by locals.tf). render-env.sh on the
# instance fetches the whole prefix (decrypted) and writes /etc/unicoach/env.
#
# Two SecureString sources:
#   - OpenTofu-owned (PGPASSWORD = the generated RDS master password): value
#     managed here.
#   - Out-of-band (DATABASE_PASSWORD, CHAT_ANTHROPIC_API_KEY): created with a
#     placeholder and ignore_changes on value, so the operator seeds the real
#     secret with the AWS CLI and OpenTofu never reverts it.

locals {
  # ssm_prefix is owned by locals.tf (= /unicoach/${var.environment}); referenced
  # here as local.ssm_prefix. Defining it twice would fail `tofu validate`.

  # Non-secret String parameters fully owned by OpenTofu.
  ssm_string_params = {
    PGHOST        = aws_db_instance.main.address
    DATABASE_HOST = aws_db_instance.main.address
    POSTGRES_PORT = "5432"
    POSTGRES_DB   = "unicoach"
    POSTGRES_USER = aws_db_instance.main.username
    DATABASE_USER = var.app_db_user
    SERVER_HOST   = "0.0.0.0"
    SERVER_PORT   = "8080"
    # public-web's bind port, and a required substitution in service.conf's
    # emailVerification.verifyUrlBase default — HOCON resolves that default
    # even though EMAIL_VERIFICATION_VERIFY_URL_BASE overrides it, so the
    # variable must exist or the JVM services fail at boot. It satisfies
    # resolution only: the http://<domain>:<port> default it feeds is never
    # correct in prod, so the EMAIL_VERIFICATION_VERIFY_URL_BASE override
    # below stays load-bearing.
    PUBLIC_WEB_PORT       = "8082"
    SESSION_COOKIE_SECURE = "true"
    # The session cookie spans the whole zone (apex + api subdomain), so it is
    # the apex app_domain, not the api host. Email links derive from the same
    # single knob.
    APP_DOMAIN                         = var.app_domain
    EMAIL_DEFAULT_FROM                 = "noreply@${var.app_domain}"
    EMAIL_VERIFICATION_VERIFY_URL_BASE = "https://${var.app_domain}/verify-email"
    EMAIL_PROVIDER                     = "ses"
    CHAT_PROVIDER                      = "anthropic"
    # The committed .conf default is the offline `stub` verifier (dev-only); prod
    # overrides it to live Google here. Paired with the .conf default flip so a
    # missing override can never silently drop prod to a stub-auth bypass.
    GOOGLE_AUTH_PROVIDER = "google"
    GOOGLE_CLIENT_IDS    = var.google_client_ids
  }

  # SecureString secrets the operator seeds out-of-band.
  ssm_out_of_band_secrets = [
    "DATABASE_PASSWORD",
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
