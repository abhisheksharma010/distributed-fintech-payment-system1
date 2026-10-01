#!/usr/bin/env bash

set -euo pipefail

# =============================================================================
# Kubernetes Happy Path E2E
# =============================================================================
# Flow:
# Gateway
#   -> Register sender/receiver
#   -> Login
#   -> Create accounts
#   -> Controlled DB funding
#   -> Create payment
#   -> Wait for Saga COMPLETED
#   -> Verify balances
#   -> Verify DEBIT/CREDIT
#   -> Verify notification
#   -> Verify Kafka
#   -> Verify Redis
#   -> Verify no NEW pod restarts during test
#
# Requires:
#   kubectl
#   curl
#   jq
# =============================================================================

NAMESPACE="fintech"
LOCAL_PORT="18081"
GATEWAY_URL="http://127.0.0.1:${LOCAL_PORT}"

DB_USER="fintech_user"

PASSWORD="Password123!"
AMOUNT="2000.00"
SENDER_OPENING_BALANCE="10000.00"
RECEIVER_OPENING_BALANCE="5000.00"

RUN_ID="$(date +%s)-$RANDOM"

SENDER_EMAIL="sender-${RUN_ID}@example.com"
RECEIVER_EMAIL="receiver-${RUN_ID}@example.com"

IDEMPOTENCY_KEY="k8s-e2e-${RUN_ID}"

PORT_FORWARD_PID=""

log() {
  echo "[e2e] $*" >&2
}

ok() {
  echo "[ ok] $*"
}

die() {
  echo "[fail] $*" >&2
  exit 1
}

cleanup() {
  if [[ -n "${PORT_FORWARD_PID}" ]]; then
    kill "${PORT_FORWARD_PID}" >/dev/null 2>&1 || true
    wait "${PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
}

trap cleanup EXIT INT TERM

# =============================================================================
# Requirements
# =============================================================================

for tool in kubectl curl jq; do
  command -v "${tool}" >/dev/null 2>&1 \
    || die "${tool} is required"
done

kubectl get namespace "${NAMESPACE}" >/dev/null 2>&1 \
  || die "namespace ${NAMESPACE} not found"

# =============================================================================
# Restart baseline
# =============================================================================

RESTARTS_BEFORE="$(
  kubectl get pods -n "${NAMESPACE}" -o json |
    jq '[.items[].status.containerStatuses[]?.restartCount] | add // 0'
)"

# =============================================================================
# Gateway port-forward
# =============================================================================

log "port-forwarding svc/api-gateway ${LOCAL_PORT} -> 8081"

kubectl port-forward \
  -n "${NAMESPACE}" \
  svc/api-gateway \
  "${LOCAL_PORT}:8081" \
  >/tmp/k8s-e2e-port-forward.log 2>&1 &

PORT_FORWARD_PID=$!

GATEWAY_READY=false

for _ in $(seq 1 30); do

  HTTP_CODE="$(
    curl -sS \
      -o /dev/null \
      -w '%{http_code}' \
      "${GATEWAY_URL}/actuator/health" \
      2>/dev/null || true
  )"

  # 401 is currently expected because Gateway actuator is protected.
  # Any real HTTP response proves the Gateway is reachable.
  if [[ -n "${HTTP_CODE}" && "${HTTP_CODE}" != "000" ]]; then
    GATEWAY_READY=true
    break
  fi

  sleep 1
done

[[ "${GATEWAY_READY}" == "true" ]] \
  || die "gateway did not become reachable"

ok "gateway reachable at ${GATEWAY_URL}"

# =============================================================================
# PostgreSQL helper
# =============================================================================

psql_query() {
  local database="$1"
  local sql="$2"

  kubectl exec \
    -n "${NAMESPACE}" \
    deployment/postgres \
    -- \
    psql \
      -U "${DB_USER}" \
      -d "${database}" \
      -At \
      -c "${sql}" |
    tr -d '\r'
}

