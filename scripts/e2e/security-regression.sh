#!/usr/bin/env bash

set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8081}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-fintech-postgres}"
DB_USERNAME="${DB_USERNAME:-fintech_user}"

PASSWORD="Password123!"

PASS_COUNT=0
FAIL_COUNT=0

echo "======================================"
echo "Story 14 — Security Regression E2E"
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
        }"
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

request_status() {

  curl --silent \
    --output /tmp/story14-security-response.json \
    --write-out "%{http_code}" \
    "$@" || true
}

echo
echo "--------------------------------------"
echo "1. Missing JWT"
echo "--------------------------------------"

CODE="$(
  request_status \
    "${GATEWAY_URL}/api/payments/00000000-0000-0000-0000-000000000001"
)"

if [ "${CODE}" = "401" ]; then
  pass "Missing JWT returns 401"
else
  fail "Missing JWT expected 401, got ${CODE}"
fi

echo
echo "--------------------------------------"
echo "2. Invalid JWT"
echo "--------------------------------------"

CODE="$(
  request_status \
    "${GATEWAY_URL}/api/payments/00000000-0000-0000-0000-000000000001" \
    -H "Authorization: Bearer definitely-not-a-valid-jwt"
)"

if [ "${CODE}" = "401" ]; then
  pass "Invalid JWT returns 401"
else
  fail "Invalid JWT expected 401, got ${CODE}"
fi

echo
echo "--------------------------------------"
echo "3. Prepare authenticated users"
echo "--------------------------------------"

USER_A_LOGIN="$(register_and_login user-a)"
USER_B_LOGIN="$(register_and_login user-b)"
USER_C_LOGIN="$(register_and_login user-c)"

TOKEN_A="$(
  echo "${USER_A_LOGIN}" |
    jq -r '.message'
)"

TOKEN_B="$(
  echo "${USER_B_LOGIN}" |
    jq -r '.message'
)"

TOKEN_C="$(
  echo "${USER_C_LOGIN}" |
    jq -r '.message'
)"

if [ -z "${TOKEN_A}" ] || [ "${TOKEN_A}" = "null" ]; then
  echo "Could not get token for User A"
  exit 1
fi

if [ -z "${TOKEN_B}" ] || [ "${TOKEN_B}" = "null" ]; then
  echo "Could not get token for User B"
  exit 1
fi

if [ -z "${TOKEN_C}" ] || [ "${TOKEN_C}" = "null" ]; then
  echo "Could not get token for User C"
  exit 1
fi

ACCOUNT_A="$(create_account "${TOKEN_A}")"
ACCOUNT_B="$(create_account "${TOKEN_B}")"
ACCOUNT_C="$(create_account "${TOKEN_C}")"

ACCOUNT_A_ID="$(
  echo "${ACCOUNT_A}" |
    jq -r '.accountId'
)"

ACCOUNT_B_ID="$(
  echo "${ACCOUNT_B}" |
    jq -r '.accountId'
)"

USER_A_ID="$(
  echo "${ACCOUNT_A}" |
    jq -r '.ownerId'
)"

USER_B_ID="$(
  echo "${ACCOUNT_B}" |
    jq -r '.ownerId'
)"

USER_C_ID="$(
  echo "${ACCOUNT_C}" |
    jq -r '.ownerId'
)"

fund_account "${ACCOUNT_A_ID}" "10000.00"
fund_account "${ACCOUNT_B_ID}" "5000.00"

echo "✅ Users prepared"

echo
echo "--------------------------------------"
echo "4. Forged X-USER-ID"
echo "--------------------------------------"

CODE="$(
  request_status \
    "${GATEWAY_URL}/api/accounts/me" \
    -H "Authorization: Bearer ${TOKEN_A}" \
    -H "X-USER-ID: ${USER_B_ID}"
)"

if [ "${CODE}" = "200" ]; then

  RESPONSE="$(
    cat /tmp/story14-security-response.json
  )"

  RETURNED_OWNER="$(
    echo "${RESPONSE}" |
      jq -r '.ownerId // empty'
  )"

  if [ "${RETURNED_OWNER}" = "${USER_A_ID}" ]; then
    pass "Forged X-USER-ID is ignored/replaced"
  else
    fail "Forged X-USER-ID influenced downstream identity"
  fi

else
  fail "Expected account request 200, got ${CODE}"
fi

echo
echo "--------------------------------------"
echo "5. Forged X-USER-ROLES"
echo "--------------------------------------"

CODE="$(
  request_status \
    "${GATEWAY_URL}/api/ledger/entries" \
    -H "Authorization: Bearer ${TOKEN_A}" \
    -H "X-USER-ROLES: ADMIN"
)"

if [ "${CODE}" = "403" ]; then
  pass "Forged X-USER-ROLES does not grant admin access"
else
  fail "Forged role expected 403, got ${CODE}"
fi

