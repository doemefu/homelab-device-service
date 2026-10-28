package ch.furchert.homelab.device.integration;

import ch.furchert.homelab.device.AbstractIntegrationTest;
import ch.furchert.homelab.device.service.InfluxWriterService;
import ch.furchert.homelab.device.service.MqttClientService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * STOMP endpoint with the CONNECT authentication kill switch off
 * ({@code app.websocket.require-auth=false}): sessions without a token are accepted, but a
 * client SEND to a broker destination and a raw client {@code MESSAGE} frame are still rejected. The default ({@code true}) is covered
 * by {@link WebSocketIntegrationTest}.
 *
 * <p>{@link MqttClientService} is mocked, so this context never connects to the shared Mosquitto
 * container and cannot add another live {@code terra2/#} subscriber (see the cross-class caveat in
 * {@link WebSocketIntegrationTest}). {@link InfluxWriterService} is mocked for the same reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "app.websocket.require-auth=false")
class WebSocketAuthDisabledIntegrationTest extends AbstractIntegrationTest {

    private static final long AWAIT_SECONDS = 10;

    @MockitoBean
    MqttClientService mqttClientService;

    @MockitoBean
    InfluxWriterService influxWriterService;

    /** The {@code @MockitoBean} from {@link AbstractIntegrationTest}. */
    @Autowired
    JwtDecoder jwtDecoder;

    @LocalServerPort
    int port;

    private static WebSocketStompClient stompClient;

    @BeforeAll
    static void startStompClient() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());
        stompClient.setDefaultHeartbeat(new long[] {0, 0});
    }

    @AfterAll
    static void stopStompClient() {
        if (stompClient != null) {
            stompClient.stop();
        }
    }

    @Test
    void connectWithoutToken_isAccepted() throws Exception {
        StompSession session = connect();
        try {
            assertThat(session.isConnected()).as("session without a token").isTrue();
            verifyNoInteractions(jwtDecoder);
        } finally {
            session.disconnect();
        }
    }

    @Test
    void clientSendToTopic_isStillRejected() throws Exception {
        StompSession session = connect();
        try {
            session.send("/topic/terrarium/terra1", Map.of("name", "terra1", "light", "spoofed"));

            Awaitility.await()
                    .alias("sending session closed by the server")
                    .atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> !session.isConnected());
        } finally {
            if (session.isConnected()) {
                session.disconnect();
            }
        }
    }

    @Test
    void rawClientMessageFrame_isStillRejected() throws Exception {
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        WebSocketSession raw = stompClient.getWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                frames.add(message.getPayload());
            }
        }, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + "/ws")).get(AWAIT_SECONDS, TimeUnit.SECONDS);
        try {
            raw.sendMessage(new TextMessage("CONNECT\naccept-version:1.2\nhost:localhost\n\n\u0000"));
            assertThat(frames.poll(AWAIT_SECONDS, TimeUnit.SECONDS)).as("reply to CONNECT").startsWith("CONNECTED");

            raw.sendMessage(new TextMessage("MESSAGE\ndestination:/topic/terrarium/terra1\nsubscription:0\n"
                    + "message-id:spoof-1\ncontent-type:application/json\n\n{\"name\":\"terra1\"}\u0000"));

            Awaitility.await()
                    .alias("raw session closed by the server")
                    .atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> !raw.isOpen());
        } finally {
            if (raw.isOpen()) {
                raw.close();
            }
        }
    }

    private StompSession connect() throws Exception {
        return stompClient
                .connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
                .get(AWAIT_SECONDS, TimeUnit.SECONDS);
    }
}
