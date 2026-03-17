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
package de.gematik.rbellogger.converter;

import static de.gematik.rbellogger.testutil.RbelElementAssertion.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import de.gematik.rbellogger.RbelConverter;
import de.gematik.rbellogger.RbelLogger;
import de.gematik.rbellogger.configuration.RbelConfiguration;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.data.RbelMessageMetadata;
import de.gematik.rbellogger.facets.http2.Http2FrameType;
import de.gematik.rbellogger.facets.http2.RbelHttp2FrameFacet;
import de.gematik.rbellogger.renderer.RbelHtmlRenderer;
import de.gematik.rbellogger.util.RbelContent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RbelHttp2FrameConverterTest {

  private RbelConverter rbelConverter;

  @BeforeEach
  void setUp() {
    var logger =
        RbelLogger.build(new RbelConfiguration().setActivateRbelParsingFor(List.of("http2frames")));
    rbelConverter = logger.getRbelConverter();
  }

  private RbelElement parseFrame(byte[] data) {
    var element = RbelElement.builder().content(RbelContent.of(data)).build();
    return rbelConverter.parseMessage(element, new RbelMessageMetadata());
  }

  /**
   * Builds a raw HTTP/2 frame: 3-byte length + 1-byte type + 1-byte flags + 4-byte streamId +
   * payload.
   */
  private static byte[] buildFrame(int type, int flags, int streamId, byte[] payload) {
    int payloadLength = payload != null ? payload.length : 0;
    ByteBuffer buf = ByteBuffer.allocate(9 + payloadLength);
    buf.put((byte) ((payloadLength >> 16) & 0xFF));
    buf.put((byte) ((payloadLength >> 8) & 0xFF));
    buf.put((byte) (payloadLength & 0xFF));
    buf.put((byte) type);
    buf.put((byte) flags);
    buf.putInt(streamId);
    if (payload != null) {
      buf.put(payload);
    }
    return buf.array();
  }

  @Test
  void settingsFrame_shouldBeParsedCorrectly() {
    byte[] settingsPayload =
        new byte[] {0x00, 0x03, 0x00, 0x00, 0x00, 0x64}; // MAX_CONCURRENT_STREAMS = 100
    byte[] frame = buildFrame(4, 0, 0, settingsPayload);

    RbelElement result = parseFrame(frame);

    assertThat(result)
        .hasFacet(RbelHttp2FrameFacet.class)
        .hasGivenValueAtPosition("$.frameType", Http2FrameType.SETTINGS)
        .hasGivenValueAtPosition("$.streamId", 0)
        .hasChildWithPath("$.payload")
        .hasChildWithPath("$.payloadLength")
        .doesNotHaveChildWithPath("$.flags");
    assertThat(result.getSize()).isEqualTo(frame.length);
  }

  @Test
  void settingsAckFrame_shouldHaveAckFlag() {
    byte[] frame = buildFrame(4, 0x01, 0, new byte[0]);

    RbelElement result = parseFrame(frame);

    assertThat(result)
        .hasFacet(RbelHttp2FrameFacet.class)
        .hasGivenValueAtPosition("$.frameType", Http2FrameType.SETTINGS)
        .hasChildWithPath("$.flags")
        .doesNotHaveChildWithPath("$.payload")
        .doesNotHaveChildWithPath("$.payloadLength")
        .hasGivenValueAtPosition("$.flags", List.of("ACK"));
  }

  @Test
  void headersFrame_shouldBeParsedWithFlags() {
    byte[] hpackPayload = new byte[] {(byte) 0x82, (byte) 0x86};
    byte[] frame = buildFrame(1, 0x05, 1, hpackPayload);

    RbelElement result = parseFrame(frame);

    assertThat(result)
        .hasGivenValueAtPosition("$.frameType", Http2FrameType.HEADERS)
        .hasGivenValueAtPosition("$.streamId", 1)
        .hasChildWithPath("$.payload")
        .hasGivenValueAtPosition("$.flags", List.of("END_STREAM", "END_HEADERS"));
  }

  @Test
  void dataFrame_shouldBeParsed() {
    byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
    byte[] frame = buildFrame(0, 0x01, 3, body);

    RbelElement result = parseFrame(frame);

    assertThat(result)
        .hasGivenValueAtPosition("$.frameType", Http2FrameType.DATA)
        .hasGivenValueAtPosition("$.streamId", 3)
        .hasStringContentEqualToAtPosition("$.payload", "hello")
        .hasGivenValueAtPosition("$.flags", List.of("END_STREAM"));
  }

  @Test
  void windowUpdateFrame_shouldBeParsed() {
    byte[] increment = ByteBuffer.allocate(4).putInt(65535).array();
    byte[] frame = buildFrame(8, 0, 0, increment);

    RbelElement result = parseFrame(frame);

    assertThat(result)
        .hasGivenValueAtPosition("$.frameType", Http2FrameType.WINDOW_UPDATE)
        .hasGivenValueAtPosition("$.streamId", 0);
  }

  @Test
  void pingFrame_shouldBeParsed() {
    byte[] opaqueData = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
    byte[] frame = buildFrame(6, 0x01, 0, opaqueData);

    RbelElement result = parseFrame(frame);

    assertThat(result)
        .hasGivenValueAtPosition("$.frameType", Http2FrameType.PING)
        .hasGivenValueAtPosition("$.flags", List.of("ACK"));
  }

  @Test
  void goawayFrame_shouldBeParsed() {
    byte[] goawayPayload = ByteBuffer.allocate(8).putInt(0).putInt(0).array();
    byte[] frame = buildFrame(7, 0, 0, goawayPayload);

    RbelElement result = parseFrame(frame);

    assertThat(result).hasGivenValueAtPosition("$.frameType", Http2FrameType.GOAWAY);
  }

  @Test
  void rstStreamFrame_shouldBeParsed() {
    byte[] errorCode = ByteBuffer.allocate(4).putInt(2).array();
    byte[] frame = buildFrame(3, 0, 1, errorCode);

    RbelElement result = parseFrame(frame);

    assertThat(result)
        .hasGivenValueAtPosition("$.frameType", Http2FrameType.RST_STREAM)
        .hasGivenValueAtPosition("$.streamId", 1);
  }

  @Test
  void connectionPreface_shouldBeSkipped() {
    byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    byte[] settingsFrame = buildFrame(4, 0, 0, new byte[] {0x00, 0x03, 0x00, 0x00, 0x00, 0x64});
    byte[] combined = new byte[preface.length + settingsFrame.length];
    System.arraycopy(preface, 0, combined, 0, preface.length);
    System.arraycopy(settingsFrame, 0, combined, preface.length, settingsFrame.length);

    RbelElement result = parseFrame(combined);

    assertThat(result).hasGivenValueAtPosition("$.frameType", Http2FrameType.SETTINGS);
    assertThat(result.getSize()).isEqualTo(combined.length);
  }

  @Test
  void incompleteFrame_shouldNotBeParsed() {
    byte[] incomplete = new byte[] {0x00, 0x00, 0x00, 0x04, 0x00};

    RbelElement result = parseFrame(incomplete);

    assertThat(result).doesNotHaveFacet(RbelHttp2FrameFacet.class);
  }

  @Test
  void incompletePayload_shouldNotBeParsed() {
    byte[] frame = buildFrame(0, 0, 1, new byte[5]);
    frame[0] = 0;
    frame[1] = 0;
    frame[2] = 100; // corrupt length to 100

    RbelElement result = parseFrame(frame);

    assertThat(result).doesNotHaveFacet(RbelHttp2FrameFacet.class);
  }

  @Test
  void nonH2Data_shouldNotBeParsed() {
    byte[] httpData =
        "GET /foo HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    RbelElement result = parseFrame(httpData);

    assertThat(result).doesNotHaveFacet(RbelHttp2FrameFacet.class);
  }

  @Test
  void multipleFramesInBuffer_shouldParseOnlyFirst() {
    byte[] frame1 = buildFrame(4, 0, 0, new byte[] {0x00, 0x03, 0x00, 0x00, 0x00, 0x64});
    byte[] frame2 = buildFrame(4, 0x01, 0, new byte[0]);
    byte[] combined = new byte[frame1.length + frame2.length];
    System.arraycopy(frame1, 0, combined, 0, frame1.length);
    System.arraycopy(frame2, 0, combined, frame1.length, frame2.length);

    RbelElement result = parseFrame(combined);

    assertThat(result)
        .hasGivenValueAtPosition("$.frameType", Http2FrameType.SETTINGS)
        .doesNotHaveChildWithPath("$.flags");
    assertThat(result.getSize()).isEqualTo(frame1.length);
  }

  @Test
  void highStreamId_shouldBeParsedCorrectly() {
    byte[] frame = buildFrame(0, 0x01, 0x7FFFFFFF, "hi".getBytes());
    frame[5] = (byte) (frame[5] | 0x80); // set reserved bit

    RbelElement result = parseFrame(frame);

    assertThat(result).hasGivenValueAtPosition("$.streamId", 0x7FFFFFFF);
  }

  @Test
  void shortDescription_shouldContainFrameTypeAndStreamId() {
    byte[] frame = buildFrame(1, 0x04, 5, new byte[] {(byte) 0x82});

    RbelElement result = parseFrame(frame);

    assertThat(result.printShortDescription()).contains("HEADERS").contains("5");
  }

  @Test
  @SneakyThrows
  void htmlRendering_shouldNotThrow() {
    byte[] frame = buildFrame(1, 0x05, 1, new byte[] {(byte) 0x82, (byte) 0x86});

    RbelElement result = parseFrame(frame);

    assertThat(result).hasFacet(RbelHttp2FrameFacet.class);
    String html = RbelHtmlRenderer.render(List.of(result));
    assertThat(html).isNotBlank().contains("HTTP/2 Frame").contains("HEADERS");
  }

  @Test
  void priorityFrame_shouldBeParsed() {
    byte[] priorityPayload =
        new byte[] {(byte) 0x80, 0x00, 0x00, 0x01, 0x10}; // dep=1, exclusive, weight=16
    byte[] frame = buildFrame(2, 0, 3, priorityPayload);

    RbelElement result = parseFrame(frame);

    assertThat(result)
        .hasGivenValueAtPosition("$.frameType", Http2FrameType.PRIORITY)
        .hasGivenValueAtPosition("$.streamId", 3);
  }

  @Test
  void headersWithPaddedFlag_shouldShowPaddedInFlags() {
    byte[] frame = buildFrame(1, 0x08, 1, new byte[] {0x00, (byte) 0x82});

    RbelElement result = parseFrame(frame);

    assertThat(result).hasGivenValueAtPosition("$.flags", List.of("PADDED"));
  }

  @Test
  void headersWithPriorityFlag_shouldShowPriorityInFlags() {
    byte[] frame = buildFrame(1, 0x20, 1, new byte[] {0x00, 0x00, 0x00, 0x00, 0x10, (byte) 0x82});

    RbelElement result = parseFrame(frame);

    assertThat(result).hasGivenValueAtPosition("$.flags", List.of("PRIORITY"));
  }

  @Test
  void emptyPayload_shouldOmitPayloadFields() {
    byte[] frame = buildFrame(4, 0x01, 0, new byte[0]); // SETTINGS ACK

    RbelElement result = parseFrame(frame);

    assertThat(result)
        .doesNotHaveChildWithPath("$.payload")
        .doesNotHaveChildWithPath("$.payloadLength");
  }

  @Test
  void messageAlreadyParsedAsHttp_shouldNotBeReparsedAsFrame() {
    byte[] frame = buildFrame(4, 0, 0, new byte[] {0x00, 0x03, 0x00, 0x00, 0x00, 0x64});
    var element = RbelElement.builder().content(RbelContent.of(frame)).build();
    element.addFacet(
        de.gematik.rbellogger.facets.http.RbelHttpMessageFacet.builder()
            .header(new RbelElement(new byte[0], element))
            .body(new RbelElement(new byte[0], element))
            .httpVersion(new RbelElement("HTTP/1.1".getBytes(), element))
            .build());
    rbelConverter.parseMessage(element, new RbelMessageMetadata());

    assertThat(element).doesNotHaveFacet(RbelHttp2FrameFacet.class);
  }
}
