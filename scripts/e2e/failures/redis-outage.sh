#!/usr/bin/env bash

set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8081}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-fintech-postgres}"
REDIS_CONTAINER="${REDIS_CONTAINER:-fintech-redis}"
DB_USERNAME="${DB_USERNAME:-fintech_user}"

PASSWORD="Password123!"

echo "======================================"
echo "Story 14 — Redis Outage E2E"
echo "======================================"

restore_redis() {

  if ! docker ps --format '{{.Names}}' |
       grep -q "^${REDIS_CONTAINER}$"; then

    echo
    echo "Restoring Redis..."

    docker start "${REDIS_CONTAINER}" >/dev/null

    echo "Waiting for Redis..."

    for attempt in $(seq 1 20); do

      if docker exec \
          "${REDIS_CONTAINER}" \
          redis-cli ping 2>/dev/null |
          grep -q "PONG"; then

        echo "Redis is available"
        return
      fi

      sleep 1
    done

    echo "Redis did not recover"
    exit 1
  fi
}

trap restore_redis EXIT

register_and_login() {

  local prefix="$1"
  local email="${prefix}-$(date +%s)-$RANDOM@example.com"

  curl --fail --silent \
    -X POST \
    "${GATEWAY_URL}/api/auth/register" \
    -H "Content-Type: application/json" \
    -d "{
          \"email\":\"${email}\",
          \"password\":\"${PASSWORD}\"
        }" \
    >/dev/null

  curl --fail --silent \
    -X POST \
    "${GATEWAY_URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{
          \"email\":\"${email}\",
          \"password\":\"${PASSWORD}\"
        }" |
    jq -r '.message'
}

create_account() {

  local token="$1"

  curl --fail --silent \
    -X POST \
    "${GATEWAY_URL}/api/accounts" \
    -H "Authorization: Bearer ${token}" \
    -H "Content-Type: application/json" \
    -d '{
          "currency":"INR"
        }'
}

fund_account() {

  local account_id="$1"
  local amount="$2"

  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d ledger_db \
    -v ON_ERROR_STOP=1 \
    -c "
        UPDATE accounts
        SET balance = ${amount},
            opening_balance = ${amount},
            updated_at = NOW()
        WHERE id = '${account_id}';
    " \
    >/dev/null
}

get_balance() {

  local account_id="$1"

  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d ledger_db \
    -tA \
    -c "
        SELECT balance
        FROM accounts
        WHERE id = '${account_id}';
    "
}

echo "Preparing users while Redis is available..."

SENDER_TOKEN="$(
  register_and_login sender
)"

RECEIVER_TOKEN="$(
  register_and_login receiver
)"

SENDER_ACCOUNT="$(
  create_account "${SENDER_TOKEN}"
)"

RECEIVER_ACCOUNT="$(
  create_account "${RECEIVER_TOKEN}"
)"

SENDER_ACCOUNT_ID="$(
  echo "${SENDER_ACCOUNT}" |
    jq -r '.accountId'
)"

RECEIVER_ACCOUNT_ID="$(
  echo "${RECEIVER_ACCOUNT}" |
    jq -r '.accountId'
)"

SENDER_USER_ID="$(
  echo "${SENDER_ACCOUNT}" |
    jq -r '.ownerId'
)"

RECEIVER_USER_ID="$(
  echo "${RECEIVER_ACCOUNT}" |
    jq -r '.ownerId'
)"

fund_account \
  "${SENDER_ACCOUNT_ID}" \
  "10000.00"

fund_account \
  "${RECEIVER_ACCOUNT_ID}" \
  "5000.00"

SENDER_BEFORE="$(
  get_balance "${SENDER_ACCOUNT_ID}"
)"

RECEIVER_BEFORE="$(
  get_balance "${RECEIVER_ACCOUNT_ID}"
)"

NON_EXISTENT_PAYMENT_ID="$(
  uuidgen |
    tr '[:upper:]' '[:lower:]'
)"

echo "Stopping Redis..."

docker stop \
  "${REDIS_CONTAINER}" \
  >/dev/null

sleep 2

echo "Redis stopped"