# =============================================================================
# GET helper with transient retries
# =============================================================================

get_with_retry() {
  local url="$1"
  local token="$2"

  local body_file
  local code
  local body

  body_file="$(mktemp)"

  for attempt in $(seq 1 15); do

    : > "${body_file}"

    code="$(
      curl -sS \
        -o "${body_file}" \
        -w '%{http_code}' \
        "${url}" \
        -H "Authorization: Bearer ${token}" \
        2>/dev/null || true
    )"

    code="${code:-000}"
    body="$(cat "${body_file}")"

    if [[ "${code}" =~ ^2[0-9][0-9]$ ]]; then
      rm -f "${body_file}"
      printf '%s' "${body}"
      return 0
    fi

    if [[ \
      "${code}" == "000" ||
      "${code}" == "502" ||
      "${code}" == "503" ||
      "${code}" == "504"
    ]]; then

      log "GET transient HTTP=${code}, retry ${attempt}/15"
      sleep 1
      continue
    fi

    rm -f "${body_file}"
    die "GET ${url} failed HTTP=${code}: ${body}"
  done

  rm -f "${body_file}"
  die "GET ${url} failed after retries"
}

# =============================================================================
# 1. Register users
# =============================================================================

register() {
  local email="$1"

  local body_file
  local code
  local body

  body_file="$(mktemp)"

  log "registering ${email}"

  for attempt in $(seq 1 15); do

    : > "${body_file}"

    code="$(
      curl -sS \
        -o "${body_file}" \
        -w '%{http_code}' \
        -X POST "${GATEWAY_URL}/api/auth/register" \
        -H "Content-Type: application/json" \
        -d "{
          \"email\":\"${email}\",
          \"password\":\"${PASSWORD}\"
        }" \
        2>/dev/null || true
    )"

    code="${code:-000}"
    body="$(cat "${body_file}")"

    if [[ "${code}" =~ ^2[0-9][0-9]$ ]]; then
      rm -f "${body_file}"
      return 0
    fi

    # A previous request may have reached Identity successfully,
    # while Gateway returned a transient 503.
    if [[ "${code}" == "409" ]]; then
      rm -f "${body_file}"
      return 0
    fi

    if [[ \
      "${code}" == "000" ||
      "${code}" == "502" ||
      "${code}" == "503" ||
      "${code}" == "504"
    ]]; then

      log "register transient HTTP=${code}, retry ${attempt}/15"
      sleep 1
      continue
    fi

    rm -f "${body_file}"
    die "register ${email} returned HTTP=${code}: ${body}"
  done

  rm -f "${body_file}"
  die "register ${email} failed after retries"
}

register "${SENDER_EMAIL}"
register "${RECEIVER_EMAIL}"

ok "users registered"

# =============================================================================
# 2. Login
# =============================================================================

login() {
  local email="$1"

  local body_file
  local code
  local body
  local token

  body_file="$(mktemp)"

  for attempt in $(seq 1 20); do

    : > "${body_file}"

    code="$(
      curl -sS \
        -o "${body_file}" \
        -w '%{http_code}' \
        -X POST "${GATEWAY_URL}/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{
          \"email\":\"${email}\",
          \"password\":\"${PASSWORD}\"
        }" \
        2>/dev/null || true
    )"

    code="${code:-000}"
    body="$(cat "${body_file}")"

    if [[ "${code}" =~ ^2[0-9][0-9]$ ]]; then

      token="$(
        printf '%s' "${body}" |
          jq -r '.message // empty' 2>/dev/null || true
      )"

      if [[ -n "${token}" && "${token}" != "null" ]]; then
        rm -f "${body_file}"
        printf '%s' "${token}"
        return 0
      fi
    fi

    if [[ \
      "${code}" == "000" ||
      "${code}" == "502" ||
      "${code}" == "503" ||
      "${code}" == "504"
    ]]; then
      sleep 1
      continue
    fi

    sleep 1
  done

  rm -f "${body_file}"
  die "login failed for ${email}"
}

