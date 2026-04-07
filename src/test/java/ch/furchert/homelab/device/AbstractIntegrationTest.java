package ch.furchert.homelab.device;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

/**
 * Base class for integration tests that require real infrastructure containers.
 *
 * <p>Containers are declared as static fields so that Testcontainers starts
 * them once per JVM and reuses them across all subclasses. Each container is
 * annotated with {@link Container} to participate in the Testcontainers lifecycle.
 *
 * <p>Subclasses inherit the {@link DynamicPropertySource} that wires the
 * running container ports into the Spring application context before the
 * context is created.
 *
 * <p>{@code disabledWithoutDocker = true} causes the entire test class (and all
 * subclasses) to be skipped when Docker is not available on the host, so the
 * CI pipeline does not fail in environments without a Docker daemon.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractIntegrationTest {

    // ------------------------------------------------------------------
    // PostgreSQL
    // ------------------------------------------------------------------

    @Container
    protected static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("homelabdb")
                    .withUsername("homelab")
                    .withPassword("homelab")
                    .waitingFor(Wait.forListeningPort());

    // ------------------------------------------------------------------
    // Mosquitto
    // ------------------------------------------------------------------

    @Container
    @SuppressWarnings("resource")
    protected static final GenericContainer<?> mosquitto =
            new GenericContainer<>("eclipse-mosquitto:2")
                    .withExposedPorts(1883)
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("mosquitto-test.conf"),
                            "/mosquitto/config/mosquitto.conf"
                    )
                    .waitingFor(Wait.forListeningPort());

    // ------------------------------------------------------------------
    // InfluxDB 2.x
    // ------------------------------------------------------------------

    @Container
    @SuppressWarnings("resource")
    protected static final GenericContainer<?> influxdb =
            new GenericContainer<>("influxdb:2.7")
                    .withExposedPorts(8086)
                    .withEnv("DOCKER_INFLUXDB_INIT_MODE", "setup")
                    .withEnv("DOCKER_INFLUXDB_INIT_USERNAME", "admin")
                    .withEnv("DOCKER_INFLUXDB_INIT_PASSWORD", "adminpass")
                    .withEnv("DOCKER_INFLUXDB_INIT_ORG", "homelab")
                    .withEnv("DOCKER_INFLUXDB_INIT_BUCKET", "iot-bucket")
                    .withEnv("DOCKER_INFLUXDB_INIT_ADMIN_TOKEN", "test-token")
                    .waitingFor(Wait.forHttp("/ping")
                            .forStatusCode(204)
                            .withStartupTimeout(Duration.ofSeconds(120)));

    // ------------------------------------------------------------------
    // Spring property injection
    // ------------------------------------------------------------------

    /**
     * Wires the container ports into the Spring environment before the
     * application context is created. This runs once per test class.
     */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // MQTT — anonymous broker; username and password are required by
        // MqttProperties binding but are ignored by the broker.
        registry.add("app.mqtt.broker-url",
                () -> "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883));
        registry.add("app.mqtt.username", () -> "test-client");
        registry.add("app.mqtt.password", () -> "");

        // InfluxDB 2.x
        registry.add("app.influxdb.url",
                () -> "http://" + influxdb.getHost() + ":" + influxdb.getMappedPort(8086));
        registry.add("app.influxdb.token", () -> "test-token");
        registry.add("app.influxdb.org", () -> "homelab");
        registry.add("app.influxdb.bucket", () -> "iot-bucket");

        // Disable JWT validation: point at a port that is definitely not
        // a JWKS endpoint so the resource server does not block context startup.
        // The OAuth2 resource server only validates tokens when requests arrive;
        // with webEnvironment=NONE and no HTTP calls in integration tests the
        // startup itself is not gated on a reachable JWKS endpoint.
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://localhost:19999/auth/jwks");
    }
}