echo
echo "Testing Gateway behavior during Redis outage..."

HTTP_CODE="$(
  curl --silent \
    --show-error \
    --connect-timeout 5 \
    --max-time 10 \
    --output /tmp/story14-redis-response.json \
    --write-out "%{http_code}" \
    "${GATEWAY_URL}/api/payments/${NON_EXISTENT_PAYMENT_ID}" \
    -H "Authorization: Bearer ${SENDER_TOKEN}" ||
    true
)"

echo "Gateway HTTP status: ${HTTP_CODE}"

if [ "${HTTP_CODE}" != "503" ]; then

  echo "Expected Gateway to return 503 while rate-limit Redis is unavailable"

  echo "Response:"
  cat /tmp/story14-redis-response.json 2>/dev/null || true
  echo

  exit 1
fi

echo "✅ Gateway fails closed with 503"

echo
echo "Verifying PostgreSQL financial state..."

SENDER_DURING_OUTAGE="$(
  get_balance "${SENDER_ACCOUNT_ID}"
)"

RECEIVER_DURING_OUTAGE="$(
  get_balance "${RECEIVER_ACCOUNT_ID}"
)"

if [ "${SENDER_DURING_OUTAGE}" != "${SENDER_BEFORE}" ]; then

  echo "Sender balance changed unexpectedly"
  echo "Before: ${SENDER_BEFORE}"
  echo "After:  ${SENDER_DURING_OUTAGE}"

  exit 1
fi

if [ "${RECEIVER_DURING_OUTAGE}" != "${RECEIVER_BEFORE}" ]; then

  echo "Receiver balance changed unexpectedly"
  echo "Before: ${RECEIVER_BEFORE}"
  echo "After:  ${RECEIVER_DURING_OUTAGE}"

  exit 1
fi

echo "✅ Financial state unchanged during Redis outage"

restore_redis
trap - EXIT

echo
echo "Waiting for Gateway Redis reconnection..."

sleep 3

echo "Testing Gateway after Redis recovery..."

RECOVERY_CODE="$(
  curl --silent \
    --show-error \
    --connect-timeout 5 \
    --max-time 10 \
    --output /tmp/story14-redis-recovery.json \
    --write-out "%{http_code}" \
    "${GATEWAY_URL}/api/payments/${NON_EXISTENT_PAYMENT_ID}" \
    -H "Authorization: Bearer ${SENDER_TOKEN}" ||
    true
)"

echo "Gateway HTTP status after recovery: ${RECOVERY_CODE}"

if [ "${RECOVERY_CODE}" != "404" ]; then

  echo "Expected 404 for valid but nonexistent payment after Redis recovery"

  echo "Response:"
  cat /tmp/story14-redis-recovery.json 2>/dev/null || true
  echo

  exit 1
fi

echo "✅ Gateway recovered after Redis restart"

SENDER_AFTER="$(
  get_balance "${SENDER_ACCOUNT_ID}"
)"

RECEIVER_AFTER="$(
  get_balance "${RECEIVER_ACCOUNT_ID}"
)"

if [ "${SENDER_AFTER}" != "${SENDER_BEFORE}" ]; then

  echo "Sender balance changed after Redis recovery"
  exit 1
fi

if [ "${RECEIVER_AFTER}" != "${RECEIVER_BEFORE}" ]; then

  echo "Receiver balance changed after Redis recovery"
  exit 1
fi

echo "✅ PostgreSQL financial state still correct"

rm -f \
  /tmp/story14-redis-response.json \
  /tmp/story14-redis-recovery.json

echo
echo "======================================"
echo "REDIS OUTAGE TEST PASSED"
echo "======================================"
echo "Redis unavailable:"
echo "  Gateway protected request -> 503"
echo "  Sender balance            -> ${SENDER_DURING_OUTAGE}"
echo "  Receiver balance          -> ${RECEIVER_DURING_OUTAGE}"
echo
echo "Redis restored:"
echo "  Valid unknown payment     -> 404"
echo "  Sender balance            -> ${SENDER_AFTER}"
echo "  Receiver balance          -> ${RECEIVER_AFTER}"
echo
echo "Financial correctness unaffected: YES"