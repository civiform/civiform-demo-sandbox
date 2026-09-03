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

# ── IAM: ECS Task Execution Role ──────────────────────────────────────────────
# Allows ECS to pull the CiviForm image and write logs.

resource "aws_iam_role" "ecs_execution" {
  name = "CiviformSandboxEcsExecutionRole"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ecs_execution_managed" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# ── IAM: ECS Task Role (CiviForm app permissions) ─────────────────────────────
# Scoped to sandbox-specific secrets only. No OIDC/ADFS/ESRI secrets needed —
# we use FAKE_IDP for MVP (STAGING_DISABLE_DEMO_MODE_LOGINS=false).

resource "aws_iam_role" "civiform_sandbox_task" {
  name = "CiviformSandboxTaskRole"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "sandbox_task_secrets" {
  name = "CiviformSandboxTaskSecretsPolicy"
  role = aws_iam_role.civiform_sandbox_task.id

  policy = jsonencode({
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

# ── IAM: Builder Service Role ─────────────────────────────────────────────────
# The cf-sandbox-builder Play app (running on the Exygy machine or its own ECS task)
# needs these permissions to manage sandbox lifecycle.

resource "aws_iam_role" "sandbox_builder" {
  name = "CiviformSandboxBuilderRole"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "sandbox_builder_policy" {
  name = "CiviformSandboxBuilderPolicy"
  role = aws_iam_role.sandbox_builder.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # Register + deregister ECS task definitions per sandbox
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
        # Pass task + execution roles to ECS
        Sid    = "PassRole"
        Effect = "Allow"
        Action = "iam:PassRole"
        Resource = [
          aws_iam_role.ecs_execution.arn,
          aws_iam_role.civiform_sandbox_task.arn,
        ]
      },
      {
        # Create/delete per-sandbox secrets
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
        # Read the RDS master password to create per-sandbox schemas
        Sid    = "RdsMasterSecret"
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue"]
        Resource = aws_secretsmanager_secret.rds_master_password.arn
      },
      {
        # Add/remove ALB listener rules for per-sandbox subdomains
        Sid    = "AlbRuleManagement"
        Effect = "Allow"
        Action = [
          "elasticloadbalancing:CreateRule",
          "elasticloadbalancing:DeleteRule",
          "elasticloadbalancing:CreateTargetGroup",
          "elasticloadbalancing:DeleteTargetGroup",
          "elasticloadbalancing:RegisterTargets",
          "elasticloadbalancing:DeregisterTargets",
          "elasticloadbalancing:DescribeTargetGroups",
          "elasticloadbalancing:DescribeRules",
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
  value = aws_iam_role.ecs_execution.arn
}

output "civiform_sandbox_task_role_arn" {
  value = aws_iam_role.civiform_sandbox_task.arn
}

output "sandbox_builder_role_arn" {
  value = aws_iam_role.sandbox_builder.arn
}

output "cloudwatch_log_group" {
  value = aws_cloudwatch_log_group.sandbox_tasks.name
}
