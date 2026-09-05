# ── ACM Wildcard Certificate ───────────────────────────────────────────────────
# Single wildcard cert covers ALL sandbox subdomains: *.sandbox.civiform.dev
# One cert, reused for every sandbox — no per-sandbox cert requests.
# DNS validation CNAME must be added to the civiform.dev zone by Rocky / DNS owner.

resource "aws_acm_certificate" "wildcard" {
  domain_name       = "*.${var.domain}"
  validation_method = "DNS"

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
# Single shared ALB. Per-sandbox routing via host-header listener rules.
# EcsFargateSandboxService creates/deletes listener rules dynamically in Java.

resource "aws_lb" "sandbox" {
  name               = "civiform-sandbox-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id

  enable_deletion_protection = false
  tags                       = { Name = "civiform-sandbox-alb" }
}

# HTTP → HTTPS redirect (applies to all subdomains)
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
# EcsFargateSandboxService.registerListenerRule() at provision time.
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

# ── Outputs ───────────────────────────────────────────────────────────────────

output "alb_dns_name" {
  description = "ALB DNS name — create a wildcard Route53/Cloudflare CNAME: *.sandbox.civiform.dev → this value"
  value       = aws_lb.sandbox.dns_name
}

output "alb_zone_id" {
  description = "ALB hosted zone ID — for Route 53 alias records"
  value       = aws_lb.sandbox.zone_id
}

output "alb_https_listener_arn" {
  description = "HTTPS listener ARN — set as ALB_LISTENER_ARN env var on the builder service"
  value       = aws_lb_listener.https.arn
}
