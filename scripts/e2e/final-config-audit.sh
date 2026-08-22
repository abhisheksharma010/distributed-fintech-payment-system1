#!/usr/bin/env bash

set -euo pipefail

PASS_COUNT=0
FAIL_COUNT=0
WARN_COUNT=0

echo "======================================"
echo "Story 14 — Final Configuration Audit"
echo "======================================"

pass() {
  echo "✅ $1"
  PASS_COUNT=$((PASS_COUNT + 1))
}

fail() {
  echo "❌ $1"
  FAIL_COUNT=$((FAIL_COUNT + 1))
}

warn() {
  echo "⚠️  $1"
  WARN_COUNT=$((WARN_COUNT + 1))
}

require_project_root() {

  if [ ! -d "api-gateway" ] ||
     [ ! -d "identity-service" ] ||
     [ ! -d "payment-service" ] ||
     [ ! -d "ledger-service" ] ||
     [ ! -d "notification-service" ]; then

    echo "Run this script from distributed-fintech-payment-system root"
    exit 1
  fi
}

require_project_root

echo
echo "--------------------------------------"
echo "1. Environment Files"
echo "--------------------------------------"

if [ -f ".env.example" ]; then
  pass ".env.example exists"
else
  fail ".env.example is missing"
fi

if git ls-files --error-unmatch .env >/dev/null 2>&1; then
  fail ".env is committed to Git"
else
  pass ".env is not committed"
fi

if grep -Eq '^\.env$|^\.env\*$|^\.env\.local$' .gitignore 2>/dev/null; then
  pass ".env is ignored by Git"
else
  fail ".gitignore does not ignore .env"
fi

echo
echo "--------------------------------------"
echo "2. IDE / Local Machine Noise"
echo "--------------------------------------"

if git ls-files |
   grep -qE '(^|/)\.idea/workspace\.xml$'; then

  fail ".idea/workspace.xml is committed"

else

  pass ".idea/workspace.xml is not committed"
fi

if git ls-files |
   grep -qE '(^|/)\.DS_Store$'; then

  fail ".DS_Store is committed"

else

  pass ".DS_Store is not committed"
fi

echo
echo "--------------------------------------"
echo "3. Production JWT Secret"
echo "--------------------------------------"

GATEWAY_CONFIG="api-gateway/src/main/resources/application.yaml"
IDENTITY_CONFIG="identity-service/src/main/resources/application.yaml"

if grep -q 'secret: ${JWT_SECRET}' "${GATEWAY_CONFIG}"; then
  pass "Gateway JWT secret is externalized"
else
  fail "Gateway JWT secret is not externalized"
fi

if grep -q 'secret: ${JWT_SECRET}' "${IDENTITY_CONFIG}"; then
  pass "Identity JWT secret is externalized"
else
  fail "Identity JWT secret is not externalized"
fi

if grep -R \
    --include='application.yaml' \
    --include='application.yml' \
    -E 'secret:[[:space:]]+[A-Za-z0-9_-]{24,}' \
    api-gateway/src/main/resources \
    identity-service/src/main/resources \
    >/tmp/story14-hardcoded-secrets.txt 2>/dev/null; then

  echo "Potential hardcoded JWT/secret values:"
  cat /tmp/story14-hardcoded-secrets.txt

  fail "Potential hardcoded production secret detected"

else

  pass "No obvious hardcoded production JWT secret"
fi

echo
echo "--------------------------------------"
echo "4. Database Configuration"
echo "--------------------------------------"

for service in \
  identity-service \
  payment-service \
  ledger-service \
  notification-service
do

  CONFIG="${service}/src/main/resources/application.yaml"

  if grep -q '\${.*DB_URL:' "${CONFIG}"; then
    pass "${service}: database URL externalized"
  else
    fail "${service}: database URL is not externalized"
  fi

  if grep -q 'username: ${DB_USERNAME:' "${CONFIG}"; then
    pass "${service}: DB username configurable"
  else
    fail "${service}: DB username is not configurable"
  fi

  if grep -q 'password: ${DB_PASSWORD:' "${CONFIG}"; then
    pass "${service}: DB password configurable"
  else
    fail "${service}: DB password is not configurable"
  fi
done

echo
echo "--------------------------------------"
echo "5. Kafka Configuration"
echo "--------------------------------------"

for service in \
  payment-service \
  ledger-service \
  notification-service
do

  CONFIG="${service}/src/main/resources/application.yaml"

  if grep -q '\${KAFKA_BOOTSTRAP_SERVERS:' "${CONFIG}"; then
    pass "${service}: Kafka bootstrap servers configurable"
  else
    fail "${service}: Kafka bootstrap servers not configurable"
  fi
done

HARDCODED_KAFKA="$(
  grep -R \
    --include='*.java' \
    -n \
    'localhost:9092' \
    payment-service/src/main/java \
    ledger-service/src/main/java \
    notification-service/src/main/java \
    2>/dev/null ||
    true
)"

if [ -z "${HARDCODED_KAFKA}" ]; then

  pass "No hardcoded localhost:9092 in production Java"

else

  echo "${HARDCODED_KAFKA}"
  fail "Hardcoded Kafka address found in production Java"
fi

echo
echo "--------------------------------------"
echo "6. Redis Responsibilities"
echo "--------------------------------------"

if grep -q \
    'spring-boot-starter-data-redis' \
    payment-service/build.gradle; then

  fail "Payment Service still depends on Redis"

else

  pass "Payment Service has no Redis dependency"
fi

