-- SP11-01: пометки подозрительной активности. SP12-02: флаги функций, из которых студент-курьер не включается.

-- Виды пометок как данные. ВНИМАНИЕ: рабочая заготовка, не решение юриста.
CREATE TABLE suspicion_kinds (
    code   text PRIMARY KEY CHECK (code ~ '^[A-Z0-9_]{3,64}$'),
    active boolean NOT NULL DEFAULT true
);
INSERT INTO suspicion_kinds (code) VALUES
    ('MULTIPLE_ACCOUNTS'), ('FAKE_RECIPIENT_PHONE'), ('REPEATED_CODE_FAILURES'), ('DOCUMENT_MISMATCH'), ('OTHER');

-- Пометка это только запись: она ничего не блокирует и не меняет у пользователя. Приостановка делается отдельным действием.
CREATE TABLE suspicious_activity (
    id          uuid PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users (id),
    kind        text        NOT NULL REFERENCES suspicion_kinds (code),
    reason_code text        NOT NULL,
    recorded_by uuid        NOT NULL,
    created_at  timestamptz NOT NULL
);
CREATE INDEX suspicious_activity_user ON suspicious_activity (user_id, created_at DESC);
CREATE TRIGGER suspicious_activity_no_change BEFORE UPDATE OR DELETE ON suspicious_activity
    FOR EACH ROW EXECUTE FUNCTION append_only();

-- Флаги функций. Студент-курьер: база не позволяет включить флаг. Для включения позже нужны отдельный меморандум
-- и новая миграция, которая снимет это ограничение осознанно (RQ-13).
CREATE TABLE feature_flags (
    key     text PRIMARY KEY,
    enabled boolean NOT NULL DEFAULT false,
    CONSTRAINT student_courier_stays_off CHECK (key <> 'STUDENT_COURIER' OR enabled = false)
);
INSERT INTO feature_flags (key, enabled) VALUES ('STUDENT_COURIER', false);
