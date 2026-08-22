#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

cd "${ROOT_DIR}"

if [ ! -f ".env" ]; then
  echo ".env not found."
  echo "Create it first:"
  echo "cp .env.example .env"
  exit 1
fi

echo "======================================"
echo "Starting Core Infrastructure"
echo "======================================"

docker-compose \
  --env-file .env \
  -f docker/docker-compose.yml \
  up -d

echo
echo "======================================"
echo "Starting Observability"
echo "======================================"

docker-compose \
  --env-file .env \
  -f infrastructure/docker-compose.yml \
  up -d

echo
echo "======================================"
echo "Container Status"
echo "======================================"

docker ps \
  --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

echo
echo "Infrastructure started."