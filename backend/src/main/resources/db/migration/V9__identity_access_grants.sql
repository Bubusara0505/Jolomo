-- SP04-02: короткая выдача файла личности. Токен действует 5 минут, привязан к администратору и объекту.
CREATE TABLE identity_access_grants (
    id           uuid PRIMARY KEY,
    token_hash   text        NOT NULL UNIQUE,   -- SHA-256 токена, сам токен не хранится
    object_id    uuid        NOT NULL REFERENCES identity_objects (id),
    admin_id     uuid        NOT NULL,
    purpose_code text        NOT NULL,
    created_at   timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL
);
CREATE INDEX identity_access_grants_object ON identity_access_grants (object_id);
