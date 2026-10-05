# Terraform state bootstrap

Creates the S3 bucket that holds Terraform state for every other root in this
repo. Apply once, by hand, before anything else.

```bash
terraform -chdir=terraform/bootstrap init
terraform -chdir=terraform/bootstrap apply
```

## State layout

One bucket, one state file per stack:

```
civiform-sandbox-tfstate/
├── platform/terraform.tfstate          # terraform/        — VPC, RDS, ALB, ECS cluster, KMS
└── sandboxes/
    ├── sb-burlington-vt-a1b2/terraform.tfstate
    ├── sb-providence-ri-c3d4/terraform.tfstate
    └── ...                             # terraform/sandbox/ — one apply per sandbox
```

Initialise a root against it with:

```bash
terraform -chdir=terraform init \
  -backend-config="bucket=civiform-sandbox-tfstate" \
  -backend-config="key=platform/terraform.tfstate" \
  -backend-config="region=us-east-1" \
  -backend-config="use_lockfile=true"
```

## Why this shape

**One state file per sandbox, not one shared file.** Terraform locks per state
file. A single shared state would serialise provisioning, so two sales reps
creating sandboxes at the same time would queue behind each other — and one
failed apply would block everyone.

**Separate state files, not workspaces.** Workspace selection is stateful CLI
context. A missed `terraform workspace select` applies the current
configuration to whichever workspace happens to be active, silently and
destructively. `-backend-config="key=..."` is scoped to the working directory
the builder creates per job, so there is no ambient state to get wrong.

**One bucket, not one bucket per sandbox.** The default account limit is 100
buckets; bucket names are globally unique, so a name collision would be a
provisioning-time failure with no good recovery; creation adds 30–60s to every
sandbox; and deletion requires emptying every object version first.

**S3, not the `pg` backend.** Putting state in the builder's own database would
mean that losing that database strands every AWS resource with no record of
what exists.

**Native S3 locking, not DynamoDB.** Terraform 1.10 added `use_lockfile`, which
puts the lock in the state bucket itself. The DynamoDB lock table is deprecated
as of 1.11. This is why the roots require `>= 1.10.0`.

## Why local state is acceptable here — and only here

This root creates the remote backend, so it cannot use one.

That is tolerable because of what this stack is: a bucket, a KMS key, and their
policies. It changes approximately never, it is applied by one person by hand,
and its "outputs" are two strings that are also discoverable from the AWS
console. If the local state file is lost, recovery is `terraform import` of
about eight resources, not a reconstruction.

The state file is gitignored. Do not commit it — it is not sensitive in the way
the sandbox state is, but committing it invites someone to apply from a stale
copy.

## Destroying

`aws_s3_bucket.tfstate` has `prevent_destroy = true`. This is deliberate:
destroying this bucket deletes the state for the platform stack and every live
sandbox, leaving real AWS resources running with no record of them. If you
genuinely need to tear it down, empty the bucket, remove the lifecycle block,
and apply — the friction is the point.
