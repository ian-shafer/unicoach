# The deploy domain is a single knob: var.app_domain, the Route53 apex/zone,
# supplied from .env.prod via TF_VAR_app_domain by bin/infra-plan / bin/infra-apply.
# Every other hostname derives from it, so the domain is set in exactly one place
# (.env.prod) and never duplicated across the HCL.
locals {
  hosted_zone_name = var.app_domain
  api_domain       = "api.${var.app_domain}"
}
