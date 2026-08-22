#!/usr/bin/env bash

set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8081}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-fintech-postgres}"
DB_USERNAME="${DB_USERNAME:-fintech_user}"

SENDER_EMAIL="sender-$(date +%s)-$RANDOM@example.com"
RECEIVER_EMAIL="receiver-$(date +%s)-$RANDOM@example.com"

PASSWORD="Password123!"
IDEMPOTENCY_KEY="e2e-$(date +%s)-$RANDOM"
AMOUNT="2000.00"

echo "======================================"
echo "Story 14 Happy Path E2E"
echo "======================================"
echo "Gateway URL: ${GATEWAY_URL}"

command -v curl >/dev/null || {
  echo "curl is required"
  exit 1
}

command -v jq >/dev/null || {
  echo "jq is required"
  exit 1
}

command -v docker >/dev/null || {
  echo "docker is required"
  exit 1
}

echo "Checking required infrastructure..."

if ! docker ps --format '{{.Names}}' | grep -q "^${POSTGRES_CONTAINER}$"; then
  echo "PostgreSQL container ${POSTGRES_CONTAINER} is not running"
  exit 1
fi

echo "Registering sender..."

curl --fail --silent \
  -X POST \
  "${GATEWAY_URL}/api/auth/register" \
  -H "Content-Type: application/json" \
  -d "{
        \"email\":\"${SENDER_EMAIL}\",
        \"password\":\"${PASSWORD}\"
      }" \
  >/dev/null

echo "Registering receiver..."

curl --fail --silent \
  -X POST \
  "${GATEWAY_URL}/api/auth/register" \
  -H "Content-Type: application/json" \
  -d "{
        \"email\":\"${RECEIVER_EMAIL}\",
        \"password\":\"${PASSWORD}\"
      }" \
  >/dev/null

echo "Logging in sender..."

SENDER_LOGIN="$(
  curl --fail --silent \
    -X POST \
    "${GATEWAY_URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{
          \"email\":\"${SENDER_EMAIL}\",
          \"password\":\"${PASSWORD}\"
        }"
)"

SENDER_TOKEN="$(
  echo "${SENDER_LOGIN}" |
    jq -r '.message'
)"

if [ -z "${SENDER_TOKEN}" ] || [ "${SENDER_TOKEN}" = "null" ]; then
  echo "Sender login did not return a token"
  echo "${SENDER_LOGIN}"
  exit 1
fi

echo "Logging in receiver..."

RECEIVER_LOGIN="$(
  curl --fail --silent \
    -X POST \
    "${GATEWAY_URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{
          \"email\":\"${RECEIVER_EMAIL}\",
          \"password\":\"${PASSWORD}\"
        }"
)"

RECEIVER_TOKEN="$(
  echo "${RECEIVER_LOGIN}" |
    jq -r '.message'
)"

if [ -z "${RECEIVER_TOKEN}" ] || [ "${RECEIVER_TOKEN}" = "null" ]; then
  echo "Receiver login did not return a token"
  echo "${RECEIVER_LOGIN}"
  exit 1
fi

echo "Creating sender account..."

SENDER_ACCOUNT="$(
  curl --fail --silent \
    -X POST \
    "${GATEWAY_URL}/api/accounts" \
    -H "Authorization: Bearer ${SENDER_TOKEN}" \
    -H "Content-Type: application/json" \
    -d '{
          "currency":"INR"
        }'
)"

SENDER_ACCOUNT_ID="$(
  echo "${SENDER_ACCOUNT}" |
    jq -r '.accountId'
)"

SENDER_USER_ID="$(
  echo "${SENDER_ACCOUNT}" |
    jq -r '.ownerId'
)"

if [ -z "${SENDER_ACCOUNT_ID}" ] || [ "${SENDER_ACCOUNT_ID}" = "null" ]; then
  echo "Sender account creation failed"
  echo "${SENDER_ACCOUNT}"
  exit 1
fi

echo "Creating receiver account..."

