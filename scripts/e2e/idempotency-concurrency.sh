#!/usr/bin/env bash

set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8081}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-fintech-postgres}"
DB_USERNAME="${DB_USERNAME:-fintech_user}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-fintech-kafka}"

PASSWORD="Password123!"

PASS_COUNT=0
FAIL_COUNT=0

echo "======================================"
echo "Story 14 Idempotency + Concurrency E2E"
echo "======================================"

pass() {
  echo "✅ $1"
  PASS_COUNT=$((PASS_COUNT + 1))
}

fail() {
  echo "❌ $1"
  FAIL_COUNT=$((FAIL_COUNT + 1))
}

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

payment_count_by_key() {

  local key="$1"

  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d payment_db \
    -tA \
    -c "
        SELECT COUNT(*)
        FROM payments
        WHERE idempotency_key = '${key}';
    "
}

ledger_count() {

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

notification_count() {

  local payment_id="$1"

  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d notification_db \
    -tA \
    -c "
        SELECT COUNT(*)
        FROM notifications
        WHERE payment_id = '${payment_id}';
    "
}

wait_for_terminal() {

  local payment_id="$1"
  local token="$2"

  for attempt in $(seq 1 20); do

    local response

    response="$(
      curl --fail --silent \
        "${GATEWAY_URL}/api/payments/${payment_id}" \
        -H "Authorization: Bearer ${token}"
    )"

    local status

    status="$(
      echo "${response}" |
        jq -r '.status'
    )"

    if [ "${status}" = "COMPLETED" ] ||
       [ "${status}" = "FAILED" ]; then

      echo "${response}"
      return
    fi

    sleep 1
  done

  echo "{}"
}

create_payment_raw() {

  local token="$1"
  local sender_id="$2"
  local receiver_id="$3"
  local amount="$4"
  local key="$5"

  curl --silent \
    --show-error \
    -w "\n%{http_code}" \
    -X POST \
    "${GATEWAY_URL}/api/payments/pay" \
    -H "Authorization: Bearer ${token}" \
    -H "Content-Type: application/json" \
    -d "{
          \"senderId\":\"${sender_id}\",
          \"receiverId\":\"${receiver_id}\",
          \"amount\":${amount},
          \"currency\":\"INR\",
          \"idempotencyKey\":\"${key}\"
        }"
}

setup_pair() {

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
}

echo
echo "--------------------------------------"
echo "1. Duplicate HTTP Request"
echo "--------------------------------------"

setup_pair

fund_account "${SENDER_ACCOUNT_ID}" "10000.00"
fund_account "${RECEIVER_ACCOUNT_ID}" "5000.00"

IDEMPOTENCY_KEY="dup-http-$(date +%s)-$RANDOM"

FIRST="$(
  create_payment_raw \
    "${SENDER_TOKEN}" \
    "${SENDER_USER_ID}" \
    "${RECEIVER_USER_ID}" \
    "2000.00" \
    "${IDEMPOTENCY_KEY}"
)"

FIRST_BODY="$(echo "${FIRST}" | sed '$d')"
FIRST_STATUS="$(echo "${FIRST}" | tail -n 1)"
FIRST_PAYMENT_ID="$(
  echo "${FIRST_BODY}" |
    jq -r '.paymentId'
)"

SECOND="$(
  create_payment_raw \
    "${SENDER_TOKEN}" \
    "${SENDER_USER_ID}" \
    "${RECEIVER_USER_ID}" \
    "2000.00" \
    "${IDEMPOTENCY_KEY}"
)"

SECOND_BODY="$(echo "${SECOND}" | sed '$d')"
SECOND_STATUS="$(echo "${SECOND}" | tail -n 1)"
SECOND_PAYMENT_ID="$(
  echo "${SECOND_BODY}" |
    jq -r '.paymentId'
)"

wait_for_terminal \
  "${FIRST_PAYMENT_ID}" \
  "${SENDER_TOKEN}" \
  >/dev/null

COUNT="$(
  payment_count_by_key \
    "${IDEMPOTENCY_KEY}"
)"

LEDGER_COUNT="$(
  ledger_count \
    "${FIRST_PAYMENT_ID}"
)"

