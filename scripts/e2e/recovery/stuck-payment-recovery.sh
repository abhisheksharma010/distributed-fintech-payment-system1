#!/usr/bin/env bash

set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8081}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-fintech-postgres}"
DB_USERNAME="${DB_USERNAME:-fintech_user}"

PASSWORD="Password123!"

echo "======================================"
echo "Story 14 — Stuck Payment Recovery E2E"
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

get_payment_status() {

  local payment_id="$1"

  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d payment_db \
    -tA \
    -c "
      SELECT status
      FROM payments
      WHERE id = '${payment_id}';
    "
}

get_payment_failure_reason() {

  local payment_id="$1"

  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d payment_db \
    -tA \
    -c "
      SELECT COALESCE(failure_reason, '')
      FROM payments
      WHERE id = '${payment_id}';
    "
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

get_ledger_count() {

  local payment_id="$1"

  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d ledger_db \
    -tA \
    -c "
      SELECT COUNT(*)
      FROM ledger_entries
      WHERE payment_id = '${payment_id}';
    "
}

get_processing_result() {

  local payment_id="$1"

  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d ledger_db \
    -tA \
    -c "
      SELECT result_type
      FROM ledger_processing_results
      WHERE payment_id = '${payment_id}';
    "
}

echo "Preparing sender and receiver..."

SENDER_TOKEN="$(register_and_login sender)"
RECEIVER_TOKEN="$(register_and_login receiver)"

SENDER_ACCOUNT="$(create_account "${SENDER_TOKEN}")"
RECEIVER_ACCOUNT="$(create_account "${RECEIVER_TOKEN}")"

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

IDEMPOTENCY_KEY="recovery-$(date +%s)-$RANDOM"

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

echo "Waiting for Ledger to process payment..."

PROCESSING_RESULT=""

for attempt in $(seq 1 20); do

  PROCESSING_RESULT="$(
    get_processing_result \
      "${PAYMENT_ID}"
  )"

  if [ "${PROCESSING_RESULT}" = "PROCESSED" ]; then
    break
  fi

  sleep 1
done

if [ "${PROCESSING_RESULT}" != "PROCESSED" ]; then
  echo "Ledger did not process payment"
  exit 1
fi

echo "✅ Ledger processed payment"

echo "Forcing Payment back into stale PROCESSING state..."

docker exec \
  "${POSTGRES_CONTAINER}" \
  psql \
  -U "${DB_USERNAME}" \
  -d payment_db \
  -v ON_ERROR_STOP=1 \
  -c "
    UPDATE payments
    SET status = 'PROCESSING',
        failure_reason = NULL,
        updated_at = NOW() - INTERVAL '10 minutes'
    WHERE id = '${PAYMENT_ID}';
  " \
  >/dev/null

STATUS="$(
  get_payment_status \
    "${PAYMENT_ID}"
)"

if [ "${STATUS}" != "PROCESSING" ]; then
  echo "Failed to create stale PROCESSING state"
  exit 1
fi

echo "✅ Payment artificially placed into stale PROCESSING state"

echo "Waiting for recovery job..."

FINAL_STATUS="PROCESSING"

for attempt in $(seq 1 40); do

  FINAL_STATUS="$(
    get_payment_status \
      "${PAYMENT_ID}"
  )"

  echo "Attempt ${attempt}: status=${FINAL_STATUS}"

  if [ "${FINAL_STATUS}" = "COMPLETED" ]; then
    break
  fi

  if [ "${FINAL_STATUS}" = "FAILED" ]; then

    REASON="$(
      get_payment_failure_reason \
        "${PAYMENT_ID}"
    )"

    echo "Recovery unexpectedly failed payment reason=${REASON}"
    exit 1
  fi

  sleep 2
done

if [ "${FINAL_STATUS}" != "COMPLETED" ]; then
  echo "Payment recovery did not resolve stale PROCESSING payment"
  exit 1
fi

echo "✅ Recovery resolved payment to COMPLETED"

echo "Verifying financial state was NOT duplicated..."

SENDER_BALANCE="$(
  get_balance \
    "${SENDER_ACCOUNT_ID}"
)"

RECEIVER_BALANCE="$(
  get_balance \
    "${RECEIVER_ACCOUNT_ID}"
)"

LEDGER_COUNT="$(
  get_ledger_count \
    "${PAYMENT_ID}"
)"

if [ "${SENDER_BALANCE}" != "8000.00" ]; then
  echo "Sender was charged incorrectly: ${SENDER_BALANCE}"
  exit 1
fi

if [ "${RECEIVER_BALANCE}" != "7000.00" ]; then
  echo "Receiver balance incorrect: ${RECEIVER_BALANCE}"
  exit 1
fi

if [ "${LEDGER_COUNT}" != "2" ]; then
  echo "Expected exactly 2 ledger entries, found ${LEDGER_COUNT}"
  exit 1
fi

echo "✅ No duplicate financial transfer"

echo
echo "======================================"
echo "STUCK PAYMENT RECOVERY TEST PASSED"
echo "======================================"
echo "Payment ID:        ${PAYMENT_ID}"
echo "Ledger result:     ${PROCESSING_RESULT}"
echo "Final status:      ${FINAL_STATUS}"
echo "Sender balance:    ${SENDER_BALANCE}"
echo "Receiver balance:  ${RECEIVER_BALANCE}"
echo "Ledger entries:    ${LEDGER_COUNT}"