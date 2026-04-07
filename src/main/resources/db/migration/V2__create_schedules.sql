CREATE TABLE schedules (
    id              BIGSERIAL PRIMARY KEY,
    device_name     VARCHAR(50),
    field           VARCHAR(50),
    payload         TEXT,
    cron_expression VARCHAR(100),
    active          BOOLEAN DEFAULT TRUE
);
