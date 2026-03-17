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
package de.gematik.test.tiger.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.gematik.rbellogger.data.core.RbelRequestFacet;
import de.gematik.rbellogger.data.core.RbelResponseFacet;
import de.gematik.rbellogger.facets.http.RbelHttpRequestFacet;
import de.gematik.rbellogger.facets.http2.Http2FrameType;
import de.gematik.rbellogger.facets.http2.RbelHttp2FrameFacet;
import de.gematik.rbellogger.testutil.RbelElementAssertion;
import de.gematik.test.tiger.common.data.config.tigerproxy.TigerConfigurationRoute;
import de.gematik.test.tiger.common.data.config.tigerproxy.TigerProxyConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

@Slf4j
class TestHttp2 extends AbstractTigerProxyTest {

  @SneakyThrows
  @Test
  void testHttp2ViaTls_shouldReturnSuccessAndBeRecordedInProxy() {
    spawnTigerProxyWithDefaultRoutesAndWith(new TigerProxyConfiguration());

    final HttpResponse<String> response =
        HttpClient.newBuilder()
            .sslContext(tigerProxy.buildSslContext())
            .version(Version.HTTP_2)
            .build()
            .send(
                HttpRequest.newBuilder()
                    .uri(new URI("https://localhost:" + tigerProxy.getProxyPort() + "/foobar"))
                    .version(Version.HTTP_2)
                    .GET()
                    .build(),
                BodyHandlers.ofString());

    log.info("Response version: {}, status: {}", response.version(), response.statusCode());
    assertThat(response.statusCode()).isEqualTo(666);
    assertThat(response.body()).contains("foo");
    assertThat(response.version())
        .describedAs("Expected HTTP/2 response. If HTTP/1.1, ALPN negotiation may have failed.")
        .isEqualTo(Version.HTTP_2);

    tigerProxy.waitForAllCurrentMessagesToBeParsed();
    assertThat(tigerProxy.getRbelMessagesList()).hasSizeGreaterThanOrEqualTo(2);

    // Verify HTTP/2.0 is correctly recorded in rbel (not HTTP/1.1)
    RbelElementAssertion.assertThat(tigerProxy.getRbelMessagesList().get(0))
        .extractChildWithPath("$.httpVersion")
        .hasStringContentEqualTo("HTTP/2.0");
    RbelElementAssertion.assertThat(tigerProxy.getRbelMessagesList().get(1))
        .extractChildWithPath("$.httpVersion")
        .hasStringContentEqualTo("HTTP/2.0");
  }

  @SneakyThrows
  @Test
  void testHttp2Plain_shouldReturnSuccessAndBeRecordedInProxy() {
    spawnTigerProxyWithDefaultRoutesAndWith(new TigerProxyConfiguration());

    final HttpResponse<String> response =
        HttpClient.newBuilder()
            .version(Version.HTTP_2)
            .build()
            .send(
                HttpRequest.newBuilder()
                    .uri(new URI("http://localhost:" + tigerProxy.getProxyPort() + "/foobar"))
                    .version(Version.HTTP_2)
                    .GET()
                    .build(),
                BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(666);
    assertThat(response.body()).contains("foo");

    tigerProxy.waitForAllCurrentMessagesToBeParsed();
    assertThat(tigerProxy.getRbelMessagesList()).hasSizeGreaterThanOrEqualTo(2);
  }

  @SneakyThrows
  @Test
  void testHttp2ViaTls_multipleRequests_shouldAllBeRecorded() {
    spawnTigerProxyWithDefaultRoutesAndWith(new TigerProxyConfiguration());

    HttpClient client =
        HttpClient.newBuilder()
            .sslContext(tigerProxy.buildSslContext())
            .version(Version.HTTP_2)
            .build();

    for (int i = 0; i < 3; i++) {
      final HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder()
                  .uri(new URI("https://localhost:" + tigerProxy.getProxyPort() + "/foobar"))
                  .version(Version.HTTP_2)
                  .GET()
                  .build(),
              BodyHandlers.ofString());

      assertThat(response.version()).isEqualTo(Version.HTTP_2);
      assertThat(response.statusCode()).isEqualTo(666);
    }

    tigerProxy.waitForAllCurrentMessagesToBeParsed();
    // 3 request/response pairs = 6 messages
    assertThat(tigerProxy.getRbelMessagesList()).hasSizeGreaterThanOrEqualTo(6);
  }

