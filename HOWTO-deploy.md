# cf-sandbox-builder — Deployment HOWTO
_For Exygy BD leads and conference presenters_

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

That's it — the portal is running.

### Creating a demo sandbox (local)

1. Go to **http://localhost:9001**
2. Click **"Create new demo"**
3. Fill in the 3-step wizard — city name, subdomain, PIN
4. Click **"Provision demo"**
5. A CiviForm container starts locally. It'll show **PROVISIONING** then **ACTIVE** in ~2 minutes.
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

## Option B — Deploy to AWS (Shareable Demo URL)

Use this for real prospect demos. Each sandbox gets a public URL:
`https://{city}.sandbox.civiform.dev`

### Prerequisites

You need:
1. **AWS credentials** — ask Rocky (`rocky@exygy.com`) for access to the Exygy sandbox AWS account
2. **Terraform** — `brew install terraform` (Mac) or https://developer.hashicorp.com/terraform/install
3. **AWS CLI** — `brew install awscli` + `aws configure` with your credentials
4. The repo cloned locally (same as Option A step 1)

### First-time infrastructure setup (do once)

```bash
cd terraform

# Initialize Terraform
terraform init

# Preview what will be created (safe — no changes yet)
terraform plan

# Create the AWS infrastructure (~5 min)
terraform apply
```

When `terraform apply` finishes, copy the outputs:

```bash
terraform output
```

You'll see values like:
```
alb_dns_name              = "civiform-sandbox-alb-xxxx.us-east-1.elb.amazonaws.com"
alb_https_listener_arn    = "arn:aws:elasticloadbalancing:..."
ecs_cluster_arn           = "arn:aws:ecs:..."
rds_endpoint              = "civiform-sandbox.xxxx.us-east-1.rds.amazonaws.com"
acm_validation_records    = { ... }  ← send these to Rocky for DNS
```

**Send Rocky:**
1. The `alb_dns_name` value → he creates `*.sandbox.civiform.dev → CNAME → <that value>`
2. The `acm_validation_records` → he adds the CNAME for SSL cert validation

After Rocky adds the DNS records, the wildcard cert goes active in ~5 min.

### Configure the builder app

Copy `.env.example` to `.env` and fill in the Terraform output values:

```bash
cp .env.example .env
# Edit .env — fill in SANDBOX_VPC_ID, ALB_LISTENER_ARN, ECS_CLUSTER, ECS_SUBNETS,
#             ECS_SECURITY_GROUP, ECS_EXECUTION_ROLE_ARN, ECS_TASK_ROLE_ARN, RDS_HOST
```

### Run the builder pointed at AWS

You can run the builder portal locally while sandboxes deploy to AWS:

```bash
# In .env, set:
SANDBOX_RUNTIME=fargate

# Then start as normal:
./bin/run-dev
```

Go to **http://localhost:9001** — create a sandbox — it provisions on AWS Fargate.
The URL the prospect visits will be `https://{subdomain}.sandbox.civiform.dev`.

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
docker compose logs -f builder   # check for Java compile errors
```

### Reset everything and start fresh (local)
```bash
docker compose down -v   # wipes the database volume
./bin/run-dev            # reinitializes from init_postgres.sql
```

### Sandbox stuck in PROVISIONING
- **Local**: run `docker ps` — the CiviForm container should appear within 30s
- **AWS**: open the AWS Console → ECS → cluster `civiform-sandbox-cluster` → Tasks tab

---

## Quick Reference

| What | Where |
|---|---|
| Portal (local) | http://localhost:9001 |
| Portal health check | http://localhost:9001/health |
| Logs | `docker compose logs -f builder` |
| Stop everything | `./bin/stop-dev` |
| AWS Console | https://console.aws.amazon.com |
| Rocky (AWS / DNS access) | rocky@exygy.com |
| Lesley (questions) | lkatzen@exygy.com |

---

## What the demo shows (once a sandbox is running)

Prospects land on the sandbox URL, enter the PIN, and see:

- 🏷️ **Persistent demo banner** — "Burlington, VT — Demo Environment — 30 days remaining"
- 🔄 **Role switcher** — toggle between [Resident Applicant] / [CiviForm Admin] / [Program Admin]
- 📋 **Pre-loaded programs** — housing, utility relief, food assistance with predicate rules
- 📊 **ROI benchmarks panel** — 75% application completion time reduction
- 📥 **Export Program Schema (JSON)** — for city IT leads to carry config into production