SENDER_TOKEN="$(login "${SENDER_EMAIL}")"
RECEIVER_TOKEN="$(login "${RECEIVER_EMAIL}")"

ok "logged in sender and receiver"

# =============================================================================
# 3. Create accounts
# =============================================================================

create_account() {
  local token="$1"

  local body_file
  local code
  local body
  local existing

  body_file="$(mktemp)"

  for attempt in $(seq 1 15); do

    : > "${body_file}"

    code="$(
      curl -sS \
        -o "${body_file}" \
        -w '%{http_code}' \
        -X POST "${GATEWAY_URL}/api/accounts" \
        -H "Authorization: Bearer ${token}" \
        -H "Content-Type: application/json" \
        -d '{"currency":"INR"}' \
        2>/dev/null || true
    )"

    code="${code:-000}"
    body="$(cat "${body_file}")"

    if [[ "${code}" =~ ^2[0-9][0-9]$ ]]; then

      if printf '%s' "${body}" | jq -e . >/dev/null 2>&1; then
        rm -f "${body_file}"
        printf '%s' "${body}"
        return 0
      fi

      rm -f "${body_file}"
      die "account creation returned invalid JSON: ${body}"
    fi

    # Important:
    # Gateway may return 503 after Ledger already created the account.
    # The retry then returns 409. Fetch the account instead.
    if [[ "${code}" == "409" ]]; then

      rm -f "${body_file}"

      existing="$(
        get_with_retry \
          "${GATEWAY_URL}/api/accounts/me" \
          "${token}"
      )"

      printf '%s' "${existing}"
      return 0
    fi

    if [[ \
      "${code}" == "000" ||
      "${code}" == "502" ||
      "${code}" == "503" ||
      "${code}" == "504"
    ]]; then

      log "account creation transient HTTP=${code}, retry ${attempt}/15"
      sleep 1
      continue
    fi

    rm -f "${body_file}"
    die "account creation failed HTTP=${code}: ${body}"
  done

  rm -f "${body_file}"
  die "account creation failed after retries"
}

SENDER_ACCOUNT="$(create_account "${SENDER_TOKEN}")"

SENDER_ACCOUNT_ID="$(
  printf '%s' "${SENDER_ACCOUNT}" |
    jq -r '.accountId // empty'
)"

SENDER_USER_ID="$(
  printf '%s' "${SENDER_ACCOUNT}" |
    jq -r '.ownerId // empty'
)"

[[ -n "${SENDER_ACCOUNT_ID}" ]] \
  || die "sender account creation failed: ${SENDER_ACCOUNT}"

[[ -n "${SENDER_USER_ID}" ]] \
  || die "sender ownerId missing: ${SENDER_ACCOUNT}"

RECEIVER_ACCOUNT="$(create_account "${RECEIVER_TOKEN}")"

RECEIVER_ACCOUNT_ID="$(
  printf '%s' "${RECEIVER_ACCOUNT}" |
    jq -r '.accountId // empty'
)"

RECEIVER_USER_ID="$(
  printf '%s' "${RECEIVER_ACCOUNT}" |
    jq -r '.ownerId // empty'
)"

[[ -n "${RECEIVER_ACCOUNT_ID}" ]] \
  || die "receiver account creation failed: ${RECEIVER_ACCOUNT}"

[[ -n "${RECEIVER_USER_ID}" ]] \
  || die "receiver ownerId missing: ${RECEIVER_ACCOUNT}"

ok "accounts created (sender=${SENDER_ACCOUNT_ID} receiver=${RECEIVER_ACCOUNT_ID})"

# =============================================================================
# 4. Controlled E2E funding
# =============================================================================

log "seeding balances via postgres pod"

OPENING_BALANCE_COLUMN="$(
  psql_query \
    "ledger_db" \
    "SELECT column_name
     FROM information_schema.columns
     WHERE table_name='accounts'
       AND column_name='opening_balance'
     LIMIT 1;"
)"

