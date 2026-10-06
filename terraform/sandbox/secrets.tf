# ── Per-sandbox secrets ───────────────────────────────────────────────────────
#
# The four secrets that carry real material. Everything else the CiviForm
# container expects comes from the shared placeholders in the platform stack.
#
# Two of these are generated here rather than passed in. SECRET_KEY and
# CIVIFORM_API_SECRET_SALT are not needed by anything before `apply`, so
# generating them in Terraform keeps them out of the builder's process memory
# and out of the tfvars file on disk. The database credentials cannot work that
# way: the builder must create the database role over JDBC before the container
# starts, so it necessarily knows that password already.
#
# All four are encrypted with the shared platform KMS key, which is also the key
# granted to the task execution role via civiform_app's kms_key_arns.

resource "random_password" "app_secret_key" {
  length  = 64
  special = false
}

resource "random_password" "api_secret_salt" {
  length  = 64
  special = false
}

locals {
  # Matches the `civiform-sandbox_*` resource scope in the platform IAM policies.
  secret_prefix = "civiform-sandbox_${var.sandbox_id}"

  secret_values = {
    DB_USERNAME              = var.db_username
    DB_PASSWORD              = var.db_password
    SECRET_KEY               = random_password.app_secret_key.result
    CIVIFORM_API_SECRET_SALT = random_password.api_secret_salt.result
  }
}

# Declared individually rather than with for_each so that main.tf's ordered
# secrets list can reference them by name. The list's order is load-bearing —
# a map would be iterated in lexical order and silently reshuffle the container
# definition.

resource "aws_secretsmanager_secret" "db_username" {
  name       = "${local.secret_prefix}_db_username"
  kms_key_id = var.kms_key_arn

  # Secrets Manager reserves a deleted name for the whole recovery window. With
  # the default 30 days, tearing a sandbox down and recreating it under the same
  # id would fail on a name collision — and 30 days is exactly the sandbox
  # lifetime, so this would surface precisely when reprovisioning an expired
  # demo for a returning prospect.
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "db_username" {
  secret_id     = aws_secretsmanager_secret.db_username.id
  secret_string = local.secret_values.DB_USERNAME
}

resource "aws_secretsmanager_secret" "db_password" {
  name                    = "${local.secret_prefix}_db_password"
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "db_password" {
  secret_id     = aws_secretsmanager_secret.db_password.id
  secret_string = local.secret_values.DB_PASSWORD
}

resource "aws_secretsmanager_secret" "app_secret_key" {
  name                    = "${local.secret_prefix}_app_secret_key"
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "app_secret_key" {
  secret_id     = aws_secretsmanager_secret.app_secret_key.id
  secret_string = local.secret_values.SECRET_KEY
}

resource "aws_secretsmanager_secret" "api_secret_salt" {
  name                    = "${local.secret_prefix}_api_secret_salt"
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "api_secret_salt" {
  secret_id     = aws_secretsmanager_secret.api_secret_salt.id
  secret_string = local.secret_values.CIVIFORM_API_SECRET_SALT
}
