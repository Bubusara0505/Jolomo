-- SP04-03: версия уведомления у пользователя, legal hold, удаление просроченных синтетических строк.
-- Сроки хранения персональных данных реальных людей здесь НЕ заданы (ждут юриста): под удаление попадают
-- только строки, явно помеченные синтетическими и просроченные.

-- Реестр версий уведомления. Сам текст лежит в каталогах KY/RU/EN, здесь только версия.
-- ВНИМАНИЕ: строка ниже рабочая заготовка, подписанную версию юриста добавят новой строкой (миграцией или админом).
CREATE TABLE privacy_notices (
    version      text PRIMARY KEY CHECK (version ~ '^[A-Za-z0-9._-]{1,32}$'),
    published_at timestamptz NOT NULL,
    is_current   boolean     NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX privacy_notices_one_current ON privacy_notices ((true)) WHERE is_current;
INSERT INTO privacy_notices (version, published_at, is_current) VALUES ('WORKING-1', now(), true);

ALTER TABLE users
    ADD COLUMN notice_version       text REFERENCES privacy_notices (version),
    ADD COLUMN notice_accepted_at   timestamptz,
    ADD COLUMN synthetic            boolean     NOT NULL DEFAULT false,
    ADD COLUMN synthetic_expires_at timestamptz,
    ADD CONSTRAINT users_notice_pair CHECK ((notice_version IS NULL) = (notice_accepted_at IS NULL)),
    ADD CONSTRAINT users_synthetic_expiry CHECK (synthetic OR synthetic_expires_at IS NULL);
CREATE INDEX users_synthetic_expiry_idx ON users (synthetic_expires_at) WHERE synthetic;

-- Изъятие из удаления. Внешнего ключа нет намеренно: запись о hold переживает удаление строки пользователя.
CREATE TABLE legal_holds (
    id          uuid PRIMARY KEY,
    user_id     uuid        NOT NULL,
    reason_code text        NOT NULL CHECK (reason_code ~ '^[A-Z0-9_]{3,64}$'),
    placed_by   uuid        NOT NULL,
    placed_at   timestamptz NOT NULL,
    released_by uuid,
    released_at timestamptz,
    CONSTRAINT legal_holds_release_pair CHECK ((released_by IS NULL) = (released_at IS NULL))
);
-- Не больше одного действующего hold на пользователя.
CREATE UNIQUE INDEX legal_holds_one_active_per_user ON legal_holds (user_id) WHERE released_at IS NULL;

-- Удаление синтетики должно уметь убрать записи инцидентов и пометок вместе с синтетическим пользователем.
-- Исключение действует только внутри транзакции, где задание явно выставило jolomo.purge_synthetic,
-- и только для DELETE этих двух таблиц. audit_events и domain_events это исключение не затрагивает.
CREATE OR REPLACE FUNCTION append_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE'
       AND TG_TABLE_NAME IN ('incidents', 'suspicious_activity')
       AND current_setting('jolomo.purge_synthetic', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;
