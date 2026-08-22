#!/usr/bin/env bash

set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8081}"
IDENTITY_URL="${IDENTITY_URL:-http://localhost:8082}"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8083}"
LEDGER_URL="${LEDGER_URL:-http://localhost:8084}"
NOTIFICATION_URL="${NOTIFICATION_URL:-http://localhost:8085}"

PASSWORD="Password123!"

PASS_COUNT=0
FAIL_COUNT=0

echo "======================================"
echo "Story 14 — Observability Verification"
echo "======================================"

pass() {
  echo "✅ $1"
  PASS_COUNT=$((PASS_COUNT + 1))
}

fail() {
  echo "❌ $1"
  FAIL_COUNT=$((FAIL_COUNT + 1))
}

echo
echo "Preparing authenticated Gateway observer..."

OBSERVER_EMAIL="observer-$(date +%s)-$RANDOM@example.com"

curl --fail --silent \
  -X POST \
  "${GATEWAY_URL}/api/auth/register" \
  -H "Content-Type: application/json" \
  -d "{
        \"email\":\"${OBSERVER_EMAIL}\",
        \"password\":\"${PASSWORD}\"
      }" \
  >/dev/null

LOGIN_RESPONSE="$(
  curl --fail --silent \
    -X POST \
    "${GATEWAY_URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{
          \"email\":\"${OBSERVER_EMAIL}\",
          \"password\":\"${PASSWORD}\"
        }"
)"

GATEWAY_TOKEN="$(
  echo "${LOGIN_RESPONSE}" |
    jq -r '.message'
)"

if [ -z "${GATEWAY_TOKEN}" ] ||
   [ "${GATEWAY_TOKEN}" = "null" ]; then

  echo "Could not obtain Gateway observer JWT"
  exit 1
fi

echo "✅ Observer authenticated"

check_public_health() {

  local name="$1"
  local url="$2"

  CODE="$(
    curl --silent \
      --output /tmp/story14-health.json \
      --write-out "%{http_code}" \
      "${url}/actuator/health" ||
      true
  )"

  if [ "${CODE}" = "200" ]; then
    pass "${name} health endpoint available"
  else
    fail "${name} health endpoint expected 200, got ${CODE}"
  fi
}

check_gateway_health() {

  CODE="$(
    curl --silent \
      --output /tmp/story14-health.json \
      --write-out "%{http_code}" \
      "${GATEWAY_URL}/actuator/health" \
      -H "Authorization: Bearer ${GATEWAY_TOKEN}" ||
      true
  )"

  if [ "${CODE}" = "200" ]; then
    pass "API Gateway authenticated health endpoint available"
  else
    fail "API Gateway health expected 200 with JWT, got ${CODE}"
  fi
}

check_public_prometheus() {

  local name="$1"
  local url="$2"

  CODE="$(
    curl --silent \
      --output /tmp/story14-prometheus.txt \
      --write-out "%{http_code}" \
      "${url}/actuator/prometheus" ||
      true
  )"

  if [ "${CODE}" != "200" ]; then

    fail "${name} prometheus expected 200, got ${CODE}"
    return
  fi

  if grep -q \
      "jvm_memory_used_bytes" \
      /tmp/story14-prometheus.txt; then

    pass "${name} exposes Prometheus metrics"

  else

    fail "${name} Prometheus response missing JVM metrics"
  fi
}

check_gateway_prometheus() {

  CODE="$(
    curl --silent \
      --output /tmp/story14-gateway-metrics.txt \
      --write-out "%{http_code}" \
      "${GATEWAY_URL}/actuator/prometheus" \
      -H "Authorization: Bearer ${GATEWAY_TOKEN}" ||
      true
  )"

  if [ "${CODE}" != "200" ]; then

    fail "API Gateway prometheus expected 200 with JWT, got ${CODE}"
    return 1
  fi

  if grep -q \
      "jvm_memory_used_bytes" \
      /tmp/story14-gateway-metrics.txt; then

    pass "API Gateway exposes authenticated Prometheus metrics"

  else

    fail "API Gateway Prometheus response missing JVM metrics"
  fi
}

