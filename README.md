Next is **Story 15 — README creation**.

Your uploaded repo currently has **no root `README.md`**, so create:

```text
README.md
```

Use this as the first complete version:

````markdown
# Distributed Fintech Payment System

A distributed fintech payment system demonstrating Spring Boot microservices, Kafka-based Saga workflows, idempotent payments, transactional outbox, double-entry ledgering, Redis rate limiting/caching, recovery/reconciliation, security, and observability.

This project is designed as a backend-focused portfolio system that demonstrates how financial workflows can be made safe across multiple services without relying on distributed database transactions.

---

# Architecture

```text
                         CLIENT
                           │
                           ▼
                    ┌──────────────┐
                    │ API GATEWAY  │
                    │    :8081     │
                    └──────┬───────┘
                           │
          ┌────────────────┼─────────────────┐
          │                │                 │
          ▼                ▼                 ▼
   ┌────────────┐    ┌────────────┐   ┌────────────┐
   │  IDENTITY  │    │  PAYMENT   │   │   LEDGER   │
   │   :8082    │    │   :8083    │   │   :8084    │
   └─────┬──────┘    └─────┬──────┘   └─────┬──────┘
         │                  │                │
         ▼                  ▼                ▼
   identity_db         payment_db        ledger_db
                            │
                     Transactional
                         Outbox
                            │
                            ▼
                         Kafka
                            │
                            ▼
                         Ledger
                            │
                    Atomic Debit/Credit
                            │
                       Double Entry
                            │
                            ▼
                   ledger.completed /
                     ledger.failed
                            │
                            ▼
                         Payment
                            │
                   COMPLETED / FAILED
                            │
                            ▼
                  payments.completed /
                    payments.failed
                            │
                            ▼
                 ┌──────────────────┐
                 │   NOTIFICATION   │
                 │      :8085       │
                 └────────┬─────────┘
                          │
                          ▼
                  notification_db
````

---

# Services

| Service              | Responsibility                                                                         |   Port | Database          |
| -------------------- | -------------------------------------------------------------------------------------- | -----: | ----------------- |
| API Gateway          | Routing, JWT validation, trusted identity propagation, rate limiting, circuit breaking | `8081` | —                 |
| Identity Service     | Registration, login, users, roles, JWT creation                                        | `8082` | `identity_db`     |
| Payment Service      | Payment lifecycle, idempotency, Saga coordination, transactional outbox, recovery      | `8083` | `payment_db`      |
| Ledger Service       | Accounts, balances, atomic transfers, double-entry ledger, reconciliation, cache       | `8084` | `ledger_db`       |
| Notification Service | Persistent mock notifications for final payment results                                | `8085` | `notification_db` |

---

# Technology Stack

## Backend

* Java 21
* Spring Boot
* Spring MVC
* Spring Security
* Spring Data JPA
* Hibernate
* Spring Kafka
* Spring Cache
* Spring Cloud Gateway
* Spring Scheduling
* Spring Boot Actuator
* Micrometer

## Data and Messaging

* PostgreSQL
* Redis
* Apache Kafka
* Zookeeper
* Flyway

## Testing

* JUnit 5
* Mockito
* Spring Boot Test
* PostgreSQL integration tests
* End-to-end shell verification

## Observability

* Prometheus
* Grafana
* Zipkin
* Spring Boot Actuator
* Micrometer metrics

---

# Core Payment Flow

```text
Authenticated Sender
        │
        ▼
POST /api/payments/pay
        │
        ▼
Payment created
status = PROCESSING
        │
        ▼
Payment + Outbox Event
same PostgreSQL transaction
        │
        ▼
payments.created
        │
        ▼
Ledger Service
        │
        ├── validate accounts
        ├── validate currency
        ├── validate available balance
        ├── lock accounts
        ├── debit sender
        ├── credit receiver
        └── create DEBIT + CREDIT entries
        │
        ▼
ledger.completed
        │
        ▼
Payment Service
        │
        ▼
status = COMPLETED
        │
        ▼
payments.completed
        │
        ▼
Notification Service
        │
        ▼
PAYMENT_COMPLETED notification
```

For a business failure:

```text
Ledger validation fails
        │
        ▼
ledger.failed
        │
        ▼
Payment = FAILED
        │
        ▼
payments.failed
        │
        ▼
PAYMENT_FAILED notification
```

Examples of supported failure reasons include:

```text
INSUFFICIENT_BALANCE
ACCOUNT_INACTIVE
CURRENCY_MISMATCH
```

---

# Reliability Patterns

## Idempotency

Each logical payment request contains an `idempotencyKey`.

```text
same key + same payload
        │
        ▼
