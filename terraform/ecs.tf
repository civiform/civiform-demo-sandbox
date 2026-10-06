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

# ── IAM ───────────────────────────────────────────────────────────────────────
#
# Only one role lives here now: the builder's.
#
# The former CiviformSandboxEcsExecutionRole and CiviformSandboxTaskRole are
# gone. They existed for the AWS-SDK implementation, which registered task
# definitions by hand and had to pass shared roles into them. The upstream
# civiform_app module creates its own execution role per sandbox — and uses it
# for both task_role_arn and execution_role_arn — so shared roles would have
# been dead weight that still looked load-bearing.

# Terraform state lives in a bucket created by the bootstrap stack, encrypted
# with a key that stack owns. Looked up by alias rather than hardcoded so this
# fails clearly if bootstrap has not been applied.
data "aws_kms_key" "tfstate" {
  key_id = "alias/civiform-sandbox-tfstate"
}

data "aws_caller_identity" "current" {}

# Builder Service Role — what cf-sandbox-builder needs to run `terraform apply`
# against terraform/sandbox, plus create and drop per-sandbox databases.
#
# This is materially broader than the SDK-era policy it replaces, because
# Terraform creates the whole per-sandbox stack rather than just a task and a
# listener rule. Everything that can be scoped by name prefix is.
module "sandbox_builder_role" {
  source      = "./modules/iam_role"
  name        = "CiviformSandboxBuilderRole"
  description = "Runs terraform apply/destroy for per-sandbox stacks"

