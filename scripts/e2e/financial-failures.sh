#!/usr/bin/env bash

set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8081}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-fintech-postgres}"
DB_USERNAME="${DB_USERNAME:-fintech_user}"

PASSWORD="Password123!"

PASS_COUNT=0
FAIL_COUNT=0

echo "======================================"
echo "Story 14 Financial Failure E2E"
echo "======================================"

fail() {
  echo "❌ $1"
  FAIL_COUNT=$((FAIL_COUNT + 1))
}

pass() {
  echo "✅ $1"
  PASS_COUNT=$((PASS_COUNT + 1))
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

  local login

  login="$(
    curl --fail --silent \
      -X POST \
      "${GATEWAY_URL}/api/auth/login" \
      -H "Content-Type: application/json" \
      -d "{
            \"email\":\"${email}\",
            \"password\":\"${PASSWORD}\"
          }"
  )"

  echo "${login}" | jq -r '.message'
}

create_account() {

  local token="$1"
  local currency="$2"

  curl --fail --silent \
    -X POST \
    "${GATEWAY_URL}/api/accounts" \
    -H "Authorization: Bearer ${token}" \
    -H "Content-Type: application/json" \
    -d "{
          \"currency\":\"${currency}\"
        }"
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

set_account_status() {

  local account_id="$1"
  local status="$2"

  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d ledger_db \
    -v ON_ERROR_STOP=1 \
    -c "
        UPDATE accounts
        SET status = '${status}',
            updated_at = NOW()
        WHERE id = '${account_id}';
    " \
    >/dev/null
}

set_account_currency() {

  local account_id="$1"
  local currency="$2"

  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d ledger_db \
    -v ON_ERROR_STOP=1 \
    -c "
        UPDATE accounts
        SET currency = '${currency}',
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

get_failed_notification_count() {

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
        WHERE payment_id = '${payment_id}'
          AND type = 'PAYMENT_FAILED';
    "
}

wait_for_terminal_payment() {

  local payment_id="$1"
  local token="$2"

  local response=""
  local status="PROCESSING"

  for attempt in $(seq 1 20); do

    response="$(
      curl --fail --silent \
        "${GATEWAY_URL}/api/payments/${payment_id}" \
        -H "Authorization: Bearer ${token}"
    )"

    status="$(
      echo "${response}" |
        jq -r '.status'
    )"

    if [ "${status}" = "FAILED" ] ||
       [ "${status}" = "COMPLETED" ]; then

      echo "${response}"
      return
    fi

    sleep 1
  done

  echo "${response}"
}

wait_for_failed_notification() {

  local payment_id="$1"

  local count="0"

  for attempt in $(seq 1 15); do

    count="$(
      get_failed_notification_count \
        "${payment_id}"
    )"

    if [ "${count}" = "1" ]; then
      echo "${count}"
      return
    fi

    sleep 1
  done

  echo "${count}"
}

create_payment() {

  local token="$1"
  local sender_id="$2"
  local receiver_id="$3"
  local amount="$4"
  local currency="$5"

  local key="story14-failure-$(date +%s)-$RANDOM"

  curl --fail --silent \
    -X POST \
    "${GATEWAY_URL}/api/payments/pay" \
    -H "Authorization: Bearer ${token}" \
    -H "Content-Type: application/json" \
    -d "{
          \"senderId\":\"${sender_id}\",
          \"receiverId\":\"${receiver_id}\",
          \"amount\":${amount},
          \"currency\":\"${currency}\",
          \"idempotencyKey\":\"${key}\"
        }"
}