same payment
        │
        ▼
no duplicate money movement
```

If the same key is reused with a different payment payload:

```text
409 Conflict
IDEMPOTENCY_KEY_REUSED
```

### Idempotency key ownership

The **client is responsible for generating the idempotency key**.

For example:

```text
new intended payment
→ generate a new unique key

retry of the same logical payment
→ reuse the same key
```

Example:

```text
manual-payment-001
```

The current application does not automatically expire payment idempotency keys.

---

## Transactional Outbox

Payment creation and the corresponding Kafka event are not written independently.

Conceptually:

```text
BEGIN DATABASE TRANSACTION

INSERT payment
INSERT outbox_event

COMMIT
```

The Outbox publisher later sends:

```text
payments.created
```

to Kafka.

If Kafka is unavailable, the Payment and Outbox rows remain committed and publication is retried after Kafka recovers.

---

## Saga

The distributed transaction is coordinated through events:

```text
Payment
   │
   ▼
payments.created
   │
   ▼
Ledger
   │
   ├── ledger.completed
   │
   └── ledger.failed
   │
   ▼
Payment final state
```

No distributed database transaction is used between Payment and Ledger.

---

## Effectively-once financial behavior

Kafka delivery is at-least-once.

Financial processing is protected through:

* payment idempotency
* Ledger processing result tracking
* database constraints
* account row locking
* duplicate event handling
* terminal-state protection

Therefore duplicate Kafka delivery must not result in duplicate money movement.

---

# Double-Entry Ledger

Every successful transfer creates exactly:

```text
1 DEBIT
1 CREDIT
```

Example:

```text
Sender balance before   = 10000
Receiver balance before = 5000
Transfer                = 2000

Sender balance after    = 8000
Receiver balance after  = 7000

Ledger:
DEBIT  = 2000
CREDIT = 2000
```

A failed financial validation creates no DEBIT/CREDIT pair.

---

# Concurrency Safety

Ledger balance changes use PostgreSQL locking.

The system prevents concurrent transfers from spending the same balance twice.

Example:

```text
Sender balance = 1000

Payment A = 800
Payment B = 800
```

Both payments must never successfully spend `1600`.

The sender balance must never become negative.

Redis is not used to make financial correctness decisions.

---

# Redis Responsibilities

Redis is deliberately limited to non-authoritative responsibilities.

## API Gateway

Redis is used for:

```text
rate limiting
```

If Gateway rate-limit Redis becomes unavailable, protected payment traffic fails closed rather than bypassing API protection.

## Ledger Service

Redis is used for:

```text
safe read caching
```

Financial balance validation continues to use PostgreSQL.

Redis cache failures fall back to PostgreSQL reads and do not alter committed transfers.

---

# Recovery and Reconciliation

## Stuck Payment Recovery

Payment Service periodically checks payments that remain in:

```text
PROCESSING
```

for longer than the configured timeout.

Recovery determines whether Ledger already processed the payment before retrying.

It does not blindly mark unknown financial outcomes as failed.

If the recovery attempt limit is reached while the outcome remains unknown, the payment remains:

```text
PROCESSING
```

rather than being incorrectly declared failed.

## Reconciliation

Ledger reconciliation checks accounting invariants and detects inconsistencies such as mismatched balance and ledger state.

---

# Security

The system uses:

* Spring Security
* JWT authentication
* role-based authorization
* Gateway trusted identity propagation

Normal clients authenticate using:

```http
Authorization: Bearer <JWT>
```

The Gateway derives authenticated identity from the JWT.

Client-supplied headers such as:

```text
X-USER-ID
X-USER-ROLES
```

are not trusted as authority.

A user can create a payment only when:

```text
authenticatedUserId == senderId
```

Payment lookup is allowed only for users participating in the payment.

---

# Kafka Topics

The current local architecture uses:

```text
payments.created
payments.created.DLT

ledger.completed
ledger.failed

payments.completed
payments.failed
```

Logical successful path:

```text
payments.created
      ↓
ledger.completed
      ↓
payments.completed
```

Logical failure path:

```text
payments.created
      ↓
ledger.failed
      ↓
payments.failed
```

---

# Notification Service

Notification Service is Kafka-driven.

It consumes final payment events:

```text
payments.completed
payments.failed
```

and persists mock notifications in PostgreSQL.

Supported notification types:

```text
PAYMENT_COMPLETED
PAYMENT_FAILED
```

Current mock lifecycle:

```text
PENDING
  ↓
