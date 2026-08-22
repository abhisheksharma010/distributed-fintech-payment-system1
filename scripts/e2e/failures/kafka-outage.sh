#!/usr/bin/env bash

set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8081}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-fintech-postgres}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-fintech-kafka}"
DB_USERNAME="${DB_USERNAME:-fintech_user}"

PASSWORD="Password123!"

echo "======================================"
echo "Story 14 — Kafka Outage E2E"
echo "======================================"

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

echo "Creating sender + receiver..."

SENDER_TOKEN="$(register_and_login sender)"
RECEIVER_TOKEN="$(register_and_login receiver)"

SENDER_ACCOUNT="$(create_account "${SENDER_TOKEN}")"
RECEIVER_ACCOUNT="$(create_account "${RECEIVER_TOKEN}")"

SENDER_ACCOUNT_ID="$(echo "${SENDER_ACCOUNT}" | jq -r '.accountId')"
RECEIVER_ACCOUNT_ID="$(echo "${RECEIVER_ACCOUNT}" | jq -r '.accountId')"

SENDER_USER_ID="$(echo "${SENDER_ACCOUNT}" | jq -r '.ownerId')"
RECEIVER_USER_ID="$(echo "${RECEIVER_ACCOUNT}" | jq -r '.ownerId')"

fund_account "${SENDER_ACCOUNT_ID}" "10000.00"
fund_account "${RECEIVER_ACCOUNT_ID}" "5000.00"

IDEMPOTENCY_KEY="kafka-outage-$(date +%s)-$RANDOM"

echo "Stopping Kafka..."

docker stop "${KAFKA_CONTAINER}" >/dev/null

restore_kafka() {

  echo
  echo "Restoring Kafka..."

  docker start "${KAFKA_CONTAINER}" >/dev/null

  echo "Waiting for Kafka..."

  for attempt in $(seq 1 30); do

    if docker exec \
      "${KAFKA_CONTAINER}" \
      kafka-topics \
      --bootstrap-server localhost:9092 \
      --list \
      >/dev/null 2>&1; then

      echo "Kafka is available"
      return
    fi

    sleep 1
  done

  echo "Kafka did not recover"
  exit 1
}

trap restore_kafka EXIT

echo "Creating payment while Kafka is unavailable..."

PAYMENT_RESPONSE="$(
  curl --fail --silent \
    -X POST \
    "${GATEWAY_URL}/api/payments/pay" \
    -H "Authorization: Bearer ${SENDER_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{
          \"senderId\":\"${SENDER_USER_ID}\",
          \"receiverId\":\"${RECEIVER_USER_ID}\",
          \"amount\":2000.00,
          \"currency\":\"INR\",
          \"idempotencyKey\":\"${IDEMPOTENCY_KEY}\"
        }"
)"

PAYMENT_ID="$(
  echo "${PAYMENT_RESPONSE}" |
    jq -r '.paymentId'
)"

echo "Payment ID: ${PAYMENT_ID}"

echo "Waiting briefly for outbox publisher to fail..."

sleep 5

echo "Verifying payment + outbox committed..."

PAYMENT_STATUS="$(
  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d payment_db \
    -tA \
    -c "
        SELECT status
        FROM payments
        WHERE id = '${PAYMENT_ID}';
    "
)"

OUTBOX_PUBLISHED="$(
  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d payment_db \
    -tA \
    -c "
        SELECT published
        FROM outbox_events
        WHERE aggregate_id = '${PAYMENT_ID}';
    "
)"

if [ "${PAYMENT_STATUS}" != "PROCESSING" ]; then
  echo "Expected PROCESSING while Kafka is unavailable, got ${PAYMENT_STATUS}"
  exit 1
fi

if [ "${OUTBOX_PUBLISHED}" != "f" ]; then
  echo "Expected unpublished outbox row, got published=${OUTBOX_PUBLISHED}"
  exit 1
fi

echo "✅ Payment persisted"
echo "✅ Outbox persisted"
echo "✅ Outbox remains unpublished during Kafka outage"

restore_kafka
trap - EXIT

echo "Waiting for outbox retry and Saga completion..."

FINAL_STATUS="PROCESSING"

for attempt in $(seq 1 40); do

  PAYMENT_READ="$(
    curl --fail --silent \
      "${GATEWAY_URL}/api/payments/${PAYMENT_ID}" \
      -H "Authorization: Bearer ${SENDER_TOKEN}"
  )"

  FINAL_STATUS="$(
    echo "${PAYMENT_READ}" |
      jq -r '.status'
  )"

  echo "Attempt ${attempt}: status=${FINAL_STATUS}"

  if [ "${FINAL_STATUS}" = "COMPLETED" ]; then
    break
  fi

  if [ "${FINAL_STATUS}" = "FAILED" ]; then

    REASON="$(
      echo "${PAYMENT_READ}" |
        jq -r '.failureReason'
    )"

    echo "Payment unexpectedly failed: ${REASON}"
    exit 1
  fi

  sleep 1
done

if [ "${FINAL_STATUS}" != "COMPLETED" ]; then
  echo "Payment did not recover after Kafka restart"
  exit 1
fi

OUTBOX_PUBLISHED="$(
  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d payment_db \
    -tA \
    -c "
        SELECT published
        FROM outbox_events
        WHERE aggregate_id = '${PAYMENT_ID}';
    "
)"

if [ "${OUTBOX_PUBLISHED}" != "t" ]; then
  echo "Outbox was not marked published after Kafka recovery"
  exit 1
fi

LEDGER_COUNT="$(
  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d ledger_db \
    -tA \
    -c "
        SELECT COUNT(*)
        FROM ledger_entries
        WHERE payment_id = '${PAYMENT_ID}';
    "
)"

if [ "${LEDGER_COUNT}" != "2" ]; then
  echo "Expected exactly 2 ledger entries, got ${LEDGER_COUNT}"
  exit 1
fi

echo
echo "======================================"
echo "KAFKA OUTAGE TEST PASSED"
echo "======================================"
echo "Payment persisted during outage: YES"
echo "Outbox persisted during outage: YES"
echo "Outbox unpublished during outage: YES"
echo "Retry after Kafka recovery: YES"
echo "Final payment status: ${FINAL_STATUS}"
echo "Ledger entries: ${LEDGER_COUNT}"