package ch.furchert.homelab.device.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.client.web.client.RequestAttributePrincipalResolver;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Wires the OAuth2-aware {@link RestClient} used by {@code AuthServiceClient}
 * to call the auth-service admin API service-to-service.
 *
 * <p>Uses {@link AuthorizedClientServiceOAuth2AuthorizedClientManager} (no
 * servlet request needed) with the {@code client_credentials} provider. The
 * {@code OAuth2ClientHttpRequestInterceptor} obtains/refreshes the token and,
 * via its authorization-failure handler, evicts the cached client on a 401 so
 * the next call fetches a fresh token — no manual retry logic required.
 * Verified against Spring Security 7 reference docs.
 */
@Configuration
public class AuthServiceClientConfig {

    @Bean
    OAuth2ClientHttpRequestInterceptor authServiceOAuth2Interceptor(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) {

        OAuth2AuthorizedClientProvider provider =
                OAuth2AuthorizedClientProviderBuilder.builder()
                        .clientCredentials()
                        .build();

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(provider);

        OAuth2ClientHttpRequestInterceptor interceptor =
                new OAuth2ClientHttpRequestInterceptor(manager);
        // Resolve the principal from the request attribute (application-scoped,
        // not the current user) — this is a non-servlet, service-to-service call.
        interceptor.setPrincipalResolver(new RequestAttributePrincipalResolver());
        interceptor.setAuthorizationFailureHandler(
                OAuth2ClientHttpRequestInterceptor.authorizationFailureHandler(
                        authorizedClientService));
        return interceptor;
    }

    @Bean
    RestClient authServiceRestClient(
            @Value("${app.auth-service.base-url}") String baseUrl,
            @Value("${app.auth-service.connect-timeout:5s}") Duration connectTimeout,
            @Value("${app.auth-service.read-timeout:10s}") Duration readTimeout,
            OAuth2ClientHttpRequestInterceptor authServiceOAuth2Interceptor) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(connectTimeout, readTimeout))
                .requestInterceptor(authServiceOAuth2Interceptor)
                .build();
    }

    /**
     * JDK HTTP client with explicit timeouts: {@code RestClient.builder()} applies none, so a hung
     * auth-service would otherwise block the request thread (and its DB transaction) indefinitely.
     */
    public static JdkClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
