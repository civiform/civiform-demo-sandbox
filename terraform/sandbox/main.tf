# ── Per-sandbox CiviForm stack ────────────────────────────────────────────────
#
# One `terraform apply` of this root == one sandbox. Everything shared (VPC,
# RDS, ALB, ECS cluster, KMS, placeholder secrets) lives in the platform root
# and arrives here as input variables.
#
# The heavy lifting is done by two modules from civiform/cloud-deploy-infra, so
# a sandbox runs the same task definition and the same service wiring as a
# production CiviForm deployment. That is the whole point: a demo that diverges
# from production is not evidence of anything.

terraform {
  required_version = ">= 1.10.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.14"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.0"
    }
  }

  # Fully unconfigured: the builder supplies bucket, key, region and
  # use_lockfile at init time, with key = sandboxes/<sandbox_id>/terraform.tfstate.
  # That is what gives each sandbox its own state file and its own lock.
  backend "s3" {}
}

provider "aws" {
  region = var.aws_region

  # Applied to every resource in this stack, including those created inside the
  # upstream modules — which is why a bad value here fails the apply in seven
  # places at once rather than one.
  default_tags {
    tags = {
      Project    = "civiform-sandbox"
      ManagedBy  = "terraform"
      SandboxId  = var.sandbox_id
      SandboxFor = local.city_name_tag
    }
  }
}

data "aws_caller_identity" "current" {}

locals {
  fqdn = "${var.subdomain}.${var.base_domain}"

  # AWS tag values are restricted to letters, numbers, spaces and _ . : / = + - @
  # (the API enforces ^([\p{L}\p{Z}\p{N}_.:/=+\-@]*)$). Commas are not in that
  # set, and "Burlington, VT" is the canonical way a city name is written — so
  # the obvious input breaks the apply.
  #
  # Sanitised rather than validated away: city_name is free text typed by a
  # sales rep and is also the user-visible whitelabel branding, so rejecting
  # "Burlington, VT" would be the wrong trade. The tag drops the comma; the
  # branding below keeps it.
  #
  # Note this is not cosmetic — default_tags propagates to every resource in
  # both upstream modules, including the IAM role and target group, so an
  # invalid value fails the apply in seven places with errors that name AWS
  # internals rather than this variable.
  city_name_tag = replace(var.city_name, "/[^\\p{L}\\p{Z}\\p{N}_.:\\/=+\\-@]/", "")

  # Placeholder secrets deliberately withheld from the container.
  #
  # Supplying these two crashes the server at startup. AdfsClientProvider.get()
  # guards on client_id and secret but not on discovery_uri:
  #
  #   if (!configuration.hasPath("adfs.client_id")
  #       || !configuration.hasPath("adfs.secret")) {
  #     return null;
  #   }
  #   ...
  #   config.setDiscoveryURI(configuration.getString("adfs.discovery_uri"));
  #
  # adfs.discovery_uri has no default in CiviForm's auth.conf, so presenting a
  # client id and secret without it takes the provider past its own guard and
  # into a ConfigException during Guice injection. Withholding them keeps the
  # provider on its null path, which is what we want anyway: the admin IdP is
  # never exercised, because admins sign in through the demo-mode buttons.
  #
  # Verified against civiform/civiform:latest — adding only these two env vars
  # to an otherwise-working container reproduces the failure exactly.
  #
  # The alternative, supplying an ADFS_DISCOVERY_URI, would make every sandbox
  # boot depend on a reachable third-party OIDC endpoint in order to construct a
  # client no one ever uses.
  withheld_placeholder_secrets = ["ADFS_CLIENT_ID", "ADFS_SECRET"]

  # Order is significant — see the `secrets` variable docs in the civiform_app
  # module. This must stay in upstream's order, because the module renders it
  # straight into the container definition's secrets array.
  #
  # The first four carry real, sandbox-specific material and are created in
  # secrets.tf. The rest are shared inert placeholders from the platform stack:
  # sandboxes authenticate with FAKE_IDP, so no identity provider credential is
  # ever read. See terraform/placeholder_secrets.tf.
  civiform_secrets = concat(
    [
      {
        name       = "DB_USERNAME"
        value_arn  = aws_secretsmanager_secret_version.db_username.secret_arn
        secret_arn = aws_secretsmanager_secret.db_username.arn
      },
      {
        name       = "DB_PASSWORD"
        value_arn  = aws_secretsmanager_secret_version.db_password.secret_arn
        secret_arn = aws_secretsmanager_secret.db_password.arn
      },
      {
        name       = "SECRET_KEY"
        value_arn  = aws_secretsmanager_secret_version.app_secret_key.secret_arn
        secret_arn = aws_secretsmanager_secret.app_secret_key.arn
      },
      {
        name       = "CIVIFORM_API_SECRET_SALT"
        value_arn  = aws_secretsmanager_secret_version.api_secret_salt.secret_arn
        secret_arn = aws_secretsmanager_secret.api_secret_salt.arn
      },
    ],
    [
      for secret in var.placeholder_secrets : secret
      if !contains(local.withheld_placeholder_secrets, secret.name)
    ],
  )
}