if [[ "${OPENING_BALANCE_COLUMN}" == "opening_balance" ]]; then

  psql_query \
    "ledger_db" \
    "UPDATE accounts
     SET balance=${SENDER_OPENING_BALANCE},
         opening_balance=${SENDER_OPENING_BALANCE}
     WHERE id='${SENDER_ACCOUNT_ID}';" \
    >/dev/null

  psql_query \
    "ledger_db" \
    "UPDATE accounts
     SET balance=${RECEIVER_OPENING_BALANCE},
         opening_balance=${RECEIVER_OPENING_BALANCE}
     WHERE id='${RECEIVER_ACCOUNT_ID}';" \
    >/dev/null

else

  psql_query \
    "ledger_db" \
    "UPDATE accounts
     SET balance=${SENDER_OPENING_BALANCE}
     WHERE id='${SENDER_ACCOUNT_ID}';" \
    >/dev/null

  psql_query \
    "ledger_db" \
    "UPDATE accounts
     SET balance=${RECEIVER_OPENING_BALANCE}
     WHERE id='${RECEIVER_ACCOUNT_ID}';" \
    >/dev/null
fi

SENDER_BEFORE="$(
  psql_query \
    "ledger_db" \
    "SELECT balance
     FROM accounts
     WHERE id='${SENDER_ACCOUNT_ID}';"
)"

RECEIVER_BEFORE="$(
  psql_query \
    "ledger_db" \
    "SELECT balance
     FROM accounts
     WHERE id='${RECEIVER_ACCOUNT_ID}';"
)"

[[ "${SENDER_BEFORE}" == "10000.00" ]] \
  || die "sender seed failed: ${SENDER_BEFORE}"

[[ "${RECEIVER_BEFORE}" == "5000.00" ]] \
  || die "receiver seed failed: ${RECEIVER_BEFORE}"

ok "balances seeded"

# =============================================================================
# 5. Create payment
# =============================================================================

create_payment() {
  local body_file
  local code
  local body

  body_file="$(mktemp)"

  for attempt in $(seq 1 15); do

    : > "${body_file}"

    code="$(
      curl -sS \
        -o "${body_file}" \
        -w '%{http_code}' \
        -X POST "${GATEWAY_URL}/api/payments/pay" \
        -H "Authorization: Bearer ${SENDER_TOKEN}" \
        -H "Content-Type: application/json" \
        -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
        -d "{
          \"senderId\":\"${SENDER_USER_ID}\",
          \"receiverId\":\"${RECEIVER_USER_ID}\",
          \"amount\":${AMOUNT},
          \"currency\":\"INR\",
          \"idempotencyKey\":\"${IDEMPOTENCY_KEY}\"
        }" \
        2>/dev/null || true
    )"

    code="${code:-000}"
    body="$(cat "${body_file}")"

    if [[ "${code}" =~ ^2[0-9][0-9]$ ]]; then

      if printf '%s' "${body}" | jq -e . >/dev/null 2>&1; then
        rm -f "${body_file}"
        printf '%s' "${body}"
        return 0
      fi

      rm -f "${body_file}"
      die "payment creation returned invalid JSON: ${body}"
    fi

    if [[ \
      "${code}" == "000" ||
      "${code}" == "502" ||
      "${code}" == "503" ||
      "${code}" == "504"
    ]]; then

      log "payment creation transient HTTP=${code}, retry ${attempt}/15"
      sleep 1
      continue
    fi

    rm -f "${body_file}"
    die "payment creation failed HTTP=${code}: ${body}"
  done

  rm -f "${body_file}"
  die "payment creation failed after retries"
}

log "creating payment"

PAYMENT_RESPONSE="$(create_payment)"

PAYMENT_ID="$(
  printf '%s' "${PAYMENT_RESPONSE}" |
    jq -r '.paymentId // empty'
)"

[[ -n "${PAYMENT_ID}" ]] \
  || die "paymentId missing: ${PAYMENT_RESPONSE}"

