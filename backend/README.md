# JolOmo — backend

Java 21, Spring Boot (кандидат 4.1.1 по ТЗ), Gradle Kotlin DSL, PostgreSQL 16 + PostGIS, Flyway.

## Запуск
1. `cp .env.example .env`, задайте `DB_PASSWORD`, `OTP_PEPPER` (`openssl rand -hex 32`), `SESSION_SECRET` (`openssl rand -hex 32`) и `SMS_BODY_KEY` (`openssl rand -base64 32`) `ADMIN_TOTP_KEY` (`openssl rand -base64 32`, шифрует секреты TOTP администраторов) и `IDENTITY_KEY` (`openssl rand -base64 32`, ключ файлов личности; потеряете ключ, потеряете файлы). Без любого из этих секретов приложение не стартует.
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

## Администраторы и первый вход
Вход администратора: логин, пароль и TOTP (или одноразовый код восстановления). Сессия отдельная от пользовательской (cookie `jl_admin`, 30 минут).
Права по ролям: `REVIEWER` проверяет заявки, `SUPPORT` принимает инциденты, `OWNER` может всё и один управляет учётными записями.

Создать первого администратора (один раз, на пустой базе):
1. `export ADMIN_TOTP_KEY=$(openssl rand -base64 32)` и `export ADMIN_BOOTSTRAP_LOGIN=owner`, остальные секреты как обычно.
2. Запустить `./gradlew bootRun`. В консоли один раз напечатаются пароль, ключ TOTP и 8 кодов восстановления. Сохраните их в менеджере паролей, ключ TOTP добавьте в приложение-аутентификатор (ввод ключа вручную).
3. Остановить приложение и убрать `ADMIN_BOOTSTRAP_LOGIN` из окружения.
Потеря устройства: другой OWNER вызывает `POST /api/v1/admin/accounts/{id}/reset`. Если OWNER единственный, а коды восстановления потеряны, остаётся удалить его строку в `admin_accounts` и повторить создание.

## Каталог точек (TBD-022)
В `pickup_locations` ровно две точки: Западный автовокзал Бишкека и автовокзал Каракола. Названия на KY, RU и EN, координаты в PostGIS.
**Координаты сейчас временные (центры городов, `coordinates_verified = false`)**, перед пилотом их надо заменить на координаты самих точек
отдельной миграцией. Физическое положение Западного автовокзала Бишкека менялось: старый закрыт в 2024 году, рейсы идут с вокзала на улице Фрунзе, 209,
а новый Западный автовокзал у села Достук, по сообщениям, готовится к открытию. Какую точку считать «западным автовокзалом» для пилота, решает владелец продукта.
Название на кыргызском стоит показать носителю языка.

## Поездки (SP05-01)
- `POST /api/v1/trips` создаёт черновик (нужен профиль водителя). `GET /api/v1/trips/mine` свои поездки.
  `GET /api/v1/trips/{id}`: владельцу своя в любом статусе, остальным только опубликованная в публичном виде (без владельца, телефонов, документов).
- `PATCH /api/v1/trips/{id}` правит поля **или** меняет статус: `{"status":"PUBLISHED"}` публикует, `{"status":"CANCELLED"}` отменяет. Не одновременно.
- Матрица: чужая поездка это 404 (не отличить от несуществующей); публикация и правка опубликованной требуют допуска водителя и загруженного
  выключателя обязательств (иначе 403 `NOT_APPROVED` или `OBLIGATIONS_NOT_LOADED`); отмена доступна владельцу всегда, повтор безопасен;
  завершённая и отменённая поездки заморожены (409 `INVALID_TRIP_STATE`).
- У опубликованной поездки правятся только цена, число мест и слотов посылок (не ниже уже занятого). Маршрут и время зафиксированы: для их смены поездку отменяют и создают новую.
- Поиск `GET /api/v1/trips` (SP05-02), события `domain_events` (SP05-04), Idempotency-Key (бронирование, SP06) в этой задаче не входят.

