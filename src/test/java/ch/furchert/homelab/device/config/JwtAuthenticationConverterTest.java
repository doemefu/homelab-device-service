package ch.furchert.homelab.device.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the {@code role} → {@code ROLE_*} mapping added in
 * {@link SecurityConfig}. SPEC §6 wrongly claimed this already existed; this
 * test guards the corrected behaviour.
 */
class JwtAuthenticationConverterTest {

    private final JwtAuthenticationConverter converter =
            new SecurityConfig().jwtAuthenticationConverter();

    private Jwt jwt(String claimName, Object value) {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .subject("sub")
                .claim(claimName, value)
                .build();
    }

    @Test
    void mapsRoleClaimToPrefixedAuthority() {
        AbstractAuthenticationToken token = converter.convert(jwt("role", "ADMIN"));

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_ADMIN");
    }

    @Test
    void userRoleMapsToRoleUser() {
        AbstractAuthenticationToken token = converter.convert(jwt("role", "USER"));

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_USER");
    }

    @Test
    void deviceTokenWithoutRoleClaim_hasNoRoleAuthority() {
        // client_credentials device tokens carry device_id, no role claim.
        AbstractAuthenticationToken token = converter.convert(jwt("device_id", "terra3"));

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .noneMatch(a -> a.startsWith("ROLE_"));
    }
}
