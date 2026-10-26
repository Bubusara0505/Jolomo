-- SP03-01: долговечный outbox SMS. Повтор того же command_id безопасен.
CREATE TABLE sms_messages (
    id           uuid PRIMARY KEY,
    command_id   text        NOT NULL UNIQUE,
    phone        text        NOT NULL,
    body         text,                       -- обнуляется, когда сообщение покинуло PENDING
    status       text        NOT NULL CHECK (status IN ('PENDING', 'SUBMITTED', 'DELIVERED', 'FAILED')),
    attempts     integer     NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL,
    submitted_at timestamptz
);
CREATE INDEX sms_messages_pending_idx ON sms_messages (created_at) WHERE status = 'PENDING';
