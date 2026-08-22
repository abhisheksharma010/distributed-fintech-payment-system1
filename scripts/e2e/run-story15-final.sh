#!/usr/bin/env bash

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

PASS_COUNT=0
FAIL_COUNT=0
WARN_COUNT=0
FAILED_STEPS=()

cd "${ROOT_DIR}"

echo "======================================"
echo "Story 15 — Final Verification"
echo "======================================"

pass() {
  echo "✅ $1"
  PASS_COUNT=$((PASS_COUNT + 1))
}

fail() {
  echo "❌ $1"
  FAIL_COUNT=$((FAIL_COUNT + 1))
  FAILED_STEPS+=("$1")
}

warn() {
  echo "⚠️  $1"
  WARN_COUNT=$((WARN_COUNT + 1))
}

run_step() {

  local name="$1"
  shift

  echo
  echo "--------------------------------------"
  echo "${name}"
  echo "--------------------------------------"

  if "$@"; then
    pass "${name}"
  else
    fail "${name}"
  fi
}

echo
echo "======================================"
echo "1. Required Project Files"
echo "======================================"

if [ -f "README.md" ]; then
  pass "README.md exists"
else
  fail "README.md missing"
fi

if [ -f ".env.example" ]; then
  pass ".env.example exists"
else
  fail ".env.example missing"
fi

if [ -f ".gitignore" ]; then
  pass ".gitignore exists"
else
  fail ".gitignore missing"
fi

if [ -f "scripts/local/start-infra.sh" ]; then
  pass "Infrastructure startup script exists"
else
  fail "Infrastructure startup script missing"
fi

if [ -f "scripts/local/stop-infra.sh" ]; then
  pass "Infrastructure shutdown script exists"
else
  fail "Infrastructure shutdown script missing"
fi

echo
echo "======================================"
echo "2. Git Hygiene"
echo "======================================"

if git ls-files |
   grep -Eq '(^|/)(build|\.gradle|\.idea|__MACOSX)(/|$)|\.DS_Store$'; then

  git ls-files |
    grep -E '(^|/)(build|\.gradle|\.idea|__MACOSX)(/|$)|\.DS_Store$'

  fail "Generated/IDE files are tracked"

else
  pass "No generated/IDE files tracked"
fi

if git ls-files --error-unmatch .env >/dev/null 2>&1; then
  fail ".env is committed"
else
  pass ".env is not committed"
fi

echo
echo "======================================"
echo "3. TODO / Debug Audit"
echo "======================================"

TODO_OUTPUT="$(
  git grep -nE 'TODO|FIXME|HACK|XXX' -- ':!README.md' 2>/dev/null || true
)"

if [ -z "${TODO_OUTPUT}" ]; then
  pass "No unresolved TODO/FIXME/HACK markers"
else
  echo "${TODO_OUTPUT}"
  warn "TODO/FIXME/HACK markers require review"
fi

DEBUG_OUTPUT="$(
  git grep -nE \
    'System\.out\.print|printStackTrace\(' \
    -- \
    '*/src/main/java/**' \
    2>/dev/null || true
)"

if [ -z "${DEBUG_OUTPUT}" ]; then
  pass "No production debug printing"
else
  echo "${DEBUG_OUTPUT}"
  fail "Production debug printing remains"
fi

echo
echo "======================================"
echo "4. Java Package Consistency"
echo "======================================"

python3 - <<'PY'
from pathlib import Path
import re
import sys

services = [
    "api-gateway",
    "identity-service",
    "payment-service",
    "ledger-service",
    "notification-service",
]

bad = []

for service in services:
    root = Path(service) / "src/main/java"

    for f in root.rglob("*.java"):
        text = f.read_text(errors="ignore")

        m = re.search(
            r'^\s*package\s+([A-Za-z0-9_.]+)\s*;',
            text,
            re.MULTILINE
        )

        if not m:
            continue

        package = m.group(1)

        expected = Path(*package.split(".")) / f.name
        actual = f.relative_to(root)

        if actual != expected:
            bad.append((f, root / expected))

if bad:
    for actual, expected in bad:
        print(f"MISMATCH: {actual}")
        print(f"EXPECTED: {expected}")
    sys.exit(1)

print("All Java package paths are consistent.")
PY

if [ $? -eq 0 ]; then
  pass "Java package paths consistent"
