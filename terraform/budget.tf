# Monthly spend alert for the whole AWS account the platform runs in.
#
# Opt-in: no budget is created until budget_alert_emails is set, so existing
# deployments are unchanged by default. AWS Budgets emails the subscribers
# directly, so no SNS topic is needed.
resource "aws_budgets_budget" "monthly_cost" {
  count = length(var.budget_alert_emails) > 0 ? 1 : 0

  name         = "${var.environment}-monthly-cost"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = var.budget_alert_emails
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = var.budget_alert_emails
  }
}
