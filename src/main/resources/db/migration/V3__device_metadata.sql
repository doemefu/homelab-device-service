-- Device registration metadata (SPEC-device-registration §5).
-- provisioned = TRUE means the device was created via the admin POST /devices
-- API; auto-created (first-MQTT-message) rows keep provisioned = FALSE.
ALTER TABLE devices
    ADD COLUMN type        VARCHAR(50),
    ADD COLUMN description VARCHAR(200),
    ADD COLUMN created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ADD COLUMN provisioned BOOLEAN NOT NULL DEFAULT FALSE;
