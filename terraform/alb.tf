# ── ACM Certificate for fixed sandbox URL ─────────────────────────────────────
# Single cert for demo.sandbox.civiform.dev — no wildcard needed.
# DNS validation CNAME must be added to the civiform.dev zone by Rocky / DNS owner.

resource "aws_acm_certificate" "demo" {
  domain_name       = "demo.${var.domain}"
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = { Name = "civiform-sandbox-demo-cert" }
}

output "acm_validation_records" {
  description = "Add this CNAME to the civiform.dev DNS zone to validate the cert"
  value = {
    for dvo in aws_acm_certificate.demo.domain_validation_options : dvo.domain_name => {
      name  = dvo.resource_record_name
      type  = dvo.resource_record_type
      value = dvo.resource_record_value
    }
  }
}

# ── Application Load Balancer ─────────────────────────────────────────────────

resource "aws_lb" "sandbox" {
  name               = "civiform-sandbox-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id

  enable_deletion_protection = false
  tags                       = { Name = "civiform-sandbox-alb" }
}

# HTTP → HTTPS redirect
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.sandbox.arn
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

# ── Shared target group — one per environment, updated at sandbox creation ─────
# EcsFargateSandboxService deregisters the old task and registers the new one
# each time a sandbox is created. No per-sandbox target groups.

resource "aws_lb_target_group" "civiform" {
  name        = "civiform-sandbox-tg"
  port        = 9000
  protocol    = "HTTP"
  vpc_id      = aws_vpc.sandbox.id
  target_type = "ip"

  health_check {
    path                = "/health"
    protocol            = "HTTP"
    matcher             = "200"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 15
  }

  tags = { Name = "civiform-sandbox-tg" }
}

# HTTPS listener — forwards everything to the single shared target group
resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.sandbox.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate.demo.arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.civiform.arn
  }
}

# ── Outputs ───────────────────────────────────────────────────────────────────

output "alb_dns_name" {
  description = "ALB DNS name — point demo.sandbox.civiform.dev CNAME here"
  value       = aws_lb.sandbox.dns_name
}

output "alb_zone_id" {
  description = "ALB hosted zone ID — for Route 53 alias records"
  value       = aws_lb.sandbox.zone_id
}

output "target_group_arn" {
  description = "Shared target group ARN — set as ALB_TARGET_GROUP_ARN env var on the builder"
  value       = aws_lb_target_group.civiform.arn
}
