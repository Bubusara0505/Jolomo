-- SP03-01 (правки): состояния SMS по ТЗ и шифрованный текст.
-- Старые строки хранили текст открытым видом, расшифровать их нельзя: завершаем и стираем.
ALTER TABLE sms_messages DROP CONSTRAINT sms_messages_status_check;
UPDATE sms_messages SET status = 'FAILED', body = NULL WHERE status = 'PENDING';
ALTER TABLE sms_messages ADD CONSTRAINT sms_messages_status_check
    CHECK (status IN ('QUEUED', 'SUBMITTED', 'SENT', 'DELIVERED', 'FAILED', 'UNKNOWN'));
-- body теперь шифртекст (AES-GCM); NULL после отправки или отказа.
ALTER TABLE sms_messages ADD COLUMN expires_at timestamptz; -- после этого срока сообщение не отправляем (для OTP = срок кода)
DROP INDEX sms_messages_pending_idx;
CREATE INDEX sms_messages_queued_idx ON sms_messages (created_at) WHERE status = 'QUEUED';
