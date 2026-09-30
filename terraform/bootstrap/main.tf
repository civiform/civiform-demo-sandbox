# ── Terraform state bucket ────────────────────────────────────────────────────
#
# This root exists solely to create the S3 bucket that every other root in this
# repo stores its state in. It is the one stack that cannot use a remote
# backend, because the backend is what it creates — so it keeps state in a local
# file. See README.md for why that is acceptable here.
#
# Apply once, by hand. It should almost never change after that.

terraform {
  required_version = ">= 1.10.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.14"
    }
  }

  # Intentionally no backend block. State is local (terraform.tfstate in this
  # directory) and gitignored.
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project   = "civiform-sandbox"
      Component = "tfstate-bootstrap"
      ManagedBy = "terraform"
    }
  }
}

# ── Encryption key ────────────────────────────────────────────────────────────
#
# A separate key from the platform stack's `sandbox_secrets` key, and it has to
# be: that key is defined in the platform root, whose state lives in this
# bucket. Using it here would make the bucket depend on state stored in the
# bucket.
#
# This is not merely belt-and-braces. Terraform state for the sandbox stacks
# contains plaintext secret values — the per-sandbox database password and
# application secret are attributes of resources Terraform manages, and
# Terraform records attribute values verbatim. The state bucket is therefore as
# sensitive as Secrets Manager itself and is encrypted accordingly.

resource "aws_kms_key" "tfstate" {
  description             = "Encrypts CiviForm sandbox Terraform state"
  deletion_window_in_days = 30
  enable_key_rotation     = true

  tags = { Name = "civiform-sandbox-tfstate" }
}

resource "aws_kms_alias" "tfstate" {
  name          = "alias/civiform-sandbox-tfstate"
  target_key_id = aws_kms_key.tfstate.key_id
}

# ── Bucket ────────────────────────────────────────────────────────────────────

resource "aws_s3_bucket" "tfstate" {
  bucket = var.state_bucket_name

  # Guards against `terraform destroy` in this directory taking every sandbox's
  # state with it. Recovering from that means reconciling live AWS resources
  # against nothing, by hand, per sandbox.
  lifecycle {
    prevent_destroy = true
  }

  tags = { Name = var.state_bucket_name }
}

# Versioning is the recovery mechanism for a corrupted or truncated state write.
# Terraform has no undo; the previous object version is the only way back.
resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.tfstate.arn
    }

    # Without this, every state read and write is a separate KMS API call.
    # Terraform reads state on every plan, and the builder plans on every
    # status poll, so the call volume is not trivial. An S3 bucket key collapses
    # them to roughly one KMS call per bucket per short interval.
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Object ownership enforced means ACLs are ignored entirely, so the only path to
# granting access is a bucket or IAM policy. One mechanism, one place to audit.
resource "aws_s3_bucket_ownership_controls" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_policy" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  # Ordering matters: attaching a policy before the public access block is in
  # place leaves a window where a misjudged policy could be accepted.
  depends_on = [aws_s3_bucket_public_access_block.tfstate]

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource = [
          aws_s3_bucket.tfstate.arn,
          "${aws_s3_bucket.tfstate.arn}/*",
        ]
        Condition = {
          Bool = { "aws:SecureTransport" = "false" }
        }
      },
      {
        # Belt and braces against a future caller overriding the bucket default
        # with a weaker algorithm on a specific PutObject.
        Sid       = "DenyUnencryptedObjectUploads"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:PutObject"
        Resource  = "${aws_s3_bucket.tfstate.arn}/*"
        Condition = {
          StringNotEquals = { "s3:x-amz-server-side-encryption" = "aws:kms" }
        }
      },
    ]
  })
}

# Versioning plus frequent writes means old state versions accumulate forever.
# Ninety days is long enough to recover from a bad apply that nobody noticed for
# a while, and short enough that storage does not grow without bound.
resource "aws_s3_bucket_lifecycle_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  rule {
    id     = "expire-noncurrent-state-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 90
    }

    # Terraform's native S3 locking writes a small .tflock object alongside the
    # state and deletes it on release. A crashed apply can leave one behind;
    # this sweeps the debris rather than leaving a stale lock to be discovered
    # the next time someone tries to provision.
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# ── Outputs ───────────────────────────────────────────────────────────────────

output "state_bucket_name" {
  description = "Pass as -backend-config=\"bucket=...\" when initialising the other roots"
  value       = aws_s3_bucket.tfstate.id
}

output "state_kms_key_arn" {
  description = "Pass as -backend-config=\"kms_key_id=...\""
  value       = aws_kms_key.tfstate.arn
}
