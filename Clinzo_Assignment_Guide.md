# Clinzo Backend Assessment — Build Guide, Docs & Video Script

Stack assumed: **Java 21, Spring Boot, PostgreSQL, Redis, Docker** (your usual stack — swap PostgreSQL for MongoDB isn't advisable here; this is a relational-constraints problem, so Postgres is the right call and worth stating explicitly in your README).

---

## 1. Detailed Build Prompt (use this as your own spec, or feed it to Claude Code phase-by-phase)

### 1.1 Domain model

```
Doctor
  id, name, timezone (IANA string e.g. "Asia/Kolkata")

AvailabilityWindow
  id, doctor_id, day_of_week (or specific_date), start_time_utc, end_time_utc,
  slot_duration_minutes, buffer_minutes, appointment_type, is_recurring, active (bool)

Slot   -- materialized, not computed on the fly
  id, doctor_id, availability_window_id, start_time_utc, end_time_utc,
  status (AVAILABLE | HELD | BOOKED | CANCELLED | EXPIRED_HOLD),
  version (int, for optimistic locking), created_at, updated_at

Booking
  id, slot_id, patient_id, status (HELD | CONFIRMED | CANCELLED | RESCHEDULED),
  hold_expires_at (nullable), created_at, updated_at

AuditLog
  id, entity_type, entity_id, action, actor_id, old_state, new_state, timestamp
```

**Why materialized slots, not computed-on-read:** you need a row to hold a unique constraint / lock against. Computing slots in memory means two concurrent booking requests have nothing shared to serialize on. State this reasoning explicitly in your README — it's the first thing an interviewer will probe.

### 1.2 Slot generation

- A scheduled job (or on-demand generator, run when a window is created) splits `[start_time, end_time)` into chunks of `slot_duration_minutes`, inserting a `buffer_minutes` gap between consecutive slots.
- Generate in **UTC only**. Never store local time. Convert to the doctor's or patient's timezone only at the API response layer.
- Idempotent generation: use a unique constraint on `(doctor_id, start_time_utc)` so re-running the generator doesn't duplicate slots.

### 1.3 Concurrency-safe booking — the core of the assignment

Two people click "book" on the same slot within milliseconds. Pick **one** primary mechanism and defend it; don't half-implement three.

**Recommended: Optimistic locking + DB unique constraint, backed by a Redis hold for UX.**

1. **Reservation hold (Redis):** `SET hold:{slot_id} {patient_id} NX EX 120` — atomic "set if not exists" with a 2-minute TTL. This is your first line of defense and gives instant feedback without touching Postgres.
2. **Confirm booking (Postgres transaction):**
   ```sql
   UPDATE slots
   SET status = 'BOOKED', version = version + 1
   WHERE id = ? AND status IN ('AVAILABLE', 'HELD') AND version = ?
   ```
   Zero rows updated ⇒ someone else won ⇒ return 409 Conflict. This is optimistic concurrency control — no long-held DB locks, scales under load.
3. Why not `SELECT ... FOR UPDATE` as the primary mechanism: it works, but holds a row lock for the transaction's duration and doesn't scale as well under high contention on the same slot. Mention this as the alternative you considered and rejected — that tradeoff discussion is exactly what "Communication" is graded on.

**Proof of correctness:** write a test that fires N concurrent threads (e.g. `ExecutorService` with 50 threads) all trying to book the same slot, and assert exactly 1 succeeds and 49 get a conflict response. This single test is probably the highest-value thing you can show in the video.

### 1.4 Reservation holds (expiry)

- Redis TTL handles the "hold" state automatically expiring.
- Also add a Postgres side: a scheduled job (`@Scheduled`) that flips any `Slot` still `HELD` past `hold_expires_at` back to `AVAILABLE` — this covers correctness even if Redis is unavailable (partial failure / "correctness under partial failure" requirement).

### 1.5 Cancellation & rescheduling

- **Cancel:** `Booking.status = CANCELLED`, `Slot.status = AVAILABLE` in one transaction. Immediately queryable as free.
- **Reschedule:** treat as cancel-old + book-new inside a single transaction, not a mutation of the existing slot's time — this preserves the audit trail and avoids leaving the old slot in a broken state if the new booking fails.

### 1.6 Retroactive availability changes

- If a doctor shrinks/removes a window that has confirmed bookings inside it: **never silently cancel a confirmed booking.** Mark the `AvailabilityWindow` inactive, but let existing `BOOKED` slots stand; only prune `AVAILABLE` slots that fall outside the new window. Surface a warning to the doctor listing affected bookings ("3 booked appointments fall outside your new hours — cancel manually or keep them").

### 1.7 API surface

```
GET  /doctors/{id}/slots?date=2026-07-28&type=follow-up   → list AVAILABLE slots (converted to query tz)
POST /slots/{id}/hold        {patientId}                  → creates Redis hold, returns hold_expires_at
POST /bookings                {slotId, patientId, holdToken} → confirms booking (optimistic lock check)
DELETE /bookings/{id}                                       → cancel
PATCH /bookings/{id}/reschedule {newSlotId}                 → reschedule
PUT  /doctors/{id}/availability/{windowId}                   → update window (triggers retroactive-change logic)
```

### 1.8 Auditability

Every state transition (hold created, booking confirmed, cancelled, rescheduled, window changed) writes an `AuditLog` row inside the same transaction. This is cheap to add and evaluators specifically call it out.

### Suggested build order (phase-by-phase, test after each)
1. Entities + Flyway/Liquibase migrations
2. Slot generation from an `AvailabilityWindow` + unit tests for boundary cases (exact division, remainder minutes, buffer eating into last slot)
3. Booking endpoint with optimistic locking + the concurrency test from 1.3
4. Redis hold layer
5. Cancellation + reschedule
6. Retroactive availability change handling
7. Timezone conversion at the API boundary
8. Audit log

---

## 2. README — required sections (this is what they asked for verbatim: approach, assumptions, setup, design decisions, trade-offs)

```markdown
# Doctor Slot Scheduling System

## Approach
[2-3 paragraphs: materialized slots, optimistic locking + Redis hold, why]

## Assumptions
- Consultation duration is fixed per availability window (bonus: variable per appointment type — note if implemented)
- Doctor's timezone is stored; patients query in their own tz via query param
- A "hold" expires after 2 minutes if not confirmed
- [any others you made]

## Data Model
[ER diagram — image or mermaid block]

## Concurrency Strategy
- Why optimistic locking over pessimistic (SELECT FOR UPDATE) over distributed lock
- Why Redis hold as a fast-fail UX layer, not the source of truth
- Link to the concurrency test and how to run it

## API Reference
[table of endpoints, request/response examples]

## Setup Instructions
```bash
docker-compose up -d        # postgres + redis
./mvnw spring-boot:run
```

## Trade-offs & What I'd Do Differently at Scale
- Sharding by doctor_id if a single doctor's slot table gets hot
- Moving hold-expiry sweep to a distributed scheduler (e.g. Kafka-triggered) instead of @Scheduled if running multiple instances
- [anything else]

## Bonus Work Completed
- [ ] Variable-length appointment types
- [ ] Waitlist
- [ ] Multi-doctor booking discussion
```

Keep this to ~1-2 pages of prose plus diagrams/tables — dense and scannable, not padded.

---

## 3. Video Walkthrough (≤15 min) — script outline

| Time | Segment |
|---|---|
| 0:00–1:30 | Problem restated in your own words: the core challenge is turning wide availability windows into discrete slots and guaranteeing exactly-one-winner under concurrent booking. |
| 1:30–4:00 | Data model walkthrough — screen-share the ER diagram, explain why Slot is a materialized row and why Booking is separate from Slot (audit trail, reschedule history) |
| 4:00–8:00 | **Live demo of the concurrency test** — run the 50-thread test, show the assertion (1 success, 49 conflicts), briefly show the SQL/optimistic-lock code that makes it work |
| 8:00–10:30 | Cancellation, reschedule, and retroactive-availability-change demo (hit the API with curl/Postman) |
| 10:30–12:30 | Timezone handling — show a slot stored in UTC, requested by a patient in a different tz, converted correctly |
| 12:30–14:30 | Trade-offs: why optimistic over pessimistic locking, what you'd change for scale, what's incomplete/bonus not done |
| 14:30–15:00 | Close |

Recording tips:
- Use OBS Studio (free, works fine on Fedora) — screen + mic, no need for face-cam.
- Script the concurrency-test demo precisely; it's the single highest-signal moment in the video — don't wing it.
- Keep terminal font size large (14pt+) for readability.
- Do one dry run before the real recording so you're not narrating live debugging.

---

## 4. Submission checklist

- [ ] GitHub repo public or shared with reviewer, clean commit history (not one giant commit)
- [ ] README with all 5 required sections above
- [ ] Concurrency test committed and passing in CI or shown passing locally
- [ ] Docker Compose for one-command setup
- [ ] Video ≤15 min, uploaded to the same Drive folder as code + docs
- [ ] Reply to the assignment email with **only the Drive link**, nothing else, per their instruction

---

### One thing worth deciding now
Given your 48-hour window, do you want to scope this to **core requirements + the concurrency proof** (skip the bonus waitlist/multi-doctor sections), or attempt bonuses too? That decision changes how you allocate the ~40 hours you realistically have between code, docs, and the video.
