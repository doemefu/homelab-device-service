package ch.furchert.homelab.device.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration properties for the InfluxDB connection.
 * Bound from the {@code app.influxdb.*} namespace in application.yaml.
 */
@Data
@ConfigurationProperties("app.influxdb")
public class InfluxDbProperties {

    /** Full InfluxDB URL, e.g. {@code http://influxdb:8086}. */
    private String url;

    /** InfluxDB API token — never log this value. */
    @ToString.Exclude
    private String token;

    /** InfluxDB organisation name. */
    private String org;

    /** Default bucket to write measurements into. */
    private String bucket;
}
