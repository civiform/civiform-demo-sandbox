# ── ACM Wildcard Certificate ─────────────────────────────────────────────────
# Pre-provisioned once; shared by all sandboxes via SNI on the ALB.
# DNS validation record must be added to civiform.dev zone by Rocky / DNS owner.

resource "aws_acm_certificate" "wildcard" {
  domain_name       = "*.${var.domain}"
  validation_method = "DNS"

  subject_alternative_names = [var.domain]

  lifecycle {
    create_before_destroy = true
  }

  tags = { Name = "civiform-sandbox-wildcard-cert" }
}

# Output the DNS validation records so Rocky can add them to the civiform.dev zone
output "acm_validation_records" {
  description = "Add these CNAME records to the civiform.dev DNS zone to validate the wildcard cert"
  value = {
    for dvo in aws_acm_certificate.wildcard.domain_validation_options : dvo.domain_name => {
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

  tags = { Name = "civiform-sandbox-alb" }
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

# HTTPS listener — default 404, per-sandbox rules added dynamically by EcsFargateSandboxService
resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.sandbox.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate.wildcard.arn

  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Sandbox not found"
      status_code  = "404"
    }
  }
}

# ── Outputs ───────────────────────────────────────────────────────────────────

output "alb_dns_name" {
  description = "ALB DNS name — point *.sandbox.civiform.dev CNAME here"
  value       = aws_lb.sandbox.dns_name
}

output "alb_zone_id" {
  description = "ALB hosted zone ID — for Route 53 alias records"
  value       = aws_lb.sandbox.zone_id
}

output "https_listener_arn" {
  description = "ARN of the HTTPS listener — used by EcsFargateSandboxService to add per-sandbox rules"
  value       = aws_lb_listener.https.arn
}
