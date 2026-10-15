variable "aws_region" {
  description = "AWS region for all resources"
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Environment name (used in resource names and tags)"
  type        = string
  default     = "sandbox"
}

variable "domain" {
  description = "Base sandbox domain — sandboxes live at {slug}.sandbox.civiform.dev"
  type        = string
  default     = "sandbox.civiform.dev"
}

variable "civiform_image" {
  description = "CiviForm Docker image to run in each sandbox ECS task"
  type        = string
  default     = "civiform/civiform:latest"
}

variable "ecs_task_cpu" {
  description = "CPU units for each CiviForm sandbox ECS task (1024 = 1 vCPU)"
  type        = number
  default     = 512
}

variable "ecs_task_memory" {
  description = "Memory (MB) for each CiviForm sandbox ECS task"
  type        = number
  default     = 1024
}

variable "db_instance_class" {
  description = "RDS instance class for the shared sandbox Postgres instance"
  type        = string
  default     = "db.t3.micro"
}

variable "db_name" {
  description = "Database name on the sandbox RDS instance"
  type        = string
  default     = "civiform_sandbox"
}

variable "vpc_cidr" {
  description = "CIDR block for the sandbox VPC"
  type        = string
  default     = "10.100.0.0/16"
}

# Referenced by the builder IAM policy in ecs.tf, not by any resource here: the
# bucket itself is owned by terraform/bootstrap. Keep the default in sync with
# that stack's state_bucket_name or the builder will be granted access to a
# bucket that does not exist.
variable "state_bucket_name" {
  description = "Terraform state bucket created by terraform/bootstrap"
  type        = string
  default     = "civiform-sandbox-tfstate"
}

variable "builder_image_tag" {
  description = "Image tag in the builder ECR repository to deploy"
  type        = string
  default     = "latest"
}

variable "builder_task_cpu" {
  description = "CPU units for the cf-sandbox-builder ECS task (1024 = 1 vCPU)"
  type        = number
  default     = 1024
}

variable "builder_task_memory" {
  description = "Memory (MB) for the cf-sandbox-builder ECS task"
  type        = number
  default     = 2048
}

variable "auth0_domain" {
  description = "Auth0 tenant domain for portal OIDC login"
  type        = string
  default     = "civiform-sandbox-builder.us.auth0.com"
}

variable "auth0_client_id" {
  description = "Auth0 application Client ID for portal OIDC login"
  type        = string
  default     = ""
}


