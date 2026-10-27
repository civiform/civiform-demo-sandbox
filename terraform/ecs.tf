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

# Account-level S3 Block Public Access.
#
# Each sandbox creates two buckets in `terraform/sandbox/filestorage.tf`:
#   1. `civiform-sb-files-*`  — private (`block_public_policy = true` on the bucket)
#   2. `civiform-sb-public-*` — serves program summary images (`block_public_policy = false`
#      on the bucket, with a bucket policy granting public `s3:GetObject` to
#      `/program-summary-image/program-*` only)
#
# AWS enables account-wide `block_public_policy = true` by default in new
# accounts, which overrides per-bucket settings and causes `s3:PutBucketPolicy`
# on `civiform-sb-public-*` to fail with 403 AccessDenied ("public policies are
# prevented by the BlockPublicPolicy setting in S3 Block Public Access").
#
# Relaxing `block_public_policy` and `restrict_public_buckets` at the account
# level delegates the check to each bucket's own `aws_s3_bucket_public_access_block`
# (which remains `true` on `civiform-sb-files-*` and `civiform-sandbox-tfstate`).
resource "aws_s3_account_public_access_block" "sandbox_account" {
  account_id = data.aws_caller_identity.current.account_id

  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = false
  restrict_public_buckets = false
}

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
        # Mutating security group calls.
        #
        # NOTE: ec2:CreateSecurityGroup evaluates authorization against BOTH the
        # vpc/* resource and the new security-group/* resource. Neither resource
        # populates the `ec2:Vpc` condition key at creation time (the VPC ARN is
        # the resource itself, and the security group does not exist yet).
        #
        # Scoping the vpc/* resource to `aws_vpc.sandbox.arn` already confines
        # `CreateSecurityGroup` to the sandbox VPC — EC2 requires permission on
        # BOTH resources for the call to succeed, so a call specifying any other
        # VPC ID will fail on the vpc/* check.
        #
        # Existing security group mutations (Delete, Authorize/Revoke rules) DO
        # populate `ec2:Vpc` on `security-group/*` and are conditioned on it.
        Sid    = "Ec2CreateSecurityGroupInSandboxVpc"
        Effect = "Allow"
        Action = ["ec2:CreateSecurityGroup"]
        Resource = [
          aws_vpc.sandbox.arn,
          "arn:aws:ec2:${var.aws_region}:${data.aws_caller_identity.current.account_id}:security-group/*",
        ]
      },
      {
        Sid    = "Ec2SecurityGroupsInSandboxVpc"
        Effect = "Allow"
        Action = [
          "ec2:DeleteSecurityGroup",
          "ec2:AuthorizeSecurityGroupIngress",
          "ec2:AuthorizeSecurityGroupEgress",
          "ec2:RevokeSecurityGroupIngress",
          "ec2:RevokeSecurityGroupEgress",
          "ec2:ModifySecurityGroupRules",
          "ec2:UpdateSecurityGroupRuleDescriptionsIngress",
          "ec2:UpdateSecurityGroupRuleDescriptionsEgress",
          "ec2:CreateTags",
          "ec2:DeleteTags",
        ]
        Resource = [
          "arn:aws:ec2:${var.aws_region}:${data.aws_caller_identity.current.account_id}:security-group/*",
          "arn:aws:ec2:${var.aws_region}:${data.aws_caller_identity.current.account_id}:security-group-rule/*",
        ]
        Condition = {
          StringEqualsIfExists = { "ec2:Vpc" = aws_vpc.sandbox.arn }
        }
      },
      {
        Sid    = "Ec2TagOnCreateSecurityGroup"
        Effect = "Allow"
        Action = ["ec2:CreateTags"]
        Resource = [
          "arn:aws:ec2:${var.aws_region}:${data.aws_caller_identity.current.account_id}:security-group/*",
          "arn:aws:ec2:${var.aws_region}:${data.aws_caller_identity.current.account_id}:security-group-rule/*",
        ]
        Condition = {
          StringEquals = {
            "ec2:CreateAction" = [
              "CreateSecurityGroup",
              "AuthorizeSecurityGroupIngress",
              "AuthorizeSecurityGroupEgress",
            ]
          }
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
          "iam:UpdateRole",
          "iam:TagRole",
          "iam:UntagRole",
          "iam:ListRolePolicies",
          "iam:ListAttachedRolePolicies",
          "iam:ListInstanceProfilesForRole",
          "iam:CreatePolicy",
          "iam:DeletePolicy",
          "iam:GetPolicy",
          "iam:GetPolicyVersion",
          "iam:ListPolicyVersions",
          "iam:CreatePolicyVersion",
          "iam:DeletePolicyVersion",
          "iam:TagPolicy",
          "iam:UntagPolicy",
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
          "secretsmanager:UpdateSecret",
          "secretsmanager:PutSecretValue",
          "secretsmanager:GetSecretValue",
          "secretsmanager:DescribeSecret",
          "secretsmanager:GetResourcePolicy",
          "secretsmanager:PutResourcePolicy",
          "secretsmanager:DeleteResourcePolicy",
          "secretsmanager:ListSecretVersionIds",
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
          "application-autoscaling:TagResource",
          "application-autoscaling:UntagResource",
          "cloudwatch:PutMetricAlarm",
          "cloudwatch:DeleteAlarms",
          "cloudwatch:DescribeAlarms",
          "cloudwatch:ListTagsForResource",
          "cloudwatch:TagResource",
          "cloudwatch:UntagResource",
          "iam:CreateServiceLinkedRole",
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

# ── Builder Application (cf-sandbox-builder) ──────────────────────────────────
# Runs the generator portal itself on the shared cluster at https://sandbox.civiform.dev.

resource "aws_ecr_repository" "builder" {
  name                 = "civiform-sandbox-builder"
  image_tag_mutability = "MUTABLE"
  force_delete         = true

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = { Name = "civiform-sandbox-builder" }
}

resource "aws_ecr_lifecycle_policy" "builder" {
  repository = aws_ecr_repository.builder.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Keep the last 10 images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 10
        }
        action = {
          type = "expire"
        }
      }
    ]
  })
}

