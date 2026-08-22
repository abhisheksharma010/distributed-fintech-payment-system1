#!/usr/bin/env bash

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

PASS_COUNT=0
FAIL_COUNT=0
FAILED_STEPS=()

echo "======================================"
echo "Story 14 — Full Final Verification"
echo "======================================"
echo "Project root: ${ROOT_DIR}"

run_step() {

  local name="$1"
  shift

  echo
  echo "--------------------------------------"
  echo "${name}"
  echo "--------------------------------------"

  if "$@"; then

    echo "✅ ${name}"
    PASS_COUNT=$((PASS_COUNT + 1))

  else

    echo "❌ ${name}"
    FAIL_COUNT=$((FAIL_COUNT + 1))
    FAILED_STEPS+=("${name}")
  fi
}

check_port() {

  local port="$1"
  local name="$2"

  if lsof -i ":${port}" >/dev/null 2>&1; then
    echo "✅ ${name} running on ${port}"
    return 0
  fi

  echo "❌ ${name} NOT running on ${port}"
  return 1
}

check_container() {

  local name="$1"

  if docker ps \
      --format '{{.Names}}' |
      grep -q "^${name}$"; then

    echo "✅ ${name} running"
    return 0
  fi

  echo "❌ ${name} NOT running"
  return 1
}

cd "${ROOT_DIR}"

echo
echo "======================================"
echo "1. Infrastructure"
echo "======================================"

run_step \
  "PostgreSQL container" \
  check_container \
  fintech-postgres

run_step \
  "Redis container" \
  check_container \
  fintech-redis

run_step \
  "Kafka container" \
  check_container \
  fintech-kafka

run_step \
  "Zookeeper container" \
  check_container \
  fintech-zookeeper

echo
echo "======================================"
echo "2. All Five Service Test Suites"
echo "======================================"

run_step \
  "API Gateway tests" \
  bash -c "
    cd '${ROOT_DIR}/api-gateway'
    ./gradlew clean test
  "

run_step \
  "Identity Service tests" \
  bash -c "
    cd '${ROOT_DIR}/identity-service'
    ./gradlew clean test
  "

run_step \
  "Payment Service tests" \
  bash -c "
    cd '${ROOT_DIR}/payment-service'
    ./gradlew clean test
  "

run_step \
  "Ledger Service tests" \
  bash -c "
    cd '${ROOT_DIR}/ledger-service'
    ./gradlew clean test
  "

run_step \
  "Notification Service tests" \
  bash -c "
    cd '${ROOT_DIR}/notification-service'
    ./gradlew clean test
  "

echo
echo "======================================"
echo "3. Runtime Services"
echo "======================================"

run_step \
  "API Gateway port" \
  check_port \
  8081 \
  "API Gateway"

run_step \
  "Identity Service port" \
  check_port \
  8082 \
  "Identity Service"

run_step \
  "Payment Service port" \
  check_port \
  8083 \
  "Payment Service"

run_step \
  "Ledger Service port" \
  check_port \
  8084 \
  "Ledger Service"

run_step \
  "Notification Service port" \
  check_port \
  8085 \
  "Notification Service"

echo
echo "======================================"
echo "4. Story 14 E2E"
echo "======================================"

run_step \
  "Happy Path E2E" \
  "${ROOT_DIR}/scripts/e2e/happy-path.sh"

run_step \
  "Financial Failure E2E" \
  "${ROOT_DIR}/scripts/e2e/financial-failures.sh"

run_step \
  "Idempotency + Concurrency E2E" \
  "${ROOT_DIR}/scripts/e2e/idempotency-concurrency.sh"

run_step \
  "Kafka Outage E2E" \
  "${ROOT_DIR}/scripts/e2e/failures/kafka-outage.sh"

run_step \
  "Redis Outage E2E" \
  "${ROOT_DIR}/scripts/e2e/failures/redis-outage.sh"

run_step \
  "Already Processed Recovery E2E" \
  "${ROOT_DIR}/scripts/e2e/recovery/stuck-payment-recovery.sh"

run_step \
  "Not Processed Recovery E2E" \
  "${ROOT_DIR}/scripts/e2e/recovery/not-processed-recovery.sh"

run_step \
  "Recovery Exhaustion Safety" \
  "${ROOT_DIR}/scripts/e2e/recovery/recovery-exhaustion.sh"

run_step \
  "Security Regression E2E" \
  "${ROOT_DIR}/scripts/e2e/security-regression.sh"

run_step \
  "Observability Verification" \
  "${ROOT_DIR}/scripts/e2e/observability-verification.sh"

run_step \
  "Final Configuration Audit" \
  "${ROOT_DIR}/scripts/e2e/final-config-audit.sh"

echo
echo "======================================"
echo "STORY 14 FINAL RESULT"
echo "======================================"

echo "Passed: ${PASS_COUNT}"
echo "Failed: ${FAIL_COUNT}"

if [ "${FAIL_COUNT}" -ne 0 ]; then

  echo
  echo "Failed steps:"

  for step in "${FAILED_STEPS[@]}"; do
    echo "  ❌ ${step}"
  done

  echo
  echo "STORY 14 NOT YET VERIFIED"
  exit 1
fi

echo
echo "✅ API Gateway"
echo "✅ Identity Service"
echo "✅ Payment Service"
echo "✅ Ledger Service"
echo "✅ Notification Service"
echo "✅ Story 14 E2E scenarios"
echo
echo "======================================"
echo "STORY 14 COMPLETED & VERIFIED ✅"
echo "======================================"