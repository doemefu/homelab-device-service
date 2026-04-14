package ch.furchert.homelab.device.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Chain 1 (order 1): session-based OAuth2 login for Swagger UI.
     * Handles the OIDC Authorization Code flow with PKCE for browser access to documentation.
     * Stores the authenticated principal in an HTTP session.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain swaggerSecurityFilterChain(
            HttpSecurity http,
            ClientRegistrationRepository clientRegistrationRepository) throws Exception {

        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(
                        clientRegistrationRepository,
                        "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(
                OAuth2AuthorizationRequestCustomizers.withPkce());

        http
            .securityMatcher(
                "/swagger-ui.html",
                "/swagger-ui/**",
                "/api-docs/**",
                "/webjars/**",
                "/login/oauth2/**",
                "/oauth2/**"
            )
            .authorizeHttpRequests(auth -> auth
                // Static assets for Swagger UI
                .requestMatchers("/webjars/**").permitAll()
                // Everything else in this chain requires authentication
                .anyRequest().authenticated()
            )
            .oauth2Login(login -> login
                .authorizationEndpoint(endpoint -> endpoint
                    .authorizationRequestResolver(resolver)
                )
            );

        return http.build();
    }

    /**
     * Chain 2 (order 2): stateless resource server for REST API, WebSocket, actuator.
     * Behaviour is identical to the previous single-chain configuration.
     * This chain handles all requests NOT matched by Chain 1.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            // Catch-all matcher; acts as fallback because it has lower priority (@Order(2)) than Chain 1
            .securityMatcher("/**")
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
