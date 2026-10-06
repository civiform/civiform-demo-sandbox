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

- Web UI: http://localhost:9001
- Health check: http://localhost:9001/health
- Vite dev server (frontend hot-reload): http://localhost:5174

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
./bin/stop-dev -v   # or: see the note below
./bin/run-dev       # reinitializes via init_postgres.sql
```

> **Do not use a bare `docker compose down -v`.** `bin/lib.sh` sets
> `COMPOSE_PROJECT_NAME=cf-sandbox-builder`, but a plain `docker compose` invocation
> derives the project name from the directory instead (`civiform-demo-sandbox`). It will
> cheerfully report `Volume ... Removed` while removing a _different_ project's volume and
> leaving `cf-sandbox-builder_postgres_data` untouched — so the database is not reset and
> `init_postgres.sql` never re-runs. The symptom is confusing: schema changes appear not to
> take effect, with no error anywhere.
>
> If invoking compose directly, set the project name and pass both files:
>
> ```bash
> COMPOSE_PROJECT_NAME=cf-sandbox-builder \
>   docker compose -f docker-compose.yml -f docker-compose.dev.yml down -v
> ```

### `init_postgres.sql` must be world-readable

It is bind-mounted into `/docker-entrypoint-initdb.d/`, where the container's `postgres`
user reads it. Bind mounts preserve host permissions, so if the working copy is mode `0640`
the container cannot read it and Postgres skips initialization entirely — leaving an empty
database with only this line in `docker logs cf-sandbox-builder-db-1`:

```
psql: error: /docker-entrypoint-initdb.d/init_postgres.sql: Permission denied
```

Git records mode `0644`. To restore the tree to the modes git already tracks:

```bash
git ls-files -z | xargs -0 chmod u+rw,go+r-w
```
