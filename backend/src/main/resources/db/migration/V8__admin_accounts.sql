-- SP04-01: именованные администраторы, пароль + обязательный TOTP, свои сессии (не пользовательские).
CREATE TABLE admin_accounts (
    id              uuid PRIMARY KEY,
    login           text        NOT NULL UNIQUE CHECK (login ~ '^[a-z0-9._-]{3,64}$'),
    display_name    text        NOT NULL,
    role            text        NOT NULL CHECK (role IN ('REVIEWER', 'SUPPORT', 'OWNER')),
    status          text        NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    password_hash   text        NOT NULL,
    totp_secret_enc bytea       NOT NULL,                 -- секрет TOTP только в шифрованном виде
    last_totp_step  bigint      NOT NULL DEFAULT 0,       -- защита от повторного использования кода
    failed_attempts integer     NOT NULL DEFAULT 0,
    locked_until    timestamptz,
    created_at      timestamptz NOT NULL,
    created_by      uuid,
    updated_at      timestamptz NOT NULL
);

CREATE TABLE admin_recovery_codes (
    admin_id  uuid NOT NULL REFERENCES admin_accounts (id) ON DELETE CASCADE,
    code_hash text NOT NULL,                              -- SHA-256, сам код не хранится
    used_at   timestamptz,
    PRIMARY KEY (admin_id, code_hash)
);

CREATE TABLE admin_sessions (
    id         uuid PRIMARY KEY,
    admin_id   uuid        NOT NULL REFERENCES admin_accounts (id),
    token_hash text        NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz
);
CREATE INDEX admin_sessions_admin ON admin_sessions (admin_id);
