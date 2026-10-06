# ── Shared KMS key for sandbox secrets ────────────────────────────────────────
#
# One customer-managed key encrypts every sandbox's Secrets Manager entries —
# both the shared placeholders in placeholder_secrets.tf and the per-sandbox
# DB/app secrets the builder writes at provisioning time.
#
# Why a customer-managed key at all, rather than the free `aws/secretsmanager`
# AWS-managed key:
#
#   1. The upstream `civiform_app` module takes a `kms_key_arns` input and has a
#      validation rule requiring it to be non-empty. It uses those ARNs to grant
#      kms:Decrypt to the ECS task execution role it creates. Passing the
#      AWS-managed key ARN there does not work: AWS-managed keys have an
#      immutable key policy that only permits the owning service, so an
#      IAM-side grant is not sufficient.
#   2. A single key we control means one place to audit access to every
#      sandbox's credentials, and one key to disable if a sandbox leaks.
#
# Why *one* key shared across sandboxes rather than one per sandbox: KMS charges
# $1/month per key regardless of use, which would roughly double the marginal
# cost of a sandbox. The isolation a per-sandbox key buys is already provided by
# the per-sandbox secret names and the resource-scoped IAM policies, since every
# sandbox task runs under a role that can only read `civiform-sandbox_*` secrets
# belonging to it.

resource "aws_kms_key" "sandbox_secrets" {
  description = "Encrypts Secrets Manager entries for all CiviForm sandbox instances"

  # 7 days is the AWS minimum. Sandboxes are ephemeral and this key is
  # recreatable from scratch, so there is no reason to sit through the 30-day
  # default if we ever need to tear the platform down and rebuild it.
  deletion_window_in_days = 7

  enable_key_rotation = true

  tags = { Name = "civiform-sandbox-secrets" }
}

resource "aws_kms_alias" "sandbox_secrets" {
  name          = "alias/civiform-sandbox-secrets"
  target_key_id = aws_kms_key.sandbox_secrets.key_id
}

# ── Outputs ───────────────────────────────────────────────────────────────────

output "sandbox_secrets_kms_key_arn" {
  description = "Shared KMS key ARN — passed to civiform_app as kms_key_arns, and used by the builder when it creates per-sandbox secrets"
  value       = aws_kms_key.sandbox_secrets.arn
}
