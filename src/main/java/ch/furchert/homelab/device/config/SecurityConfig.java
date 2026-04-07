package ch.furchert.homelab.device.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Allow WebSocket upgrade handshakes without CSRF token
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/ws/**")
            )
            // Stateless — no HTTP session, JWT carries all auth state
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> auth
                // Health and info probes are public (liveness/readiness checks)
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                // WebSocket endpoint is read-only broadcast — no auth required
                .requestMatchers("/ws/**").permitAll()
                // OpenAPI / Swagger UI — documentation access without auth
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/api-docs/**").permitAll()
                // Everything else requires a valid JWT
                .anyRequest().authenticated()
            )
            // Validate JWTs via JWKS endpoint configured in application.yaml
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> {})
            );
        return http.build();
    }
}