# ── CiviForm task definition ──────────────────────────────────────────────────
#
# NOTE: `source` cannot be interpolated. Terraform resolves module sources
# before variables or locals exist, so the ref has to be a literal in every
# module block. When bumping the pin, bump it in both blocks below — there is
# no way to factor it into one place, and nothing will warn you if they drift.
#
# Pinned to daf8155 ("Move civiform container/task definition to its own module #600"),
# on main branch. A commit SHA rather than a branch: a moving ref would mean
# two sandboxes created a week apart silently run different infrastructure.
module "civiform_app" {
  source = "git::https://github.com/civiform/cloud-deploy-infra.git//cloud/aws/modules/civiform_app?ref=daf8155c367509e2acbe6d9d77fabd113917786d"

  app_prefix = var.sandbox_id
  aws_region = var.aws_region

  image_tag = var.civiform_image_tag

  # Both containers log to the shared platform group. Streams stay separable
  # because the module hardcodes an "ecs" stream prefix and names the container
  # "<app_prefix>-civiform", giving ecs/<sandbox_id>-civiform/<task-id>.
  log_group_name         = var.log_group_name
  scraper_log_group_name = var.log_group_name

  # Sandboxes deploy the civiform_only task definition, so the scraper is never
  # launched and prometheus_remote_write_endpoint stays empty.

  ecs_task_cpu    = var.ecs_task_cpu
  ecs_task_memory = var.ecs_task_memory

  # Must fit inside ecs_task_memory with headroom, or the task fails to place.
  ecs_server_container_memory             = var.ecs_task_memory
  ecs_server_container_memory_reservation = floor(var.ecs_task_memory / 2)

  secrets = local.civiform_secrets

  # The database and its user are created over JDBC by the builder before this
  # apply runs, because the container would fail its health check against a
  # database that does not exist yet and trip the deployment circuit breaker.
  db_jdbc_string = "jdbc:postgresql://${var.db_address}:5432/${var.db_name}?ssl=true&sslmode=require"

  file_storage_bucket_name        = aws_s3_bucket.files.id
  file_storage_bucket_arn         = aws_s3_bucket.files.arn
  public_file_storage_bucket_name = aws_s3_bucket.public_files.id

  kms_key_arns = [var.kms_key_arn]

