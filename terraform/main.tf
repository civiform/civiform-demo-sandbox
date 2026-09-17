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

  # S3 backend — configure before first apply:
  #   terraform init \
  #     -backend-config="bucket=civiform-sandbox-tfstate" \
  #     -backend-config="key=sandbox/terraform.tfstate" \
  #     -backend-config="region=us-east-1"
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
