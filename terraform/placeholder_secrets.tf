# ── Shared placeholder secrets ────────────────────────────────────────────────
#
# The upstream `civiform_app` module wires a fixed set of secrets into the
# container definition. Eleven entries are required; the container will not
# start if an ARN is missing. But sandboxes authenticate with CiviForm's
# built-in FAKE_IDP (STAGING_DISABLE_DEMO_MODE_LOGINS=false), so the identity
# provider credentials are never read at runtime.
#
# Rather than minting dead secrets per sandbox, create them once here and point
# every sandbox at the same ARNs. At $0.40/secret/month this saves ~$2.80 per
# sandbox per month against a marginal sandbox cost of roughly $22 — a little
# over 10%, and it scales with the number of concurrent demos.
#
# This is only safe because these values are genuinely inert. The four secrets
# that carry real, sandbox-specific material — DB_USERNAME, DB_PASSWORD,
# SECRET_KEY, CIVIFORM_API_SECRET_SALT — are created per sandbox by the builder
# and must never be shared. Sharing SECRET_KEY in particular would let a session
# cookie minted in one prospect's sandbox be replayed against another's.
#
# NOTE: the integration plan called for six placeholders (the OIDC/ADFS set).
# ESRI_ARCGIS_API_TOKEN is included as a seventh on the same reasoning: the
# address-correction service it authenticates is not part of the demo, so the
# value is equally inert and equally not per-sandbox.

locals {
  # Order is significant. The `civiform_app` module renders its `secrets` input
  # into the container definition in list order, so a reordering produces a
  # different task definition JSON and therefore a new revision and a rolling
  # redeploy. The sandbox stack concatenates its four real secrets ahead of this
  # list; keep both halves stable.
  #
  # Names must match the environment variables CiviForm reads.
  placeholder_secret_names = [
    "ADFS_SECRET",
    "ADFS_CLIENT_ID",
    "APPLICANT_OIDC_CLIENT_ID",
    "APPLICANT_OIDC_CLIENT_SECRET",
    "ADMIN_OIDC_CLIENT_ID",
    "ADMIN_OIDC_CLIENT_SECRET",
    "ESRI_ARCGIS_API_TOKEN",
  ]
}

resource "aws_secretsmanager_secret" "placeholder" {
  for_each = toset(local.placeholder_secret_names)

  # The `civiform-sandbox_` prefix is load-bearing: the task role policy in
  # ecs.tf scopes secretsmanager:GetSecretValue to `civiform-sandbox_*`.
  name        = "civiform-sandbox_shared_${each.key}"
  description = "Inert placeholder — sandboxes use FAKE_IDP and never read this value"

  kms_key_id = aws_kms_key.sandbox_secrets.arn

  # Secrets Manager soft-deletes by default, and a name stays reserved for the
  # whole recovery window. Zero lets the platform stack be destroyed and
  # reapplied back to back, which matters while this is still being iterated on.
  recovery_window_in_days = 0

  tags = { Name = "civiform-sandbox-shared-${lower(each.key)}" }
}

resource "aws_secretsmanager_secret_version" "placeholder" {
  for_each = aws_secretsmanager_secret.placeholder

  secret_id = each.value.id

  # Deliberately not a random value. These are readable by anyone who can read
  # the sandbox secrets, and a plausible-looking random string invites someone
  # to wonder whether it is real. An explicit self-describing string does not.
  secret_string = "placeholder-not-used-sandboxes-use-fake-idp"
}

# ── Outputs ───────────────────────────────────────────────────────────────────

output "placeholder_secrets" {
  description = "Shared inert secrets, in civiform_app `secrets` input shape. The sandbox stack appends this to its own per-sandbox secrets."

  # Both fields are the same ARN, and that is correct rather than an oversight.
  #
  # Secrets Manager has no such thing as a version ARN — a version is addressed
  # by VersionId or VersionStage against the secret's ARN. The AWS provider
  # exposes `arn` on aws_secretsmanager_secret_version purely for historical
  # reasons; it returns the secret's ARN and is now formally deprecated in
  # favour of `secret_arn`. Upstream's app.tf still reads the deprecated
  # attribute, which is why its two fields look different but resolve
  # identically.
  #
  # The practical consequence is that ECS resolves these to AWSCURRENT at task
  # start, so a sandbox picks up a rotated value on its next deployment without
  # the task definition changing. That is the behaviour production CiviForm has
  # always had.
  #
  # Both fields are still populated from different resources on purpose: reading
  # `secret_arn` off the *version* resource makes Terraform order the value
  # write before any consumer, which reading it off the secret alone would not.
  value = [
    for name in local.placeholder_secret_names : {
      name       = name
      value_arn  = aws_secretsmanager_secret_version.placeholder[name].secret_arn
      secret_arn = aws_secretsmanager_secret.placeholder[name].arn
    }
  ]
}
