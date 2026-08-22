#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

cd "${ROOT_DIR}"

echo "Stopping observability..."

docker compose \
  --env-file .env \
  -f infrastructure/docker-compose.yml \
  down

echo "Stopping core infrastructure..."

docker compose \
  --env-file .env \
  -f docker/docker-compose.yml \
  down

echo
echo "Infrastructure stopped."
