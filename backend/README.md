# JolOmo — backend

Java 21, Spring Boot (кандидат 4.1.1 по ТЗ), Gradle Kotlin DSL, PostgreSQL 16 + PostGIS, Flyway.

## Запуск
1. `cp .env.example .env` и задайте `DB_PASSWORD` (то же значение, что для БД).
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