else
  fail "Java package/path mismatch detected"
fi

echo
echo "======================================"
echo "5. Environment Documentation"
echo "======================================"

for key in \
  DB_USERNAME \
  DB_PASSWORD \
  JWT_SECRET \
  KAFKA_BOOTSTRAP_SERVERS \
  REDIS_HOST \
  REDIS_PORT \
  IDENTITY_SERVICE_URL \
  PAYMENT_SERVICE_URL \
  LEDGER_SERVICE_URL \
  NOTIFICATION_SERVICE_URL \
  ZIPKIN_ENDPOINT
do

  if grep -q "^${key}=" .env.example; then
    pass "${key} documented"
  else
    fail "${key} missing from .env.example"
  fi
done

echo
echo "======================================"
echo "6. README Required Sections"
echo "======================================"

for section in \
  "Architecture" \
  "Services" \
  "Technology Stack" \
  "Core Payment Flow" \
  "Reliability Patterns" \
  "Security" \
  "Kafka Topics" \
  "Local Infrastructure" \
  "Starting the Services" \
  "Main APIs" \
  "Local Demo Flow" \
  "Testing" \
  "Observability"
do

  if grep -qi "${section}" README.md; then
    pass "README section: ${section}"
  else
    fail "README missing section: ${section}"
  fi
done

echo
echo "======================================"
echo "7. Notification Documentation"
echo "======================================"

if grep -qi \
  "does not expose a public notification-history HTTP API" \
  README.md; then

  pass "Notification API limitation documented"

else

  warn "Notification read API limitation not explicitly documented"
fi

echo
echo "======================================"
echo "8. Idempotency Documentation"
echo "======================================"

if grep -qi \
  "client is responsible for generating the idempotency key" \
  README.md; then

  pass "Idempotency key ownership documented"

else

  fail "Idempotency key source not documented"
fi

echo
echo "======================================"
echo "9. Flyway Migration Naming"
echo "======================================"

BAD_MIGRATIONS="$(
  find . \
    -path "*/src/main/resources/db/migration/*" \
    -type f \
    | grep -E '\.sql\.sql$' \
    || true
)"

if [ -z "${BAD_MIGRATIONS}" ]; then
  pass "No malformed Flyway filenames"
else
  echo "${BAD_MIGRATIONS}"
  fail "Malformed Flyway migration filename remains"
fi

echo
echo "======================================"
echo "10. Service Build/Test Suites"
echo "======================================"

run_step \
  "API Gateway tests" \
  bash -c "
    cd '${ROOT_DIR}/api-gateway'
    ./gradlew clean test --no-parallel
  "

run_step \
  "Identity Service tests" \
  bash -c "
    cd '${ROOT_DIR}/identity-service'
    ./gradlew clean test --no-parallel
  "

run_step \
  "Payment Service tests" \
  bash -c "
    cd '${ROOT_DIR}/payment-service'
    ./gradlew clean test --no-parallel
  "

run_step \
  "Ledger Service tests" \
  bash -c "
    cd '${ROOT_DIR}/ledger-service'
    ./gradlew clean test --no-parallel
  "

run_step \
  "Notification Service tests" \
  bash -c "
    cd '${ROOT_DIR}/notification-service'
    ./gradlew clean test --no-parallel
  "

echo
echo "======================================"
echo "11. Story 14 Regression"
echo "======================================"

if [ -x "scripts/e2e/run-story14-all.sh" ]; then

  run_step \
    "Story 14 full regression" \
    "${ROOT_DIR}/scripts/e2e/run-story14-all.sh"

else

  fail "Story 14 master regression script missing/not executable"
fi

echo
echo "======================================"
echo "STORY 15 FINAL SUMMARY"
echo "======================================"

echo "Passed:   ${PASS_COUNT}"
echo "Warnings: ${WARN_COUNT}"
echo "Failed:   ${FAIL_COUNT}"

if [ "${FAIL_COUNT}" -ne 0 ]; then

  echo
  echo "Failed checks:"

  for step in "${FAILED_STEPS[@]}"; do
    echo "  ❌ ${step}"
  done

  echo
  echo "STORY 15 NOT YET COMPLETE"
  exit 1
fi

echo
echo "======================================"
echo "STORY 15 COMPLETED & VERIFIED ✅"
echo "======================================"
echo
echo "Spring Boot / Backend Phase COMPLETE ✅"