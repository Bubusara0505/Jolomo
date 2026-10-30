-- SP09-02 и SP09-03: код доставки (delivery OTP). Отдельно от телефонного OTP: свои таблица, секрет и привязка.
CREATE TABLE delivery_challenges (
    id              uuid PRIMARY KEY,                 -- он же ссылка получателя: случайный UUID, подобрать нельзя
    parcel_id       uuid        NOT NULL REFERENCES parcel_requests (id),
    purpose         text        NOT NULL DEFAULT 'PARCEL_DELIVERY' CHECK (purpose = 'PARCEL_DELIVERY'),
    code_hash       text,                              -- HMAC, привязанный к посылке, получателю и переходу; NULL после завершения
    status          text        NOT NULL CHECK (status IN ('ACTIVE', 'CONSUMED', 'LOCKED', 'EXPIRED', 'SUPERSEDED')),
    attempts_used   integer     NOT NULL DEFAULT 0 CHECK (attempts_used BETWEEN 0 AND 3),
    created_at      timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    consumed_at     timestamptz,
    locked_at       timestamptz,
    CHECK (status <> 'ACTIVE' OR code_hash IS NOT NULL)
);
-- Одна действующая проверка на посылку.
CREATE UNIQUE INDEX delivery_challenges_one_active ON delivery_challenges (parcel_id) WHERE status = 'ACTIVE';
-- Доставка потребляет код не более одного раза на посылку (опора для «ровно одна доставка», SP10-04).
CREATE UNIQUE INDEX delivery_challenges_one_consumed ON delivery_challenges (parcel_id) WHERE status = 'CONSUMED';
CREATE INDEX delivery_challenges_parcel ON delivery_challenges (parcel_id, created_at DESC);
