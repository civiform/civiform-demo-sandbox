terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
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
