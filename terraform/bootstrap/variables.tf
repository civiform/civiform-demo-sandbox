variable "aws_region" {
  description = "AWS region for the state bucket. Must match the region the other roots deploy into."
  type        = string
  default     = "us-east-1"
}

variable "state_bucket_name" {
  description = "Globally unique S3 bucket name for Terraform state"
  type        = string
  default     = "civiform-sandbox-tfstate"
}
