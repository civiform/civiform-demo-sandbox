# ── ECS Cluster ───────────────────────────────────────────────────────────────

resource "aws_ecs_cluster" "sandbox" {
  name = "civiform-sandbox-cluster"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = { Name = "civiform-sandbox-cluster" }
}

resource "aws_ecs_cluster_capacity_providers" "sandbox" {
  cluster_name       = aws_ecs_cluster.sandbox.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
  }
}

# ── CloudWatch Log Group (shared across all sandbox tasks) ────────────────────

resource "aws_cloudwatch_log_group" "sandbox_tasks" {
  name              = "/ecs/civiform-sandbox"
  retention_in_days = 30
  tags              = { Name = "civiform-sandbox-ecs-logs" }
}

# ── IAM Roles ─────────────────────────────────────────────────────────────────
# All roles use the shared ./modules/iam_role module which provides the
# ECS task trust policy and wires inline + managed policies. This avoids
# repeating the assume_role_policy block for each role.

# 1. ECS Task Execution Role — allows ECS to pull image + write logs
module "ecs_execution_role" {
  source      = "./modules/iam_role"
  name        = "CiviformSandboxEcsExecutionRole"
  description = "Allows ECS agent to pull CiviForm image from ECR and write CloudWatch logs"

  # No inline policy needed — use the AWS-managed ECS execution policy
  managed_policy_arns = [
    "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
  ]
}

# 2. ECS Task Role — permissions the CiviForm app itself has at runtime
#    Scoped to sandbox-specific secrets only. No OIDC/ADFS/ESRI secrets needed —
#    FAKE_IDP is used for MVP (STAGING_DISABLE_DEMO_MODE_LOGINS=false).
module "civiform_sandbox_task_role" {
  source      = "./modules/iam_role"
  name        = "CiviformSandboxTaskRole"
  description = "Runtime permissions for the CiviForm app process inside sandbox containers"

  policy_json = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "secretsmanager:GetSecretValue",
        "secretsmanager:DescribeSecret"
      ]
      # Scoped to per-sandbox secrets only: civiform-sandbox_{id}_*
      Resource = "arn:aws:secretsmanager:${var.aws_region}:*:secret:civiform-sandbox_*"
    }]
  })
}

# 3. Builder Service Role — permissions the cf-sandbox-builder Play app needs
#    to manage the full sandbox lifecycle (create, monitor, teardown).
module "sandbox_builder_role" {
  source      = "./modules/iam_role"
  name        = "CiviformSandboxBuilderRole"
  description = "Allows cf-sandbox-builder to manage ECS tasks, ALB rules, Secrets Manager, and RDS schemas"

  policy_json = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # Register + run + stop ECS tasks per sandbox
        Sid    = "ECSTaskManagement"
        Effect = "Allow"
        Action = [
          "ecs:RegisterTaskDefinition",
          "ecs:DeregisterTaskDefinition",
          "ecs:RunTask",
          "ecs:StopTask",
          "ecs:DescribeTasks",
          "ecs:ListTasks",
        ]
        Resource = "*"
        Condition = {
          ArnLike = {
            "ecs:cluster" = aws_ecs_cluster.sandbox.arn
          }
        }
      },
      {
        # Pass task + execution roles to ECS at RunTask time
        Sid    = "PassRole"
        Effect = "Allow"
        Action = "iam:PassRole"
        Resource = [
          module.ecs_execution_role.arn,
          module.civiform_sandbox_task_role.arn,
        ]
      },
      {
        # Create/read/delete per-sandbox secrets
        Sid    = "SecretsManagement"
        Effect = "Allow"
        Action = [
          "secretsmanager:CreateSecret",
          "secretsmanager:PutSecretValue",
          "secretsmanager:DeleteSecret",
          "secretsmanager:GetSecretValue",
        ]
        Resource = "arn:aws:secretsmanager:${var.aws_region}:*:secret:civiform-sandbox_*"
      },
      {
        # Read the RDS master password to CREATE/DROP per-sandbox schemas
        Sid    = "RdsMasterSecret"
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue"]
        Resource = aws_secretsmanager_secret.rds_master_password.arn
      },
      {
        # Create/delete per-sandbox ALB target groups + listener rules (wildcard routing)
        Sid    = "AlbRuleManagement"
        Effect = "Allow"
        Action = [
          "elasticloadbalancing:CreateRule",
          "elasticloadbalancing:DeleteRule",
          "elasticloadbalancing:DescribeRules",
          "elasticloadbalancing:CreateTargetGroup",
          "elasticloadbalancing:DeleteTargetGroup",
          "elasticloadbalancing:DescribeTargetGroups",
          "elasticloadbalancing:DescribeTargetHealth",
          "elasticloadbalancing:RegisterTargets",
          "elasticloadbalancing:DeregisterTargets",
        ]
        Resource = "*"
      },
    ]
  })
}

# ── Outputs ───────────────────────────────────────────────────────────────────

output "ecs_cluster_arn" {
  value = aws_ecs_cluster.sandbox.arn
}

output "ecs_execution_role_arn" {
  value = module.ecs_execution_role.arn
}

output "civiform_sandbox_task_role_arn" {
  value = module.civiform_sandbox_task_role.arn
}

output "sandbox_builder_role_arn" {
  value = module.sandbox_builder_role.arn
}

output "cloudwatch_log_group" {
  value = aws_cloudwatch_log_group.sandbox_tasks.name
}
