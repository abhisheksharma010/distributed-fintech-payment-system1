#!/usr/bin/env bash

set -euo pipefail

POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-fintech-postgres}"
DB_USERNAME="${DB_USERNAME:-fintech_user}"

echo "======================================"
echo "Story 14 — Recovery Exhaustion Safety"
echo "======================================"

PAYMENT_ID="$(
  uuidgen |
    tr '[:upper:]' '[:lower:]'
)"

SENDER_ID="$(
  uuidgen |
    tr '[:upper:]' '[:lower:]'
)"

RECEIVER_ID="$(
  uuidgen |
    tr '[:upper:]' '[:lower:]'
)"

IDEMPOTENCY_KEY="story14-exhaustion-${PAYMENT_ID}"

cleanup() {

  echo
  echo "Cleaning test fixture..."

  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d payment_db \
    -v ON_ERROR_STOP=1 \
    -c "
        DELETE FROM outbox_events
        WHERE aggregate_id = '${PAYMENT_ID}';

        DELETE FROM payments
        WHERE id = '${PAYMENT_ID}';
    " \
    >/dev/null 2>&1 || true

  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d notification_db \
    -v ON_ERROR_STOP=1 \
    -c "
        DELETE FROM notifications
        WHERE payment_id = '${PAYMENT_ID}';
    " \
    >/dev/null 2>&1 || true
}

trap cleanup EXIT

echo "Creating controlled stale PROCESSING payment..."

#
# This payment deliberately has:
#
# status                 = PROCESSING
# recovery_attempt_count = 2
# updated_at             = 10 minutes old
# last_recovery_at       = 10 minutes old
#
# There is NO payments.created event and NO Ledger result.
#
# Therefore LedgerRecoveryClient must return NOT_FOUND.
#
# With maxAttempts=3, the next recovery attempt becomes 3.
#
# Correct safe behavior:
#
#   PROCESSING stays PROCESSING
#   failure_reason stays NULL
#   recovery_attempt_count becomes 3
#
# It MUST NOT become FAILED merely because Ledger currently
# returns NOT_FOUND.
#

docker exec \
  "${POSTGRES_CONTAINER}" \
  psql \
  -U "${DB_USERNAME}" \
  -d payment_db \
  -v ON_ERROR_STOP=1 \
  -c "
      INSERT INTO payments (
          id,
          sender_id,
          receiver_id,
          amount,
          currency,
          status,
          idempotency_key,
          failure_reason,
          created_at,
          updated_at,
          recovery_attempt_count,
          last_recovery_at
      )
      VALUES (
          '${PAYMENT_ID}',
          '${SENDER_ID}',
          '${RECEIVER_ID}',
          2000.00,
          'INR',
          'PROCESSING',
          '${IDEMPOTENCY_KEY}',
          NULL,
          NOW() - INTERVAL '10 minutes',
          NOW() - INTERVAL '10 minutes',
          2,
          NOW() - INTERVAL '10 minutes'
      );
  " \
  >/dev/null

echo "Payment ID: ${PAYMENT_ID}"
echo "✅ Stale PROCESSING fixture created"

echo
echo "Verifying Ledger has no knowledge of payment..."

LEDGER_RESULT_COUNT="$(
  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d ledger_db \
    -tA \
    -c "
        SELECT COUNT(*)
        FROM ledger_processing_results
        WHERE payment_id = '${PAYMENT_ID}';
    "
)"

LEDGER_ENTRY_COUNT="$(
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

if [ "${LEDGER_RESULT_COUNT}" != "0" ]; then
  echo "Expected zero Ledger processing results"
  exit 1
fi

if [ "${LEDGER_ENTRY_COUNT}" != "0" ]; then
  echo "Expected zero Ledger entries"
  exit 1
fi

echo "✅ Ledger state is NOT_FOUND"

echo
echo "Waiting for Payment recovery job..."

CURRENT_ATTEMPTS="2"
STATUS="PROCESSING"