  @SneakyThrows
  @Test
  void testHttp2ViaTls_postWithBody_shouldForwardBodyCorrectly() {
    spawnTigerProxyWithDefaultRoutesAndWith(new TigerProxyConfiguration());

    String requestBody = "Hello HTTP/2 world!";

    final HttpResponse<String> response =
        HttpClient.newBuilder()
            .sslContext(tigerProxy.buildSslContext())
            .version(Version.HTTP_2)
            .build()
            .send(
                HttpRequest.newBuilder()
                    .uri(new URI("https://localhost:" + tigerProxy.getProxyPort() + "/echo"))
                    .version(Version.HTTP_2)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build(),
                BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo(requestBody);

    tigerProxy.waitForAllCurrentMessagesToBeParsed();
    assertThat(tigerProxy.getRbelMessagesList()).hasSizeGreaterThanOrEqualTo(2);
  }

  @SneakyThrows
  @Test
  void testHttp2ViaTls_headersShouldBePreserved() {
    spawnTigerProxyWithDefaultRoutesAndWith(new TigerProxyConfiguration());

    final HttpResponse<String> response =
        HttpClient.newBuilder()
            .sslContext(tigerProxy.buildSslContext())
            .version(Version.HTTP_2)
            .build()
            .send(
                HttpRequest.newBuilder()
                    .uri(new URI("https://localhost:" + tigerProxy.getProxyPort() + "/foobar"))
                    .version(Version.HTTP_2)
                    .header("X-Custom-Header", "custom-value")
                    .GET()
                    .build(),
                BodyHandlers.ofString());

    assertThat(response.version()).isEqualTo(Version.HTTP_2);
    assertThat(response.statusCode()).isEqualTo(666);
    assertThat(response.headers().firstValue("foo")).hasValue("bar1");

    tigerProxy.waitForAllCurrentMessagesToBeParsed();
    RbelElementAssertion.assertThat(tigerProxy.getRbelMessagesList().get(0))
        .extractChildWithPath("$.header.x-custom-header")
        .hasStringContentEqualTo("custom-value");
  }

  @SneakyThrows
  @Test
  void testHttp2ViaTls_rbelMessagesShouldContainCorrectPathAndBody() {
    spawnTigerProxyWithDefaultRoutesAndWith(new TigerProxyConfiguration());

    final HttpResponse<String> response =
        HttpClient.newBuilder()
            .sslContext(tigerProxy.buildSslContext())
            .version(Version.HTTP_2)
            .build()
            .send(
                HttpRequest.newBuilder()
                    .uri(
                        new URI(
                            "https://localhost:" + tigerProxy.getProxyPort() + "/foobar?foo=bar"))
                    .version(Version.HTTP_2)
                    .GET()
                    .build(),
                BodyHandlers.ofString());

    assertThat(response.version()).isEqualTo(Version.HTTP_2);

    tigerProxy.waitForAllCurrentMessagesToBeParsed();
    // request
    RbelElementAssertion.assertThat(tigerProxy.getRbelMessagesList().get(0))
        .extractChildWithPath("$.path.basicPath")
        .hasStringContentEqualTo("/foobar");
    // response body
    RbelElementAssertion.assertThat(tigerProxy.getRbelMessagesList().get(1))
        .extractChildWithPath("$.body")
        .hasStringContentEqualTo("{\"foo\":\"bar\"}");
  }

  @SneakyThrows
  @Test
  void testHttp2_withH2Backend_shouldProxyEndToEnd() {
    H2TestServer h2Backend = H2TestServer.h2c(0);
    try {
      h2Backend.start();

      TigerProxyConfiguration config = new TigerProxyConfiguration();
      spawnTigerProxyWith(config);
      tigerProxy.addRoute(
          TigerConfigurationRoute.builder()
              .from("/")
              .to("http://localhost:" + h2Backend.getPort())
              .build());

      final HttpResponse<String> response =
          HttpClient.newBuilder()
              .sslContext(tigerProxy.buildSslContext())
              .version(Version.HTTP_2)
              .build()
              .send(
                  HttpRequest.newBuilder()
                      .uri(new URI("https://localhost:" + tigerProxy.getProxyPort() + "/h2test"))
                      .version(Version.HTTP_2)
                      .GET()
                      .build(),
                  BodyHandlers.ofString());

      assertThat(response.version()).isEqualTo(Version.HTTP_2);
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("h2");

      assertThat(h2Backend.getRequestsReceived()).hasValue(1);

      tigerProxy.waitForAllCurrentMessagesToBeParsed();
      assertThat(tigerProxy.getRbelMessagesList()).hasSizeGreaterThanOrEqualTo(2);
    } finally {
      h2Backend.close();
    }
  }

  @SneakyThrows
  @Test
  void testHttp2ViaTls_forwardProxy_shouldPassthroughViaCONNECT() {
    // Use a real h2+TLS backend (WireMock doesn't support HTTP/2 ALPN)
    H2TestServer h2TlsBackend = H2TestServer.h2Tls(0);
    try {
      h2TlsBackend.start();

      // No routes configured — the proxy must use CONNECT forwarding.
      // Enable CONNECT message logging so we can verify the CONNECT request appears in rbel.
      spawnTigerProxyWith(TigerProxyConfiguration.builder().logConnectMessages(true).build());

      // HttpClient with a ProxySelector sends CONNECT to the proxy for HTTPS targets.
      final HttpResponse<String> response =
          HttpClient.newBuilder()
              .sslContext(tigerProxy.buildSslContext())
              .proxy(
                  java.net.ProxySelector.of(
                      new java.net.InetSocketAddress("localhost", tigerProxy.getProxyPort())))
              .version(Version.HTTP_2)
              .build()
              .send(
                  HttpRequest.newBuilder()
                      .uri(new URI("https://localhost:" + h2TlsBackend.getPort() + "/foobar"))
                      .version(Version.HTTP_2)
                      .GET()
                      .build(),
                  BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.version())
          .describedAs(
              "Expected HTTP/2 end-to-end through CONNECT tunnel. "
                  + "If HTTP/1.1, the proxy is downgrading the protocol.")
          .isEqualTo(Version.HTTP_2);
      assertThat(response.body()).contains("h2");
      assertThat(h2TlsBackend.getRequestsReceived()).hasValue(1);

      tigerProxy.waitForAllCurrentMessagesToBeParsed();
      // message 0 = CONNECT request, message 1 = actual GET, message 2 = actual response
      assertThat(tigerProxy.getRbelMessagesList()).hasSizeGreaterThanOrEqualTo(3);

      // Verify the CONNECT request is logged
      RbelElementAssertion.assertThat(tigerProxy.getRbelMessagesList().get(0))
          .extractChildWithPath("$.method")
          .hasStringContentEqualTo("CONNECT");
    } finally {
      h2TlsBackend.close();
    }
  }

  @SneakyThrows
  @Test
  void testHttp2ViaTls_withFrameParsing_shouldProduceFrameElements() {
    spawnTigerProxyWithDefaultRoutesAndWith(
        TigerProxyConfiguration.builder().activateRbelParsingFor(List.of("http2frames")).build());

    final HttpResponse<String> response =
        HttpClient.newBuilder()
            .sslContext(tigerProxy.buildSslContext())
            .version(Version.HTTP_2)
            .build()
            .send(
                HttpRequest.newBuilder()
                    .uri(new URI("https://localhost:" + tigerProxy.getProxyPort() + "/foobar"))
                    .version(Version.HTTP_2)
                    .GET()
                    .build(),
                BodyHandlers.ofString());

    assertThat(response.version()).isEqualTo(Version.HTTP_2);
    assertThat(response.statusCode()).isEqualTo(666);

    tigerProxy.waitForAllCurrentMessagesToBeParsed();

    var frames =
        tigerProxy.getRbelMessagesList().stream()
            .filter(msg -> msg.hasFacet(RbelHttp2FrameFacet.class))
            .toList();
    // With frame parsing active, we should have raw h2 frame messages from the tap handler
    assertThat(frames).isNotEmpty();

    // Verify frame structure: every frame must have a frameType and streamId
    frames.forEach(
        frame -> {
          var facet = frame.getFacetOrFail(RbelHttp2FrameFacet.class);
          assertThat(facet.getFrameType()).isNotNull();
          assertThat(facet.getStreamId()).isNotNull();
          // frameType should be a known Http2FrameType
          assertThat(facet.getFrameType().seekValue(Http2FrameType.class)).isPresent();
          // Every frame should be categorized as request or response
          assertThat(
                  frame.hasFacet(RbelRequestFacet.class) || frame.hasFacet(RbelResponseFacet.class))
              .describedAs("Frame %s should have a request or response facet", facet.getFrameType())
              .isTrue();
        });

    // Verify we have both request and response frames
    assertThat(frames.stream().anyMatch(f -> f.hasFacet(RbelRequestFacet.class)))
        .describedAs("Expected at least one request frame (client → proxy)")
        .isTrue();
    assertThat(frames.stream().anyMatch(f -> f.hasFacet(RbelResponseFacet.class)))
        .describedAs("Expected at least one response frame (proxy → backend → proxy)")
        .isTrue();

    // We expect at least a SETTINGS frame (connection setup)
    assertThat(
            frames.stream()
                .anyMatch(
                    f ->
                        f.getFacetOrFail(RbelHttp2FrameFacet.class)
                                .getFrameType()
                                .seekValue(Http2FrameType.class)
                                .orElse(null)
                            == Http2FrameType.SETTINGS))
        .describedAs("Expected at least one SETTINGS frame")
        .isTrue();
  }

  @SneakyThrows
  @Test
  void testHttp2ViaTls_largeBody_shouldBeReassembledCorrectly() {
    spawnTigerProxyWithDefaultRoutesAndWith(
        TigerProxyConfiguration.builder().activateRbelParsingFor(List.of("http2frames")).build());

    // 64 KB body — exceeds the default HTTP/2 MAX_FRAME_SIZE (16 KB),
    // so it will be split into multiple DATA frames
    String largeBody = "X".repeat(64 * 1024);

    final HttpResponse<String> response =
        HttpClient.newBuilder()
            .sslContext(tigerProxy.buildSslContext())
            .version(Version.HTTP_2)
            .build()
            .send(
                HttpRequest.newBuilder()
                    .uri(new URI("https://localhost:" + tigerProxy.getProxyPort() + "/echo"))
                    .version(Version.HTTP_2)
                    .POST(HttpRequest.BodyPublishers.ofString(largeBody))
                    .build(),
                BodyHandlers.ofString());

    assertThat(response.version()).isEqualTo(Version.HTTP_2);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).hasSize(largeBody.length());
    assertThat(response.body()).isEqualTo(largeBody);

    tigerProxy.waitForAllCurrentMessagesToBeParsed();
    // With frame parsing active, raw h2 frames appear before the reassembled HTTP messages.
    // Find the actual HTTP POST request by looking for the method.
    var httpRequest =
        tigerProxy.getRbelMessagesList().stream()
            .filter(msg -> msg.hasFacet(RbelHttpRequestFacet.class))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No HTTP request found in rbel messages"));
    RbelElementAssertion.assertThat(httpRequest)
        .extractChildWithPath("$.body")
        .hasStringContentEqualTo(largeBody);

    // Verify that the body was split into multiple DATA frames
    long dataFrameCount =
        tigerProxy.getRbelMessagesList().stream()
            .filter(msg -> msg.hasFacet(RbelHttp2FrameFacet.class))
            .filter(
                msg ->
                    msg.getFacetOrFail(RbelHttp2FrameFacet.class)
                            .getFrameType()
                            .seekValue(Http2FrameType.class)
                            .orElse(null)
                        == Http2FrameType.DATA)
            .count();
    assertThat(dataFrameCount)
        .describedAs("64 KB body should produce multiple DATA frames (default MAX_FRAME_SIZE=16KB)")
        .isGreaterThan(1);

    // Verify that WINDOW_UPDATE frames were exchanged (flow control for the large body)
    long windowUpdateCount =
        tigerProxy.getRbelMessagesList().stream()
            .filter(msg -> msg.hasFacet(RbelHttp2FrameFacet.class))
            .filter(
                msg ->
                    msg.getFacetOrFail(RbelHttp2FrameFacet.class)
                            .getFrameType()
                            .seekValue(Http2FrameType.class)
                            .orElse(null)
                        == Http2FrameType.WINDOW_UPDATE)
            .count();
    assertThat(windowUpdateCount)
        .describedAs("64 KB transfer should trigger WINDOW_UPDATE frames for flow control")
        .isGreaterThan(0);
  }

