/*
 *  Copyright 2021-2025 gematik GmbH
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
 * ******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 */

package de.gematik.test.tiger.testenvmgr.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.common.data.config.tigerproxy.TigerConfigurationRoute;
import de.gematik.test.tiger.testenvmgr.TigerTestEnvMgr;
import de.gematik.test.tiger.testenvmgr.junit.TigerTest;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.client.ClientProperties;
import org.glassfish.tyrus.client.SslEngineConfigurator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.Banner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
class TyrusWebSocketTest {

  @ParameterizedTest(name = "routeTarget={0}")
  @CsvSource({"127.0.0.1", "localhost"})
  @SneakyThrows
  @TigerTest(
      tigerYaml =
          """
          tigerProxy:
            proxyPort: ${free.port.0}
            tls.masterSecretsFile: masterSecrets.txt
            activateRbelParsingFor:
              - websocket
          """,
      skipEnvironmentSetup = true)
  void testWebSocketWithProxy(String routeTarget, TigerTestEnvMgr tigerTestEnvMgr) {
    int backendPort = TigerGlobalConfiguration.readIntegerOptional("free.port.1").orElseThrow();

    try (ConfigurableApplicationContext appContext =
        new SpringApplicationBuilder()
            .bannerMode(Banner.Mode.OFF)
            .properties(Map.of("server.port", backendPort))
            .sources(TestApplication.class)
            .run()) {
      await()
          .atMost(Duration.ofSeconds(10))
          .pollInterval(Duration.ofMillis(100))
          .until(appContext::isRunning);

      tigerTestEnvMgr.setUpEnvironment();
      var localProxy = tigerTestEnvMgr.getLocalTigerProxyOptional().orElseThrow();
      localProxy.addRoute(
          TigerConfigurationRoute.builder()
              .from("/")
              .to("http://" + routeTarget + ":" + backendPort)
              .matchForProxyType(false)
              .build());
      int proxyPort = localProxy.getProxyPort();

      // Use wss:// to connect via TLS through the proxy
      String wsUrl = "ws://localhost:" + backendPort + "/websocket";
      CompletableFuture<String> result = new CompletableFuture<>();

      ClientManager client = ClientManager.createClient();

      // Configure proxy
      client.getProperties().put(ClientProperties.PROXY_URI, "http://localhost:" + proxyPort);

      // Configure SSL using the Tiger proxy's SSL context
      SslEngineConfigurator sslEngineConfigurator =
          new SslEngineConfigurator(localProxy.buildSslContext());
      sslEngineConfigurator.setHostVerificationEnabled(false);
      client.getProperties().put(ClientProperties.SSL_ENGINE_CONFIGURATOR, sslEngineConfigurator);

      ClientEndpointConfig config = ClientEndpointConfig.Builder.create().build();

      Session session =
          client.connectToServer(
              new Endpoint() {
                @Override
                public void onOpen(Session session, EndpointConfig endpointConfig) {
                  session.addMessageHandler(
                      new MessageHandler.Whole<String>() {
                        @Override
                        public void onMessage(String message) {
                          if (message.contains("Hello, Tiger!")) {
                            result.complete(message);
                          }
                        }
                      });
                  try {
                    session.getBasicRemote().sendText("Tiger");
                  } catch (Exception e) {
                    result.completeExceptionally(e);
                  }
                }

                @Override
                public void onClose(Session session, CloseReason closeReason) {
                  if (!result.isDone()) {
                    result.completeExceptionally(
                        new RuntimeException("WebSocket closed: " + closeReason.getReasonPhrase()));
                  }
                }

                @Override
                public void onError(Session session, Throwable thr) {
                  result.completeExceptionally(thr);
                }
              },
              config,
              URI.create(wsUrl));

      // Wait for the greeting
      assertThat(result.get(5, TimeUnit.SECONDS)).contains("Hello, Tiger!");

      session.close();
    }
  }

  @SpringBootApplication
  @Import({TyrusWebSocketTest.WebSocketConfig.class})
  static class TestApplication {}

  @Configuration
  @EnableWebSocket
  static class WebSocketConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
      registry.addHandler(new EchoWebSocketHandler(), "/websocket").setAllowedOrigins("*");
    }
  }

  static class EchoWebSocketHandler extends TextWebSocketHandler {
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message)
        throws Exception {
      String payload = message.getPayload();
      session.sendMessage(new TextMessage("Hello, " + payload + "!"));
    }
  }
}