run_failure_scenario() {

  local name="$1"
  local expected_reason="$2"
  local sender_balance="$3"
  local receiver_balance="$4"
  local setup_function="$5"

  echo
  echo "--------------------------------------"
  echo "${name}"
  echo "--------------------------------------"

  local sender_token
  local receiver_token

  sender_token="$(
    register_and_login \
      "sender"
  )"

  receiver_token="$(
    register_and_login \
      "receiver"
  )"

  local sender_account
  local receiver_account

  sender_account="$(
    create_account \
      "${sender_token}" \
      "INR"
  )"

  receiver_account="$(
    create_account \
      "${receiver_token}" \
      "INR"
  )"

  local sender_account_id
  local receiver_account_id
  local sender_user_id
  local receiver_user_id

  sender_account_id="$(
    echo "${sender_account}" |
      jq -r '.accountId'
  )"

  receiver_account_id="$(
    echo "${receiver_account}" |
      jq -r '.accountId'
  )"

  sender_user_id="$(
    echo "${sender_account}" |
      jq -r '.ownerId'
  )"

  receiver_user_id="$(
    echo "${receiver_account}" |
      jq -r '.ownerId'
  )"

  fund_account \
    "${sender_account_id}" \
    "${sender_balance}"

  fund_account \
    "${receiver_account_id}" \
    "${receiver_balance}"

  "${setup_function}" \
    "${sender_account_id}" \
    "${receiver_account_id}"

  local sender_before
  local receiver_before

  sender_before="$(
    get_balance \
      "${sender_account_id}"
  )"

  receiver_before="$(
    get_balance \
      "${receiver_account_id}"
  )"

  local payment_response

  payment_response="$(
    create_payment \
      "${sender_token}" \
      "${sender_user_id}" \
      "${receiver_user_id}" \
      "2000.00" \
      "INR"
  )"

  local payment_id

  payment_id="$(
    echo "${payment_response}" |
      jq -r '.paymentId'
  )"

  echo "Payment ID: ${payment_id}"

  local terminal_response

  terminal_response="$(
    wait_for_terminal_payment \
      "${payment_id}" \
      "${sender_token}"
  )"

  local status
  local failure_reason

  status="$(
    echo "${terminal_response}" |
      jq -r '.status'
  )"

  failure_reason="$(
    echo "${terminal_response}" |
      jq -r '.failureReason'
  )"

  if [ "${status}" != "FAILED" ]; then

    fail "${name}: expected FAILED but got ${status}"
    return
  fi

  if [ "${failure_reason}" != "${expected_reason}" ]; then

    fail "${name}: expected ${expected_reason}, got ${failure_reason}"
    return
  fi

  local sender_after
  local receiver_after

  sender_after="$(
    get_balance \
      "${sender_account_id}"
  )"

  receiver_after="$(
    get_balance \
      "${receiver_account_id}"
  )"

  if [ "${sender_before}" != "${sender_after}" ]; then

    fail "${name}: sender balance changed ${sender_before} -> ${sender_after}"
    return
  fi

  if [ "${receiver_before}" != "${receiver_after}" ]; then

    fail "${name}: receiver balance changed ${receiver_before} -> ${receiver_after}"
    return
  fi

  local ledger_count

  ledger_count="$(
    get_ledger_count \
      "${payment_id}"
  )"

  if [ "${ledger_count}" != "0" ]; then

    fail "${name}: expected 0 ledger entries, found ${ledger_count}"
    return
  fi

  local notification_count

  notification_count="$(
    wait_for_failed_notification \
      "${payment_id}"
  )"

  if [ "${notification_count}" != "1" ]; then

    fail "${name}: expected one PAYMENT_FAILED notification, found ${notification_count}"
    return
  fi

  pass "${name}"

  echo "Status:            ${status}"
  echo "Reason:            ${failure_reason}"
  echo "Sender balance:    ${sender_after}"
  echo "Receiver balance:  ${receiver_after}"
  echo "Ledger entries:    ${ledger_count}"
  echo "Failed notification: ${notification_count}"
}

setup_insufficient_balance() {
  :
}

setup_inactive_sender() {

  local sender_account_id="$1"

  set_account_status \
    "${sender_account_id}" \
    "INACTIVE"
}

setup_currency_mismatch() {

  local sender_account_id="$1"
  local receiver_account_id="$2"

  set_account_currency \
    "${receiver_account_id}" \
    "USD"
}

run_failure_scenario \
  "Insufficient Balance" \
  "INSUFFICIENT_BALANCE" \
  "1000.00" \
  "5000.00" \
  setup_insufficient_balance

run_failure_scenario \
  "Inactive Sender" \
  "ACCOUNT_INACTIVE" \
  "10000.00" \
  "5000.00" \
  setup_inactive_sender

run_failure_scenario \
  "Currency Mismatch" \
  "CURRENCY_MISMATCH" \
  "10000.00" \
  "5000.00" \
  setup_currency_mismatch

echo
echo "======================================"
echo "FINANCIAL FAILURE E2E SUMMARY"
echo "======================================"
echo "Passed: ${PASS_COUNT}"
echo "Failed: ${FAIL_COUNT}"

if [ "${FAIL_COUNT}" -ne 0 ]; then

  echo "STORY 14 MILESTONE 2 FAILED"
  exit 1
fi

echo "STORY 14 MILESTONE 2 PASSED"