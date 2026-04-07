package ch.furchert.homelab.device.integration;

import ch.furchert.homelab.device.AbstractIntegrationTest;
import ch.furchert.homelab.device.service.InfluxWriterService;
import ch.furchert.homelab.device.service.ParsedMqttMessage;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link InfluxWriterService} against a real InfluxDB 2.x container.
 *
 * <p>Inherits container management and {@code @DynamicPropertySource} from
 * {@link AbstractIntegrationTest}. The service is loaded from the full Spring
 * application context (webEnvironment=NONE) so that the production
 * {@link InfluxWriterService} bean — backed by the container-wired
 * {@link com.influxdb.client.WriteApiBlocking} — is used.
 *
 * <p>After calling {@link InfluxWriterService#write}, the test creates an
 * independent {@link InfluxDBClient} pointing at the same container and runs
 * a Flux query to assert that the measurement point was actually stored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class InfluxWriterIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    InfluxWriterService influxWriterService;

    /** Independent InfluxDB client used only for query-back assertions. */
    private InfluxDBClient queryClient;

    @BeforeEach
    void setUpQueryClient() {
        String influxUrl = "http://" + influxdb.getHost() + ":" + influxdb.getMappedPort(8086);
        queryClient = InfluxDBClientFactory.create(influxUrl, "test-token".toCharArray(), "homelab", "iot-bucket");
    }

    @AfterEach
    void tearDownQueryClient() {
        if (queryClient != null) {
            queryClient.close();
        }
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    /**
     * Calls {@link InfluxWriterService#write} with a SENSOR_DATA message and
     * queries InfluxDB to verify the point was persisted with the correct
     * measurement name, device tag, temperature, and humidity fields.
     */
    @Test
    void write_sensorData_persistsPointInInfluxDb() throws InterruptedException {
        ParsedMqttMessage msg = new ParsedMqttMessage(
                "terra1",
                ParsedMqttMessage.MessageType.SENSOR_DATA,
                22.5,
                65.0,
                null, null, null, null
        );

        influxWriterService.write(msg);

        // Allow the write to propagate — InfluxDB writes are synchronous via
        // WriteApiBlocking but a brief pause guards against edge-case timing.
        Thread.sleep(500);

        String fluxQuery = """
                from(bucket: "iot-bucket")
                  |> range(start: -1m)
                  |> filter(fn: (r) => r._measurement == "terrarium")
                  |> filter(fn: (r) => r.device == "terra1")
                """;

        QueryApi queryApi = queryClient.getQueryApi();
        List<FluxTable> tables = queryApi.query(fluxQuery, "homelab");

        assertThat(tables)
                .as("at least one table returned from InfluxDB query")
                .isNotEmpty();

        // Collect all records across all tables into a flat list for assertion.
        List<FluxRecord> records = tables.stream()
                .flatMap(t -> t.getRecords().stream())
                .toList();

        assertThat(records)
                .as("at least one record written to InfluxDB")
                .isNotEmpty();

        boolean temperatureFound = records.stream().anyMatch(r ->
                "temperature".equals(r.getField())
                && r.getValue() instanceof Number n
                && Double.compare(n.doubleValue(), 22.5) == 0
        );
        boolean humidityFound = records.stream().anyMatch(r ->
                "humidity".equals(r.getField())
                && r.getValue() instanceof Number n
                && Double.compare(n.doubleValue(), 65.0) == 0
        );

        assertThat(temperatureFound)
                .as("temperature=22.5 written to InfluxDB")
                .isTrue();
        assertThat(humidityFound)
                .as("humidity=65.0 written to InfluxDB")
                .isTrue();
    }

    /**
     * Verifies that a non-SENSOR_DATA message results in no point written to
     * InfluxDB. The query should return zero records for an MQTT_STATUS message.
     */
    @Test
    void write_mqttStatus_doesNotWriteToInfluxDb() throws InterruptedException {
        ParsedMqttMessage msg = new ParsedMqttMessage(
                "terra2",
                ParsedMqttMessage.MessageType.MQTT_STATUS,
                null, null,
                true, null, null, null
        );

        influxWriterService.write(msg);

        Thread.sleep(300);

        // Query specifically for terra2 to avoid noise from other tests.
        String fluxQuery = """
                from(bucket: "iot-bucket")
                  |> range(start: -30s)
                  |> filter(fn: (r) => r._measurement == "terrarium")
                  |> filter(fn: (r) => r.device == "terra2")
                """;

        QueryApi queryApi = queryClient.getQueryApi();
        List<FluxTable> tables = queryApi.query(fluxQuery, "homelab");

        long recordCount = tables.stream()
                .mapToLong(t -> t.getRecords().size())
                .sum();

        assertThat(recordCount)
                .as("no InfluxDB records written for MQTT_STATUS message from terra2")
                .isZero();
    }
}