## Поиск поездок (SP05-02)
`GET /api/v1/trips?direction=BISHKEK_TO_KARAKOL` (вход не нужен). Параметры:
- `direction` обязателен (`BISHKEK_TO_KARAKOL` или `KARAKOL_TO_BISHKEK`);
- `from`, `to` окно отправления в ISO-8601 UTC (по умолчанию `from` = сейчас, `to` не ограничен); поездка подходит, если её окно пересекается с запрошенным;
- `nearPickupLocationId` и `radiusMeters` (по умолчанию 5000, максимум 100000): только поездки, чья точка посадки не дальше радиуса от выбранной точки каталога, ближайшие первыми при равном времени; в ответе `distanceMeters`;
- `seats` (по умолчанию 1) сколько свободных мест нужно; `limit` (по умолчанию 20, максимум 50) и `offset`.
Всегда исключены: черновики, отменённые и завершённые, просроченные (окно отправления закончилось), полные и поездки другого направления.
В ответе только публичные поля: направление, окно, точка (код, названия KY/RU/EN, координаты), свободные места, цена в KGS. Ни владельца, ни телефонов.

План запроса: тест `TripSearchExplainTest` пишет `EXPLAIN (ANALYZE, BUFFERS)` в `backend/build/explain/trip-search.txt`. Запись плана хранится в `backend/docs/explain-trip-search.txt`.

## Журнал событий (SP05-04)
Таблица `domain_events` append-only: триггер базы запрещает UPDATE и DELETE. Событие пишется в той же транзакции, что и изменение поездки
(`TRIP_CREATED`, `TRIP_UPDATED`, `TRIP_PUBLISHED`, `TRIP_CANCELLED`), у каждой сущности версии 1, 2, 3 без дублей и пропусков.
Повтор записи с тем же `event id` игнорируется (`DomainEventStore.append(eventId, ...)` вернёт `false`). Отказанные операции и правки без изменений событий не пишут.
В payload только идентификаторы и значения полей поездки, без телефонов. Журнал не имеет HTTP-маршрутов.

## Брони (SP06)
- `POST /api/v1/trips/{tripId}/bookings` с телом `{"seats":N}` и заголовком `Idempotency-Key` запрашивает места. Запрос места **не занимает** (места занимает принятие водителем).
  Отказы: нет такой, не опубликована или отменена (404 `TRIP_NOT_FOUND`), своя поездка (403 `OWN_TRIP`), уже отправилась (409 `TRIP_DEPARTED`), мест меньше запрошенного
  (409 `NOT_ENOUGH_SEATS`), уже есть активный запрос на эту поездку (409 `BOOKING_ALREADY_ACTIVE`, держит и частичный индекс базы), `seats` не больше 0 (400 `INVALID_SEATS`).
- `POST /api/v1/bookings/{id}/accept`, `/reject`, `/cancel`; `GET /api/v1/bookings/mine`, `GET /api/v1/bookings/{id}`, `GET /api/v1/trips/{tripId}/bookings` (водителю).
- Последнее место: принятие идёт под `SELECT ... FOR UPDATE` строки поездки, затем строки брони (порядок блокировок везде один). Побеждает один, остальные получают 409 `NO_SEATS_LEFT`.
  Отмена возвращает места только при переходе ACCEPTED -> CANCELLED, то есть не более одного раза. Повторные accept, reject и cancel безопасны и места не трогают.
- Права: принимает владелец поездки с допуском (правило `MANAGE_BOOKINGS`, данные в `eligibility_rules`). Отклонять и отменять можно и без допуска.
  Пассажир отменяет свой запрос или принятую бронь, водитель только принятую. После отправления отмены и принятия нет. Чужая бронь это 404 `BOOKING_NOT_FOUND`.
- Если водитель отменяет поездку, все её REQUESTED и ACCEPTED брони в той же транзакции становятся CANCELLED (`cancelled_by = TRIP_CANCELLED`), места не возвращаются.
- Идемпотентность: запись о ключе (`idempotency_records`) пишется в той же транзакции, что и бронь. Тот же ключ и то же тело возвращают сохранённый ответ (заголовок `Idempotent-Replayed: true`),
  другое тело с тем же ключом это 409 `IDEMPOTENCY_KEY_REUSED`. Отказы не сохраняются. Параллельные одинаковые запросы создают одну бронь. Сроки хранения записей не заданы (ждут юриста), удаления пока нет.
- События: `BOOKING_REQUESTED`, `BOOKING_ACCEPTED`, `BOOKING_REJECTED`, `BOOKING_CANCELLED` в `domain_events` (aggregate `BOOKING`), без телефонов.
- Контактов в ответах нет вообще: их откроет SP07-01 после ACCEPTED. `COMPLETED`, `NO_SHOW` и `REVIEW_REQUIRED` ставит SP07-03.
