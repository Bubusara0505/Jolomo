-- SP04-04: каталог точек посадки и поездки. Инварианты держит база.

-- Каталог точек (TBD-022: seed заменяемый, шире двух пунктов пока не нужен).
CREATE TABLE pickup_locations (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code                 text NOT NULL UNIQUE,
    city                 text NOT NULL CHECK (city IN ('BISHKEK', 'KARAKOL')),
    name_ky              text NOT NULL,
    name_ru              text NOT NULL,
    name_en              text NOT NULL,
    location             geography(Point, 4326) NOT NULL,
    -- false: координаты временные (центр города), до пилота их надо заменить на координаты самой точки.
    coordinates_verified boolean NOT NULL DEFAULT false,
    active               boolean NOT NULL DEFAULT true,
    UNIQUE (id, city)
);
CREATE INDEX pickup_locations_gix ON pickup_locations USING gist (location);

-- ВНИМАНИЕ: координаты ниже это центры городов, а не вокзалы. Заменить после уточнения физической точки
-- (положение Западного автовокзала Бишкека менялось, см. README). Названия на KY требуют проверки носителем.
INSERT INTO pickup_locations (code, city, name_ky, name_ru, name_en, location, coordinates_verified) VALUES
    ('BISHKEK_WEST_BUS_STATION', 'BISHKEK',
     'Бишкек Батыш автовокзалы', 'Западный автовокзал Бишкека', 'Bishkek West Bus Station',
     ST_SetSRID(ST_MakePoint(74.5698, 42.8746), 4326)::geography, false),
    ('KARAKOL_BUS_STATION', 'KARAKOL',
     'Каракол автовокзалы', 'Автовокзал Каракола', 'Karakol Bus Station',
     ST_SetSRID(ST_MakePoint(78.3936, 42.4907), 4326)::geography, false);

CREATE TABLE trips (
    id                      uuid PRIMARY KEY,
    driver_user_id          uuid        NOT NULL REFERENCES users (id),
    direction               text        NOT NULL CHECK (direction IN ('BISHKEK_TO_KARAKOL', 'KARAKOL_TO_BISHKEK')),
    -- Город отправления выводится из направления; точка посадки обязана быть в этом городе.
    origin_city             text GENERATED ALWAYS AS (
                                CASE direction WHEN 'BISHKEK_TO_KARAKOL' THEN 'BISHKEK' WHEN 'KARAKOL_TO_BISHKEK' THEN 'KARAKOL' END
                            ) STORED,
    pickup_location_id      uuid        NOT NULL,
    departure_from          timestamptz NOT NULL,
    departure_to            timestamptz NOT NULL,
    seats_total             integer     NOT NULL,
    seats_available         integer     NOT NULL,
    parcel_slots_total      integer     NOT NULL DEFAULT 0,
    parcel_slots_available  integer     NOT NULL DEFAULT 0,
    price_kgs               integer     NOT NULL,   -- цена места в целых сомах
    currency                text        NOT NULL DEFAULT 'KGS',
    status                  text        NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED', 'COMPLETED')),
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    published_at            timestamptz,
    FOREIGN KEY (pickup_location_id, origin_city) REFERENCES pickup_locations (id, city),
    CHECK (departure_to > departure_from),
    CHECK (seats_total > 0),
    CHECK (seats_available BETWEEN 0 AND seats_total),
    CHECK (parcel_slots_total >= 0),
    CHECK (parcel_slots_available BETWEEN 0 AND parcel_slots_total),
    CHECK (price_kgs > 0),
    CHECK (currency = 'KGS'),
    CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);
CREATE INDEX trips_owner ON trips (driver_user_id);
-- Для поиска (SP05-02): только опубликованные, по направлению и времени.
CREATE INDEX trips_search ON trips (direction, departure_from) WHERE status = 'PUBLISHED';