  @SneakyThrows
  @Test
  void testHttp2ViaTls_backendOnlySpeaksH1_shouldStillWork() {
    // The WireMock backend only speaks HTTP/1.1. The proxy should handle the protocol
    // mismatch: client speaks h2 to the proxy, proxy speaks h1 to the backend.
    spawnTigerProxyWithDefaultRoutesAndWith(new TigerProxyConfiguration());

    final HttpResponse<String> response =
        HttpClient.newBuilder()
            .sslContext(tigerProxy.buildSslContext())
            .version(Version.HTTP_2)
            .build()
            .send(
                HttpRequest.newBuilder()
                    .uri(new URI("https://localhost:" + tigerProxy.getProxyPort() + "/foobar"))
                    .version(Version.HTTP_2)
                    .GET()
                    .build(),
                BodyHandlers.ofString());

    // The client-to-proxy leg is h2, proxy-to-backend is h1.
    // The response back to the client should still be h2.
    assertThat(response.version()).isEqualTo(Version.HTTP_2);
    assertThat(response.statusCode()).isEqualTo(666);
    assertThat(response.body()).contains("foo");

    tigerProxy.waitForAllCurrentMessagesToBeParsed();
    assertThat(tigerProxy.getRbelMessagesList()).hasSizeGreaterThanOrEqualTo(2);
  }

