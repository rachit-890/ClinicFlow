# Clinzo — High-Performance Doctor Slot Scheduling & Concurrency System

Clinzo is a enterprise-grade doctor appointment scheduling backend built with **Java 21, Spring Boot 3, PostgreSQL, Redis, and Testcontainers**. It features materialized time slots, UTC-only date/time persistence, fast-fail Redis hold reservations, and robust optimistic-concurrency control for appointment bookings.

---

## Key Features & Design Decisions

- **Materialized Time Slots**: Slot rows are generated ahead of time in UTC. Materialization ensures there is a concrete database row for concurrent requests to serialize against.
- **UTC Timezone Boundary**: All timestamps in the database and Redis are strictly stored in UTC (`Instant`). Timezone conversions occur exclusively at the API boundary based on the doctor's or patient's requested IANA timezone.
- **Dual Booking Workflows**:
  - **Two-Step Checkout (Hold -> Confirm)**: Fast 120-second reservation hold backed by Redis `SET NX EX 120` returning an opaque UUID token, allowing patients time to complete checkout.
  - **Direct Single-Step Booking**: Direct booking from `AVAILABLE` to `BOOKED` without requiring a prior hold step.

---

## Concurrency Strategy

Clinzo employs a multi-tiered concurrency architecture designed to guarantee **exactly-one-winner semantics** under extreme contention without relying on slow database row locks (`SELECT ... FOR UPDATE`).

### 1. Hold Race (Fast-Fail UX Layer)
- Executed via Redis atomic operation `SET hold:{slotId} {holdToken} NX EX 120`.
- If two or more users attempt to hold the same slot simultaneously, exactly one succeeds in setting the key in Redis.
- If Redis succeeds, an optimistic database update transitions the slot from `AVAILABLE` to `HELD`. If the database update fails or returns 0 rows (e.g. concurrent modification), the Redis key is immediately deleted to prevent orphaned keys.

### 2. Confirm Race (Single Atomic Control-Flow Update Query)
- Confirmation control flow is governed by a single atomic `UPDATE` query executing optimistic concurrency locking:
  ```sql
  UPDATE slots
  SET status = 'BOOKED', version = version + 1, updated_at = :now
  WHERE id = :slotId AND version = :expectedVersion AND status IN (:allowedStatuses)
  ```
- **Path B (Direct Booking, No Token)**: Executed with `allowedStatuses = ['AVAILABLE']`. Zero prior status checks gate execution. The decision to succeed or fail comes **solely** from the row count affected (`updatedRows == 1` vs `0`). If `updatedRows == 0`, a post-failure diagnostic `SELECT` checks current status strictly to format an informative 409 Conflict message.
- **Path A (Token-Verified Confirmation)**: Validates the hold token against Redis. If valid, executes the atomic `UPDATE` query with `allowedStatuses = ['HELD', 'AVAILABLE']`. The DB update's `WHERE` clause serves as the sole source of truth for database state modification.

### 3. Database Version Source of Truth
- The `version` passed into the optimistic update query is read fresh from PostgreSQL inside the active `@Transactional` method (`slotRepository.findById(slotId)`). It is **never** accepted from external client DTOs or prior hold responses, eliminating stale-version race vectors.

### 4. TTL / Sweep Clock Skew & The "Dead Zone"
> **Note on TTL/Sweep Clock Skew & The "Dead Zone"**: The Redis hold TTL (120s) and the PostgreSQL background hold-expiry sweeper (`@Scheduled` running every 30s) operate on independent clocks. As a result, there is a narrow, accepted eventual-consistency window between a hold key expiring in Redis and the Postgres slot row status being flipped back from `HELD` to `AVAILABLE`. For up to ~30 seconds after a hold's Redis key expires (120s TTL) and before the next DB sweep runs (30s interval), the slot enters a transient "dead zone" where it is unbookable via either path: Path A fails because Redis no longer contains a matching token, and Path B fails because Postgres still shows `status = 'HELD'`. This is a deliberate tradeoff favoring strict correctness (never double-booking under partial system latency) over instantaneous availability.

---

## Concurrency Test Suite Proofs

The committed test suite (`BookingServiceConcurrencyTest`) runs **50 concurrent threads** against real Testcontainers PostgreSQL and Redis instances:

1. `highContention_50Threads_directBooking_exactlyOneWinner`: Proves Path B direct booking concurrency. 50 threads call `confirmBooking` without tokens on an `AVAILABLE` slot. Asserts exactly 1 winner (200 OK), 49 conflicts (409), slot status `BOOKED` (version 1), and 1 `CONFIRMED` booking row.
2. `highContention_50Threads_concurrentHold_exactlyOneWinner`: Proves Hold race concurrency. 50 threads call `holdSlot` simultaneously. Asserts exactly 1 winner receives a valid token, 49 conflicts (409), slot status `HELD` (version 1), 1 `HELD` booking row, and 1 Redis key present.
3. `highContention_50Threads_holdThenConfirm_patientFlow`: Proves the full two-step patient flow. 50 threads race to `holdSlot` (1 winner gets token, 49 fail at hold step). The winner then confirms with its token (slot ends `BOOKED` at version 2, Redis key evicted).
4. `highContention_50Threads_mixedPath_25Hold_25DirectConfirm_exactlyOneWinner`: Proves mixed-path safety. 25 `holdSlot` threads vs 25 direct `confirmBooking` threads race simultaneously. Asserts exactly 1 winner across the entire 50-thread pool, 49 conflicts, and clean state co-existence.
5. `confirmBooking_onHeldSlot_withoutToken_rejected`: Proves hold protection against direct booking. Patient A holds a slot; Patient B attempts direct confirm without a token -> rejected with 409 Conflict, slot remains `HELD`, and Patient A's hold remains undisturbed.

---

## Running Tests & Setup

### Prerequisites
- Java 21
- Docker (for Testcontainers)

### Run Tests
```bash
./mvnw test
```

### Local Stack (Docker Compose)
```bash
docker-compose up -d
./mvnw spring-boot:run
```