resource "aws_cloudwatch_log_group" "builder" {
  name              = "/ecs/civiform-sandbox-builder"
  retention_in_days = 30
  tags              = { Name = "civiform-sandbox-builder-logs" }
}

# Play session signing secret (APPLICATION_SECRET)
resource "random_password" "builder_app_secret" {
  length  = 48
  special = false
}

resource "aws_secretsmanager_secret" "builder_app_secret" {
  name                    = "civiform-sandbox/builder-app-secret"
  description             = "Play APPLICATION_SECRET for cf-sandbox-builder"
  kms_key_id              = aws_kms_key.sandbox_secrets.arn
  recovery_window_in_days = 0
  tags                    = { Name = "civiform-sandbox-builder-app-secret" }
}

resource "aws_secretsmanager_secret_version" "builder_app_secret" {
  secret_id     = aws_secretsmanager_secret.builder_app_secret.id
  secret_string = random_password.builder_app_secret.result
}

# Auth0 OIDC client secret (AUTH0_CLIENT_SECRET).
# Created with an initial placeholder so the ECS task starts cleanly before the
# real value is set via the AWS CLI or console; `ignore_changes` prevents a
# subsequent `terraform apply` from overwriting the real secret.
resource "aws_secretsmanager_secret" "auth0_client_secret" {
  name                    = "civiform-sandbox/auth0-client-secret"
  description             = "Auth0 OIDC Client Secret for cf-sandbox-builder"
  kms_key_id              = aws_kms_key.sandbox_secrets.arn
  recovery_window_in_days = 0
  tags                    = { Name = "civiform-sandbox-auth0-client-secret" }
}

resource "aws_secretsmanager_secret_version" "auth0_client_secret" {
  secret_id     = aws_secretsmanager_secret.auth0_client_secret.id
  secret_string = "REPLACE_WITH_AUTH0_CLIENT_SECRET"

  lifecycle {
    ignore_changes = [secret_string]
  }
}

# Execution role for the builder task: pulls the builder image from ECR, writes
# logs, and injects secrets from Secrets Manager at container startup.
module "sandbox_builder_execution_role" {
  source      = "./modules/iam_role"
  name        = "CiviformSandboxBuilderExecutionRole"
  description = "ECS task execution role for cf-sandbox-builder"

  managed_policy_arns = [
    "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
  ]

  policy_json = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ReadBuilderSecrets"
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue",
          "secretsmanager:DescribeSecret",
        ]
        Resource = [
          aws_secretsmanager_secret.rds_master_password.arn,
          aws_secretsmanager_secret.builder_app_secret.arn,
          aws_secretsmanager_secret.auth0_client_secret.arn,
        ]
      },
      {
        Sid      = "DecryptBuilderSecrets"
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = aws_kms_key.sandbox_secrets.arn
      },
    ]
  })
}

