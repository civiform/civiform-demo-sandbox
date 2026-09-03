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
