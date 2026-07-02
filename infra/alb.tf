resource "aws_lb" "main" {
  name               = local.name_prefix
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id

  # Raised from the 60s default so streamed chat responses (SSE proxied from the
  # Anthropic provider) are not severed by the ALB's idle cutoff.
  idle_timeout = 300

  tags = {
    Name = local.name_prefix
  }
}

resource "aws_lb_target_group" "app" {
  name     = local.name_prefix
  port     = 8080
  protocol = "HTTP"
  vpc_id   = aws_vpc.main.id

  health_check {
    path                = "/healthz"
    protocol            = "HTTP"
    matcher             = "200"
    healthy_threshold   = 2
    unhealthy_threshold = 2
    interval            = 30
    timeout             = 5
  }

  tags = {
    Name = local.name_prefix
  }
}

resource "aws_lb_target_group_attachment" "app" {
  target_group_arn = aws_lb_target_group.app.arn
  target_id        = aws_instance.app.id
  port             = 8080
}

# Host-routed web services (app -> public-web, admin -> admin-web) co-located on
# the same instance. One target group, one instance attachment, and one listener
# rule per service; every port derives from local.web_services (no drift with the
# service's configured bind port). Both web modules already serve GET /healthz, so
# the health check matches the api target group.
resource "aws_lb_target_group" "web" {
  for_each = local.web_services

  name     = "${local.name_prefix}-${each.key}"
  port     = each.value.port
  protocol = "HTTP"
  vpc_id   = aws_vpc.main.id

  health_check {
    path                = "/healthz"
    protocol            = "HTTP"
    matcher             = "200"
    healthy_threshold   = 2
    unhealthy_threshold = 2
    interval            = 30
    timeout             = 5
  }

  tags = {
    Name = "${local.name_prefix}-${each.key}"
  }
}

resource "aws_lb_target_group_attachment" "web" {
  for_each = local.web_services

  target_group_arn = aws_lb_target_group.web[each.key].arn
  target_id        = aws_instance.app.id
  port             = each.value.port
}

# Host-header rules on the shared :443 listener. An unmatched host falls to the
# listener's default_action (rest-server, api), so adding these is additive.
resource "aws_lb_listener_rule" "web" {
  for_each = local.web_services

  listener_arn = aws_lb_listener.https.arn
  priority     = each.value.priority

  condition {
    host_header {
      values = ["${each.value.subdomain}.${var.app_domain}"]
    }
  }

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.web[each.key].arn
  }
}

# HTTPS:443 — terminates TLS with the ACM cert, forwards to the app target group.
resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate_validation.wildcard.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}

# HTTP:80 — redirect to 443.
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}
