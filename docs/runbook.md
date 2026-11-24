# Sandbox builder runbook

How to run the sandbox builder in AWS: who owns it, how to get access, how to deploy, what the daily sweeper does and what to do when it alerts, and how spending is watched. For architecture, environment variables and the full deploy steps, see the [deployment guide](deployment-guide.md). For local development, see the [README](../README.md).

## Owners

| Role | Who |
|---|---|
| Owner | (name) |
| Backup | (name) |

The owner watches `#civiform-sre-alerts` for this service, reads the spend alerts, and approves access requests for AWS, Auth0 and GitHub. Update this table when ownership changes.

## What lives where

| Thing | Where |
|---|---|
| Portal | `https://sandbox.civiform.dev` |
| AWS account | A dedicated account in Exygy's AWS organization, region `us-east-1`. Ask the owner for the account ID. |
| Builder service | ECS cluster `civiform-sandbox-cluster`, service `civiform-sandbox-builder` |
| Sandboxes | One ECS service per sandbox in the same cluster, named `sb-<id>-civiform-service` |
| Image | ECR repository `civiform-sandbox-builder` |
| Logs | CloudWatch `/ecs/civiform-sandbox-builder` (builder) and `/ecs/civiform-sandbox` (sandboxes), kept 30 days |
| Terraform state | S3 bucket `civiform-sandbox-tfstate`. Platform state is `sandbox/terraform.tfstate`, and each sandbox is `sandboxes/<id>/terraform.tfstate`. |
| Portal sign-in | Auth0 tenant `civiform-sandbox-builder.us.auth0.com` |
| DNS | Cloudflare, for `sandbox.civiform.dev` |

## Access

- **AWS:** Add the person in IAM Identity Center with the steps in [Add user to AWS account](https://github.com/civiform/civiform/wiki/Exygy-Admin-Info#add-user-to-aws-account). To give access to the builder account only, assign the `PowerUserAccess` permission set on that account under Multi-account permissions instead of adding the person to the Power Users group, which also reaches the other accounts it is assigned to.
- **GitHub:** Add the person as a collaborator on `civiform/civiform-demo-sandbox`.
- **Auth0:** Portal users need the `Sandbox builder users` role. See [Auth0 Setup](deployment-guide.md#auth0-setup). The owner decides who administers the tenant.
- **Cloudflare:** Only needed to change DNS, which is rare.

## Deploy

1. Refresh AWS credentials from SSO. The `Deploy to Production` workflow reads temporary credentials from the repository secrets `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` and `AWS_SESSION_TOKEN`, which expire within hours.
2. Run the `Deploy to Production` workflow from GitHub Actions. To deploy from your machine instead, run `./bin/deploy-prod`.
3. Check that the service is stable and healthy:

    ```bash
    aws ecs describe-services --region us-east-1 --cluster civiform-sandbox-cluster \
      --services civiform-sandbox-builder --query 'services[0].deployments'
    curl -s https://sandbox.civiform.dev/health
    ```

Every deploy rebuilds the `latest` image, so there is no tag to roll back to. To roll back, run the workflow from a branch or tag at the last good commit.

Changes under `terraform/` are not part of the workflow. Run `terraform plan` and read it before `terraform apply`, using the state key above and the commands in [Platform Infrastructure](deployment-guide.md#platform-infrastructure-terraform). A new task definition only takes effect through that apply. `--force-new-deployment` restarts the service on its current task definition.

## The daily sweeper

`DestroyExpiredSandboxesJob` runs every day at 02:00 UTC and does two things:

1. Destroys sandboxes that expired more than 7 days ago, including their database, and marks them `DELETED`. The grace period is `AUTODELETION_GRACE_DAYS`.
2. Disables sandboxes that expired in the last 7 days, and marks them `STOPPED`. Their infrastructure keeps running and costing money until the grace period ends.

If a destroy fails, the sandbox is marked `DELETE_FAILED` and the next daily run retries it. After the third failed attempt the job stops retrying and posts an alert.

The job's normal log lines don't reach CloudWatch, because the builder logs at `WARN`. A quiet log is normal. To check for trouble, search `/ecs/civiform-sandbox-builder` for `Failed to destroy expired sandbox` and `Failed to disable expired sandbox`. The dashboard shows each sandbox's current status.

## When the alert fires

The alert is posted to `#civiform-sre-alerts` with the title "CiviForm Sandbox automated teardown failed 3 times". It lists the sandbox ID, city, subdomain, database and the last error.

1. Find the sandbox's errors in `/ecs/civiform-sandbox-builder` by searching for its ID.
2. Click Delete on its row in the dashboard. This retries the teardown, which sometimes succeeds after a transient failure.
3. If that fails too, follow the manual steps under [DELETE_FAILED](deployment-guide.md#delete_failed).
4. Confirm nothing is left running: the `sb-<id>-civiform-service` ECS service, the ALB rules, the S3 buckets and the database.

A different alert, "CiviForm Sandbox DurableJob failure", means the job itself crashed. Its error report is in the alert and in the builder log.

## Alerts setup

The builder posts alerts only if the `SLACK_SRE_ALERTS_WEBHOOK_URL` secret is set. Without it, alerts are written to the builder log at `ERROR` and nobody is notified. Terraform defines the secret `civiform-sandbox/slack-sre-webhook-url` and passes it to the builder task. To set it up:

1. Create an incoming webhook for `#civiform-sre-alerts` in Slack.
2. Run `terraform plan` and then `terraform apply` in `terraform/`. This creates the secret with a placeholder value, registers a new task definition and updates the service.
3. Store the webhook URL in the secret:

    ```bash
    read -rs WEBHOOK_URL
    aws secretsmanager put-secret-value --region us-east-1 \
      --secret-id civiform-sandbox/slack-sre-webhook-url --secret-string "$WEBHOOK_URL"
    ```

4. Restart the builder so it reads the new value:

    ```bash
    aws ecs update-service --region us-east-1 --cluster civiform-sandbox-cluster \
      --service civiform-sandbox-builder --force-new-deployment
    ```

5. Check that the task definition lists `SLACK_SRE_ALERTS_WEBHOOK_URL` under `secrets`.

## Spending

Each running sandbox is its own Fargate service, and ECS is the largest cost in the account. A sandbox that is stuck, or expired but still inside its grace period, keeps costing money.

`terraform/budget.tf` defines a monthly budget for the whole account. It emails the addresses in `budget_alert_emails` at 80 percent of `monthly_budget_usd` spent and when the forecast passes 100 percent. No budget is created until `budget_alert_emails` is set, so set it when you apply, to a shared address that the owner reads. For example:

```bash
terraform apply -var='budget_alert_emails=["team-alias@example.com"]'
```

When an alert fires, compare the dashboard with `aws ecs list-services --cluster civiform-sandbox-cluster`. A `sb-<id>-civiform-service` with no matching running or `STOPPED` row is a leftover.
