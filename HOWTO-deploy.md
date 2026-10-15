# cf-sandbox-builder — Deployment HOWTO
_For Exygy BD leads, engineers, and conference presenters_

---

## Option A — Run Locally (Laptop Demo)

Use this if you're presenting from your own machine and don't have cloud access yet.
Sandboxes spin up as Docker containers on your laptop — no AWS needed.

### Prerequisites

Install these once:

| Tool | Install |
|---|---|
| Docker Desktop | https://www.docker.com/products/docker-desktop/ |
| Git | https://git-scm.com/downloads |

### Setup (one-time)

```bash
# 1. Clone the repo
git clone https://github.com/civiform/civiform-demo-sandbox.git
cd civiform-demo-sandbox

# 2. Start the stack (Postgres + builder portal)
./bin/run-dev
```

Wait ~60 seconds for the Play server to compile and start. Then open:

> **http://localhost:9001**

Log in with `admin@civiform.dev` and password `demo` (or your configured `DEMO_PORTAL_PASSWORD`).

### Creating a demo sandbox (local)

1. Go to **http://localhost:9001**
2. Click **"Create new demo"**
3. Fill in the 3-step wizard — city name, subdomain, PIN
4. Click **"Create"**
5. A CiviForm container starts locally. It'll show **PROVISIONING** then **RUNNING** in ~2 minutes.
6. Share the URL (`http://localhost:{port}`) and PIN with your prospect

> ⚠️ **Local caveat**: the sandbox URL is `localhost:{port}` — only accessible on your machine.
> For a shareable URL, use Option B (AWS).

### Stop the stack

```bash
./bin/stop-dev
```

### Rebuild after code changes

```bash
./bin/build-dev
./bin/run-dev
```

---

## Option B — Deploy to AWS (`sandbox.civiform.dev`)

In AWS, both the **Builder Portal** (`https://sandbox.civiform.dev`) and all provisioned **Demo Sandboxes** (`https://{city}.sandbox.civiform.dev`) run on AWS ECS Fargate behind a single shared Application Load Balancer and shared RDS PostgreSQL instance.

### Prerequisites

1. **AWS credentials** — access to the Exygy sandbox AWS account (`aws configure` or `aws sso login`)
2. **Terraform >= 1.10** — `brew install terraform` or https://developer.hashicorp.com/terraform/install
3. **AWS CLI v2** & **Docker** installed locally

### Step 1: Bootstrap the Terraform State Bucket (One-Time per AWS Account)

The platform stack and every sandbox store their Terraform state in a shared, KMS-encrypted S3 bucket (`civiform-sandbox-tfstate`). Create it once:

```bash
terraform -chdir=terraform/bootstrap init
terraform -chdir=terraform/bootstrap apply
```

### Step 2: Provision the Platform & Builder Service Infrastructure

Initialize the main `terraform/` stack against the state bucket and apply it (pass your Auth0 tenant domain and Client ID so Auth0 OIDC login is enabled on the portal):

```bash
terraform -chdir=terraform init \
  -backend-config="bucket=civiform-sandbox-tfstate" \
  -backend-config="key=sandbox/terraform.tfstate" \
  -backend-config="region=us-east-1" \
  -backend-config="use_lockfile=true"

terraform -chdir=terraform apply \
  -var="auth0_domain=civiform-sandbox-builder.us.auth0.com" \
  -var="auth0_client_id=<YOUR_AUTH0_CLIENT_ID>"
```

When `terraform apply` finishes, inspect the outputs:

```bash
terraform -chdir=terraform output
```

### Step 3: Configure Cloudflare DNS & Auth0 (One-Time)

1. **Cloudflare DNS (`civiform.dev` zone)**:
   - **ACM Certificate Validation**: Add the CNAME record shown in `terraform -chdir=terraform output acm_validation_records` (set Proxy status to **DNS Only / grey cloud**).
   - **Builder Portal**: Add `CNAME` `sandbox.civiform.dev` → `<alb_dns_name>`.
   - **Sandbox Wildcard**: Add `CNAME` `*.sandbox.civiform.dev` → `<alb_dns_name>`.
   - Once the validation CNAME is live, AWS ACM automatically issues the certificate covering both `sandbox.civiform.dev` and `*.sandbox.civiform.dev` within ~5 minutes.

2. **Auth0 Client Secret & Callback URLs**:
   - Store the Auth0 Client Secret in AWS Secrets Manager:
     ```bash
     aws secretsmanager put-secret-value \
       --region us-east-1 \
       --secret-id civiform-sandbox/auth0-client-secret \
       --secret-string "<YOUR_AUTH0_CLIENT_SECRET>"
     ```
   - In the Auth0 Dashboard (`civiform-sandbox-builder` tenant):
     - **Allowed Callback URLs**: `https://sandbox.civiform.dev/callback`
     - **Allowed Logout URLs**: `https://sandbox.civiform.dev/login`

### Step 4: Build & Deploy the Builder Image to ECS

Run the deployment script to build `prod.Dockerfile`, push to the ECR repository, and roll out the `civiform-sandbox-builder` ECS service:

```bash
./bin/deploy-prod
```

On first boot, `cf-sandbox-builder` automatically initializes its Postgres tables and sequences in RDS and starts serving at **https://sandbox.civiform.dev** — no RDS bastion or manual SQL migration is required.

---

## Option C — Hybrid Development (Run Portal Locally, Provision Sandboxes to AWS)

If you are developing locally and want your local portal (`http://localhost:9001`) to provision sandboxes onto the AWS cluster, RDS is in a private subnet and requires an SSM tunnel:

```bash
# 1. Generate the local platform outputs file once
terraform -chdir=terraform output -json > terraform/platform-outputs.json

# 2. In terminal 1: start the SSM port-forward + Docker bridge relay (requires a running bastion)
./bin/rds-tunnel

# 3. In terminal 2: start the local builder pointed at AWS
export SANDBOX_RUNTIME=terraform
export RDS_ADMIN_HOST=host.docker.internal
export RDS_ADMIN_PORT=15432
export RDS_ADMIN_PASSWORD=$(./bin/rds-password)
./bin/run-dev
```

---

## Troubleshooting

### "Port already in use" (local)
```bash
./bin/stop-dev          # stop the stack
docker ps               # check for leftover containers
docker rm -f <id>       # remove them
./bin/run-dev           # restart
```

### Builder portal won't start
```bash
docker compose logs -f builder   # check for Java compile errors (local)
aws logs tail /ecs/civiform-sandbox-builder --follow --region us-east-1  # (AWS)
```

### Reset everything and start fresh (local)
```bash
docker compose down -v   # wipes the database volume
./bin/run-dev            # reinitializes from init_postgres.sql
```

### Sandbox stuck in PROVISIONING
- **Local**: run `docker ps` — the CiviForm container should appear within 30s
- **AWS**: check builder logs (`aws logs tail /ecs/civiform-sandbox-builder --follow`) or open AWS Console → ECS → cluster `civiform-sandbox-cluster` → Tasks tab

---

## Quick Reference

| What | Where |
|---|---|
| Portal (AWS) | https://sandbox.civiform.dev |
| Portal (local) | http://localhost:9001 |
| Portal health check | https://sandbox.civiform.dev/health |
| Builder logs (AWS) | `aws logs tail /ecs/civiform-sandbox-builder --follow` |
| Sandbox logs (AWS) | `aws logs tail /ecs/civiform-sandbox --follow` |
| Deploy new builder release | `./bin/deploy-prod` |
| Rocky (AWS / DNS access) | rocky@exygy.com |
| Lesley (questions) | lkatzen@exygy.com |
