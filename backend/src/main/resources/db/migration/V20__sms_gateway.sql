-- SP10-01: Android-шлюз SMS по схеме «устройство само опрашивает сервер» и правдивые статусы.
-- Статус SENT или DELIVERED возможен только у сообщения, которое взяло и подтвердило конкретное устройство.
ALTER TABLE sms_messages
    ADD COLUMN leased_by    text,          -- устройство, которое взяло сообщение (имя из SMS_GATEWAY_DEVICES)
    ADD COLUMN lease_until  timestamptz,   -- до этого момента сообщение закреплено за устройством и без подтверждения
    ADD COLUMN sent_at      timestamptz,   -- когда сервер получил от устройства «отправлено»
    ADD COLUMN delivered_at timestamptz,   -- когда сервер получил от устройства отчёт о доставке
    ADD COLUMN failed_reason text,         -- короткий код причины, не текст
    ADD CONSTRAINT sms_sent_delivered_need_device CHECK (status NOT IN ('SENT', 'DELIVERED') OR leased_by IS NOT NULL),
    ADD CONSTRAINT sms_delivered_needs_time CHECK (status <> 'DELIVERED' OR delivered_at IS NOT NULL);

-- Сообщения, принятые устройством, но так и не получившие от него отчёта: переводятся в UNKNOWN (см. SmsGatewayService).
CREATE INDEX sms_messages_gateway_submitted_idx ON sms_messages (submitted_at)
    WHERE status = 'SUBMITTED' AND leased_by IS NOT NULL;

-- Когда устройство в последний раз опрашивало сервер (для алерта «молчание шлюза», SP13-03).
CREATE TABLE gateway_heartbeats (
    device       text PRIMARY KEY,
    last_seen_at timestamptz NOT NULL,
    polls        bigint      NOT NULL DEFAULT 0
);