SENT
```

The service currently does **not expose a public notification-history HTTP API**.

Notification state can be inspected through `notification_db` during local development.

---

# Prerequisites

Install:

```text
Java 21
Docker
Docker Compose
Git
```

Useful local tools:

```text
curl
jq
psql (optional)
```

---

# Environment Configuration

Create:

```bash
cp .env.example .env
```

Update local placeholder values where needed.

For macOS/Linux, load the environment into the current terminal:

```bash
set -a
source .env
set +a
```

Do not commit `.env`.

Only `.env.example` should be committed.

---

# Local Infrastructure

Start PostgreSQL, Redis, Kafka, Zookeeper, Prometheus, Grafana and Zipkin:

```bash
chmod +x scripts/local/start-infra.sh
./scripts/local/start-infra.sh
```

Stop infrastructure:

```bash
chmod +x scripts/local/stop-infra.sh
./scripts/local/stop-infra.sh
```

Core infrastructure is defined in:

```text
docker/docker-compose.yml
```

Observability infrastructure is defined in:

```text
infrastructure/docker-compose.yml
```

---

# Local Databases

The local PostgreSQL container contains:

```text
identity_db
payment_db
ledger_db
notification_db
```

Database creation is handled by:

```text
docker/init-db/init.sql
```

Application schemas are created and evolved through Flyway migrations.

A fresh local environment should require no manual schema creation.

---

# Starting the Services

For macOS/Linux, make the Gradle wrappers executable if necessary:

```bash
chmod +x api-gateway/gradlew
chmod +x identity-service/gradlew
chmod +x payment-service/gradlew
chmod +x ledger-service/gradlew
chmod +x notification-service/gradlew
```

Recommended local startup order:

```text
1. Infrastructure
2. Identity Service
3. Ledger Service
4. Payment Service
5. Notification Service
6. API Gateway
```

After infrastructure is ready, the application services do not depend on a strict boot order, but this order makes local startup easier to understand.

## Identity Service

```bash
cd identity-service
./gradlew bootRun
```

## Ledger Service

```bash
cd ledger-service
./gradlew bootRun
```

## Payment Service

```bash
cd payment-service
./gradlew bootRun
```

## Notification Service

```bash
cd notification-service
./gradlew bootRun
```

## API Gateway

```bash
cd api-gateway
./gradlew bootRun
```

---

# Main APIs

Normal client traffic should go through:

```text
http://localhost:8081
```

## Authentication

### Register

```http
POST /api/auth/register
```

Example:

```json
{
  "email": "sender@example.com",
  "password": "Password123!"
}
```

### Login

```http
POST /api/auth/login
```

Example:

```json
{
  "email": "sender@example.com",
  "password": "Password123!"
}
```

The current login response returns the JWT in its response message field.

---

# Accounts

## Create Account

```http
POST /api/accounts
Authorization: Bearer <JWT>
Content-Type: application/json
```

```json
{
  "currency": "INR"
}
```

Ownership is derived from the authenticated JWT.

A normal client should not provide its own account owner ID.

## Get My Account

```http
GET /api/accounts/me
Authorization: Bearer <JWT>
```

---

# Payments

## Create Payment

```http
POST /api/payments/pay
Authorization: Bearer <JWT>
Content-Type: application/json
```

Example:

```json
{
  "senderId": "<sender-user-id>",
  "receiverId": "<receiver-user-id>",
  "amount": 2000.00,
  "currency": "INR",
  "idempotencyKey": "demo-payment-001"
}
```

Typical initial state:

```text
PROCESSING
```

The payment asynchronously transitions to:

```text
COMPLETED
```

or:

```text
FAILED
```

## Get Payment

```http
GET /api/payments/{paymentId}
Authorization: Bearer <JWT>
```

Both sender and receiver can retrieve a payment they participate in.

## My Payments

```http
GET /api/payments/mine?page=0&size=10
Authorization: Bearer <JWT>
```

Returns payments where the authenticated user is the sender or receiver.

---

# Ledger

## My Ledger Entries

```http
GET /api/ledger/my-entries
Authorization: Bearer <JWT>
```

Normal users see their own transaction history.

## Administrative Ledger Entries

```http
GET /api/ledger/entries
```

This endpoint requires the appropriate administrative role.

---

# Local Demo Flow

A simple demonstration is:

```text
Register sender
        ↓
Register receiver
        ↓
Login both users
        ↓
Create sender account
        ↓