for attempt in $(seq 1 45); do

  STATUS="$(
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

  CURRENT_ATTEMPTS="$(
    docker exec \
      "${POSTGRES_CONTAINER}" \
      psql \
      -U "${DB_USERNAME}" \
      -d payment_db \
      -tA \
      -c "
          SELECT recovery_attempt_count
          FROM payments
          WHERE id = '${PAYMENT_ID}';
      "
  )"

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

  echo "Attempt ${attempt}: status=${STATUS} recoveryAttempt=${CURRENT_ATTEMPTS}"

  #
  # Immediately fail if the unsafe old behavior appears.
  #
  if [ "${STATUS}" = "FAILED" ]; then

    echo
    echo "❌ UNSAFE RECOVERY RESULT"
    echo "Payment was falsely declared FAILED"
    echo "Failure reason: ${FAILURE_REASON}"
    exit 1
  fi

  if [ "${STATUS}" = "COMPLETED" ]; then

    echo
    echo "❌ Unexpected COMPLETED state"
    exit 1
  fi

  if [ "${CURRENT_ATTEMPTS}" -ge "3" ]; then
    break
  fi

  sleep 2
done

if [ "${CURRENT_ATTEMPTS}" -lt "3" ]; then

  echo "Recovery job did not process the stale payment"
  exit 1
fi

echo
echo "Verifying safe exhaustion state..."

STATUS="$(
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

CURRENT_ATTEMPTS="$(
  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d payment_db \
    -tA \
    -c "
        SELECT recovery_attempt_count
        FROM payments
        WHERE id = '${PAYMENT_ID}';
    "
)"

if [ "${STATUS}" != "PROCESSING" ]; then

  echo "Expected payment to remain PROCESSING"
  echo "Actual status=${STATUS}"
  exit 1
fi

if [ -n "${FAILURE_REASON}" ]; then

  echo "Expected failure_reason to remain empty"
  echo "Actual failure_reason=${FAILURE_REASON}"
  exit 1
fi

if [ "${CURRENT_ATTEMPTS}" != "3" ]; then

  echo "Expected recovery_attempt_count=3"
  echo "Actual=${CURRENT_ATTEMPTS}"
  exit 1
fi

echo "✅ Payment remains PROCESSING"
echo "✅ No false PROCESSING_TIMEOUT failure"
echo "✅ Recovery attempt reached maximum safely"

echo
echo "Verifying no financial side effects..."

LEDGER_RESULT_COUNT="$(
  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d ledger_db \
    -tA \
    -c "
        SELECT COUNT(*)
        FROM ledger_processing_results
        WHERE payment_id = '${PAYMENT_ID}';
    "
)"

LEDGER_ENTRY_COUNT="$(
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

OUTBOX_COUNT="$(
  docker exec \
    "${POSTGRES_CONTAINER}" \
    psql \
    -U "${DB_USERNAME}" \
    -d payment_db \
    -tA \
    -c "
        SELECT COUNT(*)
        FROM outbox_events
        WHERE aggregate_id = '${PAYMENT_ID}';
    "
)"

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
        WHERE payment_id = '${PAYMENT_ID}';
    "
)"

if [ "${LEDGER_RESULT_COUNT}" != "0" ]; then

  echo "Expected zero Ledger results"
  exit 1
fi

if [ "${LEDGER_ENTRY_COUNT}" != "0" ]; then

  echo "Expected zero Ledger entries"
  exit 1
fi

if [ "${OUTBOX_COUNT}" != "0" ]; then

  echo "Expected zero outbox events for controlled fixture"
  exit 1
fi

if [ "${NOTIFICATION_COUNT}" != "0" ]; then

  echo "Expected zero notifications"
  exit 1
fi

echo "✅ No Ledger mutation"
echo "✅ No outbox publication"
echo "✅ No false failure notification"

echo
echo "======================================"
echo "RECOVERY EXHAUSTION SAFETY PASSED"
echo "======================================"
echo "Payment ID:              ${PAYMENT_ID}"
echo "Status:                  ${STATUS}"
echo "Failure reason:          <none>"
echo "Recovery attempts:       ${CURRENT_ATTEMPTS}"
echo "Ledger results:          ${LEDGER_RESULT_COUNT}"
echo "Ledger entries:          ${LEDGER_ENTRY_COUNT}"
echo "Outbox events:           ${OUTBOX_COUNT}"
echo "Notifications:           ${NOTIFICATION_COUNT}"
echo
echo "Outcome remains UNKNOWN rather than falsely FAILED: YES"