if [ "${FIRST_PAYMENT_ID}" = "${SECOND_PAYMENT_ID}" ] &&
   [ "${COUNT}" = "1" ] &&
   [ "${LEDGER_COUNT}" = "2" ]; then

  pass "Duplicate HTTP request remains idempotent"

else

  fail "Duplicate HTTP request created duplicate state"
fi

echo
echo "--------------------------------------"
echo "2. Idempotency Key Reuse"
echo "--------------------------------------"

REUSE_KEY="reuse-$(date +%s)-$RANDOM"

FIRST="$(
  create_payment_raw \
    "${SENDER_TOKEN}" \
    "${SENDER_USER_ID}" \
    "${RECEIVER_USER_ID}" \
    "100.00" \
    "${REUSE_KEY}"
)"

SECOND="$(
  create_payment_raw \
    "${SENDER_TOKEN}" \
    "${SENDER_USER_ID}" \
    "${RECEIVER_USER_ID}" \
    "200.00" \
    "${REUSE_KEY}"
)"

SECOND_BODY="$(echo "${SECOND}" | sed '$d')"
SECOND_STATUS="$(echo "${SECOND}" | tail -n 1)"

SECOND_CODE="$(
  echo "${SECOND_BODY}" |
    jq -r '.code // empty'
)"

COUNT="$(
  payment_count_by_key \
    "${REUSE_KEY}"
)"

if [ "${SECOND_STATUS}" = "409" ] &&
   [ "${SECOND_CODE}" = "IDEMPOTENCY_KEY_REUSED" ] &&
   [ "${COUNT}" = "1" ]; then

  pass "Changed payload with reused key is rejected"

else

  fail "Idempotency-key reuse behavior incorrect status=${SECOND_STATUS} code=${SECOND_CODE} count=${COUNT}"
fi

echo
echo "--------------------------------------"
echo "3. Concurrent Spending"
echo "--------------------------------------"

setup_pair

fund_account "${SENDER_ACCOUNT_ID}" "1000.00"
fund_account "${RECEIVER_ACCOUNT_ID}" "0.00"

KEY_A="concurrent-a-$(date +%s)-$RANDOM"
KEY_B="concurrent-b-$(date +%s)-$RANDOM"

TMP_A="$(mktemp)"
TMP_B="$(mktemp)"

create_payment_raw \
  "${SENDER_TOKEN}" \
  "${SENDER_USER_ID}" \
  "${RECEIVER_USER_ID}" \
  "800.00" \
  "${KEY_A}" \
  >"${TMP_A}" &

PID_A=$!

create_payment_raw \
  "${SENDER_TOKEN}" \
  "${SENDER_USER_ID}" \
  "${RECEIVER_USER_ID}" \
  "800.00" \
  "${KEY_B}" \
  >"${TMP_B}" &

PID_B=$!

wait "${PID_A}"
wait "${PID_B}"

BODY_A="$(sed '$d' "${TMP_A}")"
BODY_B="$(sed '$d' "${TMP_B}")"

PAYMENT_A="$(
  echo "${BODY_A}" |
    jq -r '.paymentId'
)"

PAYMENT_B="$(
  echo "${BODY_B}" |
    jq -r '.paymentId'
)"

rm -f "${TMP_A}" "${TMP_B}"

RESULT_A="$(
  wait_for_terminal \
    "${PAYMENT_A}" \
    "${SENDER_TOKEN}"
)"

RESULT_B="$(
  wait_for_terminal \
    "${PAYMENT_B}" \
    "${SENDER_TOKEN}"
)"

STATUS_A="$(
  echo "${RESULT_A}" |
    jq -r '.status'
)"

STATUS_B="$(
  echo "${RESULT_B}" |
    jq -r '.status'
)"

FINAL_BALANCE="$(
  get_balance \
    "${SENDER_ACCOUNT_ID}"
)"

SUCCESS_COUNT=0

[ "${STATUS_A}" = "COMPLETED" ] &&
  SUCCESS_COUNT=$((SUCCESS_COUNT + 1))

[ "${STATUS_B}" = "COMPLETED" ] &&
  SUCCESS_COUNT=$((SUCCESS_COUNT + 1))

if [ "${SUCCESS_COUNT}" = "1" ] &&
   [ "${FINAL_BALANCE}" = "200.00" ]; then

  pass "Concurrent spending cannot double-spend sender"

