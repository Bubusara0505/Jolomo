# Метрики пилота, реестр событий и ночная выгрузка (SP12-01)

Определения проверяются тестами `MetricsServiceTest` и `ExportServiceTest`. Меняется определение, меняются тест и этот документ.

## Реестр событий
`EventRegistry` перечисляет все типы событий журнала `domain_events` и поля payload, которые вообще можно выгружать.
Новый тип события сначала вносится в реестр, потом пишется в код. Тест `everyEventTypeTheServicesActuallyWriteIsRegistered`
прогоняет живые сервисы и падает, если в журнал попал незарегистрированный тип. Незарегистрированные события в выгрузку не попадают.

| Сущность | События |
|---|---|
| TRIP | `TRIP_CREATED`, `TRIP_UPDATED`, `TRIP_PUBLISHED`, `TRIP_CANCELLED` |
| BOOKING | `BOOKING_REQUESTED`, `BOOKING_ACCEPTED`, `BOOKING_REJECTED`, `BOOKING_CANCELLED`, `BOOKING_COMPLETED`, `BOOKING_NO_SHOW` |
| PARCEL | `PARCEL_REQUESTED`, `PARCEL_ACCEPTED`, `PARCEL_REJECTED`, `PARCEL_CANCELLED`, `PARCEL_PICKUP_CONFIRMED`, `PARCEL_IN_TRANSIT`, `PARCEL_DELIVERY_PENDING`, `PARCEL_DELIVERY_CODE_SENT`, `PARCEL_DELIVERED`, `PARCEL_VERIFICATION_LOCKED`, `PARCEL_VERIFICATION_UNLOCKED`, `PARCEL_INCIDENT_RECORDED` |

## Когорты
- Когорта броней: брони, у которых событие `BOOKING_REQUESTED` попало в отчётный период. Когорта посылок: посылки с `PARCEL_REQUESTED` в периоде.
- Исходы когорты считаются в любое время после запроса, даже после конца периода: бронь, запрошенная в понедельник и принятая в среду, относится к понедельнику.
- Считаем по уникальным сущностям: два события одного смысла у одной брони это одна бронь.
- Если знаменатель равен нулю, метрика не определена (`value = null`), а не 0 % и не 100 %.

## Метрики (числитель и знаменатель)
| Код | Числитель | Знаменатель |
|---|---|---|
| `BOOKING_ACCEPTANCE_RATE` | брони с `BOOKING_ACCEPTED` | брони с `BOOKING_ACCEPTED` или `BOOKING_REJECTED` |
| `BOOKING_COMPLETION_RATE` | брони с `BOOKING_COMPLETED` | брони с `BOOKING_COMPLETED` или `BOOKING_NO_SHOW` |
| `BOOKING_NO_SHOW_RATE` | брони с `BOOKING_NO_SHOW` | то же, что у доли завершённых |
| `BOOKING_CANCEL_AFTER_ACCEPT_RATE` | брони с `BOOKING_CANCELLED`, у которых `from` = `ACCEPTED` | брони с `BOOKING_ACCEPTED` |
| `PARCEL_ACCEPTANCE_RATE` | посылки с `PARCEL_ACCEPTED` | посылки с `PARCEL_ACCEPTED` или `PARCEL_REJECTED` |
| `PARCEL_DELIVERY_RATE` | посылки с `PARCEL_DELIVERED` | посылки, дошедшие до `PARCEL_DELIVERY_PENDING` |
| `PARCEL_VERIFICATION_LOCK_RATE` | посылки с `PARCEL_VERIFICATION_LOCKED` (хотя бы раз) | посылки, дошедшие до `PARCEL_DELIVERY_PENDING` |

Первые четыре метрики брони определены в `booking-lifecycle-and-metrics.md` (SP07-03), три метрики посылок добавлены здесь.
Знаменатель посылочных долей включает посылки, которые ещё ждут получателя: пока они не доставлены, доля доставленных занижена.
Это не ошибка, а свойство определения; читать долю нужно с учётом возраста когорты.

**Не определено намеренно:** «доля отмеченных после отправления» (нужно время отправления, а событие брони его не несёт) и любые метрики по деньгам (платформа денег не держит).

## Ночная выгрузка
Включается только явно: `EXPORT_ENABLED=true` и `EXPORT_PSEUDONYM_KEY` (не короче 16 символов, из окружения). Без ключа при включении приложение не запускается.
Запуск каждую ночь в 02:30 по Asia/Bishkek за вчерашние сутки, файл `jolomo-export-ГГГГ-ММ-ДД.json` в каталоге `EXPORT_DIR` (по умолчанию `~/.jolomo/export`, права `rw-------`, запись через временный файл, повтор перезаписывает).

Состав документа: `schemaVersion`, `generatedAt`, `window` (окно событий), `metricsCohort` (когорта метрик: все запросы до конца окна), `metrics`, `skippedUnregistered`, `events`.

Что сокращено, чтобы не было персональных данных:
- идентификаторы людей и сущностей (`aggregate`, `actor`, `tripId`, `passengerId`, `senderId`, `incidentId`) заменены псевдонимом: HMAC-SHA256 с ключом выгрузки, первые 12 байт в hex.
  Один человек даёт один псевдоним во всех выгрузках, но без ключа идентификатор не восстановить. Сменить ключ значит порвать связь между выгрузками.
- из payload берутся только поля белого списка: `from`, `to`, `reason`, `seats`, `size`, `category`, `obligationVersion`, `kind`, `parcelStatus`, `termsVersion`. Остальное (телефоны, любые коды, текст, суммы изменений поездки) отбрасывается.
- значение, похожее на телефон (9 и больше цифр подряд) или длиннее 64 символов, отбрасывается даже в разрешённом поле.
- сырых OTP и содержимого документов в журнале событий нет, а белый список их и не пропустил бы. Файлы личности в выгрузку не попадают вообще.

Что делать с ключом: он входит в набор копий ключей (SP13-01). Потеря ключа не теряет данные, но разрывает связность псевдонимов.
Сроки хранения выгрузок не заданы (ждут юриста): удаления старых файлов нет, каталог чистится вручную.
