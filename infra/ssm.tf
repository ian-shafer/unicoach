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

  # SSM owns exactly two classes (RFC 95): secrets (below) and Terraform-owned RDS
  # identity — the values whose canonical source is Terraform, not an author. The
  # RDS-identity keys are the only non-secret Strings here; every other non-secret
  # is committed to the dotenv roles (.env / .env.<env>) and shipped in the release
  # bundle's flat deploy-env, which render-env merges UNDER this prefix on the
  # instance. PGHOST/DATABASE_HOST/POSTGRES_USER are RDS resource attributes;
  # DATABASE_USER is var.app_db_user (which also drives bin/db-create-role), so its
  # one home is that variable, not a second committed copy. Committing any of the
  # four would duplicate a Terraform-owned fact and risk drift.
  ssm_string_params = {
    PGHOST        = aws_db_instance.main.address
    DATABASE_HOST = aws_db_instance.main.address
    POSTGRES_USER = aws_db_instance.main.username
    DATABASE_USER = var.app_db_user
  }

  # SecureString secrets the operator seeds out-of-band. The App Store Server
  # API credential (RFC 110) is three keys seeded as one unit once the Paid Apps
  # agreement activates — issuer and key id are identifiers, not secrets, but
  # keeping the trio atomic and out of the committed tree beats splitting it.
  # APP_STORE_PRIVATE_KEY is the .p8 body as ONE-LINE base64 PKCS#8 (PEM
  # header/footer/newlines stripped): grep -v 'PRIVATE KEY' AuthKey_<KEYID>.p8 | tr -d '\n'
  ssm_out_of_band_secrets = [
    "DATABASE_PASSWORD",
    "CHAT_ANTHROPIC_API_KEY",
    "APP_STORE_ISSUER_ID",
    "APP_STORE_KEY_ID",
    "APP_STORE_PRIVATE_KEY",
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