else

  fail "Concurrent spending unsafe A=${STATUS_A} B=${STATUS_B} balance=${FINAL_BALANCE}"
fi

echo
echo "--------------------------------------"
echo "4. Duplicate payments.created Kafka Delivery"
echo "--------------------------------------"

setup_pair

fund_account "${SENDER_ACCOUNT_ID}" "10000.00"
fund_account "${RECEIVER_ACCOUNT_ID}" "5000.00"

KAFKA_KEY="dup-kafka-$(date +%s)-$RANDOM"

PAYMENT_RESPONSE="$(
  create_payment_raw \
    "${SENDER_TOKEN}" \
    "${SENDER_USER_ID}" \
    "${RECEIVER_USER_ID}" \
    "2000.00" \
    "${KAFKA_KEY}"
)"

PAYMENT_BODY="$(echo "${PAYMENT_RESPONSE}" | sed '$d')"

PAYMENT_ID="$(
  echo "${PAYMENT_BODY}" |
    jq -r '.paymentId'
)"

wait_for_terminal \
  "${PAYMENT_ID}" \
  "${SENDER_TOKEN}" \
  >/dev/null

EVENT_PAYLOAD="$(
  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d payment_db \
    -tA \
    -c "
        SELECT payload
        FROM outbox_events
        WHERE aggregate_id = '${PAYMENT_ID}'
        LIMIT 1;
    "
)"

echo "${EVENT_PAYLOAD}" |
docker exec -i \
  "${KAFKA_CONTAINER}" \
  kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic payments.created \
  >/dev/null

sleep 3

LEDGER_COUNT="$(
  ledger_count \
    "${PAYMENT_ID}"
)"

SENDER_BALANCE="$(
  get_balance \
    "${SENDER_ACCOUNT_ID}"
)"

RECEIVER_BALANCE="$(
  get_balance \
    "${RECEIVER_ACCOUNT_ID}"
)"

if [ "${LEDGER_COUNT}" = "2" ] &&
   [ "${SENDER_BALANCE}" = "8000.00" ] &&
   [ "${RECEIVER_BALANCE}" = "7000.00" ]; then

  pass "Duplicate payments.created does not duplicate money"

else

  fail "Duplicate Kafka event caused duplicate financial effect"
fi

echo
echo "--------------------------------------"
echo "5. Duplicate ledger.completed"
echo "--------------------------------------"

NOTIFICATIONS_BEFORE="$(
  notification_count \
    "${PAYMENT_ID}"
)"

LEDGER_TRANSACTION_ID="$(
  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d ledger_db \
    -tA \
    -c "
        SELECT ledger_transaction_id
        FROM ledger_processing_results
        WHERE payment_id = '${PAYMENT_ID}';
    "
)"

COMPLETED_AT="$(
  date -u +"%Y-%m-%dT%H:%M:%SZ"
)"

LEDGER_COMPLETED_PAYLOAD="$(
  cat <<EOF
{
  "paymentId":"${PAYMENT_ID}",
  "ledgerTransactionId":"${LEDGER_TRANSACTION_ID}",
  "completedAt":"${COMPLETED_AT}"
}
EOF
)"

echo "${LEDGER_COMPLETED_PAYLOAD}" |
docker exec -i \
  "${KAFKA_CONTAINER}" \
  kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic ledger.completed \
  >/dev/null

sleep 3

NOTIFICATIONS_AFTER="$(
  notification_count \
    "${PAYMENT_ID}"
)"

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

if [ "${PAYMENT_STATUS}" = "COMPLETED" ] &&
   [ "${NOTIFICATIONS_AFTER}" = "${NOTIFICATIONS_BEFORE}" ]; then

  pass "Duplicate ledger.completed is idempotent"

else

  fail "Duplicate ledger.completed created duplicate downstream state"
fi

echo
echo "======================================"
echo "MILESTONE 3 SUMMARY"
echo "======================================"
echo "Passed: ${PASS_COUNT}"
echo "Failed: ${FAIL_COUNT}"

if [ "${FAIL_COUNT}" -ne 0 ]; then
  echo "STORY 14 MILESTONE 3 FAILED"
  exit 1
fi

echo "STORY 14 MILESTONE 3 PASSED"