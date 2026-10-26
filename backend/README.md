# JolOmo — backend

Java 21, Spring Boot (кандидат 4.1.1 по ТЗ), Gradle Kotlin DSL, PostgreSQL 16 + PostGIS, Flyway.

## Запуск
1. `cp .env.example .env`, задайте `DB_PASSWORD` и `OTP_PEPPER` (секрет для хеша кодов: `openssl rand -hex 32`). Без `OTP_PEPPER` приложение не стартует.
2. `docker compose up -d` — локальная БД с PostGIS.
3. `./gradlew bootRun` (переменные из `.env` экспортируйте в окружение).
4. Проверка: `curl localhost:8080/actuator/health`.

## Тесты
`./gradlew test` — нужен запущенный Docker (Testcontainers с образом `postgis/postgis:16-3.4`).
H2 не используется.

## Правила
- Схему меняет только Flyway (`src/main/resources/db/migration`), Hibernate таблицы не создаёт.
- Модули: auth, users, verification, trips, bookings, parcels, courier, admin, sms, events, shared.
  У `courier` нет маршрутов.
- Версии зависимостей зафиксированы здесь и в `build.gradle.kts` (замена FRG-03).

## API (спринт 2)
- Ошибка всегда одним конвертом: `{"code", "message" (ключ перевода), "correlationId"}`, без трассы стека.
  Заголовок `X-Correlation-Id` принимается и возвращается, id попадает в каждую строку журнала `[cid=...]`.
- `GET /actuator/health` — health.
- `POST /api/v1/auth/otp/request` `{phone, purpose}` -> 202 `{challengeId, expiresAt, resendAfterSeconds}`.
  Телефон: `+996` и 9 цифр. Код в ответе не возвращается.
- `POST /api/v1/auth/otp/verify` `{challengeId, phone, purpose, code}` -> 200 `{verified: true}`.
  Ошибки: `OTP_INVALID`, `OTP_EXPIRED`, `OTP_ATTEMPTS_EXHAUSTED`, `OTP_TOO_SOON`, `OTP_RESEND_LIMIT`.
  Сессия пока не выдаётся (SP02-04).
- SMS не отправляются: фейковый шлюз. Чтобы увидеть код локально, запустите с `SMS_FAKE_LOG_BODY=true`
  и смотрите лог (только dev).
