# Per-environment identity, all derived from two tokens: var.environment and the
# base domain (var.app_domain + var.hosted_zone_name). There is no special case
# for prod — every env's resource names, SSM prefix, and zone derive uniformly,
# so a second env is stood up by authoring .env.<env> alone.
#
# locals.tf is the sole owner of ssm_prefix and name_prefix; ssm.tf and iam.tf
# reference local.ssm_prefix, every renamed resource references local.name_prefix.
locals {
  name_prefix = "unicoach-${var.environment}"
  ssm_prefix  = "/unicoach/${var.environment}"

  # The Route53 zone app_domain lives in. Apex envs (app_domain == its own zone,
  # e.g. prod's uni.coach) omit HOSTED_ZONE_NAME and fall back to app_domain; a
  # subdomain-per-env sets HOSTED_ZONE_NAME to the parent zone.
  hosted_zone_name = coalesce(var.hosted_zone_name, var.app_domain)
  api_domain       = "api.${var.app_domain}"
}
