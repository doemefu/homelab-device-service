CREATE TABLE devices (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50) NOT NULL UNIQUE,
    mqtt_online BOOLEAN     DEFAULT FALSE,
    temperature DOUBLE PRECISION,
    humidity    DOUBLE PRECISION,
    light       VARCHAR(10),
    night_light VARCHAR(10),
    rain        VARCHAR(10),
    last_seen   TIMESTAMP
);

-- Seed known devices
INSERT INTO devices (name) VALUES ('terra1'), ('terra2');