if grep -q \
    'spring-boot-starter-cache' \
    payment-service/build.gradle; then

  fail "Payment Service still has unused cache dependency"

else

  pass "Payment Service has no unused cache dependency"
fi

if grep -qE \
    'redis:' \
    payment-service/src/main/resources/application.yaml; then

  fail "Payment Service still contains Redis configuration"

else

  pass "Payment Service has no Redis configuration"
fi

if grep -qE \
    'redis:' \
    identity-service/src/main/resources/application.yaml; then

  warn "Identity Service still contains Redis configuration — verify it is actually required"

else

  pass "Identity Service has no unused Redis configuration"
fi

if grep -qE \
    'redis:' \
    api-gateway/src/main/resources/application.yaml; then

  pass "Gateway Redis configuration exists for rate limiting"

else

  fail "Gateway Redis configuration missing"
fi

if grep -qE \
    'redis:' \
    ledger-service/src/main/resources/application.yaml; then

  pass "Ledger Redis configuration exists for safe read caching"

else

  fail "Ledger Redis configuration missing"
fi

echo
echo "--------------------------------------"
echo "7. Debug Output"
echo "--------------------------------------"

DEBUG_OUTPUT="$(
  grep -R \
    --include='*.java' \
    -n \
    'System\.out\.print' \
    api-gateway/src/main/java \
    identity-service/src/main/java \
    payment-service/src/main/java \
    ledger-service/src/main/java \
    notification-service/src/main/java \
    2>/dev/null ||
    true
)"

if [ -z "${DEBUG_OUTPUT}" ]; then

  pass "No System.out debug statements in production Java"

else

  echo "${DEBUG_OUTPUT}"
  fail "System.out debug statements remain in production Java"
fi

PRINT_STACK="$(
  grep -R \
    --include='*.java' \
    -n \
    '\.printStackTrace()' \
    api-gateway/src/main/java \
    identity-service/src/main/java \
    payment-service/src/main/java \
    ledger-service/src/main/java \
    notification-service/src/main/java \
    2>/dev/null ||
    true
)"

if [ -z "${PRINT_STACK}" ]; then

  pass "No printStackTrace calls in production Java"

else

  echo "${PRINT_STACK}"
  fail "printStackTrace calls remain"
fi

echo
echo "--------------------------------------"
echo "8. Placeholder / Temporary Config"
echo "--------------------------------------"

PLACEHOLDERS="$(
  grep -R \
    --include='application.yaml' \
    --include='application.yml' \
    -nE \
    'placeholder:[[:space:]]*true|TODO|FIXME' \
    api-gateway/src/main/resources \
    identity-service/src/main/resources \
    payment-service/src/main/resources \
    ledger-service/src/main/resources \
    notification-service/src/main/resources \
    2>/dev/null ||
    true
)"

if [ -z "${PLACEHOLDERS}" ]; then

  pass "No obvious placeholder runtime configuration"
else

  echo "${PLACEHOLDERS}"
  fail "Placeholder runtime configuration remains"
fi

echo
echo "--------------------------------------"
echo "9. Actuator Exposure"
echo "--------------------------------------"

for service in \
  api-gateway \
  identity-service \
  payment-service \
  ledger-service \
  notification-service
do

  CONFIG="${service}/src/main/resources/application.yaml"

  if grep -q \
      'include: health,info,metrics,prometheus' \
      "${CONFIG}"; then

    pass "${service}: actuator exposure is restricted"
  else

    warn "${service}: verify actuator exposure manually"
  fi
done

echo
echo "--------------------------------------"
echo "10. Service URLs"
echo "--------------------------------------"

for VAR in \
  IDENTITY_SERVICE_URL \
  PAYMENT_SERVICE_URL \
  LEDGER_SERVICE_URL \
  NOTIFICATION_SERVICE_URL
do

  if grep -q \
      "\${${VAR}:" \
      api-gateway/src/main/resources/application.yaml; then

    pass "Gateway ${VAR} is configurable"
  else

    fail "Gateway ${VAR} is not configurable"
  fi
done

echo
echo "--------------------------------------"
echo "11. Kafka Topics"
echo "--------------------------------------"

TOPIC_CONFIG_COUNT="$(
  grep -R \
    --include='application.yaml' \
    -c \
    '\${.*_TOPIC:' \
    payment-service/src/main/resources \
    ledger-service/src/main/resources \
    notification-service/src/main/resources \
    2>/dev/null |
  awk -F: '{sum += $2} END {print sum+0}'
)"

if [ "${TOPIC_CONFIG_COUNT}" -gt "0" ]; then

  pass "Kafka topics are configuration-driven"

else

  fail "No configurable Kafka topics detected"
fi

echo
echo "--------------------------------------"
echo "12. Git Status"
echo "--------------------------------------"

if git status --porcelain |
   grep -qE '\.idea/workspace\.xml|\.DS_Store'; then

  warn "Local IDE/OS files are present in working tree"

else

  pass "No IDE/OS noise in working tree"
fi

rm -f \
  /tmp/story14-hardcoded-secrets.txt

echo
echo "======================================"
echo "FINAL CONFIGURATION AUDIT"
echo "======================================"
echo "Passed:   ${PASS_COUNT}"
echo "Warnings: ${WARN_COUNT}"
echo "Failed:   ${FAIL_COUNT}"

if [ "${FAIL_COUNT}" -ne 0 ]; then

  echo
  echo "STORY 14 MILESTONE 8 FAILED"
  exit 1
fi

echo
echo "STORY 14 MILESTONE 8 PASSED"