ok "payment created ${PAYMENT_ID}"

# =============================================================================
# 6. Wait for Saga
# =============================================================================

log "waiting for payment Saga"

PAYMENT_STATUS=""
PAYMENT_READ=""
FAILURE_REASON=""

for attempt in $(seq 1 60); do

  PAYMENT_READ="$(
    get_with_retry \
      "${GATEWAY_URL}/api/payments/${PAYMENT_ID}" \
      "${SENDER_TOKEN}"
  )"

  PAYMENT_STATUS="$(
    printf '%s' "${PAYMENT_READ}" |
      jq -r '.status // empty'
  )"

  log "payment attempt ${attempt}: status=${PAYMENT_STATUS}"

  if [[ "${PAYMENT_STATUS}" == "COMPLETED" ]]; then
    break
  fi

  if [[ "${PAYMENT_STATUS}" == "FAILED" ]]; then

    FAILURE_REASON="$(
      printf '%s' "${PAYMENT_READ}" |
        jq -r '.failureReason // empty'
    )"

    die "payment FAILED reason=${FAILURE_REASON}"
  fi

  sleep 1
done

[[ "${PAYMENT_STATUS}" == "COMPLETED" ]] \
  || die "payment did not reach COMPLETED"

ok "payment COMPLETED"

# =============================================================================
# 7. Verify authoritative balances
# =============================================================================

SENDER_AFTER="$(
  psql_query \
    "ledger_db" \
    "SELECT balance
     FROM accounts
     WHERE id='${SENDER_ACCOUNT_ID}';"
)"

RECEIVER_AFTER="$(
  psql_query \
    "ledger_db" \
    "SELECT balance
     FROM accounts
     WHERE id='${RECEIVER_ACCOUNT_ID}';"
)"

[[ "${SENDER_AFTER}" == "8000.00" ]] \
  || die "unexpected sender balance: ${SENDER_AFTER}"

[[ "${RECEIVER_AFTER}" == "7000.00" ]] \
  || die "unexpected receiver balance: ${RECEIVER_AFTER}"

ok "balances verified"

# =============================================================================
# 8. Verify double-entry Ledger
# =============================================================================

ENTRY_TYPE_COLUMN="$(
  psql_query \
    "ledger_db" \
    "SELECT column_name
     FROM information_schema.columns
     WHERE table_name='ledger_entries'
       AND column_name IN ('entry_type','type')
     ORDER BY CASE
       WHEN column_name='entry_type' THEN 1
       ELSE 2
     END
     LIMIT 1;"
)"

[[ -n "${ENTRY_TYPE_COLUMN}" ]] \
  || die "ledger entry type column not found"

DEBIT_COUNT="$(
  psql_query \
    "ledger_db" \
    "SELECT COUNT(*)
     FROM ledger_entries
     WHERE payment_id='${PAYMENT_ID}'
       AND ${ENTRY_TYPE_COLUMN}='DEBIT';"
)"

CREDIT_COUNT="$(
  psql_query \
    "ledger_db" \
    "SELECT COUNT(*)
     FROM ledger_entries
     WHERE payment_id='${PAYMENT_ID}'
       AND ${ENTRY_TYPE_COLUMN}='CREDIT';"
)"

DEBIT_TOTAL="$(
  psql_query \
    "ledger_db" \
    "SELECT COALESCE(SUM(amount),0)
     FROM ledger_entries
     WHERE payment_id='${PAYMENT_ID}'
       AND ${ENTRY_TYPE_COLUMN}='DEBIT';"
)"

CREDIT_TOTAL="$(
  psql_query \
    "ledger_db" \
    "SELECT COALESCE(SUM(amount),0)
     FROM ledger_entries
     WHERE payment_id='${PAYMENT_ID}'
       AND ${ENTRY_TYPE_COLUMN}='CREDIT';"
)"

