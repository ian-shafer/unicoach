# SES sender-identity verification. The app sends transactional email (account
# verification) from noreply@<app_domain> with EMAIL_PROVIDER=ses (see ssm.tf);
# the ses:SendEmail grant on the instance role lives in iam.tf. SES refuses mail
# until the sending domain is a verified identity, so the domain identity and its
# DKIM / MAIL FROM DNS are codified here — every environment provisions its own
# sender identity in the same apply as the rest of its stack, rather than as a
# manual out-of-band step.
#
# The identity is var.app_domain (the From domain), which for an apex env equals
# hosted_zone_name and for a subdomain env is the env's own host; its DKIM/MAIL
# FROM records are published into the hosted zone (data.aws_route53_zone.main).
# A live apex may already run inbound mail (prod's uni.coach apex is Google
# Workspace) — SES only adds DKIM CNAMEs and an isolated MAIL FROM subdomain,
# neither of which collides with the apex MX/TXT.
#
# Two account-level actions are NOT expressible in HCL and remain runbook steps:
# exiting the SES sandbox (a PutAccountDetails production-access request) and —
# while still sandboxed — verifying individual test recipients.

resource "aws_sesv2_email_identity" "sender" {
  email_identity = var.app_domain

  # Easy DKIM: SES generates the key pair and publishes three CNAME tokens; the
  # domain verifies for sending once they resolve.
  dkim_signing_attributes {
    next_signing_key_length = "RSA_2048_BIT"
  }
}

# The three Easy DKIM CNAMEs. Tokens are known once the identity exists, so the
# records apply in the same run and the domain self-verifies without a second
# pass.
resource "aws_route53_record" "ses_dkim" {
  count   = 3
  zone_id = data.aws_route53_zone.main.zone_id
  name    = "${aws_sesv2_email_identity.sender.dkim_signing_attributes[0].tokens[count.index]}._domainkey.${var.app_domain}"
  type    = "CNAME"
  ttl     = 300
  records = ["${aws_sesv2_email_identity.sender.dkim_signing_attributes[0].tokens[count.index]}.dkim.amazonses.com"]
}

# Custom MAIL FROM subdomain so the Return-Path aligns with the sending domain
# (SPF alignment for DMARC) and bounce/complaint traffic routes back to SES.
# Isolated on mail.<app_domain>, so it never collides with an apex inbound-mail MX.
resource "aws_sesv2_email_identity_mail_from_attributes" "sender" {
  email_identity   = aws_sesv2_email_identity.sender.email_identity
  mail_from_domain = "mail.${var.app_domain}"

  # If the MAIL FROM records ever fail to resolve, fall back to the SES default
  # Return-Path rather than rejecting the send — deliverability degrades but the
  # verification email still goes out.
  behavior_on_mx_failure = "USE_DEFAULT_VALUE"
}

# MX for the MAIL FROM subdomain: SES receives bounces/complaints here.
resource "aws_route53_record" "ses_mail_from_mx" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = aws_sesv2_email_identity_mail_from_attributes.sender.mail_from_domain
  type    = "MX"
  ttl     = 300
  records = ["10 feedback-smtp.${var.region}.amazonses.com"]
}

# SPF for the MAIL FROM subdomain, authorizing Amazon SES as a sender.
resource "aws_route53_record" "ses_mail_from_spf" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = aws_sesv2_email_identity_mail_from_attributes.sender.mail_from_domain
  type    = "TXT"
  ttl     = 300
  records = ["v=spf1 include:amazonses.com ~all"]
}
