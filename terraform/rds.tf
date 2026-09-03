# ── RDS Postgres: Shared Sandbox Instance ─────────────────────────────────────
# One RDS instance shared across all sandboxes.
# Each sandbox gets its own schema + user (same pattern as Sprint 1 Docker approach).
# Isolated from Exygy's main CiviForm RDS — separate instance entirely.

resource "random_password" "rds_master" {
  length  = 32
  special = false
}

resource "aws_db_subnet_group" "sandbox" {
  name       = "civiform-sandbox-db-subnet-group"
  subnet_ids = aws_subnet.private[*].id
  tags       = { Name = "civiform-sandbox-db-subnet-group" }
}

resource "aws_db_instance" "sandbox" {
  identifier = "civiform-sandbox-postgres"

  engine         = "postgres"
  engine_version = "16.3"
  instance_class = var.db_instance_class

  db_name  = var.db_name
  username = "sandbox_master"
  password = random_password.rds_master.result

  allocated_storage     = 20
  max_allocated_storage = 100
  storage_type          = "gp3"
  storage_encrypted     = true

  db_subnet_group_name   = aws_db_subnet_group.sandbox.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  # Sprint 2: single-AZ to minimise cost. Enable Multi-AZ in Sprint 8 hardening.
  multi_az            = false
  publicly_accessible = false

  backup_retention_period = 7
  deletion_protection     = false # set true before production
  skip_final_snapshot     = true  # set false before production

  tags = { Name = "civiform-sandbox-postgres" }
}

# Store master password in Secrets Manager so EcsFargateSandboxService can
# connect to create per-sandbox schemas at provisioning time.
resource "aws_secretsmanager_secret" "rds_master_password" {
  name                    = "civiform-sandbox/rds-master-password"
  recovery_window_in_days = 0 # allow immediate deletion during dev
}

resource "aws_secretsmanager_secret_version" "rds_master_password" {
  secret_id = aws_secretsmanager_secret.rds_master_password.id
  secret_string = jsonencode({
    username = aws_db_instance.sandbox.username
    password = random_password.rds_master.result
    host     = aws_db_instance.sandbox.address
    port     = aws_db_instance.sandbox.port
    dbname   = var.db_name
  })
}

# ── Outputs ───────────────────────────────────────────────────────────────────

output "rds_endpoint" {
  description = "RDS endpoint — used by EcsFargateSandboxService to build per-sandbox DATABASE_URL"
  value       = aws_db_instance.sandbox.address
}

output "rds_master_secret_arn" {
  description = "ARN of the RDS master password secret"
  value       = aws_secretsmanager_secret.rds_master_password.arn
  sensitive   = true
}
