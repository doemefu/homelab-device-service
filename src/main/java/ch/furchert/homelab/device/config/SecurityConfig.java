package ch.furchert.homelab.device.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.Collection;

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
     * Reads under {@code /devices/**} need any valid access token; every other method there
     * (device control, and future writes) needs role ADMIN (#81).
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
                // WebSocket upgrade stays open: browsers cannot send a bearer token on it.
                // The JWT is checked on the STOMP CONNECT frame (StompAuthChannelInterceptor).
                .requestMatchers("/ws/**").permitAll()
                // Reads stay open to any valid access token; writes (control) are ADMIN-only (#81)
                .requestMatchers(HttpMethod.GET, "/devices/**").authenticated()
                .requestMatchers("/devices/**").hasRole("ADMIN")
                // Everything else requires a valid JWT
                .anyRequest().authenticated()
            )
            // Validate JWTs via the JWKS endpoint and issuer configured in application.yaml; the
            // auto-configured decoder also applies the idTokenRejectingValidator bean below
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            );
        return http.build();
    }

    /**
     * Rejects ID tokens presented as bearer tokens (auth-service INTERFACES.md, "Reject ID tokens").
     * ID tokens are signed with the same key as access tokens and carry {@code role}; Spring
     * Authorization Server adds {@code azp} to every ID token and to no access token. Spring Boot
     * adds {@code OAuth2TokenValidator<Jwt>} beans to the auto-configured {@code JwtDecoder}, so the
     * check applies to REST and to STOMP CONNECT alike.
     */
    @Bean
    OAuth2TokenValidator<Jwt> idTokenRejectingValidator() {
        OAuth2Error idToken = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
                "ID tokens are not accepted as bearer tokens", null);
        return jwt -> jwt.hasClaim("azp")
                ? OAuth2TokenValidatorResult.failure(idToken)
                : OAuth2TokenValidatorResult.success();
    }

    /**
     * Maps {@code scope} to {@code SCOPE_*} and the auth-service {@code role} claim (an unprefixed
     * string such as {@code ADMIN}, present only on tokens of user-driven grants) to {@code ROLE_*},
     * as auth-service does for its own API. client_credentials tokens carry no role.
     */
    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("role");
        roles.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
            authorities.addAll(roles.convert(jwt));
            return authorities;
        });
        return converter;
    }
}