  @SneakyThrows
  @Test
  void testMixedH1AndH2_onSameProxy_shouldBothWork() {
    spawnTigerProxyWithDefaultRoutesAndWith(new TigerProxyConfiguration());

    // First: HTTP/2 request
    final HttpResponse<String> h2Response =
        HttpClient.newBuilder()
            .sslContext(tigerProxy.buildSslContext())
            .version(Version.HTTP_2)
            .build()
            .send(
                HttpRequest.newBuilder()
                    .uri(new URI("https://localhost:" + tigerProxy.getProxyPort() + "/foobar"))
                    .version(Version.HTTP_2)
                    .GET()
                    .build(),
                BodyHandlers.ofString());

    assertThat(h2Response.version()).isEqualTo(Version.HTTP_2);
    assertThat(h2Response.statusCode()).isEqualTo(666);

    // Second: HTTP/1.1 request via a fresh client (must be a new connection, not reusing the h2
    // one)
    final HttpResponse<String> h1Response =
        HttpClient.newBuilder()
            .sslContext(tigerProxy.buildSslContext())
            .version(Version.HTTP_1_1)
            .build()
            .send(
                HttpRequest.newBuilder()
                    .uri(new URI("https://localhost:" + tigerProxy.getProxyPort() + "/foobar"))
                    .version(Version.HTTP_1_1)
                    .GET()
                    .build(),
                BodyHandlers.ofString());

    assertThat(h1Response.version()).isEqualTo(Version.HTTP_1_1);
    assertThat(h1Response.statusCode()).isEqualTo(666);
    assertThat(h1Response.body()).contains("foo");

    // Third: HTTP/2 again to confirm it still works after h1
    final HttpResponse<String> h2Response2 =
        HttpClient.newBuilder()
            .sslContext(tigerProxy.buildSslContext())
            .version(Version.HTTP_2)
            .build()
            .send(
                HttpRequest.newBuilder()
                    .uri(new URI("https://localhost:" + tigerProxy.getProxyPort() + "/foobar"))
                    .version(Version.HTTP_2)
                    .GET()
                    .build(),
                BodyHandlers.ofString());

    assertThat(h2Response2.version()).isEqualTo(Version.HTTP_2);
    assertThat(h2Response2.statusCode()).isEqualTo(666);

    tigerProxy.waitForAllCurrentMessagesToBeParsed();
    // 3 request/response pairs = 6 messages
    assertThat(tigerProxy.getRbelMessagesList()).hasSizeGreaterThanOrEqualTo(6);
  }

