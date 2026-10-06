# ── Per-sandbox file storage ──────────────────────────────────────────────────
#
# Two buckets, mirroring a production CiviForm deployment: one private bucket
# for applicant uploads, one with a narrow public read path for program images.
#
# Simplified from cloud-deploy-infra's filestorage.tf in two ways:
#
#   - No access-log bucket. It exists upstream for audit retention; a sandbox
#     holds only synthetic data for 30 days, and a third bucket per sandbox is
#     a third thing teardown can leave behind.
#   - No per-sandbox file-storage KMS key. Upstream creates one; we reuse the
#     shared platform key, which is already granted to the task execution role
#     through civiform_app's kms_key_arns. Saves $1/month per sandbox.

locals {
  # Bucket names are globally unique across all of AWS, so the sandbox id alone
  # is not safe — a collision would be an unrecoverable provisioning failure in
  # someone else's account. The account id makes it unique without needing a
  # random suffix that would have to be tracked.
  bucket_suffix = "${var.sandbox_id}-${data.aws_caller_identity.current.account_id}"
}

# ── Applicant uploads (private) ───────────────────────────────────────────────

resource "aws_s3_bucket" "files" {
  bucket = "civiform-sb-files-${local.bucket_suffix}"

  # Required for teardown. `terraform destroy` fails on a non-empty bucket, and
  # a sandbox that has been demoed will not be empty. Safe here in a way it
  # never is in production: the contents are synthetic by policy.
  force_destroy = true

  tags = { Name = "${var.sandbox_id} CiviForm files" }
}

resource "aws_s3_bucket_public_access_block" "files" {
  bucket = aws_s3_bucket.files.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "files" {
  bucket = aws_s3_bucket.files.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "files" {
  bucket = aws_s3_bucket.files.id

  rule {
    apply_server_side_encryption_by_default {
      kms_master_key_id = var.kms_key_arn
      sse_algorithm     = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

# NOTE: upstream additionally attaches a bucket policy denying s3:* to every
# principal except the task execution role. That is deliberately omitted here.
#
# Combined with force_destroy = true it would be a teardown trap: the deny
# applies to the builder's own credentials too, so `terraform destroy` would be
# blocked from emptying the bucket and the sandbox would fail to tear down —
# leaving billable storage behind with no obvious cause. Upstream does not hit
# this because production sets force_destroy = false.
#
# Access is still closed: the public access block above, BucketOwnerEnforced
# ownership, and the fact that the only IAM grant to this bucket is the one
# civiform_app attaches to the task execution role.

# ── Public files, e.g. program images ─────────────────────────────────────────

resource "aws_s3_bucket" "public_files" {
  bucket        = "civiform-sb-public-${local.bucket_suffix}"
  force_destroy = true

  tags = { Name = "${var.sandbox_id} CiviForm public files" }
}

resource "aws_s3_bucket_public_access_block" "public_files" {
  bucket = aws_s3_bucket.public_files.id

  # Objects here are served to unauthenticated browsers, so the policy must be
  # allowed to grant public read. Scope is narrow — see the policy below.
  block_public_policy     = false
  restrict_public_buckets = false

  # ACLs stay blocked regardless. Ownership is BucketOwnerEnforced, so ACLs are
  # ignored anyway and policies are the single mechanism for granting access.
  block_public_acls  = true
  ignore_public_acls = true
}

resource "aws_s3_bucket_ownership_controls" "public_files" {
  bucket = aws_s3_bucket.public_files.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

data "aws_iam_policy_document" "public_files" {
  statement {
    sid     = "PublicReadProgramImages"
    effect  = "Allow"
    actions = ["s3:GetObject"]

    # Deliberately a prefix, not the whole bucket. The path is set by
    # PublicFileNameFormatter.java in the civiform repo; if that changes
    # upstream, program images 404 here and this resource is what to update.
    resources = ["${aws_s3_bucket.public_files.arn}/program-summary-image/program-*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }
  }
}

resource "aws_s3_bucket_policy" "public_files" {
  bucket = aws_s3_bucket.public_files.id
  policy = data.aws_iam_policy_document.public_files.json

  # The public access block must be relaxed before a public policy is accepted.
  depends_on = [aws_s3_bucket_public_access_block.public_files]
}