  policy_json = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # Per-sandbox state only.
        #
        # The exclusion of platform/ is the important part: that state file
        # contains the RDS master password in plaintext, along with every other
        # platform secret. A compromised builder must not be able to read it.
        Sid    = "TerraformSandboxState"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
        ]
        Resource = "arn:aws:s3:::${var.state_bucket_name}/sandboxes/*"
      },
      {
        # Terraform lists the prefix to discover existing state and lock files.
        # Condition-scoped so this does not become a way to enumerate platform/.
        Sid      = "TerraformStateList"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = "arn:aws:s3:::${var.state_bucket_name}"
        Condition = {
          StringLike = { "s3:prefix" = ["sandboxes/*"] }
        }
      },
      {
        # The state bucket is SSE-KMS, so reading and writing state needs the key.
        Sid    = "TerraformStateKms"
        Effect = "Allow"
        Action = [
          "kms:Decrypt",
          "kms:GenerateDataKey",
        ]
        Resource = data.aws_kms_key.tfstate.arn
      },
      {
        # Creating per-sandbox secrets encrypted with the shared key.
        Sid    = "SandboxSecretsKms"
        Effect = "Allow"
        Action = [
          "kms:Decrypt",
          "kms:Encrypt",
          "kms:GenerateDataKey",
          "kms:DescribeKey",
          "kms:CreateGrant",
        ]
        Resource = aws_kms_key.sandbox_secrets.arn
      },
      {
        # Task definitions and services. Not name-scopable: RegisterTaskDefinition
        # takes no resource, and DescribeTaskDefinition is needed for arbitrary
        # revisions. Constrained to this cluster where the API supports it.
        Sid    = "EcsSandboxWorkloads"
        Effect = "Allow"
        Action = [
          "ecs:RegisterTaskDefinition",
          "ecs:DeregisterTaskDefinition",
          "ecs:DescribeTaskDefinition",
          "ecs:ListTaskDefinitions",
          "ecs:CreateService",
          "ecs:UpdateService",
          "ecs:DeleteService",
          "ecs:DescribeServices",
          "ecs:DescribeClusters",
          "ecs:ListTasks",
          "ecs:DescribeTasks",
          "ecs:TagResource",
          "ecs:UntagResource",
        ]
        Resource = "*"
      },
      {
        # Target groups and listener rules. The ELB API does not support
        # name-prefix conditions, and Describe* must be unscoped for Terraform to
        # read back what it created.
        Sid    = "ElasticLoadBalancing"
        Effect = "Allow"
        Action = [
          "elasticloadbalancing:CreateTargetGroup",
          "elasticloadbalancing:DeleteTargetGroup",
          "elasticloadbalancing:ModifyTargetGroup",
          "elasticloadbalancing:ModifyTargetGroupAttributes",
          "elasticloadbalancing:CreateRule",
          "elasticloadbalancing:DeleteRule",
          "elasticloadbalancing:ModifyRule",
          "elasticloadbalancing:Describe*",
          "elasticloadbalancing:AddTags",
          "elasticloadbalancing:RemoveTags",
          "elasticloadbalancing:RegisterTargets",
          "elasticloadbalancing:DeregisterTargets",
        ]
        Resource = "*"
      },
      {
        # ecs_fargate_service creates its own per-service security group.
        # Read-only calls cannot be resource-scoped.
        Sid    = "Ec2Describe"
        Effect = "Allow"
        Action = [
          "ec2:DescribeSecurityGroups",
          "ec2:DescribeSecurityGroupRules",
          "ec2:DescribeSubnets",
          "ec2:DescribeVpcs",
          "ec2:DescribeNetworkInterfaces",
          "ec2:DescribeAvailabilityZones",
          "ec2:DescribeTags",
        ]
        Resource = "*"
      },
      {
        # Mutating security group calls, confined to the sandbox VPC so the
        # builder cannot touch security groups anywhere else in the account.
        Sid    = "Ec2SecurityGroupsInSandboxVpc"
        Effect = "Allow"
        Action = [
          "ec2:CreateSecurityGroup",
          "ec2:DeleteSecurityGroup",
          "ec2:AuthorizeSecurityGroupIngress",
          "ec2:AuthorizeSecurityGroupEgress",
          "ec2:RevokeSecurityGroupIngress",
          "ec2:RevokeSecurityGroupEgress",
          "ec2:CreateTags",
        ]
        Resource = "*"
        Condition = {
          StringEquals = { "ec2:Vpc" = aws_vpc.sandbox.arn }
        }
      },
      {
        # civiform_app creates a task execution role and a custom policy per
        # sandbox, named "<app_prefix>-civiform-...". Sandbox ids are "sb-<hex>",
        # so the prefix confines this to sandbox-owned principals.
        #
        # See the WARNING below: this is the statement to revisit first.
        Sid    = "IamSandboxRoles"
        Effect = "Allow"
        Action = [
          "iam:CreateRole",
          "iam:DeleteRole",
          "iam:GetRole",
          "iam:TagRole",
          "iam:ListRolePolicies",
          "iam:ListAttachedRolePolicies",
          "iam:CreatePolicy",
          "iam:DeletePolicy",
          "iam:GetPolicy",
          "iam:GetPolicyVersion",
          "iam:ListPolicyVersions",
          "iam:CreatePolicyVersion",
          "iam:DeletePolicyVersion",
          "iam:AttachRolePolicy",
          "iam:DetachRolePolicy",
        ]
        Resource = [
          "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/sb-*",
          "arn:aws:iam::${data.aws_caller_identity.current.account_id}:policy/sb-*",
        ]
      },
      {
        # Handing the freshly created execution role to ECS. Restricted by
        # service so the role cannot be passed to, say, EC2 or Lambda.
        Sid      = "PassSandboxExecutionRole"
        Effect   = "Allow"
        Action   = "iam:PassRole"
        Resource = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/sb-*"
        Condition = {
          StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" }
        }
      },
      {
        # Per-sandbox file storage buckets, created and force-destroyed by the
        # sandbox stack. Named civiform-sb-{files,public}-<id>-<account>.
        Sid    = "SandboxBuckets"
        Effect = "Allow"
        Action = ["s3:*"]
        Resource = [
          "arn:aws:s3:::civiform-sb-*",
          "arn:aws:s3:::civiform-sb-*/*",
        ]
      },
      {
        Sid    = "SandboxSecrets"
        Effect = "Allow"
        Action = [
          "secretsmanager:CreateSecret",
          "secretsmanager:DeleteSecret",
          "secretsmanager:PutSecretValue",
          "secretsmanager:GetSecretValue",
          "secretsmanager:DescribeSecret",
          "secretsmanager:TagResource",
          "secretsmanager:UntagResource",
        ]
        Resource = "arn:aws:secretsmanager:${var.aws_region}:*:secret:civiform-sandbox_*"
      },
      {
        # The RDS master password, read to CREATE and DROP per-sandbox databases
        # and roles.
        #
        # A separate statement because the pattern above does not reach it: the
        # per-sandbox secrets are named civiform-sandbox_<id>_<key> with an
        # underscore, while this one is civiform-sandbox/rds-master-password with
        # a slash. The names look alike enough that the gap is easy to miss, and
        # the symptom would be an AccessDenied several minutes into a provision.
        #
        # Read-only on purpose. The builder must never rotate or delete the
        # credential that every sandbox database depends on.
        Sid      = "RdsMasterSecret"
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
        Resource = aws_secretsmanager_secret.rds_master_password.arn
      },
      {
        # ecs_fargate_service always instantiates its autoscaling submodule, even
        # with min and max pinned equal, so these are required for apply to
        # succeed rather than for any scaling we actually want.
        Sid    = "AutoscalingAndAlarms"
        Effect = "Allow"
        Action = [
          "application-autoscaling:RegisterScalableTarget",
          "application-autoscaling:DeregisterScalableTarget",
          "application-autoscaling:DescribeScalableTargets",
          "application-autoscaling:PutScalingPolicy",
          "application-autoscaling:DeleteScalingPolicy",
          "application-autoscaling:DescribeScalingPolicies",
          "application-autoscaling:ListTagsForResource",
          "cloudwatch:PutMetricAlarm",
          "cloudwatch:DeleteAlarms",
          "cloudwatch:DescribeAlarms",
        ]
        Resource = "*"
      },
      {
        # The shared log group already exists; the sandbox only writes streams
        # into it. Deliberately no logs:DeleteLogGroup — one sandbox must not be
        # able to delete every other sandbox's logs.
        Sid    = "SharedLogGroup"
        Effect = "Allow"
        Action = [
          "logs:CreateLogStream",
          "logs:PutLogEvents",
          "logs:DescribeLogGroups",
          "logs:DescribeLogStreams",
        ]
        Resource = "${aws_cloudwatch_log_group.sandbox_tasks.arn}:*"
      },
    ]
  })
}

