package ch.furchert.homelab.device.config;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApiBlocking;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration that wires the InfluxDB client beans.
 *
 * <p>The token is passed as a {@code char[]} to avoid keeping a {@code String}
 * reference alive in the heap longer than necessary.
 */
@Configuration
@EnableConfigurationProperties(InfluxDbProperties.class)
public class InfluxDbConfig {

    /**
     * Creates and configures the {@link InfluxDBClient} using properties
     * bound from {@code app.influxdb.*}.
     *
     * @param props bound InfluxDB properties; must not be null
     * @return a fully initialised {@link InfluxDBClient}
     */
    @Bean
    public InfluxDBClient influxDBClient(InfluxDbProperties props) {
        return InfluxDBClientFactory.create(
                props.getUrl(),
                props.getToken().toCharArray(),
                props.getOrg(),
                props.getBucket()
        );
    }

    /**
     * Exposes the blocking write API as a Spring bean so it can be injected
     * directly into services without holding a reference to the full client.
     *
     * @param client the {@link InfluxDBClient} bean
     * @return a {@link WriteApiBlocking} backed by the given client
     */
    @Bean
    public WriteApiBlocking writeApiBlocking(InfluxDBClient client) {
        return client.getWriteApiBlocking();
    }
}