Create receiver account
        ↓
Prepare controlled demo balances
        ↓
Create payment
        ↓
Check payment status
        ↓
Check balances
        ↓
Check ledger history
        ↓
Verify notification
```

---

# Demo Funding

The project intentionally does not expose an unrestricted public "add money" endpoint solely for testing.

For local demonstrations, initial balances can be prepared using controlled development SQL.

Example:

```sql
UPDATE accounts
SET balance = 10000.00,
    opening_balance = 10000.00,
    updated_at = NOW()
WHERE id = '<sender-account-id>';

UPDATE accounts
SET balance = 5000.00,
    opening_balance = 5000.00,
    updated_at = NOW()
WHERE id = '<receiver-account-id>';
```

This is a local demo/test fixture only and is not part of the normal payment API.

---

# Testing

Each service has its own Gradle test suite.

Example:

```bash
cd payment-service
./gradlew clean test
```

The Ledger integration suite should be run without Gradle parallel execution when sharing the same local PostgreSQL instance:

```bash
cd ledger-service
./gradlew clean test --no-parallel
```

Full Story 14 regression:

```bash
./scripts/e2e/run-story14-all.sh
```

The regression suite covers major scenarios such as:

* successful payment
* insufficient balance
* inactive account
* currency mismatch
* idempotency
* duplicate HTTP requests
* duplicate Kafka events
* concurrent spending
* double-spend prevention
* Kafka outage
* Redis outage
* stuck-payment recovery
* recovery exhaustion safety
* reconciliation
* JWT/security regression
* notification persistence
* observability
* configuration audit

---

# Observability

## Actuator

Service Actuator endpoints are available under:

```text
/actuator
```

Examples:

```text
/actuator/health
/actuator/metrics
/actuator/prometheus
```

Gateway Actuator endpoints require authentication.

## Prometheus

Local Prometheus configuration is under:

```text
infrastructure/prometheus/
```

## Grafana

Grafana is started by the observability Docker Compose stack.

## Zipkin

Default local Zipkin endpoint:

```text
http://localhost:9411
```

Tracing configuration is controlled through:

```text
TRACING_SAMPLING_PROBABILITY
ZIPKIN_ENDPOINT
```

---

# Important Financial Invariants

The system is designed around several important guarantees.

## No negative balance

```text
balance >= 0
```

## Double-entry equality

For a successful payment:

```text
total DEBIT == total CREDIT
```

## Failed financial validation

For failures such as:

```text
INSUFFICIENT_BALANCE
ACCOUNT_INACTIVE
CURRENCY_MISMATCH
```

expected financial effect:

```text
0 balance mutation
0 DEBIT
0 CREDIT
```

## Duplicate processing

Repeated delivery of the same logical financial operation must not result in duplicate balance changes.

---

# Useful Local Database Verification

## Accounts

```sql
SELECT
    id,
    owner_id,
    balance,
    opening_balance,
    currency,
    status
FROM accounts;
```

## Payments

```sql
SELECT
    id,
    sender_id,
    receiver_id,
    amount,
    currency,
    status,
    idempotency_key,
    failure_reason
FROM payments;
```

## Ledger entries

```sql
SELECT
    payment_id,
    account_id,
    entry_type,
    amount
FROM ledger_entries;
```

## Notifications

```sql
SELECT
    payment_id,
    user_id,
    type,
    status,
    channel,
    message
FROM notifications;
```

---

# Repository Hygiene

Do not commit:

```text
.env
build/
.gradle/
.idea/
.DS_Store
logs
```

Do not commit:

* JWT secrets
* database passwords
* tokens
* private credentials

Use `.env.example` only for placeholder configuration.

---

# Current Scope

This repository currently focuses on the Spring Boot/backend phase.

The following are intentionally outside the current backend story:

* AWS deployment
* Kubernetes
* CI/CD
* Terraform
* Helm
* cloud secret managers
* managed Kafka
* managed Redis
* production TLS
* service mesh
* real email/SMS providers

These belong to the next Cloud/DevOps phase.

---

# Backend Status

The backend phase demonstrates:

```text
Microservices
Spring Security
JWT + RBAC
Kafka
Saga
Transactional Outbox
Idempotency
State-machine payment lifecycle
PostgreSQL transactions
Concurrency-safe balance handling
Double-entry ledger
Redis rate limiting/caching
Retry + DLT
Recovery
Reconciliation
Flyway
Persistent notifications
Actuator
Prometheus
Grafana
Zipkin
JUnit / Mockito / integration testing
```

