#!/usr/bin/env bash

set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8081}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-fintech-postgres}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-fintech-kafka}"
DB_USERNAME="${DB_USERNAME:-fintech_user}"

PASSWORD="Password123!"

echo "======================================"
echo "Story 14 — Not Processed Recovery E2E"
echo "======================================"

restore_kafka() {

  if ! docker ps --format '{{.Names}}' |
       grep -q "^${KAFKA_CONTAINER}$"; then

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
  fi
}

trap restore_kafka EXIT

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

get_processing_result_count() {

  local payment_id="$1"

  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d ledger_db \
    -tA \
    -c "
        SELECT COUNT(*)
        FROM ledger_processing_results
        WHERE payment_id = '${payment_id}';
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

echo "Preparing sender and receiver..."

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

echo "Stopping Kafka before payment creation..."

docker stop \
  "${KAFKA_CONTAINER}" \
  >/dev/null

sleep 2

IDEMPOTENCY_KEY="recovery-not-processed-$(date +%s)-$RANDOM"

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

sleep 3

echo "Verifying Ledger has NOT processed payment..."

PROCESSING_RESULT_COUNT="$(
  get_processing_result_count \
    "${PAYMENT_ID}"
)"

LEDGER_COUNT="$(
  get_ledger_count \
    "${PAYMENT_ID}"
)"

if [ "${PROCESSING_RESULT_COUNT}" != "0" ]; then

  echo "Expected no Ledger processing result"
  exit 1
fi

if [ "${LEDGER_COUNT}" != "0" ]; then

  echo "Expected no Ledger entries"
  exit 1
fi

echo "✅ Ledger has not processed payment"

echo "Preventing normal outbox retry..."

docker exec \
  "${POSTGRES_CONTAINER}" \
  psql \
  -U "${DB_USERNAME}" \
  -d payment_db \
  -v ON_ERROR_STOP=1 \
  -c "
      UPDATE outbox_events
      SET published = TRUE,
          published_at = NOW(),
          last_error = NULL
      WHERE aggregate_id = '${PAYMENT_ID}';
  " \
  >/dev/null

echo "Forcing payment into stale PROCESSING state..."

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

  echo "Could not create stale PROCESSING state"
  exit 1
fi

echo "✅ Stale PROCESSING payment prepared"

echo
echo "Restoring Kafka..."

restore_kafka
trap - EXIT

echo
echo "Waiting for stuck-payment recovery..."

FINAL_STATUS="PROCESSING"

for attempt in $(seq 1 45); do

  FINAL_STATUS="$(
    get_payment_status \
      "${PAYMENT_ID}"
  )"

  PROCESSING_RESULT_COUNT="$(
    get_processing_result_count \
      "${PAYMENT_ID}"
  )"

  echo "Attempt ${attempt}: status=${FINAL_STATUS} ledgerResultCount=${PROCESSING_RESULT_COUNT}"

  if [ "${FINAL_STATUS}" = "COMPLETED" ]; then
    break
  fi

  if [ "${FINAL_STATUS}" = "FAILED" ]; then

    FAILURE_REASON="$(
      docker exec \
        "${POSTGRES_CONTAINER}" \
        psql \
        -U "${DB_USERNAME}" \
        -d payment_db \
        -tA \
        -c "
            SELECT COALESCE(failure_reason, '')
            FROM payments
            WHERE id = '${PAYMENT_ID}';
        "
    )"

    echo "Payment unexpectedly FAILED reason=${FAILURE_REASON}"
    exit 1
  fi

  sleep 2
done

if [ "${FINAL_STATUS}" != "COMPLETED" ]; then

  echo "Recovery did not complete payment"
  exit 1
fi

echo "✅ Recovery completed previously unprocessed payment"

echo "Verifying exactly-once financial result..."

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

PROCESSING_RESULT_COUNT="$(
  get_processing_result_count \
    "${PAYMENT_ID}"
)"

if [ "${SENDER_BALANCE}" != "8000.00" ]; then

  echo "Unexpected sender balance: ${SENDER_BALANCE}"
  exit 1
fi

if [ "${RECEIVER_BALANCE}" != "7000.00" ]; then

  echo "Unexpected receiver balance: ${RECEIVER_BALANCE}"
  exit 1
fi

if [ "${LEDGER_COUNT}" != "2" ]; then

  echo "Expected exactly 2 ledger entries, found ${LEDGER_COUNT}"
  exit 1
fi

if [ "${PROCESSING_RESULT_COUNT}" != "1" ]; then

  echo "Expected exactly 1 Ledger processing result, found ${PROCESSING_RESULT_COUNT}"
  exit 1
fi

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
          AND entry_type = 'DEBIT';
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
          AND entry_type = 'CREDIT';
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

echo "✅ Exactly one DEBIT + one CREDIT"

echo
echo "======================================"
echo "NOT-PROCESSED RECOVERY TEST PASSED"
echo "======================================"
echo "Payment ID:              ${PAYMENT_ID}"
echo "Final status:            ${FINAL_STATUS}"
echo "Sender balance:          ${SENDER_BALANCE}"
echo "Receiver balance:        ${RECEIVER_BALANCE}"
echo "Ledger entries:          ${LEDGER_COUNT}"
echo "Ledger processing result:${PROCESSING_RESULT_COUNT}"
echo "DEBIT count:             ${DEBIT_COUNT}"
echo "CREDIT count:            ${CREDIT_COUNT}"