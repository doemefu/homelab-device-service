package ch.furchert.homelab.device.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test {@code application.yaml} shadows the production one on the test classpath, so the
 * decoder tests run against a copy of the JWT settings. This test pins the production file to the
 * same issuer and audience (#81, #124).
 */
class ProductionJwtSettingsTest {

    private static final String PREFIX = "spring.security.oauth2.resourceserver.jwt.";

    @Test
    void productionConfig_validatesIssuerAndFurchertChAudience() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("main", new FileSystemResource("src/main/resources/application.yaml"));

        assertThat(sources).hasSize(1);
        PropertySource<?> main = sources.getFirst();
        assertThat(String.valueOf(main.getProperty(PREFIX + "audiences"))).isEqualTo("furchert-ch");
        assertThat(String.valueOf(main.getProperty(PREFIX + "issuer-uri")))
                .isEqualTo("${JWT_ISSUER:https://auth.furchert.ch}");
    }
}
