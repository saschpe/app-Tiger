/*
 *
 * Copyright 2021-2025 gematik GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 */
package de.gematik.test.tiger.proxy.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import de.gematik.test.tiger.config.ResetTigerConfiguration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

/**
 * Regression tests for the Spring CORS wildcard validation bug fixed by switching from
 * setAllowedOrigins("*") to setAllowedOriginPatterns("*") in {@link TracingEndpointConfiguration}.
 * With the old setAllowedOrigins("*"), Spring rejects cross-origin WebSocket connections when
 * allowCredentials is implied (SockJS).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@DirtiesContext
@ResetTigerConfiguration
class TracingEndpointConfigurationTest {

  @LocalServerPort private int port;

  static Stream<Arguments> crossOriginTransports() {
    return Stream.of(
        Arguments.of(
            "SockJS",
            new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))),
            "http"),
        Arguments.of("NativeWebSocket", new StandardWebSocketClient(), "ws"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("crossOriginTransports")
  void stompConnectionWithCrossOrigin_shouldSucceed(
      String displayName, WebSocketClient wsClient, String scheme) throws Exception {
    var stompClient = new WebSocketStompClient(wsClient);
    stompClient.setMessageConverter(new MappingJackson2MessageConverter());

    var url = scheme + "://localhost:" + port + "/tracing";

    var headers = new WebSocketHttpHeaders();
    headers.setOrigin("http://some-other-origin.example.com");

    var session =
        stompClient
            .connectAsync(url, headers, new StompHeaders(), new StompSessionHandlerAdapter() {})
            .get(5, TimeUnit.SECONDS);
    try {
      assertThat(session.isConnected()).isTrue();
    } finally {
      if (session != null && session.isConnected()) {
        session.disconnect();
      }
      stompClient.stop();
    }
  }
}
