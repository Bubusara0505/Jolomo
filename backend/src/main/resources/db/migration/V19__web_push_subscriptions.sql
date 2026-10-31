-- SP11-02: подписки Web Push. Хранятся только endpoint, срок и отзыв. Ключи шифрования подписки не храним:
-- push уходит без полезной нагрузки (пустой), поэтому в нём не может быть контактов и он ничего не подтверждает.
-- Endpoint это адрес, по которому можно разбудить устройство человека: с точки зрения приватности он как токен,
-- в журналы и ответы API не попадает.
CREATE TABLE web_push_subscriptions (
    id             uuid PRIMARY KEY,
    user_id        uuid        NOT NULL REFERENCES users (id),
    endpoint       text        NOT NULL UNIQUE CHECK (char_length(endpoint) BETWEEN 1 AND 2048),
    created_at     timestamptz NOT NULL,
    expires_at     timestamptz NOT NULL,
    revoked_at     timestamptz,
    revoke_reason  text CHECK (revoke_reason IN ('USER', 'GONE', 'REPEATED_FAILURES', 'POLICY')),
    failure_count  integer     NOT NULL DEFAULT 0 CHECK (failure_count >= 0),
    last_failure_at timestamptz,
    CONSTRAINT web_push_revoke_pair CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL))
);
CREATE INDEX web_push_subscriptions_user_active ON web_push_subscriptions (user_id) WHERE revoked_at IS NULL;