RECEIVER_ACCOUNT="$(
  curl --fail --silent \
    -X POST \
    "${GATEWAY_URL}/api/accounts" \
    -H "Authorization: Bearer ${RECEIVER_TOKEN}" \
    -H "Content-Type: application/json" \
    -d '{
          "currency":"INR"
        }'
)"

RECEIVER_ACCOUNT_ID="$(
  echo "${RECEIVER_ACCOUNT}" |
    jq -r '.accountId'
)"

RECEIVER_USER_ID="$(
  echo "${RECEIVER_ACCOUNT}" |
    jq -r '.ownerId'
)"

if [ -z "${RECEIVER_ACCOUNT_ID}" ] || [ "${RECEIVER_ACCOUNT_ID}" = "null" ]; then
  echo "Receiver account creation failed"
  echo "${RECEIVER_ACCOUNT}"
  exit 1
fi

echo "Controlled E2E funding..."

docker exec \
  "${POSTGRES_CONTAINER}" \
  psql \
  -U "${DB_USERNAME}" \
  -d ledger_db \
  -v ON_ERROR_STOP=1 \
  -c "
      UPDATE accounts
      SET balance = 10000.00,
          opening_balance = 10000.00,
          updated_at = NOW()
      WHERE id = '${SENDER_ACCOUNT_ID}';

      UPDATE accounts
      SET balance = 5000.00,
          opening_balance = 5000.00,
          updated_at = NOW()
      WHERE id = '${RECEIVER_ACCOUNT_ID}';
  " \
  >/dev/null

echo "Creating payment..."

PAYMENT_RESPONSE="$(
  curl --fail --silent \
    -X POST \
    "${GATEWAY_URL}/api/payments/pay" \
    -H "Authorization: Bearer ${SENDER_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{
          \"senderId\":\"${SENDER_USER_ID}\",
          \"receiverId\":\"${RECEIVER_USER_ID}\",
          \"amount\":${AMOUNT},
          \"currency\":\"INR\",
          \"idempotencyKey\":\"${IDEMPOTENCY_KEY}\"
        }"
)"

PAYMENT_ID="$(
  echo "${PAYMENT_RESPONSE}" |
    jq -r '.paymentId'
)"

if [ -z "${PAYMENT_ID}" ] || [ "${PAYMENT_ID}" = "null" ]; then
  echo "Payment creation failed"
  echo "${PAYMENT_RESPONSE}"
  exit 1
fi

echo "Payment ID: ${PAYMENT_ID}"
echo "Waiting for Saga completion..."

PAYMENT_STATUS="PROCESSING"
PAYMENT_READ=""

for attempt in $(seq 1 30); do

  PAYMENT_READ="$(
    curl --fail --silent \
      "${GATEWAY_URL}/api/payments/${PAYMENT_ID}" \
      -H "Authorization: Bearer ${SENDER_TOKEN}"
  )"

  PAYMENT_STATUS="$(
    echo "${PAYMENT_READ}" |
      jq -r '.status'
  )"

  echo "Attempt ${attempt}: status=${PAYMENT_STATUS}"

  if [ "${PAYMENT_STATUS}" = "COMPLETED" ]; then
    break
  fi

  if [ "${PAYMENT_STATUS}" = "FAILED" ]; then

    REASON="$(
      echo "${PAYMENT_READ}" |
        jq -r '.failureReason'
    )"

    echo "Payment failed unexpectedly: ${REASON}"
    exit 1
  fi

  sleep 1
done

if [ "${PAYMENT_STATUS}" != "COMPLETED" ]; then
  echo "Payment did not complete within timeout"
  exit 1
fi

FAILURE_REASON="$(
  echo "${PAYMENT_READ}" |
    jq -r '.failureReason'
)"

if [ "${FAILURE_REASON}" != "null" ]; then
  echo "Expected failureReason=null, got ${FAILURE_REASON}"
  exit 1
fi

echo "Payment COMPLETED"

echo "Verifying authoritative balances..."