  // ---- Medium priority edge cases ----

  @SneakyThrows
  @Test
  void testHttp2ViaTls_redirectResponse_shouldBeRecordedCorrectly() {
    spawnTigerProxyWithDefaultRoutesAndWith(new TigerProxyConfiguration());

    // /forward returns 301 -> /foobar which returns 666
    // Use followRedirects(NEVER) so we can inspect the 301 itself
    final HttpResponse<String> response =
        HttpClient.newBuilder()
            .sslContext(tigerProxy.buildSslContext())
            .version(Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
            .send(
                HttpRequest.newBuilder()
                    .uri(new URI("https://localhost:" + tigerProxy.getProxyPort() + "/forward"))
                    .version(Version.HTTP_2)
                    .GET()
                    .build(),
                BodyHandlers.ofString());

    assertThat(response.version()).isEqualTo(Version.HTTP_2);
    assertThat(response.statusCode()).isEqualTo(301);

    tigerProxy.waitForAllCurrentMessagesToBeParsed();
    assertThat(tigerProxy.getRbelMessagesList()).hasSizeGreaterThanOrEqualTo(2);
    RbelElementAssertion.assertThat(tigerProxy.getRbelMessagesList().get(1))
        .extractChildWithPath("$.responseCode")
        .hasStringContentEqualTo("301");
  }

  @SneakyThrows
  @Test
  void testHttp2ViaTls_binaryResponseBody_shouldBePreserved() {
    spawnTigerProxyWithDefaultRoutesAndWith(new TigerProxyConfiguration());

    // POST to /foobar returns binaryMessageContent (set up in AbstractTigerProxyTest)
    final HttpResponse<byte[]> response =
        HttpClient.newBuilder()
            .sslContext(tigerProxy.buildSslContext())
            .version(Version.HTTP_2)
            .build()
            .send(
                HttpRequest.newBuilder()
                    .uri(new URI("https://localhost:" + tigerProxy.getProxyPort() + "/foobar"))
                    .version(Version.HTTP_2)
                    .POST(HttpRequest.BodyPublishers.ofString("trigger"))
                    .build(),
                BodyHandlers.ofByteArray());

    assertThat(response.version()).isEqualTo(Version.HTTP_2);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo(binaryMessageContent);

    tigerProxy.waitForAllCurrentMessagesToBeParsed();
    assertThat(tigerProxy.getRbelMessagesList()).hasSizeGreaterThanOrEqualTo(2);
  }

  @SneakyThrows
  @Test
  void testHttp2Plain_withFrameParsing_shouldProduceFrameElements() {
    // Java's HttpClient doesn't support h2c prior knowledge, so we use a Netty-based client
    // that sends the HTTP/2 connection preface directly.
    spawnTigerProxyWithDefaultRoutesAndWith(
        TigerProxyConfiguration.builder().activateRbelParsingFor(List.of("http2frames")).build());

    try (H2cTestClient client = new H2cTestClient()) {
      var response = client.sendGet("localhost", tigerProxy.getProxyPort(), "/foobar");
      assertThat(response.status().code()).isEqualTo(666);
    }

    tigerProxy.waitForAllCurrentMessagesToBeParsed();

    var frames =
        tigerProxy.getRbelMessagesList().stream()
            .filter(msg -> msg.hasFacet(RbelHttp2FrameFacet.class))
            .toList();
    assertThat(frames).describedAs("Expected h2c frame-level messages").isNotEmpty();

    assertThat(
            frames.stream()
                .anyMatch(
                    f ->
                        f.getFacetOrFail(RbelHttp2FrameFacet.class)
                                .getFrameType()
                                .seekValue(Http2FrameType.class)
                                .orElse(null)
                            == Http2FrameType.SETTINGS))
        .describedAs("Expected at least one SETTINGS frame in h2c")
        .isTrue();
  }

  @SneakyThrows
  @Test
  void testHttp2ViaTls_notFoundResponse_shouldBeRecorded() {
    spawnTigerProxyWithDefaultRoutesAndWith(new TigerProxyConfiguration());

    // Request a path that has no WireMock stub — WireMock returns 404
    final HttpResponse<String> response =
        HttpClient.newBuilder()
            .sslContext(tigerProxy.buildSslContext())
            .version(Version.HTTP_2)
            .build()
            .send(
                HttpRequest.newBuilder()
                    .uri(new URI("https://localhost:" + tigerProxy.getProxyPort() + "/nonexistent"))
                    .version(Version.HTTP_2)
                    .GET()
                    .build(),
                BodyHandlers.ofString());

    assertThat(response.version()).isEqualTo(Version.HTTP_2);
    assertThat(response.statusCode()).isEqualTo(404);

    tigerProxy.waitForAllCurrentMessagesToBeParsed();
    assertThat(tigerProxy.getRbelMessagesList()).hasSizeGreaterThanOrEqualTo(2);
    RbelElementAssertion.assertThat(tigerProxy.getRbelMessagesList().get(1))
        .extractChildWithPath("$.responseCode")
        .hasStringContentEqualTo("404");
  }

  @SneakyThrows
  @Test
  void testHttp2ViaTls_redirectFollowed_shouldRecordBothHops() {
    spawnTigerProxyWithDefaultRoutesAndWith(new TigerProxyConfiguration());

    // /forward returns 301 -> /foobar; with NORMAL redirect, client follows automatically
    final HttpResponse<String> response =
        HttpClient.newBuilder()
            .sslContext(tigerProxy.buildSslContext())
            .version(Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
            .send(
                HttpRequest.newBuilder()
                    .uri(new URI("https://localhost:" + tigerProxy.getProxyPort() + "/forward"))
                    .version(Version.HTTP_2)
                    .GET()
                    .build(),
                BodyHandlers.ofString());

    assertThat(response.version()).isEqualTo(Version.HTTP_2);
    assertThat(response.statusCode()).isEqualTo(666);
    assertThat(response.body()).contains("foo");

    tigerProxy.waitForAllCurrentMessagesToBeParsed();
    // 2 request/response pairs: /forward (301) + /foobar (666) = 4 messages
    assertThat(tigerProxy.getRbelMessagesList()).hasSizeGreaterThanOrEqualTo(4);
  }

  // ---- Low priority edge cases ----

  @SneakyThrows
  @Test
  void testHttp2_backendSendsGoaway_firstRequestShouldStillSucceed() {
    // Backend sends GOAWAY after responding. Verify the response is still delivered correctly.
    // TODO: The proxy currently does not handle GOAWAY by closing the backend connection.
    //  A second request on the same proxy-to-backend connection will stall because the backend
    //  rejected new streams. The proxy's connection pool should discard connections that received
    //  GOAWAY and open fresh ones for subsequent requests.
    H2TestServer goawayBackend = H2TestServer.h2cWithGoaway(0);
    try {
      goawayBackend.start();

      TigerProxyConfiguration config = new TigerProxyConfiguration();
      spawnTigerProxyWith(config);
      tigerProxy.addRoute(
          TigerConfigurationRoute.builder()
              .from("/")
              .to("http://localhost:" + goawayBackend.getPort())
              .build());

      final HttpResponse<String> response =
          HttpClient.newBuilder()
              .sslContext(tigerProxy.buildSslContext())
              .version(Version.HTTP_2)
              .build()
              .send(
                  HttpRequest.newBuilder()
                      .uri(new URI("https://localhost:" + tigerProxy.getProxyPort() + "/test1"))
                      .version(Version.HTTP_2)
                      .GET()
                      .build(),
                  BodyHandlers.ofString());

      assertThat(response.version()).isEqualTo(Version.HTTP_2);
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("goaway");
      assertThat(goawayBackend.getRequestsReceived()).hasValue(1);

      tigerProxy.waitForAllCurrentMessagesToBeParsed();
      assertThat(tigerProxy.getRbelMessagesList()).hasSizeGreaterThanOrEqualTo(2);
    } finally {
      goawayBackend.close();
    }
  }

  @SneakyThrows
  @Test
  void testHttp2ViaTls_withFrameParsing_goaway_shouldBeRecordedAsFrame() {
    H2TestServer goawayBackend = H2TestServer.h2cWithGoaway(0);
    try {
      goawayBackend.start();

      spawnTigerProxyWith(
          TigerProxyConfiguration.builder().activateRbelParsingFor(List.of("http2frames")).build());
      tigerProxy.addRoute(
          TigerConfigurationRoute.builder()
              .from("/")
              .to("http://localhost:" + goawayBackend.getPort())
              .build());

      final HttpResponse<String> response =
          HttpClient.newBuilder()
              .sslContext(tigerProxy.buildSslContext())
              .version(Version.HTTP_2)
              .build()
              .send(
                  HttpRequest.newBuilder()
                      .uri(new URI("https://localhost:" + tigerProxy.getProxyPort() + "/test"))
                      .version(Version.HTTP_2)
                      .GET()
                      .build(),
                  BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(200);

      // GOAWAY may arrive asynchronously — give it a moment
      Awaitility.await()
          .atMost(5, TimeUnit.SECONDS)
          .untilAsserted(
              () -> {
                tigerProxy.waitForAllCurrentMessagesToBeParsed();
                var goawayFrames =
                    tigerProxy.getRbelMessagesList().stream()
                        .filter(msg -> msg.hasFacet(RbelHttp2FrameFacet.class))
                        .filter(
                            msg ->
                                msg.getFacetOrFail(RbelHttp2FrameFacet.class)
                                        .getFrameType()
                                        .seekValue(Http2FrameType.class)
                                        .orElse(null)
                                    == Http2FrameType.GOAWAY)
                        .toList();
                assertThat(goawayFrames)
                    .describedAs("Expected at least one GOAWAY frame from the backend")
                    .isNotEmpty();
              });
    } finally {
      goawayBackend.close();
    }
  }

  @SneakyThrows
  @Test
  void testHttp2ViaTls_backendConnectionReset_shouldNotCrashProxy() {
    // If the backend connection is reset mid-stream, the proxy should return an error
    // to the client rather than crashing.
    spawnTigerProxyWithDefaultRoutesAndWith(new TigerProxyConfiguration());

    // /error endpoint is configured to reset the connection (Fault.CONNECTION_RESET_BY_PEER)
    HttpClient client =
        HttpClient.newBuilder()
            .sslContext(tigerProxy.buildSslContext())
            .version(Version.HTTP_2)
            .build();

    try {
      client.send(
          HttpRequest.newBuilder()
              .uri(new URI("https://localhost:" + tigerProxy.getProxyPort() + "/error"))
              .version(Version.HTTP_2)
              .GET()
              .build(),
          BodyHandlers.ofString());
      // If no exception, the proxy returned an error response — that's fine
    } catch (Exception e) {
      // Connection errors are acceptable — the important thing is that the proxy didn't crash
      log.info("Expected exception for connection-reset test: {}", e.getMessage());
    }

    // Verify the proxy is still alive by sending another request
    final HttpResponse<String> recoveryResponse =
        HttpClient.newBuilder()
            .sslContext(tigerProxy.buildSslContext())
            .version(Version.HTTP_2)
            .build()
            .send(
                HttpRequest.newBuilder()
                    .uri(new URI("https://localhost:" + tigerProxy.getProxyPort() + "/foobar"))
                    .version(Version.HTTP_2)
                    .GET()
                    .build(),
                BodyHandlers.ofString());

    assertThat(recoveryResponse.version()).isEqualTo(Version.HTTP_2);
    assertThat(recoveryResponse.statusCode()).isEqualTo(666);
  }

  // NOTE:
  // Server push (PUSH_PROMISE) is not testable in our setup: Netty's HttpToHttp2ConnectionHandler
  // does not expose an API to send PUSH_PROMISE frames from a server, and the
  // InboundHttp2ToHttpAdapter on the client side silently ignores them. Since server push is
  // deprecated in RFC 9113 and major browsers/clients disable it, this is acceptable.
}
