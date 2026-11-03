package ch.furchert.homelab.device.client;

import ch.furchert.homelab.device.dto.AuthClientCreated;
import ch.furchert.homelab.device.exception.DeviceAlreadyExistsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import static org.springframework.security.oauth2.client.web.client.RequestAttributeClientRegistrationIdResolver.clientRegistrationId;
import static org.springframework.security.oauth2.client.web.client.RequestAttributePrincipalResolver.principal;

/**
 * {@link AuthServiceClient} backed by the OAuth2-aware {@code authServiceRestClient}.
 *
 * <p>The {@code device-service-admin} registration ({@code client_credentials},
 * scope {@code clients:admin}) is selected per request via the
 * {@code clientRegistrationId} attribute; the application-scoped principal
 * keeps the authorized client cached across requests rather than per-user.
 */
@Slf4j
@Component
public class AuthServiceClientImpl implements AuthServiceClient {

    private static final String REGISTRATION_ID = "device-service-admin";
    private static final String PRINCIPAL = "device-service";

    private final RestClient restClient;

    public AuthServiceClientImpl(RestClient authServiceRestClient) {
        this.restClient = authServiceRestClient;
    }

    @Override
    public AuthClientCreated createDeviceClient(String clientId, String description) {
        return restClient.post()
                .uri("/api/v1/clients")
                .attributes(clientRegistrationId(REGISTRATION_ID))
                .attributes(principal(PRINCIPAL))
                .body(new CreateClientRequest(clientId, description))
                .retrieve()
                .onStatus(status -> status.value() == 409, (req, res) -> {
                    throw new DeviceAlreadyExistsException(clientId);
                })
                .body(AuthClientCreated.class);
    }

    @Override
    public void deleteDeviceClient(String clientId) {
        restClient.delete()
                .uri("/api/v1/clients/{clientId}", clientId)
                .attributes(clientRegistrationId(REGISTRATION_ID))
                .attributes(principal(PRINCIPAL))
                .retrieve()
                .toBodilessEntity();
        log.info("Revoked auth-service device client '{}'", clientId);
    }

    /** Request body for {@code POST /api/v1/clients}. */
    private record CreateClientRequest(String clientId, String description) {}
}