echo
echo "--------------------------------------"
echo "1. Health Endpoints"
echo "--------------------------------------"

check_gateway_health

check_public_health \
  "Identity Service" \
  "${IDENTITY_URL}"

check_public_health \
  "Payment Service" \
  "${PAYMENT_URL}"

check_public_health \
  "Ledger Service" \
  "${LEDGER_URL}"

check_public_health \
  "Notification Service" \
  "${NOTIFICATION_URL}"

echo
echo "--------------------------------------"
echo "2. Prometheus Metrics"
echo "--------------------------------------"

check_gateway_prometheus || true

check_public_prometheus \
  "Identity Service" \
  "${IDENTITY_URL}"

check_public_prometheus \
  "Payment Service" \
  "${PAYMENT_URL}"

check_public_prometheus \
  "Ledger Service" \
  "${LEDGER_URL}"

check_public_prometheus \
  "Notification Service" \
  "${NOTIFICATION_URL}"

echo
echo "--------------------------------------"
echo "3. Gateway Story 13 Metrics"
echo "--------------------------------------"

curl --fail --silent \
  "${GATEWAY_URL}/actuator/prometheus" \
  -H "Authorization: Bearer ${GATEWAY_TOKEN}" \
  > /tmp/story14-gateway-metrics.txt

if grep -q \
  "gateway_rate_limit_rejected" \
  /tmp/story14-gateway-metrics.txt; then

  pass "Gateway rate-limit rejection metric exists"

else

  fail "Gateway rate-limit rejection metric missing"
fi

if grep -q \
  "gateway_redis_failure" \
  /tmp/story14-gateway-metrics.txt; then

  pass "Gateway Redis failure metric exists"

else

  fail "Gateway Redis failure metric missing"
fi

if grep -q \
  "gateway_rate_limit_identity_fallback" \
  /tmp/story14-gateway-metrics.txt; then

  pass "Gateway identity fallback metric exists"

else

  fail "Gateway identity fallback metric missing"
fi

echo
echo "--------------------------------------"
echo "4. Ledger Cache Metrics"
echo "--------------------------------------"

curl --fail --silent \
  "${LEDGER_URL}/actuator/prometheus" \
  > /tmp/story14-ledger-metrics.txt

if grep -q \
  "ledger_cache_miss" \
  /tmp/story14-ledger-metrics.txt; then

  pass "Ledger cache miss metric exists"

else

  fail "Ledger cache miss metric missing"
fi

if grep -q \
  "ledger_cache_eviction" \
  /tmp/story14-ledger-metrics.txt; then

  pass "Ledger cache eviction metric exists"

else

  fail "Ledger cache eviction metric missing"
fi

if grep -q \
  "ledger_cache_redis_failure" \
  /tmp/story14-ledger-metrics.txt; then

  pass "Ledger Redis failure metric exists"

else

  fail "Ledger Redis failure metric missing"
fi

echo
echo "--------------------------------------"
echo "5. JVM + Process Metrics"
echo "--------------------------------------"

curl --fail --silent \
  "${GATEWAY_URL}/actuator/prometheus" \
  -H "Authorization: Bearer ${GATEWAY_TOKEN}" \
  > /tmp/story14-service-metrics.txt

if grep -q "jvm_memory_used_bytes" \
     /tmp/story14-service-metrics.txt &&
   grep -q "process_uptime_seconds" \
     /tmp/story14-service-metrics.txt; then

  pass "API Gateway JVM/process metrics available"
else
  fail "API Gateway JVM/process metrics missing"
fi

for service in \
  "${IDENTITY_URL}" \
  "${PAYMENT_URL}" \
  "${LEDGER_URL}" \
  "${NOTIFICATION_URL}"
