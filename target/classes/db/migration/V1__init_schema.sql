-- V1__init_schema.sql
-- Clinzo — Doctor Slot Scheduling System

-- ── Doctors ────────────────────────────────────────────────
CREATE TABLE doctors (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255)  NOT NULL,
    timezone    VARCHAR(64)   NOT NULL DEFAULT 'UTC',
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- ── Availability Windows ───────────────────────────────────
CREATE TABLE availability_windows (
    id                      BIGSERIAL PRIMARY KEY,
    doctor_id               BIGINT        NOT NULL REFERENCES doctors(id),
    day_of_week             SMALLINT,           -- 1=MON … 7=SUN (ISO)
    specific_date           DATE,
    start_time_utc          TIMESTAMPTZ   NOT NULL,
    end_time_utc            TIMESTAMPTZ   NOT NULL,
    slot_duration_minutes   INT           NOT NULL,
    buffer_minutes          INT           NOT NULL DEFAULT 0,
    appointment_type        VARCHAR(64)   NOT NULL DEFAULT 'GENERAL',
    is_recurring            BOOLEAN       NOT NULL DEFAULT TRUE,
    active                  BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    -- Fix #3: exactly one of (day_of_week, specific_date) must be set
    CONSTRAINT chk_recurring_or_specific CHECK (
        (is_recurring = TRUE  AND day_of_week IS NOT NULL AND specific_date IS NULL) OR
        (is_recurring = FALSE AND specific_date IS NOT NULL AND day_of_week IS NULL)
    ),
    CONSTRAINT chk_day_of_week_range CHECK (
        day_of_week IS NULL OR (day_of_week >= 1 AND day_of_week <= 7)
    ),
    CONSTRAINT chk_time_order CHECK (end_time_utc > start_time_utc),
    CONSTRAINT chk_slot_duration_positive CHECK (slot_duration_minutes > 0),
    CONSTRAINT chk_buffer_non_negative CHECK (buffer_minutes >= 0)
);

CREATE INDEX idx_avail_doctor ON availability_windows(doctor_id);

-- ── Slots (materialized, not computed on read) ─────────────
CREATE TABLE slots (
    id                      BIGSERIAL PRIMARY KEY,
    doctor_id               BIGINT        NOT NULL REFERENCES doctors(id),
    availability_window_id  BIGINT        NOT NULL REFERENCES availability_windows(id),
    start_time_utc          TIMESTAMPTZ   NOT NULL,
    end_time_utc            TIMESTAMPTZ   NOT NULL,
    status                  VARCHAR(20)   NOT NULL DEFAULT 'AVAILABLE',
    version                 INT           NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    -- Idempotent generation: no duplicate slots per doctor per start time
    CONSTRAINT uq_slot_doctor_start UNIQUE (doctor_id, start_time_utc),
    CONSTRAINT chk_slot_status CHECK (
        status IN ('AVAILABLE', 'HELD', 'BOOKED', 'CANCELLED', 'EXPIRED_HOLD')
    )
);

CREATE INDEX idx_slot_doctor_status ON slots(doctor_id, status);
CREATE INDEX idx_slot_avail_window  ON slots(availability_window_id);

-- ── Bookings ───────────────────────────────────────────────
CREATE TABLE bookings (
    id              BIGSERIAL PRIMARY KEY,
    slot_id         BIGINT        NOT NULL REFERENCES slots(id),
    patient_id      VARCHAR(255)  NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'HELD',
    hold_token      VARCHAR(64),
    hold_expires_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_booking_status CHECK (
        status IN ('HELD', 'CONFIRMED', 'CANCELLED', 'RESCHEDULED')
    )
);

CREATE INDEX idx_booking_slot   ON bookings(slot_id);
CREATE INDEX idx_booking_patient ON bookings(patient_id);

-- ── Audit Log ──────────────────────────────────────────────
CREATE TABLE audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    entity_type VARCHAR(64)   NOT NULL,
    entity_id   BIGINT        NOT NULL,
    action      VARCHAR(64)   NOT NULL,
    actor_id    VARCHAR(255),
    old_state   TEXT,
    new_state   TEXT,
    timestamp   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_entity ON audit_logs(entity_type, entity_id);
