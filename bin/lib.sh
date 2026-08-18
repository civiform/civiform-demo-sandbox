#! /usr/bin/env bash

set -euo pipefail

export COMPOSE_PROJECT_NAME="cf-sandbox-builder"

function docker::compose() {
  docker compose -f docker-compose.yml "$@"
}

function docker::compose_dev() {
  docker compose -f docker-compose.yml -f docker-compose.dev.yml "$@"
}