do

  curl --fail --silent \
    "${service}/actuator/prometheus" \
    > /tmp/story14-service-metrics.txt

  if ! grep -q \
      "jvm_memory_used_bytes" \
      /tmp/story14-service-metrics.txt; then

    fail "${service}: JVM memory metric missing"
    continue
  fi

  if ! grep -q \
      "process_uptime_seconds" \
      /tmp/story14-service-metrics.txt; then

    fail "${service}: process uptime metric missing"
    continue
  fi

  pass "${service}: JVM/process metrics available"
done

echo
echo "--------------------------------------"
echo "6. Gateway Actuator Authentication"
echo "--------------------------------------"

UNAUTHENTICATED_CODE="$(
  curl --silent \
    --output /tmp/story14-unauthenticated.json \
    --write-out "%{http_code}" \
    "${GATEWAY_URL}/actuator/prometheus" ||
    true
)"

if [ "${UNAUTHENTICATED_CODE}" = "401" ]; then

  pass "Gateway Prometheus endpoint requires authentication"

else

  fail "Expected unauthenticated Gateway Prometheus to return 401, got ${UNAUTHENTICATED_CODE}"
fi

echo
echo "--------------------------------------"
echo "7. Actuator Exposure Safety"
echo "--------------------------------------"

ENV_CODE="$(
  curl --silent \
    --output /tmp/story14-env.json \
    --write-out "%{http_code}" \
    "${GATEWAY_URL}/actuator/env" \
    -H "Authorization: Bearer ${GATEWAY_TOKEN}" ||
    true
)"

if [ "${ENV_CODE}" = "404" ] ||
   [ "${ENV_CODE}" = "403" ]; then

  pass "Sensitive Gateway /actuator/env is not exposed"

else

  fail "Sensitive actuator /env unexpectedly returned ${ENV_CODE}"
fi

CONFIG_CODE="$(
  curl --silent \
    --output /tmp/story14-configprops.json \
    --write-out "%{http_code}" \
    "${GATEWAY_URL}/actuator/configprops" \
    -H "Authorization: Bearer ${GATEWAY_TOKEN}" ||
    true
)"

if [ "${CONFIG_CODE}" = "404" ] ||
   [ "${CONFIG_CODE}" = "403" ]; then

  pass "Sensitive Gateway /actuator/configprops is not exposed"

else

  fail "Sensitive actuator /configprops unexpectedly returned ${CONFIG_CODE}"
fi

echo
echo "--------------------------------------"
echo "8. Required Payment Actuator Endpoints"
echo "--------------------------------------"

for endpoint in \
  health \
  info \
  metrics \
  prometheus
do

  CODE="$(
    curl --silent \
      --output /dev/null \
      --write-out "%{http_code}" \
      "${PAYMENT_URL}/actuator/${endpoint}" ||
      true
  )"

  if [ "${CODE}" = "200" ]; then

    pass "Payment actuator /${endpoint} available"

  else

    fail "Payment actuator /${endpoint} expected 200, got ${CODE}"
  fi
done

rm -f \
  /tmp/story14-health.json \
  /tmp/story14-prometheus.txt \
  /tmp/story14-gateway-metrics.txt \
  /tmp/story14-ledger-metrics.txt \
  /tmp/story14-service-metrics.txt \
  /tmp/story14-unauthenticated.json \
  /tmp/story14-env.json \
  /tmp/story14-configprops.json

echo
echo "======================================"
echo "OBSERVABILITY SUMMARY"
echo "======================================"
echo "Passed: ${PASS_COUNT}"
echo "Failed: ${FAIL_COUNT}"

if [ "${FAIL_COUNT}" -ne 0 ]; then

  echo "STORY 14 MILESTONE 7 FAILED"
  exit 1
fi

echo "STORY 14 MILESTONE 7 PASSED"