  civiform_server_environment_variables = {
    BASE_URL = "https://${local.fqdn}"

    # City branding — the sandbox presents as the prospect's own government.
    WHITELABEL_CIVIC_ENTITY_SHORT_NAME = var.city_name
    WHITELABEL_CIVIC_ENTITY_FULL_NAME  = var.city_name

    # Demo affordances. All three are required by the product brief and all
    # three are exactly what must never be enabled on a real deployment.
    STAGING_DISABLE_DEMO_MODE_LOGINS   = "false"
    SHOW_NOT_PRODUCTION_BANNER_ENABLED = "true"

    # Load-bearing, and not obviously so. FakeAdminClient — the thing that
    # actually provides the [CiviForm Admin] / [Program Admin] personas — only
    # activates for hosts in ImmutableSet.of("localhost", "civiform",
    # staging_hostname). staging_hostname defaults to "", so without this the
    # server starts cleanly, serves pages, and silently renders no demo login
    # buttons at all. STAGING_DISABLE_DEMO_MODE_LOGINS=false is necessary but
    # not sufficient.
    STAGING_HOSTNAME = local.fqdn

    # No applicant identity provider.
    #
    # Was "generic-oidc", which is what local dev uses against the dev-oidc
    # container. That does not survive the move to ECS: generic-oidc requires
    # applicant_generic_oidc.discovery_uri, CiviForm's auth.conf gives it no
    # default, and there is no fake IdP reachable from a Fargate task — so the
    # server died during Guice injection before it ever bound a port.
    #
    # "disabled" binds a null applicant client (AuthIdentityProviderName.
    # DISABLED_APPLICANT), which is sound for a demo: residents browse through
    # GuestClient, which is always registered, and admins sign in through the
    # demo-mode buttons above. The alternative — pointing at the shared Auth0
    # staging tenant the way Exygy's hand-built demo sites do — would make every
    # sandbox boot depend on a third-party endpoint to support a login path no
    # evaluator is ever asked to use.
    #
    # Note the admin IdP is left at its "adfs" default and deliberately given no
    # ADFS_DISCOVERY_URI. Verified empirically against civiform/civiform:latest:
    # the server boots, serves /playIndex, and renders all four fake-admin
    # personas with no ADFS configuration present.
    CIVIFORM_APPLICANT_IDP = "disabled"

    STAGING_ADMIN_LIST = var.admin_email
  }
}

# ── ECS service ───────────────────────────────────────────────────────────────
#
# create_load_balancer = false is the change PR 1 added upstream. Without it
# this module insists on standing up its own ALB, which at ~$16/month each
# would cost more than everything else in a sandbox combined and would need its
# own certificate and DNS record.
module "civiform_service" {
  source = "git::https://github.com/civiform/cloud-deploy-infra.git//cloud/aws/modules/ecs_fargate_service?ref=daf8155c367509e2acbe6d9d77fabd113917786d"

  app_prefix = var.sandbox_id

  create_load_balancer          = false
  existing_lb_security_group_id = var.alb_security_group_id

  task_definition_arn = module.civiform_app.civiform_only_task_definition_arn

  # Declared but currently unused upstream: the module builds the ECS service's
  # load_balancer.container_name from its own app_prefix instead. That happens
  # to produce the same string, because both modules derive it identically from
  # the same app_prefix. Passing it anyway — it is a required variable, and it
  # documents the coupling that the two modules must share an app_prefix.
  container_name = module.civiform_app.server_container_name

  vpc_id          = var.vpc_id
  private_subnets = var.private_subnet_ids

  ecs_cluster_arn = var.ecs_cluster_arn
  # Required because the module always instantiates its autoscaling submodule,
  # even when min and max are pinned equal.
  ecs_cluster_name = var.ecs_cluster_name

  # A sandbox serves a handful of evaluators. Pinning min = max = 1 keeps cost
  # flat and predictable and removes scaling as a variable during a live demo.
  desired_count             = 1
  scale_target_min_capacity = 1
  scale_target_max_capacity = 1
}

# ── Routing ───────────────────────────────────────────────────────────────────
#
# The only per-sandbox routing resource. Cloudflare already holds a wildcard
# record for *.<base_domain> pointing at the shared ALB, and the ACM
# certificate covers the same wildcard, so a sandbox becomes reachable the
# instant this rule exists — no DNS write, no propagation wait, no certificate
# issuance. That removes the largest variable-latency step from provisioning.
resource "aws_lb_listener_rule" "sandbox" {
  listener_arn = var.alb_listener_arn

  # Allocated from a Postgres sequence in the builder. ALB rule priorities must
  # be unique per listener, and a sequence makes that atomic; a
  # describe-then-pick would race whenever two sandboxes are created at once.
  priority = var.listener_priority

  action {
    type             = "forward"
    target_group_arn = module.civiform_service.lb_https_target_group_arn
  }

  condition {
    host_header {
      values = [local.fqdn]
    }
  }

  tags = { Name = "${var.sandbox_id}-rule" }
}
