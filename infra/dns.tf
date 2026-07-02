# The parent zone is auto-created by Route53 Domains registration, so it is
# referenced by data source rather than created (a managed zone would conflict).
# If registration is incomplete this resolves nothing and dns.tf fails closed.
data "aws_route53_zone" "main" {
  name         = local.hosted_zone_name
  private_zone = false
}

# A single wildcard cert covers every one-level host under the app domain (api,
# app, admin, and any future sibling), so the ALB listener needs no per-host
# certificate. Changing domain_name forces replacement; create_before_destroy
# issues the new cert before dropping the old one, avoiding a gap.
resource "aws_acm_certificate" "wildcard" {
  domain_name       = "*.${var.app_domain}"
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

# DNS-validation records written into the auto-created zone. A wildcard emits a
# single validation record; the for_each handles it generically.
resource "aws_route53_record" "cert_validation" {
  for_each = {
    for dvo in aws_acm_certificate.wildcard.domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  }

  zone_id = data.aws_route53_zone.main.zone_id
  name    = each.value.name
  type    = each.value.type
  records = [each.value.record]
  ttl     = 60

  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "wildcard" {
  certificate_arn         = aws_acm_certificate.wildcard.arn
  validation_record_fqdns = [for record in aws_route53_record.cert_validation : record.fqdn]
}

# ALIAS api.<app_domain> -> the ALB.
resource "aws_route53_record" "api" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = local.api_domain
  type    = "A"

  alias {
    name                   = aws_lb.main.dns_name
    zone_id                = aws_lb.main.zone_id
    evaluate_target_health = true
  }
}

# ALIAS app.<app_domain> / admin.<app_domain> -> the ALB, one per web service.
resource "aws_route53_record" "web" {
  for_each = local.web_services

  zone_id = data.aws_route53_zone.main.zone_id
  name    = "${each.value.subdomain}.${var.app_domain}"
  type    = "A"

  alias {
    name                   = aws_lb.main.dns_name
    zone_id                = aws_lb.main.zone_id
    evaluate_target_health = true
  }
}
