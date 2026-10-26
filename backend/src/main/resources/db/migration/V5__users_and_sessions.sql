-- SP02-04: пользователи и семейства refresh-токенов.
CREATE TABLE users (
    id            uuid PRIMARY KEY,
    phone         text        NOT NULL UNIQUE,
    status        text        NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at    timestamptz NOT NULL,
    last_login_at timestamptz
);

CREATE TABLE refresh_sessions (
    id          uuid PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users (id),
    family_id   uuid        NOT NULL,
    token_hash  text        NOT NULL UNIQUE,  -- SHA-256 токена; сам токен не хранится
    status      text        NOT NULL CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED')),
    created_at  timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL,
    rotated_at  timestamptz
);
CREATE INDEX refresh_sessions_family ON refresh_sessions (family_id);
-- В семействе ровно один действующий refresh.
CREATE UNIQUE INDEX refresh_sessions_one_active_per_family
    ON refresh_sessions (family_id) WHERE status = 'ACTIVE';
