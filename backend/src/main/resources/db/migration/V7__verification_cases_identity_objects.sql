-- SP03-04: заявка на проверку водителя и объекты личности (байты лежат в зашифрованном каталоге, не в базе).
CREATE TABLE verification_cases (
    id             uuid PRIMARY KEY,
    driver_user_id uuid        NOT NULL REFERENCES users (id),
    status         text        NOT NULL CHECK (status IN ('SUBMITTED', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'EXPIRED')),
    reviewer_id    uuid,
    reason_code    text,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz
);
-- Одна открытая заявка на водителя.
CREATE UNIQUE INDEX verification_cases_one_open_per_driver
    ON verification_cases (driver_user_id) WHERE status IN ('SUBMITTED', 'IN_REVIEW');

CREATE TABLE identity_objects (
    id            uuid PRIMARY KEY,
    owner_user_id uuid        NOT NULL REFERENCES users (id),
    case_id       uuid        NOT NULL REFERENCES verification_cases (id),
    storage_key   text        NOT NULL UNIQUE,     -- путь внутри каталога, публичного URL нет
    content_type  text        NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'application/pdf')),
    size_bytes    bigint      NOT NULL CHECK (size_bytes > 0),
    sha256        text        NOT NULL,
    state         text        NOT NULL CHECK (state IN ('STORED', 'DELETED')),
    created_at    timestamptz NOT NULL
);
CREATE INDEX identity_objects_case ON identity_objects (case_id);
