terraform {
  # 1.10 introduced native S3 state locking (use_lockfile), which is what lets the
  # per-sandbox stacks share one bucket without a DynamoDB lock table.
  required_version = ">= 1.10.0"

  required_providers {
    aws = {
      # Pinned to match cloud-deploy-infra's aws_oidc template. The upstream modules
      # we consume in terraform/sandbox do not pin a provider themselves, so they
      # inherit whatever the root declares -- and they were only ever validated
      # against v6. Keeping both roots on the same major avoids the two stacks
      # disagreeing about resource schemas.
      source  = "hashicorp/aws"
      version = "~> 6.14"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.0"
    }
  }

  # S3 backend. The bucket is created by the bootstrap root (terraform/bootstrap),
  # which must be applied first. Configure at init time rather than hardcoding,
  # so the same configuration can target a different account:
  #
  #   terraform init \
  #     -backend-config="bucket=civiform-sandbox-tfstate" \
  #     -backend-config="key=platform/terraform.tfstate" \
  #     -backend-config="region=us-east-1" \
  #     -backend-config="use_lockfile=true"
  #
  # The key is deliberately namespaced: per-sandbox stacks use
  # sandboxes/<id>/terraform.tfstate in the same bucket, so each sandbox locks
  # independently and concurrent provisioning does not serialise.
  #
  # use_lockfile is not a default. Without it there is no locking at all, and
  # two simultaneous applies will silently clobber each other's state.
  backend "s3" {
    encrypt = true
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "civiform-sandbox"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
