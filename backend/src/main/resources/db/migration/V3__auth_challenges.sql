-- SP02-02, SP02-03: телефонный OTP. Хранится только хеш; бюджет попыток и лимит повторной
-- отправки живут в БД и переживают рестарт.
CREATE TABLE auth_challenges (
    id            uuid PRIMARY KEY,
    phone         text        NOT NULL,
    purpose       text        NOT NULL CHECK (purpose IN ('LOGIN')),
    code_hash     text,                      -- обнуляется при любом терминальном состоянии
    expires_at    timestamptz NOT NULL,
    attempts_left integer     NOT NULL CHECK (attempts_left >= 0),
    status        text        NOT NULL CHECK (status IN ('ACTIVE', 'CONSUMED', 'EXPIRED', 'EXHAUSTED', 'SUPERSEDED')),
    created_at    timestamptz NOT NULL,
    consumed_at   timestamptz
);
-- Не более одного активного challenge на телефон и purpose.
CREATE UNIQUE INDEX auth_challenges_one_active
    ON auth_challenges (phone, purpose) WHERE status = 'ACTIVE';
CREATE INDEX auth_challenges_phone_created ON auth_challenges (phone, purpose, created_at);
