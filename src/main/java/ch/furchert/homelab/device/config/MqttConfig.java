package ch.furchert.homelab.device.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Activates binding of {@link MqttProperties} to the {@code app.mqtt.*} namespace.
 */
@Configuration
@EnableConfigurationProperties(MqttProperties.class)
public class MqttConfig {
}
