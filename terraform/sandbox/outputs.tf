# ── Outputs ───────────────────────────────────────────────────────────────────
#
# Read by TerraformSandboxService via `terraform output -json` after apply and
# persisted to the sandbox_instances row. Keep these stable: the Java side
# parses them by name.

output "sandbox_url" {
  description = "Public URL of the sandbox. Reachable as soon as the listener rule exists, via the pre-existing Cloudflare wildcard."
  value       = "https://${local.fqdn}"
}

output "target_group_arn" {
  description = "Target group fronting this sandbox's ECS service. Polled during provisioning to determine when the sandbox is actually serving."
  value       = module.civiform_service.lb_https_target_group_arn
}

output "listener_rule_arn" {
  description = "ALB listener rule (allow) routing this sandbox's hostname when cookie is present."
  value       = aws_lb_listener_rule.sandbox_allow.arn
}

output "ecs_service_name" {
  description = "ECS service name, for log lookup and manual intervention."
  value       = module.civiform_service.aws_ecs_service_name
}

output "task_definition_arn" {
  description = "Task definition this sandbox is pinned to."
  value       = module.civiform_app.civiform_only_task_definition_arn
}

output "log_stream_prefix" {
  description = <<-EOT
    Prefix identifying this sandbox's streams within the shared log group.

    The civiform_app module hardcodes an "ecs" awslogs stream prefix and names
    the container "<app_prefix>-civiform", so streams land at
    ecs/<container>/<task-id>. Because the log group is shared across all
    sandboxes, this prefix is the only thing separating them.
  EOT
  value       = "ecs/${module.civiform_app.server_container_name}"
}
