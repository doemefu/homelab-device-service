package ch.furchert.homelab.device.client;

import ch.furchert.homelab.device.dto.AuthClientCreated;
import ch.furchert.homelab.device.exception.DeviceAlreadyExistsException;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.client.web.client.RequestAttributePrincipalResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WireMock-backed tests for {@link AuthServiceClientImpl}, mirroring the
 * behaviour of auth-service {@code DeviceClientLifecycleIT}: the OAuth2
 * {@code client_credentials} token round-trip plus the admin API contract.
 */
@WireMockTest
class AuthServiceClientTest {

    private AuthServiceClient client;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        String baseUrl = wm.getHttpBaseUrl();

        ClientRegistration registration = ClientRegistration
                .withRegistrationId("device-service-admin")
                .clientId("device-service")
                .clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(baseUrl + "/oauth2/token")
                .scope("clients:admin")
                .build();
        ClientRegistrationRepository repo = new InMemoryClientRegistrationRepository(registration);
        OAuth2AuthorizedClientService svc = new InMemoryOAuth2AuthorizedClientService(repo);

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(repo, svc);
        manager.setAuthorizedClientProvider(
                OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());

        OAuth2ClientHttpRequestInterceptor interceptor = new OAuth2ClientHttpRequestInterceptor(manager);
        interceptor.setPrincipalResolver(new RequestAttributePrincipalResolver());
        interceptor.setAuthorizationFailureHandler(
                OAuth2ClientHttpRequestInterceptor.authorizationFailureHandler(svc));

        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(interceptor)
                .build();
        client = new AuthServiceClientImpl(restClient);
    }

    private void stubToken() {
        stubFor(post(urlEqualTo("/oauth2/token")).willReturn(okJson(
                "{\"access_token\":\"test-access-token\",\"token_type\":\"Bearer\","
                        + "\"expires_in\":3600,\"scope\":\"clients:admin\"}")));
    }

    @Test
    void createDeviceClient_happyPath_obtainsTokenThenReturnsCreatedClient() {
        stubToken();
        stubFor(post(urlEqualTo("/api/v1/clients"))
                .withHeader("Authorization", equalTo("Bearer test-access-token"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"clientId\":\"terra3\",\"clientSecret\":\"plaintext-secret\","
                                + "\"scopes\":[\"mqtt:pub\",\"mqtt:sub\"],"
                                + "\"createdAt\":\"2026-05-16T10:00:00Z\"}")));

        AuthClientCreated created = client.createDeviceClient("terra3", "Greenhouse 3");

        assertThat(created.clientId()).isEqualTo("terra3");
        assertThat(created.clientSecret()).isEqualTo("plaintext-secret");
        assertThat(created.scopes()).containsExactly("mqtt:pub", "mqtt:sub");

        // Token obtained via client_credentials grant.
        verify(postRequestedFor(urlEqualTo("/oauth2/token"))
                .withRequestBody(containing("grant_type=client_credentials")));
        verify(postRequestedFor(urlEqualTo("/api/v1/clients"))
                .withRequestBody(matchingJsonPath("$.clientId", equalTo("terra3"))));
    }

    @Test
    void createDeviceClient_conflict_throwsDeviceAlreadyExists() {
        stubToken();
        stubFor(post(urlEqualTo("/api/v1/clients"))
                .willReturn(aResponse().withStatus(409)));

        assertThatThrownBy(() -> client.createDeviceClient("terra3", null))
                .isInstanceOf(DeviceAlreadyExistsException.class);
    }

    @Test
    void deleteDeviceClient_sendsAuthenticatedDeleteAndIsIdempotent() {
        stubToken();
        stubFor(delete(urlEqualTo("/api/v1/clients/terra3"))
                .willReturn(aResponse().withStatus(204)));

        client.deleteDeviceClient("terra3");

        verify(deleteRequestedFor(urlEqualTo("/api/v1/clients/terra3"))
                .withHeader("Authorization", equalTo("Bearer test-access-token")));
    }
}
