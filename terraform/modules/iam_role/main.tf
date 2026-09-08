# terraform/modules/iam_role/main.tf
#
# Reusable local module: creates an IAM role with ECS task trust policy
# and attaches an inline policy document.
#
# Usage:
#   module "my_role" {
#     source      = "./modules/iam_role"
#     name        = "MyRoleName"
#     description = "Human-readable description"
#     policy_json = jsonencode({ ... })
#   }

variable "name" {
  description = "IAM role name"
  type        = string
}

variable "description" {
  description = "Human-readable description of what this role is for"
  type        = string
  default     = ""
}

variable "policy_json" {
  description = "Inline policy document JSON to attach to the role (optional)"
  type        = string
  default     = ""
}

variable "managed_policy_arns" {
  description = "AWS managed policy ARNs to attach (optional)"
  type        = list(string)
  default     = []
}

# All roles in this project are assumed by ECS tasks
resource "aws_iam_role" "this" {
  name        = var.name
  description = var.description

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# Inline policy (optional — skip if policy_json is empty)
resource "aws_iam_role_policy" "inline" {
  count  = var.policy_json != "" ? 1 : 0
  name   = "${var.name}Policy"
  role   = aws_iam_role.this.id
  policy = var.policy_json
}

# Managed policy attachments (optional)
resource "aws_iam_role_policy_attachment" "managed" {
  count      = length(var.managed_policy_arns)
  role       = aws_iam_role.this.name
  policy_arn = var.managed_policy_arns[count.index]
}

output "arn" {
  value = aws_iam_role.this.arn
}

output "name" {
  value = aws_iam_role.this.name
}

output "id" {
  value = aws_iam_role.this.id
}
