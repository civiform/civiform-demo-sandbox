---
name: run-dev
description: >-
  Start, stop, and interact with the local cf-sandbox-builder development
  environment using Docker Compose. Use this skill when the user wants to
  run the app locally, check logs, rebuild containers, or run tests.
---

# Skill: Local Development Environment

## Starting the Dev Stack

```bash
# From repo root — starts Postgres + Builder (port 9000 + 5173)
./bin/run-dev
```

- Web UI: http://localhost:9000
- Health check: http://localhost:9000/health
- Vite dev server (frontend hot-reload): http://localhost:5173

## Stopping

```bash
./bin/stop-dev
```

## Rebuilding the Container Image

```bash
./bin/build-dev
```

## Running SBT Commands (inside container)

```bash
# e.g., run tests
./bin/sbt test

# compile only
./bin/sbt compile
```

## Running npm Commands (inside container)

```bash
# install deps
./bin/npm install

# build frontend assets
./bin/npm run build

# watch mode
./bin/npm run build:watch
```

## Docker Compose Details

- **`docker-compose.yml`**: Base config — Postgres 16-alpine + `cf-sandbox-builder-dev` image
  - Postgres: port 5432, DB `sandbox_builder`, user `postgres`, password `example`
  - Builder: ports 9000 (Play) + 5173 (Vite), entrypoint `/bin/bash`
- **`docker-compose.dev.yml`**: Dev overrides with volume mounts for live code reload

## Checking Logs

```bash
docker compose logs -f builder
docker compose logs -f db
```

## Resetting the Database

```bash
docker compose down -v   # removes postgres_data volume
./bin/run-dev            # reinitializes via init_postgres.sql
```