SENDER_BALANCE="$(
  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d ledger_db \
    -tA \
    -c "
        SELECT balance
        FROM accounts
        WHERE id = '${SENDER_ACCOUNT_ID}';
    "
)"

RECEIVER_BALANCE="$(
  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d ledger_db \
    -tA \
    -c "
        SELECT balance
        FROM accounts
        WHERE id = '${RECEIVER_ACCOUNT_ID}';
    "
)"

if [ "${SENDER_BALANCE}" != "8000.00" ]; then
  echo "Sender balance mismatch: ${SENDER_BALANCE}"
  exit 1
fi

if [ "${RECEIVER_BALANCE}" != "7000.00" ]; then
  echo "Receiver balance mismatch: ${RECEIVER_BALANCE}"
  exit 1
fi

echo "Balances verified"

echo "Verifying double-entry ledger..."

DEBIT_COUNT="$(
  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d ledger_db \
    -tA \
    -c "
        SELECT COUNT(*)
        FROM ledger_entries
        WHERE payment_id = '${PAYMENT_ID}'
          AND entry_type = 'DEBIT'
          AND amount = ${AMOUNT};
    "
)"

CREDIT_COUNT="$(
  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d ledger_db \
    -tA \
    -c "
        SELECT COUNT(*)
        FROM ledger_entries
        WHERE payment_id = '${PAYMENT_ID}'
          AND entry_type = 'CREDIT'
          AND amount = ${AMOUNT};
    "
)"

if [ "${DEBIT_COUNT}" != "1" ]; then
  echo "Expected exactly 1 DEBIT, found ${DEBIT_COUNT}"
  exit 1
fi

if [ "${CREDIT_COUNT}" != "1" ]; then
  echo "Expected exactly 1 CREDIT, found ${CREDIT_COUNT}"
  exit 1
fi

DEBIT_TOTAL="$(
  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d ledger_db \
    -tA \
    -c "
        SELECT COALESCE(SUM(amount),0)
        FROM ledger_entries
        WHERE payment_id = '${PAYMENT_ID}'
          AND entry_type = 'DEBIT';
    "
)"

CREDIT_TOTAL="$(
  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d ledger_db \
    -tA \
    -c "
        SELECT COALESCE(SUM(amount),0)
        FROM ledger_entries
        WHERE payment_id = '${PAYMENT_ID}'
          AND entry_type = 'CREDIT';
    "
)"

if [ "${DEBIT_TOTAL}" != "${CREDIT_TOTAL}" ]; then
  echo "Double-entry mismatch DEBIT=${DEBIT_TOTAL} CREDIT=${CREDIT_TOTAL}"
  exit 1
fi

echo "Ledger verified"

echo "Waiting for completion notification..."

NOTIFICATION_COUNT="0"

for attempt in $(seq 1 15); do

  NOTIFICATION_COUNT="$(
    docker exec \
      "${POSTGRES_CONTAINER}" \
      psql \
      -U "${DB_USERNAME}" \
      -d notification_db \
      -tA \
      -c "
          SELECT COUNT(*)
          FROM notifications
          WHERE payment_id = '${PAYMENT_ID}'
            AND type = 'PAYMENT_COMPLETED';
      "
  )"

  if [ "${NOTIFICATION_COUNT}" = "1" ]; then
    break
  fi

  sleep 1
done

if [ "${NOTIFICATION_COUNT}" != "1" ]; then
  echo "Expected exactly one completion notification, found ${NOTIFICATION_COUNT}"
  exit 1
fi

echo "Notification verified"

echo
echo "======================================"
echo "STORY 14 HAPPY PATH PASSED"
echo "======================================"
echo "Payment:  ${PAYMENT_ID}"
echo "Sender:   10000.00 -> ${SENDER_BALANCE}"
echo "Receiver: 5000.00 -> ${RECEIVER_BALANCE}"
echo "DEBIT:    ${DEBIT_TOTAL}"
echo "CREDIT:   ${CREDIT_TOTAL}"
echo "Notification count: ${NOTIFICATION_COUNT}"