locals {
  # Inline platform outputs handed directly to the builder container via
  # PLATFORM_OUTPUTS_JSON. Building this from the Terraform resources themselves
  # means `terraform apply` wires every shared infrastructure ID into the
  # builder automatically — no manual `terraform output -json > ...` step.
  builder_platform_outputs_json = jsonencode({
    base_domain                 = var.domain
    rds_endpoint                = aws_db_instance.sandbox.address
    vpc_id                      = aws_vpc.sandbox.id
    private_subnet_ids          = aws_subnet.private[*].id
    alb_security_group_id       = aws_security_group.alb.id
    alb_https_listener_arn      = aws_lb_listener.https.arn
    ecs_cluster_arn             = aws_ecs_cluster.sandbox.arn
    ecs_cluster_name            = aws_ecs_cluster.sandbox.name
    cloudwatch_log_group        = aws_cloudwatch_log_group.sandbox_tasks.name
    sandbox_secrets_kms_key_arn = aws_kms_key.sandbox_secrets.arn
    placeholder_secrets = [
      for name in local.placeholder_secret_names : {
        name       = name
        value_arn  = aws_secretsmanager_secret_version.placeholder[name].secret_arn
        secret_arn = aws_secretsmanager_secret.placeholder[name].arn
      }
    ]
  })
}

resource "aws_ecs_task_definition" "builder" {
  family                   = "civiform-sandbox-builder"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.builder_task_cpu)
  memory                   = tostring(var.builder_task_memory)
  execution_role_arn       = module.sandbox_builder_execution_role.arn
  task_role_arn            = module.sandbox_builder_role.arn

  container_definitions = jsonencode([
    {
      name      = "builder"
      image     = "${aws_ecr_repository.builder.repository_url}:${var.builder_image_tag}"
      essential = true

      portMappings = [
        {
          containerPort = 9000
          hostPort      = 9000
          protocol      = "tcp"
        }
      ]

      environment = [
        { name = "SANDBOX_RUNTIME", value = "terraform" },
        { name = "APP_BASE_URL", value = "https://${var.domain}" },
        { name = "AWS_REGION", value = var.aws_region },
        { name = "TERRAFORM_STATE_BUCKET", value = var.state_bucket_name },
        { name = "CIVIFORM_IMAGE_TAG", value = split(":", var.civiform_image)[length(split(":", var.civiform_image)) - 1] },
        { name = "DB_JDBC_URL", value = "jdbc:postgresql://${aws_db_instance.sandbox.address}:${aws_db_instance.sandbox.port}/${var.db_name}?ssl=true&sslmode=require" },
        { name = "DB_USER", value = aws_db_instance.sandbox.username },
        { name = "RDS_ADMIN_DATABASE", value = "postgres" },
        { name = "RDS_ADMIN_USER", value = aws_db_instance.sandbox.username },
        { name = "AUTH0_DOMAIN", value = var.auth0_domain },
        { name = "AUTH0_CLIENT_ID", value = var.auth0_client_id },
        { name = "PLATFORM_OUTPUTS_JSON", value = local.builder_platform_outputs_json },
      ]

      secrets = [
        {
          name      = "APPLICATION_SECRET"
          valueFrom = aws_secretsmanager_secret.builder_app_secret.arn
        },
        {
          name      = "DB_PASSWORD"
          valueFrom = "${aws_secretsmanager_secret.rds_master_password.arn}:password::"
        },
        {
          name      = "RDS_ADMIN_PASSWORD"
          valueFrom = "${aws_secretsmanager_secret.rds_master_password.arn}:password::"
        },
        {
          name      = "AUTH0_CLIENT_SECRET"
          valueFrom = aws_secretsmanager_secret.auth0_client_secret.arn
        },
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.builder.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "builder"
        }
      }
    }
  ])

  tags = { Name = "civiform-sandbox-builder" }
}

resource "aws_ecs_service" "builder" {
  name            = "civiform-sandbox-builder"
  cluster         = aws_ecs_cluster.sandbox.id
  task_definition = aws_ecs_task_definition.builder.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  # Ensure the previous deployment is terminated before starting a new one.
  # This prevents the new builder instance from marking a creation or deletion
  # as failed while the previous builder instance is working on it.
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
  scheduling_strategy                = "REPLICA"
  availability_zone_rebalancing      = "DISABLED"

  # Allow Terraform apply to finish before the first Docker image is pushed to ECR.
  wait_for_steady_state = false

  network_configuration {
    subnets          = aws_subnet.private[*].id
    security_groups  = [aws_security_group.ecs_tasks.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.builder.arn
    container_name   = "builder"
    container_port   = 9000
  }

  depends_on = [aws_lb_listener_rule.builder]

  tags = { Name = "civiform-sandbox-builder" }
}

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

output "builder_ecr_repository_url" {
  description = "ECR repository URL for the cf-sandbox-builder image"
  value       = aws_ecr_repository.builder.repository_url
}

output "builder_ecs_service_name" {
  description = "ECS service name for cf-sandbox-builder"
  value       = aws_ecs_service.builder.name
}

output "auth0_client_secret_arn" {
  description = "Secrets Manager ARN where the Auth0 Client Secret should be stored"
  value       = aws_secretsmanager_secret.auth0_client_secret.arn
}

