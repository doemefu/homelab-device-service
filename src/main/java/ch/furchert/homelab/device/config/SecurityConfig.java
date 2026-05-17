package ch.furchert.homelab.device.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

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
                "/api-docs",
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
    /**
     * Maps the auth-service {@code role} claim (an <em>unprefixed</em> string,
     * e.g. {@code "ADMIN"} — see auth-service {@code INTERFACES.md} §1) to a
     * {@code ROLE_<value>} authority so {@code hasRole("ADMIN")} works.
     *
     * <p>Without this, Spring's default converter only maps the {@code scope}
     * claim to {@code SCOPE_*} authorities and every {@code hasRole} check
     * fails. Device {@code client_credentials} tokens carry no {@code role}
     * claim, so they yield no role authority (cannot reach admin endpoints).
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            if (role == null || role.isBlank()) {
                return List.<GrantedAuthority>of();
            }
            return List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_" + role));
        });
        return converter;
    }

    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
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
                // Device registration / deletion is admin-only
                .requestMatchers(HttpMethod.POST, "/devices").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/devices/**").hasRole("ADMIN")
                // Everything else requires a valid JWT
                .anyRequest().authenticated()
            )
            // Validate JWTs via JWKS endpoint configured in application.yaml
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
            );
        return http.build();
    }
}
