-- SP03-03: профиль водителя, правила допуска как данные, выключатель обязательств, аудит.
CREATE TABLE driver_profiles (
    user_id                  uuid PRIMARY KEY REFERENCES users (id),
    status                   text        NOT NULL CHECK (status IN
        ('UNVERIFIED', 'SUBMITTED', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'EXPIRED', 'SUSPENDED')),
    status_before_suspension text,
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL
);

-- Правила допуска: действие разрешено, если статус профиля есть в этой таблице.
-- Исход заключения юриста меняет строки, а не код.
CREATE TABLE eligibility_rules (
    action         text NOT NULL,
    profile_status text NOT NULL,
    PRIMARY KEY (action, profile_status)
);
INSERT INTO eligibility_rules (action, profile_status) VALUES ('PUBLISH_TRIP', 'APPROVED');

-- Выключатель юриста. По умолчанию выключен. Включает только сервер (ops/админ), клиент не может.
CREATE TABLE obligation_config (
    key       text PRIMARY KEY,
    enabled   boolean     NOT NULL DEFAULT false,
    version   text,
    loaded_at timestamptz,
    loaded_by uuid
);
INSERT INTO obligation_config (key, enabled) VALUES ('TRIP_PUBLISH', false), ('PARCEL_CREATE', false);

-- Привилегированные и значимые действия: кто, что, над кем, по какой причине.
CREATE TABLE audit_events (
    id            uuid PRIMARY KEY,
    occurred_at   timestamptz NOT NULL,
    actor_user_id uuid,
    action        text        NOT NULL,
    subject_type  text        NOT NULL,
    subject_id    uuid,
    reason_code   text        NOT NULL,
    from_state    text,
    to_state      text
);
CREATE INDEX audit_events_subject ON audit_events (subject_type, subject_id, occurred_at);
