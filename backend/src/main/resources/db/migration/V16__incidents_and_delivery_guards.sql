-- SP09-01: инциденты поддержки. SP10-04: защита «ровно одна доставка» на уровне базы. Аудит только дописывается.

-- Виды инцидентов как данные: подписанный перечень юриста меняет таблицу, а не код.
-- ВНИМАНИЕ: список ниже рабочая заготовка, а не решение юриста.
CREATE TABLE incident_kinds (
    code   text PRIMARY KEY CHECK (code ~ '^[A-Z0-9_]{3,64}$'),
    active boolean NOT NULL DEFAULT true
);
INSERT INTO incident_kinds (code) VALUES
    ('RECIPIENT_UNREACHABLE'), ('CODE_NOT_RECEIVED'), ('PARCEL_DAMAGED'), ('PARCEL_LOST'), ('HANDOVER_DISPUTE'), ('OTHER');

-- Инцидент ничего не меняет в посылке: он только запись. Свободного текста нет (персональные данные в него не попадут).
CREATE TABLE incidents (
    id            uuid PRIMARY KEY,
    parcel_id     uuid        NOT NULL REFERENCES parcel_requests (id),
    kind          text        NOT NULL REFERENCES incident_kinds (code),
    reason_code   text        NOT NULL,
    recorded_by   uuid        NOT NULL,      -- администратор поддержки
    parcel_status text        NOT NULL,      -- состояние посылки в момент записи
    terms_version text        NOT NULL,      -- версия условий (набор обязательств, действовавший при создании посылки)
    created_at    timestamptz NOT NULL
);
CREATE INDEX incidents_parcel ON incidents (parcel_id, created_at DESC);

CREATE FUNCTION append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER incidents_no_change BEFORE UPDATE OR DELETE ON incidents
    FOR EACH ROW EXECUTE FUNCTION append_only();
-- Строки аудита не правятся и не удаляются: на них опираются доставка и разбор.
CREATE TRIGGER audit_events_no_change BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION append_only();

-- У посылки не может быть двух событий DELIVERED, что бы ни случилось с процессом (RQ-10).
CREATE UNIQUE INDEX domain_events_one_parcel_delivered
    ON domain_events (aggregate_type, aggregate_id) WHERE event_type = 'PARCEL_DELIVERED';
