# JolOmo — backend

Java 21, Spring Boot (кандидат 4.1.1 по ТЗ), Gradle Kotlin DSL, PostgreSQL 16 + PostGIS, Flyway.

## Запуск
1. `cp .env.example .env`, задайте `DB_PASSWORD`, `OTP_PEPPER` (`openssl rand -hex 32`), `SESSION_SECRET` (`openssl rand -hex 32`) и `SMS_BODY_KEY` (`openssl rand -base64 32`). Без любого из трёх секретов приложение не стартует.
2. `docker compose up -d` — локальная БД с PostGIS.
3. `./gradlew bootRun` (переменные из `.env` экспортируйте в окружение). Чтобы видеть код SMS в логе: `SPRING_PROFILES_ACTIVE=dev SMS_FAKE_LOG_BODY=true ./gradlew bootRun`; без профиля `dev` флаг не даст стартовать.
4. Проверка: `curl localhost:8080/actuator/health`.

## Тесты
`./gradlew test` — нужен запущенный Docker (Testcontainers с образом `postgis/postgis:16-3.4`).
H2 не используется.

## Правила
- Схему меняет только Flyway (`src/main/resources/db/migration`), Hibernate таблицы не создаёт.
- Модули: auth, users, verification, trips, bookings, parcels, courier, admin, sms, events, shared.
  У `courier` нет маршрутов.
- Версии зависимостей зафиксированы здесь и в `build.gradle.kts` (замена FRG-03).

- Версии зависимостей фиксирует `gradle.lockfile`: создать один раз командой `./gradlew dependencies --write-locks`.

## API
Полное описание: `openapi.yaml`. Кратко:

- Ошибка всегда одним конвертом `{"code", "message" (ключ перевода), "correlationId"}`, без трассы стека.
  Заголовок `X-Correlation-Id` принимается и возвращается, id попадает в каждую строку журнала `[cid=...]`.
- `GET /actuator/health`.
- `POST /api/v1/auth/otp/request` `{phone, purpose}` -> 202 `{challengeId, expiresAt, resendAfterSeconds}`.
  Телефон `+996` и 9 цифр. Лимиты: 1 запрос в 60 с и 5 в час на телефон, 30 в минуту на всех (`OTP_BUSY`).
- `POST /api/v1/auth/otp/verify` `{challengeId, phone, purpose, code}` -> 200 `{verified, userId}` и три cookie.
  Ошибки: `OTP_INVALID`, `OTP_EXPIRED`, `OTP_ATTEMPTS_EXHAUSTED`, `USER_SUSPENDED`.
- `POST /api/v1/auth/refresh` -> 204 и новые cookie; `SESSION_INVALID` или `SESSION_REUSED` (401) означают «нужен новый вход».
- `POST /api/v1/auth/logout` -> 204. `GET /api/v1/auth/csrf`. `GET /api/v1/me`.

## Сессии и CSRF (для фронтенда)
- Три cookie: `jl_access` (15 минут) и `jl_refresh` (30 дней, только для `/api/v1/auth`) HttpOnly; Secure; SameSite=Lax,
  а также `jl_csrf`, который читает JS. Токены в `localStorage` не хранить.
- На каждый POST/PUT/PATCH/DELETE, когда есть cookie сессии, добавлять заголовок `X-CSRF-Token` со значением `jl_csrf`
  (иначе 403 `CSRF_INVALID`). Если cookie пропала, вызвать `GET /api/v1/auth/csrf`.
- При 401 `UNAUTHENTICATED` один раз вызвать refresh и повторить запрос. Запросы refresh отправлять строго по одному:
  два параллельных refresh одним токеном считаются кражей и отзывают сессию.
- Реальные SMS не отправляются: фейковый шлюз. Текст SMS хранится в базе зашифрованным (`SMS_BODY_KEY`) и стирается после отправки.