echo
echo "--------------------------------------"
echo "6. Sender spoofing"
echo "--------------------------------------"

IDEMPOTENCY_KEY="sender-spoof-$(date +%s)-$RANDOM"

CODE="$(
  request_status \
    -X POST \
    "${GATEWAY_URL}/api/payments/pay" \
    -H "Authorization: Bearer ${TOKEN_A}" \
    -H "Content-Type: application/json" \
    -d "{
          \"senderId\":\"${USER_B_ID}\",
          \"receiverId\":\"${USER_A_ID}\",
          \"amount\":100.00,
          \"currency\":\"INR\",
          \"idempotencyKey\":\"${IDEMPOTENCY_KEY}\"
        }"
)"

if [ "${CODE}" = "403" ]; then
  pass "Authenticated user cannot spoof senderId"
else
  fail "Sender spoofing expected 403, got ${CODE}"
fi

echo
echo "--------------------------------------"
echo "7. Sender can read own payment"
echo "--------------------------------------"

KEY="ownership-$(date +%s)-$RANDOM"

PAYMENT_RESPONSE="$(
  curl --fail --silent \
    -X POST \
    "${GATEWAY_URL}/api/payments/pay" \
    -H "Authorization: Bearer ${TOKEN_A}" \
    -H "Content-Type: application/json" \
    -d "{
          \"senderId\":\"${USER_A_ID}\",
          \"receiverId\":\"${USER_B_ID}\",
          \"amount\":100.00,
          \"currency\":\"INR\",
          \"idempotencyKey\":\"${KEY}\"
        }"
)"

PAYMENT_ID="$(
  echo "${PAYMENT_RESPONSE}" |
    jq -r '.paymentId'
)"

sleep 2

CODE="$(
  request_status \
    "${GATEWAY_URL}/api/payments/${PAYMENT_ID}" \
    -H "Authorization: Bearer ${TOKEN_A}"
)"

if [ "${CODE}" = "200" ]; then
  pass "Sender can read own payment"
else
  fail "Sender expected 200, got ${CODE}"
fi

echo
echo "--------------------------------------"
echo "8. Receiver can read related payment"
echo "--------------------------------------"

CODE="$(
  request_status \
    "${GATEWAY_URL}/api/payments/${PAYMENT_ID}" \
    -H "Authorization: Bearer ${TOKEN_B}"
)"

if [ "${CODE}" = "200" ]; then
  pass "Receiver can read payment they participate in"
else
  fail "Receiver expected 200, got ${CODE}"
fi

echo
echo "--------------------------------------"
echo "9. Unrelated user cannot read payment"
echo "--------------------------------------"

CODE="$(
  request_status \
    "${GATEWAY_URL}/api/payments/${PAYMENT_ID}" \
    -H "Authorization: Bearer ${TOKEN_C}"
)"

if [ "${CODE}" = "403" ] || [ "${CODE}" = "404" ]; then
  pass "Unrelated user cannot read another payment"
else
  fail "Unrelated user expected 403/404, got ${CODE}"
fi

echo
echo "--------------------------------------"
echo "10. Normal user cannot access admin ledger"
echo "--------------------------------------"

CODE="$(
  request_status \
    "${GATEWAY_URL}/api/ledger/entries" \
    -H "Authorization: Bearer ${TOKEN_A}"
)"

if [ "${CODE}" = "403" ]; then
  pass "Normal user blocked from admin ledger endpoint"
else
  fail "Admin-only endpoint expected 403, got ${CODE}"
fi

echo
echo "--------------------------------------"
echo "11. User can access own ledger"
echo "--------------------------------------"

CODE="$(
  request_status \
    "${GATEWAY_URL}/api/ledger/my-entries" \
    -H "Authorization: Bearer ${TOKEN_A}"
)"

if [ "${CODE}" = "200" ]; then
  pass "Authenticated user can access own ledger entries"
else
  fail "Own ledger endpoint expected 200, got ${CODE}"
fi

echo
echo "--------------------------------------"
echo "12. Direct downstream request"
echo "--------------------------------------"

CODE="$(
  request_status \
    "http://localhost:8083/api/payments/${PAYMENT_ID}"
)"

if [ "${CODE}" = "401" ] || [ "${CODE}" = "403" ]; then
  pass "Direct Payment Service request without trusted identity is rejected"
else
  fail "Direct downstream request expected 401/403, got ${CODE}"
fi

rm -f /tmp/story14-security-response.json

echo
echo "======================================"
echo "SECURITY REGRESSION SUMMARY"
echo "======================================"
echo "Passed: ${PASS_COUNT}"
echo "Failed: ${FAIL_COUNT}"

if [ "${FAIL_COUNT}" -ne 0 ]; then
  echo "STORY 14 MILESTONE 6 FAILED"
  exit 1
fi

echo "STORY 14 MILESTONE 6 PASSED"