# ⚠️  KNOWN RESIDUAL RISK — resolve before this role is used in a shared account.
#
# IamSandboxRoles above grants iam:CreatePolicy and iam:AttachRolePolicy over
# sb-* names. The builder controls the *content* of policies it creates, so in
# principle it could write an over-broad policy under an sb- name, attach it to
# an sb- role, and pass that role to ECS. The sb-* scoping limits the blast
# radius but does not close the escalation path.
#
# The standard fix is a permissions boundary: require iam:PermissionsBoundary on
# CreateRole so every role the builder creates is capped regardless of what is
# attached to it. That cannot be done yet — the upstream civiform_app module
# does not expose a permissions_boundary argument on the role it creates, so the
# condition would fail every apply.
#
# Next step: a small PR to cloud-deploy-infra adding an optional
# `permissions_boundary` variable to civiform_app, then add here:
#
#   Condition = {
#     StringEquals = {
#       "iam:PermissionsBoundary" =
#         "arn:aws:iam::<account>:policy/CiviformSandboxBoundary"
#     }
#   }
#
# Until then this role should live in an account dedicated to sandboxes.

# ── Outputs ───────────────────────────────────────────────────────────────────

output "ecs_cluster_arn" {
  value = aws_ecs_cluster.sandbox.arn
}

# ecs_fargate_service needs the cluster name as well as the ARN: the ARN goes to the
# ECS service, while the autoscaling target is addressed by name.
output "ecs_cluster_name" {
  description = "Shared ECS cluster name"
  value       = aws_ecs_cluster.sandbox.name
}

output "sandbox_builder_role_arn" {
  value = module.sandbox_builder_role.arn
}

output "cloudwatch_log_group" {
  value = aws_cloudwatch_log_group.sandbox_tasks.name
}