[[ "${DEBIT_COUNT}" == "1" ]] \
  || die "expected exactly 1 DEBIT, got ${DEBIT_COUNT}"

[[ "${CREDIT_COUNT}" == "1" ]] \
  || die "expected exactly 1 CREDIT, got ${CREDIT_COUNT}"

[[ "${DEBIT_TOTAL}" == "2000.00" ]] \
  || die "unexpected DEBIT total: ${DEBIT_TOTAL}"

[[ "${CREDIT_TOTAL}" == "2000.00" ]] \
  || die "unexpected CREDIT total: ${CREDIT_TOTAL}"

ok "double-entry ledger verified"

# =============================================================================
# 9. Verify notification
# =============================================================================

log "waiting for payment notification"

NOTIFICATION_TYPE_COLUMN="$(
  psql_query \
    "notification_db" \
    "SELECT column_name
     FROM information_schema.columns
     WHERE table_name='notifications'
       AND column_name IN ('type','notification_type')
     ORDER BY CASE
       WHEN column_name='type' THEN 1
       ELSE 2
     END
     LIMIT 1;"
)"

[[ -n "${NOTIFICATION_TYPE_COLUMN}" ]] \
  || die "notification type column not found"

NOTIFICATION_COUNT="0"

for _ in $(seq 1 30); do

  NOTIFICATION_COUNT="$(
    psql_query \
      "notification_db" \
      "SELECT COUNT(*)
       FROM notifications
       WHERE payment_id='${PAYMENT_ID}'
         AND ${NOTIFICATION_TYPE_COLUMN}='PAYMENT_COMPLETED';"
  )"

  if [[ "${NOTIFICATION_COUNT}" == "1" ]]; then
    break
  fi

  sleep 1
done

[[ "${NOTIFICATION_COUNT}" == "1" ]] \
  || die "expected 1 PAYMENT_COMPLETED notification, got ${NOTIFICATION_COUNT}"

ok "notification verified"

# =============================================================================
# 10. Kafka smoke
# =============================================================================

kubectl exec \
  -n "${NAMESPACE}" \
  deployment/kafka \
  -- \
  kafka-broker-api-versions \
    --bootstrap-server kafka:29092 \
  >/dev/null 2>&1 \
  || die "Kafka check failed"

ok "Kafka OK"

# =============================================================================
# 11. Redis smoke
# =============================================================================

REDIS_RESULT="$(
  kubectl exec \
    -n "${NAMESPACE}" \
    deployment/redis \
    -- redis-cli ping |
    tr -d '\r'
)"

[[ "${REDIS_RESULT}" == "PONG" ]] \
  || die "Redis check failed: ${REDIS_RESULT}"

ok "Redis OK"

# =============================================================================
# 12. Verify no NEW pod restarts during E2E
# =============================================================================

RESTARTS_AFTER="$(
  kubectl get pods -n "${NAMESPACE}" -o json |
    jq '[.items[].status.containerStatuses[]?.restartCount] | add // 0'
)"

NEW_RESTARTS=$((RESTARTS_AFTER - RESTARTS_BEFORE))

[[ "${NEW_RESTARTS}" -eq 0 ]] \
  || die "${NEW_RESTARTS} new pod restart(s) occurred during E2E"

# =============================================================================
# Success
# =============================================================================

echo
echo "========================================"
echo "KUBERNETES HAPPY PATH PASSED"
echo "========================================"
echo "Payment:       ${PAYMENT_ID}"
echo "Sender:        ${SENDER_BEFORE} -> ${SENDER_AFTER}"
echo "Receiver:      ${RECEIVER_BEFORE} -> ${RECEIVER_AFTER}"
echo "DEBIT total:   ${DEBIT_TOTAL}"
echo "CREDIT total:  ${CREDIT_TOTAL}"
echo "Notification:  ${NOTIFICATION_COUNT}"
echo "Kafka:         OK"
echo "Redis:         OK"
echo "New restarts:  ${NEW_RESTARTS}"
echo "========================================"