-- SP07-04: заявки на посылки. Размеры и категории ограничены базой; допустимые категории это данные.

-- Категории посылок как данные: исход заключения юриста (LEG-07/08) меняет таблицу, а не код.
-- ВНИМАНИЕ: два кода ниже это рабочая заготовка, а не решение юриста. Их надо заменить подписанным перечнем.
CREATE TABLE parcel_categories (
    code   text PRIMARY KEY CHECK (code ~ '^[A-Z0-9_]{2,40}$'),
    active boolean NOT NULL DEFAULT true
);
INSERT INTO parcel_categories (code) VALUES ('DOCUMENTS'), ('SMALL_GOODS');

CREATE TABLE parcel_requests (
    id                 uuid PRIMARY KEY,
    sender_user_id     uuid        NOT NULL REFERENCES users (id),
    trip_id            uuid        NOT NULL REFERENCES trips (id),
    -- Назначенный водитель: владелец поездки на момент создания (поездка владельца не меняет).
    driver_user_id     uuid        NOT NULL REFERENCES users (id),
    recipient_phone    text        NOT NULL CHECK (recipient_phone ~ '^\+996[0-9]{9}$'),
    size               text        NOT NULL CHECK (size IN ('S', 'M', 'L')),
    category           text        NOT NULL REFERENCES parcel_categories (code),
    status             text        NOT NULL CHECK (status IN
        ('REQUESTED', 'ACCEPTED', 'PICKUP_CONFIRMED', 'IN_TRANSIT', 'DELIVERY_PENDING', 'DELIVERED',
         'REJECTED', 'CANCELLED', 'DELIVERY_FAILED', 'VERIFICATION_LOCKED', 'DISPUTED')),
    -- Версия набора обязательств, действовавшего при создании (для разбора и SP09-01).
    obligation_version text        NOT NULL,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    CHECK (sender_user_id <> driver_user_id)
);
CREATE INDEX parcel_requests_sender ON parcel_requests (sender_user_id, created_at DESC);
CREATE INDEX parcel_requests_trip ON parcel_requests (trip_id, status);
