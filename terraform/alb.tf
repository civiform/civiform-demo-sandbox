# ── ACM Wildcard Certificate ───────────────────────────────────────────────────
# Single certificate covers both the builder portal (sandbox.civiform.dev) and
# all sandbox subdomains (*.sandbox.civiform.dev).
# DNS validation CNAME must be added to the civiform.dev zone in Cloudflare.

resource "aws_acm_certificate" "wildcard" {
  domain_name               = "*.${var.domain}"
  subject_alternative_names = [var.domain]
  validation_method         = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = { Name = "civiform-sandbox-wildcard-cert" }
}

output "acm_validation_records" {
  description = "Add this CNAME to the civiform.dev DNS zone to validate the wildcard cert"
  value = {
    for dvo in aws_acm_certificate.wildcard.domain_validation_options : dvo.domain_name => {
      name  = dvo.resource_record_name
      type  = dvo.resource_record_type
      value = dvo.resource_record_value
    }
  }
}

# ── Application Load Balancer ──────────────────────────────────────────────────
# Single shared ALB. Routes:
#   - Host: sandbox.civiform.dev   → cf-sandbox-builder service (priority 100)
#   - Host: *.sandbox.civiform.dev → per-sandbox target groups (priorities 1000–50000)

resource "aws_lb" "sandbox" {
  name               = "civiform-sandbox-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id

  enable_deletion_protection = false
  tags                       = { Name = "civiform-sandbox-alb" }
}

# HTTP → HTTPS redirect (applies to the portal and all sandbox subdomains)
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

# HTTPS listener — default action returns 404 for unknown subdomains.
# Per-sandbox listener rules (host-header → target group) are created by
# TerraformSandboxService at provision time.
resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.sandbox.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate.wildcard.arn

  # Default: no matching sandbox found
  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Sandbox not found or expired."
      status_code  = "404"
    }
  }
}

# ── Builder Portal Target Group & Listener Rule ────────────────────────────────
# Routes https://sandbox.civiform.dev to the cf-sandbox-builder ECS service.
# Priority 100 sits well below the 1000–50000 range allocated by
# sandbox_listener_priority_seq for individual sandboxes.

resource "aws_lb_target_group" "builder" {
  name        = "civiform-sandbox-builder-tg"
  port        = 9000
  protocol    = "HTTP"
  vpc_id      = aws_vpc.sandbox.id
  target_type = "ip"

  health_check {
    enabled             = true
    path                = "/health"
    port                = "traffic-port"
    protocol            = "HTTP"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    timeout             = 5
    interval            = 15
    matcher             = "200"
  }

  tags = { Name = "civiform-sandbox-builder-tg" }
}

resource "aws_lb_listener_rule" "builder" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 100

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.builder.arn
  }

  condition {
    host_header {
      values = [var.domain]
    }
  }

  tags = { Name = "civiform-sandbox-builder-rule" }
}

# ── Outputs ───────────────────────────────────────────────────────────────────

output "alb_dns_name" {
  description = "ALB DNS name — create Cloudflare CNAMEs for sandbox.civiform.dev and *.sandbox.civiform.dev → this value"
  value       = aws_lb.sandbox.dns_name
}

output "alb_zone_id" {
  description = "ALB hosted zone ID — for Route 53 alias records"
  value       = aws_lb.sandbox.zone_id
}

output "alb_https_listener_arn" {
  description = "HTTPS listener ARN — the per-sandbox host-header rules attach to this"
  value       = aws_lb_listener.https.arn
}

# Echoed as an output purely so that the per-sandbox tfvars can be generated
# entirely from `terraform output -json`. Without it the base domain is the one
# value that has to be hand-copied into every sandbox, which is exactly the kind
# of thing that drifts and then fails as an unexplained 404 from the ALB's
# default action.
output "base_domain" {
  description = "Wildcard domain the certificate covers and sandboxes live under"
  value       = var.domain
}
