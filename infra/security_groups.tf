# Three security groups enforcing a linear path: internet -> alb -> ec2 -> rds.
# The ec2 group's only ingress is 8080 from the alb group; no rule opens SSH, so
# the instance's public IP does not widen its exposure.

resource "aws_security_group" "alb" {
  name        = "${local.name_prefix}-alb"
  description = "ALB: public HTTPS/HTTP in, app traffic out to ec2."
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${local.name_prefix}-alb"
  }
}

resource "aws_security_group" "ec2" {
  name        = "${local.name_prefix}-ec2"
  description = "App instance: 8080 from alb only; all egress (Anthropic, SES, SSM)."
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${local.name_prefix}-ec2"
  }
}

resource "aws_security_group" "rds" {
  name        = "${local.name_prefix}-rds"
  description = "RDS: 5432 from ec2 only; no egress."
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${local.name_prefix}-rds"
  }
}

# ── alb ingress: 443 + 80 from the internet ───────────────────────────────────
resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTPS from the internet"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTP from the internet (redirected to 443)"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
}

# ── alb egress: to ec2 on 8080 ────────────────────────────────────────────────
resource "aws_vpc_security_group_egress_rule" "alb_to_ec2" {
  security_group_id            = aws_security_group.alb.id
  description                  = "Forward to app instance on 8080"
  referenced_security_group_id = aws_security_group.ec2.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}

# ── ec2 ingress: 8080 from alb only ───────────────────────────────────────────
resource "aws_vpc_security_group_ingress_rule" "ec2_from_alb" {
  security_group_id            = aws_security_group.ec2.id
  description                  = "App port from the ALB"
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}

# ── ec2 egress: all (Anthropic API, SES, SSM endpoints, package installs) ──────
resource "aws_vpc_security_group_egress_rule" "ec2_all" {
  security_group_id = aws_security_group.ec2.id
  description       = "All outbound (Anthropic, SES, SSM, packages)"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# ── rds ingress: 5432 from ec2 only; no egress rules ──────────────────────────
resource "aws_vpc_security_group_ingress_rule" "rds_from_ec2" {
  security_group_id            = aws_security_group.rds.id
  description                  = "Postgres from the app instance"
  referenced_security_group_id = aws_security_group.ec2.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}
