-- SP06: брони мест и записи идемпотентности. Инварианты держит база.

CREATE TABLE bookings (
    id                uuid PRIMARY KEY,
    trip_id           uuid        NOT NULL REFERENCES trips (id),
    passenger_user_id uuid        NOT NULL REFERENCES users (id),
    seats             integer     NOT NULL CHECK (seats > 0),
    -- Цена места на момент запроса: правка цены поездки позже не меняет уже запрошенную бронь.
    price_kgs         integer     NOT NULL CHECK (price_kgs > 0),
    status            text        NOT NULL CHECK (status IN
        ('REQUESTED', 'ACCEPTED', 'REJECTED', 'CANCELLED', 'COMPLETED', 'NO_SHOW', 'REVIEW_REQUIRED')),
    cancelled_by      text        CHECK (cancelled_by IN ('PASSENGER', 'DRIVER', 'TRIP_CANCELLED')),
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    decided_at        timestamptz,
    CHECK (status <> 'CANCELLED' OR cancelled_by IS NOT NULL)
);
-- Одна активная пара «заявитель и поездка»: повторный запрос, пока прежний жив, база не пропустит.
CREATE UNIQUE INDEX bookings_one_active_per_passenger_trip
    ON bookings (trip_id, passenger_user_id) WHERE status IN ('REQUESTED', 'ACCEPTED');
CREATE INDEX bookings_passenger ON bookings (passenger_user_id, created_at DESC);
CREATE INDEX bookings_trip ON bookings (trip_id, status);

-- Водитель принимает и отклоняет запросы, пока его профиль в одном из этих статусов (правило как данные).
INSERT INTO eligibility_rules (action, profile_status) VALUES ('MANAGE_BOOKINGS', 'APPROVED');

-- Идемпотентность: тот же субъект, scope и ключ дают сохранённый ответ; другой отпечаток тела это конфликт.
CREATE TABLE idempotency_records (
    id                  uuid PRIMARY KEY,
    subject_user_id     uuid        NOT NULL REFERENCES users (id),
    scope               text        NOT NULL,
    idem_key            text        NOT NULL CHECK (char_length(idem_key) BETWEEN 8 AND 128),
    request_fingerprint text        NOT NULL,
    response_status     integer     NOT NULL,
    response_body       text        NOT NULL,
    created_at          timestamptz NOT NULL,
    UNIQUE (subject_user_id, scope, idem_key)
);
