-- SP05-04: append-only журнал доменных событий. Пишется в той же транзакции, что и изменение.
CREATE TABLE domain_events (
    id             uuid        PRIMARY KEY,                 -- идентификатор события: повтор того же id игнорируется
    aggregate_type text        NOT NULL,
    aggregate_id   uuid        NOT NULL,                    -- без внешнего ключа: журнал переживает сущность
    version        integer     NOT NULL CHECK (version >= 1),
    event_type     text        NOT NULL,
    actor_user_id  uuid,
    occurred_at    timestamptz NOT NULL,
    payload        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (aggregate_type, aggregate_id, version)          -- версии одной сущности без дублей и пропусков вставки
);

-- Append-only: изменение и удаление строк запрещены самой базой.
CREATE FUNCTION domain_events_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'domain_events is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER domain_events_no_change
    BEFORE UPDATE OR DELETE ON domain_events
    FOR EACH ROW EXECUTE FUNCTION domain_events_immutable();
